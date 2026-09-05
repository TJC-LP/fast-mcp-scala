package com.tjclp.fastmcp
package server.manager

import zio.{System as _, Task as _, *}

import core.*
import jsonrpc.{McpError, McpErrorCarrier}
import server.TaskSettings

private[fastmcp] final case class TaskSnapshot(
    task: Task,
    outcome: Option[Exit[Throwable, Any]]
)

/** Which store pool a task is charged to. Legacy protocol sessions can be minted freely by any
  * client, so their tasks are counted apart from modern bearer tasks: a legacy-session flood can
  * fill only the legacy pool and can never starve bearer clients (and vice versa).
  */
private[fastmcp] enum PoolKind:
  case Session, Bearer

/** Identity a task is created under.
  *
  * @param sessionId
  *   visibility scope: `Some(sid)` = legacy task visible only to that protocol session; `None` =
  *   modern bearer task visible to bearer-scope callers presenting its id
  * @param ownerKey
  *   accounting bucket for the running / stored caps. Legacy tasks use the session id; bearer tasks
  *   use the transport-supplied client key (`None` = the shared anonymous bucket)
  */
final case class TaskScope(sessionId: Option[String], ownerKey: Option[String]):

  private[fastmcp] def pool: PoolKind =
    if sessionId.isDefined then PoolKind.Session else PoolKind.Bearer

object TaskScope:

  def session(sessionId: String): TaskScope =
    TaskScope(Some(sessionId), Some(s"session:$sessionId"))

  def bearer(clientKey: Option[String]): TaskScope =
    TaskScope(None, clientKey.map(k => s"client:$k"))

/** Internal representation of a task's mutable state. Held inside the [[TaskManager]]'s [[Ref]];
  * never escapes the manager.
  */
private final case class TaskEntry(
    taskId: String,
    sessionId: Option[String],
    ownerKey: Option[String],
    pool: PoolKind,
    createdAtMs: Long,
    lastUpdatedAtMs: Long,
    /** Monotonic ([[TaskManager.monotonicMs]]) twin of `lastUpdatedAtMs`; drives the retention
      * grace and eviction order so a wall-clock step never reorders or un-ages entries.
      */
    lastUpdatedMonoMs: Long,
    ttlMs: Option[Long],
    /** Monotonic deadline; the sweeper compares it against [[TaskManager.monotonicMs]]. */
    expiresAtMonoMs: Long,
    pollIntervalMs: Long,
    status: TaskStatus,
    statusMessage: Option[String],
    fiber: Fiber.Runtime[Throwable, Any],
    result: Promise[Throwable, Any],
    /** Monotonic insertion number; breaks millisecond ties so eviction is FIFO among equals. */
    seq: Long = 0L
):

  def toTask: Task = Task(
    taskId = taskId,
    status = status,
    statusMessage = statusMessage,
    createdAt = TaskTimestamp.fromEpochMillis(createdAtMs),
    lastUpdatedAt = TaskTimestamp.fromEpochMillis(lastUpdatedAtMs),
    ttl = ttlMs,
    pollInterval = Some(pollIntervalMs)
  )

/** Running (non-terminal) and total (terminal included) entry counts. */
private[fastmcp] final case class Counts(running: Int, total: Int)

/** Test-visible view of the store's indices. */
private[fastmcp] final case class TaskStoreStats(
    total: Int,
    running: Int,
    sweeperActive: Boolean,
    perOwner: Map[Option[String], Counts],
    perPool: Map[PoolKind, Counts]
)

/** The task store: entries plus O(1) per-owner and per-pool indices, and the sweeper claim flag.
  * Every mutation goes through these methods so the indices stay consistent with `entries`.
  */
private final case class TaskStore(
    entries: Map[String, TaskEntry],
    owners: Map[Option[String], Counts],
    pools: Map[PoolKind, Counts],
    sweeperActive: Boolean,
    nextSeq: Long
):

  def insert(entry: TaskEntry): TaskStore =
    val e = entry.copy(seq = nextSeq)
    copy(
      entries = entries.updated(e.taskId, e),
      owners = owners.updated(e.ownerKey, bump(owners.get(e.ownerKey), 1, 1)),
      pools = pools.updated(e.pool, bump(pools.get(e.pool), 1, 1)),
      nextSeq = nextSeq + 1
    )

  /** `prev` -> `next` status transition. Decrements `running` iff `prev` was non-terminal. */
  def markTerminal(prev: TaskEntry, next: TaskEntry): TaskStore =
    val dRunning = if prev.status.isTerminal then 0 else -1
    copy(
      entries = entries.updated(next.taskId, next),
      owners = owners.updated(prev.ownerKey, bump(owners.get(prev.ownerKey), dRunning, 0)),
      pools = pools.updated(prev.pool, bump(pools.get(prev.pool), dRunning, 0))
    )

  def remove(es: Iterable[TaskEntry]): TaskStore =
    es.foldLeft(this) { (s, e) =>
      if !s.entries.contains(e.taskId) then s
      else
        val dRunning = if e.status.isTerminal then 0 else -1
        s.copy(
          entries = s.entries.removed(e.taskId),
          owners = drop(s.owners, e.ownerKey, bump(s.owners.get(e.ownerKey), dRunning, -1)),
          pools = drop(s.pools, e.pool, bump(s.pools.get(e.pool), dRunning, -1))
        )
    }

  /** Terminal entries of `pool` whose last update (monotonic) is at or before `cutoff` — the only
    * entries a stored cap may evict.
    */
  private def stale(pool: PoolKind, cutoff: Long): Iterator[TaskEntry] =
    entries.values.iterator.filter(e =>
      e.pool == pool && e.status.isTerminal && e.lastUpdatedMonoMs <= cutoff
    )

  /** Oldest stale entry of `owner` (any owner of `pool` when `owner` is `None`), ordered by
    * (lastUpdatedMonoMs, insertion order).
    */
  def evictable(pool: PoolKind, owner: Option[Option[String]], cutoff: Long): Option[TaskEntry] =
    val candidates = stale(pool, cutoff).filter(e => owner.forall(_ == e.ownerKey))
    if candidates.isEmpty then None
    else Some(candidates.minBy(e => (e.lastUpdatedMonoMs, e.seq)))

  /** Owner holding the most STALE (evictable) entries in `pool`, ties broken lexicographically on
    * the key. Ranking on evictable rather than total entries means an owner whose entries are all
    * running or still inside the retention grace never nominates a victim.
    */
  def largestStaleOwner(pool: PoolKind, cutoff: Long): Option[Option[String]] =
    val totals = stale(pool, cutoff)
      .foldLeft(Map.empty[Option[String], Int])((acc, e) =>
        acc.updated(e.ownerKey, acc.getOrElse(e.ownerKey, 0) + 1)
      )
    if totals.isEmpty then None
    else Some(totals.maxBy { case (key, n) => (n, key.getOrElse("")) }._1)

  def stats: TaskStoreStats =
    val pool = pools.values.foldLeft(Counts(0, 0))((a, c) =>
      Counts(a.running + c.running, a.total + c.total)
    )
    TaskStoreStats(pool.total, pool.running, sweeperActive, owners, pools)

  private def bump(c: Option[Counts], dRunning: Int, dTotal: Int): Counts =
    val base = c.getOrElse(Counts(0, 0))
    Counts(base.running + dRunning, base.total + dTotal)

  private def drop[K](m: Map[K, Counts], k: K, next: Counts): Map[K, Counts] =
    if next.total <= 0 then m.removed(k) else m.updated(k, next)

private object TaskStore:

  val empty: TaskStore =
    TaskStore(Map.empty, Map.empty, Map.empty, sweeperActive = false, nextSeq = 0L)

/** Concurrent index of MCP tasks for a single server.
  *
  * Each task wraps a ZIO effect representing a long-running tool invocation. The manager:
  *
  *   - generates a UUID task ID,
  *   - forks the underlying effect on a daemon fiber so the JSON-RPC response can return
  *     immediately with a [[CreateTaskResult]],
  *   - tracks status transitions (`working → completed | failed | cancelled`) and updates them
  *     atomically when the fiber finishes,
  *   - blocks `tasks/result` waiters via a [[Promise]] until the task reaches a terminal status,
  *   - interrupts the fiber on `tasks/cancel`,
  *   - sweeps TTL-expired entries with ONE lazily started, self-terminating daemon fiber (expiry
  *     interrupts still-running work — an expired task never lingers as an invisible fiber, and N
  *     terminal tasks never hold N sleeping fibers).
  *
  * Bounds. Every task is charged to an owner ([[TaskScope.ownerKey]]) and a pool ([[PoolKind]]).
  * Admission is a single `Ref.modify`: per-owner running cap (`-32602`), per-pool running ceiling
  * (`-32003`), per-owner and per-pool stored caps that evict the oldest terminal entry older than
  * `minResultRetentionMs` and reject with `-32003` when nothing is evictable. The pool cap charges
  * the creating owner's own stale results first, then the owner holding the most stale results — so
  * the flooder pays for its own flood, and an owner whose entries are all running or inside the
  * grace is never a victim. Rejections never mutate the store.
  *
  * Clocks. Wire timestamps (`createdAt`, `lastUpdatedAt`) are wall-clock; expiry, the retention
  * grace and eviction order use the monotonic clock ([[TaskManager.monotonicMs]]) so an NTP step or
  * VM resume neither sweeps running tasks early nor extends retention.
  *
  * Atomicity. `create` runs under `uninterruptibleMask`; an interrupt of the creating fiber (client
  * abort, `notifications/cancelled`) is detected before admission and again after registration and
  * rolls the registration back, so no unregistered entry or parked fiber is ever left behind.
  *
  * Session isolation: legacy tasks created with a `sessionId` are visible only to that initialized
  * session. A `None` owner is a modern bearer task, visible only to bearer-scope (modern) callers
  * that present its high-entropy ID — legacy protocol sessions never see bearer tasks, and
  * bearer-scope callers never see session-bound tasks. Authorization beyond that belongs at the MCP
  * endpoint boundary.
  *
  * @tparam R
  *   the ZIO environment the wrapped effect may require. `tm.create(...)` runs inside the server's
  *   `executionRuntime` (the runtime captured at `runHttp[R]()` entry), so the forked daemon fiber
  *   inherits `R` automatically and discharges it at fiber start.
  */
class TaskManager[R] private[manager] (
    settings: TaskSettings,
    storeRef: Ref[TaskStore],
    newId: UIO[String],
    sweeperRef: Ref[Option[Fiber.Runtime[Nothing, Unit]]],
    /** Test hook invoked at `"forked"` (task fiber parked, nothing registered) and `"registered"`
      * (entry admitted, gate still closed). `_ => ZIO.unit` in production.
      */
    private[fastmcp] val checkpoint: String => UIO[Unit]
):

  private val perOwnerRunning: Int = settings.maxConcurrentPerSession
  private val perOwnerStored: Int = math.max(settings.maxStoredPerOwner, perOwnerRunning)
  private val poolRunning: Int = settings.maxConcurrentTotal
  private val poolStored: Int = math.max(settings.maxStoredTotal, poolRunning)

  /** Create a task wrapping `run`. Returns immediately with a [[CreateTaskResult]]; the underlying
    * effect executes on a background daemon fiber.
    *
    * Fails with [[TaskConcurrencyLimitExceeded]] (`-32602`) when the owner already has
    * `settings.maxConcurrentPerSession` running tasks, and with [[TaskCapacityExceeded]] (`-32003`)
    * when the pool's running ceiling or a stored-entry cap is hit with nothing evictable. Other
    * failures bubble through as `Throwable`.
    *
    * Registration is all-or-nothing with respect to interruption of the calling fiber.
    *
    * @param scope
    *   Visibility scope + accounting owner ([[TaskScope.session]] / [[TaskScope.bearer]]).
    * @param requestedTtlMs
    *   Requestor-supplied TTL in millis. Clamped to `settings.maxTtlMs`; defaulted to
    *   `settings.defaultTtlMs` if `None`.
    * @param run
    *   Effect producing the underlying request result (typed as `Any` because TaskManager is
    *   type-erased on result type — the dispatch layer narrows on retrieval).
    * @param onStatusChange
    *   Callback fired with the updated [[Task]] each time status transitions. Useful for emitting
    *   `notifications/tasks/status`. Errors in the callback are swallowed.
    */
  def create(
      scope: TaskScope,
      requestedTtlMs: Option[Long],
      run: ZIO[R, Throwable, Any],
      onStatusChange: Task => UIO[Unit]
  ): ZIO[R, Throwable, CreateTaskResult] =
    ZIO.uninterruptibleMask { _ =>
      for
        taskId <- newId
        promise <- Promise.make[Throwable, Any]
        start <- Promise.make[Nothing, Boolean]
        nowMs <- ZIO.succeed(System.currentTimeMillis())
        nowMono <- ZIO.succeed(TaskManager.monotonicMs())
        ttlMs = effectiveTtl(requestedTtlMs)
        // acquireReleaseExitWith runs the release uninterruptibly, so the status update + promise
        // completion always happen, even if the fiber is interrupted via tasks/cancel. A closed
        // gate (`false`) means the registration was rolled back: the fiber self-interrupts and its
        // release no-ops against the missing entry.
        // `forkDaemon` inherits the current runtime — in production this is the server's
        // `executionRuntime` captured at `runHttp[R]()` entry, so any `R` the run effect requires
        // is already discharged by the inherited environment.
        wrapped =
          ZIO.acquireReleaseExitWith(ZIO.unit)((_, exit: Exit[Throwable, Any]) =>
            recordTerminal(taskId, exit, promise, onStatusChange)
          )(_ => start.await.flatMap(go => if go then run else ZIO.interrupt))
        // A child forked inside the mask inherits the mask's uninterruptibility; `.interruptible`
        // is mandatory or tasks/cancel and TTL expiry would never land.
        fiber <- wrapped.interruptible.forkDaemon
        result <- registerOrRollback(taskId, scope, ttlMs, nowMs, nowMono, fiber, promise, start)
          .onExit(exit => ZIO.unless(exit.isSuccess)(rollback(taskId) *> start.succeed(false)).unit)
      yield result
    }

  /** Backward-compatible overload: `Some(sid)` = legacy session task, `None` = anonymous bearer
    * task.
    */
  def create(
      sessionId: Option[String],
      requestedTtlMs: Option[Long],
      run: ZIO[R, Throwable, Any],
      onStatusChange: Task => UIO[Unit]
  ): ZIO[R, Throwable, CreateTaskResult] =
    create(
      sessionId.fold(TaskScope.bearer(None))(TaskScope.session),
      requestedTtlMs,
      run,
      onStatusChange
    )

  private def registerOrRollback(
      taskId: String,
      scope: TaskScope,
      ttlMs: Option[Long],
      nowMs: Long,
      nowMono: Long,
      fiber: Fiber.Runtime[Throwable, Any],
      promise: Promise[Throwable, Any],
      start: Promise[Nothing, Boolean]
  ): IO[Throwable, CreateTaskResult] =
    for
      _ <- checkpoint("forked")
      // A pending interrupt recorded while masked (client abort / notifications/cancelled)? Bail
      // out BEFORE admission so the interrupt never costs anyone an evicted result.
      _ <- failIfInterruptPending
      entry = TaskEntry(
        taskId = taskId,
        sessionId = scope.sessionId,
        ownerKey = scope.ownerKey,
        pool = scope.pool,
        createdAtMs = nowMs,
        lastUpdatedAtMs = nowMs,
        lastUpdatedMonoMs = nowMono,
        ttlMs = ttlMs,
        expiresAtMonoMs = nowMono + ttlMs.getOrElse(settings.defaultTtlMs),
        pollIntervalMs = settings.pollIntervalMs,
        status = TaskStatus.Working,
        statusMessage = Some("The operation is now in progress."),
        fiber = fiber,
        result = promise
      )
      admission <- storeRef.modify(admit(entry, nowMono))
      result <- admission match
        case Left(err) => promise.fail(err) *> ZIO.fail(err)
        case Right(startSweeper) =>
          for
            _ <- ZIO.when(startSweeper)(startSweeperFiber)
            _ <- checkpoint("registered")
            // Interrupted between admission and here? The onExit guard in `create` rolls back.
            _ <- failIfInterruptPending
            store <- storeRef.get
            created <-
              // Swept while the create was held (TTL 0 / a slow hook): return no dead handle.
              if !store.entries.contains(taskId) then ZIO.fail(new TaskNotFoundError(taskId))
              else start.succeed(true).as(CreateTaskResult(entry.toTask))
          yield created
    yield result

  /** Interrupts are recorded (not acted upon) while masked; `Descriptor.interrupters` exposes them
    * — the same check `ZIO.allowInterrupt` uses. `ZIO.interrupt` here fails the effect with an
    * interruption cause that takes effect once the mask ends.
    */
  private val failIfInterruptPending: UIO[Unit] =
    ZIO.descriptorWith(d => if d.interrupters.nonEmpty then ZIO.interrupt else ZIO.unit)

  private def rollback(taskId: String): UIO[Unit] =
    storeRef.update(s => s.entries.get(taskId).fold(s)(e => s.remove(List(e))))

  /** Single-`modify` admission. `Right(startSweeper)` when the entry was inserted (after any cap
    * evictions), `Left(err)` when rejected — in which case the store is returned untouched.
    */
  private def admit(entry: TaskEntry, nowMono: Long)(
      s: TaskStore
  ): (Either[Throwable, Boolean], TaskStore) =
    val owner = entry.ownerKey
    val pool = entry.pool
    val oc = s.owners.getOrElse(owner, Counts(0, 0))
    val pc = s.pools.getOrElse(pool, Counts(0, 0))
    val cutoff = nowMono - settings.minResultRetentionMs
    if s.entries.contains(entry.taskId) then
      (Left(new IllegalStateException(s"duplicate task id ${entry.taskId}")), s)
    else if oc.running >= perOwnerRunning then
      // Never echo a derived client key: the session id is the only owner name on the wire.
      (Left(TaskConcurrencyLimitExceeded(entry.sessionId, perOwnerRunning)), s)
    else if pc.running >= poolRunning then (Left(TaskCapacityExceeded("running", poolRunning)), s)
    else
      val ownerEvict =
        if oc.total >= perOwnerStored then s.evictable(pool, Some(owner), cutoff) else None
      if oc.total >= perOwnerStored && ownerEvict.isEmpty then
        (Left(TaskCapacityExceeded("stored-per-owner", perOwnerStored)), s)
      else
        val s1 = s.remove(ownerEvict.toList)
        val p1 = s1.pools.getOrElse(pool, Counts(0, 0))
        // Pool cap: the creator's own stale results pay first, then the owner holding the most
        // stale results. Never the owner with the most entries — a distributed flood of fresh
        // entries must not be able to nominate a legitimate heavy user as the victim.
        val poolEvict =
          if p1.total >= poolStored then
            s1.evictable(pool, Some(owner), cutoff)
              .orElse(
                s1.largestStaleOwner(pool, cutoff)
                  .flatMap(big => s1.evictable(pool, Some(big), cutoff))
              )
          else None
        if p1.total >= poolStored && poolEvict.isEmpty then
          (Left(TaskCapacityExceeded("stored", poolStored)), s)
        else
          val next = s1.remove(poolEvict.toList).insert(entry)
          (Right(!s.sweeperActive), next.copy(sweeperActive = true))

  // ------- sweeper -------

  /** One iteration: drop expired entries (interrupting still-running ones), then sleep to the
    * nearest deadline (capped by `sweepIntervalMs`) and repeat. Releases its claim and exits, in
    * the same `modify` that observes the drain, when the store is empty.
    */
  private def sweepLoop: UIO[Unit] =
    ZIO.succeed(TaskManager.monotonicMs()).flatMap { now =>
      storeRef
        .modify { s =>
          val expired = s.entries.values.filter(_.expiresAtMonoMs <= now).toList
          val rest = s.remove(expired)
          if rest.entries.isEmpty then ((expired, None), rest.copy(sweeperActive = false))
          else ((expired, Some(rest.entries.values.iterator.map(_.expiresAtMonoMs).min)), rest)
        }
        .flatMap { case (expired, next) =>
          // interruptFork: never block the sweeper on a task body; the task's release then no-ops
          // against the removed entry. Parked (never-started) fibers die the same way.
          ZIO.foreachDiscard(expired.filter(!_.status.isTerminal))(_.fiber.interruptFork) *>
            next.fold(ZIO.unit) { deadline =>
              val sleepMs = math.max(1L, math.min(deadline - now, settings.sweepIntervalMs))
              ZIO.sleep(Duration.fromMillis(sleepMs)) *> sweepLoop
            }
        }
    }

  /** Fork the sweeper. Should the loop ever die with a defect (nothing in its body can fail today),
    * the claim is released and logged so the next `create` starts a fresh sweeper instead of TTL
    * expiry silently stopping for the life of the server. Interruption (shutdown) keeps the claim:
    * `shutdown` drains the store right after.
    */
  private def startSweeperFiber: UIO[Unit] =
    sweepLoop
      .catchAllCause { cause =>
        ZIO
          .unless(cause.isInterruptedOnly)(
            ZIO.logErrorCause("Task sweeper died; releasing its claim", cause) *>
              storeRef.update(_.copy(sweeperActive = false))
          )
          .unit
      }
      .interruptible
      .forkDaemon
      .flatMap(f => sweeperRef.set(Some(f)))

  // ------- queries -------

  /** Look up a task by ID, returning `None` if the ID is unknown or owned by a different session
    * than the caller's.
    */
  def get(taskId: String, sessionId: Option[String]): UIO[Option[Task]] =
    storeRef.get.map(s => visible(s.entries, taskId, sessionId).map(_.toTask))

  /** Non-blocking detailed view used by the 2026 Tasks extension. */
  private[fastmcp] def snapshot(
      taskId: String,
      sessionId: Option[String]
  ): UIO[Option[TaskSnapshot]] =
    storeRef.get.flatMap { s =>
      visible(s.entries, taskId, sessionId) match
        case None => ZIO.none
        case Some(entry) if entry.status.isTerminal =>
          entry.result.await.exit.map(outcome => Some(TaskSnapshot(entry.toTask, Some(outcome))))
        case Some(entry) => ZIO.some(TaskSnapshot(entry.toTask, None))
    }

  /** List all tasks visible to `sessionId`, in `createdAt` descending order.
    *
    * Pagination is best-effort: we return everything in a single page (no cursor returned) for the
    * MVP. Will revisit when we have real-world pagination needs.
    */
  def list(sessionId: Option[String], cursor: Option[String]): UIO[ListTasksResult] =
    val _ = cursor // unused in MVP; opaque cursor support deferred
    storeRef.get.map { s =>
      val visibleTasks = s.entries.values
        .filter(e => sessionVisible(e, sessionId))
        .toList
        .sortBy(-_.createdAtMs)
        .map(_.toTask)
      ListTasksResult(visibleTasks, nextCursor = None)
    }

  /** Cancel a task. Returns `Right(Task)` on successful cancel; `Left(reason)` for unknown task,
    * different session, or already-terminal task.
    *
    * Interrupting the fiber awaits its actual completion, so on return the task's status is
    * `Cancelled` (or whatever terminal state it raced to).
    */
  def cancel(taskId: String, sessionId: Option[String]): UIO[Either[String, Task]] =
    storeRef.get.flatMap { s =>
      visible(s.entries, taskId, sessionId) match
        case None =>
          ZIO.succeed(Left("Task not found"))
        case Some(entry) if entry.status.isTerminal =>
          ZIO.succeed(
            Left(
              s"Cannot cancel task: already in terminal status '${entry.status.toString.toLowerCase}'"
            )
          )
        case Some(entry) =>
          // Interrupt the fiber. `Fiber.interrupt` awaits the fiber's exit, including its
          // release block (recordTerminal). After that, the store should reflect Cancelled, but
          // under heavy concurrent test load the read can occasionally observe the pre-update
          // entry. Synthesize a Cancelled view if so — the fiber is definitionally cancelled at
          // this point regardless of what the Ref happens to show.
          entry.fiber.interrupt *> storeRef.get.map { latest =>
            latest.entries.get(taskId) match
              case Some(e) if e.status.isTerminal => Right(e.toTask)
              case _ =>
                Right(
                  entry.toTask.copy(
                    status = TaskStatus.Cancelled,
                    statusMessage = Some("The task was cancelled by request."),
                    lastUpdatedAt = TaskTimestamp.now()
                  )
                )
          }
    }

  /** Block until the task reaches a terminal status, then return the underlying request's result
    * (or fail with the underlying request's error).
    *
    * For unknown / cross-session tasks, fails with [[TaskNotFoundError]] (JSON-RPC `-32602`, same
    * as `tasks/get`).
    */
  def result(taskId: String, sessionId: Option[String]): IO[Throwable, Any] =
    storeRef.get.flatMap { s =>
      visible(s.entries, taskId, sessionId) match
        case None => ZIO.fail(new TaskNotFoundError(taskId))
        case Some(entry) => entry.result.await
    }

  // ------- lifecycle -------

  /** Drop every task charged to `scope.ownerKey`, interrupting the running ones. Registered by the
    * legacy task path as a session finalizer, so a `DELETE`d or idle-evicted session no longer pins
    * invisible tasks until their TTL. The anonymous bearer bucket (`ownerKey = None`) is shared and
    * is never bulk-released.
    */
  def releaseScope(scope: TaskScope): UIO[Unit] =
    scope.ownerKey match
      case None => ZIO.unit
      case Some(key) =>
        storeRef
          .modify { s =>
            val mine = s.entries.values.filter(_.ownerKey.contains(key)).toList
            (mine, s.remove(mine))
          }
          .flatMap(mine =>
            ZIO.foreachDiscard(mine.filter(!_.status.isTerminal))(_.fiber.interruptFork)
          )

  /** Stop the sweeper (awaiting its exit), then drain the store, interrupting running tasks. For
    * embedders that stop a server without exiting the process, and for test cleanup — the sweeper
    * is a daemon fiber and otherwise exits on its own once the store drains.
    *
    * Order matters against a racing `create`: the drain resets `sweeperActive`, so a create that
    * lands afterwards starts a fresh sweeper, and one that landed in between is drained here.
    * Draining first could leave `sweeperActive = true` with no live sweeper.
    */
  def shutdown: UIO[Unit] =
    for
      sweeper <- sweeperRef.getAndSet(None)
      _ <- ZIO.foreachDiscard(sweeper)(_.interrupt)
      drained <- storeRef.getAndSet(TaskStore.empty)
      _ <- ZIO.foreachDiscard(drained.entries.values.filter(!_.status.isTerminal))(
        _.fiber.interruptFork
      )
    yield ()

  /** Resolve the bucket key of a modern bearer task per `settings.ownerKey`. */
  def ownerKeyFor(ctx: TaskOwnerContext): IO[Throwable, Option[String]] =
    settings.ownerKey match
      case TaskOwnerKey.Transport => ZIO.succeed(ctx.transportClientKey)
      case TaskOwnerKey.Custom(f) => ZIO.attempt(f(ctx))

  private[fastmcp] def stats: UIO[TaskStoreStats] = storeRef.get.map(_.stats)

  // ------- internals -------

  private def sessionVisible(entry: TaskEntry, sessionId: Option[String]): Boolean =
    (entry.sessionId, sessionId) match
      case (None, None) =>
        true // bearer task, bearer-scope (modern) caller: the id is the credential
      case (None, Some(_)) => false // bearer task hidden from legacy protocol sessions
      case (Some(_), None) => false // session-bound task hidden from bearer-scope callers
      case (Some(a), Some(b)) => a == b

  private def visible(
      all: Map[String, TaskEntry],
      taskId: String,
      sessionId: Option[String]
  ): Option[TaskEntry] =
    all.get(taskId).filter(sessionVisible(_, sessionId))

  private def effectiveTtl(requested: Option[Long]): Option[Long] =
    val raw = requested.getOrElse(settings.defaultTtlMs)
    Some(math.min(math.max(raw, 0L), settings.maxTtlMs))

  private def recordTerminal(
      taskId: String,
      exit: Exit[Throwable, Any],
      promise: Promise[Throwable, Any],
      onStatusChange: Task => UIO[Unit]
  ): UIO[Unit] =
    val (status, message) = exit match
      case Exit.Success(_) =>
        (TaskStatus.Completed, Some("The operation completed successfully."))
      case Exit.Failure(cause) if cause.isInterruptedOnly =>
        (TaskStatus.Cancelled, Some("The task was cancelled by request."))
      case Exit.Failure(cause) =>
        val firstFailureMsg = cause.failureOption.flatMap(t => Option(t.getMessage))
        (TaskStatus.Failed, firstFailureMsg.filter(_.nonEmpty))
    val nowMs = System.currentTimeMillis()
    val nowMono = TaskManager.monotonicMs()
    for
      updated <- storeRef.modify { s =>
        s.entries.get(taskId) match
          case None => (None, s)
          case Some(entry) =>
            val next = entry.copy(
              status = status,
              statusMessage = message,
              lastUpdatedAtMs = nowMs,
              lastUpdatedMonoMs = nowMono
            )
            (Some(next), s.markTerminal(entry, next))
      }
      _ <- exit match
        case Exit.Success(value) => promise.succeed(value).unit
        case Exit.Failure(cause) => promise.failCause(cause).unit
      _ <- ZIO.foreachDiscard(updated)(e => onStatusChange(e.toTask).ignore)
    yield ()

/** Raised by [[TaskManager.create]] when the calling owner (legacy session, or modern client
  * bucket) is already running `settings.maxConcurrentPerSession` tasks. Dispatch layers catch this
  * and surface a spec-compliant JSON-RPC error. `sessionId` is `None` for bearer tasks — a derived
  * client key is never echoed on the wire.
  */
final class TaskConcurrencyLimitExceeded(
    val sessionId: Option[String],
    val limit: Int
) extends RuntimeException(
      s"Task concurrency limit exceeded for session ${sessionId.getOrElse("(none)")}: limit=$limit"
    )
    with McpErrorCarrier:
  // Cap exceeded is a request-rejection (-32602), not a generic server error.
  def toMcpError: McpError = McpError.invalidParams(getMessage)

/** Raised by [[TaskManager.create]] when server-side capacity is exhausted: the pool's running
  * ceiling (`kind = "running"`), or an owner / pool stored-entry cap with nothing evictable
  * (`"stored-per-owner"` / `"stored"`). Maps to `-32003` — a server condition, not a request fault,
  * so clients may retry later.
  */
final class TaskCapacityExceeded(val kind: String, val limit: Int)
    extends RuntimeException(s"Task capacity exceeded ($kind, limit $limit)")
    with McpErrorCarrier:
  def toMcpError: McpError = McpError(ErrorCodes.CapacityExceeded, getMessage)

/** Unknown / cross-session task id from `tasks/result`. Maps to JSON-RPC `-32602` so it agrees with
  * `tasks/get`'s "Unknown task" error.
  */
final class TaskNotFoundError(val taskId: String)
    extends RuntimeException(s"Unknown task: $taskId")
    with McpErrorCarrier:
  def toMcpError: McpError = McpError.invalidParams(getMessage)

object TaskManager:

  private val noCheckpoint: String => UIO[Unit] = _ => ZIO.unit

  /** Monotonic milliseconds (`System.nanoTime`, available on JVM, Scala.js and Scala Native). Only
    * differences are meaningful; never persisted or put on the wire.
    */
  private[manager] def monotonicMs(): Long = System.nanoTime() / 1_000_000L

  /** Allocate a new manager with empty state. `newId` supplies task ids; production passes the
    * platform backend's CSPRNG (`TransportBackend.randomId()`) because task ids are bearer handles
    * and shared code has no `SecureRandom`.
    */
  def make[R](settings: TaskSettings, newId: UIO[String]): UIO[TaskManager[R]] =
    for
      store <- Ref.make(TaskStore.empty)
      sweeper <- Ref.make(Option.empty[Fiber.Runtime[Nothing, Unit]])
    yield new TaskManager[R](settings, store, newId, sweeper, noCheckpoint)

  /** Synchronous constructor for non-ZIO call sites (tests). Internally identical to [[make]]. */
  def makeUnsafe[R](settings: TaskSettings, newId: UIO[String]): TaskManager[R] =
    makeUnsafe(settings, newId, noCheckpoint)

  /** Test constructor with a checkpoint hook (see [[TaskManager.checkpoint]]). */
  private[fastmcp] def makeUnsafe[R](
      settings: TaskSettings,
      newId: UIO[String],
      checkpoint: String => UIO[Unit]
  ): TaskManager[R] =
    Unsafe.unsafe { implicit unsafe =>
      new TaskManager[R](
        settings,
        Ref.unsafe.make(TaskStore.empty),
        newId,
        Ref.unsafe.make(Option.empty[Fiber.Runtime[Nothing, Unit]]),
        checkpoint
      )
    }
