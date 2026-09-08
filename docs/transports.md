# Transports

← [README](../README.md) · see also [Platforms](./platforms.md) · [Architecture § Transports](./architecture.md#transports) · [2026-07-28 upgrade guide](./2026-07-28-upgrade.md)

Transport is a phantom type parameter on `McpServerApp[T, Self]`: `Stdio` or `Http`. The matching
`TransportRunner[T]` given resolves at compile time, so there is no run-time transport plumbing in
user code. Every transport is a thin adapter over the shared `MessageLoop` (limit checks → parse →
dispatch → reply framing) and the shared router; each platform contributes only a `TransportBackend` (stdio)
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
stdio-only programs never link zio-http or netty; exclude both `dev.zio:zio-http_3` and `io.netty:*`
from the dependency (netty is a direct, version-pinned dependency of the JVM artifact) to keep them
off the classpath too. That is what makes small GraalVM images possible
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

Every POST — legacy and modern — must carry `Content-Type: application/json`; anything else is
refused with 415 before the body is read or a session is minted. Modern POST requests must
additionally include:

- an `Accept` header listing both `application/json` and `text/event-stream`
- `MCP-Protocol-Version: 2026-07-28`
- `Mcp-Method`, and for tool calls, resource reads, and prompt gets also `Mcp-Name`

The protocol version and client capabilities are repeated in every request's `params._meta`.
Schema-driven `Mcp-Param-*` values may be supplied through `x-mcp-header`.

| Condition | HTTP | JSON-RPC code |
|---|---|---|
| `Host`/`Origin` refused by `allowedHosts` / `allowedOrigins` | 403 | `-32000` |
| Missing or wrong `Content-Type` (any POST) | 415 | `-32000` |
| Body larger than `maxRequestBodyBytes` | 413 | `-32000` on first-party paths; netty and Bun answer an empty 413 when they refuse the body first |
| Frame over `limits` (`maxFrameChars` / `maxDepth` / `maxObjectFields`) | 400 | `-32700` |
| Header/body mismatch | 400 | `-32020` |
| Missing required client capability | 400 | `-32021` |
| Unsupported protocol version (answer carries `data.supported`) | 400 | `-32022` |
| Unknown request method | 404 | `-32601` |

The admission gates run in a fixed order on every backend, on headers first and before any session
state is touched: unknown path 404 → `allowedHosts`/`allowedOrigins` 403 → `Content-Type` 415 →
declared `Content-Length` over `maxRequestBodyBytes` 413 → `Accept` 406 → body read (platform byte
cap, then a first-party post-read check → 413) → frame limits (`-32700`, HTTP 400) → dispatch. The
legacy session cap (`maxSessions`) is applied when an `initialize` is dispatched. Any defect on the
way is answered by a fixed JSON-RPC 500 boundary (`Internal server error`) on both backends: no
exception text, stack trace, or path ever reaches the client.

The complete wire-behavior and review matrix is in the
[2026-07-28 upgrade guide](./2026-07-28-upgrade.md#wire-behavior).

### The legacy compatibility adapter

Requests that speak an older protocol version (`Protocol.LegacyProtocolVersions`) are routed to an
initialization-based adapter: `initialize` mints an `Mcp-Session-Id`, the standalone GET stream
pushes server→client messages (JVM; Bun answers 405 because per-request SSE already covers
server→client traffic), and DELETE terminates the session. Legacy POSTs are subject to the same
`Content-Type: application/json` gate (415) as modern ones. Only `initialize` mints a session; idle
sessions are evicted after `sessionIdleTimeout`, and the store is capped by `maxSessions`
(`Some(1000)` by default): at the cap the longest-idle session without a live GET stream is evicted
to make room, and the `initialize` is refused with 503 only when every stored session holds a live
GET. DELETE, idle eviction, and cap eviction all go through `Session.terminate`: a request still in
flight on that session is interrupted (no reply) and its running legacy tasks are released.

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
| `keepAliveInterval` | `None` | When set, emit SSE heartbeats on quiet streams so proxies do not kill long calls. Neither listener closes a quiet stream on its own: the JVM has no idle timeout and Bun runs with `idleTimeout: 0` (its 10 s default used to cut a slow tool's reply). |
| `allowedHosts` | `None` | DNS-rebinding/CSRF guard: the `Host` value must parse as one `host[:port]` authority and its hostname (or the verbatim `host:port`) must be listed — the port itself is not compared; a present `Origin` must be the same origin as the request `Host` (`scheme://host:port`; scheme not compared; a port-less `Host` admits `http://h` and `https://h`) or appear in `allowedOrigins`; cross-port loopback origins, `null`, a `Host` or `Origin` sent more than once (seen as its `", "`-joined value), and malformed `Host`/`Origin` ports are refused (403), with or without the other header. IPv6 entries are written bracketed, exactly as they appear in the `Host` header: `Set("[::1]")`, not `Set("::1")`. |
| `allowedOrigins` | `None` | Extra browser origins (`https://app.example.com`, `http://localhost:5173`) admitted alongside the request's own authority; malformed entries fail `runHttp()` at startup. |
| `maxRequestBodyBytes` | `1 MiB` | Request body cap on every backend; larger bodies get 413 before decoding (empty 413 on the wire from netty/Bun, JSON-RPC `-32000` on first-party paths); must not exceed `limits.maxFrameChars`. |
| `maxSessions` | `Some(1000)` | Cap on stored legacy sessions; at the cap the longest-idle session without a live GET is evicted (unauthenticated initializes can evict idle sessions — front non-loopback deployments with auth), 503 only if none is evictable; `None` disables. |
| `loggingEnabled` | `false` | Advertise logging; modern clients use per-request `_meta` levels, legacy clients `logging/setLevel`. |
| `resourcesSubscribe` | `false` | Enable legacy `resources/subscribe`; modern clients use `subscriptions/listen`. |
| `tasks` | `TaskSettings()` | The Tasks extension, off by default (see [tasks.md](./tasks.md)). |
| `limits` | `LimitSettings()` | Input bounds on every transport: `maxFrameChars` 4 MiB, `maxDepth` 64, `maxObjectFields` 1024 (`-32700` / HTTP 400 before dispatch), `maxUriChars` 8192 and `maxSubscriptionsPerSession` 1024 (`-32602`). See [Input limits](#input-limits). |

HTTP-specific fields are ignored under stdio; `limits` and `tasks` apply on every transport.

### Input limits

`McpServerSettings.limits: LimitSettings` bounds every inbound JSON-RPC frame on every transport
(stdio and HTTP; JVM, Scala.js/Bun, Scala Native) before any dispatch work happens. The limits
cannot be disabled, only moved; the constructor rejects out-of-range values at construction.

| Field | Default | Bounds |
|---|---|---|
| `maxFrameChars` | `4 * 1024 * 1024` | One decoded frame (one stdio line / one HTTP body), in UTF-16 chars. |
| `maxDepth` | `64` | JSON nesting depth (the envelope object is depth 1; every nested `{` / `[` adds one). Hard ceiling `LimitSettings.MaxSupportedDepth`. |
| `maxObjectFields` | `1024` | Members of any single JSON object anywhere in the frame. |
| `maxUriChars` | `8192` | A client-supplied resource URI (`resources/read`, `resources/subscribe`, `resources/unsubscribe`, `subscriptions/listen` entries). |
| `maxSubscriptionsPerSession` | `1024` | Distinct URIs one legacy session may hold via `resources/subscribe`. |

A frame that violates `maxFrameChars`, `maxDepth`, or `maxObjectFields` is answered with JSON-RPC
`-32700` (HTTP 400) by a linear pre-scan of the raw text, and never mints a session; `maxUriChars`
and `maxSubscriptionsPerSession` violations answer `-32602`. On HTTP an oversized body meets
`maxRequestBodyBytes` first (413), so keep that cap at or below `maxFrameChars` — `runHttp()`
refuses to start otherwise; `maxFrameChars` is the transport-independent backstop. On stdio the
line splitter itself is bounded at `maxFrameChars`: an over-long line is truncated, answered with
`-32700`, and the rest of it discarded.

`maxObjectFields` bounds the residual cost of building a `Map` from attacker-chosen, hash-colliding
keys after the frame check: each object costs up to `maxObjectFields² / 2` string comparisons, so
the per-frame constant grows quadratically with this knob (at the defaults about 0.4 s per 4 MiB
frame on the JVM, several times that on single-threaded Bun). Operators exposing a Bun server to
untrusted networks should lower it (for example to 256); the wire shapes never need more than a few
dozen members per object.

Frames carrying large payloads must fit: a `sampling/createMessage` result with base64 images over
stdio may need a larger frame, e.g.

```scala 3 raw
McpServerSettings(limits = LimitSettings(maxFrameChars = 8 * 1024 * 1024))
```

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
