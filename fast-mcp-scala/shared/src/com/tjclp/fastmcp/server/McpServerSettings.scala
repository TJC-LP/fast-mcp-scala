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
  *   separately). Distinct from and larger than the per-owner cap. `-32003` when exceeded.
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
    ownerKey: com.tjclp.fastmcp.core.TaskOwnerKey = com.tjclp.fastmcp.core.TaskOwnerKey.Transport
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

/** Settings for an MCP server. HTTP-specific fields (`stateless`, `keepAliveInterval`,
  * `disallowDelete`, `httpEndpoint`) are ignored under stdio transports.
  */
case class McpServerSettings(
    debug: Boolean = false,
    logLevel: String = "INFO",
    // Spec: HTTP servers SHOULD bind to localhost by default (DNS-rebinding surface). Deployments
    // that need external exposure (e.g. containers) must set this explicitly — 0.5.0 BREAKING
    // change from the old "0.0.0.0" default.
    host: String = "127.0.0.1",
    port: Int = 8000,
    httpEndpoint: String = "/mcp",
    warnOnDuplicateResources: Boolean = true,
    warnOnDuplicateTools: Boolean = true,
    warnOnDuplicatePrompts: Boolean = true,
    dependencies: List[String] = List.empty,
    // If true, advertise templates via the resources/templates/list endpoint.
    // If false, rely on clients that derive templates from resource URIs containing `{}`.
    exposeTemplatesEndpoint: Boolean = false,
    // Legacy HTTP compatibility settings. MCP 2026-07-28 is always stateless and may use a
    // request-scoped SSE response regardless of this flag. For older versions, true disables the
    // protocol session store; false preserves the initialize/session/GET/DELETE adapter.
    stateless: Boolean = false,
    keepAliveInterval: Option[java.time.Duration] = None,
    // Streamable HTTP only: evict sessions idle longer than this (no POST/GET/DELETE activity and
    // no live GET stream). Guards the session store against abandoned clients; `None` disables.
    sessionIdleTimeout: Option[java.time.Duration] = Some(java.time.Duration.ofMinutes(30)),
    disallowDelete: Boolean = false,
    // Advertise logging. Modern requests opt in per call through `_meta`; the legacy adapter also
    // wires `logging/setLevel`. Off by default (#56 honesty).
    loggingEnabled: Boolean = false,
    // Legacy adapter only: advertise and wire resources/subscribe + resources/unsubscribe.
    // Modern subscriptions use subscriptions/listen. Off by default.
    resourcesSubscribe: Boolean = false,
    // DNS-rebinding protection (Streamable/stateless HTTP). When `Some`, an HTTP request whose
    // `Host` (or `Origin`) hostname — port ignored — is not in the set is rejected with 403.
    // `None` (default) disables host checking, preserving prior behavior.
    allowedHosts: Option[Set[String]] = None,
    // Optional io.modelcontextprotocol/tasks extension. Off by default.
    tasks: TaskSettings = TaskSettings()
)
