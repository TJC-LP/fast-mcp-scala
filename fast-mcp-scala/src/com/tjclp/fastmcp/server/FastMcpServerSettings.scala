package com.tjclp.fastmcp.server

/** Settings for the FastMCPScala server
  */
case class FastMcpServerSettings(
    debug: Boolean = false,
    logLevel: String = "INFO",
    host: String = "0.0.0.0",
    port: Int = 8000,
    warnOnDuplicateResources: Boolean = true,
    warnOnDuplicateTools: Boolean = true,
    warnOnDuplicatePrompts: Boolean = true,
    dependencies: List[String] = List.empty,
    // If true, advertise templates via the resources/templates/list endpoint.
    // If false, rely on clients that derive templates from resource URIs containing `{}`.
    exposeTemplatesEndpoint: Boolean = false
)
