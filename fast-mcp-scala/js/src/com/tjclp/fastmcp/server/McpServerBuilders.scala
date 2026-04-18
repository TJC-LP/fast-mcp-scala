package com.tjclp.fastmcp
package server

import zio.*

/** JS-platform factory mirroring [[com.tjclp.fastmcp.server.McpServer$]] on the JVM. Users write
  * `McpServer("name", "0.1.0")` on either platform and get back a backend-appropriate server.
  */
object McpServer:

  /** JS-side given so the shared sugar trait can build an `McpServerCore` without linking against
    * JS-specific types.
    */
  given McpServerCoreFactory with

    def build(name: String, version: String, settings: McpServerSettings): McpServerCore =
      new JsMcpServer(name, version, settings)

  def apply(
      name: String = "FastMCPScala",
      version: String = "0.1.0",
      settings: McpServerSettings = McpServerSettings()
  ): JsMcpServer =
    new JsMcpServer(name, version, settings)

  /** Create a server and run it on stdio in one step. */
  def stdio(
      name: String = "FastMCPScala",
      version: String = "0.1.0",
      settings: McpServerSettings = McpServerSettings()
  ): ZIO[Any, Throwable, Unit] =
    ZIO.succeed(apply(name, version, settings)).flatMap(_.runStdio())

  /** Create a server and run it on HTTP in one step.
    *
    * Uses the Bun-first Streamable HTTP backend. Set `settings.stateless = true` for stateless
    * request/response mode.
    */
  def http(
      name: String = "FastMCPScala",
      version: String = "0.1.0",
      settings: McpServerSettings = McpServerSettings()
  ): ZIO[Any, Throwable, Unit] =
    ZIO.succeed(apply(name, version, settings)).flatMap(_.runHttp())
