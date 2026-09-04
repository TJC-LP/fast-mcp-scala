# Transports

← [README](../README.md) · see also [Platforms](./platforms.md) · [Architecture § Transports](./architecture.md#transports) · [2026-07-28 upgrade guide](./2026-07-28-upgrade.md)

Transport is a phantom type parameter on `McpServerApp[T, Self]`: `Stdio` or `Http`. The matching
`TransportRunner[T]` given resolves at compile time, so there is no run-time transport plumbing in
user code. Every transport is a thin adapter over the shared `MessageLoop` (parse → dispatch →
reply framing) and the shared router; each platform contributes only a `TransportBackend` (stdio)
and, where HTTP is available, an `HttpTransportBackend`.

## stdio

```scala 3 raw
object MyServer extends McpServerApp[Stdio, MyServer.type]:
  @Tool(...) def hello(name: String): String = s"Hello, $name!"
```

Newline-delimited JSON-RPC over stdin/stdout, the transport Claude Desktop and the MCP Inspector
use. One durable session per process; shutdown is EOF-driven (the client closing stdin ends the
loop). The stdio lifecycle (`StdioLoop`: session, single-writer stdout, outbound drainer, EOF
teardown) is shared by the JVM and Scala Native backends; the Scala.js backend drives Node's
callback IO directly. Because `runStdio()` has no reachable call path into the HTTP stack,
stdio-only programs never link zio-http or netty. That is what makes small GraalVM images possible
(see [native-image.md](./native-image.md)).

## HTTP

```scala 3 raw
object MyHttpServer extends McpServerApp[Http, MyHttpServer.type]:
  override def settings = McpServerSettings(port = 8090)

  @Tool(...) def hello(name: String): String = s"Hello, $name!"
```

### The modern path (MCP 2026-07-28)

`runHttp()` accepts one stateless JSON-RPC message per `POST /mcp`. A request may receive a plain
JSON response or a request-scoped SSE stream carrying progress, logging, subscription events, and
its final response. Protocol sessions, `Mcp-Session-Id`, the standalone GET stream, SSE replay,
and HTTP DELETE are **not used** by the modern path. Closing a response stream interrupts its
dispatch fiber; `subscriptions/listen` uses the same long-lived POST response.

Modern Streamable HTTP is identical on the JVM (ZIO HTTP) and Bun (`Bun.serve`).

### Request requirements and error mapping

Modern POST requests must include:

- `Content-Type: application/json`
- an `Accept` header listing both `application/json` and `text/event-stream`
- `MCP-Protocol-Version: 2026-07-28`
- `Mcp-Method`, and for tool calls, resource reads, and prompt gets also `Mcp-Name`

The protocol version and client capabilities are repeated in every request's `params._meta`.
Schema-driven `Mcp-Param-*` values may be supplied through `x-mcp-header`.

| Condition | HTTP | JSON-RPC code |
|---|---|---|
| Header/body mismatch | 400 | `-32020` |
| Missing required client capability | 400 | `-32021` |
| Unsupported protocol version (answer carries `data.supported`) | 400 | `-32022` |
| Unknown request method | 404 | `-32601` |

The complete wire-behavior and review matrix is in the
[2026-07-28 upgrade guide](./2026-07-28-upgrade.md#wire-behavior).

### The legacy compatibility adapter

Requests that speak an older protocol version (`Protocol.LegacyProtocolVersions`) are routed to an
initialization-based adapter: `initialize` mints an `Mcp-Session-Id`, the standalone GET stream
pushes server→client messages (JVM; Bun answers 405 because per-request SSE already covers
server→client traffic), and DELETE terminates the session. Only `initialize` mints a session; idle
sessions are evicted after `sessionIdleTimeout`.

`stateless` controls **only** this adapter. Modern requests are stateless regardless of the flag.
Leaving it `false` (the default) lets older clients fall back to the initialize/session/GET/DELETE
flow; setting it `true` disables that legacy session store. On the stateless legacy adapter all
clients share one session identity, which is why legacy task requests there are refused
(see [tasks.md](./tasks.md)).

### `McpServerSettings` reference

| Setting | Default | Description |
|---|---|---|
| `host` | `127.0.0.1` | Bind address. Changed in 0.5.0 from `0.0.0.0` per the spec's bind-localhost guidance; set `"0.0.0.0"` explicitly for containers or external exposure. |
| `port` | `8000` | Listen port. |
| `httpEndpoint` | `/mcp` | JSON-RPC endpoint path. |
| `stateless` | `false` | Disable the legacy HTTP session store; modern requests are always stateless. |
| `sessionIdleTimeout` | `30 minutes` | Evict legacy sessions with no client activity (live legacy GET streams are exempt); `None` disables. |
| `keepAliveInterval` | `None` | When set, emit SSE heartbeats on quiet streams so proxies do not kill long calls. |
| `allowedHosts` | `None` | DNS-rebinding guard: reject requests whose `Host`/`Origin` is not in the set (403). |
| `loggingEnabled` | `false` | Advertise logging; modern clients use per-request `_meta` levels, legacy clients `logging/setLevel`. |
| `resourcesSubscribe` | `false` | Enable legacy `resources/subscribe`; modern clients use `subscriptions/listen`. |
| `tasks` | `TaskSettings()` | The Tasks extension, off by default (see [tasks.md](./tasks.md)). |

HTTP-specific fields are ignored under stdio.

### Lower-level construction

Skip the sugar trait and construct directly when you need control over the lifecycle:

```scala 3 raw
val server = McpServer("name", "0.1.0")   // platform-appropriate server
server.tool(addTool)
server.runHttp()                          // inside your own ZIOAppDefault
```

`McpServer.typed[R]("name")` builds a server whose handlers may require a ZIO environment `R`;
provide the layer at the boundary with `.provide(...)` on `runStdio()` / `runHttp()`.

## Platform notes

| | JVM | Scala.js / Bun | Scala Native |
|---|---|---|---|
| stdio | `System.in` / `System.out` (`JvmTransportBackend`) | Node `process.stdin` / `stdout` (`JsTransportBackend`) | `System.in` / `System.out`, ids from `/dev/urandom` (`NativeTransportBackend`) |
| HTTP | ZIO HTTP on netty (`JvmHttpBackend`); NIO inside GraalVM images, `-Dfastmcp.http.channelType` overrides | `Bun.serve` (`JsTransportBackend`) | none by design: zio-http has no Native artifacts, so `McpServerApp[Http]` does not compile ([#81](https://github.com/TJC-LP/fast-mcp-scala/issues/81) tracks a socket backend) |
| Legacy GET push stream | ✅ | 405 | n/a |

Node and Deno parity for the HTTP listener is a follow-up; only the `Bun.serve(...)` entry point is
Bun-specific today.
