package com.tjclp.fastmcp
package server

import zio.*

/** The time a server reads when it decides something: how long a session has been idle, whether a
  * task has outlived its TTL or its retention grace, when a heartbeat or a watchdog is due, whether
  * a cancellation is recent enough to apply to a request registered after it.
  *
  * One instance is carried by the router ([[router.McpRouter.clock]]) and handed to everything
  * built for that server: its sessions, its task manager, its HTTP handler. Production uses
  * [[ServerClock.system]]. A test passes its own and moves it explicitly, so nothing it asserts
  * depends on how fast the machine happens to be (CONTRIBUTING.md, "Tests and time").
  *
  * Not public API: there is no supported way to run a server on another clock.
  */
private[fastmcp] trait ServerClock:

  /** Milliseconds on a monotonic clock. The origin is arbitrary; only differences mean anything. */
  def monotonicMs(): Long

  /** Suspend the calling fiber until `duration` has passed on this clock. */
  def sleep(duration: Duration): UIO[Unit]

private[fastmcp] object ServerClock:

  /** `System.nanoTime` (available on the JVM, Scala.js and Scala Native) and `ZIO.sleep`. */
  val system: ServerClock = new ServerClock:
    def monotonicMs(): Long = java.lang.System.nanoTime() / 1_000_000L
    def sleep(duration: Duration): UIO[Unit] = ZIO.sleep(duration)
