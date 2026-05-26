package com.tjclp.fastmcp
package server

import zio.*

/** Factory namespace for the public `McpServer` API on the JVM.
  *
  * `McpServer(...)` returns a `FastMcpServer[Any]` — the annotation-scanning, tool-mounting,
  * transport-capable server with no ZIO environment requirement. For layer-aware servers, use
  * `McpServer.typed[R](...)` (or the equivalent `FastMcpServer.typed[R](...)`) which yields
  * `FastMcpServer[R]`; `runHttp()` then returns `ZIO[R, Throwable, Unit]` so the user can supply
  * the layer via `.provide(...)`.
  *
  * The `.http(...)` and `.stdio(...)` helpers short-circuit the common "create and immediately run"
  * pattern when you don't need to mount anything manually and your handlers don't require an
  * environment.
  *
  * Typical usage:
  * {{{
  *   // Default — no environment
  *   val server = McpServer("MyServer", "0.1.0")
  *
  *   // Layer-aware
  *   val typedServer = McpServer.typed[Client]("MyServer", "0.1.0")
  *   for
  *     _ <- ZIO.attempt(typedServer.scanAnnotations[MyServer.type])
  *     _ <- typedServer.runHttp().provide(Client.default)
  *   yield ()
  * }}}
  */
object McpServer:

  /** JVM-side given so the shared sugar trait can build an `McpServerCore` without linking against
    * JVM-specific types.
    */
  given McpServerCoreFactory with

    def build(name: String, version: String, settings: McpServerSettings): McpServerCore[Any] =
      new FastMcpServer[Any](name, version, settings)

  def apply(
      name: String = "FastMCPScala",
      version: String = "0.1.0",
      settings: McpServerSettings = McpServerSettings()
  ): FastMcpServer[Any] =
    new FastMcpServer[Any](name, version, settings)

  /** Layer-aware factory. `McpServer.typed[Client]("name")` returns `FastMcpServer[Client]`. The
    * resulting server's `runHttp() / runStdio()` returns `ZIO[Client, Throwable, Unit]` — complete
    * it with `.provide(Client.default)` (or any layer producing `Client`).
    */
  def typed[R](
      name: String = "FastMCPScala",
      version: String = "0.1.0",
      settings: McpServerSettings = McpServerSettings()
  ): FastMcpServer[R] =
    new FastMcpServer[R](name, version, settings)

  /** Create a new server and run it with HTTP transport.
    *
    * Uses streamable transport (sessions + SSE) by default. Set `settings.stateless = true` for
    * stateless transport (no sessions, no SSE).
    */
  def http(
      name: String = "FastMCPScala",
      version: String = "0.1.0",
      settings: McpServerSettings = McpServerSettings()
  ): ZIO[Any, Throwable, Unit] =
    ZIO.succeed(apply(name, version, settings)).flatMap(_.runHttp())

  /** Create a new server and run it with stdio transport. */
  def stdio(
      name: String = "FastMCPScala",
      version: String = "0.1.0",
      settings: McpServerSettings = McpServerSettings()
  ): ZIO[Any, Throwable, Unit] =
    ZIO.succeed(apply(name, version, settings)).flatMap(_.runStdio())
