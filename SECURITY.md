# Security Policy

## Supported versions

| Version | Supported |
|---|---|
| latest `1.x` release | ✅ security fixes as patch releases |
| `1.0.0-RC*`, `0.x` | ❌ upgrade to the latest `1.x` |

## Reporting a vulnerability

Please report security issues privately through
[GitHub's private vulnerability reporting](https://github.com/TJC-LP/fast-mcp-scala/security/advisories/new)
for this repository. Do **not** open a public issue, discussion, or pull request for a suspected
vulnerability.

Include what you can of:

- a description of the issue and its impact
- the affected artifact and version (`fast-mcp-scala_3`, `fast-mcp-scala_sjs1_3`, or
  `fast-mcp-scala_native0.5_3`)
- steps or a minimal server that reproduces it
- a suggested fix, if you have one

You will receive an acknowledgement within five business days. Confirmed issues are fixed in a
patch release, disclosed through a GitHub Security Advisory, and credited in the
[CHANGELOG](CHANGELOG.md) unless you prefer otherwise. Critical (P0) issues follow the
response targets in [ROADMAP.md](ROADMAP.md).

## Scope

fast-mcp-scala is a **server** library. It implements the MCP wire protocol, transports, and
request routing. It does not implement an authorization server, token issuance, or credential
storage; deployments must put their own authorization in front of the MCP endpoint.

Hardening knobs worth knowing when you deploy over HTTP
([docs/transports.md](docs/transports.md)):

- `host` defaults to `127.0.0.1`; opt in to external exposure explicitly. **Binding loopback
  alone does not protect against browser-originated requests**: it keeps other machines out, not
  a web page open in a browser on the same machine. That is the job of the Host/Origin guard in
  the next item, which is why it is on by default for a loopback bind.
- The DNS-rebinding / browser-CSRF guard checks every request's `Host` and `Origin` (403 on
  refusal). The `Host` hostname must be an allowed name, and a present `Origin` is matched as a
  full origin (`scheme://host:port`) against the request `Host` or the `allowedOrigins`
  allow-list; cross-port loopback origins, `null`, and malformed ports are refused. Which names
  are allowed:
  - **loopback bind, nothing configured (the default): on automatically**, for `localhost`,
    `127.0.0.1`, `[::1]` and the bound address, on any port;
  - `allowedHosts = Some(names)`: exactly those names, on any bind address. IPv6 literals are
    listed in bracket form (`"[::1]"`). This is what a loopback listener behind a reverse proxy
    that forwards its own `Host` needs;
  - **non-loopback bind (`0.0.0.0`, a LAN address) with no allow-list: OFF**, because the names
    clients use cannot be inferred — startup logs a WARN. Such a deployment needs `allowedHosts`
    (and `allowedOrigins` for browser front-ends on another origin) **and** an authenticating
    proxy in front of it;
  - `dnsRebindingProtection = false` is the explicit opt-out: every `Host` and `Origin` is
    accepted. Use it only when a trusted proxy in front of the listener does this check and you
    cannot enumerate the names it forwards.

  The guard stops browser-driven attacks only; it is not authentication. A non-browser client can
  send any `Host` it likes.
- Every HTTP backend (zio-http, `Bun.serve`, the socket server) serves plaintext HTTP/1.1 only; there is no TLS setting by design. Terminate
  TLS and authentication at a reverse proxy in front of the MCP endpoint.
- The socket HTTP backend (the default on Scala Native, opt-in on the JVM) serves each connection
  on its own thread and admits at most `maxConnections` (256) at once; unlike the event-loop
  backends, that is a hard ceiling on concurrent clients, including idle keep-alive connections
  and open event streams. Idle connections (60 s), requests that arrive too slowly
  (a 60 s allowance extended by one second per 4 KiB received, then 408) and writes blocked on a client that stopped reading (30 s) are closed so a stalled
  client cannot hold a slot indefinitely, but a client that keeps making progress within those
  limits can hold one, and 256 such clients exhaust the listener. Put it behind a reverse proxy
  that limits connections per client when it is reachable by untrusted peers.
- The socket HTTP backend reads HTTP/1.1 strictly, because a proxy in front of it and the server
  must agree on where each request ends: lines end in CRLF only, control characters are refused
  (HTAB is allowed inside a field value), only SP and HTAB count as whitespace, and chunked
  framing must be exact. Anything else is answered `400` and the connection is closed. This
  narrows what a front end can be made to disagree about; it does not replace a proxy that
  normalises or rejects malformed requests itself.
- On a 2026-07-28 POST the routing headers (`MCP-Protocol-Version`, `Mcp-Method`, `Mcp-Name`,
  `Mcp-Param-*`) must each appear once: a repeat is refused with `-32020` on every backend, so a
  gateway that authorises on one occurrence and the server can never act on different ones. A
  gateway should still reject repeats itself rather than pick one. (Bun joins a repeated header
  into one value before the server sees it; there the joined value is what is compared with the
  body.)
- `contextHeaders` (empty by default) exposes the listed request headers to handlers through
  `McpContext.header`. The library does not authenticate them: they are whatever the peer sent,
  so list a header only when a proxy in front of the server sets or strips it on every request,
  and keep the listener unreachable except through that proxy. A header sent more than once is
  seen as its joined value, and the library never logs the values — a forwarded access token is
  a credential, so keep it out of your own logs and tool results too.
- Every POST must carry `Content-Type: application/json` (415 otherwise), evaluated before the
  body is read or a session is minted.
- `maxRequestBodyBytes` (1 MiB) caps request bodies on every backend (413 before decoding), and
  `limits: LimitSettings` bounds every inbound frame on every transport — frame size 4 MiB, JSON
  depth 64, object width 1024, array length 4096, 65 536 values per frame, and numbers of at most
  512 digits with an exponent within ±512 (`-32700` before dispatch), resource URI length 8192 and
  subscriptions per session 1024 (`-32602`). The limits cannot be disabled, only moved. The value
  count bounds the parsed tree independently of the body size; the object and array bounds cap the
  hash-collision work a frame can cause (lower `maxArrayElements` and `maxObjectFields` when no
  tool takes long arrays, especially on Bun); the number bounds keep `BigInt` / `BigDecimal`
  handler parameters from being handed a magnitude that is expensive to expand
  ([docs/transports.md](docs/transports.md#input-limits)).
- `limits.maxOutboundMessages` (256) bounds what one session buffers for messages emitted outside
  a request's own response stream (legacy task status notifications, a task body's log and
  progress messages). A legacy JVM HTTP session drops the oldest at the bound, so a client that
  never opens its GET stream holds at most that many messages; sessions with no reader for the
  channel (Bun HTTP, 2026-07-28 HTTP requests, the legacy stateless adapter) buffer none; stdio
  applies back-pressure. The bound counts messages, not bytes: a tool that emits very large log
  or progress messages from a task should keep them small.
- `maxSessions` (`Some(1000)`) caps the legacy session store, and what each stored session keeps
  from its client is bounded too: a session is stored only for an `initialize` that succeeded, and
  its `clientInfo` + `capabilities` may hold at most 256 JSON values and 16 KiB of text (`-32602`
  otherwise; a fixed budget, independent of `maxRequestBodyBytes`). The store's worst case is
  therefore `maxSessions` × the per-session budget — about 100 MiB of heap at the defaults; the
  table in [docs/transports.md](docs/transports.md#the-legacy-session-store) adds the terms that
  `resourcesSubscribe` and tasks contribute when enabled.
- At the cap, sessions are counted per client (the peer address the backend reports): a client
  holding more sessions than the newcomer's gives one up, even one with a live, recently used GET
  stream, so a single address cannot fill the store and have every other client refused. Among
  clients holding their share the longest-idle session without a live GET is evicted — or, when
  every one holds a GET stream, the longest-idle one idle longer than `sessionIdleTimeout` — so an
  unauthenticated flood of `initialize` requests from many addresses can still evict idle
  legitimate sessions. Behind a reverse proxy all clients share the proxy's address and the store
  is plain least-recently-used. Front non-loopback deployments with `allowedHosts`/`allowedOrigins`
  and an authenticating proxy that limits sessions per principal.
- A request holds nothing once nobody can be answered: a client that disconnects — while its
  request runs, as the response head goes out, or before its event stream is started — stops the
  request's dispatch and gives a legacy session's GET stream slot back. A reply whose stream is
  never started is given up after 5 seconds at the latest (at once on the zio-http and socket
  backends when the connection is seen to close). A legacy session removed from the store (DELETE,
  idle eviction, eviction at `maxSessions`) is always terminated, so its in-flight requests and
  tasks are released even when the request that removed it is itself cut short.
- Transport failures and defects answer a fixed JSON-RPC error (400/413/500) with no exception
  text, stack trace, or path, on the JVM and on Bun regardless of `NODE_ENV`.
- Modern Tasks IDs are bearer handles: possession grants access to that task
  ([docs/tasks.md](docs/tasks.md)). Per-client task caps are keyed on the peer address the HTTP
  transport supplies (`TaskOwnerKey.Transport`); behind a reverse proxy every client collapses to
  one address, so use `TaskOwnerKey.Custom` with an authenticated principal. `_meta` and
  `clientInfo` are client-controlled and are never used as a key.
- The task store is bounded in entries and in bytes. Besides the running and stored caps,
  `TaskSettings.maxRequestBytes` (2 MiB) caps what a running task keeps of its request,
  `maxResultBytes` (8 MiB) caps one stored result, and `maxBytesPerOwner` (16 MiB) /
  `maxBytesTotal` (128 MiB per pool, two pools) cap what the store retains per client and in
  total, so retained memory does not depend on what clients send or tools return. The figures are
  estimated heap bytes; a finished task keeps its result only, never its request
  ([docs/tasks.md](docs/tasks.md#memory-bounds)). The per-client budget follows the same owner key
  as the caps above: behind a reverse proxy, without `TaskOwnerKey.Custom`, all clients share one
  owner budget, and a client with many peer addresses is held by the pool budget only.
- Each tool's advertised `inputSchema` is enforced on `tools/call` before the handler runs
  (`validateToolInput`, on by default, every transport and platform): a value outside a declared
  `enum`, bound, length, type, `required` list or `additionalProperties` rule is answered with an
  `isError` result and never reaches the handler. The built-in validator covers a documented
  subset of JSON Schema and deliberately does not evaluate `pattern` (no backtracking regular
  expression is ever run on client input), `format`, `multipleOf` or `uniqueItems`; a handler
  that relies on one of those, or that runs with validation switched off, must check it itself.
  `McpServer.toolInputValidation` lists, per tool, whether enforcement is on and which declared
  keywords are skipped ([docs/custom-types.md](docs/custom-types.md#what-the-server-enforces)).
  Schema validation is input hygiene, not authorization.
- `scanAnnotations` binds each `@Tool` / `@Resource` / `@Prompt` to the exact annotated overload,
  and duplicate registrations within one object are compile-time errors, so the advertised schema
  and the invoked handler always come from the same declaration.

The CI and release pipeline is hardened against supply-chain tampering: the conformance harness is
resolved only from the committed, integrity-hashed `conformance/bun.lock`; every GitHub Action is
pinned to a full commit SHA and kept current by Dependabot; the release workflow separates
verification, publishing, and the GitHub release into least-privilege jobs; and the Mill launcher
distribution is verified against `.github/mill-dist.sha256` before the first `./mill` in every job
([DEPENDENCY_POLICY.md](DEPENDENCY_POLICY.md)).
