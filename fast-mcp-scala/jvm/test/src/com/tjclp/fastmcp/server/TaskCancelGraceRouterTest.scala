package com.tjclp.fastmcp.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.*

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.core.{TaskSupport, Tasks}
import com.tjclp.fastmcp.core.wire.Implementation
import com.tjclp.fastmcp.jsonrpc.JsonRpcMessage
import com.tjclp.fastmcp.server.manager.TaskManager
import com.tjclp.fastmcp.server.router.{McpRouter, RouterBuilder, Session}
import com.tjclp.fastmcp.server.transport.MessageLoop
import com.tjclp.fastmcp.testkit.{Eventually, ManualClock}

/** `tasks/cancel` on a task whose body does not stop, through the router: what the two protocol
  * eras answer once `TaskSettings.cancelGraceMs` has passed, and where the legacy status
  * notification goes (GH #115, D6.14 / C3.3). The manager-level contract is in
  * `TaskCancelGraceSpec`.
  *
  * The tool's body waits uninterruptibly on a promise the test completes, which is what a
  * synchronous body is to an interrupt; the grace runs on a clock the test moves.
  */
class TaskCancelGraceRouterTest extends AnyFunSuite with Matchers:

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private case class NoArgs()

  private val GraceMs = 5_000L

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""

  private val taskMeta =
    s""""_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{"extensions":{"${Tasks.ExtensionId}":{}}}}"""

  private def modern(id: Int, method: String, fields: String): String =
    s"""{"jsonrpc":"2.0","id":$id,"method":"$method","params":{$fields,$taskMeta}}"""

  private def legacy(id: Int, method: String, params: String): String =
    s"""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}"""

  /** The router, its task manager, and the handles of the `stuck` tool's body. */
  private final class Fixture:
    val clock = new ManualClock
    val started: Promise[Nothing, Unit] = runUnsafe(Promise.make[Nothing, Unit])
    val release: Promise[Nothing, Unit] = runUnsafe(Promise.make[Nothing, Unit])
    val settled: Ref[Int] = runUnsafe(Ref.make(0))

    private val settings =
      McpServerSettings(tasks = TaskSettings(enabled = true, cancelGraceMs = GraceMs))
    private val server = McpServer.typed[Any]("GraceServer", "0.1.0", settings)
    runUnsafe(
      server.tool(
        McpTool[NoArgs, String](name = "stuck")(_ =>
          (started.succeed(()) *> release.await.as("late")).uninterruptible
        ).withTaskSupport(TaskSupport.Optional)
      )
    )

    val tm: TaskManager[Any] = TaskManager.makeUnsafe[Any](
      settings.tasks,
      ZIO.succeed(java.util.UUID.randomUUID().toString),
      stage => ZIO.when(stage == "settled")(settled.update(_ + 1)).unit,
      clock
    )

    val router: McpRouter[Any] = RouterBuilder.build[Any](
      serverInfo = Implementation(name = "GraceServer", version = "0.1.0"),
      instructions = None,
      toolManager = server.toolManager,
      promptManager = server.promptManager,
      resourceManager = server.resourceManager,
      settings = settings,
      taskManager = Some(tm)
    )

    def frame(session: Session, value: String): String =
      runUnsafe(MessageLoop.handleFrame(router, session, value)).getOrElse(fail("no response"))

    /** The task's body has returned and what it left has been dealt with. */
    def awaitBodySettled(): Unit =
      val _ = runUnsafe(release.succeed(()))
      val _ = runUnsafe(Eventually("both settlements recorded")(settled.get)(_ >= 2))

    def close(): Unit = runUnsafe(release.succeed(()) *> tm.shutdown)

  private def taskIdOf(reply: String): String =
    val id = reply.fromJson[zio.json.ast.Json].toOption.flatMap { json =>
      val result = json.asObject.flatMap(_.get("result")).flatMap(_.asObject)
      result
        .flatMap(_.get("taskId"))
        .orElse(result.flatMap(_.get("task")).flatMap(_.asObject).flatMap(_.get("taskId")))
        .flatMap(_.asString)
    }
    id.getOrElse(fail(s"no taskId in $reply"))

  private def statusNotifications(messages: Chunk[JsonRpcMessage]): List[String] =
    messages.toList.collect {
      case JsonRpcMessage.Notification(Tasks.NotificationTasksStatus, params) =>
        params.map(_.toJson).getOrElse("")
    }

  test("legacy: the reply shows the task cancelled; one status notification, on the session") {
    val f = new Fixture
    try
      val session = runUnsafe(Session.make("legacy"))
      f.frame(session, initFrame) should include(""""tasks"""")
      val created =
        f.frame(session, legacy(2, "tools/call", """{"name":"stuck","arguments":{},"task":{}}"""))
      val taskId = taskIdOf(created)
      runUnsafe(f.started.await)

      // The cancel request has a reply stream of its own, as over HTTP.
      val sink = runUnsafe(Queue.unbounded[JsonRpcMessage])
      val cancelFrame = legacy(3, "tasks/cancel", s"""{"taskId":"$taskId"}""")
      val cancel = runUnsafe(
        session.runWithSink(sink)(MessageLoop.handleFrame(f.router, session, cancelFrame)).forkDaemon
      )
      runUnsafe(f.clock.awaitSleeping(Duration.fromMillis(GraceMs)))
      f.clock.advanceMs(GraceMs - 1L)
      runUnsafe(cancel.poll) shouldBe None
      f.clock.advanceMs(1L)
      val reply = runUnsafe(cancel.join).getOrElse(fail("no reply to tasks/cancel"))
      reply should include(""""status":"cancelled"""")

      // The notification is not part of the cancel's reply stream. It is on the session's
      // out-of-request channel, where the task's own fiber would have put it.
      runUnsafe(sink.size) shouldBe 0
      val told = statusNotifications(runUnsafe(session.outbound.takeAll))
      told should have size 1
      told.head should (include(taskId) and include(""""status":"cancelled""""))

      val result = f.frame(session, legacy(4, "tasks/result", s"""{"taskId":"$taskId"}"""))
      result should (include("-32602") and include("was cancelled"))

      // The body returns "late": nothing about the task changes and nothing more is sent.
      f.awaitBodySettled()
      f.frame(session, legacy(5, "tasks/get", s"""{"taskId":"$taskId"}""")) should
        include(""""status":"cancelled"""")
      statusNotifications(runUnsafe(session.outbound.takeAll)) shouldBe Nil
      f.frame(session, legacy(6, "tasks/result", s"""{"taskId":"$taskId"}""")) should
        include("was cancelled")
    finally f.close()
  }

  test("2026-07-28: the cancel is acknowledged; tasks/get shows cancelled and never a result") {
    val f = new Fixture
    try
      val session = runUnsafe(Session.make("modern", clientKey = Some("A")))
      val created = f.frame(session, modern(1, "tools/call", """"name":"stuck","arguments":{}"""))
      created should include(""""resultType":"task"""")
      val taskId = taskIdOf(created)
      runUnsafe(f.started.await)

      val cancelFrame = modern(2, "tasks/cancel", s""""taskId":"$taskId"""")
      val cancel =
        runUnsafe(MessageLoop.handleFrame(f.router, session, cancelFrame).forkDaemon)
      runUnsafe(f.clock.awaitSleeping(Duration.fromMillis(GraceMs)))
      f.clock.advanceMs(GraceMs - 1L)
      runUnsafe(cancel.poll) shouldBe None
      f.clock.advanceMs(1L)
      val reply = runUnsafe(cancel.join).getOrElse(fail("no reply to tasks/cancel"))
      reply should include(""""resultType":"complete"""")
      reply should not include "error"

      val shown = f.frame(session, modern(3, "tasks/get", s""""taskId":"$taskId""""))
      shown should include(""""status":"cancelled"""")
      runUnsafe(f.tm.stats).running shouldBe 0

      f.awaitBodySettled()
      val late = f.frame(session, modern(4, "tasks/get", s""""taskId":"$taskId""""))
      late should include(""""status":"cancelled"""")
      late should not include "late"
      // Cancelling it again is the idempotent acknowledgement of a terminal task.
      f.frame(session, cancelFrame) should include(""""resultType":"complete"""")
    finally f.close()
  }
