package com.tjclp.fastmcp.server.transport

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.http.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.RegistrationMacro.*
import com.tjclp.fastmcp.server.*

/** Integration test for the stateless HTTP transport.
  *
  * Sets up a FastMcpServer with the ZioHttpStatelessTransport, then exercises the full MCP protocol
  * lifecycle by feeding requests directly into the zio-http Routes (no real TCP listener required).
  */
@SuppressWarnings(Array("org.wartremover.warts.Null"))
class ZioHttpStatelessTransportTest extends AnyFlatSpec with Matchers {

  // --- Fixtures: a tiny annotated server ----
  object TestServer:
    @Tool(name = Some("greet"), description = Some("Greet someone"))
    def greet(@Param("Name") name: String): String = s"Hello, $name!"

    @Tool(name = Some("add"), description = Some("Add two numbers"))
    def add(@Param("a") a: Double, @Param("b") b: Double): String = s"${a + b}"

    @Resource(uri = "info://test", name = Some("test-info"), description = Some("Test info"))
    def info(): String = """{"status":"ok"}"""

    @Prompt(name = Some("ask"), description = Some("Ask about a topic"))
    def ask(@Param("Topic") topic: String): List[Message] =
      List(Message(Role.User, TextContent(s"Tell me about: $topic")))

  // Build the transport + routes once. We drive them directly through the Routes handler
  // so no actual port is opened.
  private val runtime = zio.Runtime.default

  private val app: Routes[Any, Response] = {
    import io.modelcontextprotocol.json.McpJsonDefaults
    val server = FastMcpServer(
      name = "TestHTTP",
      version = "0.1.0",
      settings = FastMcpServerSettings()
    )
    val _ = server.scanAnnotations[TestServer.type]
    val jsonMapper = McpJsonDefaults.getMapper()
    val t = new ZioHttpStatelessTransport(jsonMapper, "/mcp")
    server.setupStatelessServer(t)
    t.routes
  }

  /** Helper: send a JSON-RPC POST to /mcp and return the response status + body. */
  private def post(body: String): (Int, String) =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe
        .run(
          ZIO.scoped {
            for {
              response <- app(
                Request
                  .post(URL(Path.root / "mcp"), Body.fromString(body))
                  .addHeader(Header.ContentType(MediaType.application.json))
                  .addHeader(
                    Header.Accept(MediaType.application.json, MediaType.text.`event-stream`)
                  )
              ).catchAll(resp => ZIO.succeed(resp))
              text <- response.body.asString
            } yield (response.status.code, text)
          }
        )
        .getOrThrowFiberFailure()
    }

  /** Helper: send POST without Accept header. */
  private def postNoAccept(body: String): (Int, String) =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe
        .run(
          ZIO.scoped {
            for {
              response <- app(
                Request
                  .post(URL(Path.root / "mcp"), Body.fromString(body))
                  .addHeader(Header.ContentType(MediaType.application.json))
              ).catchAll(resp => ZIO.succeed(resp))
              text <- response.body.asString
            } yield (response.status.code, text)
          }
        )
        .getOrThrowFiberFailure()
    }

  /** Helper: send a GET to /mcp. */
  private def get(): Int =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe
        .run(
          ZIO.scoped {
            for {
              response <- app(
                Request.get(URL(Path.root / "mcp"))
              ).catchAll(resp => ZIO.succeed(resp))
            } yield response.status.code
          }
        )
        .getOrThrowFiberFailure()
    }

  // ---------- Tests ----------

  "initialize" should "return server info and capabilities" in {
    val (code, body) = post(
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}"""
    )
    code shouldBe 200
    body should include("\"serverInfo\"")
    body should include("\"TestHTTP\"")
    body should include("\"protocolVersion\"")
  }

  "notifications/initialized" should "return 202" in {
    val (code, _) = post(
      """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
    )
    code shouldBe 202
  }

  "tools/list" should "return registered tools" in {
    val (code, body) = post(
      """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}"""
    )
    code shouldBe 200
    body should include("\"greet\"")
    body should include("\"add\"")
    body should include("\"inputSchema\"")
  }

  "tools/call greet" should "return greeting text" in {
    val (code, body) = post(
      """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"greet","arguments":{"name":"World"}}}"""
    )
    code shouldBe 200
    body should include("Hello, World!")
    body should include("\"isError\":false")
  }

  "tools/call add" should "return sum" in {
    val (code, body) = post(
      """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"add","arguments":{"a":3.14,"b":2.86}}}"""
    )
    code shouldBe 200
    body should include("6.0")
    body should include("\"isError\":false")
  }

  "tools/call nonexistent" should "return JSON-RPC error" in {
    val (code, body) = post(
      """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"nonexistent","arguments":{}}}"""
    )
    code shouldBe 200 // JSON-RPC errors are still HTTP 200
    body should include("\"error\"")
  }

  "resources/list" should "return registered resources" in {
    val (code, body) = post(
      """{"jsonrpc":"2.0","id":6,"method":"resources/list","params":{}}"""
    )
    code shouldBe 200
    body should include("info://test")
    body should include("\"test-info\"")
  }

  "resources/read" should "return resource content" in {
    val (code, body) = post(
      """{"jsonrpc":"2.0","id":7,"method":"resources/read","params":{"uri":"info://test"}}"""
    )
    code shouldBe 200
    body should include("status")
    body should include("ok")
  }

  "prompts/list" should "return registered prompts" in {
    val (code, body) = post(
      """{"jsonrpc":"2.0","id":8,"method":"prompts/list","params":{}}"""
    )
    code shouldBe 200
    body should include("\"ask\"")
    body should include("\"topic\"")
  }

  "prompts/get" should "return prompt messages" in {
    val (code, body) = post(
      """{"jsonrpc":"2.0","id":9,"method":"prompts/get","params":{"name":"ask","arguments":{"topic":"MCP"}}}"""
    )
    code shouldBe 200
    body should include("Tell me about: MCP")
  }

  "missing Accept header" should "return 400" in {
    val (code, body) = postNoAccept(
      """{"jsonrpc":"2.0","id":10,"method":"tools/list","params":{}}"""
    )
    code shouldBe 400
    body should include("Accept")
  }

  "GET /mcp" should "return 405" in {
    val code = get()
    code shouldBe 405
  }
}
