package com.tjclp.fastmcp.server.router

import zio.*
import zio.json.*
import zio.json.ast.Json

// Explicit imports avoid the `core.Task` / `zio.Task` collision.
import com.tjclp.fastmcp.core.{
  ExtensionTaskHandle,
  Task,
  TaskParams,
  TaskStatus,
  TaskStatusNotificationParams,
  TaskSupport,
  Tasks
}
import com.tjclp.fastmcp.core.wire.EmptyResult
import com.tjclp.fastmcp.jsonrpc.{JsonRpcMessage, McpError}
import com.tjclp.fastmcp.server.manager.{TaskManager, TaskSnapshot, ToolManager}

private case class TaskIdParams(taskId: String)

private object TaskIdParams:
  given JsonDecoder[TaskIdParams] = DeriveJsonDecoder.gen[TaskIdParams]

private case class TaskListParams(cursor: Option[String] = None)

private object TaskListParams:
  given JsonDecoder[TaskListParams] = DeriveJsonDecoder.gen[TaskListParams]

private case class TaskUpdateParams(taskId: String, inputResponses: Map[String, Json])

private object TaskUpdateParams:
  given JsonDecoder[TaskUpdateParams] = DeriveJsonDecoder.gen[TaskUpdateParams]

/** Task routing supports both eras without mixing their wire shapes. Legacy requests explicitly
  * carry `params.task`; 2026 clients declare the Tasks extension and the server may return a task
  * handle without per-call augmentation.
  */
final class TaskMiddleware[R](
    taskManager: TaskManager[R],
    toolManager: ToolManager[R]
) extends Middleware[R]:

  def wrap(method: String, next: RequestHandler[R]): RequestHandler[R] =
    if method != Methods.ToolsCall then next
    else
      (session, params) =>
        session.currentRequestContext.flatMap {
          case Some(context) =>
            modern(session, params, next, context.clientCapabilities.extensions)
          case None => legacy(session, params, next)
        }

  private def legacy(
      session: Session,
      params: Json,
      next: RequestHandler[R]
  ): ZIO[R, McpError, Json] =
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
          // The shared stateless session's id is not a client identity: tasks keyed on it would
          // be visible to (and cancellable by) every stateless client. Modern 2026-07-28 task
          // requests are unaffected — they never take this legacy branch.
          case (true, _) if !session.supportsTasks =>
            ZIO.fail(
              McpError.methodNotFound(
                "task augmentation is not available on the legacy stateless transport; " +
                  "use MCP 2026-07-28 task requests or a session-bearing transport"
              )
            )
          case (true, TaskSupport.Optional | TaskSupport.Required) =>
            // runWithoutSink: the task fiber (forked inside create) outlives this POST, whose
            // sink queue is shut down when the SSE response ends — sends must go to outbound.
            session
              .runWithoutSink(
                taskManager.create(
                  sessionId = Some(session.sessionId),
                  requestedTtlMs = ttl,
                  run = next(session, params),
                  onStatusChange = task => session.send(statusNotification(task))
                )
              )
              .mapBoth(
                McpError.fromThrowable,
                created => created.toJsonAST.getOrElse(Json.Obj())
              )
          case (false, TaskSupport.Required) =>
            ZIO.fail(McpError.methodNotFound(s"Tool '$name' requires task augmentation"))
          case (true, TaskSupport.Forbidden) =>
            ZIO.fail(McpError.methodNotFound(s"Tool '$name' does not support task augmentation"))
          case _ => next(session, params)

  private def modern(
      session: Session,
      params: Json,
      next: RequestHandler[R],
      extensions: Option[Map[String, Json]]
  ): ZIO[R, McpError, Json] =
    decodeName(params) match
      case Left(err) => ZIO.fail(err)
      case Right(name) =>
        val support = toolManager
          .getToolDefinition(name)
          .map(_.effectiveTaskSupport)
          .getOrElse(TaskSupport.Forbidden)
        val hasLegacyAugmentation = params match
          case Json.Obj(fields) => fields.toMap.contains("task")
          case _ => false
        val clientSupportsTasks = extensions.exists(_.contains(Tasks.ExtensionId))

        if hasLegacyAugmentation then
          ZIO.fail(McpError.invalidParams("tools/call: `params.task` was removed in 2026-07-28"))
        else
          support match
            case TaskSupport.Forbidden => next(session, params)
            case TaskSupport.Required if !clientSupportsTasks =>
              ZIO.fail(
                McpError.missingRequiredClientCapability(
                  Json.Obj("extensions" -> Json.Obj(Tasks.ExtensionId -> Json.Obj()))
                )
              )
            case TaskSupport.Optional if !clientSupportsTasks => next(session, params)
            case TaskSupport.Optional | TaskSupport.Required =>
              // runWithoutSink for the same reason as the legacy branch: the ephemeral modern
              // session's outbound is never drained, but an offer to a live unbounded queue is
              // harmless and GC'd with the task — unlike an offer to a shutdown sink queue.
              session
                .runWithoutSink(
                  taskManager.create(
                    sessionId = None,
                    requestedTtlMs = None,
                    run = next(session, params),
                    onStatusChange = _ => ZIO.unit
                  )
                )
                .mapBoth(
                  McpError.fromThrowable,
                  created =>
                    ExtensionTaskHandle
                      .fromLegacy(created.task)
                      .toJsonAST
                      .getOrElse(Json.Obj())
                )

  private def decodeName(params: Json): Either[McpError, String] =
    params match
      case Json.Obj(fields) =>
        fields.toMap.get("name") match
          case Some(Json.Str(name)) => Right(name)
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
      params.toJsonAST.toOption
    )

private case class CallToolRequestParamsLite(task: Option[TaskParams] = None)

private object CallToolRequestParamsLite:
  given JsonDecoder[CallToolRequestParamsLite] = DeriveJsonDecoder.gen[CallToolRequestParamsLite]

final class TaskHandlers[R](taskManager: TaskManager[R]):

  private def ok[A: JsonEncoder](value: A): UIO[Json] =
    ZIO.succeed(value.toJsonAST.getOrElse(Json.Obj()))

  private def decodeParams[A: JsonDecoder](params: Json, context: String): IO[McpError, A] =
    val normalized = params match
      case Json.Null => Json.Obj()
      case other => other
    ZIO.fromEither(normalized.as[A]).mapError(err => McpError.invalidParams(s"$context: $err"))

  /** Legacy callers on the shared stateless session all present the same session identity, so the
    * legacy task surface must not exist there. Modern callers (with a request context) are
    * unaffected.
    */
  private def rejectSharedLegacySession(session: Session, method: String): IO[McpError, Unit] =
    session.currentRequestContext.flatMap { ctx =>
      ZIO.fail(McpError.methodNotFound(method)).when(ctx.isEmpty && !session.supportsTasks).unit
    }

  val get: RequestHandler[R] = (session, params) =>
    for
      _ <- rejectSharedLegacySession(session, Tasks.MethodTasksGet)
      req <- decodeParams[TaskIdParams](params, "tasks/get")
      modern <- session.currentRequestContext.map(_.isDefined)
      json <-
        if modern then
          taskManager.snapshot(req.taskId, None).flatMap {
            case Some(snapshot) => ZIO.succeed(renderSnapshot(snapshot))
            case None => ZIO.fail(McpError.invalidParams(s"Unknown task: ${req.taskId}"))
          }
        else
          taskManager.get(req.taskId, Some(session.sessionId)).flatMap {
            case Some(task) => ok(task)
            case None => ZIO.fail(McpError.invalidParams(s"Unknown task: ${req.taskId}"))
          }
    yield json

  val list: RequestHandler[R] = (session, params) =>
    for
      _ <- rejectSharedLegacySession(session, Tasks.MethodTasksList)
      req <- decodeParams[TaskListParams](params, "tasks/list")
      _ <- ZIO
        .fail(McpError.invalidParams(s"tasks/list: unknown cursor: ${req.cursor.getOrElse("")}"))
        .when(req.cursor.isDefined)
      result <- taskManager.list(Some(session.sessionId), req.cursor)
      json <- ok(result)
    yield json

  val cancel: RequestHandler[R] = (session, params) =>
    for
      _ <- rejectSharedLegacySession(session, Tasks.MethodTasksCancel)
      req <- decodeParams[TaskIdParams](params, "tasks/cancel")
      modern <- session.currentRequestContext.map(_.isDefined)
      outcome <- taskManager.cancel(req.taskId, if modern then None else Some(session.sessionId))
      json <- outcome match
        case Right(task) => if modern then ok(EmptyResult()) else ok(task)
        case Left(err) => ZIO.fail(McpError.invalidParams(err))
    yield json

  val update: RequestHandler[R] = (session, params) =>
    for
      modern <- session.currentRequestContext.map(_.isDefined)
      _ <- ZIO.fail(McpError.methodNotFound(Tasks.MethodTasksUpdate)).unless(modern)
      req <- decodeParams[TaskUpdateParams](params, "tasks/update")
      task <- taskManager.get(req.taskId, None)
      _ <- task match
        case None => ZIO.fail(McpError.invalidParams(s"Unknown task: ${req.taskId}"))
        case Some(value) if value.status != TaskStatus.InputRequired =>
          ZIO.fail(McpError.invalidParams(s"Task ${req.taskId} is not waiting for input"))
        case Some(_) =>
          ZIO.fail(
            McpError.invalidParams(
              "This task has no resumable input request; retry the original MCP request instead"
            )
          )
      json <- ok(EmptyResult())
    yield json

  val result: RequestHandler[R] = (session, params) =>
    for
      _ <- rejectSharedLegacySession(session, Tasks.MethodTasksResult)
      req <- decodeParams[TaskIdParams](params, "tasks/result")
      raw <- taskManager
        .result(req.taskId, Some(session.sessionId))
        .mapError(McpError.fromThrowable)
      json = raw match
        case value: Json => value
        case other => Json.Str(other.toString)
    yield json

  private def renderSnapshot(snapshot: TaskSnapshot): Json =
    val task = snapshot.task
    val handle = ExtensionTaskHandle.fromLegacy(task)
    val base = List(
      "taskId" -> Json.Str(handle.taskId),
      "status" -> handle.status.toJsonAST.getOrElse(Json.Str("failed")),
      "createdAt" -> Json.Str(handle.createdAt),
      "lastUpdatedAt" -> Json.Str(handle.lastUpdatedAt),
      "ttlMs" -> handle.ttlMs.fold[Json](Json.Null)(value => Json.Num(BigDecimal(value)))
    ) ++ handle.statusMessage.map(value => "statusMessage" -> Json.Str(value)).toList ++
      handle.pollIntervalMs
        .map(value => "pollIntervalMs" -> Json.Num(BigDecimal(value)))
        .toList
    val details = snapshot.outcome match
      case Some(Exit.Success(value)) if task.status == TaskStatus.Completed =>
        List(
          "result" -> (value match
            case json: Json => json
            case other => Json.Str(other.toString)
          )
        )
      case Some(Exit.Failure(cause)) if task.status == TaskStatus.Failed =>
        val error = cause.failureOption
          .map(McpError.fromThrowable)
          .getOrElse(McpError.internalError("Task failed"))
        List("error" -> error.toErrorObject.toJsonAST.getOrElse(Json.Obj()))
      case _ => Nil
    Json.Obj((base ++ details)*)
