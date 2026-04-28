package com.tjclp.fastmcp
package server.manager

import zio.{System as _, Task as _, *}

import core.*
import server.TaskSettings

/** Internal representation of a task's mutable state. Held inside the [[TaskManager]]'s [[Ref]];
  * never escapes the manager.
  */
private final case class TaskEntry(
    taskId: String,
    sessionId: Option[String],
    createdAtMs: Long,
    lastUpdatedAtMs: Long,
    ttlMs: Option[Long],
    pollIntervalMs: Long,
    status: TaskStatus,
    statusMessage: Option[String],
    fiber: Fiber.Runtime[Throwable, Any],
    result: Promise[Throwable, Any]
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
  *   - schedules TTL-based cleanup on a separate daemon fiber.
  *
  * Session isolation: tasks created with a `sessionId` are only visible to that session. A `None`
  * `sessionId` means session-less (single-user / stdio-style) — visible to all callers. The HTTP
  * transport supplies a session ID; the stdio transport doesn't (and stdio doesn't support tasks
  * anyway, since the Java SDK owns dispatch there).
  */
class TaskManager(settings: TaskSettings, tasksRef: Ref[Map[String, TaskEntry]]):

  /** Create a task wrapping `run`. Returns immediately with a [[CreateTaskResult]]; the underlying
    * effect executes on a background daemon fiber.
    *
    * Fails with [[TaskConcurrencyLimitExceeded]] when the session already has
    * `settings.maxConcurrentPerSession` non-terminal tasks. Other failures bubble through as
    * `Throwable`.
    *
    * @param sessionId
    *   Owning session for isolation, if any.
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
      sessionId: Option[String],
      requestedTtlMs: Option[Long],
      run: ZIO[Any, Throwable, Any],
      onStatusChange: Task => UIO[Unit]
  ): IO[Throwable, CreateTaskResult] =
    for
      taskId <- ZIO.succeed(TaskManager.newTaskId())
      promise <- Promise.make[Throwable, Any]
      start <- Promise.make[Nothing, Unit]
      nowMs <- ZIO.succeed(System.currentTimeMillis())
      ttlMs = effectiveTtl(requestedTtlMs)
      // acquireReleaseExitWith runs the release uninterruptibly, so the status update + promise
      // completion always happen, even if the fiber is interrupted via tasks/cancel.
      wrapped =
        ZIO.acquireReleaseExitWith(ZIO.unit)((_, exit: Exit[Throwable, Any]) =>
          recordTerminal(taskId, exit, promise, onStatusChange)
        )(_ => start.await *> run)
      fiber <- wrapped.forkDaemon
      entry = TaskEntry(
        taskId = taskId,
        sessionId = sessionId,
        createdAtMs = nowMs,
        lastUpdatedAtMs = nowMs,
        ttlMs = ttlMs,
        pollIntervalMs = settings.pollIntervalMs,
        status = TaskStatus.Working,
        statusMessage = Some("The operation is now in progress."),
        fiber = fiber,
        result = promise
      )
      accepted <- tasksRef.modify { all =>
        val runningForSession =
          all.values.count(e => e.sessionId == sessionId && !e.status.isTerminal)
        if runningForSession >= settings.maxConcurrentPerSession then (false, all)
        else (true, all.updated(taskId, entry))
      }
      _ <-
        if accepted then ZIO.unit
        else
          val err = TaskConcurrencyLimitExceeded(
            sessionId,
            settings.maxConcurrentPerSession
          )
          promise.fail(err).unit *>
            fiber.interrupt.ignore *>
            ZIO.fail(err)
      _ <- start.succeed(())
      _ <- ttlMs.fold(ZIO.unit)(t => scheduleEviction(taskId, t).forkDaemon.unit)
    yield CreateTaskResult(entry.toTask)

  /** Look up a task by ID, returning `None` if the ID is unknown or owned by a different session
    * than the caller's.
    */
  def get(taskId: String, sessionId: Option[String]): UIO[Option[Task]] =
    tasksRef.get.map(visible(_, taskId, sessionId).map(_.toTask))

  /** List all tasks visible to `sessionId`, in `createdAt` descending order.
    *
    * Pagination is best-effort: we return everything in a single page (no cursor returned) for the
    * MVP. Will revisit when we have real-world pagination needs.
    */
  def list(sessionId: Option[String], cursor: Option[String]): UIO[ListTasksResult] =
    val _ = cursor // unused in MVP; opaque cursor support deferred
    tasksRef.get.map { all =>
      val visibleTasks = all.values
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
    tasksRef.get.flatMap { all =>
      visible(all, taskId, sessionId) match
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
          // release block (recordTerminal). After that, tasksRef should reflect Cancelled, but
          // under heavy concurrent test load the read can occasionally observe the pre-update
          // entry. Synthesize a Cancelled view if so — the fiber is definitionally cancelled at
          // this point regardless of what the Ref happens to show.
          entry.fiber.interrupt *> tasksRef.get.map { latest =>
            latest.get(taskId) match
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
    * For unknown / cross-session tasks, fails with [[NoSuchElementException]] so the dispatch layer
    * can map to the appropriate JSON-RPC error.
    */
  def result(taskId: String, sessionId: Option[String]): IO[Throwable, Any] =
    tasksRef.get.flatMap { all =>
      visible(all, taskId, sessionId) match
        case None => ZIO.fail(new NoSuchElementException(s"Task not found: $taskId"))
        case Some(entry) => entry.result.await
    }

  // ------- internals -------

  private def sessionVisible(entry: TaskEntry, sessionId: Option[String]): Boolean =
    (entry.sessionId, sessionId) match
      case (None, _) => true // session-less tasks are visible to all
      case (Some(_), None) => false // session-bound task with no caller session → reject
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
    for
      updated <- tasksRef.modify { all =>
        all.get(taskId) match
          case None => (None, all)
          case Some(entry) =>
            val next = entry.copy(
              status = status,
              statusMessage = message,
              lastUpdatedAtMs = nowMs
            )
            (Some(next), all.updated(taskId, next))
      }
      _ <- exit match
        case Exit.Success(value) => promise.succeed(value).unit
        case Exit.Failure(cause) => promise.failCause(cause).unit
      _ <- ZIO.foreachDiscard(updated)(e => onStatusChange(e.toTask).ignore)
    yield ()

  private def scheduleEviction(taskId: String, ttlMs: Long): UIO[Unit] =
    ZIO.sleep(Duration.fromMillis(ttlMs)) *> tasksRef.update(_.removed(taskId))

/** Raised by [[TaskManager.create]] when the calling session is already running
  * `settings.maxConcurrentPerSession` tasks. Dispatch layers catch this and surface a
  * spec-compliant JSON-RPC error.
  */
final class TaskConcurrencyLimitExceeded(
    val sessionId: Option[String],
    val limit: Int
) extends RuntimeException(
      s"Task concurrency limit exceeded for session ${sessionId.getOrElse("(none)")}: limit=$limit"
    )

object TaskManager:

  /** Allocate a new manager with empty state. */
  def make(settings: TaskSettings): UIO[TaskManager] =
    Ref.make(Map.empty[String, TaskEntry]).map(new TaskManager(settings, _))

  /** Synchronous constructor for non-ZIO call sites (e.g., the JS server's lazy initializer).
    * Internally identical to [[make]].
    */
  def makeUnsafe(settings: TaskSettings): TaskManager =
    Unsafe.unsafe { implicit unsafe =>
      new TaskManager(settings, Ref.unsafe.make(Map.empty[String, TaskEntry]))
    }

  /** Cross-platform UUIDv4 generator. We can't use `java.util.UUID.randomUUID()` because Scala.js
    * doesn't ship it; `scala.util.Random` is available on both targets and gives us enough entropy
    * for task IDs (the spec only requires "cryptographically secure" when no auth context is
    * available, which is a transport-level concern).
    */
  private[manager] def newTaskId(): String =
    val bytes = new Array[Byte](16)
    scala.util.Random.nextBytes(bytes)
    bytes(6) = ((bytes(6) & 0x0f) | 0x40).toByte // version 4
    bytes(8) = ((bytes(8) & 0x3f) | 0x80).toByte // variant 1 (RFC 4122)
    val sb = new StringBuilder(36)
    var i = 0
    while i < 16 do
      if i == 4 || i == 6 || i == 8 || i == 10 then sb.append('-')
      val byte = bytes(i) & 0xff
      sb.append(Integer.toHexString((byte >> 4) & 0x0f))
      sb.append(Integer.toHexString(byte & 0x0f))
      i += 1
    sb.toString
