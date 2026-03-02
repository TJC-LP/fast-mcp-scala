package com.tjclp.fastmcp
package examples

import zio.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.RegistrationMacro.*
import com.tjclp.fastmcp.server.*

/** Example server demonstrating Streamable HTTP transport with session management and SSE.
  *
  * Start with: ./mill fast-mcp-scala.runMain com.tjclp.fastmcp.examples.StreamableHttpExampleServer
  *
  * Then exercise via curl:
  * {{{
  * # Initialize (creates session, returns mcp-session-id header)
  * curl -s -X POST http://localhost:8090/mcp \
  *   -H "Content-Type: application/json" \
  *   -H "Accept: application/json, text/event-stream" \
  *   -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}'
  *
  * # Call tool (returns SSE stream)
  * curl -N -X POST http://localhost:8090/mcp \
  *   -H "Content-Type: application/json" \
  *   -H "Accept: application/json, text/event-stream" \
  *   -H "mcp-session-id: <session-id>" \
  *   -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"greet","arguments":{"name":"World"}}}'
  *
  * # Delete session
  * curl -X DELETE http://localhost:8090/mcp -H "mcp-session-id: <session-id>"
  * }}}
  */
object StreamableHttpExampleServer extends ZIOAppDefault:

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
    """{"server":"StreamableHttpExample","transport":"streamable-http"}"""

  @Prompt(name = Some("summarize"), description = Some("Summarize a topic"))
  def summarize(
      @Param("Topic to summarize") topic: String
  ): List[Message] =
    List(Message(Role.User, TextContent(s"Please summarize: $topic")))

  override def run: ZIO[Any, Throwable, Unit] =
    val server = FastMcpServer(
      name = "StreamableHttpExample",
      version = "0.1.0",
      settings = FastMcpServerSettings(port = 8090)
    )
    for
      _ <- ZIO.attempt(server.scanAnnotations[StreamableHttpExampleServer.type])
      _ <- server.runHttp()
    yield ()
