package com.tjclp.fastmcp.server.transport

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.http.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.RegistrationMacro.*
import com.tjclp.fastmcp.server.*

/** Integration test for the Streamable HTTP transport.
  *
  * Exercises the full MCP Streamable HTTP lifecycle: session creation via initialize, SSE streaming
  * for tool/resource/prompt requests, notification handling, and session deletion.
  */
@SuppressWarnings(Array("org.wartremover.warts.Null"))
class ZioHttpStreamableTransportProviderTest extends AnyFlatSpec with Matchers {

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

  private val runtime = zio.Runtime.default

  private val app: Routes[Any, Response] = {
    import io.modelcontextprotocol.json.McpJsonDefaults
    val server = FastMcpServer(
      name = "TestStreamable",
      version = "0.1.0",
      settings = McpServerSettings()
    )
    val _ = server.scanAnnotations[TestServer.type]
    val jsonMapper = McpJsonDefaults.getMapper()
    val provider = new ZioHttpStreamableTransportProvider(jsonMapper, "/mcp")
    server.setupStreamableServer(provider)
    provider.routes
  }

  /** Helper: POST with Accept and optional mcp-session-id. Returns (status, body, headers). */
  private def post(
      body: String,
      sessionId: Option[String] = None,
      collectSse: Boolean = false
  ): (Int, String, Headers) =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe
        .run(
          ZIO.scoped {
            for {
              req <- ZIO.succeed {
                var r = Request
                  .post(URL(Path.root / "mcp"), Body.fromString(body))
                  .addHeader(Header.ContentType(MediaType.application.json))
                  .addHeader(
                    Header.Accept(MediaType.application.json, MediaType.text.`event-stream`)
                  )
                sessionId.foreach(sid => r = r.addHeader(Header.Custom("mcp-session-id", sid)))
                r
              }
              response <- app(req).catchAll(resp => ZIO.succeed(resp))
              text <-
                if collectSse then collectSseBody(response)
                else response.body.asString
              headers <- ZIO.succeed(response.headers)
            } yield (response.status.code, text, headers)
          }
        )
        .getOrThrowFiberFailure()
    }

  /** Collect SSE stream body from a streaming response.
    *
    * For SSE responses, the body is a ZStream backed by a Queue that terminates when the SDK shuts
    * down the queue via `transport.close()`. We yield first to give the daemon fiber a chance to
    * start processing before we begin consuming.
    */
  private def collectSseBody(response: Response): ZIO[Scope, Throwable, String] =
    ZIO.yieldNow *>
      response.body.asString.timeoutFail(new RuntimeException("SSE body timeout"))(5.seconds)

  /** Helper: POST without Accept header. */
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

  /** Helper: DELETE with mcp-session-id. */
  private def delete(sessionId: String): Int =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe
        .run(
          ZIO.scoped {
            for {
              response <- app(
                Request
                  .delete(URL(Path.root / "mcp"))
                  .addHeader(Header.Custom("mcp-session-id", sessionId))
              ).catchAll(resp => ZIO.succeed(resp))
            } yield response.status.code
          }
        )
        .getOrThrowFiberFailure()
    }

  /** Initialize a session and return the session ID. */
  private def initSession(): String =
    val (code, body, headers) = post(
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}"""
    )
    code shouldBe 200
    body should include("\"serverInfo\"")
    headers.rawHeader("mcp-session-id") should not be empty
    headers.rawHeader("mcp-session-id").get

  // ---------- Tests ----------

  "initialize" should "return server info with mcp-session-id header" in {
    val (code, body, headers) = post(
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}"""
    )
    code shouldBe 200
    body should include("\"TestStreamable\"")
    body should include("\"protocolVersion\"")
    headers.rawHeader("mcp-session-id") should not be empty
  }

  "notifications/initialized" should "return 202 with valid session" in {
    val sid = initSession()
    val (code, _, _) = post(
      """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
      sessionId = Some(sid)
    )
    code shouldBe 202
  }

  "tools/list" should "return SSE stream with tools" in {
    val sid = initSession()
    val (code, body, _) = post(
      """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""",
      sessionId = Some(sid),
      collectSse = true
    )
    code shouldBe 200
    body should include("\"greet\"")
    body should include("\"add\"")
  }

  "tools/call greet" should "return SSE stream with greeting" in {
    val sid = initSession()
    val (code, body, _) = post(
      """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"greet","arguments":{"name":"World"}}}""",
      sessionId = Some(sid),
      collectSse = true
    )
    code shouldBe 200
    body should include("Hello, World!")
  }

  "tools/call add" should "return SSE stream with sum" in {
    val sid = initSession()
    val (code, body, _) = post(
      """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"add","arguments":{"a":3.14,"b":2.86}}}""",
      sessionId = Some(sid),
      collectSse = true
    )
    code shouldBe 200
    body should include("6.0")
  }

  "resources/list" should "return SSE stream with resources" in {
    val sid = initSession()
    val (code, body, _) = post(
      """{"jsonrpc":"2.0","id":5,"method":"resources/list","params":{}}""",
      sessionId = Some(sid),
      collectSse = true
    )
    code shouldBe 200
    body should include("info://test")
  }

  "resources/read" should "return SSE stream with resource content" in {
    val sid = initSession()
    val (code, body, _) = post(
      """{"jsonrpc":"2.0","id":6,"method":"resources/read","params":{"uri":"info://test"}}""",
      sessionId = Some(sid),
      collectSse = true
    )
    code shouldBe 200
    body should include("status")
    body should include("ok")
  }

  "prompts/list" should "return SSE stream with prompts" in {
    val sid = initSession()
    val (code, body, _) = post(
      """{"jsonrpc":"2.0","id":7,"method":"prompts/list","params":{}}""",
      sessionId = Some(sid),
      collectSse = true
    )
    code shouldBe 200
    body should include("\"ask\"")
  }

  "prompts/get" should "return SSE stream with prompt messages" in {
    val sid = initSession()
    val (code, body, _) = post(
      """{"jsonrpc":"2.0","id":8,"method":"prompts/get","params":{"name":"ask","arguments":{"topic":"MCP"}}}""",
      sessionId = Some(sid),
      collectSse = true
    )
    code shouldBe 200
    body should include("Tell me about: MCP")
  }

  "POST without session (non-initialize)" should "return 400" in {
    val (code, body, _) = post(
      """{"jsonrpc":"2.0","id":9,"method":"tools/list","params":{}}"""
    )
    code shouldBe 400
    body should include("Session ID required")
  }

  "POST without Accept header" should "return 400" in {
    val (code, body) = postNoAccept(
      """{"jsonrpc":"2.0","id":10,"method":"initialize","params":{}}"""
    )
    code shouldBe 400
    body should include("Accept")
  }

  "DELETE with valid session" should "return 200" in {
    val sid = initSession()
    val code = delete(sid)
    code shouldBe 200
  }

  "DELETE with unknown session" should "return 404" in {
    val code = delete("nonexistent-session-id")
    code shouldBe 404
  }
}
