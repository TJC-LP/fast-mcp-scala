package com.tjclp.fastmcp
package server.manager

import scala.concurrent.{ExecutionContext, Future}

import org.scalatest.funsuite.AsyncFunSuite
import zio.{Task as _, *}

import core.{Task, TaskStatus}
import server.TaskSettings
import testkit.{Eventually, ManualClock}

/** `tasks/cancel` waits for the task's body for `TaskSettings.cancelGraceMs` and no longer (GH #115,
  * D6.14 / C3.3).
  *
  * A synchronous body sees an interrupt only when it returns. It is modelled here by an
  * uninterruptible wait on a promise the test completes, so "the body is still running" and "the
  * body has returned" are states the test sets, and the grace is time on a clock the test moves.
  * Compiled into the JVM, Scala.js and Scala Native test modules (`shared/test/src`).
  */
class TaskCancelGraceSpec extends AsyncFunSuite:

  override implicit val executionContext: ExecutionContext = ExecutionContext.global

  private def run[A](effect: ZIO[Any, Throwable, A]): Future[A] =
    Unsafe.unsafe(implicit unsafe => Runtime.default.unsafe.runToFuture(effect))

  private val GraceMs = 5_000L
  private val Session = Some("s1")

  /** What a test needs to see of one manager: how many task fibers have settled, and every status
    * the creator's callback was told.
    */
  private final case class Fixture(
      tm: TaskManager[Any],
      clock: ManualClock,
      settled: Ref[Int],
      statuses: Ref[List[TaskStatus]]
  ):
    val onStatus: Task => UIO[Unit] = task => statuses.update(_ :+ task.status)

    def awaitSettled(count: Int): ZIO[Any, Throwable, Int] =
      Eventually(s"$count task settlements recorded")(settled.get)(_ >= count)

  private def fixture(graceMs: Long): UIO[Fixture] =
    for
      settled <- Ref.make(0)
      statuses <- Ref.make(List.empty[TaskStatus])
      clock = new ManualClock
      ids = new java.util.concurrent.atomic.AtomicLong
      tm = TaskManager.makeUnsafe[Any](
        // The sweeper sleeps for at most `sweepIntervalMs`: a delay no test here names.
        TaskSettings(enabled = true, sweepIntervalMs = 250L, cancelGraceMs = graceMs),
        // Not `UUID.randomUUID`: it needs `SecureRandom`, which Scala.js does not link.
        ZIO.succeed(s"task-${ids.incrementAndGet()}"),
        stage => ZIO.when(stage == "settled")(settled.update(_ + 1)).unit,
        clock
      )
    yield Fixture(tm, clock, settled, statuses)

  /** A body that cannot be interrupted until the test lets it return `value`. */
  private def synchronousBody(
      started: Promise[Nothing, Unit],
      release: Promise[Nothing, Unit],
      value: String
  ): ZIO[Any, Throwable, Any] =
    (started.succeed(()) *> release.await.as(value)).uninterruptible

  private def cancelledError(exit: Exit[Throwable, Any]): Boolean =
    exit match
      case Exit.Failure(cause) => cause.failureOption.exists(_.isInstanceOf[TaskCancelledError])
      case Exit.Success(_) => false

  test("a body that does not stop: the cancel is answered when the grace has passed, not before") {
    run(
      for
        f <- fixture(GraceMs)
        started <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        created <- f.tm.createSized(
          TaskScope.session("s1"),
          None,
          requestBytes = 1024L,
          synchronousBody(started, release, "late"),
          f.onStatus
        )
        id = created.task.taskId
        _ <- started.await
        running <- f.tm.stats
        waiter <- f.tm.result(id, Session).exit.forkDaemon
        cancel <- f.tm.cancel(id, Session).forkDaemon
        _ <- f.clock.awaitSleeping(Duration.fromMillis(GraceMs))
        // One millisecond short of the grace: only a further advance (or the body returning,
        // which this test has not allowed) can complete the cancel.
        _ <- f.clock.advance(Duration.fromMillis(GraceMs - 1L))
        early <- cancel.poll
        stillWorking <- f.tm.get(id, Session)
        _ <- f.clock.advance(1.millis)
        outcome <- cancel.join
        waited <- waiter.join
        shown <- f.tm.get(id, Session)
        after <- f.tm.result(id, Session).exit
        stats <- f.tm.stats
        told <- f.statuses.get
        // The body returns a value long after the task was recorded as cancelled.
        _ <- release.succeed(())
        _ <- f.awaitSettled(2)
        lateShown <- f.tm.get(id, Session)
        lateResult <- f.tm.result(id, Session).exit
        lateTold <- f.statuses.get
        lateStats <- f.tm.stats
        _ <- f.tm.shutdown
      yield
        assert(early.isEmpty, "the cancel was answered before the grace had passed")
        assert(stillWorking.map(_.status).contains(TaskStatus.Working))
        assert(outcome.map(_.status) == Right(TaskStatus.Cancelled))
        assert(cancelledError(waited), s"a parked tasks/result waiter got $waited")
        assert(shown.map(_.status).contains(TaskStatus.Cancelled))
        assert(cancelledError(after))
        // The entry is a stored result like any other: not running, no fiber, no request charge.
        assert(stats.running == 0 && stats.total == 1 && stats.heldFibers == 0)
        assert(running.perPool.values.map(_.bytes).sum == 1024L)
        assert(stats.perPool.values.map(_.bytes).sum == 0L)
        assert(told == List(TaskStatus.Cancelled))
        // What the body returned is dropped: same status, same answer, no second notification.
        assert(lateShown.map(_.status).contains(TaskStatus.Cancelled))
        assert(cancelledError(lateResult), s"the late result replaced the cancellation: $lateResult")
        assert(lateTold == List(TaskStatus.Cancelled))
        assert(lateStats.running == 0 && lateStats.total == 1)
    )
  }

  test("a body that stops when interrupted: the cancel is answered without the clock moving") {
    run(
      for
        f <- fixture(GraceMs)
        started <- Promise.make[Nothing, Unit]
        created <- f.tm.create(
          TaskScope.session("s1"),
          None,
          started.succeed(()) *> ZIO.never,
          f.onStatus
        )
        id = created.task.taskId
        _ <- started.await
        // The clock never moves in this test, so a cancel that waited out its grace would never
        // return; the bound only turns that into a failure with a message.
        outcome <- f.tm
          .cancel(id, Session)
          .timeoutFail(new AssertionError("the cancel waited for the grace"))(60.seconds)
        settled <- f.settled.get
        told <- f.statuses.get
        graceTimers = f.clock.sleeping.count(_ == GraceMs)
        _ <- f.tm.shutdown
      yield
        assert(outcome.map(_.status) == Right(TaskStatus.Cancelled))
        // Recorded once, by the task's own fiber, before the cancel returned.
        assert(settled == 1)
        assert(told == List(TaskStatus.Cancelled))
        assert(graceTimers == 0, "the grace timer outlived the cancel")
    )
  }

  /** One cancel of a task whose body returns half-way through the grace: what the cancel answered,
    * what the store shows, the task's result, every status reported, and how many grace timers are
    * still on the clock.
    */
  private def cancelThenReturn(
      body: (Promise[Nothing, Unit], Promise[Nothing, Unit]) => ZIO[Any, Throwable, Any]
  ): ZIO[
    Any,
    Throwable,
    (Either[String, Task], Option[Task], Exit[Throwable, Any], List[TaskStatus], Int, Int)
  ] =
    for
      f <- fixture(GraceMs)
      started <- Promise.make[Nothing, Unit]
      release <- Promise.make[Nothing, Unit]
      created <- f.tm.create(TaskScope.session("s1"), None, body(started, release), f.onStatus)
      id = created.task.taskId
      _ <- started.await
      cancel <- f.tm.cancel(id, Session).forkDaemon
      _ <- f.clock.awaitSleeping(Duration.fromMillis(GraceMs))
      _ <- f.clock.advance(Duration.fromMillis(GraceMs / 2))
      _ <- release.succeed(())
      outcome <- cancel.join.timeoutFail(new AssertionError("the cancel was not answered"))(
        60.seconds
      )
      shown <- f.tm.get(id, Session)
      result <- f.tm.result(id, Session).exit
      settled <- f.settled.get
      told <- f.statuses.get
      graceTimers = f.clock.sleeping.count(_ == GraceMs)
      _ <- f.tm.shutdown
    yield (outcome, shown, result, told, settled, graceTimers)

  test("the body stops inside the grace: the cancel is answered then, and its timer is gone") {
    // On returning, this body reaches a point where a pending interrupt takes effect, as a
    // synchronous body lifted with `ZIO.attempt` does on the step after it.
    run(
      cancelThenReturn((started, release) =>
        (started.succeed(()) *> release.await).uninterruptible *> ZIO.allowInterrupt.as("unused")
      )
    ).map { (outcome, shown, result, told, settled, graceTimers) =>
      assert(outcome.map(_.status) == Right(TaskStatus.Cancelled))
      assert(shown.map(_.status).contains(TaskStatus.Cancelled))
      assert(cancelledError(result))
      // Recorded once, by the task's own fiber: the cancel had nothing to record.
      assert(settled == 1)
      assert(told == List(TaskStatus.Cancelled))
      assert(graceTimers == 0, "the grace timer outlived the cancel")
    }
  }

  test("the body completes inside the grace: the cancel reports the status the task ended in") {
    // This body's last step is uninterruptible, so it returns its value although it was
    // interrupted while it ran: the task raced the cancel and won. The cancel does not overwrite
    // that. (The first test relies on the same body returning a value: there the grace has passed
    // by then, and it is the guard in `recordTerminal` that keeps the task cancelled.)
    run(cancelThenReturn((started, release) => synchronousBody(started, release, "won"))).map {
      (outcome, shown, result, told, settled, graceTimers) =>
        assert(outcome.map(_.status) == Right(TaskStatus.Completed))
        assert(shown.map(_.status).contains(TaskStatus.Completed))
        assert(result == Exit.succeed("won"))
        assert(settled == 1)
        assert(told == List(TaskStatus.Completed))
        assert(graceTimers == 0, "the grace timer outlived the cancel")
    }
  }

  test("cancelGraceMs = 0: a body that does not stop is recorded cancelled without any wait") {
    run(
      for
        f <- fixture(0L)
        started <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        created <- f.tm.create(
          TaskScope.bearer(None),
          None,
          synchronousBody(started, release, "late"),
          f.onStatus
        )
        id = created.task.taskId
        _ <- started.await
        outcome <- f.tm
          .cancel(id, None)
          .timeoutFail(new AssertionError("the cancel waited"))(60.seconds)
        snapshot <- f.tm.snapshot(id, None)
        _ <- release.succeed(())
        _ <- f.awaitSettled(2)
        late <- f.tm.snapshot(id, None)
        told <- f.statuses.get
        _ <- f.tm.shutdown
      yield
        assert(outcome.map(_.status) == Right(TaskStatus.Cancelled))
        assert(snapshot.map(_.task.status).contains(TaskStatus.Cancelled))
        assert(late.map(_.task.status).contains(TaskStatus.Cancelled))
        assert(late.flatMap(_.outcome).exists(cancelledError))
        assert(told == List(TaskStatus.Cancelled))
    )
  }

  test("a task swept by its TTL while a cancel waits: the cancel is still answered as cancelled") {
    run(
      for
        f <- fixture(GraceMs)
        started <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        created <- f.tm.create(
          TaskScope.session("s1"),
          Some(1_000L),
          synchronousBody(started, release, "late"),
          f.onStatus
        )
        id = created.task.taskId
        _ <- started.await
        cancel <- f.tm.cancel(id, Session).forkDaemon
        _ <- f.clock.awaitSleeping(Duration.fromMillis(GraceMs))
        // Past the TTL (the sweeper removes the entry), then to the end of the grace.
        _ <- f.clock.advance(1.second)
        _ <- Eventually("the sweeper removed the expired task")(f.tm.stats)(_.total == 0)
        _ <- f.clock.advance(Duration.fromMillis(GraceMs))
        outcome <- cancel.join
        gone <- f.tm.get(id, Session)
        // Two settlements: the cancel's record (the entry was gone, so it only answered the
        // result promise) and the body's own.
        _ <- release.succeed(())
        _ <- f.awaitSettled(2)
        stats <- f.tm.stats
        told <- f.statuses.get
        _ <- f.tm.shutdown
      yield
        assert(outcome.map(_.status) == Right(TaskStatus.Cancelled))
        assert(gone.isEmpty)
        assert(stats.total == 0 && stats.running == 0)
        // A task removed by its TTL has no entry left to report a status for: the creator's
        // callback (the legacy `notifications/tasks/status`) is not called, neither by the sweep
        // nor by the cancel or the body that find the entry gone.
        assert(told.isEmpty, s"a swept task reported $told")
    )
  }
