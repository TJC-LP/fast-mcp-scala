package com.tjclp.fastmcp
package testkit

import zio.*

/** Waiting for something another fiber, thread or process does, without guessing how long it
  * takes: poll the condition until it holds, and fail with a message that says what was awaited
  * when it has not held by a deadline no healthy run comes near (CONTRIBUTING.md, "Tests and
  * time").
  *
  * The outcome of such a wait does not depend on the machine's speed, only its duration does. A
  * fixed sleep followed by an assertion is the opposite: its outcome is decided by the scheduler.
  *
  * The pause between two polls is the one place the test suite sleeps on the real clock.
  */
object Eventually:

  /** Far beyond anything a healthy run needs, on a host with every core busy. */
  val Deadline: Duration = 60.seconds

  /** The pause between two polls. */
  val Pause: Duration = 5.millis

  /** `check`, repeated until `done` accepts its result; that result. Fails when the deadline
    * passes, naming `what` and the last result seen.
    */
  def apply[R, E <: Throwable, A](what: String, deadline: Duration = Deadline)(
      check: ZIO[R, E, A]
  )(done: A => Boolean): ZIO[R, Throwable, A] =
    ZIO.suspendSucceed {
      def gaveUp(seen: String): AssertionError =
        new AssertionError(s"not within ${deadline.toMillis} ms: $what (last seen: $seen)")
      val giveUpAt = java.lang.System.nanoTime() + deadline.toNanos
      // A plain loop, not a `Schedule`: a schedule driver reads the wall clock through
      // scala-java-time on Scala.js, and merged schedules overflow the stack on Scala Native.
      def poll: ZIO[R, Throwable, A] =
        check.flatMap { a =>
          if done(a) then ZIO.succeed(a)
          else if java.lang.System.nanoTime() >= giveUpAt then ZIO.fail(gaveUp(a.toString))
          else ZIO.sleep(Pause) *> poll
        }
      // The backstop for a `check` that itself never returns.
      poll.timeoutFail(gaveUp("nothing: the check did not return"))(deadline.plus(10.seconds))
    }

  /** [[apply]] for a condition. */
  def holds[R, E <: Throwable](what: String, deadline: Duration = Deadline)(
      condition: ZIO[R, E, Boolean]
  ): ZIO[R, Throwable, Unit] =
    apply(what, deadline)(condition)(identity).unit
