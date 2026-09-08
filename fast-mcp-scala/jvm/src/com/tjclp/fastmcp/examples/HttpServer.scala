package com.tjclp.fastmcp
package examples

import com.tjclp.fastmcp.*

/** MCP server over HTTP — transport is a phantom type parameter on `McpServerApp`.
  *
  * `runHttp()` (dispatched by `McpServerApp[Http, ...]`) serves MCP 2026-07-28 Streamable HTTP:
  * one stateless JSON-RPC message per `POST /mcp`, answered with plain JSON or a request-scoped SSE
  * stream — no protocol sessions, standalone GET stream, or DELETE on the modern path. Requests
  * that speak an older revision (2025-11-25 and earlier) are routed to the legacy initialize /
  * session / GET / DELETE adapter, which is on by default. `stateless = true` disables only that
  * adapter's session store (legacy replies then come back as plain JSON); modern requests are
  * unaffected. Every POST, on either path, must carry `Content-Type: application/json` — anything
  * else is refused with 415 before the body is read.
  *
  * Start with: `./mill fast-mcp-scala.jvm.runMain com.tjclp.fastmcp.examples.HttpServer`
  *
  * Modern requests (2026-07-28): one self-contained POST each, no session. Header set:
  * `MCP-Protocol-Version: 2026-07-28`, `Mcp-Method` (plus `Mcp-Name` for `tools/call`,
  * `resources/read` and `prompts/get`), `Accept: application/json, text/event-stream` (both media
  * types) and `Content-Type: application/json`; the body repeats the version and the client
  * capabilities under `params._meta`:
  * {{{
  *   # 1. Discover capabilities and supported protocol versions
  *   curl -s -X POST http://localhost:8090/mcp \
  *     -H "Content-Type: application/json" \
  *     -H "Accept: application/json, text/event-stream" \
  *     -H "MCP-Protocol-Version: 2026-07-28" \
  *     -H "Mcp-Method: server/discover" \
  *     -d '{"jsonrpc":"2.0","id":1,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}'
  *
  *   # 2. Call a tool (the reply arrives as JSON or as an SSE `event: message` frame)
  *   curl -s -N -X POST http://localhost:8090/mcp \
  *     -H "Content-Type: application/json" \
  *     -H "Accept: application/json, text/event-stream" \
  *     -H "MCP-Protocol-Version: 2026-07-28" \
  *     -H "Mcp-Method: tools/call" \
  *     -H "Mcp-Name: greet" \
  *     -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"greet","arguments":{"name":"World"},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}'
  *
  *   # 3. Wrong media type: refused with 415 before the body is read, on either protocol path
  *   curl -s -i -X POST http://localhost:8090/mcp \
  *     -H "Content-Type: text/plain" \
  *     -H "Accept: application/json, text/event-stream" \
  *     -d '{"jsonrpc":"2.0","id":3,"method":"ping"}'
  *   # HTTP/1.1 415 ...
  *   # {"jsonrpc":"2.0","id":null,"error":{"code":-32000,"message":"Content-Type must be application/json"}}
  * }}}
  *
  * Legacy 2025-11-25 adapter (also serves 2025-06-18 and earlier): `initialize` mints a session,
  * every later request carries it in `mcp-session-id`, and DELETE closes it:
  * {{{
  *   # 1. Initialize (the response carries an `mcp-session-id` header)
  *   curl -s -D- -X POST http://localhost:8090/mcp \
  *     -H "Content-Type: application/json" \
  *     -H "Accept: application/json, text/event-stream" \
  *     -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}'
  *
  *   # 2. Call a tool on that session (SSE-framed reply)
  *   curl -N -X POST http://localhost:8090/mcp \
  *     -H "Content-Type: application/json" \
  *     -H "Accept: application/json, text/event-stream" \
  *     -H "mcp-session-id: <id-from-step-1>" \
  *     -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"greet","arguments":{"name":"World"}}}'
  *
  *   # 3. Close the session (legacy adapter only)
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
