package com.tjclp.fastmcp.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.core.TaskStatus
import com.tjclp.fastmcp.server.manager.{TaskCancelledError, TaskManager, TaskScope}

/** A task that is interrupted before its fiber has run a single step still records a terminal
  * status.
  *
  * The task fiber used to be forked interruptible as a whole. An interrupt that reached it before
  * it had started (`tasks/cancel`, a session release or the TTL sweep right after the create) ended
  * it before the release that records the terminal status was registered: the entry stayed
  * `working`, counted as running until its TTL, and `tasks/result` never completed. About 2 in 100
  * creates followed at once by a cancel hit it on a loaded machine, and CI hit it once in
  * `TaskMemoryBoundsTest`.
  */
class TaskCancelRaceTest extends AnyFunSuite with Matchers:

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private val Rounds = 1500

  /** One create followed at once by a cancel, in one fiber. `None` when the task ended as it
    * should; otherwise what the store shows instead.
    */
  private def createThenCancel(tm: TaskManager[Any], round: Int): UIO[Option[String]] =
    val scope = TaskScope.bearer(Some(s"k$round"))
    for
      created <- tm.create(scope, None, ZIO.never, _ => ZIO.unit).orDie
      id = created.task.taskId
      _ <- tm.cancel(id, None)
      settled <- (ZIO.sleep(2.millis) *> tm.get(id, None))
        .repeatUntil(_.forall(_.status == TaskStatus.Cancelled))
        .timeout(5.seconds)
      result <- tm.result(id, None).either.timeout(5.seconds)
      stats <- tm.stats
    yield (settled, result) match
      case (Some(_), Some(Left(_: TaskCancelledError))) => None
      case _ =>
        Some(s"round $round: status settled=${settled.isDefined}, result=$result, running=${stats.running}")

  test("a cancel right after the create always ends the task as cancelled") {
    val tm = TaskManager.makeUnsafe[Any](
      TaskSettings(enabled = true),
      ZIO.succeed(java.util.UUID.randomUUID().toString)
    )
    try
      val wrong = runUnsafe(ZIO.foreach((1 to Rounds).toList)(createThenCancel(tm, _))).flatten
      withClue(wrong.take(3).mkString(" | "))(wrong.size shouldBe 0)
      val stats = runUnsafe(tm.stats)
      stats.running shouldBe 0
      stats.heldFibers shouldBe 0
    finally runUnsafe(tm.shutdown)
  }
