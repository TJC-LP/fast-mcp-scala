package com.tjclp.fastmcp.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*

import com.tjclp.fastmcp.{given, *}

/** Tasks need a session-durable transport: stateless HTTP shares one session id across all
  * clients, so a task-enabled stateless server would leak tasks between them. `buildRouter` fails
  * fast on that combination (covering `runHttp` and platform entry points that build the router
  * directly); streamable HTTP and stdio both work.
  */
class TaskTransportGuardTest extends AnyFunSuite with Matchers:

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  test("tasks + stateless HTTP fails fast at router construction") {
    val server = McpServer(
      "GuardServer",
      "0.1.0",
      McpServerSettings(stateless = true, tasks = TaskSettings(enabled = true))
    )
    val exit = runUnsafe(server.buildRouter.exit)
    exit match
      case Exit.Failure(cause) =>
        val err = cause.failureOption.getOrElse(fail("expected a typed failure"))
        err shouldBe an[IllegalStateException]
        err.getMessage should include("stateless")
      case Exit.Success(_) => fail("expected buildRouter to fail for tasks + stateless")
  }

  test("tasks build on the default streamable settings (also used by stdio)") {
    val server =
      McpServer("GuardServer2", "0.1.0", McpServerSettings(tasks = TaskSettings(enabled = true)))
    noException should be thrownBy runUnsafe(server.buildRouter)
  }
