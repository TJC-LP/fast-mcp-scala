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

  test("the status and the stored outcome agree when the body returns as the cancel gives up") {
    // Two writers end one task: the cancel that stopped waiting, and the body returning. Each
    // writes the status and then completes the result promise, as two steps. Here the cancel has
    // written `Cancelled` and is held before its second step while the body returns and runs both
    // of its own. The body lost the status, so it must not decide the outcome either.
    run(
      for
        settled <- Ref.make(0)
        held <- Promise.make[Nothing, Unit]
        gate <- Promise.make[Nothing, Unit]
        first <- Ref.make(true)
        ids = new java.util.concurrent.atomic.AtomicLong
        tm = TaskManager.makeUnsafe[Any](
          TaskSettings(enabled = true, sweepIntervalMs = 250L, cancelGraceMs = 0L),
          ZIO.succeed(s"task-${ids.incrementAndGet()}"),
          {
            case "recorded" =>
              // Only the first writer to get here is held: that is the cancel.
              first.getAndSet(false).flatMap(isFirst => (held.succeed(()) *> gate.await).when(isFirst).unit)
            case "settled" => settled.update(_ + 1)
            case _ => ZIO.unit
          },
          new ManualClock
        )
        started <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        created <- tm.createSized(
          TaskScope.session("s1"),
          None,
          requestBytes = 16L,
          synchronousBody(started, release, "late"),
          _ => ZIO.unit
        )
        id = created.task.taskId
        _ <- started.await
        waiter <- tm.result(id, Session).exit.forkDaemon
        cancel <- tm.cancel(id, Session).forkDaemon
        _ <- held.await
        // The body returns and its fiber runs to the end of its own record.
        _ <- release.succeed(())
        _ <- Eventually("the body's fiber settled")(settled.get)(_ >= 1)
        shown <- tm.get(id, Session)
        early <- waiter.poll
        _ <- gate.succeed(())
        outcome <- cancel.join
        waited <- waiter.join
        _ <- tm.shutdown
      yield
        assert(shown.map(_.status).contains(TaskStatus.Cancelled))
        assert(early.isEmpty, s"the body that lost the status completed the result: $early")
        assert(outcome.map(_.status) == Right(TaskStatus.Cancelled))
        assert(cancelledError(waited), s"status cancelled, but tasks/result got $waited")
    )
  }

  test("a body that outlives its cancellation still counts against the running caps") {
    // One running task per owner. The cancelled task's body has not returned, so it is still
    // work this owner is running: a second task is refused until the body is gone. Otherwise a
    // client could stack up bodies by starting and cancelling synchronous tasks in turn.
    run(
      for
        settled <- Ref.make(0)
        ids = new java.util.concurrent.atomic.AtomicLong
        tm = TaskManager.makeUnsafe[Any](
          TaskSettings(
            enabled = true,
            sweepIntervalMs = 250L,
            cancelGraceMs = 0L,
            maxConcurrentPerSession = 1
          ),
          ZIO.succeed(s"task-${ids.incrementAndGet()}"),
          stage => ZIO.when(stage == "settled")(settled.update(_ + 1)).unit,
          new ManualClock
        )
        started <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        scope = TaskScope.session("s1")
        first <- tm.createSized(scope, None, 16L, synchronousBody(started, release, "late"), _ => ZIO.unit)
        _ <- started.await
        cancelled <- tm.cancel(first.task.taskId, Session)
        lingering <- tm.stats
        refused <- tm.createSized(scope, None, 16L, ZIO.never, _ => ZIO.unit).exit
        // Another owner is not affected by this one's leftover body.
        other <- tm.createSized(TaskScope.session("s2"), None, 16L, ZIO.never, _ => ZIO.unit).exit
        _ <- release.succeed(())
        // Not the "settled" count: the cancel's own record has already raised it. What the next
        // create depends on is the body's fiber having reported its end.
        after <- Eventually("the cancelled body's fiber has ended")(tm.stats)(_.lingering == 0)
        admitted <- tm.createSized(scope, None, 16L, ZIO.never, _ => ZIO.unit).exit
        _ <- tm.shutdown
      yield
        assert(cancelled.map(_.status) == Right(TaskStatus.Cancelled))
        assert(lingering.running == 0 && lingering.lingering == 1, lingering)
        assert(
          refused.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[TaskConcurrencyLimitExceeded]),
          s"a second task was admitted beside a body that is still running: $refused"
        )
        assert(other.isSuccess, other)
        assert(after.lingering == 0, after)
        assert(admitted.isSuccess, admitted)
    )
  }

  test("a body removed by the TTL sweep while it runs counts until it has returned") {
    run(
      for
        settled <- Ref.make(0)
        ids = new java.util.concurrent.atomic.AtomicLong
        clock = new ManualClock
        tm = TaskManager.makeUnsafe[Any](
          TaskSettings(enabled = true, sweepIntervalMs = 250L, maxConcurrentPerSession = 1),
          ZIO.succeed(s"task-${ids.incrementAndGet()}"),
          stage => ZIO.when(stage == "settled")(settled.update(_ + 1)).unit,
          clock
        )
        started <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        scope = TaskScope.session("s1")
        _ <- tm.createSized(scope, Some(1_000L), 16L, synchronousBody(started, release, "late"), _ => ZIO.unit)
        _ <- started.await
        // The sweeper must be parked on its interval before the clock moves, or it would park
        // afterwards, 250 ms past the new reading, and never wake.
        _ <- clock.awaitSleeping(250.millis)
        _ <- clock.advance(2.seconds)
        swept <- Eventually("the expired task was swept")(tm.stats)(_.total == 0)
        refused <- tm.createSized(scope, None, 16L, ZIO.never, _ => ZIO.unit).exit
        _ <- release.succeed(())
        after <- Eventually("the swept body's fiber has ended")(tm.stats)(_.lingering == 0)
        admitted <- tm.createSized(scope, None, 16L, ZIO.never, _ => ZIO.unit).exit
        _ <- tm.shutdown
      yield
        assert(swept.lingering == 1, swept)
        assert(
          refused.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[TaskConcurrencyLimitExceeded]),
          s"a second task was admitted beside a swept body that is still running: $refused"
        )
        assert(after.lingering == 0, after)
        assert(admitted.isSuccess, admitted)
    )
  }

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
