package com.tjclp.fastmcp
package server

import zio.*

/** Public JVM builder namespace for the MCP server surface. */
object McpServer:

  def apply(
      name: String = "FastMCPScala",
      version: String = "0.1.0",
      settings: FastMcpServerSettings = FastMcpServerSettings()
  ): FastMcpServer =
    new FastMcpServer(name, version, settings)

  /** Create a new server and run it with HTTP transport.
    *
    * Uses streamable transport (sessions + SSE) by default. Set `settings.stateless = true` for
    * stateless transport (no sessions, no SSE).
    */
  def http(
      name: String = "FastMCPScala",
      version: String = "0.1.0",
      settings: FastMcpServerSettings = FastMcpServerSettings()
  ): ZIO[Any, Throwable, Unit] =
    ZIO.succeed(apply(name, version, settings)).flatMap(_.runHttp())

  /** Create a new server and run it with stdio transport. */
  def stdio(
      name: String = "FastMCPScala",
      version: String = "0.1.0",
      settings: FastMcpServerSettings = FastMcpServerSettings()
  ): ZIO[Any, Throwable, Unit] =
    ZIO.succeed(apply(name, version, settings)).flatMap(_.runStdio())
