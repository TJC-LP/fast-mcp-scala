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

This binds `127.0.0.1:8090` with [Host / Origin validation](#host--origin-validation) on: a
loopback bind gets it without any setting.

### Host / Origin validation

**Binding loopback alone does not protect against browser-originated requests.** A loopback bind
keeps other machines out; a web page open in a browser on the same machine can still reach the
port. What tells such a page apart is the `Host` (and `Origin`) it sends, so the HTTP transport
checks both on every request to the endpoint — whatever its method, before the method is judged,
the body is read or any session state is touched — and answers **403** (`-32000`,
`Host/Origin not allowed (DNS-rebinding protection)`) when the check fails.

| `host` (bind address) | `allowedHosts` | Guard |
|---|---|---|
| loopback: `127.0.0.0/8`, `::1`, `localhost` (the default `127.0.0.1` included) | `None` (default) | **On, automatically.** `Host` must be `localhost`, `127.0.0.1`, `[::1]` or the bound address itself — on any port |
| any | `Some(names)` | On, with exactly those names |
| not loopback: `0.0.0.0`, `::`, a LAN address, a host name | `None`, and `allowedOrigins` unset | **Off — and a WARN is logged at startup.** The names clients use cannot be inferred |
| any | `None`, with `dnsRebindingProtection = false` | Off, by explicit opt-out |

The rules, identical on every backend (zio-http, the socket backend, `Bun.serve`):

- **`Host`** must parse as one `host[:port]` authority whose hostname is an allowed name. The port
  is not compared, so a forwarded port (`ssh -L 9000:127.0.0.1:8000`, an IDE port-forward) keeps
  working, but it must be a port: a `Host` whose port is not 1–65535 in decimal digits
  (`localhost:99999`, `localhost:abc`) and a `Host` sent more than once are refused. A request
  with no `Host` passes: that is not something a browser can do.
- **IPv6 literals are written with their brackets** in `allowedHosts`, exactly as they appear in a
  `Host` header: `Set("[::1]")`. An unbracketed entry (`Set("::1")`) matches no request, so every
  request is refused.
- **`Origin`**, when present, must be the same origin as the request's `Host` (same host and
  port), or be listed in `allowedOrigins`. A page on another loopback port
  (`http://localhost:3000`), `Origin: null`, and malformed values are refused. A request with no
  `Origin` (every non-browser client) passes.
- A loopback bind address is recognised from the `host` string alone — `localhost`, a
  `127.x.y.z` literal, `::1` in any spelling. A host name that merely resolves to loopback (an
  `/etc/hosts` alias) is not: list it in `allowedHosts`.

Deployments:

- **Local development (the default).** Nothing to configure. MCP clients, `curl` and test
  harnesses that reach the server as `localhost` / `127.0.0.1` / `[::1]` are unaffected.
- **A browser front-end on another origin** (a dev server on `http://localhost:5173`): add
  `allowedHosts = Some(Set("localhost", "127.0.0.1"))` and
  `allowedOrigins = Some(Set("http://localhost:5173"))`. (With `allowedOrigins` alone, only the
  listed origins pass.) You also need a CORS-terminating proxy; see below.
- **A loopback listener behind a reverse proxy that forwards its own `Host`** (`Host:
  mcp.example.com` arriving at `127.0.0.1:8000`): list the forwarded name —
  `allowedHosts = Some(Set("mcp.example.com"))`. An explicit list replaces the automatic names,
  so add `"localhost"` if local clients must keep working. This deployment worked without any
  setting before 1.1.0 and now needs this one line.
- **A non-loopback bind** (`host = "0.0.0.0"` in a container): set `allowedHosts` to the public
  name(s) and `allowedOrigins` for browser front-ends, **and** put an authenticating proxy in
  front. The guard is not authentication: a non-browser client can send any `Host` it likes.
- **Opting out**: `dnsRebindingProtection = false` accepts every `Host` and `Origin` and silences
  the startup warning. It is only valid with both allow-lists unset (`runHttp()` refuses the
  contradictory combination at startup). Use it when a trusted proxy in front of the listener
  already does this check and you cannot enumerate the names it forwards; otherwise prefer
  `allowedHosts`.

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

Modern Streamable HTTP is identical on the JVM (ZIO HTTP), the socket backend and Bun
(`Bun.serve`). Every backend serves plaintext HTTP/1.1 only — there is no TLS setting by design;
terminate TLS and authentication at a reverse proxy in front of the MCP endpoint.

**There is no CORS handling.** No backend adds `Access-Control-*` headers, and an `OPTIONS`
preflight is answered 405 like any other method the endpoint does not serve: a browser-hosted
client on another origin needs a CORS-terminating proxy in front of the endpoint (and its origin
listed in `allowedOrigins`; see [Host / Origin validation](#host--origin-validation)).

### The endpoint: path and methods

**Path.** A request is on the MCP endpoint when its path **equals** `httpEndpoint` (default
`/mcp`; a setting without the leading slash, `"mcp"`, means the same) — an exact, case-sensitive
match on every backend. A trailing slash (`/mcp/`), another case (`/MCP`), a longer path
(`/mcp/extra`) and a prefixed one (`/api/mcp`) are all **404** with an empty body, before any other
check. The query string is not part of the path: `/mcp?x=1` is the endpoint, and the query is
ignored. Because the match is exact, `runHttp()` refuses at startup an `httpEndpoint` that no
request path could equal: one containing `?`, `#`, `%`, whitespace or a control character, a
`.` / `..` segment, or any character a client would percent-encode (non-ASCII, `"`, `<`, `[`,
`\` and the like). Letters, digits and `-._~!$&'()*+,;=:@` are what a path segment may hold.

What each backend compares is the path as its HTTP layer hands it over. The socket backend
compares the request target exactly as written. zio-http normalises the target first — it decodes
percent-escapes, drops empty segments and resolves `.` segments — so `/%6dcp`, `//mcp` and
`/./mcp` reach the endpoint there and are 404 on the socket backend; Bun resolves `.` / `..`
segments (`/./mcp` is the endpoint) and leaves escapes and empty segments alone. A client that
writes the path plainly sees no difference, and a proxy in front should route the plain path only.

**Methods.** After the path, every request meets the Host/Origin guard (403), and only then is its
method judged. A method the endpoint does not serve is **405** with an `Allow` header listing the
ones it does and a JSON-RPC `-32000` body that names the method (a `HEAD` gets the status and
headers only):

| Mode | `Allow` |
|---|---|
| legacy session adapter, zio-http and socket backends | `POST, GET, DELETE` (`POST, GET` with `disallowDelete`) |
| legacy session adapter, Bun (no standalone GET stream) | `POST, DELETE` (`POST` with `disallowDelete`) |
| `stateless = true`, every backend | `POST` |
| a GET or DELETE carrying `MCP-Protocol-Version: 2026-07-28` | `POST` (that revision uses neither) |

So `PUT`, `PATCH`, `OPTIONS`, `HEAD` and `TRACE` are 405 everywhere, as is a GET on Bun or on the
stateless adapter. Before 1.1.0 zio-http answered these methods 404 without running the guard.

### Request requirements and error mapping

Every POST — legacy and modern — must carry `Content-Type: application/json` (media-type
parameters are allowed, but a `charset` other than utf-8 is refused); anything else is refused with
415 before the body is read or a session is minted. Modern POST requests must
additionally include:

- an `Accept` header that allows both `application/json` and `text/event-stream`
- `MCP-Protocol-Version: 2026-07-28`
- `Mcp-Method`, and for tool calls, resource reads, and prompt gets also `Mcp-Name`

The protocol version and client capabilities are repeated in every request's `params._meta`.
Schema-driven `Mcp-Param-*` values may be supplied through `x-mcp-header`.

`Accept` is read as RFC 9110 defines it, not searched for a substring: media ranges are compared
whole and case-insensitively, `type/*` and `*/*` are honoured, the most specific range that
matches decides, and `q=0` means "not acceptable" — `Accept: */*;q=0` allows nothing, and
`application/json, text/event-stream;q=0` does not allow an event stream. On a legacy POST an
absent `Accept` passes, and a present one must allow `application/json` (and `text/event-stream`
too unless `stateless = true`); a refusal is 406 with `-32000`. A POST that declares 2026-07-28
must send the header and allow both types; every way of failing that is the same reply: 406,
`-32020`, `Accept must include application/json and text/event-stream`.

Each of `MCP-Protocol-Version`, `Mcp-Method`, `Mcp-Name` and every `Mcp-Param-*` must appear
**once**. A request that carries one of them more than once is refused as a header/body mismatch
(400, `-32020`) on every backend, whatever the values and their order and even when they agree —
the server never picks one occurrence. A repeated `MCP-Protocol-Version` is refused on any POST,
legacy ones included. On Bun the runtime joins a repeated header into one `", "`-separated value
before the server sees it, so the server cannot count field lines there: a joined
`MCP-Protocol-Version` is refused as above, and a joined `Mcp-Method` / `Mcp-Name` / `Mcp-Param-*`
is compared with the body as that one value — a mismatch unless the body's value is literally the
joined string.

| Condition | HTTP | JSON-RPC code |
|---|---|---|
| `Host`/`Origin` refused by the Host/Origin guard (automatic on a loopback bind, or `allowedHosts` / `allowedOrigins`) | 403 | `-32000` |
| Path is not the endpoint | 404 | none (empty body) |
| Method the endpoint does not serve (`Allow` lists the ones it does) | 405 | `-32000` |
| Missing or wrong `Content-Type` (any POST; a non-utf-8 `charset` counts as wrong) | 415 | `-32000` |
| `Accept` does not allow the reply types — legacy POST or GET | 406 | `-32000` |
| `Accept` absent or not allowing both reply types — 2026-07-28 POST | 406 | `-32020` |
| Legacy GET or DELETE without an `Mcp-Session-Id` (an empty header counts as absent) | 400 | `-32000` |
| Legacy request naming a session that does not exist | 404 | `-32001` |
| Body larger than `maxRequestBodyBytes` | 413 | `-32000` on first-party paths; netty and Bun answer an empty 413 when they refuse the body first |
| Frame over `limits` (`maxFrameChars` / `maxDepth` / `maxObjectFields` / `maxArrayElements` / `maxValues` / `maxNumberDigits` / `maxNumberExponent`) | 400 | `-32700` |
| Header/body mismatch, or a routing header sent more than once | 400 | `-32020` |
| Missing required client capability | 400 | `-32021` |
| Unsupported protocol version (`data.supported` lists every version the server speaks) | 400 | `-32022` |
| Unknown request method | 404 | `-32601` |

The admission gates run in a fixed order on every backend, on headers first and before any session
state is touched: unknown path 404 → Host/Origin guard 403 → method 405 → `Content-Type` 415 →
declared `Content-Length` over `maxRequestBodyBytes` 413 → `Accept` 406 → body read (platform byte
cap, then a first-party post-read check → 413) → frame limits (`-32700`, HTTP 400) → dispatch. A
legacy GET or DELETE continues after the method check with: a 2026-07-28 version header 405 → (GET
only: unsupported `MCP-Protocol-Version` 400 → `Accept` 406) → missing `Mcp-Session-Id` 400 →
unknown session 404. A
legacy `initialize` meets the session cap (`maxSessions`) only after it has succeeded — see
[The legacy session store](#the-legacy-session-store). Any transport or
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
(`Some(1000)` by default) — [The legacy session store](#the-legacy-session-store) below has the
rules. The periodic idle sweeper never evicts a session with a live
GET stream while capacity is free. DELETE, idle eviction, and cap eviction all go through
`Session.terminate`: a request still in flight on that session is interrupted (no reply) and its
running legacy tasks are released. Activity is stamped when a request arrives, not while it runs,
so a call that outlasts `sessionIdleTimeout` on an otherwise quiet session can be evicted
mid-flight and never answered (a `-32001` reply is tracked for 1.0.1) — keep the timeout above
your longest call, or poll through Tasks. When that happens the server logs a WARN naming the
method and the session (`Request 'tools/call' ended without a reply: session … was terminated
while it ran`); a request the client cancelled itself is not logged at WARN.

A GET or DELETE without an `Mcp-Session-Id` is answered **400** (`-32000`,
`mcp-session-id header is required`), as is any POST other than `initialize`; an id the store does
not hold is **404** (`-32001`). An empty or blank `Mcp-Session-Id` header counts as absent on every
backend, and one sent more than once is read as its `", "`-joined value, which names no session
(404). Before 1.1.0 a session-less GET or DELETE was 405, and the JVM looked an empty id up and
answered 404.

A GET stream's slot (one stream per session; a second GET is answered 409) is given back when the
stream ends, when the client disconnects, and when the reply is never streamed at all — a client
that closes its connection as the response head goes out, before the server has started the
stream. The same holds for the reply to a POST: a request whose reply stream is never started is
stopped rather than left running. The zio-http and socket backends do both as soon as they see
the connection close, and in any case it happens within 5 seconds of the reply being built (the
shared handler gives up on a streamed reply that nothing has started by then; such a reply, run
later, is an empty stream).
Removing a session and terminating it are one step, so a `DELETE` or an at-cap `initialize` whose
client goes away mid-way cannot leave a session that is gone from the store but still running.

A GET peer that disappears without closing its connection (NAT expiry, a suspended machine) leaves
the stream "live" from the server's point of view: the OS only notices such a peer once the server
writes to the socket — that is, with `keepAliveInterval` set — and only after its own TCP
retransmission budget (zio-http 3.11.4 exposes no accepted-socket option, so the server does not
set TCP keepalive itself). The cap-time rules below bound the session store regardless.

`stateless` controls **only** this adapter. Modern requests are stateless regardless of the flag.
Leaving it `false` (the default) lets older clients fall back to the initialize/session/GET/DELETE
flow; setting it `true` disables that legacy session store. On the stateless legacy adapter all
clients share one session identity, which is why legacy task requests there are refused
(see [tasks.md](./tasks.md)).

#### The legacy session store

**A session is stored only for an `initialize` that succeeded.** A header-less `initialize` is
answered on a session that is not in the store yet. If it fails — params that do not decode, or a
`clientInfo` / `capabilities` over the budget below — the reply carries the JSON-RPC error and no
`Mcp-Session-Id`, and the store is exactly as it was: no slot is taken and nobody is evicted. Only a
successful `initialize` meets the cap, and the cap check, the choice of the session that makes room
and the insert are one atomic step, so the store never exceeds `maxSessions` however many
initializes race. Because the session id travels in the response head, the reply to a header-less
`initialize` is sent once the handler has finished (it is not opened early after the one-second
grace other legacy replies have).

**What a session keeps from its client is bounded.** A stored session holds the `clientInfo` and
`capabilities` of its `initialize` for as long as it lives, and both have open members (`icons`,
`experimental`, `extensions`, the per-capability objects). Together they may hold at most
**256 JSON values** (every object, array, string, number, boolean and null in the two members) and
**16 KiB of text** (16,384 UTF-16 code units of member names, string values and number digits). An
`initialize` over either bound is refused with `-32602` on the legacy streamable adapter. The
budget is a fixed constant: it does not grow with `maxRequestBodyBytes` or `limits.maxFrameChars`.
An ordinary `initialize` uses a few dozen values and a few hundred characters; the one thing that
can meet the bound in practice is an icon sent inline as a large `data:` URI. Stdio and the
sessionless adapter (`stateless = true`) store no session and are not subject to the budget.

**Sizing the heap.** The store's worst case is `maxSessions` multiplied by what one session may
hold:

| Per session | Bound | Worst case |
| --- | --- | --- |
| `clientInfo` + `capabilities` | 256 JSON values, 16 KiB of text | under 100 KiB of heap on the JVM: the text at up to two bytes a character (32 KiB) plus object overhead per value |
| `resources/subscribe` URIs (only with `resourcesSubscribe = true`) | `limits.maxSubscriptionsPerSession` × `limits.maxUriChars` (1024 × 8192) | 8 Mi characters, about 16 MiB |
| Legacy tasks (only with `tasks.enabled`) | `TaskSettings` caps, in entries and in bytes | see [tasks.md](tasks.md#memory-bounds) |
| Messages buffered for the GET stream (JVM; server-emitted, not client-supplied) | `limits.maxOutboundMessages` messages (256); a count, not a byte bound | see [Messages outside a request](#messages-outside-a-request) |

At the defaults (`maxSessions = Some(1000)`, subscriptions and tasks off) the client-supplied data
the store can pin is therefore at most about **100 MiB**, and a few hundred kilobytes in normal
use. The value and character bounds are exact; the heap figure is a rounded-up estimate of object
overhead on top of them (sessions built to sit exactly at the budget measured between 5 and 50 KiB
each on a 64-bit JVM, depending on the shape). `maxSessions = None` removes the bound on the number
of sessions and with it any worst case. If you enable `resourcesSubscribe` for untrusted clients, lower
`maxSubscriptionsPerSession`, `maxUriChars` or `maxSessions` so their product fits your heap.

**Who makes room at the cap.** When a successful `initialize` finds the store full, one stored
session is terminated to make room. Sessions are counted per client — the TCP peer address the
backend reports for the `initialize` that opened them:

1. If the newcomer's own client already holds as many sessions as any other client, it replaces
   one of its own. A client at the top can only grow at its own expense.
2. Otherwise, if some client holds at least two sessions more than the newcomer's client, the
   client holding the most gives one up.
3. Otherwise holdings are level and the idle rules decide over the whole store: the longest-idle
   session without a live GET stream; failing that, the longest-idle session with a live GET that
   has been idle longer than `sessionIdleTimeout`. If neither exists, the store is full of clients
   that each hold their share, and the `initialize` is refused with 503.

In cases 1 and 2 the session given up is that client's longest-idle one without a live GET stream,
or else its longest-idle one outright: its GET stream is closed and the client reconnects (a later
request on the evicted session answers 404, and a client then initializes again). Holding a GET
stream open and touching a session keeps it out of the idle rules, but not out of cases 1 and 2,
so one client cannot fill the store with exempt sessions and have every other client refused.

Two limits of this accounting are worth knowing:

- **Behind a reverse proxy every session carries the proxy's address**, so all clients count as
  one: case 1 always applies and the store behaves as a least-recently-used cache (a full store
  admits a newcomer by closing the longest-idle session, live GET or not, instead of answering
  503). Per-client fairness then belongs in the proxy — authenticate there and limit sessions or
  connections per principal.
- **It counts addresses, not people.** Clients behind one NAT share a count, and a party that
  controls many addresses holds one share per address. The cap is not a rate limiter: put one in
  front of a server exposed to untrusted networks.

A backend that reports no peer address (a custom backend, or an in-memory test) treats every
session as its own client, so only case 3 applies.

#### Messages outside a request

Most server→client messages ride the response stream of the request that caused them. A message
emitted when no such stream exists goes to the session's out-of-request channel instead. Today
that is what a **legacy task** emits once its creating POST has been answered — the
`notifications/tasks/status` of every terminal transition, and any log, progress or
server→client request message of the task body — and, on stdio, every server-pushed message. The
channel holds at most `limits.maxOutboundMessages` messages (default 256) per session, whether or
not anything reads it:

| Session | Reader | At the bound |
|---|---|---|
| stdio (every platform) | the stdout writer | back-pressure: the emitter waits for the writer, nothing is lost; after stdin EOF nothing is buffered |
| legacy session, JVM | the standalone GET stream, when the client opens one | the oldest buffered message is dropped; the emitter is never blocked or interrupted. A GET opened later receives the newest messages still buffered |
| legacy session, Bun | none (GET answers 405) | nothing is buffered: the message is discarded |
| 2026-07-28 HTTP request, legacy stateless adapter | none | nothing is buffered: the message is discarded |

So on Bun, and for a JVM client that never opens its GET stream, task status must be polled with
`tasks/get`, and a server→client request issued by a task body after its POST was answered gets
no reply (it fails with its timeout). A JVM client whose GET stream falls more than
`maxOutboundMessages` behind loses the oldest messages, not the newest.

### `McpServerSettings` reference

| Setting | Default | Description |
|---|---|---|
| `host` | `127.0.0.1` | Bind address. Changed in 0.5.0 from `0.0.0.0` per the spec's bind-localhost guidance; set `"0.0.0.0"` explicitly for containers or external exposure — and then set `allowedHosts` / `allowedOrigins` too: a non-loopback bind is not guarded automatically, and startup logs a WARN until an allow-list is set. See [Host / Origin validation](#host--origin-validation). |
| `port` | `8000` | Listen port. |
| `httpEndpoint` | `/mcp` | JSON-RPC endpoint path, matched exactly (no trailing slash, case-sensitive; the query string is ignored). Letters, digits, `/` and `-._~!$&'()*+,;=:@` only, and no `.` / `..` segment — `runHttp()` fails at startup otherwise. See [The endpoint: path and methods](#the-endpoint-path-and-methods). |
| `stateless` | `false` | Disable the legacy HTTP session store; modern requests are always stateless. |
| `disallowDelete` | `false` | Legacy adapter: answer 405 to HTTP DELETE instead of terminating the session. |
| `sessionIdleTimeout` | `30 minutes` | Evict legacy sessions with no client activity. Live legacy GET streams are exempt from the periodic sweep but become evictable at the `maxSessions` cap once idle this long; `None` disables both. |
| `keepAliveInterval` | `None` | When set, emit SSE heartbeats on quiet streams so proxies do not kill long calls. The legacy GET stream heartbeats from the first byte. The reply to a POST waits for the handler's first frame for at most one interval: a call that is still silent then gets its SSE response opened with a heartbeat (since 1.1.0; see "The modern path"). With `None`, a 2026-07-28 reply waits for the first frame however long that takes, and a legacy reply opens its stream after one second. Neither listener closes a quiet stream on its own: the JVM has no idle timeout and Bun runs with `idleTimeout: 0` (its 10 s default used to cut a slow tool's reply). Heartbeats are also what lets the OS detect a GET peer that vanished without closing its connection (the socket is only probed when the server writes to it, and the OS gives up after its retransmission budget); without them such a stream stays live until the cap-time eviction described under `maxSessions`. |
| `allowedHosts` | `None` (automatic) | The `Host` names of the DNS-rebinding/CSRF guard ([Host / Origin validation](#host--origin-validation)). `None` is **automatic**: on a loopback bind the guard is on for `localhost`, `127.0.0.1`, `[::1]` and the bound address; on any other bind it is off and startup warns. `Some(names)` uses exactly those names on any bind address (`Some(Set.empty)` reads as `None`). With the guard on: the `Host` value must parse as one `host[:port]` authority and its hostname (or the verbatim `host:port`) must be listed — the port itself is not compared; a present `Origin` must be the same origin as the request `Host` (`scheme://host:port`; scheme not compared; a port-less `Host` admits `http://h` and `https://h`) or appear in `allowedOrigins`; cross-port loopback origins, `null`, a `Host` or `Origin` sent more than once (seen as its `", "`-joined value), and malformed `Host`/`Origin` ports are refused (403), with or without the other header. IPv6 entries are written bracketed, exactly as they appear in the `Host` header: `Set("[::1]")`, not `Set("::1")`. |
| `allowedOrigins` | `None` | Extra browser origins (`https://app.example.com`, `http://localhost:5173`) admitted in addition to the request's own authority **when `allowedHosts` is also set**; with `allowedOrigins` alone only the listed origins pass (the request's own authority is then refused 403 too), and the `Host` is held to the automatic loopback names on a loopback bind and not checked on any other bind. Malformed entries fail `runHttp()` at startup. |
| `dnsRebindingProtection` | `true` | The explicit opt-out of the Host/Origin guard (since 1.1.0). `false` accepts every `Host` and `Origin` and silences the non-loopback startup warning; it must not be combined with `allowedHosts` / `allowedOrigins` (`runHttp()` fails at startup). Prefer listing the proxied name in `allowedHosts`. |
| `maxRequestBodyBytes` | `1 MiB` | Request body cap on every backend; larger bodies get 413 before decoding (empty 413 on the wire from netty/Bun, JSON-RPC `-32000` on first-party paths); must not exceed `limits.maxFrameChars`. This is the cap a large `sampling/createMessage` or elicitation *result* meets over HTTP (a legacy client then waits out its 60 s request timeout): raise it together with `limits.maxFrameChars` — see [Input limits](#input-limits). |
| `maxSessions` | `Some(1000)` | Cap on stored legacy sessions. A session is stored only for an `initialize` that succeeded, and keeps at most 256 JSON values / 16 KiB of text from it, so the store's worst case is `maxSessions` × the per-session budget (about 100 MiB at the defaults). At the cap a client holding more sessions than the newcomer's gives one up; among clients holding their share, the longest-idle session without a live GET is evicted, else the longest-idle GET-holding session idle longer than `sessionIdleTimeout`; 503 only if nobody qualifies. Unauthenticated initializes can evict idle sessions — front non-loopback deployments with auth. `None` disables. See [The legacy session store](#the-legacy-session-store). |
| `loggingEnabled` | `false` | Advertise logging; modern clients use per-request `_meta` levels, legacy clients `logging/setLevel`. |
| `resourcesSubscribe` | `false` | Enable legacy `resources/subscribe`; modern clients use `subscriptions/listen`. |
| `exposeTemplatesEndpoint` | `false` | List registered resource templates through `resources/templates/list`. `resources/list` never lists templates; with the default the templates endpoint answers an empty page (clients derive templates from `{}` URIs) and `resources/read` on a matching URI works either way. Applies on every transport. |
| `contextHeaders` | `Set.empty` | HTTP request headers a handler may read through `McpContext.header` / `McpContext.headers`; nothing is exposed unless listed. See "Request headers in handlers". |
| `tasks` | `TaskSettings()` | The Tasks extension, off by default (see [tasks.md](./tasks.md)). |
| `limits` | `LimitSettings()` | Input bounds on every transport: `maxFrameChars` 4 MiB, `maxDepth` 64, `maxObjectFields` 1024, `maxArrayElements` 4096, `maxValues` 65536, `maxNumberDigits` 512, `maxNumberExponent` 512 (`-32700` / HTTP 400 before dispatch), `maxUriChars` 8192 and `maxSubscriptionsPerSession` 1024 (`-32602`); plus `maxOutboundMessages` 256, the per-session buffer for messages emitted outside a request. See [Input limits](#input-limits). |

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
| `maxOutboundMessages` | `256` | Server→client messages one session buffers outside a request's own response stream — see [Messages outside a request](#messages-outside-a-request). Applies to the stdio session (back-pressure at the bound) and to each legacy JVM HTTP session (oldest dropped at the bound); sessions with no reader buffer nothing. |

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
on `tools/call`, an error reply on `prompts/get` and `resources/read`. (On `tools/call` the string
only gets that far when the tool's `inputSchema` admits a string for the parameter or input
validation is off: a schema derived from `BigDecimal` advertises `number`, and
[input-schema validation](custom-types.md#what-the-server-enforces) refuses a string for it first,
also with `isError: true`. Frame limits run before that validation, and the decoder check after
it.) For string-encoded numbers
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
| Strict parsing | the server refuses what a proxy in front of it could read differently, with a `400` and a closed connection: a line of the request head not ending in CRLF (a bare LF or a bare CR, also before the request line), NUL / another control character / DEL anywhere in the head (HTAB is allowed inside a field value), a request line that is not `method SP target SP version` exactly, a method or field name with a character outside ASCII `tchar`, obsolete line folding. Only SP and HTAB are trimmed around a field value, and `Content-Length` is ASCII digits |
| Chunked request bodies | read exactly as RFC 9112 §7.1 writes them: the chunk size is 1–7 hex digits from the first byte of its line, optionally followed by chunk extensions (ignored; control characters in them are refused); the size line, the chunk data and every trailer line end in CRLF; trailer lines must be field lines (their content is not used). Any other framing is a `400` and the connection is closed |
| Connections | 256 concurrent by default; more wait in the kernel backlog. The cap is a hard limit, so three timeouts keep a client from holding a slot without making progress. All four are set with `JvmSocketHttpBackend.withSettings(maxConnections = n, ...)` on the JVM or `NativeHttpBackend.withSettings(...)` on Scala Native (experimental, like the backend itself); the backend class has no public constructor, so on Scala Native there is no way to build one outside the `-experimental` opt-in |
| `idleTimeout` (60 s) | a keep-alive connection with no request activity is closed |
| `requestReadTimeout` (60 s) | the allowance for a whole request, head and body, from its first byte (408 when it runs out). Every 4 KiB received extends it by one second, so a slow client that is still sending a large body gets the time it needs, while one sending a byte or a line at a time cannot keep the connection |
| `writeStallTimeout` (30 s) | a write that makes no progress for this long — the client stopped reading a large reply or an event stream — closes the connection. Replies go out in 16 KiB slices and the clock restarts with each, so a slow client that is still reading is never cut off. Blocking sockets have no write timeout, so the accept thread sweeps for stalled writes once a second |
| Refused requests | a reply that leaves request bytes unread (a body over the cap, a refused `Host`, a malformed head) is followed by a lingering close: the write side is shut down and the rest of the request discarded for up to 5 s, so the client reads the error reply instead of a connection reset |
| Accept failures | a failed `accept` (out of file descriptors or threads, a connection reset before it was accepted) never stops the listener: it is retried with a pause that grows to 1 s for as long as it lasts, with a warning in the log on the first failure of a run and about once a minute after |
| SSE | chunked transfer-encoding; `keepAliveInterval` pings are written by the connection thread when the stream has been quiet that long |
| Vanished clients | while a reply or a GET stream is quiet, the read side is probed once a second; a closed peer interrupts the request and releases the session's GET slot. A request that finishes within a second is never probed. A reply that cannot be delivered is released without being streamed; the shared handler's 5-second bound on an unstarted reply covers whatever is left |
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
Bun-specific today. `httpEndpoint` is matched as an exact path on every backend, and the query
string is ignored ([The endpoint: path and methods](#the-endpoint-path-and-methods)).

One Bun-only refusal: Bun builds a URL from the request line and the `Host` header before the
server sees the request, and a `Host` it cannot parse (`Host: 127.0.0.1:99999`, `localhost:abc`)
leaves no URL to read the path from. With the Host/Origin guard on — the default on a loopback
bind — that `Host` is refused by the guard itself, 403, as on the JVM. With the guard off (a
non-loopback bind with no allow-list, or `dnsRebindingProtection = false`) Bun answers 400
`-32000` `Malformed request URL`, where the JVM backends, which do not build a URL, serve the
request.
