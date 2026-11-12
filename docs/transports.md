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
loop). On the JVM and in GraalVM images SIGTERM/SIGINT take effect only after that EOF — the main
fiber is parked in a blocking `System.in.read` that interruption cannot unblock — so a host must
close the child's stdin before signalling it (the TypeScript SDK does; `Ctrl-C`, `timeout` and
`docker stop` stall until stdin closes); Scala Native and Bun/Node exit on the signal at once. An
interruptible reader is tracked for 1.0.1. Each inbound frame is dispatched in its own fiber, so a
handler awaiting a server→client round trip (roots, sampling, elicitation) cannot deadlock the
loop; replies may therefore be written in a different order from the requests — correlate by `id`,
and wait for the `initialize` result before pipelining further requests. A frame is a
newline-terminated line: do not rely on trailing bytes without a `\n` being dispatched (the JVM
and Bun drop them), and until the 1.0.1 fix the JVM may drop the replies to frames still in flight
when stdin closes — wait for every reply before closing the pipe (Scala Native and Bun answer
them). The stdio lifecycle (`StdioLoop`: session, single-writer stdout, outbound drainer, EOF
teardown) is shared by the JVM and Scala Native backends; the Scala.js backend drives Node's
callback IO directly. Because `runStdio()` has no reachable call path into the HTTP stack,
stdio-only programs never link zio-http or netty; exclude `dev.zio:zio-http_3` from the dependency
(netty reaches the JVM artifact only through zio-http, so that one exclusion sheds both) to keep
them off the classpath too. That is what makes small GraalVM images possible
(see [native-image.md](./native-image.md)).

### stdout is the wire — log to stderr

Every byte a stdio server writes to stdout must be a JSON-RPC frame: a stray line confuses the host
at best, and a line that lands *inside* a frame loses that reply. Two guarantees hold on the JVM,
Scala.js/Bun and Scala Native alike:

- **ZIO logs go to stderr.** ZIO's default logger prints to stdout (`console.log` on Bun), so the
  stdio runner replaces it with the same format on stderr: `McpServerApp[Stdio]` installs
  `TransportRunner.stdio.bootstrap` (`Runtime.removeDefaultLoggers ++
  Runtime.addLogger(StdioLogging.stderrLogger)`) as its ZIO `bootstrap`, and `McpServer.runStdio()`
  makes the same swap for a plain `ZIOAppDefault` as long as ZIO's stock logger is still installed.
  `ZIO.logInfo` inside a tool is therefore safe. Loggers you install yourself are never touched.
- **Frames are written atomically.** A reply and its newline go out in one `PrintStream` call, so
  anything else that prints to `System.out` can only add a whole stray line between frames, never
  split one. Still: never `println` from a stdio server — a stray line is a protocol error for
  strict hosts. Use `ZIO.log*` or `System.err`.

To use your own logger, override `bootstrap` — as a `val`, which is how `ZIOAppDefault` declares it
(`override def` does not compile):

```scala 3 raw
import zio.*

object MyServer extends McpServerApp[Stdio, MyServer.type]:
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers ++
      Runtime.addLogger(ZLogger.default.map(line => java.lang.System.err.println(line)))

  @Tool(...) def hello(name: String): String = s"Hello, $name!"
```

Whatever the override installs is left alone by `runStdio()`; only ZIO's stock stdout logger is
ever swapped. Before 1.0.0 the stdio runner installed no logger at all, so ZIO log lines went to
stdout — if you relied on that, override `bootstrap` with a stdout logger of your own. The ZIO
runtime itself has two stdout prints outside the logger — the `gracefulShutdownTimeout` expiry
warning and the notice it prints when a logger throws — both unreachable at the defaults
(`gracefulShutdownTimeout` is infinite), so leave that default alone on stdio.

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

The shape of a reply is chosen from the request's first frame: an error that maps to an HTTP
status is answered as plain JSON with that status (see the table below), anything else is answered
as `text/event-stream`. When that first frame is already the final reply — a call that emitted no
progress, log or sub-request first, which is most calls — the whole body is known, and it is sent
with a `Content-Length` rather than as a chunked stream: the same single `message` event, without
the streaming machinery. Otherwise the SSE stream is opened and carries each frame as it comes. With `keepAliveInterval` set, that wait is bounded by one interval — a handler that has
produced nothing by then gets its stream opened with a heartbeat, and heartbeats continue until the
reply, so a proxy in front sees bytes during a long quiet tool call. An error that maps to an HTTP
status and is raised after the stream opened is delivered in-band (HTTP 200, the JSON-RPC error as
the final event). Without `keepAliveInterval` the reply waits for the first frame.

Modern Streamable HTTP is identical on the JVM (ZIO HTTP) and Bun (`Bun.serve`). Both backends
serve plaintext HTTP/1.1 only — there is no TLS setting by design; terminate TLS and authentication
at a reverse proxy in front of the MCP endpoint. Neither backend adds CORS headers or answers an
`OPTIONS` preflight: a browser-hosted client on another origin needs a CORS-terminating proxy in
front of the endpoint (and, with `allowedHosts` on, its origin listed in `allowedOrigins`).

### Request requirements and error mapping

Every POST — legacy and modern — must carry `Content-Type: application/json` (media-type
parameters are allowed, but a `charset` other than utf-8 is refused); anything else is refused with
415 before the body is read or a session is minted. Modern POST requests must
additionally include:

- an `Accept` header listing both `application/json` and `text/event-stream`
- `MCP-Protocol-Version: 2026-07-28`
- `Mcp-Method`, and for tool calls, resource reads, and prompt gets also `Mcp-Name`

The protocol version and client capabilities are repeated in every request's `params._meta`.
Schema-driven `Mcp-Param-*` values may be supplied through `x-mcp-header`.

| Condition | HTTP | JSON-RPC code |
|---|---|---|
| `Host`/`Origin` refused by `allowedHosts` / `allowedOrigins` | 403 | `-32000` |
| Missing or wrong `Content-Type` (any POST; a non-utf-8 `charset` counts as wrong) | 415 | `-32000` |
| Body larger than `maxRequestBodyBytes` | 413 | `-32000` on first-party paths; netty and Bun answer an empty 413 when they refuse the body first |
| Frame over `limits` (`maxFrameChars` / `maxDepth` / `maxObjectFields` / `maxArrayElements` / `maxValues` / `maxNumberDigits` / `maxNumberExponent`) | 400 | `-32700` |
| Header/body mismatch | 400 | `-32020` |
| Missing required client capability | 400 | `-32021` |
| Unsupported protocol version (answer carries `data.supported`) | 400 | `-32022` |
| Unknown request method | 404 | `-32601` |

The admission gates run in a fixed order on every backend, on headers first and before any session
state is touched: unknown path 404 → `allowedHosts`/`allowedOrigins` 403 → `Content-Type` 415 →
declared `Content-Length` over `maxRequestBodyBytes` 413 → `Accept` 406 → body read (platform byte
cap, then a first-party post-read check → 413) → frame limits (`-32700`, HTTP 400) → dispatch. The
legacy session cap (`maxSessions`) is applied when an `initialize` is dispatched. Any transport or
handler-construction defect on the way is answered by a fixed JSON-RPC 500 boundary
(`Internal server error`) on both backends, with no exception text, stack trace, or path. A defect
raised *inside* a tool handler (`ZIO.die`, an uncaught exception) is not on this path: the router
answers it in-band as `-32603` carrying the handler's message, on the legacy and the modern path
alike — so keep secrets out of the exception messages your tools can throw.

The complete wire-behavior and review matrix is in the
[2026-07-28 upgrade guide](./2026-07-28-upgrade.md#wire-behavior).

### Request headers in handlers (`contextHeaders`)

A server behind an authenticating proxy usually needs the identity the proxy forwards: who made
the call, and often a forwarded access token to act as that user downstream. List the header names
and read them from the context:

```scala
val settings = McpServerSettings(
  contextHeaders = Set("x-forwarded-email", "x-forwarded-access-token")
)

@Tool(name = Some("whoami"))
def whoami(ctx: McpContext): String =
  ctx.header("x-forwarded-email").getOrElse("anonymous")   // ctx.headers: Map[String, String]
```

- **Nothing is exposed unless listed.** Names compare case-insensitively; `ctx.headers` is keyed
  by lower-cased name and holds the listed headers that the request carried.
- **Per request, not per session.** Each call sees the headers of the HTTP request that carried
  it: a token the proxy rotates during a legacy session's life is seen with its new value, and a
  2026-07-28 request sees its own. A task keeps the headers of the request that created it —
  the requests that poll it do not change what the task's handler sees.
- **A header sent more than once is seen joined** (`"a, b"`), on every backend. A value a client
  sent and a proxy appended to is therefore never read as the proxy's value alone; a proxy should
  still *replace* the header.
- **The values are whatever the peer sent.** Listing a name is your statement that the proxy sets
  or strips that header on every request. A client that can reach the server directly can send
  any header it likes, so keep the listener on loopback or a private network behind the proxy.
- The library never logs these values. Treat them as credentials in your own logging too.
- Same behaviour on the JVM (zio-http and the socket backend), Bun and Scala Native. On stdio
  `ctx.header` is always `None` and `ctx.headers` is empty.

`ctx.protocolSessionId` tells a protocol session from a stand-in: it is `Some` (the
`Mcp-Session-Id` value) only for a durable legacy session and `None` for a 2026-07-28 request,
legacy stateless mode and stdio — for which `ctx.sessionId` still returns `request-…`,
`stateless` and `stdio`.

### The legacy compatibility adapter

The reply to a legacy POST request is shaped the same way as a modern one (see "The modern path"):
a reply that is complete at its first frame is one fixed-length `text/event-stream` body, and
anything else is streamed. The wait for that first frame is bounded on this path: after one second
(or one `keepAliveInterval`, if shorter) the stream is opened whether or not anything has arrived.
Before 1.1.0 a legacy reply was always a chunked stream whose response head was sent at once; a
quiet call now shows its head at most a second later, which stays well inside any client's
response-header timeout.

Requests that speak an older protocol version (`Protocol.LegacyProtocolVersions`) are routed to an
initialization-based adapter: `initialize` mints an `Mcp-Session-Id`, the standalone GET stream
pushes server→client messages (JVM; Bun answers 405 because per-request SSE already covers
server→client traffic), and DELETE terminates the session (`disallowDelete = true` answers 405
instead). Legacy POSTs are subject to the same
`Content-Type: application/json` gate (415) as modern ones. Only `initialize` mints a session; idle
sessions are evicted after `sessionIdleTimeout`, and the store is capped by `maxSessions`
(`Some(1000)` by default): at the cap the longest-idle session without a live GET stream is evicted
to make room; when every stored session holds a live GET, the longest-idle of them is evicted
instead — its GET stream is closed — provided it has been idle longer than `sessionIdleTimeout`
(clients reopen their GET stream as usual); the `initialize` is refused with 503 only when no
session qualifies under either rule. The periodic idle sweeper never evicts a session with a live
GET stream while capacity is free. DELETE, idle eviction, and cap eviction all go through
`Session.terminate`: a request still in flight on that session is interrupted (no reply) and its
running legacy tasks are released. Activity is stamped when a request arrives, not while it runs,
so a call that outlasts `sessionIdleTimeout` on an otherwise quiet session can be evicted
mid-flight and never answered (a `-32001` reply is tracked for 1.0.1) — keep the timeout above
your longest call, or poll through Tasks. An empty `mcp-session-id` header counts as an unknown
session on the JVM (404) and as a missing header on Bun (400). On Bun a legacy session has no GET
channel, so a server→client message emitted outside a request (a future
`notifications/resources/updated`) has no delivery path; nothing publishes such messages today.

A GET peer that disappears without closing its connection (NAT expiry, a suspended machine) leaves
the stream "live" from the server's point of view: the OS only notices such a peer once the server
writes to the socket — that is, with `keepAliveInterval` set — and only after its own TCP
retransmission budget (zio-http 3.11.4 exposes no accepted-socket option, so the server does not
set TCP keepalive itself). The cap-time rule above bounds the session store regardless.

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
| `disallowDelete` | `false` | Legacy adapter: answer 405 to HTTP DELETE instead of terminating the session. |
| `sessionIdleTimeout` | `30 minutes` | Evict legacy sessions with no client activity. Live legacy GET streams are exempt from the periodic sweep but become evictable at the `maxSessions` cap once idle this long; `None` disables both. |
| `keepAliveInterval` | `None` | When set, emit SSE heartbeats on quiet streams so proxies do not kill long calls. The legacy GET stream heartbeats from the first byte. The reply to a POST waits for the handler's first frame for at most one interval: a call that is still silent then gets its SSE response opened with a heartbeat (since 1.1.0; see "The modern path"). With `None`, a 2026-07-28 reply waits for the first frame however long that takes, and a legacy reply opens its stream after one second. Neither listener closes a quiet stream on its own: the JVM has no idle timeout and Bun runs with `idleTimeout: 0` (its 10 s default used to cut a slow tool's reply). Heartbeats are also what lets the OS detect a GET peer that vanished without closing its connection (the socket is only probed when the server writes to it, and the OS gives up after its retransmission budget); without them such a stream stays live until the cap-time eviction described under `maxSessions`. |
| `allowedHosts` | `None` | DNS-rebinding/CSRF guard: the `Host` value must parse as one `host[:port]` authority and its hostname (or the verbatim `host:port`) must be listed — the port itself is not compared; a present `Origin` must be the same origin as the request `Host` (`scheme://host:port`; scheme not compared; a port-less `Host` admits `http://h` and `https://h`) or appear in `allowedOrigins`; cross-port loopback origins, `null`, a `Host` or `Origin` sent more than once (seen as its `", "`-joined value), and malformed `Host`/`Origin` ports are refused (403), with or without the other header. IPv6 entries are written bracketed, exactly as they appear in the `Host` header: `Set("[::1]")`, not `Set("::1")`. |
| `allowedOrigins` | `None` | Extra browser origins (`https://app.example.com`, `http://localhost:5173`) admitted in addition to the request's own authority **when `allowedHosts` is also set**; with `allowedOrigins` alone only the listed origins pass (the request's own authority is then refused 403 too). Malformed entries fail `runHttp()` at startup. |
| `maxRequestBodyBytes` | `1 MiB` | Request body cap on every backend; larger bodies get 413 before decoding (empty 413 on the wire from netty/Bun, JSON-RPC `-32000` on first-party paths); must not exceed `limits.maxFrameChars`. This is the cap a large `sampling/createMessage` or elicitation *result* meets over HTTP (a legacy client then waits out its 60 s request timeout): raise it together with `limits.maxFrameChars` — see [Input limits](#input-limits). |
| `maxSessions` | `Some(1000)` | Cap on stored legacy sessions; at the cap the longest-idle session without a live GET is evicted, else the longest-idle GET-holding session that has been idle longer than `sessionIdleTimeout` (its GET stream is closed). Unauthenticated initializes can evict idle sessions — front non-loopback deployments with auth. 503 only if neither rule finds a victim; `None` disables. |
| `loggingEnabled` | `false` | Advertise logging; modern clients use per-request `_meta` levels, legacy clients `logging/setLevel`. |
| `resourcesSubscribe` | `false` | Enable legacy `resources/subscribe`; modern clients use `subscriptions/listen`. |
| `exposeTemplatesEndpoint` | `false` | List registered resource templates through `resources/templates/list`. `resources/list` never lists templates; with the default the templates endpoint answers an empty page (clients derive templates from `{}` URIs) and `resources/read` on a matching URI works either way. Applies on every transport. |
| `contextHeaders` | `Set.empty` | HTTP request headers a handler may read through `McpContext.header` / `McpContext.headers`; nothing is exposed unless listed. See "Request headers in handlers". |
| `tasks` | `TaskSettings()` | The Tasks extension, off by default (see [tasks.md](./tasks.md)). |
| `limits` | `LimitSettings()` | Input bounds on every transport: `maxFrameChars` 4 MiB, `maxDepth` 64, `maxObjectFields` 1024, `maxArrayElements` 4096, `maxValues` 65536, `maxNumberDigits` 512, `maxNumberExponent` 512 (`-32700` / HTTP 400 before dispatch), `maxUriChars` 8192 and `maxSubscriptionsPerSession` 1024 (`-32602`). See [Input limits](#input-limits). |

HTTP-specific fields are ignored under stdio; `limits`, `tasks` and `exposeTemplatesEndpoint` apply on every transport.

### Input limits

`McpServerSettings.limits: LimitSettings` bounds every inbound JSON-RPC frame on every transport
(stdio and HTTP; JVM, Scala.js/Bun, Scala Native) before any dispatch work happens. The limits
cannot be disabled, only moved; the constructor rejects out-of-range values at construction.

| Field | Default | Bounds |
|---|---|---|
| `maxFrameChars` | `4 * 1024 * 1024` | One decoded frame (one stdio line / one HTTP body), in UTF-16 chars. |
| `maxDepth` | `64` | JSON nesting depth (the envelope object is depth 1; every nested `{` / `[` adds one). Hard ceiling `LimitSettings.MaxSupportedDepth`. |
| `maxObjectFields` | `1024` | Members of any single JSON object anywhere in the frame. |
| `maxArrayElements` | `4096` | Elements of any single JSON array anywhere in the frame. |
| `maxValues` | `65536` | JSON values in the whole frame: every object, array, string, number, boolean and `null` counts once (member names do not). |
| `maxNumberDigits` | `512` | Digits in the significand of a number as written (integer and fraction part, leading and trailing zeros included). Hard ceiling `LimitSettings.MaxSupportedNumberDigits` (4096). |
| `maxNumberExponent` | `512` | Absolute value of a number's exponent (the part after `e` / `E`). Hard ceiling `LimitSettings.MaxSupportedNumberExponent` (4096). |
| `maxUriChars` | `8192` | A client-supplied resource URI (`resources/read`, `resources/subscribe`, `resources/unsubscribe`, `subscriptions/listen` entries). |
| `maxSubscriptionsPerSession` | `1024` | Distinct URIs one legacy session may hold via `resources/subscribe`. |

A frame that violates `maxFrameChars`, `maxDepth`, `maxObjectFields`, `maxArrayElements`,
`maxValues`, `maxNumberDigits` or `maxNumberExponent` is answered with JSON-RPC `-32700` (HTTP 400)
by a linear pre-scan of the raw text — before the parser allocates anything — and never mints a
session; the parsed tree is then re-checked against the same bounds. `maxUriChars` and
`maxSubscriptionsPerSession` violations answer `-32602`. Text inside JSON strings is never
interpreted: a base64 payload or a string full of digits and brackets is one value. The pre-scan is
a single pass with no allocation per token; on the JVM it costs about a nanosecond per character of
structured JSON and far less for long strings, a fraction of what parsing the same frame costs.

On HTTP an oversized body meets `maxRequestBodyBytes` first (413), so keep that cap at or below
`maxFrameChars` — `runHttp()` refuses to start otherwise; `maxFrameChars` is the
transport-independent backstop. On stdio the line splitter itself is bounded at `maxFrameChars`: an
over-long line is truncated, answered with `-32700`, and the rest of it discarded.

`maxValues`, `maxObjectFields` and `maxArrayElements` together bound what an accepted frame can
cost, independently of its size in bytes:

- **Memory.** The parsed tree has at most `maxValues` nodes, so it is bounded by the frame text
  plus a fixed overhead per node (65 536 nodes at the defaults) — not by a multiple of
  `maxRequestBodyBytes`. A megabyte of one-character array elements is refused; it is not parsed
  into a tree tens of times its size.
- **Hash collisions.** Building a `Map` from one object's attacker-chosen, hash-colliding keys
  costs up to `maxObjectFields² / 2` string comparisons (about 5 × 10^5 at the default), and
  building a `Set` (or any type containing one) from one array's colliding elements costs up to
  `maxArrayElements² / 2` (about 8.4 × 10^6 at the default, a few tens of milliseconds on the
  JVM). Because `maxValues` caps the members of all objects and arrays in a frame together, the
  whole frame costs at most `maxValues × max(maxObjectFields, maxArrayElements) / 2` comparisons:
  about 3.4 × 10^7 through `Map` sinks, which every server has, and about 1.3 × 10^8 — half a
  second to a second on the JVM, several times that on single-threaded Bun — for a handler that
  builds a `Set` from a client array.

The array default is 4096 rather than 1024 so that an embedding vector of 1536 or 3072 numbers
passes without configuration; the cost is the 16-fold larger per-array figure above. The per-frame
constant grows linearly with each of `maxObjectFields` and `maxArrayElements`, so a server with no
tool that takes long arrays — and any Bun server exposed to untrusted networks — should lower both
(for example to 256); the wire shapes never need more than a few dozen members per object. The
defaults hold sixteen 3072-number vectors, or a table of 4096 rows by 14 columns, in one frame.
Raise `maxArrayElements` for tools that take longer arrays by design, and `maxValues` with it when
a single call carries more than about 65 000 values:

```scala 3 raw
McpServerSettings(limits = LimitSettings(maxArrayElements = 16384, maxValues = 262144))
```

`maxNumberDigits` and `maxNumberExponent` bound the magnitude a client can write in a few bytes.
The defaults admit the full `Long` range and every finite `Double` (whose shortest form has an
exponent between -324 and 308) and refuse a number such as `1e999999999` from its text, with work
that does not depend on the magnitude. A number inside the bounds expands to an integer of at most
`maxNumberDigits + maxNumberExponent` decimal digits (1024 by default: at most 3402 bits), and a
`BigDecimal` decoded from it has a scale within the same window, so a handler parameter typed
`BigInt`, `java.math.BigInteger` or `BigDecimal` costs a small constant per number — at decode time
and in whatever arithmetic the handler then does. That guarantee is checked again on the value
itself in front of every argument decoder (at most `maxNumberDigits` significant digits, none of
them outside `10^±(maxNumberDigits + maxNumberExponent)`), so it also holds for a value that did
not arrive as a JSON number in a frame: an in-process call, a prompt or resource-template argument
(always a string), or a number a client writes as a JSON string. `"1e999999999"` is an ordinary
value for a `String` parameter and is refused when it decodes into a `BigDecimal` — `isError: true`
on `tools/call`, an error reply on `prompts/get` and `resources/read`. For string-encoded numbers
the check looks inside case classes, tuples, `Option`, `Either`, arrays and standard collections; a
`BigDecimal` that a custom decoder wraps in a class of its own is not seen. The parser
independently refuses a significand over 256 bits (about 77 significant digits).

JSON embedded in a string argument (`McpDecodeContext.parseJsonObject` / `parseJsonArray`) is held
to the same depth, object, array, value-count and number bounds, answered with `-32602`.

Frames carrying large payloads must fit: a `sampling/createMessage` result with base64 images over
stdio may need a larger frame, e.g.

```scala 3 raw
import com.tjclp.fastmcp.server.LimitSettings   // optional since 1.0.0 (root-exported); the release candidates need it

McpServerSettings(limits = LimitSettings(maxFrameChars = 8 * 1024 * 1024))
```

Over HTTP the same result arrives as a POST body and meets `maxRequestBodyBytes` (1 MiB) first — a
bodiless 413 from netty/Bun, which a legacy client only surfaces after its 60 s request timeout — so
raise both knobs together, keeping `maxRequestBodyBytes ≤ limits.maxFrameChars`:

```scala 3 raw
McpServerSettings(
  maxRequestBodyBytes = 4 * 1024 * 1024,
  limits = LimitSettings(maxFrameChars = 8 * 1024 * 1024)
)
```

### Lower-level construction

Skip the sugar trait and construct directly when you need control over the lifecycle:

```scala 3 raw
val server = McpServer("name", "0.1.0")   // platform-appropriate server
server.tool(addTool) *> server.runHttp()  // inside your own ZIOAppDefault
```

`tool`, `prompt` and `resource` return `ZIO` effects that register on evaluation, so sequence them
(`*>`, or `_ <- server.tool(addTool)` in a `for` comprehension) rather than calling them as bare
statements: a discarded `server.tool(addTool)` registers nothing and the server advertises no
tools. Compiling with `-Wnonunit-statement` flags exactly this mistake
(`unused value of type zio.ZIO[...]`).

`McpServer.typed[R]("name")` builds a server whose handlers may require a ZIO environment `R`;
provide the layer at the boundary with `.provide(...)` on `runStdio()` / `runHttp()`.

### The socket backend

Scala Native has no zio-http, so its `HttpTransportBackend` is a small HTTP/1.1 + SSE server over
`java.net.ServerSocket`, written for MCP and nothing else (`SocketHttpServer`, compiled into both
the JVM and Scala Native modules from `fast-mcp-scala/jvm-native/`). It renders the same shared
`StreamableHttpHandler` as the zio-http backend, so every rule in this document — the gates and
their order, the body and session caps, the legacy adapter, the 2026-07-28 stateless path — applies
unchanged. On Scala Native it is the platform's HTTP backend (`NativeHttpBackend`) and is
**experimental**; on the JVM it is an opt-in (`JvmSocketHttpBackend`) for netty-free deployments and
GraalVM images.

**Opting in on Scala Native.** `NativeHttpBackend` is marked `@experimental`, so a program that
serves HTTP there — `McpServerApp[Http]`, or `runHttp()` — compiles only in experimental mode:

```scala 3 raw
//> using platform native
//> using options -experimental

object MyServer extends McpServerApp[Http, MyServer.type]:
  @Tool() def add(a: Int, b: Int): Int = a + b
```

(`scalacOptions += "-experimental"` in sbt or Mill; or annotate the app object
`@scala.annotation.experimental` instead of using the flag.) Without it the compiler refuses with
a message that points here. Stdio programs on Scala Native never touch this backend and need
nothing. The gate exists because of the runtime defect described under "Known limitation" below;
it will be lifted when a Scala Native release contains the fix.

**Opting in on the JVM.** Import the backend's given *by name* next to the root import:

```scala 3 raw
import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.server.transport.JvmSocketHttpBackend.httpInstance

object MyServer extends McpServerApp[Http, MyServer.type]:
  @Tool() def add(a: Int, b: Int): Int = a + b
```

The given has the same name as the default backend's on purpose: a named import outranks the root
wildcard import and replaces the default everywhere in the file. Do not declare your own
`given JvmSocketHttpBackend.type = JvmSocketHttpBackend` at the top level instead — inside any
object the root import still wins and the server silently binds netty. For a single call,
`server.runHttp()(using JvmSocketHttpBackend)` passes the backend explicitly. Excluding
`dev.zio::zio-http` from the dependency then removes netty from the classpath.

To change the connection cap or the timeouts, declare a configured backend in your file under the
default's exact name (the name is what replaces the imported default everywhere in the file; under
any other name the given loses to the import inside an object):

```scala 3 raw
import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.server.transport.{HttpTransportBackend, JvmSocketHttpBackend}

given httpInstance: HttpTransportBackend = JvmSocketHttpBackend.withSettings(maxConnections = 1024)
```

**How it runs.** One thread per connection, taken from ZIO's blocking executor, owns the socket
and does plain blocking I/O; it enters ZIO only to run the MCP handler. A reply whose frames are
ready is written as a single packet (head, SSE frames and the chunked terminator together).

| Aspect | Behaviour |
|---|---|
| Protocol | HTTP/1.1 keep-alive, requests served one at a time per connection; HTTP/1.0 and `Connection: close` get close-delimited replies. No HTTP/2, no TLS (terminate both at a proxy) |
| Request bodies | `Content-Length` and `Transfer-Encoding: chunked`, bounded on the wire by `maxRequestBodyBytes` (413); `Expect: 100-continue` is answered only after the header gates pass |
| Request head | at most 8 KiB (431) |
| Connections | 256 concurrent by default; more wait in the kernel backlog. The cap is a hard limit, so three timeouts keep a client from holding a slot without making progress. All four are set with `JvmSocketHttpBackend.withSettings(maxConnections = n, ...)` on the JVM or `NativeHttpBackend.withSettings(...)` on Scala Native (experimental, like the backend itself); the backend class has no public constructor, so on Scala Native there is no way to build one outside the `-experimental` opt-in |
| `idleTimeout` (60 s) | a keep-alive connection with no request activity is closed |
| `requestReadTimeout` (60 s) | the allowance for a whole request, head and body, from its first byte (408 when it runs out). Every 4 KiB received extends it by one second, so a slow client that is still sending a large body gets the time it needs, while one sending a byte or a line at a time cannot keep the connection |
| `writeStallTimeout` (30 s) | a write that makes no progress for this long — the client stopped reading a large reply or an event stream — closes the connection. Replies go out in 16 KiB slices and the clock restarts with each, so a slow client that is still reading is never cut off. Blocking sockets have no write timeout, so the accept thread sweeps for stalled writes once a second |
| Refused requests | a reply that leaves request bytes unread (a body over the cap, a refused `Host`, a malformed head) is followed by a lingering close: the write side is shut down and the rest of the request discarded for up to 5 s, so the client reads the error reply instead of a connection reset |
| Accept failures | a failed `accept` (out of file descriptors or threads, a connection reset before it was accepted) never stops the listener: it is retried with a pause that grows to 1 s for as long as it lasts, with a warning in the log on the first failure of a run and about once a minute after |
| SSE | chunked transfer-encoding; `keepAliveInterval` pings are written by the connection thread when the stream has been quiet that long |
| Vanished clients | while a reply or a GET stream is quiet, the read side is probed once a second; a closed peer interrupts the request and releases the session's GET slot. A request that finishes within a second is never probed |
| Shutdown | closing the server's scope closes the listener and every connection. ZIO signal handlers are no-ops on Scala Native, so SIGTERM there ends the process by the OS default |
| Diagnostics | `FASTMCP_HTTP_DEBUG=1` prints per-connection failures to stderr |

**Known limitation on Scala Native (0.5.12).** Scala Native allocates objects of 8 KiB and up
through a separate large-object allocator, and that allocator's slow path is not safe when several
threads use it at once: it retries a single time after growing the heap by exactly one object, and
a thread that loses the race receives a null pointer and the process aborts with `Unhandled signal
11`. This is [scala-native#4916](https://github.com/scala-native/scala-native/issues/4916); it is
fixed on Scala Native's main branch (#4935) but 0.5.12, the latest release, does not contain the
fix. It reproduces with four threads allocating 8 KiB arrays in a loop, with no ZIO and no
fast-mcp-scala code, under both the Immix and Commix collectors, on macOS and Linux, and it has no
configuration switch; a larger `GC_INITIAL_HEAP_SIZE` makes it rarer but does not remove it. The request path of this server is kept below the threshold — per-connection buffers start at
4 KiB and a small request allocates nothing that large — so ordinary small tool calls do not reach
it. Concurrent requests or replies of 8 KiB and up do: in a stress test with 16 KiB request bodies
on 16 connections the process aborted within seconds in most runs (6 of 10).

**Memory on Scala Native (0.5.12).** Resident memory also grows slowly under sustained load and is
not given back: roughly 10 to 30 bytes per request, which is 1 to 3 GiB per hour at 30,000
requests per second. It is a real leak, not a heap settling — with `GC_MAXIMUM_HEAP_SIZE=128m` the server ran
out of heap after about 3.7 million 2026-07-28 requests — and it is in the runtime: the same
workload on the JVM holds a flat 9 MB live heap. Runtime-allocated weak references are the likely
cause (zio-json's encoder pool allocates one after each collection, and 0.5.12 does not reclaim
weak references under churn).

**Both are fixed on Scala Native's main branch.** The same server built entirely against the
nightly `0.5.13-20261107-69432d9-SNAPSHOT` survived the 16 KiB stress in 10 of 10 runs and held a
flat 79 MB for a four-minute soak (9 million requests) under the 128 MiB cap that kills the 0.5.12
build. Until a Scala Native release carries those fixes, treat Scala Native HTTP as suitable for
small payloads and bounded uptime, run it under a supervisor that restarts it, and expect the
`@experimental` gate to stay. fast-mcp-scala itself keeps building against the released 0.5.12: a
published library cannot depend on a snapshot, and the runtime is linked by the application's own
Scala Native version in any case — an application that accepts a nightly toolchain gets the fixes
without a new fast-mcp-scala release. The JVM socket backend is not affected by either problem.

Two more Scala Native specifics: request fibers are not registered in `Fiber.roots` (each
registration allocates a `WeakReference`, which Scala Native does not reclaim at request rates —
the heap grew by gigabytes per minute under load before this was switched off), and the listener
is plaintext only because `javax.net.ssl` is absent from the Scala Native javalib.

## Platform notes

| | JVM | Scala.js / Bun | Scala Native |
|---|---|---|---|
| stdio | `System.in` / `System.out` (`JvmTransportBackend`) | Node `process.stdin` / `stdout` (`JsTransportBackend`) | `System.in` / `System.out`, ids from `/dev/urandom` (`NativeTransportBackend`) |
| HTTP | ZIO HTTP on netty (`JvmHttpBackend`); NIO inside GraalVM images, `-Dfastmcp.http.channelType` overrides. Opt-in: the socket server (`JvmSocketHttpBackend`) | `Bun.serve` (`JsTransportBackend`) | the socket server (`NativeHttpBackend`) — zio-http has no Native artifacts |
| Legacy GET push stream | ✅ | 405 | ✅ |

Node and Deno parity for the HTTP listener is a follow-up; only the `Bun.serve(...)` entry point is
Bun-specific today. `httpEndpoint` is matched as an exact path on Bun; the JVM additionally accepts
a trailing slash (`/mcp/`). Both ignore the query string.
