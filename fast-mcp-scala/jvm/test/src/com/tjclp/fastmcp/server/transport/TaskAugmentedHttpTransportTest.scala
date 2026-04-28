package com.tjclp.fastmcp.server.transport

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.{Task as _, *}
import zio.http.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.RegistrationMacro.*
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.manager.TaskManager

/** Integration test for the experimental MCP Tasks dispatch over Streamable HTTP.
  *
  * Drives the same `ZioHttpStreamableTransportProvider` that a production server would, and
  * exercises the full `tools/call` + `params.task` round-trip plus all four tasks methods.
  */
@SuppressWarnings(Array("org.wartremover.warts.Null"))
class TaskAugmentedHttpTransportTest extends AnyFlatSpec with Matchers {

  object TestServer:
    @Tool(
      name = Some("slow"),
      description = Some("A long-running tool"),
      taskSupport = Some("optional")
    )
    def slow(@Param("Greeting") who: String): String = s"Slowly hello, $who"

    @Tool(
      name = Some("must-task"),
      description = Some("Must be invoked as a task"),
      taskSupport = Some("required")
    )
    def mustTask(@Param("x") x: Int): String = s"x=$x"

    @Tool(name = Some("plain"), description = Some("Plain non-task tool"))
    def plain(@Param("y") y: Int): String = s"plain=$y"

    @Tool(
      name = Some("rich-content"),
      description = Some("Returns structured content"),
      taskSupport = Some("optional")
    )
    def richContent(): List[Content] =
      List(TextContent("alpha"), ImageContent(data = "Zm9v", mimeType = "image/png"))

    @Tool(
      name = Some("broken-task"),
      description = Some("Fails during task execution"),
      taskSupport = Some("optional")
    )
    def brokenTask(): String =
      throw new RuntimeException("task boom")

    @Tool(
      name = Some("blocky"),
      description = Some("Sleeps for 500ms to keep a task slot occupied for cap tests"),
      taskSupport = Some("optional")
    )
    def blocky(): String =
      Thread.sleep(500L)
      "done"

  private val runtime = zio.Runtime.default

  private val app: Routes[Any, Response] = {
    import io.modelcontextprotocol.json.McpJsonDefaults
    val taskSettings = TaskSettings(enabled = true)
    val server = FastMcpServer(
      name = "TaskTest",
      version = "0.1.0",
      settings = McpServerSettings(tasks = taskSettings)
    )
    val _ = server.scanAnnotations[TestServer.type]
    val jsonMapper = McpJsonDefaults.getMapper()
    val taskManager = TaskManager.makeUnsafe(taskSettings)
    val dispatcher = new TaskDispatcher(taskManager, server.toolManager, jsonMapper)
    val provider =
      new ZioHttpStreamableTransportProvider(jsonMapper, "/mcp", false, None, Some(dispatcher))
    server.setupStreamableServer(provider)
    provider.routes
  }

  private def post(
      body: String,
      sessionId: Option[String] = None,
      collectSse: Boolean = false
  ): (Int, String) =
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
                if collectSse then
                  response.body.asString
                    .timeoutFail(new RuntimeException("SSE body timeout"))(5.seconds)
                else response.body.asString
            } yield (response.status.code, text)
          }
        )
        .getOrThrowFiberFailure()
    }

  private def initSession(): String =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe
        .run(
          ZIO.scoped {
            for {
              response <- app(
                Request
                  .post(
                    URL(Path.root / "mcp"),
                    Body.fromString(
                      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}"""
                    )
                  )
                  .addHeader(Header.ContentType(MediaType.application.json))
                  .addHeader(
                    Header.Accept(MediaType.application.json, MediaType.text.`event-stream`)
                  )
              )
              sid <- ZIO
                .fromOption(response.headers.rawHeader("mcp-session-id"))
                .orElseFail(new RuntimeException("Missing mcp-session-id header"))
            } yield sid
          }
        )
        .getOrThrowFiberFailure()
    }

  private def extractTaskId(body: String): String = {
    val r = "\"taskId\"\\s*:\\s*\"([^\"]+)\"".r
    r.findFirstMatchIn(body)
      .map(_.group(1))
      .getOrElse(throw new RuntimeException(s"No taskId in body: $body"))
  }

  // ---------- Tests ----------

  "initialize" should "advertise tasks capability when enabled" in {
    val (code, body) = post(
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}"""
    )
    code shouldBe 200
    body should include("\"tasks\"")
    body should include("\"requests\"")
    body should include("\"call\"")
  }

  "tools/list" should "include execution.taskSupport on opt-in tools" in {
    val sid = initSession()
    val (code, body) = post(
      """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""",
      sessionId = Some(sid),
      collectSse = true
    )
    code shouldBe 200
    body should include("\"execution\"")
    body should include("\"taskSupport\":\"optional\"")
    body should include("\"taskSupport\":\"required\"")
    // The plain tool has no taskSupport set; should not have execution field.
    val plainStart = body.indexOf("\"plain\"")
    val plainEnd =
      Math.min(body.indexOf("}", plainStart) + 1, body.length)
    if plainStart >= 0 then {
      val plainSnippet = body.substring(plainStart, plainEnd)
      plainSnippet should not include "execution"
    }
  }

  "tools/call with params.task on optional tool" should "return CreateTaskResult" in {
    val sid = initSession()
    val (code, body) = post(
      """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"slow","arguments":{"who":"World"},"task":{"ttl":60000}}}""",
      sessionId = Some(sid)
    )
    code shouldBe 200
    body should include("\"task\"")
    body should include("\"taskId\"")
    body should include("\"working\"")
  }

  "tools/call without params.task on required tool" should "return method-not-found" in {
    val sid = initSession()
    val (code, body) = post(
      """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"must-task","arguments":{"x":1}}}""",
      sessionId = Some(sid)
    )
    code shouldBe 200
    body should include("-32601")
    body should include("requires task augmentation")
  }

  "tools/call with params.task on forbidden tool" should "return method-not-found" in {
    val sid = initSession()
    val (code, body) = post(
      """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"plain","arguments":{"y":2},"task":{"ttl":60000}}}""",
      sessionId = Some(sid)
    )
    code shouldBe 200
    body should include("-32601")
    body should include("does not support task augmentation")
  }

  "tasks/get" should "return the task state for a known task" in {
    val sid = initSession()
    val (_, createBody) = post(
      """{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"slow","arguments":{"who":"X"},"task":{"ttl":60000}}}""",
      sessionId = Some(sid)
    )
    val taskId = extractTaskId(createBody)
    val (code, body) = post(
      s"""{"jsonrpc":"2.0","id":7,"method":"tasks/get","params":{"taskId":"$taskId"}}""",
      sessionId = Some(sid)
    )
    code shouldBe 200
    body should include(taskId)
    body should include("\"createdAt\"")
    body should include("\"lastUpdatedAt\"")
  }

  "tasks/get" should "fail with -32602 for unknown task ID" in {
    val sid = initSession()
    val (code, body) = post(
      """{"jsonrpc":"2.0","id":8,"method":"tasks/get","params":{"taskId":"does-not-exist"}}""",
      sessionId = Some(sid)
    )
    code shouldBe 200
    body should include("-32602")
    body should include("Task not found")
  }

  "tasks/result" should "fail with -32602 for unknown task ID" in {
    val sid = initSession()
    val (code, body) = post(
      """{"jsonrpc":"2.0","id":18,"method":"tasks/result","params":{"taskId":"does-not-exist"}}""",
      sessionId = Some(sid)
    )
    code shouldBe 200
    body should include("-32602")
    body should include("Task not found")
  }

  "tasks/result" should "return the underlying tool result after completion" in {
    val sid = initSession()
    val (_, createBody) = post(
      """{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"slow","arguments":{"who":"Result"},"task":{"ttl":60000}}}""",
      sessionId = Some(sid)
    )
    val taskId = extractTaskId(createBody)
    val (code, body) = post(
      s"""{"jsonrpc":"2.0","id":10,"method":"tasks/result","params":{"taskId":"$taskId"}}""",
      sessionId = Some(sid)
    )
    code shouldBe 200
    body should include("Slowly hello, Result")
  }

  it should "preserve tool error results for failed handlers" in {
    val sid = initSession()
    val (_, createBody) = post(
      """{"jsonrpc":"2.0","id":19,"method":"tools/call","params":{"name":"broken-task","arguments":{},"task":{"ttl":60000}}}""",
      sessionId = Some(sid)
    )
    val taskId = extractTaskId(createBody)
    val (code, body) = post(
      s"""{"jsonrpc":"2.0","id":20,"method":"tasks/result","params":{"taskId":"$taskId"}}""",
      sessionId = Some(sid)
    )
    code shouldBe 200
    body should include(""""isError":true""")
    body should include("task boom")
  }

  it should "preserve structured content from task-backed tool calls" in {
    val sid = initSession()
    val (_, createBody) = post(
      """{"jsonrpc":"2.0","id":16,"method":"tools/call","params":{"name":"rich-content","arguments":{},"task":{"ttl":60000}}}""",
      sessionId = Some(sid)
    )
    val taskId = extractTaskId(createBody)
    val (code, body) = post(
      s"""{"jsonrpc":"2.0","id":17,"method":"tasks/result","params":{"taskId":"$taskId"}}""",
      sessionId = Some(sid)
    )
    code shouldBe 200
    body should include(""""type":"text"""")
    body should include(""""text":"alpha"""")
    body should include(""""type":"image"""")
    body should include(""""mimeType":"image/png"""")
  }

  "tasks/cancel" should "transition the task to cancelled" in {
    val sid = initSession()
    // Use a tool whose execution returns immediately, so we may race the cancel — but
    // the spec says `tasks/cancel` only fails if the task is *already* terminal. As long as the
    // cancel arrives before we observe terminal status, we'll see Cancelled.
    val (_, createBody) = post(
      """{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"slow","arguments":{"who":"Cancel"},"task":{"ttl":60000}}}""",
      sessionId = Some(sid)
    )
    val taskId = extractTaskId(createBody)
    val (code, body) = post(
      s"""{"jsonrpc":"2.0","id":12,"method":"tasks/cancel","params":{"taskId":"$taskId"}}""",
      sessionId = Some(sid)
    )
    code shouldBe 200
    // Either the task was cancelled before completion or it raced to completion;
    // both are valid outcomes per spec — cancel is a best-effort operation.
    val ok =
      body.contains("\"cancelled\"") ||
        (body.contains("-32602") && body.contains("terminal status"))
    withClue(s"Body: $body") { ok shouldBe true }
  }

  "tasks/cancel" should "reject already-terminal tasks with -32602" in {
    val sid = initSession()
    val (_, createBody) = post(
      """{"jsonrpc":"2.0","id":13,"method":"tools/call","params":{"name":"slow","arguments":{"who":"Done"},"task":{"ttl":60000}}}""",
      sessionId = Some(sid)
    )
    val taskId = extractTaskId(createBody)
    // Wait for completion via tasks/result.
    val _ = post(
      s"""{"jsonrpc":"2.0","id":14,"method":"tasks/result","params":{"taskId":"$taskId"}}""",
      sessionId = Some(sid)
    )
    val (code, body) = post(
      s"""{"jsonrpc":"2.0","id":15,"method":"tasks/cancel","params":{"taskId":"$taskId"}}""",
      sessionId = Some(sid)
    )
    code shouldBe 200
    body should include("-32602")
    body should include("terminal status")
  }

  "tools/call with params.task" should "return -32602 when concurrency cap exceeded" in {
    import io.modelcontextprotocol.json.McpJsonDefaults
    val capped = TaskSettings(enabled = true, maxConcurrentPerSession = 1)
    val server = FastMcpServer(
      name = "CappedTest",
      version = "0.1.0",
      settings = McpServerSettings(tasks = capped)
    )
    val _ = server.scanAnnotations[TestServer.type]
    val jsonMapper = McpJsonDefaults.getMapper()
    val taskManager = TaskManager.makeUnsafe(capped)
    val dispatcher = new TaskDispatcher(taskManager, server.toolManager, jsonMapper)
    val provider =
      new ZioHttpStreamableTransportProvider(jsonMapper, "/mcp", false, None, Some(dispatcher))
    server.setupStreamableServer(provider)
    val cappedApp: Routes[Any, Response] = provider.routes

    def cappedPost(body: String, sid: Option[String]): (Int, String) =
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
                  sid.foreach(s => r = r.addHeader(Header.Custom("mcp-session-id", s)))
                  r
                }
                resp <- cappedApp(req).catchAll(r => ZIO.succeed(r))
                text <- resp.body.asString
              } yield (resp.status.code, text)
            }
          )
          .getOrThrowFiberFailure()
      }

    val (_, _, sid) = Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe
        .run(
          ZIO.scoped {
            for {
              resp <- cappedApp(
                Request
                  .post(
                    URL(Path.root / "mcp"),
                    Body.fromString(
                      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}"""
                    )
                  )
                  .addHeader(Header.ContentType(MediaType.application.json))
                  .addHeader(
                    Header.Accept(MediaType.application.json, MediaType.text.`event-stream`)
                  )
              )
              text <- resp.body.asString
              sid <- ZIO
                .fromOption(resp.headers.rawHeader("mcp-session-id"))
                .orElseFail(new RuntimeException("Missing mcp-session-id header"))
            } yield (resp.status.code, text, sid)
          }
        )
        .getOrThrowFiberFailure()
    }
    // First task uses the deliberately-slow tool so the slot stays occupied.
    val (codeOk, bodyOk) = cappedPost(
      """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"blocky","arguments":{},"task":{"ttl":60000}}}""",
      Some(sid)
    )
    codeOk shouldBe 200
    bodyOk should include("\"taskId\"")

    // Second task while the first is still running → cap rejection with -32602.
    val (codeErr, bodyErr) = cappedPost(
      """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"blocky","arguments":{},"task":{"ttl":60000}}}""",
      Some(sid)
    )
    codeErr shouldBe 200
    bodyErr should include("-32602")
    bodyErr should include("concurrency limit")
  }
}
