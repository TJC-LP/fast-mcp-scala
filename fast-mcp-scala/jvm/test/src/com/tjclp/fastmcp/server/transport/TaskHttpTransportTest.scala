package com.tjclp.fastmcp
package server.transport

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.RegistrationMacro.*
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.transport.JvmTransportBackend.given

/** The task lifecycle over streamable HTTP, end to end through the in-memory routes harness —
  * successor to the deleted `TaskAugmentedHttpTransportTest`. Covers capability advertisement,
  * per-tool `execution.taskSupport` negotiation (both `-32601` rejections), create → poll → result,
  * error/multi-content preservation through a task, cancellation, the per-session concurrency cap,
  * unknown-task codes (`-32602` — 0.4.0 parity, regressed pre-C1), and cross-session isolation.
  */
class TaskHttpTransportTest extends AnyFunSuite with Matchers:

  object TaskServer:

    @Tool(
      name = Some("slow"),
      description = Some("Completes after a beat"),
      taskSupport = Some("optional")
    )
    def slow(): ZIO[Any, Throwable, String] = ZIO.sleep(200.millis).as("slow done")

    @Tool(
      name = Some("must-task"),
      description = Some("Requires task augmentation"),
      taskSupport = Some("required")
    )
    def mustTask(): String = "must done"

    @Tool(name = Some("plain"), description = Some("No task support"))
    def plain(): String = "plain done"

    @Tool(
      name = Some("rich-content"),
      description = Some("Multi-content result"),
      taskSupport = Some("optional")
    )
    def richContent(): List[Content] = List(TextContent("a"), TextContent("b"))

    @Tool(
      name = Some("broken-task"),
      description = Some("Always throws"),
      taskSupport = Some("optional")
    )
    def brokenTask(): String = throw new RuntimeException("task boom")

    @Tool(name = Some("blocky"), description = Some("Long-running"), taskSupport = Some("optional"))
    def blocky(): ZIO[Any, Throwable, String] = ZIO.sleep(2.seconds).as("blocky")

  case class ChattyArgs(msg: Option[String] = None)
  given JsonDecoder[ChattyArgs] = DeriveJsonDecoder.gen[ChattyArgs]

  /** Sends progress + a log AFTER its creating POST's SSE stream has closed — the regression net
    * for the per-request queue shutdown interrupting task fibers that inherited it as their sink.
    */
  private val chattyTool = McpTool
    .withSchema[ChattyArgs, String](
      name = "chatty",
      inputSchema = ToolInputSchema.unsafeFromJsonString("""{"type":"object","properties":{}}"""),
      description = Some("Emits progress and a log mid-task")
    )
    .contextual { (_, ctx) =>
      ZIO.sleep(400.millis) *>
        ctx.get.sendProgress(ProgressToken.StringToken("t"), 0.5) *>
        ctx.get.sendLogMessage(LoggingLevel.Info, Json.Str("mid-task")).as("chatty done")
    }
    .withTaskSupport(TaskSupport.Optional)

  private val SessionIdHeader = "mcp-session-id"

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private def buildRoutes(
      maxConcurrent: Int = 64,
      stateless: Boolean = false,
      maxConcurrentTotal: Int = 1024,
      maxStoredPerOwner: Int = 256,
      minResultRetentionMs: Long = 30_000L
  ): Routes[Any, Response] =
    val server = McpServer.typed[Any](
      "TaskT",
      "0.1.0",
      McpServerSettings(
        stateless = stateless,
        tasks = TaskSettings(
          enabled = true,
          pollIntervalMs = 50,
          maxConcurrentPerSession = maxConcurrent,
          maxConcurrentTotal = maxConcurrentTotal,
          maxStoredPerOwner = maxStoredPerOwner,
          minResultRetentionMs = minResultRetentionMs
        )
      )
    )
    val _ = server.scanAnnotations[TaskServer.type]
    runUnsafe(
      server.tool(chattyTool) *>
        server.buildRouter.flatMap(r =>
          JvmHttpBackend.httpRoutes(r, server.settings, ZEnvironment.empty)
        )
    )

  private def run(routes: Routes[Any, Response], req: Request): Response =
    runUnsafe(ZIO.scoped(routes.runZIO(req)))

  private def post(routes: Routes[Any, Response], body: String, sid: Option[String]): Response =
    val base = Request
      .post(URL(Path.root / "mcp"), Body.fromString(body))
      .addHeader(Header.Custom("content-type", "application/json"))
      .addHeader(Header.Custom("accept", "application/json, text/event-stream"))
    val req = sid.fold(base)(s => base.addHeader(Header.Custom(SessionIdHeader, s)))
    run(routes, req)

  private def bodyOf(resp: Response): String = runUnsafe(resp.body.asString)

  private def initSession(routes: Routes[Any, Response]): String =
    val resp = post(routes, initFrame, None)
    // Drain the SSE body like a compliant client awaiting the initialize RESPONSE — the session
    // header arrives with the streaming response while dispatch is still running, and firing the
    // next request off the header alone races the pre-init gate (flaked under load).
    val _ = bodyOf(resp)
    resp
      .rawHeader(SessionIdHeader)
      .getOrElse(fail("initialize did not return a session id"))

  private val TaskIdPattern = """"taskId":"([^"]+)"""".r

  private def extractTaskId(body: String): String =
    TaskIdPattern.findFirstMatchIn(body).map(_.group(1)).getOrElse(fail(s"no taskId in: $body"))

  private def augmentedCall(id: Int, tool: String): String =
    s"""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$tool","arguments":{},"task":{"ttl":60000}}}"""

  private def tasksGet(id: Int, taskId: String): String =
    s"""{"jsonrpc":"2.0","id":$id,"method":"tasks/get","params":{"taskId":"$taskId"}}"""

  private def tasksResult(id: Int, taskId: String): String =
    s"""{"jsonrpc":"2.0","id":$id,"method":"tasks/result","params":{"taskId":"$taskId"}}"""

  private def tasksCancel(id: Int, taskId: String): String =
    s"""{"jsonrpc":"2.0","id":$id,"method":"tasks/cancel","params":{"taskId":"$taskId"}}"""

  private def tasksList(id: Int): String =
    s"""{"jsonrpc":"2.0","id":$id,"method":"tasks/list","params":{}}"""

  /** `_meta` block declaring a 2026-07-28 request from a client with the Tasks extension. */
  private val taskMeta =
    s""""_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{"extensions":{"${Tasks.ExtensionId}":{}}}}"""

  private def modernPost(
      routes: Routes[Any, Response],
      body: String,
      method: String,
      name: String,
      peer: Option[String] = None
  ): Response =
    val req = Request
      .post(URL(Path.root / "mcp"), Body.fromString(body))
      .addHeader(Header.Custom("content-type", "application/json"))
      .addHeader(Header.Custom("accept", "application/json, text/event-stream"))
      .addHeader(Header.Custom("mcp-protocol-version", "2026-07-28"))
      .addHeader(Header.Custom("mcp-method", method))
      .addHeader(Header.Custom("mcp-name", name))
    // `peer` stands in for netty's TCP remote address (in-memory `routes.runZIO` requests have none).
    run(routes, req.copy(remoteAddress = peer.map(java.net.InetAddress.getByName)))

  /** Poll tasks/get until the body reports the wanted status (bounded by a hard timeout). */
  private def pollUntil(
      routes: Routes[Any, Response],
      sid: String,
      taskId: String,
      status: String
  ): String =
    runUnsafe(
      (ZIO.sleep(50.millis) *> ZIO.attempt(bodyOf(post(routes, tasksGet(90, taskId), Some(sid)))))
        .repeatUntil(_.contains(s""""status":"$status""""))
        .timeoutFail(new RuntimeException(s"task $taskId never reached $status"))(15.seconds)
    )

  test("initialize advertises the tasks capability when enabled") {
    val routes = buildRoutes()
    val body = bodyOf(post(routes, initFrame, None))
    body should include(""""tasks"""")
  }

  test("tools/list carries execution.taskSupport for opted-in tools and omits it for plain") {
    val routes = buildRoutes()
    val sid = initSession(routes)
    val list = bodyOf(post(routes, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", Some(sid)))
    list should include(""""taskSupport":"optional"""")
    list should include(""""taskSupport":"required"""")
    // Objects in the tools array start with "name"; the plain tool's segment must carry no
    // execution block.
    val plainSegment = list
      .split("\"name\":")
      .find(_.startsWith("\"plain\""))
      .getOrElse(fail("plain tool missing from tools/list"))
    plainSegment should not include "taskSupport"
  }

  test("task-augmented call returns an immediate CreateTaskResult (working, ttl, pollInterval)") {
    val routes = buildRoutes()
    val sid = initSession(routes)
    val created = bodyOf(post(routes, augmentedCall(3, "slow"), Some(sid)))
    created should include(""""status":"working"""")
    created should include(""""ttl":60000""")
    created should include(""""pollInterval":50""")
    extractTaskId(created) should not be empty
  }

  test("bare call on a required-task tool is rejected -32601") {
    val routes = buildRoutes()
    val sid = initSession(routes)
    val body = bodyOf(
      post(
        routes,
        """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"must-task","arguments":{}}}""",
        Some(sid)
      )
    )
    body should include(""""code":-32601""")
    body should include("requires task augmentation")
  }

  test("task-augmented call on a non-task tool is rejected -32601") {
    val routes = buildRoutes()
    val sid = initSession(routes)
    val body = bodyOf(post(routes, augmentedCall(5, "plain"), Some(sid)))
    body should include(""""code":-32601""")
    body should include("does not support task augmentation")
  }

  test("create -> poll -> result: the task completes and yields the original result") {
    val routes = buildRoutes()
    val sid = initSession(routes)
    val taskId = extractTaskId(bodyOf(post(routes, augmentedCall(6, "slow"), Some(sid))))
    pollUntil(routes, sid, taskId, "completed")
    val result = bodyOf(post(routes, tasksResult(7, taskId), Some(sid)))
    result should include("slow done")
  }

  test("tasks/get and tasks/result answer -32602 for unknown ids (0.4.0 parity)") {
    val routes = buildRoutes()
    val sid = initSession(routes)
    val get = bodyOf(post(routes, tasksGet(8, "nope"), Some(sid)))
    get should include(""""code":-32602""")
    get should include("Unknown task")
    val res = bodyOf(post(routes, tasksResult(9, "nope"), Some(sid)))
    res should include(""""code":-32602""")
  }

  test("a throwing tool completes its task with an isError result, message preserved") {
    val routes = buildRoutes()
    val sid = initSession(routes)
    val taskId = extractTaskId(bodyOf(post(routes, augmentedCall(10, "broken-task"), Some(sid))))
    pollUntil(routes, sid, taskId, "completed")
    val result = bodyOf(post(routes, tasksResult(11, taskId), Some(sid)))
    result should include(""""isError":true""")
    result should include("task boom")
  }

  test("multi-content results survive the task round-trip") {
    val routes = buildRoutes()
    val sid = initSession(routes)
    val taskId = extractTaskId(bodyOf(post(routes, augmentedCall(12, "rich-content"), Some(sid))))
    pollUntil(routes, sid, taskId, "completed")
    val result = bodyOf(post(routes, tasksResult(13, taskId), Some(sid)))
    result should include(""""text":"a"""")
    result should include(""""text":"b"""")
  }

  test("per-session concurrency cap rejects the overflow create with -32602 (0.4.0 parity)") {
    val routes = buildRoutes(maxConcurrent = 2)
    val sid = initSession(routes)
    extractTaskId(bodyOf(post(routes, augmentedCall(14, "blocky"), Some(sid))))
    extractTaskId(bodyOf(post(routes, augmentedCall(15, "blocky"), Some(sid))))
    val third = bodyOf(post(routes, augmentedCall(16, "blocky"), Some(sid)))
    third should include(""""code":-32602""")
    third should include("concurrency limit")
  }

  test("tasks/cancel interrupts a running task; cancelling a terminal task is -32602") {
    val routes = buildRoutes()
    val sid = initSession(routes)
    val taskId = extractTaskId(bodyOf(post(routes, augmentedCall(17, "blocky"), Some(sid))))
    val cancelled = bodyOf(post(routes, tasksCancel(18, taskId), Some(sid)))
    cancelled should include(""""status":"cancelled"""")
    val again = bodyOf(post(routes, tasksCancel(19, taskId), Some(sid)))
    again should include(""""code":-32602""")
    again should include("terminal")
  }

  test("tasks are session-scoped: another session cannot see them") {
    val routes = buildRoutes()
    val sid1 = initSession(routes)
    val taskId = extractTaskId(bodyOf(post(routes, augmentedCall(20, "slow"), Some(sid1))))
    val sid2 = initSession(routes)
    val stolen = bodyOf(post(routes, tasksGet(21, taskId), Some(sid2)))
    stolen should include(""""code":-32602""")
    stolen should include("Unknown task")
  }

  test("legacy stateless: task augmentation and legacy task methods are refused with -32601") {
    val routes = buildRoutes(stateless = true)
    // All legacy stateless POSTs share the literal session id "stateless" — task ownership keyed
    // on it would let every client list, read, and cancel every other client's tasks.
    val create = bodyOf(post(routes, augmentedCall(30, "slow"), None))
    create should include(""""code":-32601""")
    create should include("stateless")
    val list = bodyOf(post(routes, tasksList(31), None))
    list should include(""""code":-32601""")
    // The 2026-07-28 bearer-task path stays available on the very same routes.
    val modern = bodyOf(
      modernPost(
        routes,
        s"""{"jsonrpc":"2.0","id":32,"method":"tools/call","params":{"name":"slow","arguments":{},$taskMeta}}""",
        "tools/call",
        "slow"
      )
    )
    modern should include(""""resultType":"task"""")
  }

  test("a task that emits progress and a log after its POST stream closes still completes") {
    val routes = buildRoutes()
    val sid = initSession(routes)
    // bodyOf drains the SSE response, which ends the stream and fires its finalizer
    // (dispatch interrupt + request-queue shutdown) while the task is still sleeping.
    val created = bodyOf(post(routes, augmentedCall(50, "chatty"), Some(sid)))
    val taskId = extractTaskId(created)
    pollUntil(routes, sid, taskId, "completed")
    val result = bodyOf(post(routes, tasksResult(51, taskId), Some(sid)))
    result should include("chatty done")
  }

  test("bearer tasks are invisible to legacy sessions (list, result, cancel)") {
    val routes = buildRoutes()
    val created = bodyOf(
      modernPost(
        routes,
        s"""{"jsonrpc":"2.0","id":40,"method":"tools/call","params":{"name":"blocky","arguments":{},$taskMeta}}""",
        "tools/call",
        "blocky"
      )
    )
    val taskId = extractTaskId(created)
    val sid = initSession(routes)
    bodyOf(post(routes, tasksList(41), Some(sid))) should not include taskId
    val result = bodyOf(post(routes, tasksResult(42, taskId), Some(sid)))
    result should include(""""code":-32602""")
    val cancel = bodyOf(post(routes, tasksCancel(43, taskId), Some(sid)))
    cancel should include(""""code":-32602""")
  }

  test("a session that loops fast task calls never holds more than maxStoredPerOwner entries") {
    val routes = buildRoutes(maxConcurrent = 8, maxStoredPerOwner = 8, minResultRetentionMs = 0L)
    val sid = initSession(routes)
    val ids = (1 to 20).map { i =>
      val taskId =
        extractTaskId(bodyOf(post(routes, augmentedCall(600 + i, "rich-content"), Some(sid))))
      // Fence: tasks/result returns only once the entry is terminal, so the next create sees an
      // evictable predecessor rather than racing the tool body.
      bodyOf(post(routes, tasksResult(700 + i, taskId), Some(sid))) should include(""""text":"a"""")
      taskId
    }
    val listed = bodyOf(post(routes, tasksList(800), Some(sid)))
    val listedIds = TaskIdPattern.findAllMatchIn(listed).map(_.group(1)).toSet
    listedIds.size should be <= 8
    listedIds should contain allElementsOf ids.takeRight(8)
    ids.take(12).foreach { old =>
      val reply = bodyOf(post(routes, tasksGet(900, old), Some(sid)))
      reply should include(""""code":-32602""")
      reply should include("Unknown task")
    }
  }

  test("pool capacity answers -32003 Task capacity exceeded across sessions") {
    val routes = buildRoutes(maxConcurrent = 1, maxConcurrentTotal = 1)
    val sid1 = initSession(routes)
    extractTaskId(bodyOf(post(routes, augmentedCall(60, "blocky"), Some(sid1))))
    val sid2 = initSession(routes)
    val rejected = bodyOf(post(routes, augmentedCall(61, "blocky"), Some(sid2)))
    rejected should include(""""code":-32003""")
    rejected should include("Task capacity exceeded (running, limit 1)")
    // The per-owner cap on the first session is still the caller's own fault: -32602.
    val own = bodyOf(post(routes, augmentedCall(62, "blocky"), Some(sid1)))
    own should include(""""code":-32602""")
    own should include("concurrency limit")
  }

  test("bearer tasks are bucketed per peer address: two peers get independent caps (F9 wiring)") {
    val routes = buildRoutes(maxConcurrent = 1)
    val blockyCall = (id: Int) =>
      s"""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"blocky","arguments":{},$taskMeta}}"""
    // Peer A fills its single slot; its second create is refused by the per-owner cap.
    extractTaskId(
      bodyOf(modernPost(routes, blockyCall(60), "tools/call", "blocky", Some("10.0.0.1")))
    )
    val overflowA =
      bodyOf(modernPost(routes, blockyCall(61), "tools/call", "blocky", Some("10.0.0.1")))
    overflowA should include(""""code":-32602""")
    overflowA should include("concurrency limit")
    // Peer B is a different owner (`Session.clientKey` = its address) and is admitted.
    extractTaskId(
      bodyOf(modernPost(routes, blockyCall(62), "tools/call", "blocky", Some("10.0.0.2")))
    )
    // Keyless requests share one anonymous bucket, independent of both peers.
    extractTaskId(bodyOf(modernPost(routes, blockyCall(63), "tools/call", "blocky", None)))
    val overflowAnon = bodyOf(modernPost(routes, blockyCall(64), "tools/call", "blocky", None))
    overflowAnon should include(""""code":-32602""")
  }

  // ---------------------------------------------------------------------------------------------
  // TJC-2353: a legacy `tasks/result` must ALWAYS be answered. A cancelled task (tasks/cancel, TTL
  // sweep) used to complete its promise with the fiber's interrupt-only cause; the awaiting handler
  // re-raised it and the router read that as "the request itself was cancelled" — no frame at all
  // (the SSE stream closed after a keepalive ping; the TS SDK's getTaskResult hung 60 s).
  // ---------------------------------------------------------------------------------------------

  private def augmentedCallTtl(id: Int, tool: String, ttlMs: Long): String =
    s"""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$tool","arguments":{},"task":{"ttl":$ttlMs}}}"""

  private val CancelledFrame = """"code":-32602"""

  test("tasks/result after tasks/cancel answers an error frame (-32602), never silence") {
    val routes = buildRoutes()
    val sid = initSession(routes)
    val taskId = extractTaskId(bodyOf(post(routes, augmentedCall(70, "blocky"), Some(sid))))
    bodyOf(post(routes, tasksCancel(71, taskId), Some(sid))) should include(""""status":"cancelled"""")
    val result = bodyOf(post(routes, tasksResult(72, taskId), Some(sid)))
    withClue(s"tasks/result body after cancel: <$result> ") {
      result should include(""""id":72""")
      result should include(CancelledFrame)
      result should include(s"Task $taskId was cancelled")
    }
  }

  test("a parked tasks/result waiter is answered when the task is cancelled") {
    val routes = buildRoutes()
    val sid = initSession(routes)
    val taskId = extractTaskId(bodyOf(post(routes, augmentedCall(73, "blocky"), Some(sid))))
    val result = runUnsafe(
      for
        waiter <- ZIO.attemptBlocking(bodyOf(post(routes, tasksResult(74, taskId), Some(sid)))).fork
        _ <- ZIO.sleep(300.millis) // let the waiter park on the task promise
        cancelled <- ZIO.attemptBlocking(bodyOf(post(routes, tasksCancel(75, taskId), Some(sid))))
        _ = cancelled should include(""""status":"cancelled"""")
        body <- waiter.join.timeoutFail(
          new RuntimeException("parked tasks/result was never answered after tasks/cancel")
        )(10.seconds)
      yield body
    )
    withClue(s"parked tasks/result body: <$result> ") {
      result should include(""""id":74""")
      result should include(CancelledFrame)
      result should include(s"Task $taskId was cancelled")
    }
  }

  test("a parked tasks/result waiter is answered when the task's TTL expires") {
    val routes = buildRoutes()
    val sid = initSession(routes)
    // ttl 300 ms on a 2 s tool: the sweeper removes the entry and interrupts the fiber while the
    // waiter is parked. The waiter must get a frame, not an EOF.
    val created = bodyOf(post(routes, augmentedCallTtl(76, "blocky", 300L), Some(sid)))
    created should include(""""ttl":300""")
    val taskId = extractTaskId(created)
    val result = runUnsafe(
      ZIO
        .attemptBlocking(bodyOf(post(routes, tasksResult(77, taskId), Some(sid))))
        .timeoutFail(
          new RuntimeException("parked tasks/result was never answered after TTL expiry")
        )(10.seconds)
    )
    withClue(s"tasks/result body during TTL expiry: <$result> ") {
      result should include(""""id":77""")
      result should include(CancelledFrame)
    }
    // Once swept, the id is simply unknown — same -32602 family as tasks/get.
    val gone = bodyOf(post(routes, tasksGet(78, taskId), Some(sid)))
    gone should include(""""code":-32602""")
    gone should include("Unknown task")
  }
