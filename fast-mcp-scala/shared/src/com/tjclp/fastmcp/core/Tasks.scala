package com.tjclp.fastmcp.core

import zio.json.*

/** MCP Tasks — experimental polling primitive introduced in MCP spec 2025-11-25.
  *
  * Tasks are durable, requestor-polled state machines wrapping long-running requests.
  * fast-mcp-scala supports the **server-as-receiver** path: tools opt in via
  * `execution.taskSupport`, clients augment `tools/call` with `params.task: { ttl }`, the server
  * returns a `CreateTaskResult` immediately, and the client polls `tasks/get` / `tasks/result`
  * until completion.
  *
  * The feature is gated behind [[com.tjclp.fastmcp.server.TaskSettings.enabled]] (off by default)
  * since the spec marks Tasks as experimental.
  */
object Tasks:
  // Method names per spec 2025-11-25.
  val MethodTasksGet: String = "tasks/get"
  val MethodTasksList: String = "tasks/list"
  val MethodTasksCancel: String = "tasks/cancel"
  val MethodTasksResult: String = "tasks/result"
  val NotificationTasksStatus: String = "notifications/tasks/status"

  /** Meta key that associates messages with their originating task. */
  val RelatedTaskMetaKey: String = "io.modelcontextprotocol/related-task"

  /** Default poll interval (5 seconds, per spec example). */
  val DefaultPollIntervalMs: Long = 5_000L

/** Per-tool opt-in for task-augmented invocation.
  *
  * Wire encoding matches `execution.taskSupport` in the spec.
  *
  *   - `Forbidden` (default): clients MUST NOT augment with a task; server returns `-32601` if they
  *     try.
  *   - `Optional`: clients MAY augment with a task or invoke normally.
  *   - `Required`: clients MUST augment with a task; server returns `-32601` for bare calls.
  */
enum TaskSupport:
  case Forbidden, Optional, Required

object TaskSupport:

  given JsonCodec[TaskSupport] = JsonCodec.string.transformOrFail(
    {
      case "forbidden" => Right(TaskSupport.Forbidden)
      case "optional" => Right(TaskSupport.Optional)
      case "required" => Right(TaskSupport.Required)
      case other => Left(s"Invalid taskSupport value: $other")
    },
    {
      case TaskSupport.Forbidden => "forbidden"
      case TaskSupport.Optional => "optional"
      case TaskSupport.Required => "required"
    }
  )

  def fromString(s: String): Either[String, TaskSupport] =
    s match
      case "forbidden" => Right(TaskSupport.Forbidden)
      case "optional" => Right(TaskSupport.Optional)
      case "required" => Right(TaskSupport.Required)
      case other => Left(s"Invalid taskSupport value: $other")

/** Lifecycle status for a task. Terminal states are absorbing. */
enum TaskStatus:
  case Working, InputRequired, Completed, Failed, Cancelled

  def isTerminal: Boolean = this match
    case TaskStatus.Completed | TaskStatus.Failed | TaskStatus.Cancelled => true
    case TaskStatus.Working | TaskStatus.InputRequired => false

object TaskStatus:

  given JsonCodec[TaskStatus] = JsonCodec.string.transformOrFail(
    {
      case "working" => Right(TaskStatus.Working)
      case "input_required" => Right(TaskStatus.InputRequired)
      case "completed" => Right(TaskStatus.Completed)
      case "failed" => Right(TaskStatus.Failed)
      case "cancelled" => Right(TaskStatus.Cancelled)
      case other => Left(s"Invalid task status: $other")
    },
    {
      case TaskStatus.Working => "working"
      case TaskStatus.InputRequired => "input_required"
      case TaskStatus.Completed => "completed"
      case TaskStatus.Failed => "failed"
      case TaskStatus.Cancelled => "cancelled"
    }
  )

/** Public Task shape returned by `tasks/get`, `tasks/cancel`, and embedded in `CreateTaskResult` /
  * `notifications/tasks/status`. Timestamps are ISO 8601 strings (RFC 3339 §5).
  */
case class Task(
    taskId: String,
    status: TaskStatus,
    statusMessage: Option[String] = None,
    createdAt: String,
    lastUpdatedAt: String,
    ttl: Option[Long] = None,
    pollInterval: Option[Long] = None
)

object Task:
  given JsonCodec[Task] = DeriveJsonCodec.gen[Task]

/** Body of `params.task` on a task-augmented request. */
case class TaskParams(ttl: Option[Long] = None)

object TaskParams:
  given JsonCodec[TaskParams] = DeriveJsonCodec.gen[TaskParams]

/** Response body returned immediately when a task-augmented request is accepted. The actual
  * operation result becomes available later via `tasks/result`.
  */
case class CreateTaskResult(task: Task)

object CreateTaskResult:
  given JsonCodec[CreateTaskResult] = DeriveJsonCodec.gen[CreateTaskResult]

/** Body of `tasks/list` response. */
case class ListTasksResult(tasks: List[Task], nextCursor: Option[String] = None)

object ListTasksResult:
  given JsonCodec[ListTasksResult] = DeriveJsonCodec.gen[ListTasksResult]

/** Body of a `notifications/tasks/status` notification's `params`. */
case class TaskStatusNotificationParams(
    taskId: String,
    status: TaskStatus,
    statusMessage: Option[String] = None,
    createdAt: String,
    lastUpdatedAt: String,
    ttl: Option[Long] = None,
    pollInterval: Option[Long] = None
)

object TaskStatusNotificationParams:
  given JsonCodec[TaskStatusNotificationParams] = DeriveJsonCodec.gen[TaskStatusNotificationParams]

/** Wire shape for the `execution` block on a `tools/list` entry. */
case class ToolExecution(taskSupport: Option[TaskSupport] = None)

object ToolExecution:
  given JsonCodec[ToolExecution] = DeriveJsonCodec.gen[ToolExecution]

/** Helpers for ISO 8601 (RFC 3339) timestamp formatting that work on both JVM and Scala.js.
  *
  * Avoids `java.text.SimpleDateFormat` and `java.time.Instant` (neither is fully implemented in
  * Scala.js). Implements UTC date breakdown via Howard Hinnant's `civil_from_days` algorithm.
  */
object TaskTimestamp:

  def fromEpochMillis(epochMillis: Long): String =
    val days = Math.floorDiv(epochMillis, 86_400_000L)
    val msInDay = Math.floorMod(epochMillis, 86_400_000L).toInt
    val (year, month, day) = civilFromDays(days)
    val hour = msInDay / 3_600_000
    val rem1 = msInDay % 3_600_000
    val minute = rem1 / 60_000
    val rem2 = rem1 % 60_000
    val second = rem2 / 1000
    val milli = rem2 % 1000
    f"$year%04d-$month%02d-$day%02dT$hour%02d:$minute%02d:$second%02d.$milli%03dZ"

  def now(): String = fromEpochMillis(System.currentTimeMillis())

  /** Howard Hinnant's algorithm for civil-from-days conversion (public domain).
    * https://howardhinnant.github.io/date_algorithms.html
    */
  private def civilFromDays(days: Long): (Int, Int, Int) =
    val z = days + 719468L
    val era = if z >= 0 then z / 146097L else (z - 146096L) / 146097L
    val doe = z - era * 146097L
    val yoe = (doe - doe / 1460L + doe / 36524L - doe / 146096L) / 365L
    val y = yoe + era * 400L
    val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
    val mp = (5L * doy + 2L) / 153L
    val d = (doy - (153L * mp + 2L) / 5L + 1L).toInt
    val m = (if mp < 10L then mp + 3L else mp - 9L).toInt
    val year = (if m <= 2 then y + 1 else y).toInt
    (year, m, d)
