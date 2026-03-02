package com.tjclp.fastmcp
package examples

import zio.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.RegistrationMacro.*
import com.tjclp.fastmcp.server.*

/** Minimal example server demonstrating @Tool, @Resource, and @Prompt over stateless HTTP
  * transport.
  *
  * Start with: ./mill fast-mcp-scala.runMain com.tjclp.fastmcp.examples.HttpExampleServer
  *
  * Then exercise via curl (see examples in project README or plan doc).
  */
object HttpExampleServer extends ZIOAppDefault:

  @Tool(name = Some("greet"), description = Some("Greet someone by name"))
  def greet(@Param("Name to greet") name: String): String =
    s"Hello, $name!"

  @Tool(name = Some("add"), description = Some("Add two numbers"))
  def add(
      @Param("First number") a: Double,
      @Param("Second number") b: Double
  ): String =
    s"${a + b}"

  @Resource(
    uri = "info://server",
    name = Some("server-info"),
    description = Some("Server metadata")
  )
  def serverInfo(): String =
    """{"server":"HttpExample","transport":"stateless-http"}"""

  @Prompt(name = Some("summarize"), description = Some("Summarize a topic"))
  def summarize(
      @Param("Topic to summarize") topic: String
  ): List[Message] =
    List(Message(Role.User, TextContent(s"Please summarize: $topic")))

  override def run: ZIO[Any, Throwable, Unit] =
    val server = FastMcpServer(
      name = "HttpExample",
      version = "0.1.0",
      settings = FastMcpServerSettings(port = 8090, stateless = true)
    )
    for
      _ <- ZIO.attempt(server.scanAnnotations[HttpExampleServer.type])
      _ <- server.runHttp()
    yield ()
