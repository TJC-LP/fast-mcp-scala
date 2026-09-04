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
      defaultTtl: Long = 3_600_000L,
      maxConcurrentTotal: Int = 1024,
      maxStoredPerOwner: Int = 256,
      maxStoredTotal: Int = 4096,
      minResultRetentionMs: Long = 30_000L,
      sweepIntervalMs: Long = 1_000L,
      checkpoint: String => UIO[Unit] = _ => ZIO.unit
  ): TaskManager[Any] =
    TaskManager.makeUnsafe[Any](
      TaskSettings(
        enabled = true,
        defaultTtlMs = defaultTtl,
        maxTtlMs = 86_400_000L,
        pollIntervalMs = 5_000L,
        maxConcurrentPerSession = maxConcurrent,
        maxConcurrentTotal = maxConcurrentTotal,
        maxStoredPerOwner = maxStoredPerOwner,
        maxStoredTotal = maxStoredTotal,
        minResultRetentionMs = minResultRetentionMs,
        sweepIntervalMs = sweepIntervalMs
      ),
      newId = ZIO.succeed(java.util.UUID.randomUUID().toString),
      checkpoint = checkpoint
    )

  private def exitOf[A](z: ZIO[Any, Throwable, A]): Exit[Throwable, A] =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(z))

  private def failureOf(exit: Exit[Throwable, ?]): Throwable =
    exit match
      case Exit.Failure(c) => c.failureOption.getOrElse(fail(s"no typed failure in $c"))
      case Exit.Success(_) => fail("expected a failure but the effect succeeded")

  /** A task that runs to completion and returns `value`; the create is fenced by awaiting the
    * result so the entry is terminal when the helper returns.
    */
  private def completed(tm: TaskManager[Any], scope: TaskScope, value: String = "ok"): String =
    val created = runUnsafe(tm.create(scope, None, ZIO.succeed(value), _ => ZIO.unit))
    val _ = runUnsafe(tm.result(created.task.taskId, scope.sessionId))
    created.task.taskId

  private def rootFibers: Int = runUnsafe(Fiber.roots.map(_.size))

  /** Never assert `Fiber.roots` after a fixed sleep: poll until the delta settles. */
  private def awaitRootsSettle(before: Int, tolerance: Int = 5): Unit =
    runUnsafe(
      (ZIO.sleep(25.millis) *> Fiber.roots.map(_.size))
        .repeatUntil(_ - before <= tolerance)
        .timeoutFail(new RuntimeException("root fibers did not settle"))(10.seconds)
        .unit
    )

  private def awaitStats(tm: TaskManager[Any])(p: TaskStoreStats => Boolean): TaskStoreStats =
    runUnsafe(
      (ZIO.sleep(20.millis) *> tm.stats)
        .repeatUntil(p)
        .timeoutFail(new RuntimeException("task store never reached the expected state"))(
          10.seconds
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
    // ZIO's `getOrThrowFiberFailure` wraps the typed error in `FiberFailure`. Run via `.exit`
    // and pattern-match the cause so the test asserts on the underlying type, not the wrapper.
    val exit = Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe
        .run(
          tm.create(sessionId = Some("s1"), requestedTtlMs = None, run = never, _ => ZIO.unit)
        )
    }
    val cause = exit match
      case Exit.Failure(c) => c
      case Exit.Success(_) => fail("Expected concurrency-cap rejection but got success")
    val typed = cause.failureOption match
      case Some(t: TaskConcurrencyLimitExceeded) => t
      case other => fail(s"Expected TaskConcurrencyLimitExceeded but got $other")
    typed.sessionId shouldBe Some("s1")
    typed.limit shouldBe 1
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

  it should "hide bearer tasks from legacy sessions and session tasks from bearer callers" in {
    val tm = newManager()
    val gate = runUnsafe(Promise.make[Nothing, Unit])
    val never: ZIO[Any, Throwable, Any] = gate.await
    val bearer = runUnsafe(
      tm.create(sessionId = None, requestedTtlMs = None, run = never, _ => ZIO.unit)
    )
    val bearerId = bearer.task.taskId
    // Bearer task is invisible to any legacy protocol session...
    runUnsafe(tm.get(bearerId, Some("alice"))) shouldBe None
    runUnsafe(tm.list(Some("alice"), None)).tasks shouldBe Nil
    runUnsafe(tm.cancel(bearerId, Some("alice"))).isLeft shouldBe true
    // ...but stays fully visible to bearer-scope (modern) callers.
    runUnsafe(tm.get(bearerId, None)).isDefined shouldBe true

    val owned = runUnsafe(
      tm.create(sessionId = Some("alice"), requestedTtlMs = None, run = never, _ => ZIO.unit)
    )
    // Mirror case: a session-bound task is invisible to bearer-scope callers.
    runUnsafe(tm.get(owned.task.taskId, None)) shouldBe None
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
    val tm = TaskManager.makeUnsafe[Any](
      TaskSettings(
        enabled = true,
        defaultTtlMs = 1_000L,
        maxTtlMs = 5_000L,
        pollIntervalMs = 100L,
        maxConcurrentPerSession = 4
      ),
      newId = ZIO.succeed(java.util.UUID.randomUUID().toString)
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

  "TTL eviction" should "remove the entry and interrupt still-running work" in {
    val tm = newManager()
    val interrupted = runUnsafe(Ref.make(false))
    val created = runUnsafe(
      tm.create(
        sessionId = Some("evict"),
        requestedTtlMs = Some(150L),
        run = ZIO.never.onInterrupt(interrupted.set(true)),
        onStatusChange = _ => ZIO.unit
      )
    )
    val taskId = created.task.taskId
    runUnsafe(
      (ZIO.sleep(25.millis) *> (tm.get(taskId, Some("evict")).map(_.isEmpty) zip interrupted.get))
        .repeatUntil { case (gone, wasInterrupted) => gone && wasInterrupted }
        .timeoutFail(new RuntimeException("TTL eviction did not remove + interrupt the task"))(
          10.seconds
        )
    )
  }

  it should "leave terminal tasks to age out without interrupting anything" in {
    val tm = newManager()
    val created = runUnsafe(
      tm.create(
        sessionId = Some("done"),
        requestedTtlMs = Some(150L),
        run = ZIO.succeed("ok"),
        onStatusChange = _ => ZIO.unit
      )
    )
    val taskId = created.task.taskId
    // Completed result stays pollable until the TTL sweeps the entry.
    runUnsafe(tm.result(taskId, Some("done"))) shouldBe "ok"
    runUnsafe(
      (ZIO.sleep(25.millis) *> tm.get(taskId, Some("done")).map(_.isEmpty))
        .repeatUntil(identity)
        .timeoutFail(new RuntimeException("TTL eviction did not remove the terminal task"))(
          10.seconds
        )
    )
  }

  // ---------------------------------------------------------------------------------------------
  // F8: bounded store, single sweeper
  // ---------------------------------------------------------------------------------------------

  "stored-entry cap" should "evict the owner's oldest terminal entry" in {
    val tm = newManager(maxConcurrent = 4, maxStoredPerOwner = 4, minResultRetentionMs = 0L)
    try
      val scope = TaskScope.session("loop")
      val ids = (1 to 6).map(i => completed(tm, scope, s"v$i"))
      val stats = runUnsafe(tm.stats)
      stats.total shouldBe 4
      stats.running shouldBe 0
      ids.take(2).foreach(id => runUnsafe(tm.get(id, Some("loop"))) shouldBe None)
      ids.drop(2).foreach(id => runUnsafe(tm.get(id, Some("loop"))).isDefined shouldBe true)
    finally runUnsafe(tm.shutdown)
  }

  it should "bound the store across owners at the pool cap and charge the largest owner" in {
    // Stored caps normalise to >= the running caps, so the running ceilings are lowered too.
    val tm = newManager(maxConcurrent = 8, maxConcurrentTotal = 8, maxStoredPerOwner = 8, maxStoredTotal = 8, minResultRetentionMs = 0L)
    try
      val a = TaskScope.session("A")
      val b = TaskScope.session("B")
      val c = TaskScope.session("C")
      val aIds = (1 to 6).map(_ => completed(tm, a))
      val bIds = (1 to 2).map(_ => completed(tm, b))
      runUnsafe(tm.stats).total shouldBe 8
      val cId = completed(tm, c)
      val stats = runUnsafe(tm.stats)
      stats.total shouldBe 8
      stats.perOwner(a.ownerKey).total shouldBe 5
      stats.perOwner(b.ownerKey).total shouldBe 2
      stats.perOwner(c.ownerKey).total shouldBe 1
      // A's OLDEST completed task paid for C's admission; B is intact.
      runUnsafe(tm.get(aIds.head, Some("A"))) shouldBe None
      aIds.tail.foreach(id => runUnsafe(tm.get(id, Some("A"))).isDefined shouldBe true)
      bIds.foreach(id => runUnsafe(tm.get(id, Some("B"))).isDefined shouldBe true)
      runUnsafe(tm.get(cId, Some("C"))).isDefined shouldBe true
    finally runUnsafe(tm.shutdown)
  }

  it should "reject when nothing is evictable" in {
    val tm = newManager(maxConcurrent = 2, maxConcurrentTotal = 2, maxStoredTotal = 2, minResultRetentionMs = 60_000L)
    val gate = runUnsafe(Promise.make[Nothing, Unit])
    try
      val never: ZIO[Any, Throwable, Any] = gate.await
      runUnsafe(tm.create(TaskScope.session("s1"), None, never, _ => ZIO.unit))
      runUnsafe(tm.create(TaskScope.session("s2"), None, never, _ => ZIO.unit))
      // Two running entries: the running ceiling is reported first (stored is normalised >= it).
      val err = failureOf(exitOf(tm.create(TaskScope.session("s3"), None, never, _ => ZIO.unit)))
      err match
        case e: TaskCapacityExceeded =>
          e.kind shouldBe "running"
          e.limit shouldBe 2
          e.getMessage shouldBe "Task capacity exceeded (running, limit 2)"
          e.toMcpError.code shouldBe ErrorCodes.CapacityExceeded
          e.toMcpError.code shouldBe -32003
        case other => fail(s"expected TaskCapacityExceeded but got $other")
      runUnsafe(tm.stats).total shouldBe 2
    finally
      runUnsafe(gate.succeed(()))
      runUnsafe(tm.shutdown)

    // Stored cap with a completed-but-young entry: rejected as "stored", store unchanged.
    val tm2 = newManager(maxConcurrent = 3, maxConcurrentTotal = 3, maxStoredPerOwner = 3, maxStoredTotal = 3, minResultRetentionMs = 60_000L)
    val gate2 = runUnsafe(Promise.make[Nothing, Unit])
    try
      val never: ZIO[Any, Throwable, Any] = gate2.await
      val done = completed(tm2, TaskScope.session("s1"))
      runUnsafe(tm2.create(TaskScope.session("s2"), None, never, _ => ZIO.unit))
      runUnsafe(tm2.create(TaskScope.session("s3"), None, never, _ => ZIO.unit))
      val before = runUnsafe(tm2.stats)
      val err = failureOf(exitOf(tm2.create(TaskScope.session("s4"), None, never, _ => ZIO.unit)))
      err match
        case e: TaskCapacityExceeded =>
          e.kind shouldBe "stored"
          e.limit shouldBe 3
        case other => fail(s"expected TaskCapacityExceeded but got $other")
      runUnsafe(tm2.stats) shouldBe before
      runUnsafe(tm2.result(done, Some("s1"))) shouldBe "ok"
    finally
      runUnsafe(gate2.succeed(()))
      runUnsafe(tm2.shutdown)
  }

  it should "map TaskCapacityExceeded to -32003 through McpError.fromThrowable" in {
    val mapped = com.tjclp.fastmcp.jsonrpc.McpError.fromThrowable(TaskCapacityExceeded("stored", 7))
    mapped.code shouldBe -32003
    mapped.message shouldBe "Task capacity exceeded (stored, limit 7)"
  }

  "retention grace" should "protect a completed-but-unfetched result from a flooding co-owner" in {
    val tm = newManager(maxConcurrent = 4, maxStoredPerOwner = 4, minResultRetentionMs = 60_000L)
    try
      val anon = TaskScope.bearer(None)
      val victim = runUnsafe(tm.create(anon, None, ZIO.succeed("precious"), _ => ZIO.unit))
      // Let the victim complete, but do NOT collect its result.
      val _ = awaitStats(tm)(_.running == 0)
      (1 to 3).foreach(_ => completed(tm, anon))
      val err = failureOf(exitOf(tm.create(anon, None, ZIO.succeed("flood"), _ => ZIO.unit)))
      err match
        case e: TaskCapacityExceeded => e.kind shouldBe "stored-per-owner"
        case other => fail(s"expected TaskCapacityExceeded but got $other")
      runUnsafe(tm.result(victim.task.taskId, None)) shouldBe "precious"
      runUnsafe(tm.stats).total shouldBe 4
    finally runUnsafe(tm.shutdown)
  }

  "sweeper" should "leave at most one sweeper fiber in Fiber.roots for many terminal tasks" in {
    val tm = newManager()
    val before = rootFibers
    try
      val scope = TaskScope.session("many")
      val ids = (1 to 50).map(_ =>
        runUnsafe(tm.create(scope, Some(60_000L), ZIO.succeed("fast"), _ => ZIO.unit)).task.taskId
      )
      ids.foreach(id => runUnsafe(tm.result(id, Some("many"))) shouldBe "fast")
      val stats = runUnsafe(tm.stats)
      stats.total shouldBe 50
      stats.running shouldBe 0
      stats.sweeperActive shouldBe true
      awaitRootsSettle(before)
    finally runUnsafe(tm.shutdown)
  }

  it should "exit when the store drains and restart on the next create" in {
    val tm = newManager(sweepIntervalMs = 50L)
    val before = rootFibers
    try
      val scope = TaskScope.session("drain")
      (1 to 3).foreach(_ =>
        runUnsafe(tm.create(scope, Some(50L), ZIO.succeed("x"), _ => ZIO.unit))
      )
      runUnsafe(tm.stats).sweeperActive shouldBe true
      val drained = awaitStats(tm)(s => s.total == 0 && !s.sweeperActive)
      drained.perOwner shouldBe empty
      drained.perPool shouldBe empty
      awaitRootsSettle(before)
      runUnsafe(tm.create(scope, Some(60_000L), ZIO.succeed("y"), _ => ZIO.unit))
      runUnsafe(tm.stats).sweeperActive shouldBe true
    finally runUnsafe(tm.shutdown)
    awaitRootsSettle(before)
  }

  "shutdown" should "drain the store, interrupt running work and stop the sweeper" in {
    val tm = newManager()
    val before = rootFibers
    val interrupted = runUnsafe(Ref.make(false))
    runUnsafe(
      tm.create(TaskScope.session("s"), None, ZIO.never.onInterrupt(interrupted.set(true)), _ => ZIO.unit)
    )
    completed(tm, TaskScope.bearer(Some("k")))
    runUnsafe(tm.shutdown)
    val stats = runUnsafe(tm.stats)
    stats.total shouldBe 0
    stats.running shouldBe 0
    stats.sweeperActive shouldBe false
    runUnsafe(
      (ZIO.sleep(10.millis) *> interrupted.get)
        .repeatUntil(identity)
        .timeoutFail(new RuntimeException("running task was not interrupted"))(10.seconds)
    )
    awaitRootsSettle(before)
  }

  "releaseScope" should "remove and interrupt a session's tasks, leaving other owners alone" in {
    val tm = newManager()
    val interrupted = runUnsafe(Ref.make(false))
    try
      val mine = TaskScope.session("mine")
      val running = runUnsafe(
        tm.create(mine, None, ZIO.never.onInterrupt(interrupted.set(true)), _ => ZIO.unit)
      )
      val doneId = completed(tm, mine)
      val otherId = completed(tm, TaskScope.session("other"))
      val anonId = completed(tm, TaskScope.bearer(None))
      runUnsafe(tm.releaseScope(mine))
      runUnsafe(tm.get(running.task.taskId, Some("mine"))) shouldBe None
      runUnsafe(tm.get(doneId, Some("mine"))) shouldBe None
      runUnsafe(tm.get(otherId, Some("other"))).isDefined shouldBe true
      runUnsafe(tm.get(anonId, None)).isDefined shouldBe true
      runUnsafe(
        (ZIO.sleep(10.millis) *> interrupted.get)
          .repeatUntil(identity)
          .timeoutFail(new RuntimeException("released task was not interrupted"))(10.seconds)
      )
      val stats = runUnsafe(tm.stats)
      stats.total shouldBe 2
      stats.running shouldBe 0
      stats.perOwner.contains(mine.ownerKey) shouldBe false
      // The shared anonymous bucket is never bulk-released.
      runUnsafe(tm.releaseScope(TaskScope.bearer(None)))
      runUnsafe(tm.get(anonId, None)).isDefined shouldBe true
    finally runUnsafe(tm.shutdown)
  }

  // ---------------------------------------------------------------------------------------------
  // F9: per-client buckets and per-pool ceilings
  // ---------------------------------------------------------------------------------------------

  "bearer tasks" should "bucket per client key: A at cap, B and anonymous still create" in {
    val tm = newManager(maxConcurrent = 3)
    val gate = runUnsafe(Promise.make[Nothing, Unit])
    try
      val never: ZIO[Any, Throwable, Any] = gate.await
      val a = TaskScope.bearer(Some("A"))
      (1 to 3).foreach(_ => runUnsafe(tm.create(a, None, never, _ => ZIO.unit)))
      runUnsafe(tm.create(TaskScope.bearer(Some("B")), None, never, _ => ZIO.unit))
      runUnsafe(tm.create(TaskScope.bearer(None), None, never, _ => ZIO.unit))
      val err = failureOf(exitOf(tm.create(a, None, never, _ => ZIO.unit)))
      err match
        case e: TaskConcurrencyLimitExceeded =>
          e.sessionId shouldBe None
          e.limit shouldBe 3
          e.getMessage should include("(none)")
          e.getMessage should not include "client:A"
          e.getMessage should not include "A:"
        case other => fail(s"expected TaskConcurrencyLimitExceeded but got $other")
      val stats = runUnsafe(tm.stats)
      stats.perOwner(Some("client:A")).running shouldBe 3
      stats.perOwner(Some("client:B")).running shouldBe 1
      stats.perOwner(None).running shouldBe 1
      stats.perPool(PoolKind.Bearer).running shouldBe 5
    finally
      runUnsafe(gate.succeed(()))
      runUnsafe(tm.shutdown)
  }

  "pool running ceiling" should "be distinct from and larger than the per-owner cap" in {
    val tm = newManager(maxConcurrent = 2, maxConcurrentTotal = 3)
    val gate = runUnsafe(Promise.make[Nothing, Unit])
    try
      val never: ZIO[Any, Throwable, Any] = gate.await
      runUnsafe(tm.create(TaskScope.bearer(Some("A")), None, never, _ => ZIO.unit))
      runUnsafe(tm.create(TaskScope.bearer(Some("A")), None, never, _ => ZIO.unit))
      runUnsafe(tm.create(TaskScope.bearer(Some("B")), None, never, _ => ZIO.unit))
      val err = failureOf(exitOf(tm.create(TaskScope.bearer(Some("C")), None, never, _ => ZIO.unit)))
      err match
        case e: TaskCapacityExceeded =>
          e.kind shouldBe "running"
          e.limit shouldBe 3
        case other => fail(s"expected TaskCapacityExceeded but got $other")
      // The legacy-session pool is counted separately.
      runUnsafe(tm.create(TaskScope.session("s1"), None, never, _ => ZIO.unit))
      val stats = runUnsafe(tm.stats)
      stats.perPool(PoolKind.Bearer).running shouldBe 3
      stats.perPool(PoolKind.Session).running shouldBe 1
    finally
      runUnsafe(gate.succeed(()))
      runUnsafe(tm.shutdown)
  }

  it should "not let a legacy-session flood block bearer tasks" in {
    val tm = newManager(maxConcurrent = 1, maxConcurrentTotal = 2)
    val gate = runUnsafe(Promise.make[Nothing, Unit])
    try
      val never: ZIO[Any, Throwable, Any] = gate.await
      runUnsafe(tm.create(TaskScope.session("s1"), None, never, _ => ZIO.unit))
      runUnsafe(tm.create(TaskScope.session("s2"), None, never, _ => ZIO.unit))
      failureOf(exitOf(tm.create(TaskScope.session("s3"), None, never, _ => ZIO.unit))) shouldBe a[
        TaskCapacityExceeded
      ]
      runUnsafe(tm.create(TaskScope.bearer(Some("modern")), None, never, _ => ZIO.unit))
      runUnsafe(tm.stats).perPool(PoolKind.Bearer).running shouldBe 1
    finally
      runUnsafe(gate.succeed(()))
      runUnsafe(tm.shutdown)
  }

  "ownerKeyFor" should "follow the operator policy" in {
    val ctx = TaskOwnerContext(Some("10.0.0.1"), None, Map.empty)
    val transport = newManager()
    runUnsafe(transport.ownerKeyFor(ctx)) shouldBe Some("10.0.0.1")
    runUnsafe(transport.ownerKeyFor(ctx.copy(transportClientKey = None))) shouldBe None

    def custom(f: TaskOwnerContext => Option[String]): TaskManager[Any] =
      TaskManager.makeUnsafe[Any](
        TaskSettings(enabled = true, ownerKey = TaskOwnerKey.Custom(f)),
        ZIO.succeed(java.util.UUID.randomUUID().toString)
      )
    runUnsafe(custom(c => c.clientInfo.map(_.name)).ownerKeyFor(ctx)) shouldBe None
    val boom = exitOf(custom(_ => throw new IllegalStateException("no principal")).ownerKeyFor(ctx))
    failureOf(boom).getMessage shouldBe "no principal"
  }

  // ---------------------------------------------------------------------------------------------
  // F11: create is atomic under interruption
  // ---------------------------------------------------------------------------------------------

  "create" should "be atomic under interruption at every checkpoint" in {
    for stage <- List("forked", "registered") do
      val latch = runUnsafe(Promise.make[Nothing, Unit])
      val gate = runUnsafe(Promise.make[Nothing, Unit])
      val hook: String => UIO[Unit] = s => if s == stage then latch.succeed(()) *> gate.await else ZIO.unit
      val tm = newManager(checkpoint = hook)
      val before = rootFibers
      val started = runUnsafe(Ref.make(false))
      try
        val exit = runUnsafe(
          for
            fiber <- tm.create(TaskScope.session("atomic"), None, started.set(true) *> ZIO.never, _ => ZIO.unit).fork
            _ <- latch.await
            // The interrupt is RECORDED while the create is masked; opening the gate lets the
            // create observe it at its next check and roll back.
            _ <- fiber.interruptFork
            _ <- gate.succeed(())
            exit <- fiber.await
          yield exit
        )
        withClue(s"stage=$stage exit=$exit: ") {
          exit.isInterrupted shouldBe true
          exit.isSuccess shouldBe false
        }
        val stats = runUnsafe(tm.stats)
        withClue(s"stage=$stage stats=$stats: ") {
          stats.total shouldBe 0
          stats.running shouldBe 0
          stats.perOwner shouldBe empty
        }
        runUnsafe(tm.list(Some("atomic"), None)).tasks shouldBe Nil
        // The parked task fiber must have been torn down and its body never started.
        awaitRootsSettle(before)
        runUnsafe(started.get) shouldBe false
      finally runUnsafe(tm.shutdown)
  }

  it should "survive a 200-iteration interruption race with no residue" in {
    val tm = newManager()
    try
      val outcomes = runUnsafe(
        ZIO.foreach((1 to 200).toList) { i =>
          for
            fiber <- tm.create(TaskScope.session(s"race-${i % 7}"), None, ZIO.unit, _ => ZIO.unit).fork
            _ <- ZIO.succeed(java.util.concurrent.locks.LockSupport.parkNanos((i % 50) * 1_000L))
            _ <- fiber.interruptFork
            exit <- fiber.await
          yield exit
        }
      )
      val registered = outcomes.count(_.isSuccess)
      val rolledBack = outcomes.count(_.isInterrupted)
      withClue(s"registered=$registered rolledBack=$rolledBack: ") {
        (registered + rolledBack) shouldBe 200
      }
      // Every create either registered a task (which then ran to completion) or left nothing. An
      // interrupt landing after the post-registration check (the documented residual) yields an
      // interrupted exit with a fully registered task, so `total` may exceed the success count —
      // but never the attempt count, and never with a non-terminal or index-inconsistent entry.
      val stats = awaitStats(tm)(_.running == 0)
      stats.total should be >= registered
      stats.total should be <= 200
      stats.perPool.get(PoolKind.Session).map(_.total).getOrElse(0) shouldBe stats.total
      stats.perOwner.values.map(_.total).sum shouldBe stats.total
      stats.perOwner.values.forall(_.running == 0) shouldBe true
      val listed = (0 until 7).flatMap(k => runUnsafe(tm.list(Some(s"race-$k"), None)).tasks)
      listed.size shouldBe stats.total
      listed.forall(_.status == TaskStatus.Completed) shouldBe true
    finally runUnsafe(tm.shutdown)
  }

  it should "still sweep an entry whose create is held past its TTL and stop counting it" in {
    val hook: String => UIO[Unit] = s => if s == "registered" then ZIO.sleep(400.millis) else ZIO.unit
    val tm = newManager(maxConcurrent = 1, sweepIntervalMs = 50L, checkpoint = hook)
    val before = rootFibers
    try
      // forkDaemon: a plain fork would be interrupted when runUnsafe's parent fiber completes.
      val fiber = runUnsafe(
        tm.create(TaskScope.session("held"), Some(100L), ZIO.never, _ => ZIO.unit).forkDaemon
      )
      // The sweeper is claimed by the insert itself, so the TTL fires while the create is parked.
      runUnsafe(ZIO.sleep(250.millis))
      val held = runUnsafe(tm.stats)
      withClue(s"stats while held: $held: ") {
        held.total shouldBe 0
        held.running shouldBe 0
      }
      // The owner's slot is free again — a concurrent create is admitted despite maxConcurrent = 1.
      val other = runUnsafe(
        tm.create(TaskScope.session("held"), Some(60_000L), ZIO.succeed("free"), _ => ZIO.unit)
      )
      runUnsafe(tm.result(other.task.taskId, Some("held"))) shouldBe "free"
      val exit = runUnsafe(fiber.await)
      failureOf(exit) shouldBe a[TaskNotFoundError]
      awaitRootsSettle(before)
    finally runUnsafe(tm.shutdown)
  }
}
