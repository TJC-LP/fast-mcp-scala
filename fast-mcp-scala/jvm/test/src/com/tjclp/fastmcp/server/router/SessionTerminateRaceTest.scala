package com.tjclp.fastmcp
package server.router

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*

import com.tjclp.fastmcp.jsonrpc.RequestId

/** The dispatcher forks a request's handler and then registers it, so `Session.terminate` can
  * sweep the in-flight registry between the two. A request registered after the sweep must still
  * be interrupted: it cannot outlive its session. Found by CI, where a `DELETE` raced a call's
  * registration and the reply stream never ended.
  */
class SessionTerminateRaceTest extends AnyFunSuite with Matchers:

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  test("a request registered after its session was terminated is interrupted") {
    // No timing: the registration happens strictly after `terminate` returned, which is the
    // order the race produces. `join` on a fiber nothing interrupts would never return.
    val (exit, tracked) = runUnsafe(
      for
        session <- Session.make("s")
        _ <- session.terminate
        fiber <- ZIO.never.forkDaemon
        _ <- session.trackInflight(RequestId.NumId(1), "tools/call", fiber)
        exit <- fiber.await
        tracked <- session.inflightIds
      yield (exit, tracked)
    )
    exit.isInterrupted shouldBe true
    tracked shouldBe empty
  }
