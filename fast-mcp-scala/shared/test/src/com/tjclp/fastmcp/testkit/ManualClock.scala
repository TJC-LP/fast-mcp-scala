package com.tjclp.fastmcp
package testkit

import java.util.concurrent.atomic.AtomicReference

import zio.*

import com.tjclp.fastmcp.server.ServerClock

/** A [[ServerClock]] that only moves when the test moves it (CONTRIBUTING.md, "Tests and time").
  *
  * `monotonicMs()` returns the current reading; `sleep` parks the calling fiber until the reading
  * has passed its deadline. [[advance]] moves the reading and wakes every sleeper that is due, in
  * deadline order. Nothing here reads the machine's clock, so a test built on it asserts the same
  * thing on an idle laptop and on a loaded CI runner.
  *
  * Two rules for a test:
  *   - before advancing past a deadline a fiber is expected to sleep to, wait until it is parked
  *     ([[awaitSleepers]]); a fiber that reaches `sleep` after the advance sleeps from the new
  *     reading and is not woken by it;
  *   - waking a fiber does not run it. After an advance, wait for the effect you expect
  *     (`Eventually`, a `Promise`, the next [[awaitSleepers]]) before asserting on it. What must
  *     NOT have happened needs no wait when nothing but a further advance could cause it.
  *
  * Compiled into the JVM, Scala.js and Scala Native test modules (`shared/test/src`): no threads,
  * no blocking.
  */
final class ManualClock(startMs: Long = 1_000_000L) extends ServerClock:
  import ManualClock.*

  private val state = new AtomicReference(State(startMs, 0L, Vector.empty, Vector.empty))

  def monotonicMs(): Long = state.get().nowMs

  def sleep(duration: Duration): UIO[Unit] =
    // Due at once, as `ZIO.sleep` of nothing: never parked, so never counted.
    if duration.toMillis <= 0L then ZIO.unit
    else
      // Registered as the fiber parks; an interrupted sleep takes itself off the clock.
      ZIO.asyncInterrupt[Any, Nothing, Unit] { resume =>
        val forMs = duration.toMillis
        val before = state.getAndUpdate { s =>
          val sleepers =
            s.sleepers :+ Sleeper(s.nextId, s.nowMs + forMs, forMs, () => resume(ZIO.unit))
          s.copy(
            nextId = s.nextId + 1,
            sleepers = sleepers,
            watchers = s.watchers.filterNot(_.satisfied(sleepers))
          )
        }
        val id = before.nextId
        val parked = before.sleepers :+ Sleeper(id, before.nowMs + forMs, forMs, () => ())
        before.watchers.filter(_.satisfied(parked)).foreach(_.reached())
        Left(ZIO.succeed(forget(id)))
      }

  private def forget(id: Long): Unit =
    val _ = state.updateAndGet(s => s.copy(sleepers = s.sleepers.filterNot(_.id == id)))

  /** Move the reading forward by `ms` and wake the sleepers now due. Synchronous, for a test that
    * is not written in ZIO; [[advance]] is the same step as an effect.
    */
  def advanceMs(ms: Long): Unit =
    val before = state.getAndUpdate { s =>
      val now = s.nowMs + math.max(0L, ms)
      s.copy(nowMs = now, sleepers = s.sleepers.filter(_.dueMs > now))
    }
    val now = before.nowMs + math.max(0L, ms)
    before.sleepers.filter(_.dueMs <= now).sortBy(s => (s.dueMs, s.id)).foreach(_.wake())

  def advance(duration: Duration): UIO[Unit] = ZIO.succeed(advanceMs(duration.toMillis))

  /** How many fibers are parked in [[sleep]] right now. */
  def sleepers: Int = state.get().sleepers.size

  /** The durations, in milliseconds, of the sleeps fibers are parked in right now (what each asked
    * for, not what is left of it). Tells one timer from another when several share the clock.
    */
  def sleeping: List[Long] = state.get().sleepers.map(_.forMs).toList

  /** Completes once at least `count` fibers are parked in [[sleep]] at the same time: the point at
    * which an advance is certain to reach them.
    */
  def awaitSleepers(count: Int): UIO[Unit] = await(_.size >= count)

  /** Completes once a fiber is parked in a [[sleep]] of exactly `duration`. For a clock that more
    * than one timer waits on: name the one the test is about by the delay it asks for.
    */
  def awaitSleeping(duration: Duration): UIO[Unit] =
    await(_.exists(_.forMs == duration.toMillis))

  private def await(satisfied: Vector[Sleeper] => Boolean): UIO[Unit] =
    ZIO.async[Any, Nothing, Unit] { resume =>
      val before = state.getAndUpdate { s =>
        if satisfied(s.sleepers) then s
        else s.copy(watchers = s.watchers :+ Watcher(satisfied, () => resume(ZIO.unit)))
      }
      if satisfied(before.sleepers) then resume(ZIO.unit)
    }

object ManualClock:

  private final case class Sleeper(id: Long, dueMs: Long, forMs: Long, wake: () => Unit)

  private final case class Watcher(
      satisfied: Vector[Sleeper] => Boolean,
      reached: () => Unit
  )

  private final case class State(
      nowMs: Long,
      nextId: Long,
      sleepers: Vector[Sleeper],
      watchers: Vector[Watcher]
  )
