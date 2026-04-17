package com.tjclp.fastmcp
package server

import zio.*

/** JS-platform factory mirroring [[com.tjclp.fastmcp.server.McpServer$]] on the JVM. Users write
  * `McpServer("name", "0.1.0")` on either platform and get back a backend-appropriate server.
  */
object McpServer:

  def apply(
      name: String = "FastMCPScala",
      version: String = "0.1.0",
      settings: FastMcpServerSettings = FastMcpServerSettings()
  ): JsMcpServer =
    new JsMcpServer(name, version, settings)

  /** Create a server and run it on stdio in one step. */
  def stdio(
      name: String = "FastMCPScala",
      version: String = "0.1.0",
      settings: FastMcpServerSettings = FastMcpServerSettings()
  ): ZIO[Any, Throwable, Unit] =
    ZIO.succeed(apply(name, version, settings)).flatMap(_.runStdio())

  /** Create a server and run it on HTTP in one step. Currently stubbed on JS; see
    * [[JsMcpServer.runHttp]] for the tracked limitation.
    */
  def http(
      name: String = "FastMCPScala",
      version: String = "0.1.0",
      settings: FastMcpServerSettings = FastMcpServerSettings()
  ): ZIO[Any, Throwable, Unit] =
    ZIO.succeed(apply(name, version, settings)).flatMap(_.runHttp())
