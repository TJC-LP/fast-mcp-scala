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
| `maxConcurrentPerSession` | `64` | Additional creations beyond this are rejected with `-32602`. Legacy tasks count per protocol session; modern bearer tasks share one global bucket. |

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

## Lifecycle and current limitations

- Task IDs come from the platform CSPRNG (`/dev/urandom` on Scala Native).
- A task that outlives its TTL is interrupted, not orphaned; terminal results stay pollable until
  the TTL sweeps the entry.
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
