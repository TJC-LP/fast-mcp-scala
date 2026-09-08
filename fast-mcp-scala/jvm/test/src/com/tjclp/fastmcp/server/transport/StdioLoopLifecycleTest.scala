package com.tjclp.fastmcp
package server.transport

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.stream.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.RegistrationMacro.*
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.router.Session
import com.tjclp.fastmcp.server.transport.JvmTransportBackend.given

/** Termination contracts for the stdio loop — successor to the deleted
  * FastMcpServerStdinEofSpec/ShutdownSpec, which tested machinery (FilterInputStream shim,
  * overridden runStdio) that no longer exists. In the native core, EOF is simply the inbound
  * stream ending and shutdown is fiber interruption; both must terminate the loop AND its
  * outbound-drainer fiber (a pre-C8 leak).
  */
class StdioLoopLifecycleTest extends AnyFunSuite with Matchers:

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""

  test("stdin EOF ends the loop and takes the outbound drainer down with it") {
    val program =
      for
        server <- ZIO.succeed(McpServer("EofServer"))
        router <- server.buildRouter
        session <- Session.make("stdio-eof")
        outQ <- Queue.unbounded[String]
        // A finite inbound stream IS the EOF: the loop must dispatch the frame and return.
        _ <- StdioLoop.run(
          router,
          session,
          ZStream(initFrame),
          s => outQ.offer(s).unit
        )
        _ <- outQ.take // the initialize reply was emitted before EOF completed the loop
        // The drainer is interrupted at EOF: an outbound push after loop end must NOT be emitted.
        _ <- session.send(com.tjclp.fastmcp.jsonrpc.JsonRpcMessage.Notification("post/eof", None))
        _ <- ZIO.sleep(200.millis)
        leaked <- outQ.poll
      yield leaked

    val leaked = runUnsafe(
      program.timeoutFail(new RuntimeException("stdio loop did not terminate on EOF"))(10.seconds)
    )
    leaked shouldBe None
  }

  test("interrupting the loop fiber terminates it promptly (graceful shutdown)") {
    val program =
      for
        server <- ZIO.succeed(McpServer("ShutdownServer"))
        router <- server.buildRouter
        session <- Session.make("stdio-shutdown")
        inQ <- Queue.unbounded[String] // never closed — the loop would run forever
        loop <- StdioLoop
          .run(router, session, ZStream.fromQueue(inQ), _ => ZIO.unit)
          .fork
        _ <- inQ.offer(initFrame)
        _ <- ZIO.sleep(100.millis)
        exit <- loop.interrupt
      yield exit

    val exit = runUnsafe(
      program.timeoutFail(new RuntimeException("stdio loop did not stop on interruption"))(
        10.seconds
      )
    )
    exit.isInterrupted shouldBe true
  }

  // ---------------------------------------------------------------------------------------------
  // TJC-2353: every request gets a response, tasks/result for a cancelled task included. Over
  // stdio there is no stream to close, so the symptom was simply a request id that never came
  // back (the D6 stdio driver's id 7 stayed unanswered until stdin EOF).
  // ---------------------------------------------------------------------------------------------

  object StdioTaskServer:

    @Tool(name = Some("blocky"), description = Some("Long-running"), taskSupport = Some("optional"))
    def blocky(): ZIO[Any, Throwable, String] = ZIO.sleep(30.seconds).as("blocky")

  private val TaskIdPattern = """"taskId":"([^"]+)"""".r

  /** Take frames until one carries the given request id (notifications carry none). */
  private def replyTo(outQ: Queue[String], id: Int): UIO[String] =
    outQ.take.repeatUntil(_.contains(s""""id":$id,"""))

  test("tasks/result for a cancelled task is answered over stdio (request id never left dangling)") {
    val program =
      for
        server <- ZIO.succeed(
          McpServer.typed[Any](
            "StdioTasks",
            "0.1.0",
            McpServerSettings(tasks = TaskSettings(enabled = true, pollIntervalMs = 50))
          )
        )
        _ <- ZIO.attempt(server.scanAnnotations[StdioTaskServer.type])
        router <- server.buildRouter
        session <- Session.make("stdio-tasks")
        inQ <- Queue.unbounded[String]
        outQ <- Queue.unbounded[String]
        loop <- StdioLoop.run(router, session, ZStream.fromQueue(inQ), s => outQ.offer(s).unit).fork
        _ <- inQ.offer(initFrame)
        _ <- replyTo(outQ, 1)
        _ <- inQ.offer("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        _ <- inQ.offer(
          """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"blocky","arguments":{},"task":{"ttl":60000}}}"""
        )
        created <- replyTo(outQ, 2)
        taskId <- ZIO
          .fromOption(TaskIdPattern.findFirstMatchIn(created).map(_.group(1)))
          .orElseFail(new RuntimeException(s"no taskId in: $created"))
        _ <- inQ.offer(
          s"""{"jsonrpc":"2.0","id":3,"method":"tasks/cancel","params":{"taskId":"$taskId"}}"""
        )
        cancelled <- replyTo(outQ, 3)
        _ <- inQ.offer(
          s"""{"jsonrpc":"2.0","id":4,"method":"tasks/result","params":{"taskId":"$taskId"}}"""
        )
        answer <- replyTo(outQ, 4).timeoutFail(
          new RuntimeException("tasks/result (id 4) for a cancelled task was never answered")
        )(5.seconds)
        _ <- loop.interrupt
      yield (cancelled, answer, taskId)

    val (cancelled, answer, taskId) = runUnsafe(
      program.timeoutFail(new RuntimeException("stdio task lifecycle did not complete"))(20.seconds)
    )
    cancelled should include(""""status":"cancelled"""")
    answer should include(""""code":-32602""")
    answer should include(s"Task $taskId was cancelled")
  }
