package com.tjclp.fastmcp
package examples

import com.tjclp.fastmcp.*

/** MCP server over HTTP — transport is a phantom type parameter on `McpServerApp`.
  *
  * `runHttp()` (dispatched by `McpServerApp[Http, ...]`) serves the full MCP Streamable HTTP spec —
  * `POST /mcp` for JSON-RPC, session tracking via the `mcp-session-id` header, and SSE streams for
  * long-running calls. Flip `stateless = true` on the settings for a sessionless, SSE-free
  * transport.
  *
  * Start with: `./mill fast-mcp-scala.jvm.runMain com.tjclp.fastmcp.examples.HttpServer`
  *
  * Then exercise via curl:
  * {{{
  *   # 1. Initialize (streamable mode returns an `mcp-session-id` response header)
  *   curl -s -D- -X POST http://localhost:8090/mcp \
  *     -H "Content-Type: application/json" \
  *     -H "Accept: application/json, text/event-stream" \
  *     -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}'
  *
  *   # 2. Call a tool (SSE streamed back)
  *   curl -N -X POST http://localhost:8090/mcp \
  *     -H "Content-Type: application/json" \
  *     -H "Accept: application/json, text/event-stream" \
  *     -H "mcp-session-id: <id-from-step-1>" \
  *     -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"greet","arguments":{"name":"World"}}}'
  *
  *   # 3. Close the session (streamable mode only)
  *   curl -X DELETE http://localhost:8090/mcp -H "mcp-session-id: <id>"
  * }}}
  */
object HttpServer extends McpServerApp[Http, HttpServer.type]:

  override def settings: McpServerSettings = McpServerSettings(port = 8090)

  @Tool(
    name = Some("greet"),
    description = Some("Greet someone by name"),
    readOnlyHint = Some(true)
  )
  def greet(@Param("Name to greet") name: String): String =
    s"Hello, $name!"

  @Tool(
    name = Some("add"),
    description = Some("Add two numbers"),
    readOnlyHint = Some(true),
    idempotentHint = Some(true)
  )
  def add(
      @Param("First number") a: Double,
      @Param("Second number") b: Double
  ): Double = a + b

  @Resource(
    uri = "info://server",
    name = Some("server-info"),
    description = Some("Server metadata")
  )
  def serverInfo(): String =
    """{"server":"HttpServer","transport":"streamable-http"}"""

  @Prompt(name = Some("summarize"), description = Some("Summarize a topic"))
  def summarize(@Param("Topic to summarize") topic: String): List[Message] =
    List(Message(Role.User, TextContent(s"Please summarize: $topic")))
