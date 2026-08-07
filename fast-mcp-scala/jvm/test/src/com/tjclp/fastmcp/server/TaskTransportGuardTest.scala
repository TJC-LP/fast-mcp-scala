package com.tjclp.fastmcp.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.core.Tasks

/** Modern Tasks are bearer handles explicitly designed to outlive protocol-level sessions. */
class TaskTransportGuardTest extends AnyFunSuite with Matchers:

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

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
