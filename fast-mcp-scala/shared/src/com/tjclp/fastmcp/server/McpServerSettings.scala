package com.tjclp.fastmcp.server

import com.tjclp.fastmcp.core.Tasks

/** Settings for the optional `io.modelcontextprotocol/tasks` extension.
  *
  * Off by default. Modern clients opt in through `clientCapabilities.extensions`; the server may
  * then return a task handle for tools whose registration declares task support. The old core task
  * draft remains available only through the legacy protocol adapter.
  *
  * Modern task IDs are bearer handles and work over stateless HTTP, Streamable HTTP, and stdio.
  * Bearer tasks are invisible to legacy protocol sessions and vice versa. Calls made through the
  * legacy protocol adapter remain isolated by their protocol session; on the stateless legacy
  * adapter — where all clients share one session identity — legacy task requests are refused with
  * `-32601`.
  *
  * Store bounds. Every task is charged to an OWNER (the legacy protocol session id, or the modern
  * client key derived by `ownerKey`) and to a POOL (legacy-session tasks and modern bearer tasks
  * are counted separately, so a flood of freely minted legacy sessions can never starve bearer
  * clients and vice versa). Running caps reject; stored caps first evict the oldest completed entry
  * older than `minResultRetentionMs` and reject only when nothing is evictable. A legacy session's
  * tasks are released (running ones interrupted) when the transport terminates the session
  * (`DELETE`, idle eviction).
  *
  * @param enabled
  *   Master switch. When false, `tasks` capability is not advertised and `params.task` is ignored.
  * @param defaultTtlMs
  *   TTL applied when the requestor does not supply one (1 hour default).
  * @param maxTtlMs
  *   Upper bound on TTL — requestor-supplied values above this are clamped (24 hour default).
  * @param pollIntervalMs
  *   `pollInterval` value advertised back to clients in `tasks/get` responses.
  * @param maxConcurrentPerSession
  *   Running (non-terminal) tasks per OWNER: the legacy protocol session id, or the modern client
  *   key derived by `ownerKey`; keyless modern requests share one anonymous bucket under this same
  *   limit. Exceeding it is the caller's own fault: `-32602`.
  * @param maxConcurrentTotal
  *   Ceiling on running tasks per POOL (legacy-session pool and modern-bearer pool, counted
  *   separately). Must be >= the per-owner cap and should be well above it — equal values let one
  *   owner fill the whole pool, removing cross-client isolation. `-32003` when exceeded. Residual
  *   by design: a client controlling >= `maxConcurrentTotal / maxConcurrentPerSession` distinct
  *   peer addresses (16 at the defaults) can still hold a pool at its ceiling (or, via
  *   `maxStoredTotal`, keep it full of fresh stored entries inside the retention grace), answering
  *   `-32003` to everyone else until its traffic stops; pair the peer-address key with edge rate
  *   limiting / per-IP connection limits, or use `TaskOwnerKey.Custom` with an authenticated
  *   principal.
  * @param maxStoredPerOwner
  *   Stored entries per owner, terminal included. At the cap the owner's oldest terminal entry
  *   older than `minResultRetentionMs` is evicted to admit the new task (its result becomes
  *   unknown); if none qualifies the create is rejected with `-32003`. Normalised to `>=
  *   maxConcurrentPerSession`.
  * @param maxStoredTotal
  *   Stored entries per pool, terminal included. At the cap the creating owner's own oldest
  *   eligible entry is evicted first, then the oldest eligible entry of the owner holding the MOST
  *   eligible (stale, terminal) entries — the flooder pays for its own flood, and an owner whose
  *   results are all inside the retention grace is never a victim; else `-32003`. Normalised to `>=
  *   maxConcurrentTotal`.
  * @param minResultRetentionMs
  *   A terminal result younger than this is never evicted by a cap (only by its own TTL), so a
  *   client always gets at least this long to collect a result (6x the default poll interval).
  * @param sweepIntervalMs
  *   Upper bound on the single TTL sweeper's sleep; a TTL is honoured within this slack. Expiry and
  *   the retention grace are measured on the monotonic clock (wire timestamps stay wall-clock).
  * @param ownerKey
  *   How modern bearer tasks are bucketed per client. `Transport` (default) uses the
  *   transport-supplied `Session.clientKey` (the peer address on the shipped HTTP backends; `None`
  *   -> one anonymous bucket). `Custom(f)` lets an operator behind an authenticating proxy derive a
  *   key; `_meta`/`clientInfo` are client-controlled and must not be used as a key unless the proxy
  *   rewrites them.
  * @param maxRequestBytes
  *   Byte budget for what ONE running task keeps of the request that created it: the `tools/call`
  *   params (arguments, `_meta`, `inputResponses`, `requestState`), the decoded client capabilities
  *   and client info, the request id, and any allow-listed HTTP headers (`contextHeaders`). A
  *   handler running as a task can still read all of it; the budget bounds it. A request over it is
  *   refused with `-32602` before anything is forked; it can still be sent as an ordinary
  *   synchronous call to a tool whose task support is optional by not asking for a task. All four
  *   byte settings count ESTIMATED heap bytes (two per string character plus a fixed overhead per
  *   JSON node), not wire bytes, so a body of many small JSON nodes is charged several times its
  *   wire size. The 2 MiB default admits any string-dominated request inside the default 1 MiB HTTP
  *   body cap.
  * @param maxResultBytes
  *   Byte budget for ONE stored result (or stored failure). A task whose result is larger ends
  *   `failed` with a message naming this setting and the result is not kept. Default 8 MiB.
  * @param maxBytesPerOwner
  *   Byte budget for everything the store holds for one owner: the request of each running task
  *   plus each stored result. A create that does not fit evicts the owner's oldest results older
  *   than `minResultRetentionMs` and is otherwise refused with `-32003`; a finishing task whose
  *   result does not fit evicts the same way and otherwise ends `failed` with its result discarded.
  *   Must be >= `maxRequestBytes` and >= `maxResultBytes`. Default 16 MiB.
  * @param maxBytesTotal
  *   The same budget per POOL (legacy-session tasks and modern bearer tasks are counted
  *   separately), with the stored-cap eviction order: the creating owner's own stale results first,
  *   then those of the owner holding the most stale results. Must be >= `maxBytesPerOwner`. Default
  *   128 MiB, so the two pools together retain at most twice that; size it to the heap.
  */
case class TaskSettings(
    enabled: Boolean = false,
    defaultTtlMs: Long = 3_600_000L,
    maxTtlMs: Long = 86_400_000L,
    pollIntervalMs: Long = Tasks.DefaultPollIntervalMs,
    maxConcurrentPerSession: Int = 64,
    maxConcurrentTotal: Int = 1024,
    maxStoredPerOwner: Int = 256,
    maxStoredTotal: Int = 4096,
    minResultRetentionMs: Long = 30_000L,
    sweepIntervalMs: Long = 1_000L,
    ownerKey: com.tjclp.fastmcp.core.TaskOwnerKey = com.tjclp.fastmcp.core.TaskOwnerKey.Transport,
    maxRequestBytes: Long = 2L * 1024 * 1024,
    maxResultBytes: Long = 8L * 1024 * 1024,
    maxBytesPerOwner: Long = 16L * 1024 * 1024,
    maxBytesTotal: Long = 128L * 1024 * 1024
):
  require(maxConcurrentPerSession >= 1, "TaskSettings.maxConcurrentPerSession must be >= 1")
  require(
    maxConcurrentTotal >= maxConcurrentPerSession,
    "TaskSettings.maxConcurrentTotal must be >= maxConcurrentPerSession"
  )
  require(maxStoredPerOwner >= 1, "TaskSettings.maxStoredPerOwner must be >= 1")
  require(maxStoredTotal >= 1, "TaskSettings.maxStoredTotal must be >= 1")
  require(minResultRetentionMs >= 0L, "TaskSettings.minResultRetentionMs must be >= 0")
  require(sweepIntervalMs >= 1L, "TaskSettings.sweepIntervalMs must be >= 1")
  require(maxRequestBytes >= 1L, "TaskSettings.maxRequestBytes must be >= 1")
  require(maxResultBytes >= 1L, "TaskSettings.maxResultBytes must be >= 1")
  require(
    maxBytesPerOwner >= maxRequestBytes && maxBytesPerOwner >= maxResultBytes,
    "TaskSettings.maxBytesPerOwner must be >= maxRequestBytes and >= maxResultBytes"
  )
  require(
    maxBytesTotal >= maxBytesPerOwner,
    "TaskSettings.maxBytesTotal must be >= maxBytesPerOwner"
  )

  // Binary compatibility with 1.0.x: the 11-field constructor. Later fields take their defaults; the
  // one repeated below is checked against the primary constructor's by BinaryCompatibilityTest.
  private[fastmcp] def this(
      enabled: Boolean,
      defaultTtlMs: Long,
      maxTtlMs: Long,
      pollIntervalMs: Long,
      maxConcurrentPerSession: Int,
      maxConcurrentTotal: Int,
      maxStoredPerOwner: Int,
      maxStoredTotal: Int,
      minResultRetentionMs: Long,
      sweepIntervalMs: Long,
      ownerKey: com.tjclp.fastmcp.core.TaskOwnerKey
  ) =
    this(
      enabled,
      defaultTtlMs,
      maxTtlMs,
      pollIntervalMs,
      maxConcurrentPerSession,
      maxConcurrentTotal,
      maxStoredPerOwner,
      maxStoredTotal,
      minResultRetentionMs,
      sweepIntervalMs,
      ownerKey,
      maxRequestBytes = 2L * 1024 * 1024
    )

  // Binary compatibility with 1.0.x: the 11-field `copy`. Later fields keep their values.
  private[fastmcp] def copy(
      enabled: Boolean,
      defaultTtlMs: Long,
      maxTtlMs: Long,
      pollIntervalMs: Long,
      maxConcurrentPerSession: Int,
      maxConcurrentTotal: Int,
      maxStoredPerOwner: Int,
      maxStoredTotal: Int,
      minResultRetentionMs: Long,
      sweepIntervalMs: Long,
      ownerKey: com.tjclp.fastmcp.core.TaskOwnerKey
  ): TaskSettings =
    copy(
      enabled,
      defaultTtlMs,
      maxTtlMs,
      pollIntervalMs,
      maxConcurrentPerSession,
      maxConcurrentTotal,
      maxStoredPerOwner,
      maxStoredTotal,
      minResultRetentionMs,
      sweepIntervalMs,
      ownerKey,
      maxRequestBytes = maxRequestBytes
    )

object TaskSettings:

  // 1.0.x had no explicit companion, and the synthetic one printed as the class name.
  override def toString: String = "TaskSettings"

  // Binary compatibility with 1.0.x: the 11-field `apply`.
  private[fastmcp] def apply(
      enabled: Boolean,
      defaultTtlMs: Long,
      maxTtlMs: Long,
      pollIntervalMs: Long,
      maxConcurrentPerSession: Int,
      maxConcurrentTotal: Int,
      maxStoredPerOwner: Int,
      maxStoredTotal: Int,
      minResultRetentionMs: Long,
      sweepIntervalMs: Long,
      ownerKey: com.tjclp.fastmcp.core.TaskOwnerKey
  ): TaskSettings =
    new TaskSettings(
      enabled,
      defaultTtlMs,
      maxTtlMs,
      pollIntervalMs,
      maxConcurrentPerSession,
      maxConcurrentTotal,
      maxStoredPerOwner,
      maxStoredTotal,
      minResultRetentionMs,
      sweepIntervalMs,
      ownerKey
    )

/** Settings for the **Skills extension** (`io.modelcontextprotocol/skills`, SEP-2640). Full
  * reference: `docs/skills.md`.
  *
  * The extension is advertised when `enabled` is true OR when at least one skill / skill provider
  * is registered (honest capabilities: registering a skill means serving it; `enabled = true`
  * serves an empty catalog, which is legitimate and distinct from not declaring the extension).
  *
  * @param enabled
  *   Declare the extension even with an empty catalog. Default false: with nothing registered the
  *   extension is not declared and `skills/list`, `skills/get` answer `-32601`.
  * @param directoryRead
  *   Advertise `directoryRead: true` and wire `resources/directory/read`. The flag is emitted only
  *   when every registered provider supports directory reads; the in-memory catalog always does.
  * @param listPageSize
  *   Skills per `skills/list` page; an entry is never split across pages.
  * @param directoryPageSize
  *   Children per `resources/directory/read` page.
  * @param ttlMs
  *   `ttlMs` on `skills/list` and `skills/get` results (2026-07-28 CacheableResult). Default 0:
  *   immediately stale, the conservative base-protocol default.
  * @param cacheScope
  *   `cacheScope` on the same results. Default `private`: a catalog may be authorization-scoped, so
  *   it must never be shared through a public cache unless the operator says so.
  * @param maxResourcesPerSkill
  *   Registration limit on `SKILL.md` + supporting files. Default = the spec's 512-entry
  *   interoperability threshold; raise only for hosts known to accept more.
  * @param maxTotalBytesPerSkill
  *   Registration limit on the summed raw byte size of a skill's files. Default = the spec's 16 MiB
  *   threshold. Raw bytes, not wire bytes: a binary file costs ~4/3 of its size as base64 in one
  *   `resources/read` frame, and `limits.maxFrameChars` (4 MiB by default) bounds the frame on
  *   every transport — a single file larger than roughly 3 MiB needs `limits` raised too.
  */
case class SkillSettings(
    enabled: Boolean = false,
    directoryRead: Boolean = true,
    listPageSize: Int = 50,
    directoryPageSize: Int = 200,
    ttlMs: Long = com.tjclp.fastmcp.core.wire.CacheHints.TtlMs,
    cacheScope: com.tjclp.fastmcp.core.wire.CacheScope =
      com.tjclp.fastmcp.core.wire.CacheHints.Scope,
    maxResourcesPerSkill: Int = com.tjclp.fastmcp.core.wire.Skills.MaxResourcesPerSkill,
    maxTotalBytesPerSkill: Long = com.tjclp.fastmcp.core.wire.Skills.MaxTotalBytesPerSkill
):
  require(listPageSize >= 1, "SkillSettings.listPageSize must be >= 1")
  require(directoryPageSize >= 1, "SkillSettings.directoryPageSize must be >= 1")
  require(ttlMs >= 0L, "SkillSettings.ttlMs must be >= 0")
  require(maxResourcesPerSkill >= 1, "SkillSettings.maxResourcesPerSkill must be >= 1")
  require(maxTotalBytesPerSkill >= 1L, "SkillSettings.maxTotalBytesPerSkill must be >= 1")

/** Settings for an MCP server. HTTP-specific fields (`host`, `port`, `httpEndpoint`, `stateless`,
  * `keepAliveInterval`, `sessionIdleTimeout`, `disallowDelete`, `allowedHosts`, `allowedOrigins`,
  * `maxRequestBodyBytes`, `maxSessions`, `contextHeaders`, `dnsRebindingProtection`) are ignored
  * under stdio transports; `stdioDrainTimeout` applies to stdio only. `limits`, `tasks` and
  * `validateToolInput` apply on every transport.
  */
case class McpServerSettings(
    // Spec: HTTP servers SHOULD bind to localhost by default (DNS-rebinding surface). Deployments
    // that need external exposure (e.g. containers) must set this explicitly — 0.5.0 BREAKING
    // change from the old "0.0.0.0" default. Binding loopback is NOT by itself a defence against a
    // web page in a local browser: that is what the Host/Origin guard below is for, and it is on
    // automatically for a loopback bind (see `allowedHosts`).
    host: String = "127.0.0.1",
    port: Int = 8000,
    // The MCP endpoint path. A request is on the endpoint when its path equals this string exactly
    // (a leading slash is added when missing): no trailing-slash or case tolerance, and the query
    // string is not part of the path. Anything else is 404. A value no request path can equal —
    // one containing `?`, `#`, `%`, whitespace or a `.` / `..` segment — fails `runHttp()` at
    // startup.
    httpEndpoint: String = "/mcp",
    // If true, advertise templates via the resources/templates/list endpoint.
    // If false, rely on clients that derive templates from resource URIs containing `{}`.
    exposeTemplatesEndpoint: Boolean = false,
    // Legacy HTTP compatibility settings. MCP 2026-07-28 is always stateless and may use a
    // request-scoped SSE response regardless of this flag. For older versions, true disables the
    // protocol session store; false preserves the initialize/session/GET/DELETE adapter.
    stateless: Boolean = false,
    // SSE heartbeat period. Also the only thing that makes the OS probe a GET peer that vanished
    // without closing its connection (a socket is probed only when written to; the OS then gives
    // up after its own retransmission budget) — zio-http 3.11.4 exposes no accepted-socket option
    // (only TCP_NODELAY is set on accepted channels), so the server sets no TCP keepalive itself.
    keepAliveInterval: Option[java.time.Duration] = None,
    // Streamable HTTP only: evict sessions idle longer than this (no POST/GET/DELETE activity).
    // Sessions with a live GET stream are exempt from the periodic sweep, but at the `maxSessions`
    // cap the longest-idle GET holder idle past this timeout is evicted when no GET-less session is
    // available. Guards the session store against abandoned clients; `None` disables both.
    sessionIdleTimeout: Option[java.time.Duration] = Some(java.time.Duration.ofMinutes(30)),
    disallowDelete: Boolean = false,
    // Advertise logging. Modern requests opt in per call through `_meta`; the legacy adapter also
    // wires `logging/setLevel`. Off by default (#56 honesty).
    loggingEnabled: Boolean = false,
    // Legacy adapter only: advertise and wire resources/subscribe + resources/unsubscribe.
    // Modern subscriptions use subscriptions/listen. Off by default.
    resourcesSubscribe: Boolean = false,
    // DNS-rebinding / CSRF guard for the HTTP transports: the `Host` names a request may carry.
    //
    // `None` (default) = AUTOMATIC. When `host` is a loopback bind address (`127.0.0.0/8`, `::1`,
    // `localhost`) the guard is ON with the loopback names `localhost`, `127.0.0.1`, `[::1]` and
    // the bound address itself, on any port. On any other bind address (`0.0.0.0`, a LAN address,
    // a host name) nothing can be inferred, so the guard is off and `runHttp()` logs a WARN at
    // startup until an allow-list is set here or in `allowedOrigins`.
    //
    // `Some(names)` = exactly these names, whatever the bind address: a request whose `Host`
    // hostname (port ignored; a verbatim `host:port` entry also matches) is not listed is refused
    // with 403. List the public name when a reverse proxy forwards its own `Host` to a loopback
    // listener. `Some(Set.empty)` is read as `None`. IPv6 literals keep their brackets, exactly
    // as they appear in a `Host` header: `Some(Set("[::1]"))`, never `Some(Set("::1"))` (an
    // unbracketed entry matches no request).
    //
    // In both forms a present `Origin` header is matched as a FULL origin (scheme://host:port,
    // default port per scheme): it must appear in `allowedOrigins`, or its hostname must be an
    // allowed `Host` name AND its host:port must equal the request's `Host` authority. `null`,
    // empty, malformed, cross-port and cross-scheme-default-port origins are refused; an absent
    // `Host`/`Origin` passes (not the browser threat this guards). To turn the guard off, see
    // `dnsRebindingProtection`.
    allowedHosts: Option[Set[String]] = None,
    // Explicit browser origins (`https://app.example.com`, `http://localhost:5173`) admitted in
    // addition to the request's own authority. Needed for front-ends served from another origin or
    // behind a TLS-terminating proxy whose Origin differs from the listener's Host. Entries that do
    // not parse as `scheme://host[:port]` fail `runHttp()` at startup. `None` (default) = none.
    // Listed WITHOUT `allowedHosts`, only these origins pass (the request's own authority is then
    // refused too); `Host` is still held to the automatic loopback names on a loopback bind, and is
    // not checked on any other bind address.
    allowedOrigins: Option[Set[String]] = None,
    // Maximum HTTP request body accepted on the MCP endpoint, in bytes, on every backend. Enforced
    // by the platform server (zio-http request aggregator / Bun.serve maxRequestBodySize) AND by a
    // first-party Content-Length + post-read check, so oversized bodies answer 413 before any JSON
    // decoding. 1 MiB default (the JVM was implicitly ~100 KiB; Bun was 128 MiB); raise it for
    // tools that legitimately take large payloads. Must be > 0.
    maxRequestBodyBytes: Int = 1024 * 1024,
    // Legacy streamable adapter only: cap on concurrently stored sessions. A session is stored only
    // for a header-less `initialize` that succeeded; a failed one takes no slot and evicts nobody.
    // When a successful one finds the store full, one stored session is evicted — terminated
    // (`Session.terminate`: in-flight requests interrupted, its tasks released, queue shut down) —
    // to make room. Sessions are counted per client (the peer address the backend reports): a
    // client that already holds as many sessions as any other replaces one of its own, and
    // otherwise a client holding at least two more than the newcomer's gives one up — its
    // longest-idle session, a live GET stream included — so one client cannot fill the store and
    // have the others refused. When holdings are level, the longest-idle session without a live GET
    // stream is evicted; when every stored session holds a live GET, the longest-idle of them,
    // provided it has been idle longer than `sessionIdleTimeout` (its GET stream is closed); the
    // request is refused with 503 only when no session qualifies.
    //
    // This bounds the NUMBER of sessions. What one session keeps from its client is bounded
    // separately — 256 JSON values and 16 KiB of text of `clientInfo` + `capabilities`
    // (`-32602` beyond that), plus `limits.maxSubscriptionsPerSession` URIs when
    // `resourcesSubscribe` is on — so the store's worst case is `maxSessions` times that
    // per-session budget (about 100 MiB of heap at the defaults; docs/transports.md, "The legacy
    // session store"). `None` disables the cap and with it that bound. Modern 2026-07-28 requests
    // never store sessions.
    maxSessions: Option[Int] = Some(1000),
    // Optional io.modelcontextprotocol/tasks extension. Off by default.
    tasks: TaskSettings = TaskSettings(),
    // Inbound input limits (frame size, JSON depth, object width, URI length, subscriptions).
    // Enforced on every transport before any dispatch work; see [[LimitSettings]].
    limits: LimitSettings = LimitSettings(),
    // Server-level usage guidance returned as `instructions` in the `initialize` result (and in
    // `server/discover` for 2026-07-28 clients) — the one place a server can tell an agent how its
    // tools fit together ("search first, then fetch by id; pass drive to narrow…"). The spec leaves
    // the content free-form; clients typically add it to the model's system prompt. `None` omits it.
    instructions: Option[String] = None,
    // Optional io.modelcontextprotocol/skills extension (SEP-2640). Declared when enabled or when a
    // skill / provider is registered; see [[SkillSettings]] and docs/skills.md.
    skills: SkillSettings = SkillSettings(),
    // HTTP request headers a handler may read through `McpContext.header` / `McpContext.headers`.
    // Nothing is exposed unless listed; names compare case-insensitively. Meant for an identity a
    // proxy in front of the server forwards (a user id, a forwarded access token). Listing a name
    // is the server author's statement that the proxy sets or strips it: a client that reaches the
    // server directly can send any header it likes. Values are read per request, are never logged
    // by the library, and a header sent more than once is seen as its ", "-joined value.
    contextHeaders: Set[String] = Set.empty,
    // Check every `tools/call` against the tool's advertised `inputSchema` before its handler runs
    // (every transport, every platform). A call that does not conform is answered with an `isError`
    // tool result and the handler is not invoked. The check covers a documented subset of JSON
    // Schema (`core.JsonSchemaValidator`): `type`, `enum`, `const`, numeric and length bounds,
    // `required`, `properties`, `additionalProperties`, `items`, `allOf`/`anyOf`/`oneOf`/`not` and
    // local `$ref`; `pattern`, `format` and `uniqueItems` among others are NOT evaluated. `false`
    // turns the check off for every tool — schemas are then advertised only and each handler must
    // check what it relies on. One tool can opt out with
    // `ToolRegistrationOptions(validateInputSchema = false)`; `McpServer.toolInputValidation`
    // reports the effective state per tool.
    validateToolInput: Boolean = true,
    // The explicit opt-out of the Host/Origin guard. `true` (default) keeps it: automatic on a
    // loopback bind, or as listed in `allowedHosts` / `allowedOrigins`. `false` turns it OFF — every
    // `Host` and `Origin` is accepted — and silences the non-loopback startup warning; it is only
    // valid with both allow-lists unset (`runHttp()` refuses the contradictory combination at
    // startup). Meant for a loopback listener behind a reverse proxy that forwards a `Host` you
    // cannot enumerate; when you can, list it in `allowedHosts` instead and keep the guard. With it
    // off, a web page open in a browser on the same machine can reach the server (DNS rebinding).
    dnsRebindingProtection: Boolean = true,
    // Stdio only: how long the server waits, after stdin reaches EOF, for the replies to frames it
    // has already read. Every such frame is dispatched and answered before `runStdio()` returns;
    // a handler still running when this time is up is interrupted, so one that never finishes
    // cannot keep the process alive. Background task bodies (the Tasks extension) are not waited
    // for. Zero interrupts in-flight handlers at EOF without waiting. Must not be negative.
    stdioDrainTimeout: java.time.Duration = McpServerSettings.DefaultStdioDrainTimeout
):
  require(!stdioDrainTimeout.isNegative, "McpServerSettings.stdioDrainTimeout must be >= 0")

  /** The Host/Origin guard these settings resolve to (`transport.HostGuard`), computed once per
    * settings value so that no request re-derives it.
    */
  private[fastmcp] lazy val hostGuardPolicy: transport.HostGuard.Policy =
    transport.HostGuard.policy(this)

  // Binary compatibility with 1.0.1: the 17-field constructor. Later fields take their defaults; the
  // one repeated below is checked against the primary constructor's by BinaryCompatibilityTest.
  private[fastmcp] def this(
      host: String,
      port: Int,
      httpEndpoint: String,
      exposeTemplatesEndpoint: Boolean,
      stateless: Boolean,
      keepAliveInterval: Option[java.time.Duration],
      sessionIdleTimeout: Option[java.time.Duration],
      disallowDelete: Boolean,
      loggingEnabled: Boolean,
      resourcesSubscribe: Boolean,
      allowedHosts: Option[Set[String]],
      allowedOrigins: Option[Set[String]],
      maxRequestBodyBytes: Int,
      maxSessions: Option[Int],
      tasks: TaskSettings,
      limits: LimitSettings,
      instructions: Option[String]
  ) =
    this(
      host,
      port,
      httpEndpoint,
      exposeTemplatesEndpoint,
      stateless,
      keepAliveInterval,
      sessionIdleTimeout,
      disallowDelete,
      loggingEnabled,
      resourcesSubscribe,
      allowedHosts,
      allowedOrigins,
      maxRequestBodyBytes,
      maxSessions,
      tasks,
      limits,
      instructions,
      skills = SkillSettings()
    )

  // Binary compatibility with 1.0.1: the 17-field `copy`. Later fields keep their values.
  private[fastmcp] def copy(
      host: String,
      port: Int,
      httpEndpoint: String,
      exposeTemplatesEndpoint: Boolean,
      stateless: Boolean,
      keepAliveInterval: Option[java.time.Duration],
      sessionIdleTimeout: Option[java.time.Duration],
      disallowDelete: Boolean,
      loggingEnabled: Boolean,
      resourcesSubscribe: Boolean,
      allowedHosts: Option[Set[String]],
      allowedOrigins: Option[Set[String]],
      maxRequestBodyBytes: Int,
      maxSessions: Option[Int],
      tasks: TaskSettings,
      limits: LimitSettings,
      instructions: Option[String]
  ): McpServerSettings =
    copy(
      host,
      port,
      httpEndpoint,
      exposeTemplatesEndpoint,
      stateless,
      keepAliveInterval,
      sessionIdleTimeout,
      disallowDelete,
      loggingEnabled,
      resourcesSubscribe,
      allowedHosts,
      allowedOrigins,
      maxRequestBodyBytes,
      maxSessions,
      tasks,
      limits,
      instructions,
      skills = skills
    )

  // Binary compatibility with 1.0.0: the 16-field constructor, from before `instructions`.
  private[fastmcp] def this(
      host: String,
      port: Int,
      httpEndpoint: String,
      exposeTemplatesEndpoint: Boolean,
      stateless: Boolean,
      keepAliveInterval: Option[java.time.Duration],
      sessionIdleTimeout: Option[java.time.Duration],
      disallowDelete: Boolean,
      loggingEnabled: Boolean,
      resourcesSubscribe: Boolean,
      allowedHosts: Option[Set[String]],
      allowedOrigins: Option[Set[String]],
      maxRequestBodyBytes: Int,
      maxSessions: Option[Int],
      tasks: TaskSettings,
      limits: LimitSettings
  ) =
    this(
      host,
      port,
      httpEndpoint,
      exposeTemplatesEndpoint,
      stateless,
      keepAliveInterval,
      sessionIdleTimeout,
      disallowDelete,
      loggingEnabled,
      resourcesSubscribe,
      allowedHosts,
      allowedOrigins,
      maxRequestBodyBytes,
      maxSessions,
      tasks,
      limits,
      instructions = None
    )

  // Binary compatibility with 1.0.0: the 16-field `copy`. Later fields keep their values.
  private[fastmcp] def copy(
      host: String,
      port: Int,
      httpEndpoint: String,
      exposeTemplatesEndpoint: Boolean,
      stateless: Boolean,
      keepAliveInterval: Option[java.time.Duration],
      sessionIdleTimeout: Option[java.time.Duration],
      disallowDelete: Boolean,
      loggingEnabled: Boolean,
      resourcesSubscribe: Boolean,
      allowedHosts: Option[Set[String]],
      allowedOrigins: Option[Set[String]],
      maxRequestBodyBytes: Int,
      maxSessions: Option[Int],
      tasks: TaskSettings,
      limits: LimitSettings
  ): McpServerSettings =
    copy(
      host,
      port,
      httpEndpoint,
      exposeTemplatesEndpoint,
      stateless,
      keepAliveInterval,
      sessionIdleTimeout,
      disallowDelete,
      loggingEnabled,
      resourcesSubscribe,
      allowedHosts,
      allowedOrigins,
      maxRequestBodyBytes,
      maxSessions,
      tasks,
      limits,
      instructions = instructions
    )

object McpServerSettings:

  // 1.0.x had no explicit companion, and the synthetic one printed as the class name.
  override def toString: String = "McpServerSettings"

  /** Default of [[McpServerSettings.stdioDrainTimeout]]: five seconds. */
  val DefaultStdioDrainTimeout: java.time.Duration = java.time.Duration.ofSeconds(5)

  // Binary compatibility with 1.0.1: the 17-field `apply`.
  private[fastmcp] def apply(
      host: String,
      port: Int,
      httpEndpoint: String,
      exposeTemplatesEndpoint: Boolean,
      stateless: Boolean,
      keepAliveInterval: Option[java.time.Duration],
      sessionIdleTimeout: Option[java.time.Duration],
      disallowDelete: Boolean,
      loggingEnabled: Boolean,
      resourcesSubscribe: Boolean,
      allowedHosts: Option[Set[String]],
      allowedOrigins: Option[Set[String]],
      maxRequestBodyBytes: Int,
      maxSessions: Option[Int],
      tasks: TaskSettings,
      limits: LimitSettings,
      instructions: Option[String]
  ): McpServerSettings =
    new McpServerSettings(
      host,
      port,
      httpEndpoint,
      exposeTemplatesEndpoint,
      stateless,
      keepAliveInterval,
      sessionIdleTimeout,
      disallowDelete,
      loggingEnabled,
      resourcesSubscribe,
      allowedHosts,
      allowedOrigins,
      maxRequestBodyBytes,
      maxSessions,
      tasks,
      limits,
      instructions
    )

  // Binary compatibility with 1.0.0: the 16-field `apply`.
  private[fastmcp] def apply(
      host: String,
      port: Int,
      httpEndpoint: String,
      exposeTemplatesEndpoint: Boolean,
      stateless: Boolean,
      keepAliveInterval: Option[java.time.Duration],
      sessionIdleTimeout: Option[java.time.Duration],
      disallowDelete: Boolean,
      loggingEnabled: Boolean,
      resourcesSubscribe: Boolean,
      allowedHosts: Option[Set[String]],
      allowedOrigins: Option[Set[String]],
      maxRequestBodyBytes: Int,
      maxSessions: Option[Int],
      tasks: TaskSettings,
      limits: LimitSettings
  ): McpServerSettings =
    new McpServerSettings(
      host,
      port,
      httpEndpoint,
      exposeTemplatesEndpoint,
      stateless,
      keepAliveInterval,
      sessionIdleTimeout,
      disallowDelete,
      loggingEnabled,
      resourcesSubscribe,
      allowedHosts,
      allowedOrigins,
      maxRequestBodyBytes,
      maxSessions,
      tasks,
      limits
    )

/** Input limits applied to every inbound JSON-RPC frame on every transport (stdio and HTTP; JVM,
  * Scala.js, Scala Native), plus the resource-URI and per-session subscription bounds.
  *
  * Frame-limit violations (`maxFrameChars`, `maxDepth`, `maxObjectFields`, `maxArrayElements`,
  * `maxValues`, `maxNumberDigits`, `maxNumberExponent`) answer JSON-RPC `-32700` (HTTP 400) before
  * any dispatch work and never mint a session; the URI and subscription bounds answer `-32602`. The
  * same JSON bounds apply to JSON embedded in string arguments and, for numbers, in front of every
  * argument decoder (`-32602` there). Limits cannot be disabled, only moved inside the documented
  * bounds — the constructor `require`s make a misconfiguration fail fast at construction.
  *
  * The frame check is one linear pass over the raw text before the parser allocates anything (about
  * a nanosecond per character on the JVM, less for long strings, with two small scratch arrays per
  * frame and no allocation per token) and one iterative walk over the parsed tree.
  *
  * Together `maxValues`, `maxObjectFields` and `maxArrayElements` bound what a frame can cost after
  * it is accepted, independently of its size in bytes: the parsed tree has at most `maxValues`
  * nodes, and building hash maps / sets from attacker-chosen colliding members costs at most
  * `maxValues × max(maxObjectFields, maxArrayElements) / 2` string comparisons per frame — about
  * 1.3 × 10^8 at the defaults, half a second to a second on the JVM and several times that on
  * single-threaded Bun, and only for a handler that builds a `Set` from a client array; without one
  * the object bound governs (`maxValues × maxObjectFields / 2`, about 3.4 × 10^7).
  *
  * @param maxFrameChars
  *   Max length of ONE decoded frame (one stdio line / one HTTP body) in UTF-16 chars. On HTTP the
  *   transport body cap (`McpServerSettings.maxRequestBodyBytes`) should be <= this so oversized
  *   bodies get 413 first; this is the transport-independent backstop. Note: sampling/createMessage
  *   results carrying base64 images must fit — raise for image-heavy stdio deployments, e.g.
  *   `LimitSettings(maxFrameChars = 16 * 1024 * 1024)`.
  * @param maxDepth
  *   Max JSON nesting depth. Depth 1 = the envelope object; every nested `{` / `[` adds one. A
  *   legacy `initialize` is depth 4, a `tools/call` with one nested argument object is depth 4-5;
  *   values below 8 break normal clients. The hard ceiling [[LimitSettings.MaxSupportedDepth]]
  *   keeps every recursive walk (encode / equals / toString) far inside the smallest supported
  *   stack, so no configuration can re-open the stack-overflow window.
  * @param maxObjectFields
  *   Max members of any single JSON object anywhere in a frame. Bounds the worst case of building a
  *   Scala `Map` from attacker-chosen hash-colliding keys: every `Map` sink after the frame check
  *   (zio-json `Map` decoders, `_meta` lookups, tool-argument maps) costs up to `maxObjectFields² /
  *   2` string comparisons per colliding object (about 5 × 10^5 at the default), and `maxValues`
  *   caps the members of all objects in a frame together. Operators exposing a Bun server to
  *   untrusted networks should lower it (e.g. 256); the wire shapes never need more than a few
  *   dozen members per object.
  * @param maxUriChars
  *   Max length (chars) of a client-supplied resource URI: `resources/read`, `resources/subscribe`,
  *   `resources/unsubscribe`, and `subscriptions/listen` entries.
  * @param maxSubscriptionsPerSession
  *   Max distinct URIs one legacy session may hold via `resources/subscribe`; further distinct
  *   subscriptions are refused with `-32602`.
  * @param maxOutboundMessages
  *   Max server-to-client messages one session buffers outside a request's own response stream
  *   (`Session.outbound`): task status notifications, and the log, progress and server-initiated
  *   request messages a task or a stdio handler emits. It applies to the stdio session and to each
  *   legacy streamable-HTTP session on the JVM, whose standalone GET stream drains the buffer. At
  *   the bound a legacy HTTP session drops the OLDEST buffered message to admit the new one (the
  *   emitter is never blocked or interrupted by a client that does not read), while the stdio
  *   session applies back-pressure (the emitter waits for the stdout writer; nothing is lost).
  *   Sessions with no reader for this channel (every 2026-07-28 HTTP request, the legacy stateless
  *   adapter, every Bun HTTP session) buffer nothing. The buffer is allocated up front, per
  *   session. Default 256.
  * @param maxArrayElements
  *   Max elements of any single JSON array anywhere in a frame. The array counterpart of
  *   `maxObjectFields`: a handler parameter that builds a hash set from a client array (`Set[T]`,
  *   or any type containing one) costs up to `maxArrayElements² / 2` string comparisons per
  *   colliding array. The default is 4096, not 1024, so that an embedding vector of 1536 or 3072
  *   numbers passes without configuration; the price is a per-array worst case of about 8.4 × 10^6
  *   comparisons, 16 times the per-object figure (a few tens of milliseconds per array on the JVM).
  *   A server with no tool that takes long arrays should lower it together with `maxObjectFields`
  *   (e.g. 256 on Bun); raise it for tools that take longer arrays by design.
  * @param maxValues
  *   Max JSON values in one frame: every object, array, string, number, boolean and `null` counts
  *   once (member names do not). Bounds the size of the parsed tree by a figure far below the frame
  *   size — a megabyte of two-character elements is refused instead of becoming a tree tens of
  *   times its size — and caps the summed length of all arrays and objects for the collision budget
  *   above. The default, 65536, holds sixteen 3072-number vectors or a table of 4096 rows by 14
  *   columns in one frame; the protocol's own messages use a few dozen values. Raise it for tools
  *   that take bulk tabular data in a single call.
  * @param maxNumberDigits
  *   Max digits in the significand of a JSON number as written (integer and fraction part, leading
  *   and trailing zeros included). The default, 512, is far beyond what a `Long` (19 digits), a
  *   `Double` (17 significant digits) or the parser itself (which refuses a significand over 256
  *   bits, about 77 significant digits) can hold. Hard ceiling
  *   [[LimitSettings.MaxSupportedNumberDigits]].
  * @param maxNumberExponent
  *   Max absolute value of a JSON number's exponent (the part after `e` / `E`). The default, 512,
  *   admits every finite `Double` (shortest form: exponents 308 down to -324) and the full `Long`
  *   range. With `maxNumberDigits` it bounds what a client number can expand to: an integer of at
  *   most `maxNumberDigits + maxNumberExponent` decimal digits (1024 by default — at most 3402
  *   bits, 426 bytes) and a `BigDecimal` scale within ±(`maxNumberDigits + maxNumberExponent`), so
  *   decoding into `BigInt` / `BigInteger` / `BigDecimal` and any arithmetic a handler then does
  *   costs a small constant per number. A number outside the bounds is refused from its text,
  *   without work proportional to its magnitude. Hard ceiling
  *   [[LimitSettings.MaxSupportedNumberExponent]].
  */
case class LimitSettings(
    maxFrameChars: Int = 4 * 1024 * 1024,
    maxDepth: Int = 64, // = codec.JsonLimits.DefaultMaxDepth (pinned by JsonLimitsTest)
    maxObjectFields: Int = 1024, // = codec.JsonLimits.DefaultMaxObjectFields
    maxUriChars: Int = 8192,
    maxSubscriptionsPerSession: Int = 1024,
    maxOutboundMessages: Int = 256,
    maxArrayElements: Int = 4096, // = codec.JsonLimits.DefaultMaxArrayElements
    maxValues: Int = 65536, // = codec.JsonLimits.DefaultMaxValues
    maxNumberDigits: Int = 512, // = codec.JsonLimits.DefaultMaxNumberDigits
    maxNumberExponent: Int = 512 // = codec.JsonLimits.DefaultMaxNumberExponent
):
  require(maxFrameChars >= 1, "limits.maxFrameChars must be >= 1")
  require(
    maxDepth >= 1 && maxDepth <= LimitSettings.MaxSupportedDepth,
    s"limits.maxDepth must be in 1..${LimitSettings.MaxSupportedDepth}"
  )
  require(maxObjectFields >= 1, "limits.maxObjectFields must be >= 1")
  require(maxUriChars >= 1, "limits.maxUriChars must be >= 1")
  require(maxSubscriptionsPerSession >= 1, "limits.maxSubscriptionsPerSession must be >= 1")
  require(maxOutboundMessages >= 1, "limits.maxOutboundMessages must be >= 1")
  require(maxArrayElements >= 1, "limits.maxArrayElements must be >= 1")
  require(maxValues >= 1, "limits.maxValues must be >= 1")
  require(
    maxNumberDigits >= 1 && maxNumberDigits <= LimitSettings.MaxSupportedNumberDigits,
    s"limits.maxNumberDigits must be in 1..${LimitSettings.MaxSupportedNumberDigits}"
  )
  require(
    maxNumberExponent >= 1 && maxNumberExponent <= LimitSettings.MaxSupportedNumberExponent,
    s"limits.maxNumberExponent must be in 1..${LimitSettings.MaxSupportedNumberExponent}"
  )

  /** The JSON bounds in the shape the codec layer consumes — built once per settings value, so the
    * per-frame path allocates nothing for it.
    */
  private[fastmcp] val jsonBounds: com.tjclp.fastmcp.codec.JsonLimits.Bounds =
    com.tjclp.fastmcp.codec.JsonLimits.Bounds(
      maxDepth,
      maxObjectFields,
      maxArrayElements,
      maxValues,
      maxNumberDigits,
      maxNumberExponent
    )

  // Binary compatibility with 1.0.x: the 5-field constructor. Later fields take their defaults; the
  // one repeated below is checked against the primary constructor's by BinaryCompatibilityTest.
  private[fastmcp] def this(
      maxFrameChars: Int,
      maxDepth: Int,
      maxObjectFields: Int,
      maxUriChars: Int,
      maxSubscriptionsPerSession: Int
  ) =
    this(
      maxFrameChars,
      maxDepth,
      maxObjectFields,
      maxUriChars,
      maxSubscriptionsPerSession,
      maxOutboundMessages = 256
    )

  // Binary compatibility with 1.0.x: the 5-field `copy`. Later fields keep their values.
  private[fastmcp] def copy(
      maxFrameChars: Int,
      maxDepth: Int,
      maxObjectFields: Int,
      maxUriChars: Int,
      maxSubscriptionsPerSession: Int
  ): LimitSettings =
    copy(
      maxFrameChars,
      maxDepth,
      maxObjectFields,
      maxUriChars,
      maxSubscriptionsPerSession,
      maxOutboundMessages = maxOutboundMessages
    )

object LimitSettings:

  // Binary compatibility with 1.0.x: the 5-field `apply`.
  private[fastmcp] def apply(
      maxFrameChars: Int,
      maxDepth: Int,
      maxObjectFields: Int,
      maxUriChars: Int,
      maxSubscriptionsPerSession: Int
  ): LimitSettings =
    new LimitSettings(
      maxFrameChars,
      maxDepth,
      maxObjectFields,
      maxUriChars,
      maxSubscriptionsPerSession
    )

  /** Hard ceiling for `maxDepth`: the stack safety of every later recursive walk over client JSON
    * (zio-json encode, `equals`, `toString`, AST unwrapping) depends on it. Defined in
    * `codec.JsonLimits` (shared with `DefaultDecodeContext`'s embedded-JSON bounds) and re-exported
    * here.
    */
  val MaxSupportedDepth: Int = com.tjclp.fastmcp.codec.JsonLimits.MaxSupportedDepth

  /** Hard ceiling (4096) for `maxNumberDigits`. With [[MaxSupportedNumberExponent]] it keeps the
    * integer a client number can expand to at 8192 decimal digits or fewer (about 3.4 KiB) on any
    * configuration, so no setting can re-open unbounded `BigInteger` / `BigDecimal` work. Defined
    * in `codec.JsonLimits` and re-exported here.
    */
  val MaxSupportedNumberDigits: Int = com.tjclp.fastmcp.codec.JsonLimits.MaxSupportedNumberDigits

  /** Hard ceiling (4096) for `maxNumberExponent`; see [[MaxSupportedNumberDigits]]. */
  val MaxSupportedNumberExponent: Int =
    com.tjclp.fastmcp.codec.JsonLimits.MaxSupportedNumberExponent
