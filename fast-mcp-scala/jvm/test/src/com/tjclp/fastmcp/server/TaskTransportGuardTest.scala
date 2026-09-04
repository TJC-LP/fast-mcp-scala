package com.tjclp.fastmcp.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.ast.Json

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.core.{Tasks, TaskSupport}
import com.tjclp.fastmcp.core.wire.Implementation
import com.tjclp.fastmcp.jsonrpc.{JsonRpcMessage, RequestId}
import com.tjclp.fastmcp.server.manager.TaskManager
import com.tjclp.fastmcp.server.router.{McpRouter, RouterBuilder, Session}
import com.tjclp.fastmcp.server.transport.MessageLoop

/** Modern Tasks are bearer handles explicitly designed to outlive protocol-level sessions. The
  * router-level cases below drive `McpRouter` directly with a hand-built `TaskManager`, so they
  * exercise the accounting (per-client buckets, per-pool ceilings), session release, and the
  * cancellation race without a transport in the way.
  */
class TaskTransportGuardTest extends AnyFunSuite with Matchers:

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private case class NoArgs()

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""

  private val taskMeta =
    s""""_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{"extensions":{"${Tasks.ExtensionId}":{}}}}"""

  private def modernCall(id: Int, tool: String): String =
    s"""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$tool","arguments":{},$taskMeta}}"""

  private def legacyCall(id: Int, tool: String): String =
    s"""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$tool","arguments":{},"task":{"ttl":60000}}}"""

  private def frame(router: McpRouter[Any], session: Session, value: String): String =
    runUnsafe(MessageLoop.handleFrame(router, session, value)).getOrElse(fail("no response"))

  /** A server with a gated `blocky` tool plus a router over a TaskManager we keep a handle on. */
  private def build(
      settings: TaskSettings,
      gate: Promise[Nothing, Unit],
      checkpoint: String => UIO[Unit] = _ => ZIO.unit,
      onInterrupt: UIO[Unit] = ZIO.unit
  ): (McpRouter[Any], TaskManager[Any]) =
    val server = McpServer.typed[Any]("GuardServer", "0.1.0", McpServerSettings(tasks = settings))
    runUnsafe(
      server.tool(
        McpTool[NoArgs, String](name = "blocky")(_ => gate.await.onInterrupt(onInterrupt).as("blocky"))
          .withTaskSupport(TaskSupport.Optional)
      )
    )
    val tm = TaskManager.makeUnsafe[Any](
      settings,
      ZIO.succeed(java.util.UUID.randomUUID().toString),
      checkpoint
    )
    val router = RouterBuilder.build[Any](
      serverInfo = Implementation(name = "GuardServer", version = "0.1.0"),
      instructions = None,
      toolManager = server.toolManager,
      promptManager = server.promptManager,
      resourceManager = server.resourceManager,
      settings = server.settings,
      taskManager = Some(tm)
    )
    (router, tm)

  private def initialized(router: McpRouter[Any], id: String): Session =
    val session = runUnsafe(Session.make(id))
    frame(router, session, initFrame) should include(""""tasks"""")
    session

  test("tasks + stateless HTTP builds and advertises the official extension") {
    val server = McpServer(
      "GuardServer",
      "0.1.0",
      McpServerSettings(stateless = true, tasks = TaskSettings(enabled = true))
    )
    val router = runUnsafe(server.buildRouter)
    router.modernCapabilities.extensions.exists(_.contains(Tasks.ExtensionId)) shouldBe true
  }

  test("tasks build on the default streamable settings (also used by stdio)") {
    val server =
      McpServer("GuardServer2", "0.1.0", McpServerSettings(tasks = TaskSettings(enabled = true)))
    noException should be thrownBy runUnsafe(server.buildRouter)
  }

  test("modern clients with distinct Session.clientKey get independent task buckets") {
    val gate = runUnsafe(Promise.make[Nothing, Unit])
    val (router, tm) = build(TaskSettings(enabled = true, maxConcurrentPerSession = 2), gate)
    try
      val a = runUnsafe(Session.make("ra", clientKey = Some("A")))
      val b = runUnsafe(Session.make("rb", clientKey = Some("B")))
      frame(router, a, modernCall(1, "blocky")) should include(""""resultType":"task"""")
      frame(router, a, modernCall(2, "blocky")) should include(""""resultType":"task"""")
      // A is at its cap; B is untouched.
      frame(router, b, modernCall(3, "blocky")) should include(""""resultType":"task"""")
      val third = frame(router, a, modernCall(4, "blocky"))
      third should include(""""code":-32602""")
      third should include("Task concurrency limit exceeded")
      third should not include "client:A"
      val stats = runUnsafe(tm.stats)
      stats.perOwner(Some("client:A")).running shouldBe 2
      stats.perOwner(Some("client:B")).running shouldBe 1
      // Keyless modern requests fall into the anonymous bucket, not into A's.
      val anon = runUnsafe(Session.make("anon"))
      frame(router, anon, modernCall(5, "blocky")) should include(""""resultType":"task"""")
      runUnsafe(tm.stats).perOwner(None).running shouldBe 1
    finally
      runUnsafe(gate.succeed(()))
      runUnsafe(tm.shutdown)
  }

  test("legacy-session pool at its ceiling does not block bearer tasks") {
    val gate = runUnsafe(Promise.make[Nothing, Unit])
    val (router, tm) =
      build(TaskSettings(enabled = true, maxConcurrentPerSession = 1, maxConcurrentTotal = 2), gate)
    try
      val s1 = initialized(router, "s1")
      val s2 = initialized(router, "s2")
      val s3 = initialized(router, "s3")
      frame(router, s1, legacyCall(10, "blocky")) should include(""""status":"working"""")
      frame(router, s2, legacyCall(11, "blocky")) should include(""""status":"working"""")
      val full = frame(router, s3, legacyCall(12, "blocky"))
      full should include(""""code":-32003""")
      full should include("Task capacity exceeded (running, limit 2)")
      // The bearer pool is counted apart from the (freely mintable) legacy sessions.
      val modern = runUnsafe(Session.make("rm", clientKey = Some("M")))
      frame(router, modern, modernCall(13, "blocky")) should include(""""resultType":"task"""")
    finally
      runUnsafe(gate.succeed(()))
      runUnsafe(tm.shutdown)
  }

  test("notifications/cancelled racing a task-augmented legacy call leaves no task behind") {
    val gate = runUnsafe(Promise.make[Nothing, Unit])
    val latch = runUnsafe(Promise.make[Nothing, Unit])
    // Hold the create after admission until the creating fiber has a pending interrupt (bounded
    // by ~5 s so a broken cancel path fails the test instead of hanging it).
    def awaitPendingInterrupt(n: Int): UIO[Unit] =
      if n <= 0 then ZIO.unit
      else
        ZIO.descriptorWith(d =>
          if d.interrupters.nonEmpty then ZIO.unit
          else ZIO.sleep(5.millis) *> awaitPendingInterrupt(n - 1)
        )
    val hook: String => UIO[Unit] =
      stage => if stage == "registered" then latch.succeed(()) *> awaitPendingInterrupt(1000) else ZIO.unit
    val (router, tm) = build(TaskSettings(enabled = true), gate, hook)
    try
      val session = initialized(router, "cancel-race")
      val id = RequestId.NumId(77)
      val call = JsonRpcMessage.Request(
        id,
        "tools/call",
        Some(
          Json.Obj(
            "name" -> Json.Str("blocky"),
            "arguments" -> Json.Obj(),
            "task" -> Json.Obj("ttl" -> Json.Num(60000))
          )
        )
      )
      val cancelled = JsonRpcMessage.Notification(
        "notifications/cancelled",
        Some(Json.Obj("requestId" -> Json.Num(77)))
      )
      val (response, stats) = runUnsafe(
        for
          fiber <- router.dispatch(session, call).fork
          _ <- latch.await
          // The dispatcher forks the pipeline BEFORE tracking it: wait until the id is tracked so
          // the cancellation cannot outrun `trackInflight`.
          _ <- (ZIO.sleep(5.millis) *> session.inflightIds.map(_.contains(id)))
            .repeatUntil(identity)
            .timeoutFail(new RuntimeException("request never tracked"))(10.seconds)
          // cancelInflight awaits the pipeline fiber, which is parked in the hook until it sees
          // the interrupt — so the cancel must run concurrently with the create.
          cancel <- router.dispatch(session, cancelled).fork
          response <- fiber.join
          _ <- cancel.join
          stats <- tm.stats
        yield (response, stats)
      )
      // A cancelled request emits no response and leaves no task: the registration rolled back.
      response shouldBe None
      stats.total shouldBe 0
      stats.running shouldBe 0
      stats.perOwner shouldBe empty
      frame(router, session, """{"jsonrpc":"2.0","id":78,"method":"tasks/list","params":{}}""") should include(
        """"tasks":[]"""
      )
      runUnsafe(session.inflightIds) shouldBe empty
    finally
      runUnsafe(gate.succeed(()))
      runUnsafe(tm.shutdown)
  }

  test("session.terminate releases the session's legacy tasks") {
    val gate = runUnsafe(Promise.make[Nothing, Unit])
    val interrupted = runUnsafe(Ref.make(0))
    val (router, tm) =
      build(TaskSettings(enabled = true), gate, onInterrupt = interrupted.update(_ + 1))
    try
      val session = initialized(router, "terminating")
      val created = frame(router, session, legacyCall(20, "blocky"))
      created should include(""""status":"working"""")
      val taskId = "\"taskId\":\"([^\"]+)\"".r
        .findFirstMatchIn(created)
        .map(_.group(1))
        .getOrElse(fail(s"missing task id in $created"))
      runUnsafe(tm.stats).running shouldBe 1
      val other = initialized(router, "bystander")
      frame(router, other, legacyCall(21, "blocky")) should include(""""status":"working"""")

      runUnsafe(session.terminate)

      val stats = runUnsafe(tm.stats)
      stats.running shouldBe 1 // the bystander's task is untouched
      stats.total shouldBe 1
      stats.perOwner.contains(Some("session:terminating")) shouldBe false
      // The released task no longer exists for anyone, and exactly its fiber was interrupted.
      runUnsafe(tm.get(taskId, Some("terminating"))) shouldBe None
      runUnsafe(
        (ZIO.sleep(10.millis) *> interrupted.get)
          .repeatUntil(_ >= 1)
          .timeoutFail(new RuntimeException("released task was not interrupted"))(10.seconds)
      ) shouldBe 1
      // Finalizers ran exactly once: terminating again is a harmless no-op.
      runUnsafe(session.terminate)
      runUnsafe(tm.stats).total shouldBe 1
      runUnsafe(interrupted.get) shouldBe 1
      // A session that never created a task terminates cleanly too.
      runUnsafe(runUnsafe(Session.make("idle")).terminate)
    finally
      runUnsafe(gate.succeed(()))
      runUnsafe(tm.shutdown)
  }
