package com.tjclp.fastmcp.server.manager

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.{Task as _, *}

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.server.TaskSettings

/** Tests for [[TaskManager]] lifecycle, status transitions, session isolation, and cancellation
  * semantics. The state machine implements the spec 2025-11-25 task lifecycle.
  */
class TaskManagerSpec extends AnyFlatSpec with Matchers {

  private def runUnsafe[A](z: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run(z).getOrThrowFiberFailure()
    }

  private def newManager(
      maxConcurrent: Int = 64,
      defaultTtl: Long = 3_600_000L
  ): TaskManager =
    TaskManager.makeUnsafe(
      TaskSettings(
        enabled = true,
        defaultTtlMs = defaultTtl,
        maxTtlMs = 86_400_000L,
        pollIntervalMs = 5_000L,
        maxConcurrentPerSession = maxConcurrent
      )
    )

  "create" should "return a CreateTaskResult in Working status" in {
    val tm = newManager()
    val gate = runUnsafe(Promise.make[Nothing, Unit])
    val never: ZIO[Any, Throwable, Any] = gate.await
    val createResult = runUnsafe(
      tm.create(sessionId = Some("s1"), requestedTtlMs = None, run = never, _ => ZIO.unit)
    )
    createResult.task.status shouldBe TaskStatus.Working
    createResult.task.taskId should not be empty
    createResult.task.pollInterval shouldBe Some(5_000L)
    createResult.task.ttl shouldBe Some(3_600_000L)
    // Cleanup so the daemon fiber doesn't outlive the test.
    val _ = runUnsafe(gate.succeed(()))
  }

  "result" should "block until the task completes and return the value" in {
    val tm = newManager()
    val gate = runUnsafe(Promise.make[Nothing, Unit])
    val effect: ZIO[Any, Throwable, Any] = gate.await.as("done")
    val createResult = runUnsafe(
      tm.create(sessionId = Some("s1"), requestedTtlMs = None, run = effect, _ => ZIO.unit)
    )
    val taskId = createResult.task.taskId
    // Open the gate concurrently with the result wait so the result resolves cleanly.
    val outcome = runUnsafe(
      gate.succeed(()).forkDaemon *> tm.result(taskId, Some("s1"))
    )
    outcome shouldBe "done"
    runUnsafe(tm.get(taskId, Some("s1"))).map(_.status) shouldBe Some(TaskStatus.Completed)
  }

  it should "fail with the original error when the task fails" in {
    val tm = newManager()
    val effect: ZIO[Any, Throwable, Any] = ZIO.fail(new RuntimeException("boom"))
    val createResult = runUnsafe(
      tm.create(sessionId = Some("s1"), requestedTtlMs = None, run = effect, _ => ZIO.unit)
    )
    val ex = intercept[Throwable] {
      val _ = runUnsafe(tm.result(createResult.task.taskId, Some("s1")))
    }
    ex.getMessage should include("boom")
    runUnsafe(tm.get(createResult.task.taskId, Some("s1"))).map(_.status) shouldBe Some(
      TaskStatus.Failed
    )
  }

  it should "record terminal status for immediately completed effects" in {
    val tm = newManager()
    val createResult = runUnsafe(
      tm.create(sessionId = Some("s1"), requestedTtlMs = None, run = ZIO.succeed("done"), _ =>
        ZIO.unit
      )
    )
    val taskId = createResult.task.taskId
    runUnsafe(tm.result(taskId, Some("s1"))) shouldBe "done"
    runUnsafe(tm.get(taskId, Some("s1"))).map(_.status) shouldBe Some(TaskStatus.Completed)
    runUnsafe(tm.list(Some("s1"), None)).tasks.map(t => t.taskId -> t.status) should contain(
      taskId -> TaskStatus.Completed
    )
  }

  it should "enforce maxConcurrentPerSession with a typed error" in {
    val tm = newManager(maxConcurrent = 1)
    val gate = runUnsafe(Promise.make[Nothing, Unit])
    val never: ZIO[Any, Throwable, Any] = gate.await
    val first = runUnsafe(
      tm.create(sessionId = Some("s1"), requestedTtlMs = None, run = never, _ => ZIO.unit)
    )
    val ex = intercept[TaskConcurrencyLimitExceeded] {
      val _ = runUnsafe(
        tm.create(sessionId = Some("s1"), requestedTtlMs = None, run = never, _ => ZIO.unit)
      )
    }
    ex.sessionId shouldBe Some("s1")
    ex.limit shouldBe 1
    runUnsafe(tm.list(Some("s1"), None)).tasks.map(_.taskId) shouldBe List(first.task.taskId)
    val _ = runUnsafe(gate.succeed(()))
  }

  "cancel" should "interrupt the running fiber and transition to Cancelled" in {
    val tm = newManager()
    val gate = runUnsafe(Promise.make[Nothing, Unit])
    val never: ZIO[Any, Throwable, Any] = gate.await
    val createResult = runUnsafe(
      tm.create(sessionId = Some("s1"), requestedTtlMs = None, run = never, _ => ZIO.unit)
    )
    val cancelOutcome = runUnsafe(tm.cancel(createResult.task.taskId, Some("s1")))
    cancelOutcome.map(_.status) shouldBe Right(TaskStatus.Cancelled)
  }

  it should "reject cancel for an already-terminal task" in {
    val tm = newManager()
    val effect: ZIO[Any, Throwable, Any] = ZIO.succeed("done")
    val createResult = runUnsafe(
      tm.create(sessionId = Some("s1"), requestedTtlMs = None, run = effect, _ => ZIO.unit)
    )
    // Wait for completion via result (also doubles as a fence).
    val _ = runUnsafe(tm.result(createResult.task.taskId, Some("s1")))
    val cancelOutcome = runUnsafe(tm.cancel(createResult.task.taskId, Some("s1")))
    cancelOutcome.isLeft shouldBe true
    cancelOutcome.left.toOption.get should include("terminal status")
  }

  it should "reject cancel for an unknown task" in {
    val tm = newManager()
    val cancelOutcome = runUnsafe(tm.cancel("does-not-exist", Some("s1")))
    cancelOutcome shouldBe Left("Task not found")
  }

  "session isolation" should "hide tasks from other sessions" in {
    val tm = newManager()
    val gate = runUnsafe(Promise.make[Nothing, Unit])
    val never: ZIO[Any, Throwable, Any] = gate.await
    val createResult = runUnsafe(
      tm.create(sessionId = Some("alice"), requestedTtlMs = None, run = never, _ => ZIO.unit)
    )
    val taskId = createResult.task.taskId
    runUnsafe(tm.get(taskId, Some("bob"))) shouldBe None
    runUnsafe(tm.cancel(taskId, Some("bob"))).isLeft shouldBe true
    runUnsafe(tm.list(Some("bob"), None)).tasks shouldBe Nil
    // Cleanup
    val _ = runUnsafe(gate.succeed(()))
  }

  "list" should "return all tasks for the calling session" in {
    val tm = newManager()
    val effect: ZIO[Any, Throwable, Any] = ZIO.succeed(())
    val first = runUnsafe(
      tm.create(sessionId = Some("s1"), requestedTtlMs = None, run = effect, _ => ZIO.unit)
    )
    val _ = runUnsafe(tm.result(first.task.taskId, Some("s1")))
    val second = runUnsafe(
      tm.create(sessionId = Some("s1"), requestedTtlMs = None, run = effect, _ => ZIO.unit)
    )
    val _ = runUnsafe(tm.result(second.task.taskId, Some("s1")))

    val listed = runUnsafe(tm.list(Some("s1"), None))
    listed.tasks.map(_.taskId).toSet shouldBe Set(first.task.taskId, second.task.taskId)
    listed.nextCursor shouldBe None
  }

  "TTL clamping" should "respect maxTtlMs" in {
    val tm = TaskManager.makeUnsafe(
      TaskSettings(
        enabled = true,
        defaultTtlMs = 1_000L,
        maxTtlMs = 5_000L,
        pollIntervalMs = 100L,
        maxConcurrentPerSession = 4
      )
    )
    val effect: ZIO[Any, Throwable, Any] = ZIO.succeed(())
    val createResult = runUnsafe(
      tm.create(
        sessionId = Some("s1"),
        requestedTtlMs = Some(1_000_000L), // exceed max
        run = effect,
        _ => ZIO.unit
      )
    )
    createResult.task.ttl shouldBe Some(5_000L)
  }
}
