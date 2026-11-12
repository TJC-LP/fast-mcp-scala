# MCP Tasks (experimental, off by default)

← [README](../README.md) · see also [Transports](./transports.md) · [2026-07-28 upgrade guide](./2026-07-28-upgrade.md)

MCP Tasks wrap a long-running `tools/call` in a durable, polled state machine so a client can
return immediately and collect the result later. They are the official
**`io.modelcontextprotocol/tasks` extension** in MCP 2026-07-28, and fast-mcp-scala keeps them
**off by default** because the spec still marks them experimental.

A client declares the extension in its per-request capabilities; the server may then return a flat
`resultType: "task"` bearer handle without per-call augmentation. Clients poll `tasks/get`, cancel
with `tasks/cancel`, and use `tasks/update` only when a task is waiting for input. `tasks/list`,
`tasks/result`, and `params.task` belong to the 2025-11-25 compatibility adapter and are rejected
on modern requests.

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
| `maxTtlMs` | 24 hours | Upper bound; requestor-supplied TTLs above this are clamped. |
| `pollIntervalMs` | `Tasks.DefaultPollIntervalMs` | `pollInterval` advertised back to clients in `tasks/get` responses. |
| `maxConcurrentPerSession` | `64` | Running (non-terminal) tasks per **owner**: the legacy protocol session id, or the modern client key derived by `ownerKey` (keyless modern requests share one anonymous bucket). Creations beyond it are rejected with `-32602`. |
| `maxConcurrentTotal` | `1024` | Running-task ceiling per **pool** (legacy-session tasks and modern bearer tasks are counted separately, so neither can starve the other). `-32003` when exceeded; must be ≥ `maxConcurrentPerSession`. |
| `maxStoredPerOwner` | `256` | Stored entries per owner, terminal results included. At the cap the owner's oldest terminal entry older than `minResultRetentionMs` is evicted to admit the new task; if none qualifies the create is refused with `-32003`. |
| `maxStoredTotal` | `4096` | Stored entries per pool, terminal results included. At the cap the creating owner's own oldest eligible entry is evicted first, then the oldest eligible entry of the owner holding the most stale results; an owner whose results are all inside the grace is never a victim; otherwise `-32003`. |
| `minResultRetentionMs` | `30 000` | A terminal result younger than this is never evicted by a cap (only by its own TTL), so a client always gets at least 30 s to collect it. |
| `sweepIntervalMs` | `1 000` | Upper bound on the single TTL sweeper's sleep; TTLs are honoured within this slack, measured on the monotonic clock. |
| `ownerKey` | `TaskOwnerKey.Transport` | How modern bearer tasks are bucketed per client: `Transport` uses the transport-supplied peer address; `TaskOwnerKey.Custom(f)` derives a key (an authenticated principal) behind a reverse proxy. |
| `maxRequestBytes` | 2 MiB | What one running task may keep of the request that created it (estimated bytes, see [Memory bounds](#memory-bounds)). A larger request is refused with `-32602` before anything is started. |
| `maxResultBytes` | 8 MiB | One stored result or failure (estimated bytes). A task whose result is larger ends `failed` and the result is not kept. |
| `maxBytesPerOwner` | 16 MiB | Everything the store holds for one owner: the request of each running task plus each stored result. Must be ≥ `maxRequestBytes` and ≥ `maxResultBytes`. |
| `maxBytesTotal` | 128 MiB | The same budget per pool (legacy-session tasks and modern bearer tasks are separate pools, so the store as a whole holds at most twice this). Must be ≥ `maxBytesPerOwner`. |

### Memory bounds

Counting entries does not bound memory, so the store also counts bytes. Every entry is charged an
estimate of the data it keeps reachable:

- **While a task runs** it keeps the request that created it, because a handler running as a task
  can read all of it through `McpContext`: the `tools/call` params (arguments, `_meta`,
  `inputResponses`, `requestState`), the decoded client capabilities and client info, the request
  id, and any allow-listed HTTP headers (`contextHeaders`). That is measured when the task is
  created. Above `maxRequestBytes` the request is refused with `-32602`; a tool whose task support
  is optional still accepts it as an ordinary synchronous call (do not ask for a task).
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
- Cancellation is cooperative. `tasks/cancel`, TTL expiry and session release interrupt the task
  fiber, but a synchronous body (`ZIO.attempt`, or a plain method on the annotation path) observes
  the interrupt only when it returns, so the status flips — and the `tasks/cancel` reply arrives —
  when the body finishes; on Scala.js a running synchronous body cannot be interrupted at all.
  Write long task bodies as ZIO effects (`ZIO.sleep`, streams, `attemptBlockingInterrupt` on the
  JVM) so they stop promptly; a grace-bounded cancel is tracked for 1.0.1.
- The server creates working / completed / failed / cancelled tool tasks. It implements
  `tasks/update` validation but does **not yet** suspend a task in `input_required`.
- `notifications/tasks/status` is emitted on legacy (2025-11-25) sessions on every terminal
  transition, as that revision's Tasks surface specifies; modern bearer tasks do not emit it —
  the 2026-07-28 extension moved status to polling (`tasks/get`). These boundaries are listed in
  the [upgrade guide](./2026-07-28-upgrade.md#deliberate-boundaries).
- A task outlives the request that created it, so what it emits afterwards — that status
  notification, and any log, progress or server-to-client request message of its body — travels
  on the session's out-of-request channel, which is bounded by `limits.maxOutboundMessages`
  (default 256). Over stdio it is always delivered (a full buffer makes the task wait for the
  stdout writer). On a legacy JVM HTTP session it is delivered on the standalone GET stream; a
  client that does not read that stream keeps only the newest 256 messages. On a Bun HTTP session
  and on any 2026-07-28 HTTP request there is no such stream and the message is discarded — poll
  `tasks/get`. See [transports](./transports.md#messages-outside-a-request).

## Legacy (2025-11-25) task surface

On the compatibility adapter, clients send `params.task: {ttl}` and poll `tasks/get` /
`tasks/result` / `tasks/list` / `tasks/cancel`. That surface works on any transport whose session
outlives a single request: the legacy streamable-HTTP adapter and stdio (one durable session per
process). On the **stateless** legacy adapter all clients share one session identity, so legacy
task requests there are rejected with `-32601`. Bearer tasks are invisible to legacy sessions and
vice versa. `tasks/result` is always answered: for a task that was cancelled (`tasks/cancel`, TTL
sweep, session release) — whether already terminal or cancelled while the waiter was parked — it
fails with `-32602` (`Task <id> was cancelled`); once the TTL has swept the entry, with `-32602`
`Unknown task`, the same code `tasks/get` uses.
