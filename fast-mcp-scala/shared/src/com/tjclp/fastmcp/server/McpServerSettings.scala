package com.tjclp.fastmcp.server

import com.tjclp.fastmcp.core.Tasks

/** Settings for the experimental MCP Tasks feature (spec 2025-11-25).
  *
  * Off by default: the spec marks Tasks as experimental and the wire format may evolve. Enabling
  * this advertises the `tasks` capability and starts honoring `params.task` on `tools/call`.
  *
  * Tasks need a transport whose session outlives a single request so the create→poll lifecycle
  * works: streamable HTTP (`runHttp()`, the default) and stdio (one durable session per process)
  * both qualify. Stateless HTTP does not — every client would share one task namespace — so
  * enabling tasks with `stateless = true` fails fast at startup.
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
  *   Resource cap; additional task creations beyond this are rejected with `-32602`.
  */
case class TaskSettings(
    enabled: Boolean = false,
    defaultTtlMs: Long = 3_600_000L,
    maxTtlMs: Long = 86_400_000L,
    pollIntervalMs: Long = Tasks.DefaultPollIntervalMs,
    maxConcurrentPerSession: Int = 64
)

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
    // HTTP transport settings
    // When true, runHttp() uses the stateless transport (no sessions, no SSE).
    // When false (default), runHttp() uses the streamable transport (sessions + SSE).
    stateless: Boolean = false,
    keepAliveInterval: Option[java.time.Duration] = None,
    // Streamable HTTP only: evict sessions idle longer than this (no POST/GET/DELETE activity and
    // no live GET stream). Guards the session store against abandoned clients; `None` disables.
    sessionIdleTimeout: Option[java.time.Duration] = Some(java.time.Duration.ofMinutes(30)),
    disallowDelete: Boolean = false,
    // Advertise the `logging` capability and wire `logging/setLevel`. Off by default (#56 honesty).
    loggingEnabled: Boolean = false,
    // Advertise `resources.subscribe` and wire `resources/subscribe` + `resources/unsubscribe`.
    // Off by default; only takes effect when at least one resource is registered.
    resourcesSubscribe: Boolean = false,
    // DNS-rebinding protection (Streamable/stateless HTTP). When `Some`, an HTTP request whose
    // `Host` (or `Origin`) hostname — port ignored — is not in the set is rejected with 403.
    // `None` (default) disables host checking, preserving prior behavior.
    allowedHosts: Option[Set[String]] = None,
    // Experimental MCP Tasks (spec 2025-11-25). Off by default.
    tasks: TaskSettings = TaskSettings()
)
