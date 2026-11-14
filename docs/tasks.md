# MCP Tasks (experimental, off by default)

← [README](../README.md) · see also [Transports](./transports.md) · [2026-07-28 upgrade guide](./2026-07-28-upgrade.md)

MCP Tasks wrap a long-running `tools/call` in a durable, polled state machine so a client can
return immediately and collect the result later. They are the official
**`io.modelcontextprotocol/tasks` extension** in MCP 2026-07-28, and fast-mcp-scala keeps them
**off by default** because the spec still marks them experimental.

A client declares the extension in its per-request capabilities; the server may then return a flat
`resultType: "task"` bearer handle without per-call augmentation. Clients poll `tasks/get`, cancel
with `tasks/cancel`, and use `tasks/update` only when a task is waiting for input. `tasks/list`
and `tasks/result` belong to the 2025-11-25 compatibility adapter and are rejected (`-32601`) on
modern requests. A 2025-11-25 `params.task` on a modern `tools/call` is ignored: it neither
creates a task nor fails the call.

## Enabling per server

```scala 3 raw
import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.server.TaskSettings   // optional since 1.0.0 (root-exported); the release candidates need it

val server = McpServer(
  name = "my-server",
  settings = McpServerSettings(tasks = TaskSettings(enabled = true))
)
```

`TaskSettings` fields (`McpServerSettings.scala`):

| Field | Default | Meaning |
|---|---|---|
| `enabled` | `false` | Master switch. When false the `tasks` capability is not advertised and `params.task` is ignored. |
| `defaultTtlMs` | 1 hour | TTL applied when the requestor does not supply one. |
| `maxTtlMs` | 24 hours | Upper bound; requestor-supplied TTLs above this are clamped. A requested TTL of zero or less is refused with `-32602` (since 1.1.0). |
| `pollIntervalMs` | `Tasks.DefaultPollIntervalMs` | `pollInterval` advertised back to clients in `tasks/get` responses. |
| `maxConcurrentPerSession` | `64` | Running (non-terminal) tasks per **owner**: the legacy protocol session id, or the modern client key derived by `ownerKey` (keyless modern requests share one anonymous bucket). Creations beyond it are rejected with `-32602`. |
| `maxConcurrentTotal` | `1024` | Running-task ceiling per **pool** (legacy-session tasks and modern bearer tasks are counted separately, so neither can starve the other). `-32003` when exceeded; must be ≥ `maxConcurrentPerSession`. |
| `maxStoredPerOwner` | `256` | Stored entries per owner, terminal results included. At the cap the owner's oldest terminal entry older than `minResultRetentionMs` is evicted to admit the new task; if none qualifies the create is refused with `-32003`. |
| `maxStoredTotal` | `4096` | Stored entries per pool, terminal results included. At the cap the creating owner's own oldest eligible entry is evicted first, then the oldest eligible entry of the owner holding the most stale results; an owner whose results are all inside the grace is never a victim; otherwise `-32003`. |
| `minResultRetentionMs` | `30 000` | A terminal result younger than this is never evicted by a cap (only by its own TTL), so a client always gets at least 30 s to collect it. |
| `sweepIntervalMs` | `1 000` | Upper bound on the single TTL sweeper's sleep; TTLs are honoured within this slack, measured on the monotonic clock. |
| `ownerKey` | `TaskOwnerKey.Transport` | How modern bearer tasks are bucketed per client: `Transport` uses the transport-supplied peer address; `TaskOwnerKey.Custom(f)` derives a key (an authenticated principal) behind a reverse proxy. |
| `maxRequestBytes` | 2 MiB | What one running task may keep of the request that created it (estimated bytes, see [Memory bounds](#memory-bounds)), together with the input requests it is waiting on and the responses it has been given. A larger request is refused with `-32602` before anything is started. |
| `maxResultBytes` | 8 MiB | One stored result or failure (estimated bytes). A task whose result is larger ends `failed` and the result is not kept. |
| `maxBytesPerOwner` | 16 MiB | Everything the store holds for one owner: the request of each running task plus each stored result. Must be ≥ `maxRequestBytes` and ≥ `maxResultBytes`. |
| `maxBytesTotal` | 128 MiB | The same budget per pool (legacy-session tasks and modern bearer tasks are separate pools, so the store as a whole holds at most twice this). Must be ≥ `maxBytesPerOwner`. |
| `cancelGraceMs` | `1 000` | How long `tasks/cancel` waits for the task's body to stop before it answers. After that the task is recorded `cancelled` although its body is still running — see [Cancelling a task](#cancelling-a-task). `0` does not wait. Since 1.1.0. |

### Memory bounds

Counting entries does not bound memory, so the store also counts bytes. Every entry is charged an
estimate of the data it keeps reachable:

- **While a task runs** it keeps the request that created it, because a handler running as a task
  can read all of it through `McpContext`: the `tools/call` params (arguments, `_meta`,
  `inputResponses`, `requestState`), the decoded client capabilities and client info, the request
  id, and any allow-listed HTTP headers (`contextHeaders`). That is measured when the task is
  created. Above `maxRequestBytes` the request is refused with `-32602`; a tool whose task support
  is optional still accepts it as an ordinary synchronous call (do not ask for a task).
- **While a task waits for input** it also keeps the requests it shows on `tasks/get` and the
  responses `tasks/update` has delivered; a response takes the place of the request it answers.
  They are charged to the same task: request, outstanding input requests and gathered responses
  together stay within `maxRequestBytes`, and within the owner and pool budgets below. A
  `tasks/update` that does not fit is refused (`-32602` for `maxRequestBytes`, `-32003` for a
  budget) and stores nothing; input requests that do not fit fail the task.
- **Once a task is terminal** its entry keeps the result only — not the task's fiber, and nothing
  of the request. The request's charge is swapped for the result's. A result or failure above
  `maxResultBytes` is not kept: the task ends `failed` with a message naming the setting.
- **Budgets.** The charges of one owner stay within `maxBytesPerOwner` and those of one pool within
  `maxBytesTotal` at all times. A create that does not fit evicts stored results older than
  `minResultRetentionMs` — the owner's own oldest first; for the pool budget then those of the
  owner holding the most stale results — and is refused with `-32003` when that is not enough. A
  finishing task whose result does not fit evicts the same way; if nothing may be evicted yet, it
  is this result that is discarded (the task ends `failed`, `tasks/result` answers `-32003`), never
  a result still inside its retention grace.

The figures are **estimated heap bytes**, not wire bytes: two per string character, a fixed
overhead per JSON value, object member and array slot, and the magnitude of each number. A
document that is one long string is charged about twice its length; a document of many small
values is charged several times its wire size, which is what it costs on the heap. The estimate
covers data — JSON, text, bytes, failure messages and JSON error data. What every entry costs
regardless of content (ids, timestamps, a failure's stack trace) is bounded by the entry caps, and
a failed task's `statusMessage` keeps at most the first 1024 characters of the failure message.
A result of another type, which only code calling `TaskManager.create` directly can produce, is
charged a flat 256 bytes.

Size the budgets to the heap: at the defaults the store retains at most 2 × 128 MiB of charged
data. Tools that return large results should raise `maxResultBytes` (and the two budgets with it)
or return a resource link instead.

## Opting in per tool

Annotation path:

```scala 3 raw
@Tool(name = Some("expensive-op"), taskSupport = Some("optional"))
def expensiveOp(@Param("input") x: String): String = ???
```

Typed-contract path:

```scala 3 raw
import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.core.TaskSupport   // optional since 1.0.0 (root-exported); the release candidates need it

val tool = McpTool[Args, Result](name = "expensive-op")(args => work(args))
  .withTaskSupport(TaskSupport.Optional)
```

`taskSupport` is the server-side policy:

- `"forbidden"` (default) — always runs synchronously.
- `"optional"` — may return a task when the client supports the extension.
- `"required"` — requires task augmentation. A modern (2026-07-28) client that does not declare
  the tasks extension gets `-32021` (missing required client capability, naming
  `extensions['io.modelcontextprotocol/tasks']`); a legacy (2025-11-25) client that sends a
  bare `tools/call` without `params.task` gets `-32601`.

Modern `tools/list` does not expose the removed `execution.taskSupport` field; legacy clients still
see and use it.

A tool with `optional` or `required` support becomes a task before its handler runs. A typed
contract can instead start as a request and hand off later: see
[Input first, then a task](#input-first-then-a-task-withdeferredtask).

## Waiting for input (`input_required`)

A tool asks its client for input the same way inside a task as outside one: `McpContext.elicit`,
`createMessage`, `listRoots` or `sendRequest`. Outside a task the call ends the request with an
`input_required` result and the client retries the request with `inputResponses` (MRTR). Inside a
2026-07-28 task there is no reply left to give, so the task waits instead:

1. The task's status becomes `input_required`. `tasks/get` returns `inputRequests`: every request
   the task is still waiting on, keyed by an opaque id.
2. The client sends `tasks/update` with `inputResponses` for some or all of those keys. The reply
   is an empty acknowledgement (`resultType: "complete"`), never the task.
3. A partial set is accepted: the answered keys leave `inputRequests` and the task stays in
   `input_required` until none is outstanding.
4. The task is `working` again and the tool runs again from the start, with every response
   gathered so far available to it — the same replay a client retry causes. Calls that were
   answered return their response; the tool continues from there. It may ask again later, with
   new keys, any number of times.

```scala 3 raw
val confirmDelete = McpTool[DeleteArgs, String](name = "confirm_delete")
  .contextual { (args, context) =>
    ZIO
      .fromOption(context)
      .orElseFail(new IllegalStateException("no request context"))
      .flatMap(_.elicit(ElicitRequestParams(s"Delete ${args.filename}?", confirmSchema)))
      .map(answer => s"${args.filename}: ${answer.action}")
  }
  .withTaskSupport(TaskSupport.Optional)
```

Rules the server applies:

- **Write the tool so that a replay is safe.** Everything before a question runs once per
  attempt. Do the work that must happen once after the last question, or make it idempotent.
  This is the rule MRTR already sets for tools outside tasks.
- **A key is asked once.** Keys are derived from the request's content, so the same question gets
  the same key on every attempt and is answered from the gathered responses. A tool that fails an
  attempt asking only for keys it already has responses for cannot make progress: the task ends
  `failed` (`-32603`).
- **Keys that are not outstanding are ignored**: a key never issued, one already answered, or any
  key sent to a task that is not waiting. The update is still acknowledged. A response for an
  outstanding key that is not a JSON object is refused with `-32602`.
- **`requestState`** a tool passes to `sendRequest` is kept by the server between attempts and
  handed to the next one (`McpContext.getRequestState`). It is never part of `tasks/get` or of the
  task handle.
- **A waiting task is a running task.** It keeps its fiber and its request, counts against
  `maxConcurrentPerSession` and `maxConcurrentTotal`, can be cancelled, and is interrupted when
  its TTL passes. A client that never answers holds one running slot until then.
- **Anyone who holds the task id can answer.** `tasks/update` is authorized like `tasks/get`: by
  possession of the id. Put authorization in front of the endpoint.
- Legacy (2025-11-25) sessions are not affected: there a tool's request is a real server-to-client
  request, and no task enters `input_required`.

A tool that may run as a task asks from inside the task, because the task exists before its
handler starts. To ask on the request first and create the task afterwards, defer the task — next
section.

## Input first, then a task (`withDeferredTask`)

SEP-2663 ("Composition with Multi Round-Trip Requests") lets one `tools/call` use both mechanisms
in sequence: one or more `input_required` results that gather input on the request itself (MRTR),
then a task handle that hands the work off. The specification asks for this order — a server
"SHOULD resolve all MRTR exchanges synchronously before responding with a `CreateTaskResult`" —
and it suits a tool that cannot start its work before a question is answered: no task is created,
stored or counted for a call the user abandons at the question.

Two additions make it expressible, on typed contracts:

- `McpTool.withDeferredTask` — every call of the tool starts as an ordinary request. No task
  exists until the handler asks for one.
- `McpContext.continueAsTask` — the hand-off. The client gets the task handle as the reply to its
  request, and everything after this call runs in the task. It returns `true` when the handler is
  running as a task from here on.

```scala 3 raw
val provision = McpTool[ProvisionArgs, String](name = "provision")
  .contextual { (args, context) =>
    for
      ctx <- ZIO.fromOption(context).orElseFail(new IllegalStateException("no request context"))
      // Asked on the request: an `input_required` result, no task yet.
      answer <- ctx.elicit(ElicitRequestParams(s"Provision ${args.cluster}?", confirmSchema))
      // The hand-off: the client now gets `resultType: "task"`.
      _ <- ctx.continueAsTask
      // Runs in the task; the client polls `tasks/get`.
      result <- provisionCluster(args.cluster, answer)
    yield result
  }
  .withTaskSupport(TaskSupport.Required)
  .withDeferredTask
```

On the wire, for a client that declared the tasks extension:

1. `tools/call` → `resultType: "input_required"` with `inputRequests` (and `requestState` if the
   tool set one). No `taskId`; the task store is untouched.
2. `tools/call` again, with `inputResponses` → `resultType: "task"` with `taskId` and `status`.
   No `requestState` and no `inputRequests`: the MRTR phase is over.
3. `tasks/get` until the task is terminal; the result is inlined there.

How it works, and what follows from it:

- **The hand-off is a replay.** On the request, `continueAsTask` ends the attempt; the server
  creates the task, and the task runs the handler again from the start with the same arguments,
  `inputResponses` and `requestState`. The questions are answered from those responses,
  `continueAsTask` returns `true`, and the handler continues. So what precedes the hand-off runs
  once per MRTR round and once more in the task — keep it to gathering input, and do the work
  that must happen once after the hand-off. `ServerHooks.beforeToolCall` runs for each of those
  attempts, as it does for every MRTR retry.
- **Do not recover from `continueAsTask`.** Like a question, it signals through the handler's
  failure channel on the request. A handler that catches it (`catchAll`, `orElse`, `either`) does
  not hand off.
- **`withDeferredTask` decides when, `withTaskSupport` decides whether.** `Required`: a client
  that did not declare the extension gets `-32021` before the handler runs, as for any required
  tool. `Optional`: for such a client `continueAsTask` returns `false` and the handler finishes
  on the request. `Forbidden` (the default): the same, for every client.
- **Register it on the `McpServer`.** A deferred tool mounted on another `McpServerCore` (one a
  custom `McpServerCoreFactory` builds, for instance a wrapper around a server) fails to register
  with an `UnsupportedOperationException`: the flag cannot reach the server through it.
- **A handler that returns without calling `continueAsTask` answers the request** with an
  ordinary result, also when its task support is `Required`. The specification allows that: a
  server may always answer with the standard result.
- **Questions after the hand-off are the task's.** They put the task in `input_required` and are
  answered through `tasks/update`, as in the previous section. Their keys are the task's own: the
  responses of the MRTR phase stay available to the handler, but a key that was answered there
  is not outstanding in the task.
- **The task store can refuse the hand-off.** The caps and byte budgets apply when the task is
  created, so a refusal (`-32602` for the per-owner running cap or `maxRequestBytes`, `-32003`
  for a full pool or budget) is the reply to the round that reached the hand-off.
- **In a tool that is not deferred** the call only reports: `true` inside a task, `false`
  otherwise.
- **Legacy (2025-11-25) sessions** have no MRTR and ask for a task per call (`params.task`), so
  the deferral does not apply: the task is created before the handler runs when the call asks
  for one, and `continueAsTask` reports which case it is.

The annotation path has no equivalent yet: `@Tool` cannot mark a tool as deferred, so an annotated
tool that may be a task is one before its method runs (there `continueAsTask` returns `true`).
Register the tool as a typed contract to defer it.

## Extension requests

On 2026-07-28 requests:

- `tasks/get`, `tasks/update` and `tasks/cancel` need the `io.modelcontextprotocol/tasks`
  extension in the request's client capabilities. Without it the answer is `-32021`, whether or
  not the id names a task.
- An id that names no task is `-32602` on all three.
- `tasks/cancel` and `tasks/update` are acknowledgements. Cancelling a task that is already
  terminal, or updating a task that is not waiting for input, is acknowledged and changes nothing.
- Over HTTP the `Mcp-Name` header must equal `params.taskId` on all three (`-32020` when it is
  missing or different), as it must equal the tool name on `tools/call`.

## Transport and security policy

Modern task IDs are **bearer handles**, so task creation and polling work over stdio and both HTTP
settings on JVM and Bun, and over stdio on Scala Native. Possession of an ID grants access to that task: keep them secret and
enforce authorization around the MCP endpoint. Legacy task IDs remain scoped to their initialized
session.

Tasks dispatch is native router middleware; there is no transport-layer special-casing.

Modern bearer tasks are bucketed per client for the running and stored caps above. By default
(`TaskOwnerKey.Transport`; `TaskOwnerKey` lives in `com.tjclp.fastmcp.core` and is root-exported
since 1.0.0) the bucket key is the peer address supplied by the HTTP transport
(zio-http `remoteAddress`, Bun `server.requestIP`); requests that arrive without a key share one
anonymous bucket bounded by the per-owner cap. Behind a reverse proxy every peer collapses to one
address, so use `TaskOwnerKey.Custom` to derive the key from an authenticated principal instead;
`_meta` and `clientInfo` are client-controlled and must never be used as a key unless the proxy
rewrites them. The pool ceilings are a documented residual: a client controlling
`maxConcurrentTotal / maxConcurrentPerSession` distinct peer addresses (16 at the defaults) can
still fill a pool, so pair the peer-address key with edge rate limiting.

## Lifecycle and current limitations

- Task IDs come from the platform CSPRNG (`/dev/urandom` on Scala Native).
- A task that outlives its TTL is interrupted, not orphaned; terminal results stay pollable until
  the TTL sweeps the entry or, after the 30 s `minResultRetentionMs` grace, the owner/pool
  stored-entry cap (`maxStoredPerOwner` 256 / `maxStoredTotal` 4096) or a byte budget
  (`maxBytesPerOwner` / `maxBytesTotal`) evicts the oldest completed task. A create with nothing
  evictable is refused with `-32003`.
- A stored (terminal) entry holds the result and nothing else of the task: neither its fiber nor
  the request. A running task keeps its request, within `maxRequestBytes` — see
  [Memory bounds](#memory-bounds).
- One lazily started sweeper fiber per server enforces TTLs and the retention grace on the
  monotonic clock; `TaskManager.create` is atomic under interruption (a client abort mid-create
  leaves no entry and no parked fiber behind).
- `Session.terminate` — used by HTTP DELETE, idle eviction, and session-cap eviction — interrupts
  the session's in-flight requests and releases (interrupts) that session's running legacy tasks;
  `runStdio()` / `runHttp()` stop the sweeper and running tasks on shutdown. A request in flight is
  not session activity (activity is stamped on arrival), so a legacy `tasks/result` parked longer
  than `sessionIdleTimeout` on an otherwise quiet session can be evicted mid-wait and gets no reply.
- Cancellation is cooperative, and `tasks/cancel` waits for it only so long: see
  [Cancelling a task](#cancelling-a-task).
- The server creates working / input_required / completed / failed / cancelled tool tasks. See
  [Waiting for input](#waiting-for-input-input_required) for `input_required`, and
  [Input first, then a task](#input-first-then-a-task-withdeferredtask) for a tool that asks on
  the request before it becomes a task.
- `notifications/tasks/status` is emitted on legacy (2025-11-25) sessions, on every platform
  (Scala Native included), when a task's entry becomes terminal: `completed`, `failed`, or
  `cancelled` by `tasks/cancel`. A running task that is removed instead — its TTL passed, its
  session was terminated, or the server is shutting down — emits none: its entry is deleted, so
  there is no status left to report (`tasks/get` answers `Unknown task`, and a parked
  `tasks/result` is answered `-32602`). That revision makes the notification optional and tells
  requestors to keep polling, so a client must not wait for one. Modern bearer tasks never emit
  it — the 2026-07-28 extension moved status to polling (`tasks/get`). These boundaries are listed
  in the [upgrade guide](./2026-07-28-upgrade.md#deliberate-boundaries).
- The extension also lets a server push `notifications/tasks` on a `subscriptions/listen` stream
  for the `taskIds` a client names. That is optional and this server does not: a client that
  declares the extension is acknowledged without any task id and keeps polling, and one that
  does not declare it gets `-32021`, as the extension requires.
- A task outlives the request that created it, so what it emits afterwards — that status
  notification, and any log, progress or server-to-client request message of its body — travels
  on the session's out-of-request channel, which is bounded by `limits.maxOutboundMessages`
  (default 256). Over stdio it is always delivered (a full buffer makes the task wait for the
  stdout writer). On a legacy JVM HTTP session it is delivered on the standalone GET stream; a
  client that does not read that stream keeps only the newest 256 messages. On a Bun HTTP session
  and on any 2026-07-28 HTTP request there is no such stream and the message is discarded — poll
  `tasks/get`. See [transports](./transports.md#messages-outside-a-request).

## Cancelling a task

`tasks/cancel`, TTL expiry and session release all stop a task the same way: they interrupt its
fiber. What happens next depends on how the tool's body is written.

- **A body that is a ZIO effect** (`ZIO.sleep`, a stream, an HTTP call through a ZIO client,
  `ZIO.attemptBlockingInterrupt` on the JVM) stops at its next step. The task is `cancelled`
  within milliseconds and `tasks/cancel` answers with that state.
- **A synchronous body** — a plain method on the annotation path, a typed-contract handler that
  returns a plain value, `ZIO.attempt { ... }`, a `Thread.sleep`, a blocking driver call — sees
  the interrupt only when it returns. Nothing can stop it earlier, and on Scala.js nothing else
  runs while it does. The library does not wrap bodies in `attemptBlockingInterrupt` for you:
  that interrupts a JVM thread, which is not safe for arbitrary code and does not exist on the
  other two platforms.

`tasks/cancel` therefore waits for the body for at most `TaskSettings.cancelGraceMs` (default
1 s). If the body has stopped by then, the reply shows how the task really ended: `cancelled`, or
`completed` / `failed` if it finished before the interrupt took effect. If it has not:

- the task is recorded `cancelled` and the cancel is answered (the legacy reply carries that
  task; the 2026-07-28 reply is the empty acknowledgement);
- a legacy `tasks/result` waiter is answered `-32602` (`Task <id> was cancelled`), and the legacy
  session gets its `notifications/tasks/status`;
- the entry is a stored result from then on: it no longer counts as running, and it holds neither
  the fiber nor the request;
- **the body keeps running until it returns.** Whatever it returns or throws then is dropped: the
  task stays `cancelled` and no second notification is sent. Its side effects still happen.

Until 1.1.0 the cancel reply waited for the body, however long that took. To get that back, set
`cancelGraceMs` to a value longer than any body runs; `0` answers without waiting at all.

A body that outlives its cancellation is work the store no longer counts: it holds a thread (on
the JVM and Scala Native) and the request it was started with until it returns, outside
`maxConcurrentPerSession`, `maxConcurrentTotal` and the byte budgets. The same has always been
true of a synchronous body whose task is removed by its TTL or with its session. A tool that can
run long should therefore be written as an effect that can be interrupted; the grace bounds the
client's wait, not the work.

## Legacy (2025-11-25) task surface

On the compatibility adapter, clients send `params.task: {ttl}` and poll `tasks/get` /
`tasks/result` / `tasks/list` / `tasks/cancel`. The `initialize` result declares all of it that
the 2025-11-25 revision has a flag for: `capabilities.tasks` is
`{"list": {}, "cancel": {}, "requests": {"tools": {"call": {}}}}` (until 1.1.0 it carried
`requests.tools.call` only, although `tasks/list` and `tasks/cancel` were served).

`params.task.ttl` is the lifetime the client asks for, in milliseconds from creation. The server
may shorten it, as the revision allows: a value above `maxTtlMs` is clamped, and the `ttl` in the
reply is the one that applies. An absent or `null` `ttl` gets `defaultTtlMs`. Zero or a negative
value is not a lifetime and is refused with `-32602`; no task is created. (Until 1.1.0 it was
accepted, and the client got a `working` handle that was gone a second later.)

That surface works on any transport whose session
outlives a single request: the legacy streamable-HTTP adapter and stdio (one durable session per
process). On the **stateless** legacy adapter all clients share one session identity, so legacy
task requests there are rejected with `-32601`, and its `initialize` result declares no `tasks`
capability. Bearer tasks are invisible to legacy sessions and
vice versa. `tasks/result` is always answered: for a task that was cancelled (`tasks/cancel`, TTL
sweep, session release) — whether already terminal or cancelled while the waiter was parked — it
fails with `-32602` (`Task <id> was cancelled`); once the TTL has swept the entry, with `-32602`
`Unknown task`, the same code `tasks/get` uses.
