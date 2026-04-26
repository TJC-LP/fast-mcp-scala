package com.tjclp.fastmcp.server

import com.tjclp.fastmcp.core.Tasks

/** Settings for the experimental MCP Tasks feature (spec 2025-11-25).
  *
  * Off by default: the spec marks Tasks as experimental and the wire format may evolve. Enabling
  * this advertises the `tasks` capability and starts honoring `params.task` on `tools/call`.
  *
  * Tasks are only supported on the Streamable HTTP transport (`runHttp()` with `stateless = false`)
  * — stdio and stateless HTTP delegate dispatch to the Java SDK, which has no tasks code yet.
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
  *   Resource cap; additional task creations beyond this fail with an internal error.
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
    host: String = "0.0.0.0",
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
    disallowDelete: Boolean = false,
    // Experimental MCP Tasks (spec 2025-11-25). Off by default.
    tasks: TaskSettings = TaskSettings()
)

/** Deprecated alias for [[McpServerSettings]]. Kept for one release cycle to ease the rename. */
@deprecated("Use McpServerSettings", since = "0.3.0-rc2")
type FastMcpServerSettings = McpServerSettings

@deprecated("Use McpServerSettings", since = "0.3.0-rc2")
val FastMcpServerSettings = McpServerSettings
