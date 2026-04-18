package com.tjclp.fastmcp.server

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
    disallowDelete: Boolean = false
)

/** Deprecated alias for [[McpServerSettings]]. Kept for one release cycle to ease the rename. */
@deprecated("Use McpServerSettings", since = "0.3.0-rc2")
type FastMcpServerSettings = McpServerSettings

@deprecated("Use McpServerSettings", since = "0.3.0-rc2")
val FastMcpServerSettings = McpServerSettings
