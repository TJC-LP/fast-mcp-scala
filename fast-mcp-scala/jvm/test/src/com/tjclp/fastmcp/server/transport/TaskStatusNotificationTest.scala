package com.tjclp.fastmcp
package server.transport

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.{Task as _, *}
import zio.json.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.jsonrpc.JsonRpcMessage
import com.tjclp.fastmcp.macros.RegistrationMacro.*
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.router.{McpRouter, Session}
import com.tjclp.fastmcp.server.transport.JvmTransportBackend.given

/** DRAFT (C3 step 10, TJC-2328; decision g) — `notifications/tasks/status` on the legacy task
  * surface. The coverage map (S1.8) found the string in ZERO test files while docs/tasks.md:98-101
  * and docs/2026-07-28-upgrade.md:61-64 say status notifications are NOT emitted; D6 observed the
  * opposite (L8 x2 on the JVM GET channel, stdio x2, TS SDK x2): the compatibility adapter emits
  * one `notifications/tasks/status` per TERMINAL transition (`completed` / `failed` / `cancelled`)
  * and none for the initial `working`.
  *
  * Mechanism pinned here: `TaskRouting.legacy` passes `onStatusChange = session.send(...)` under
  * `runWithoutSink` (TaskRouting.scala:100-108), and `TaskManager.recordTerminal` is the only
  * caller of `onStatusChange` (TaskManager.scala:639), so the frame lands on `Session.outbound` —
  * the channel the stdio drainer and the JVM GET SSE stream read (Bun legacy sessions have no
  * drainer, D4 row 35 / C3 outbound.md). Expected GREEN on main 8b2fede: it records the behaviour
  * the PR-B doc fix must describe.
  */
class TaskStatusNotificationTest extends AnyFunSuite with Matchers:

  object NotifyServer:
    @Tool(name = Some("quick"), description = Some("Completes at once"), taskSupport = Some("optional"))
    def quick(): String = "quick"

    @Tool(name = Some("nap"), description = Some("Sleeps 30 s"), taskSupport = Some("optional"))
    def nap(): ZIO[Any, Throwable, String] = ZIO.sleep(30.seconds).as("rested")

    @Tool(name = Some("boom"), description = Some("Always fails"), taskSupport = Some("optional"))
    def boom(): ZIO[Any, Throwable, String] = ZIO.fail(new RuntimeException("task boom"))

    @Tool(name = Some("die"), description = Some("Dies with a defect"), taskSupport = Some("optional"))
    def die(): ZIO[Any, Throwable, String] = ZIO.die(new IllegalStateException("task defect"))

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""

  private def callWithTask(id: Int, tool: String): String =
    s"""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$tool","arguments":{},"task":{}}}"""

  private def tasksGet(id: Int, taskId: String): String =
    s"""{"jsonrpc":"2.0","id":$id,"method":"tasks/get","params":{"taskId":"$taskId"}}"""

  private def tasksCancel(id: Int, taskId: String): String =
    s"""{"jsonrpc":"2.0","id":$id,"method":"tasks/cancel","params":{"taskId":"$taskId"}}"""

  private val TaskIdRe = """"taskId":"([^"]+)"""".r

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private def buildRouter(): McpRouter[Any] =
    val server = McpServer.typed[Any](
      "NotifyT",
      "0.1.0",
      McpServerSettings(tasks = TaskSettings(enabled = true, pollIntervalMs = 50))
    )
    val _ = server.scanAnnotations[NotifyServer.type]
    runUnsafe(server.buildRouter)

  private def frame(router: McpRouter[Any], session: Session, text: String): Option[String] =
    runUnsafe(MessageLoop.handleFrame(router, session, text))

  private def createTask(router: McpRouter[Any], session: Session, tool: String): String =
    val created = frame(router, session, callWithTask(2, tool)).getOrElse(fail("no create reply"))
    created should include(""""task"""")
    TaskIdRe.findFirstMatchIn(created).map(_.group(1)).getOrElse(fail(s"no taskId in $created"))

  /** Poll `tasks/get` until the task reports `status`; the store update precedes the notification. */
  private def awaitStatus(router: McpRouter[Any], session: Session, taskId: String, status: String): Unit =
    runUnsafe(
      (ZIO.sleep(20.millis) *> MessageLoop.handleFrame(router, session, tasksGet(3, taskId)))
        .repeatUntil(_.exists(_.contains(s""""status":"$status"""")))
        .timeoutFail(new RuntimeException(s"task $taskId never reached $status"))(10.seconds)
        .unit
    )

  /** Wait for the outbound channel to hold at least one frame, then take everything on it. */
  private def drainOutbound(session: Session): Chunk[JsonRpcMessage] =
    runUnsafe(
      (ZIO.sleep(20.millis) *> session.outbound.size)
        .repeatUntil(_ >= 1)
        .timeoutFail(new RuntimeException("nothing arrived on session.outbound"))(10.seconds) *>
        ZIO.sleep(150.millis) *> // let a hypothetical second frame land before we count
        session.outbound.takeAll
    )

  private def statusFrames(outbound: Chunk[JsonRpcMessage]): Chunk[String] =
    outbound.collect { case JsonRpcMessage.Notification(Tasks.NotificationTasksStatus, params) =>
      params.map(_.toJson).getOrElse("")
    }

  private def newSession(router: McpRouter[Any]): Session =
    val session = runUnsafe(Session.make("stdio"))
    val _ = frame(router, session, initFrame)
    session

  test("legacy session: exactly one notifications/tasks/status lands on session.outbound, at the terminal 'completed' transition (none for 'working')") {
    val router = buildRouter()
    val session = newSession(router)
    val taskId = createTask(router, session, "quick")
    awaitStatus(router, session, taskId, "completed")

    val outbound = drainOutbound(session)
    val frames = statusFrames(outbound)
    withClue(s"outbound frames: ${outbound.map(_.toJson).mkString("\n")} ") {
      outbound.size shouldBe 1 // nothing but the status notification travels out-of-request
      frames.size shouldBe 1
      frames.head should include(s""""taskId":"$taskId"""")
      frames.head should include(""""status":"completed"""")
      frames.head should include(""""createdAt"""")
      frames.head should include(""""lastUpdatedAt"""")
    }
  }

  test("legacy session: tasks/cancel emits one notifications/tasks/status with status 'cancelled'") {
    val router = buildRouter()
    val session = newSession(router)
    val taskId = createTask(router, session, "nap")
    val cancelReply = frame(router, session, tasksCancel(3, taskId)).getOrElse(fail("no cancel reply"))
    cancelReply should include(""""status":"cancelled"""")

    val frames = statusFrames(drainOutbound(session))
    frames.size shouldBe 1
    frames.head should include(s""""taskId":"$taskId"""")
    frames.head should include(""""status":"cancelled"""")
  }

  private def tasksResult(id: Int, taskId: String): String =
    s"""{"jsonrpc":"2.0","id":$id,"method":"tasks/result","params":{"taskId":"$taskId"}}"""

  test("legacy session: a FAILING tool body is a completed task with an in-band isError result (SEP-2663 tool-error-uses-completed-status) and emits one 'completed' notification") {
    // Builtins.scala:147: on the legacy adapter every handler failure surfaces as isError:true, so
    // the task's run effect SUCCEEDS and recordTerminal records Completed, not Failed.
    val router = buildRouter()
    val session = newSession(router)
    val taskId = createTask(router, session, "boom")
    awaitStatus(router, session, taskId, "completed")
    val result = frame(router, session, tasksResult(4, taskId)).getOrElse(fail("no tasks/result reply"))
    result should include(""""isError":true""")

    val frames = statusFrames(drainOutbound(session))
    frames.size shouldBe 1
    frames.head should include(s""""taskId":"$taskId"""")
    frames.head should include(""""status":"completed"""")
  }

  test("legacy session: a task body that DIES (defect) is a failed task and emits one 'failed' notification") {
    val router = buildRouter()
    val session = newSession(router)
    val taskId = createTask(router, session, "die")
    awaitStatus(router, session, taskId, "failed")

    val frames = statusFrames(drainOutbound(session))
    frames.size shouldBe 1
    frames.head should include(s""""taskId":"$taskId"""")
    frames.head should include(""""status":"failed"""")
  }

  test("the stateless legacy adapter never reaches the notification path: task augmentation is refused with -32601") {
    // `Session.make(..., supportsTasks = false)` is the shared stateless identity
    // (JvmHttpBackend.scala:226-227); TaskRouting.scala:82-88 refuses before any task exists.
    val router = buildRouter()
    val session = runUnsafe(Session.make("stateless", supportsTasks = false))
    val _ = runUnsafe(session.markInitialized)
    val reply = frame(router, session, callWithTask(2, "quick")).getOrElse(fail("no reply"))
    reply should include(""""code":-32601""")
    runUnsafe(session.outbound.size) shouldBe 0
  }
