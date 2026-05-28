package com.tjclp.fastmcp.server.router

import zio.*
import zio.json.*
import zio.json.ast.Json

// Explicit core imports: `import core.*` would make `Task` ambiguous with `zio.Task`.
import com.tjclp.fastmcp.core.{Task, TaskParams, TaskStatusNotificationParams, TaskSupport, Tasks}
import com.tjclp.fastmcp.jsonrpc.JsonRpcMessage
import com.tjclp.fastmcp.jsonrpc.McpError
import com.tjclp.fastmcp.server.manager.{TaskManager, ToolManager}

/** `params` shape for tasks/get, tasks/cancel, tasks/result. */
private case class TaskIdParams(taskId: String)

private object TaskIdParams:
  given JsonDecoder[TaskIdParams] = DeriveJsonDecoder.gen[TaskIdParams]

/** `params` shape for tasks/list (paginated). */
private case class TaskListParams(cursor: Option[String] = None)

private object TaskListParams:
  given JsonDecoder[TaskListParams] = DeriveJsonDecoder.gen[TaskListParams]

/** Task augmentation as router middleware — the clean replacement for the old transport-layer
  * `TaskDispatcher` hack. Wraps `tools/call`: when the client supplies `params.task` and the tool
  * opts in (`execution.taskSupport` ≠ Forbidden), the call is wrapped in a [[TaskManager]] task — a
  * `CreateTaskResult` is returned immediately and the work runs in the background, with status
  * pushed over the session's outbound channel. All other methods pass through untouched.
  *
  * Tool-level negotiation (spec 2025-11-25):
  *   - bare call to a `Required` tool → `-32601`
  *   - task-augmented call to a `Forbidden` tool → `-32601`
  *   - otherwise run normally / as a task per the client's request.
  */
final class TaskMiddleware[R](
    taskManager: TaskManager[R],
    toolManager: ToolManager[R]
) extends Middleware[R]:

  def wrap(method: String, next: RequestHandler[R]): RequestHandler[R] =
    if method != Methods.ToolsCall then next
    else
      (session, params) =>
        decodeName(params) match
          case Left(err) => ZIO.fail(err)
          case Right(name) =>
            val support = toolManager
              .getToolDefinition(name)
              .map(_.effectiveTaskSupport)
              .getOrElse(TaskSupport.Forbidden)
            val taskRequested = params match
              case Json.Obj(fields) => fields.toMap.contains("task")
              case _ => false
            val ttl = params.as[CallToolRequestParamsLite].toOption.flatMap(_.task).flatMap(_.ttl)

            (taskRequested, support) match
              case (true, TaskSupport.Optional | TaskSupport.Required) =>
                taskManager
                  .create(
                    sessionId = Some(session.sessionId),
                    requestedTtlMs = ttl,
                    run = next(session, params),
                    onStatusChange = task => session.send(statusNotification(task))
                  )
                  .mapBoth(
                    McpError.fromThrowable,
                    created => created.toJsonAST.getOrElse(Json.Obj())
                  )
              case (false, TaskSupport.Required) =>
                ZIO.fail(McpError.methodNotFound(s"Tool '$name' requires task augmentation"))
              case (true, TaskSupport.Forbidden) =>
                ZIO.fail(
                  McpError.methodNotFound(s"Tool '$name' does not support task augmentation")
                )
              case _ =>
                next(session, params)

  private def decodeName(params: Json): Either[McpError, String] =
    params match
      case Json.Obj(fields) =>
        fields.toMap.get("name") match
          case Some(Json.Str(n)) => Right(n)
          case _ => Left(McpError.invalidParams("tools/call: missing `name`"))
      case _ => Left(McpError.invalidParams("tools/call: params must be an object"))

  private def statusNotification(task: Task): JsonRpcMessage =
    val params = TaskStatusNotificationParams(
      taskId = task.taskId,
      status = task.status,
      statusMessage = task.statusMessage,
      createdAt = task.createdAt,
      lastUpdatedAt = task.lastUpdatedAt,
      ttl = task.ttl,
      pollInterval = task.pollInterval
    )
    JsonRpcMessage.Notification(
      Tasks.NotificationTasksStatus,
      Some(params.toJsonAST.getOrElse(Json.Obj()))
    )

/** Minimal lens for reading `params.task.ttl` without re-decoding the whole call. */
private case class CallToolRequestParamsLite(task: Option[TaskParams] = None)

private object CallToolRequestParamsLite:
  given JsonDecoder[CallToolRequestParamsLite] = DeriveJsonDecoder.gen[CallToolRequestParamsLite]

/** The tasks/get|list|cancel|result built-in request handlers (registered only when tasks are
  * enabled).
  */
final class TaskHandlers[R](taskManager: TaskManager[R]):

  private def ok[A: JsonEncoder](value: A): UIO[Json] =
    ZIO.succeed(value.toJsonAST.getOrElse(Json.Obj()))

  private def decodeParams[A: JsonDecoder](params: Json, ctx: String): IO[McpError, A] =
    // A request may omit `params` entirely (arrives as Json.Null). Treat that as an empty object so
    // handlers with all-optional params (e.g. tasks/list's cursor) decode to their defaults;
    // required-field handlers (tasks/get/cancel/result) still fail with a clear "missing field".
    val normalized = params match
      case Json.Null => Json.Obj()
      case other => other
    ZIO.fromEither(normalized.as[A]).mapError(err => McpError.invalidParams(s"$ctx: $err"))

  val get: RequestHandler[R] = (session, params) =>
    for
      req <- decodeParams[TaskIdParams](params, "tasks/get")
      task <- taskManager.get(req.taskId, Some(session.sessionId))
      json <- task match
        case Some(t) => ok(t)
        case None => ZIO.fail(McpError.invalidParams(s"Unknown task: ${req.taskId}"))
    yield json

  val list: RequestHandler[R] = (session, params) =>
    for
      req <- decodeParams[TaskListParams](params, "tasks/list")
      result <- taskManager.list(Some(session.sessionId), req.cursor)
      json <- ok(result)
    yield json

  val cancel: RequestHandler[R] = (session, params) =>
    for
      req <- decodeParams[TaskIdParams](params, "tasks/cancel")
      outcome <- taskManager.cancel(req.taskId, Some(session.sessionId))
      json <- outcome match
        case Right(task) => ok(task)
        case Left(err) => ZIO.fail(McpError.invalidParams(err))
    yield json

  val result: RequestHandler[R] = (session, params) =>
    for
      req <- decodeParams[TaskIdParams](params, "tasks/result")
      raw <- taskManager
        .result(req.taskId, Some(session.sessionId))
        .mapError(McpError.fromThrowable)
      // The task's run effect returned the tool-call result JSON; pass it straight through.
      json = raw match
        case j: Json => j
        case other => Json.Str(other.toString)
    yield json
