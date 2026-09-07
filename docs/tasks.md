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

## Opting in per tool

Annotation path:

```scala 3 raw
@Tool(name = Some("expensive-op"), taskSupport = Some("optional"))
def expensiveOp(@Param("input") x: String): String = ???
```

Typed-contract path:

```scala 3 raw
val tool = McpTool[Args, Result](name = "expensive-op")(args => work(args))
  .withTaskSupport(TaskSupport.Optional)
```

`taskSupport` is the server-side policy:

- `"forbidden"` (default) — always runs synchronously.
- `"optional"` — may return a task when the client supports the extension.
- `"required"` — requires the extension and otherwise returns `-32021`.

Modern `tools/list` does not expose the removed `execution.taskSupport` field; legacy clients still
see and use it.

## Transport and security policy

Modern task IDs are **bearer handles**, so task creation and polling work over stdio and both HTTP
settings on JVM and Bun. Possession of an ID grants access to that task: keep them secret and
enforce authorization around the MCP endpoint. Legacy task IDs remain scoped to their initialized
session.

Tasks dispatch is native router middleware; there is no transport-layer special-casing.

Modern bearer tasks are bucketed per client for the running and stored caps above. By default
(`TaskOwnerKey.Transport`) the bucket key is the peer address supplied by the HTTP transport
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
  stored-entry cap (`maxStoredPerOwner` 256 / `maxStoredTotal` 4096) evicts the oldest completed
  task. A create with nothing evictable is refused with `-32003`.
- One lazily started sweeper fiber per server enforces TTLs and the retention grace on the
  monotonic clock; `TaskManager.create` is atomic under interruption (a client abort mid-create
  leaves no entry and no parked fiber behind).
- `Session.terminate` — used by HTTP DELETE, idle eviction, and session-cap eviction — interrupts
  the session's in-flight requests and releases (interrupts) that session's running legacy tasks;
  `runStdio()` / `runHttp()` stop the sweeper and running tasks on shutdown.
- The server creates working / completed / failed / cancelled tool tasks. It implements
  `tasks/update` validation but does **not yet** suspend a task in `input_required`, and
  task-status notifications are not emitted. These boundaries are listed in the
  [upgrade guide](./2026-07-28-upgrade.md#deliberate-boundaries).

## Legacy (2025-11-25) task surface

On the compatibility adapter, clients send `params.task: {ttl}` and poll `tasks/get` /
`tasks/result` / `tasks/list` / `tasks/cancel`. That surface works on any transport whose session
outlives a single request: the legacy streamable-HTTP adapter and stdio (one durable session per
process). On the **stateless** legacy adapter all clients share one session identity, so legacy
task requests there are rejected with `-32601`. Bearer tasks are invisible to legacy sessions and
vice versa.
