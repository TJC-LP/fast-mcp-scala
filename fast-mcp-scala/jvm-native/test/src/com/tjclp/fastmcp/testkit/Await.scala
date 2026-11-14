package com.tjclp.fastmcp
package testkit

/** [[Eventually]] for a test that is not written in ZIO (threads, sockets, child processes): poll
  * a condition on the calling thread until it holds, and fail naming what was awaited when it has
  * not held by a deadline no healthy run comes near (CONTRIBUTING.md, "Tests and time").
  *
  * Compiled into the JVM and Scala Native test modules (`jvm-native/test/src`); Scala.js has no
  * thread to block. The pause between two polls is, with the one in [[Eventually]], the only place
  * the test suite sleeps on the real clock.
  */
object Await:

  /** The pause between two polls, in milliseconds. */
  val PauseMs: Long = 5L

  /** `check`, repeated until `done` accepts its result; that result. */
  def until[A](what: String, deadlineMs: Long = Eventually.Deadline.toMillis)(check: => A)(
      done: A => Boolean
  ): A =
    val giveUpAt = java.lang.System.nanoTime() + deadlineMs * 1_000_000L
    @scala.annotation.tailrec
    def poll(): A =
      val a = check
      if done(a) then a
      else if java.lang.System.nanoTime() >= giveUpAt then
        throw new AssertionError(s"not within $deadlineMs ms: $what (last seen: $a)")
      else
        Thread.sleep(PauseMs)
        poll()
    poll()

  /** [[until]] for a condition. */
  def holds(what: String, deadlineMs: Long = Eventually.Deadline.toMillis)(
      condition: => Boolean
  ): Unit =
    val _ = until(what, deadlineMs)(condition)(identity)
