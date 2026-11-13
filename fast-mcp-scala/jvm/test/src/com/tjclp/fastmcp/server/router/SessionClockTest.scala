package com.tjclp.fastmcp
package server.router

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*

/** The session stores decide "how long idle" on the monotonic clock, so a wall-clock step can
  * neither reorder evictions at the cap nor expire sessions at the idle sweep. Antithesis's clock
  * faults found the wall-clock version: a session used a moment ago was evicted as the longest
  * idle one after the clock stepped back (run 05889e0a7b79dea3751a21c1ce9df0c4-66-21).
  */
class SessionClockTest extends AnyFunSuite with Matchers:

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private def monotonic(): Long = java.lang.System.nanoTime() / 1_000_000L

  test("a new session's lastSeen is a reading of the monotonic clock") {
    val before = monotonic()
    val seen = runUnsafe(Session.make("s").flatMap(_.lastSeen))
    val after = monotonic()
    seen should (be >= before and be <= after)
  }

  test("touch records the monotonic clock, and never moves lastSeen backwards") {
    val (first, second, after) = runUnsafe(
      for
        session <- Session.make("s")
        first <- session.lastSeen
        _ <- ZIO.attempt(Thread.sleep(5))
        _ <- session.touch
        second <- session.lastSeen
      yield (first, second, monotonic())
    )
    second should be >= (first + 4)
    second should be <= after
  }

  test("the clock the stores compare with is the one lastSeen is written from") {
    val before = Session.monotonicMs()
    val seen = runUnsafe(Session.make("s").flatMap(s => s.touch *> s.lastSeen))
    val now = Session.monotonicMs()
    seen should (be >= before and be <= now)
    // The idle age the sweeper computes is a small non-negative number, whatever the wall clock says.
    (now - seen) should (be >= 0L and be < 5_000L)
  }
