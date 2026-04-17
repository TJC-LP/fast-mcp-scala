package com.tjclp.fastmcp
package server

import zio.*

/** Factory namespace for the public `McpServer` API on the JVM.
  *
  * `McpServer(...)` returns a `FastMcpServer` — the annotation-scanning, tool-mounting, transport-
  * capable server. The `.http(...)` and `.stdio(...)` helpers short-circuit the common "create and
  * immediately run" pattern when you don't need to mount anything manually.
  *
  * Typical usage:
  * {{{
  *   val server = McpServer("MyServer", "0.1.0")
  *   for
  *     _ <- ZIO.attempt(server.scanAnnotations[MyServer.type])   // annotation path
  *     _ <- server.tool(addTool)                                 // typed contract path
  *     _ <- server.runStdio()
  *   yield ()
  * }}}
  */
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
