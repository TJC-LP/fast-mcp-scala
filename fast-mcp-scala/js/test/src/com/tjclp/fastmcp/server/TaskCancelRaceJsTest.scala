package com.tjclp.fastmcp.server

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.*

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.core.TaskStatus
import com.tjclp.fastmcp.testkit.Eventually
import com.tjclp.fastmcp.server.manager.{TaskCancelledError, TaskManager, TaskScope}

/** Scala.js twin of the JVM `TaskCancelRaceTest`: a task cancelled before its fiber has run a step
  * still ends `cancelled`, and `tasks/result` completes. On one thread the order is fixed rather
  * than a race: a cancel issued by the fiber that created the task reaches the task fiber first,
  * so before the fix every round here failed.
  */
class TaskCancelRaceJsTest extends AsyncFlatSpec with Matchers:

  override implicit val executionContext: ExecutionContext = ExecutionContext.global

  private def run[A](effect: ZIO[Any, Throwable, A]): Future[A] =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.runToFuture(effect))

  "a cancel right after the create" should "end the task as cancelled" in {
    // `java.util.UUID.randomUUID` does not link on Scala.js; ids only have to be distinct here.
    val ids = new java.util.concurrent.atomic.AtomicInteger(0)
    val tm = TaskManager.makeUnsafe[Any](
      TaskSettings(enabled = true),
      ZIO.succeed(s"task-${ids.incrementAndGet()}")
    )
    val rounds = ZIO.foreach((1 to 25).toList) { round =>
      for
        created <- tm.create(TaskScope.bearer(Some(s"k$round")), None, ZIO.never, _ => ZIO.unit)
        id = created.task.taskId
        _ <- tm.cancel(id, None)
        settled <- Eventually("the task shows as cancelled")(tm.get(id, None))(
          _.forall(_.status == TaskStatus.Cancelled)
        ).option
        result <- tm.result(id, None).either.timeout(Eventually.Deadline)
      yield (settled, result) match
        case (Some(_), Some(Left(_: TaskCancelledError))) => None
        case _ => Some(s"round $round: settled=${settled.isDefined}, result=$result")
    }
    run(rounds.map(_.flatten) <* tm.shutdown).map { wrong =>
      withClue(wrong.take(3).mkString(" | "))(wrong.size shouldBe 0)
    }
  }
