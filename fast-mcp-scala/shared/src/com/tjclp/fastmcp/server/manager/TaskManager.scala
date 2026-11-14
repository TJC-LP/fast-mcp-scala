package com.tjclp.fastmcp
package server.manager

import zio.{System as _, Task as _, *}
import zio.json.ast.Json

import core.*
import jsonrpc.{McpError, McpErrorCarrier}
import server.{ServerClock, TaskSettings}

private[fastmcp] final case class TaskSnapshot(
    task: Task,
    outcome: Option[Exit[Throwable, Any]],
    /** The requests a task in `input_required` is waiting on, in the order it asked them; empty in
      * every other status.
      */
    inputRequests: List[(String, Json)] = Nil
)

/** What the body of a task uses to ask its client for input (Tasks extension, SEP-2663). */
private[fastmcp] trait TaskInput:

  /** Put the task in `input_required` showing `requests` (key -> the server-to-client request) on
    * `tasks/get`, and wait until `tasks/update` has delivered a response for every key. The task is
    * `working` again when this returns. Interrupted like the rest of the body by `tasks/cancel`,
    * the TTL sweep and shutdown.
    */
  def await(requests: List[(String, Json)]): IO[Throwable, Map[String, Json]]

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
    /** The task's fiber while it runs, so it can be interrupted; `None` once the task is terminal.
      * A fiber handle keeps every `FiberRef` value the fiber inherited reachable for as long as the
      * handle is — on the 2026-07-28 path that includes the request's whole `RequestContext` — so a
      * stored result must never hold one.
      */
    fiber: Option[Fiber.Runtime[Throwable, Any]],
    result: Promise[Throwable, Any],
    /** Estimated bytes this entry is charged ([[RetainedBytes]]): the request the task keeps while
      * it runs, then the result it stores once terminal.
      */
    bytes: Long,
    /** Monotonic insertion number; breaks millisecond ties so eviction is FIFO among equals. */
    seq: Long = 0L,
    /** `input_required` only: the requests still unanswered, in the order the task asked them. */
    inputRequests: List[(String, Json)] = Nil,
    /** `input_required` only: the responses delivered so far for the current set of requests. */
    inputAnswers: Map[String, Json] = Map.empty,
    /** `input_required` only: completed with [[inputAnswers]] once no request is outstanding. */
    inputWaiter: Option[Promise[Nothing, Map[String, Json]]] = None
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

/** Running (non-terminal) and total (terminal included) entry counts, and the estimated bytes
  * ([[RetainedBytes]]) those entries are charged.
  */
private[fastmcp] final case class Counts(running: Int, total: Int, bytes: Long = 0L)

/** Test-visible view of the store's indices. `heldFibers` counts the entries that still reference
  * their task fiber — only running tasks may.
  */
private[fastmcp] final case class TaskStoreStats(
    total: Int,
    running: Int,
    sweeperActive: Boolean,
    perOwner: Map[Option[String], Counts],
    perPool: Map[PoolKind, Counts],
    heldFibers: Int = 0
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
      owners = owners.updated(e.ownerKey, bump(owners.get(e.ownerKey), 1, 1, e.bytes)),
      pools = pools.updated(e.pool, bump(pools.get(e.pool), 1, 1, e.bytes)),
      nextSeq = nextSeq + 1
    )

  /** `prev` -> `next` status transition. Decrements `running` iff `prev` was non-terminal, and
    * swaps the entry's byte charge (the request it kept for the result it stores).
    */
  def markTerminal(prev: TaskEntry, next: TaskEntry): TaskStore =
    val dRunning = if prev.status.isTerminal then 0 else -1
    val dBytes = next.bytes - prev.bytes
    copy(
      entries = entries.updated(next.taskId, next),
      owners = owners.updated(prev.ownerKey, bump(owners.get(prev.ownerKey), dRunning, 0, dBytes)),
      pools = pools.updated(prev.pool, bump(pools.get(prev.pool), dRunning, 0, dBytes))
    )

  /** Replace a running entry by another running state of the same task (`working` <->
    * `input_required`), adjusting its byte charge. The running and stored counts do not change.
    */
  def recharge(prev: TaskEntry, next: TaskEntry): TaskStore =
    val dBytes = next.bytes - prev.bytes
    copy(
      entries = entries.updated(next.taskId, next),
      owners = owners.updated(prev.ownerKey, bump(owners.get(prev.ownerKey), 0, 0, dBytes)),
      pools = pools.updated(prev.pool, bump(pools.get(prev.pool), 0, 0, dBytes))
    )

  def remove(es: Iterable[TaskEntry]): TaskStore =
    es.foldLeft(this) { (s, e) =>
      if !s.entries.contains(e.taskId) then s
      else
        val dRunning = if e.status.isTerminal then 0 else -1
        s.copy(
          entries = s.entries.removed(e.taskId),
          owners =
            drop(s.owners, e.ownerKey, bump(s.owners.get(e.ownerKey), dRunning, -1, -e.bytes)),
          pools = drop(s.pools, e.pool, bump(s.pools.get(e.pool), dRunning, -1, -e.bytes))
        )
    }

  def ownerBytes(owner: Option[String]): Long = owners.get(owner).fold(0L)(_.bytes)
  def poolBytes(pool: PoolKind): Long = pools.get(pool).fold(0L)(_.bytes)

  /** The entries a byte budget may evict for `owner`'s benefit, in eviction order — the order the
    * stored-entry caps use: `owner`'s own stale results oldest first, then (`ownOnly = false`, the
    * pool budget) everyone else's, the owner holding the most stale results first. `except` is
    * never a victim (a finishing task must not evict itself).
    */
  def byteVictims(
      pool: PoolKind,
      owner: Option[String],
      cutoff: Long,
      ownOnly: Boolean,
      except: Option[String] = None
  ): List[TaskEntry] =
    val candidates = stale(pool, cutoff).filter(e => !except.contains(e.taskId)).toList
    val (own, others) = candidates.partition(_.ownerKey == owner)
    val oldestFirst = (e: TaskEntry) => (e.lastUpdatedMonoMs, e.seq)
    if ownOnly then own.sortBy(oldestFirst)
    else
      val held = others.groupBy(_.ownerKey)
      val ranked = held.toList.sortBy { case (key, es) => (-es.size, key.getOrElse("")) }
      own.sortBy(oldestFirst) ++ ranked.flatMap(_._2.sortBy(oldestFirst))

  /** Remove `victims` in order until `fits` holds. `None` when it still does not hold with every
    * victim gone. `victims` is evaluated only when the store does not fit as it is.
    */
  def evictUntil(victims: => List[TaskEntry])(fits: TaskStore => Boolean): Option[TaskStore] =
    @scala.annotation.tailrec
    def loop(s: TaskStore, rest: List[TaskEntry]): Option[TaskStore] =
      if fits(s) then Some(s)
      else
        rest match
          case Nil => None
          case victim :: tail => loop(s.remove(List(victim)), tail)
    if fits(this) then Some(this) else loop(this, victims)

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
      Counts(a.running + c.running, a.total + c.total, a.bytes + c.bytes)
    )
    TaskStoreStats(
      pool.total,
      pool.running,
      sweeperActive,
      owners,
      pools,
      entries.valuesIterator.count(_.fiber.isDefined)
    )

  private def bump(c: Option[Counts], dRunning: Int, dTotal: Int, dBytes: Long): Counts =
    val base = c.getOrElse(Counts(0, 0))
    Counts(base.running + dRunning, base.total + dTotal, base.bytes + dBytes)

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
  * Bytes. Counting entries does not bound memory, so every entry is also charged an estimate of
  * what it keeps reachable ([[RetainedBytes]]): while it runs, the request its fiber holds on to
  * (`maxRequestBytes` per task, `-32602` beyond); once terminal, its result (`maxResultBytes` per
  * result — a larger one is dropped and the task ends `failed`). The charges of an owner and of a
  * pool stay within `maxBytesPerOwner` / `maxBytesTotal` at all times: a create that does not fit
  * evicts stale results in the stored-cap order and is otherwise refused (`-32003`); a finishing
  * task whose result does not fit evicts the same way and otherwise ends `failed` with the result
  * discarded. A terminal entry holds neither its fiber nor anything of the request.
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
    /** Test hook invoked at `"forked"` (task fiber parked, nothing registered), `"registered"`
      * (entry admitted, gate still closed) and `"result-waiting"` (a [[result]] call has found its
      * task and waits for the outcome next). `_ => ZIO.unit` in production.
      */
    private[fastmcp] val checkpoint: String => UIO[Unit],
    /** The `FiberRef` values the sweeper fiber runs with: those of the fiber that built this
      * manager ([[TaskManager.make]]: the server's runtime configuration, no request in sight).
      */
    sweeperFiberRefs: FiberRefs = FiberRefs.empty,
    /** The clock of expiry, the retention grace, eviction order and the sweeper's sleep. */
    clock: ServerClock = ServerClock.system
):

  private val perOwnerRunning: Int = settings.maxConcurrentPerSession
  private val perOwnerStored: Int = math.max(settings.maxStoredPerOwner, perOwnerRunning)
  private val poolRunning: Int = settings.maxConcurrentTotal
  private val poolStored: Int = math.max(settings.maxStoredTotal, poolRunning)

  /** `settings.maxRequestBytes`: what one running task may keep of the request that created it. */
  private[fastmcp] val maxRequestBytes: Long = settings.maxRequestBytes

  /** Create a task wrapping `run`. Returns immediately with a [[CreateTaskResult]]; the underlying
    * effect executes on a background daemon fiber.
    *
    * Fails with [[TaskConcurrencyLimitExceeded]] (`-32602`) when the owner already has
    * `settings.maxConcurrentPerSession` running tasks, with [[TaskCapacityExceeded]] (`-32003`)
    * when the pool's running ceiling or a stored-entry cap is hit with nothing evictable, and with
    * [[TaskByteBudgetExceeded]] (`-32003`) when the owner's or the pool's byte budget is. Other
    * failures bubble through as `Throwable`.
    *
    * Registration is all-or-nothing with respect to interruption of the calling fiber.
    *
    * The result is charged to the store's byte budgets when the task finishes. What `run` itself
    * keeps reachable while it runs (whatever it closes over) cannot be seen from here: this entry
    * point charges a running task nothing. The router measures the request it hands a task and uses
    * [[createSized]].
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
    createSized(scope, requestedTtlMs, requestBytes = 0L, run, onStatusChange)

  /** [[create]] for a caller that knows what `run` keeps reachable for as long as it runs:
    * `requestBytes` is the [[RetainedBytes]] estimate of the request the task was created from (its
    * params, and on the 2026-07-28 path the request context its fiber inherits). It is refused with
    * [[TaskRequestTooLarge]] (`-32602`) above `settings.maxRequestBytes`, before anything is
    * forked, and otherwise charged to the owner and the pool until the task is terminal.
    */
  private[fastmcp] def createSized(
      scope: TaskScope,
      requestedTtlMs: Option[Long],
      requestBytes: Long,
      run: ZIO[R, Throwable, Any],
      onStatusChange: Task => UIO[Unit]
  ): ZIO[R, Throwable, CreateTaskResult] =
    createInteractive(scope, requestedTtlMs, requestBytes, _ => run, onStatusChange)

  /** [[createSized]] for a body that may ask its client for input: `run` is handed the task's
    * [[TaskInput]]. Each wait puts the task in `input_required`; it stays a running task (it holds
    * its fiber and its request) and counts against the running caps until it is terminal.
    */
  private[fastmcp] def createInteractive(
      scope: TaskScope,
      requestedTtlMs: Option[Long],
      requestBytes: Long,
      run: TaskInput => ZIO[R, Throwable, Any],
      onStatusChange: Task => UIO[Unit]
  ): ZIO[R, Throwable, CreateTaskResult] =
    if requestBytes > settings.maxRequestBytes then
      ZIO.fail(new TaskRequestTooLarge(settings.maxRequestBytes))
    else
      createAdmitted(
        scope,
        requestedTtlMs,
        math.max(requestBytes, 0L),
        taskId =>
          run(
            new TaskInput:
              def await(requests: List[(String, Json)]): IO[Throwable, Map[String, Json]] =
                awaitInput(taskId, requests)
          ),
        onStatusChange
      )

  private def createAdmitted(
      scope: TaskScope,
      requestedTtlMs: Option[Long],
      requestBytes: Long,
      run: String => ZIO[R, Throwable, Any],
      onStatusChange: Task => UIO[Unit]
  ): ZIO[R, Throwable, CreateTaskResult] =
    ZIO.uninterruptibleMask { _ =>
      for
        taskId <- newId
        promise <- Promise.make[Throwable, Any]
        start <- Promise.make[Nothing, Boolean]
        nowMs <- ZIO.succeed(System.currentTimeMillis())
        nowMono <- ZIO.succeed(clock.monotonicMs())
        ttlMs = effectiveTtl(requestedTtlMs)
        // acquireReleaseExitWith runs the release uninterruptibly, so the status update + promise
        // completion always happen, even if the fiber is interrupted via tasks/cancel. A closed
        // gate (`false`) means the registration was rolled back: the fiber self-interrupts and its
        // release no-ops against the missing entry.
        // `forkDaemon` inherits the current runtime — in production this is the server's
        // `executionRuntime` captured at `runHttp[R]()` entry, so any `R` the run effect requires
        // is already discharged by the inherited environment.
        //
        // Only the body is interruptible. The fiber is forked inside this mask, so it STARTS
        // uninterruptible, and an interrupt that reaches it before it has run a single step
        // (tasks/cancel, a session release or the TTL sweep right after the create) waits until
        // the release is registered. Making the whole fiber interruptible instead let such an
        // interrupt end it before `acquireReleaseExitWith` began: no terminal status was ever
        // recorded, the entry stayed `working` and counted as running until its TTL, and
        // `tasks/result` never completed. Without `.interruptible` on the body, cancel and expiry
        // would never land at all.
        wrapped =
          ZIO.acquireReleaseExitWith(ZIO.unit)((_, exit: Exit[Throwable, Any]) =>
            recordTerminal(taskId, exit, promise, onStatusChange)
          )(_ => start.await.flatMap(go => if go then run(taskId) else ZIO.interrupt).interruptible)
        fiber <- wrapped.forkDaemon
        result <- registerOrRollback(
          taskId,
          scope,
          ttlMs,
          nowMs,
          nowMono,
          requestBytes,
          fiber,
          promise,
          start
        )
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
      bytes: Long,
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
        fiber = Some(fiber),
        result = promise,
        bytes = bytes
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
          // Byte budgets, after the count caps: the new entry's charge must fit beside what the
          // owner and the pool already hold. Stale results make room in the stored-cap order.
          fitBytes(s1.remove(poolEvict.toList), owner, pool, cutoff, entry.bytes, None) match
            case Left(err) => (Left(err), s)
            case Right(fitted) =>
              (Right(!s.sweeperActive), fitted.insert(entry).copy(sweeperActive = true))

  /** Make `extra` more bytes fit inside `owner`'s and `pool`'s byte budgets by evicting stale
    * results (never `except`), or say which budget cannot be met.
    */
  private def fitBytes(
      s: TaskStore,
      owner: Option[String],
      pool: PoolKind,
      cutoff: Long,
      extra: Long,
      except: Option[String]
  ): Either[TaskByteBudgetExceeded, TaskStore] =
    s.evictUntil(s.byteVictims(pool, owner, cutoff, ownOnly = true, except))(
      _.ownerBytes(owner) + extra <= settings.maxBytesPerOwner
    ).toRight(TaskByteBudgetExceeded("maxBytesPerOwner", settings.maxBytesPerOwner))
      .flatMap(own =>
        own
          .evictUntil(own.byteVictims(pool, owner, cutoff, ownOnly = false, except))(
            _.poolBytes(pool) + extra <= settings.maxBytesTotal
          )
          .toRight(TaskByteBudgetExceeded("maxBytesTotal", settings.maxBytesTotal))
      )

  // ------- sweeper -------

  /** One iteration: drop expired entries (interrupting still-running ones), then sleep to the
    * nearest deadline (capped by `sweepIntervalMs`) and repeat. Releases its claim and exits, in
    * the same `modify` that observes the drain, when the store is empty.
    */
  private def sweepLoop: UIO[Unit] =
    ZIO.succeed(clock.monotonicMs()).flatMap { now =>
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
          interruptRunning(expired) *>
            next.fold(ZIO.unit) { deadline =>
              val sleepMs = math.max(1L, math.min(deadline - now, settings.sweepIntervalMs))
              clock.sleep(Duration.fromMillis(sleepMs)) *> sweepLoop
            }
        }
    }

  /** Fork the sweeper. Should the loop ever die with a defect (nothing in its body can fail today),
    * the claim is released and logged so the next `create` starts a fresh sweeper instead of TTL
    * expiry silently stopping for the life of the server. Interruption (shutdown) keeps the claim:
    * `shutdown` drains the store right after.
    *
    * The log goes through the loggers in [[sweeperFiberRefs]]: those installed when the manager was
    * built with [[TaskManager.make]] (on stdio, the stderr logger), ZIO's defaults for a manager
    * built with `makeUnsafe`.
    */
  private def startSweeperFiber: UIO[Unit] =
    // The sweeper is forked by whichever `create` finds none running, and it then runs for as long
    // as the store is non-empty — under steady traffic, indefinitely. A forked fiber starts with a
    // copy of its parent's `FiberRef` values, so it would hold that one request's context (the
    // client's `_meta`, `inputResponses`, headers) for all that time. Its first step swaps them
    // for the manager's own.
    (ZIO.updateFiberRefs((id, _) => sweeperFiberRefs.forkAs(id)) *> sweepLoop)
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
        case Some(entry) => ZIO.some(TaskSnapshot(entry.toTask, None, entry.inputRequests))
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
          ZIO.foreachDiscard(entry.fiber)(_.interrupt) *> storeRef.get.map { latest =>
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
        case Some(entry) => checkpoint("result-waiting") *> entry.result.await
    }

  // ------- input_required -------

  /** Estimated bytes one input request or response keeps reachable. */
  private def inputBytes(key: String, value: Json): Long =
    RetainedBytes.MapEntry + RetainedBytes.ofText(key) +
      RetainedBytes.ofJson(value, settings.maxRequestBytes)

  /** [[TaskInput.await]] for the task `taskId`, called on that task's own fiber.
    *
    * The requests are charged to the task like the request that created it: the task's whole charge
    * stays within `maxRequestBytes`, and the owner's and the pool's byte budgets are met by
    * evicting stale results or not at all. A wait that does not fit fails, and with it the task.
    */
  private def awaitInput(
      taskId: String,
      requests: List[(String, Json)]
  ): IO[Throwable, Map[String, Json]] =
    val asked = requests.distinctBy(_._1)
    if asked.isEmpty then ZIO.succeed(Map.empty)
    else
      for
        waiter <- Promise.make[Nothing, Map[String, Json]]
        nowMs <- ZIO.succeed(System.currentTimeMillis())
        nowMono <- ZIO.succeed(clock.monotonicMs())
        parked <- storeRef.modify { s =>
          s.entries.get(taskId) match
            case Some(entry) if !entry.status.isTerminal =>
              val extra =
                asked.foldLeft(0L)((sum, request) => sum + inputBytes(request._1, request._2))
              if entry.bytes + extra > settings.maxRequestBytes then
                (Left(new TaskInputRequestTooLarge(taskId, settings.maxRequestBytes)), s)
              else
                val cutoff = nowMono - settings.minResultRetentionMs
                fitBytes(s, entry.ownerKey, entry.pool, cutoff, extra, Some(taskId)) match
                  case Left(budget) => (Left(budget), s)
                  case Right(fitted) =>
                    val next = entry.copy(
                      status = TaskStatus.InputRequired,
                      statusMessage = Some("The task is waiting for input."),
                      lastUpdatedAtMs = nowMs,
                      lastUpdatedMonoMs = nowMono,
                      bytes = entry.bytes + extra,
                      inputRequests = asked,
                      inputAnswers = Map.empty,
                      inputWaiter = Some(waiter)
                    )
                    (Right(()), fitted.recharge(entry, next))
            // Swept or released while the body was running: its fiber is being interrupted.
            case _ => (Left(new TaskNotFoundError(taskId)), s)
        }
        _ <- ZIO.fromEither(parked)
        answers <- waiter.await
      yield answers

  /** Deliver `tasks/update` responses to a bearer task.
    *
    * `Left` only for an id that names no task ([[TaskNotFoundError]]), for a response that is not a
    * JSON object ([[TaskInputResponseInvalid]]), and for responses that do not fit the byte bounds
    * ([[TaskInputResponseTooLarge]], [[TaskByteBudgetExceeded]]); nothing is stored then. Otherwise
    * the update is accepted: a response whose key is outstanding is recorded and its request is no
    * longer shown, and every other key is ignored — a key never issued, one already answered, or
    * any key when the task is not waiting for input. The task resumes when no request is
    * outstanding; a partial set of responses leaves it in `input_required`.
    */
  private[fastmcp] def offerInput(
      taskId: String,
      responses: Map[String, Json]
  ): UIO[Either[Throwable, Unit]] =
    type Wake = Option[(Promise[Nothing, Map[String, Json]], Map[String, Json])]
    val nowMs = System.currentTimeMillis()
    val nowMono = clock.monotonicMs()
    storeRef
      .modify[(Either[Throwable, Unit], Wake)] { s =>
        visible(s.entries, taskId, None) match
          case None => ((Left(new TaskNotFoundError(taskId)), None), s)
          case Some(entry) if entry.status != TaskStatus.InputRequired => ((Right(()), None), s)
          case Some(entry) =>
            val accepted = entry.inputRequests.collect {
              case (key, request) if responses.contains(key) => (key, request, responses(key))
            }
            val malformed = accepted.collectFirst {
              case (key, _, response) if response.asObject.isEmpty => key
            }
            if accepted.isEmpty then ((Right(()), None), s)
            else if malformed.isDefined then
              ((Left(new TaskInputResponseInvalid(malformed.getOrElse(""))), None), s)
            else
              // A response takes the place of the request it answers in the task's charge.
              val delta = accepted.foldLeft(0L) { case (sum, (key, request, response)) =>
                sum + inputBytes(key, response) - inputBytes(key, request)
              }
              if entry.bytes + delta > settings.maxRequestBytes then
                ((Left(new TaskInputResponseTooLarge(settings.maxRequestBytes)), None), s)
              else
                val cutoff = nowMono - settings.minResultRetentionMs
                fitBytes(s, entry.ownerKey, entry.pool, cutoff, delta, Some(taskId)) match
                  case Left(budget) => ((Left(budget), None), s)
                  case Right(fitted) =>
                    val answers = entry.inputAnswers ++ accepted.map(a => a._1 -> a._3)
                    val remaining = entry.inputRequests.filterNot(r => responses.contains(r._1))
                    val resumed = remaining.isEmpty
                    val next = entry.copy(
                      status = if resumed then TaskStatus.Working else TaskStatus.InputRequired,
                      statusMessage =
                        if resumed then Some("The operation is now in progress.")
                        else entry.statusMessage,
                      lastUpdatedAtMs = nowMs,
                      lastUpdatedMonoMs = nowMono,
                      bytes = entry.bytes + delta,
                      inputRequests = remaining,
                      // Once resumed the answers belong to the task's fiber, not to the entry.
                      inputAnswers = if resumed then Map.empty else answers,
                      inputWaiter = if resumed then None else entry.inputWaiter
                    )
                    val wake = if resumed then entry.inputWaiter.map(_ -> answers) else None
                    ((Right(()), wake), fitted.recharge(entry, next))
      }
      .flatMap { case (outcome, wake) =>
        ZIO.foreachDiscard(wake)((waiter, answers) => waiter.succeed(answers)).as(outcome)
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
          .flatMap(interruptRunning)

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
      _ <- interruptRunning(drained.entries.values)
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

  /** Interrupt the fibers of the still-running entries among `entries` (a terminal entry holds
    * none), without waiting for them.
    */
  private def interruptRunning(entries: Iterable[TaskEntry]): UIO[Unit] =
    ZIO.foreachDiscard(entries)(entry => ZIO.foreachDiscard(entry.fiber)(_.interruptFork))

  private def effectiveTtl(requested: Option[Long]): Option[Long] =
    val raw = requested.getOrElse(settings.defaultTtlMs)
    Some(math.min(math.max(raw, 0L), settings.maxTtlMs))

  /** What a finished task leaves behind: its terminal status, the bytes its outcome is charged, and
    * the outcome the result promise is completed with.
    */
  private final case class Settled(
      status: TaskStatus,
      message: Option[String],
      bytes: Long,
      outcome: Exit[Throwable, Any]
  )

  private def failed(error: Throwable): Settled =
    Settled(TaskStatus.Failed, Some(error.getMessage), 0L, Exit.fail(error))

  /** Size the task's exit against `maxResultBytes`. A result (or a failure) over the limit is not
    * kept at all: the task ends `failed` with [[TaskResultTooLarge]] in its place.
    */
  private def settle(taskId: String, exit: Exit[Throwable, Any]): Settled =
    val limit = settings.maxResultBytes
    exit match
      case Exit.Success(value) =>
        val bytes = RetainedBytes.of(value, limit)
        if bytes > limit then failed(new TaskResultTooLarge(taskId, limit))
        else
          Settled(TaskStatus.Completed, Some("The operation completed successfully."), bytes, exit)
      // Cancelled (tasks/cancel, TTL sweep, session release): complete the promise with a
      // failure VALUE, never the raw interrupt cause. `result` re-raises whatever is stored in
      // the awaiting `tasks/result` handler fiber, and an interrupt-only cause there is
      // indistinguishable from the *request* being cancelled — the router emits no response
      // frame at all (TJC-2353). Real failures keep their full cause so defects keep traces.
      case Exit.Failure(cause) if cause.isInterruptedOnly =>
        Settled(
          TaskStatus.Cancelled,
          Some("The task was cancelled by request."),
          0L,
          Exit.fail(new TaskCancelledError(taskId))
        )
      case Exit.Failure(cause) =>
        val bytes = RetainedBytes.ofCause(cause, limit)
        if bytes > limit then failed(new TaskResultTooLarge(taskId, limit))
        else
          val message = cause.failureOption.flatMap(t => Option(t.getMessage)).filter(_.nonEmpty)
          Settled(TaskStatus.Failed, message.map(TaskManager.clipStatusMessage), bytes, exit)

  private def recordTerminal(
      taskId: String,
      exit: Exit[Throwable, Any],
      promise: Promise[Throwable, Any],
      onStatusChange: Task => UIO[Unit]
  ): UIO[Unit] =
    val settled = settle(taskId, exit)
    val nowMs = System.currentTimeMillis()
    val nowMono = clock.monotonicMs()
    for
      recorded <- storeRef.modify { s =>
        s.entries.get(taskId) match
          case None => ((None, settled.outcome), s)
          case Some(entry) =>
            // The terminal entry drops the fiber handle (and with it everything the fiber
            // inherited from the request) and swaps the request's byte charge for the result's.
            def terminal(as: Settled): TaskEntry =
              entry.copy(
                status = as.status,
                statusMessage = as.message,
                lastUpdatedAtMs = nowMs,
                lastUpdatedMonoMs = nowMono,
                fiber = None,
                bytes = as.bytes,
                inputRequests = Nil,
                inputAnswers = Map.empty,
                inputWaiter = None
              )
            val cutoff = nowMono - settings.minResultRetentionMs
            val kept = s.markTerminal(entry, terminal(settled))
            // The stored result must fit the owner's and the pool's byte budgets. Older results
            // past the retention grace make room; inside the grace nobody is evicted and it is
            // this result that is discarded.
            fitBytes(kept, entry.ownerKey, entry.pool, cutoff, 0L, Some(taskId)) match
              case Right(fitted) => ((fitted.entries.get(taskId), settled.outcome), fitted)
              case Left(budget) =>
                val discarded = failed(
                  new TaskResultDiscarded(taskId, budget.setting, budget.limitBytes)
                )
                val next = terminal(discarded)
                ((Some(next), discarded.outcome), s.markTerminal(entry, next))
      }
      (updated, outcome) = recorded
      _ <- promise.done(outcome)
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

/** Raised by [[TaskManager.create]] when the new task's charge does not fit the byte budget named
  * by `setting` (`maxBytesPerOwner` or `maxBytesTotal`) and no stored result is old enough to be
  * evicted for it. Maps to `-32003`, like the stored-entry caps: a server condition the client may
  * retry later.
  */
final class TaskByteBudgetExceeded(val setting: String, val limitBytes: Long)
    extends RuntimeException(
      s"Task capacity exceeded (TaskSettings.$setting = $limitBytes estimated bytes)"
    )
    with McpErrorCarrier:
  def toMcpError: McpError = McpError(ErrorCodes.CapacityExceeded, getMessage)

/** The request is too large to be kept for the lifetime of a task (`TaskSettings.maxRequestBytes`).
  * The request's own fault, so `-32602`; nothing is forked or stored.
  */
final class TaskRequestTooLarge(val limitBytes: Long)
    extends RuntimeException(
      "tools/call: the request is too large to run as a task " +
        s"(TaskSettings.maxRequestBytes = $limitBytes estimated bytes)"
    )
    with McpErrorCarrier:
  def toMcpError: McpError = McpError.invalidParams(getMessage)

/** What a task's result is replaced with when the result (or failure) the tool produced is larger
  * than `TaskSettings.maxResultBytes`: the task ends `failed` and the oversized value is not kept.
  * A server-side condition of the tool's output, so `-32603`.
  */
final class TaskResultTooLarge(val taskId: String, val limitBytes: Long)
    extends RuntimeException(
      "The task's result was not kept: it is larger than " +
        s"TaskSettings.maxResultBytes = $limitBytes estimated bytes"
    )
    with McpErrorCarrier:
  def toMcpError: McpError = McpError.internalError(getMessage)

/** What a task's result is replaced with when storing it would take its owner or its pool over the
  * byte budget named by `setting` and no older result may be evicted yet: the task ends `failed`.
  * Maps to `-32003` — the same work may succeed once stored results have been collected or aged.
  */
final class TaskResultDiscarded(val taskId: String, val setting: String, val limitBytes: Long)
    extends RuntimeException(
      "The task's result was discarded: the task store is at " +
        s"TaskSettings.$setting = $limitBytes estimated bytes"
    )
    with McpErrorCarrier:
  def toMcpError: McpError = McpError(ErrorCodes.CapacityExceeded, getMessage)

/** A task's body asked for input (`input_required`) whose requests, with the request the task
  * already keeps, are larger than `TaskSettings.maxRequestBytes`. The task ends `failed`. A
  * condition of the tool's own requests, so `-32603`.
  */
final class TaskInputRequestTooLarge(val taskId: String, val limitBytes: Long)
    extends RuntimeException(
      "The task's input requests were not kept: with its request they are larger than " +
        s"TaskSettings.maxRequestBytes = $limitBytes estimated bytes"
    )
    with McpErrorCarrier:
  def toMcpError: McpError = McpError.internalError(getMessage)

/** `tasks/update` responses that, with what the task already keeps, are larger than
  * `TaskSettings.maxRequestBytes`. The request's own fault, so `-32602`; nothing is stored and the
  * task keeps waiting.
  */
final class TaskInputResponseTooLarge(val limitBytes: Long)
    extends RuntimeException(
      "tasks/update: the responses are too large to keep for the task " +
        s"(TaskSettings.maxRequestBytes = $limitBytes estimated bytes)"
    )
    with McpErrorCarrier:
  def toMcpError: McpError = McpError.invalidParams(getMessage)

/** A `tasks/update` response for an outstanding request that is not a JSON object. `-32602`;
  * nothing is stored and the task keeps waiting.
  */
final class TaskInputResponseInvalid(val key: String)
    extends RuntimeException(s"tasks/update: the response for `$key` must be an object")
    with McpErrorCarrier:
  def toMcpError: McpError = McpError.invalidParams(getMessage)

/** Unknown / cross-session task id from `tasks/result`. Maps to JSON-RPC `-32602` so it agrees with
  * `tasks/get`'s "Unknown task" error.
  */
final class TaskNotFoundError(val taskId: String)
    extends RuntimeException(s"Unknown task: $taskId")
    with McpErrorCarrier:
  def toMcpError: McpError = McpError.invalidParams(getMessage)

/** The answer a `tasks/result` waiter gets for a task that was cancelled — by `tasks/cancel`, by
  * the TTL sweeper, or with its session's release. The task's promise is completed with this VALUE
  * rather than the fiber's interrupt-only cause, which a parked handler would re-raise and the
  * router would mistake for a cancelled request (no frame at all). Maps to JSON-RPC `-32602`, the
  * same family as [[TaskNotFoundError]] and the "already in terminal status" cancel error.
  */
final class TaskCancelledError(val taskId: String)
    extends RuntimeException(s"Task $taskId was cancelled")
    with McpErrorCarrier:
  def toMcpError: McpError = McpError.invalidParams(getMessage)

object TaskManager:

  private val noCheckpoint: String => UIO[Unit] = _ => ZIO.unit

  /** A failed task's `statusMessage` is the tool's failure message, which the store keeps and every
    * `tasks/get` and status notification repeats: keep the head of an overlong one.
    */
  private[fastmcp] val MaxStatusMessageChars: Int = 1024

  private[manager] def clipStatusMessage(message: String): String =
    if message.length <= MaxStatusMessageChars then message
    else message.take(MaxStatusMessageChars) + "..."

  /** Monotonic milliseconds (`System.nanoTime`, available on JVM, Scala.js and Scala Native). Only
    * differences are meaningful; never persisted or put on the wire.
    */
  private[manager] def monotonicMs(): Long = ServerClock.system.monotonicMs()

  /** Allocate a new manager with empty state. `newId` supplies task ids; production passes the
    * platform backend's CSPRNG (`TransportBackend.randomId()`) because task ids are bearer handles
    * and shared code has no `SecureRandom`.
    */
  def make[R](settings: TaskSettings, newId: UIO[String]): UIO[TaskManager[R]] =
    make(settings, newId, ServerClock.system)

  /** [[make]] on `clock` instead of the system clock (the router's, see [[ServerClock]]). */
  private[fastmcp] def make[R](
      settings: TaskSettings,
      newId: UIO[String],
      clock: ServerClock
  ): UIO[TaskManager[R]] =
    for
      store <- Ref.make(TaskStore.empty)
      sweeper <- Ref.make(Option.empty[Fiber.Runtime[Nothing, Unit]])
      // The building fiber's FiberRefs (loggers, executors, environment) become the sweeper's.
      fiberRefs <- ZIO.getFiberRefs
    yield new TaskManager[R](settings, store, newId, sweeper, noCheckpoint, fiberRefs, clock)

  /** Synchronous constructor for non-ZIO call sites (tests). Internally identical to [[make]]. */
  def makeUnsafe[R](settings: TaskSettings, newId: UIO[String]): TaskManager[R] =
    makeUnsafe(settings, newId, noCheckpoint, ServerClock.system)

  /** Test constructor with a checkpoint hook (see [[TaskManager.checkpoint]]). */
  private[fastmcp] def makeUnsafe[R](
      settings: TaskSettings,
      newId: UIO[String],
      checkpoint: String => UIO[Unit]
  ): TaskManager[R] =
    makeUnsafe(settings, newId, checkpoint, ServerClock.system)

  /** Test constructor on `clock`: the test moves it to age tasks and to wake the sweeper. */
  private[fastmcp] def makeUnsafe[R](
      settings: TaskSettings,
      newId: UIO[String],
      checkpoint: String => UIO[Unit],
      clock: ServerClock
  ): TaskManager[R] =
    Unsafe.unsafe { implicit unsafe =>
      new TaskManager[R](
        settings,
        Ref.unsafe.make(TaskStore.empty),
        newId,
        Ref.unsafe.make(Option.empty[Fiber.Runtime[Nothing, Unit]]),
        checkpoint,
        clock = clock
      )
    }
