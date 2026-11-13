package com.tjclp.fastmcp.server.router

import zio.*
import zio.json.*
import zio.json.ast.Json

// Explicit imports avoid the `core.Task` / `zio.Task` collision.
import com.tjclp.fastmcp.core.{
  ExtensionTaskHandle,
  Task,
  TaskOwnerContext,
  TaskParams,
  TaskStatus,
  TaskStatusNotificationParams,
  TaskSupport,
  Tasks
}
import com.tjclp.fastmcp.core.wire.EmptyResult
import com.tjclp.fastmcp.jsonrpc.{JsonFields, JsonRpcMessage, McpError, RequestId}
import com.tjclp.fastmcp.server.manager.{
  RetainedBytes,
  TaskInput,
  TaskManager,
  TaskScope,
  TaskSnapshot,
  ToolManager
}

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
  *
  * Accounting: legacy tasks are owned by their protocol session ([[TaskScope.session]]) and are
  * released when the transport terminates that session ([[Session.terminate]]). Modern bearer tasks
  * are owned by the client key derived from `TaskSettings.ownerKey` — by default the
  * transport-supplied [[Session.clientKey]]; keyless requests share one anonymous bucket. Each
  * owner has a running cap (`-32602`); each pool (legacy vs bearer) has running and stored ceilings
  * (`-32003`), with completed results kept for at least `minResultRetentionMs` before a cap may
  * evict them.
  *
  * Memory: a task outlives the request that created it, and for as long as it runs it keeps that
  * request — the handler closes over `params`, and on the 2026-07-28 path the task fiber inherits
  * the request context ([[Session.runWithRequest]]: the client's `_meta`, `inputResponses`, decoded
  * capabilities and request id) and any allow-listed HTTP headers ([[RequestHeaders]]), all of
  * which a handler may read through `McpContext`. That is measured here ([[retainedBytes]]),
  * refused above `TaskSettings.maxRequestBytes`, and charged to the owner's and the pool's byte
  * budgets until the task is terminal; the store then keeps the result only.
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
          case Some(context) => modern(session, params, next, context)
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
        val taskRequested = JsonFields.get(params, "task").isDefined
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
            // sink queue is shut down when the SSE response ends — sends must go to outbound,
            // which is bounded and, on a transport with no reader for it, keeps nothing.
            val scope = TaskScope.session(session.sessionId)
            // Session termination (DELETE / idle eviction) releases the session's tasks instead
            // of pinning entries nobody can see until their TTL. Keyed (idempotent), registered
            // BEFORE the create so an interrupt landing after registration can never leave a
            // task without its release hook, and again AFTER it: on a session terminated in the
            // meantime the late registration runs the release immediately.
            val release = session.addFinalizer("tasks")(taskManager.releaseScope(scope))
            RequestHeaders.current.flatMap { headers =>
              (release *>
                session
                  .runWithoutSink(
                    taskManager.createSized(
                      scope = scope,
                      requestedTtlMs = ttl,
                      requestBytes = retainedBytes(params, None, None, headers),
                      run = TaskHandoff.inTask(next(session, params)),
                      onStatusChange = task => session.send(statusNotification(task))
                    )
                  )
                  .tap(_ => release))
                // Everything here is non-blocking Ref/Promise work: keeping the whole branch
                // uninterruptible shrinks the window in which a registered task's handle is lost
                // to the dispatcher's continuation. `create` still detects a pending interrupt
                // and rolls back before the mask ends.
                .uninterruptible
                .mapBoth(
                  McpError.fromThrowable,
                  created => created.toJsonAST.getOrElse(Json.Obj())
                )
            }
          case (false, TaskSupport.Required) =>
            ZIO.fail(McpError.methodNotFound(s"Tool '$name' requires task augmentation"))
          case (true, TaskSupport.Forbidden) =>
            ZIO.fail(McpError.methodNotFound(s"Tool '$name' does not support task augmentation"))
          case _ => next(session, params)

  private def modern(
      session: Session,
      params: Json,
      next: RequestHandler[R],
      context: RequestContext
  ): ZIO[R, McpError, Json] =
    decodeName(params) match
      case Left(err) => ZIO.fail(err)
      case Right(name) =>
        val support = toolManager
          .getToolDefinition(name)
          .map(_.effectiveTaskSupport)
          .getOrElse(TaskSupport.Forbidden)
        val clientSupportsTasks = TaskMiddleware.declaresTasks(context)

        // A 2025-11-25 `params.task` is not read here. The extension removed the parameter and
        // says a server "MUST ignore it (treat the field as unknown) rather than using it as an
        // opt-in" (SEP-2663, Backward Compatibility): whether a task is created depends only on
        // the tool's policy and on the capabilities this request declares.
        support match
          case TaskSupport.Forbidden => next(session, params)
          case TaskSupport.Required if !clientSupportsTasks =>
            ZIO.fail(TaskMiddleware.missingTasksCapability)
          case TaskSupport.Optional if !clientSupportsTasks => next(session, params)
          // Deferred creation (`McpTool.withDeferredTask`): the call starts as an ordinary
          // request, so an attempt that asks for input ends with an `input_required` result on
          // the request itself and no task exists. Only an attempt whose handler reaches
          // `McpContext.continueAsTask` creates one — SEP-2663 (Task Creation): a server "SHOULD
          // resolve all MRTR exchanges synchronously before responding with a CreateTaskResult".
          // An attempt that ends any other way is the reply, as for a tool that never is a task.
          case TaskSupport.Optional | TaskSupport.Required if toolManager.isTaskDeferred(name) =>
            TaskHandoff.offered(next(session, params)).catchSome {
              case signal if McpError.isTaskHandoff(signal) =>
                createModern(session, params, next, context)
            }
          case TaskSupport.Optional | TaskSupport.Required =>
            createModern(session, params, next, context)

  /** Create the 2026-07-28 task for this `tools/call` and answer with its handle. */
  private def createModern(
      session: Session,
      params: Json,
      next: RequestHandler[R],
      context: RequestContext
  ): ZIO[R, McpError, Json] =
    // Bucket per client: only the transport-supplied key is trusted by default;
    // `clientInfo` / `_meta` reach a Custom hook but are attacker-controlled.
    val ownerContext =
      TaskOwnerContext(session.clientKey, context.clientInfo, context.meta)
    // runWithoutSink for the same reason as the legacy branch: an offer to this POST's
    // sink queue after it shut down would interrupt the task. Over HTTP the request's
    // own session has no reader for its outbound channel, so what the task sends from
    // then on is discarded, not buffered; on stdio it reaches the client.
    (taskManager.ownerKeyFor(ownerContext) <*> session.currentRequestId
      <*> RequestHeaders.current)
      .mapError(McpError.fromThrowable)
      .flatMap { (key, requestId, headers) =>
        session
          .runWithoutSink(
            taskManager.createInteractive(
              scope = TaskScope.bearer(key),
              requestedTtlMs = None,
              requestBytes = retainedBytes(params, Some(context), requestId, headers),
              run = input => runWithInput(session, params, next, context, input),
              onStatusChange = _ => ZIO.unit
            )
          )
          .uninterruptible // see the legacy branch
          .mapBoth(
            McpError.fromThrowable,
            created =>
              ExtensionTaskHandle
                .fromLegacy(created.task)
                .toJsonAST
                .getOrElse(Json.Obj())
          )
      }

  /** The body of a 2026-07-28 task: the tool call, re-run each time the client has answered what it
    * asked for.
    *
    * A handler asks its client for input the same way inside a task as outside one
    * (`McpContext.elicit` / `createMessage` / `listRoots` / `sendRequest`): the call ends the
    * attempt with an `input_required` result naming the requests. Outside a task that result is the
    * reply, and the client retries the request with `inputResponses` (MRTR, SEP-2322). Inside a
    * task there is no reply to give, so the task itself waits: it shows the requests on `tasks/get`
    * in status `input_required`, collects the responses from `tasks/update`, and then runs the
    * handler again with every response gathered so far in its request context — what a client retry
    * would have sent. `requestState` returned by an attempt is kept here and handed to the next
    * one; it is never put on the task's wire shape.
    *
    * A task created by a hand-off (`McpContext.continueAsTask`) starts here like any other: its
    * first attempt replays the handler with the `inputResponses` of the request that reached the
    * hand-off, so the questions of the MRTR phase are answered and the handler runs on from the
    * hand-off. Those responses are part of every later attempt too.
    *
    * A key is asked once in a task's lifetime (SEP-2663: a server "MUST NOT reuse a key ... after a
    * response for that key has been delivered"). An attempt that asks only for keys it already has
    * responses for cannot make progress, and the task fails.
    */
  private def runWithInput(
      session: Session,
      params: Json,
      next: RequestHandler[R],
      context: RequestContext,
      input: TaskInput
  ): ZIO[R, Throwable, Any] =
    def loop(gathered: Map[String, Json], state: Option[String]): ZIO[R, Throwable, Any] =
      val attempt = context.copy(
        inputResponses = context.inputResponses ++ gathered,
        requestState = state
      )
      // `inTask`: the handler is running as a task, whether the task was created before it
      // first ran or by its own `McpContext.continueAsTask` on the request.
      session.rebindRequest(attempt)(TaskHandoff.inTask(next(session, params))).flatMap { result =>
        TaskMiddleware.inputRequired(result) match
          case None => ZIO.succeed(result)
          case Some((requests, nextState)) =>
            val unanswered =
              requests.filterNot(request => attempt.inputResponses.contains(request._1))
            if unanswered.isEmpty then
              ZIO.fail(
                McpError.internalError(
                  "The tool asked again for input it was already given; a task asks each " +
                    "input request once"
                )
              )
            else
              input
                .await(unanswered)
                .flatMap(answers => loop(gathered ++ answers, nextState.orElse(state)))
      }
    loop(Map.empty, context.requestState)

  /** Estimate of what a task created from this request keeps reachable while it runs:
    *
    *   - the params (arguments, `_meta`, `inputResponses`, `requestState`), which the handler
    *     closes over;
    *   - on the 2026-07-28 path the request context the task fiber inherits: its lookup maps (they
    *     share their JSON nodes with the params), the decoded client capabilities and client info
    *     (copies of two `_meta` entries, so those entries are charged a second time), and the
    *     request id (held by the fiber and by the request's own session id);
    *   - the allow-listed HTTP headers the fiber inherits (`McpServerSettings.contextHeaders`).
    *
    * The walk stops once it passes the limit, so an oversized request costs no more than the limit
    * to refuse.
    */
  private def retainedBytes(
      params: Json,
      context: Option[RequestContext],
      requestId: Option[RequestId],
      headers: Map[String, String]
  ): Long =
    val limit = taskManager.maxRequestBytes
    val paramsBytes = RetainedBytes.ofJson(params, limit)
    if paramsBytes > limit then paramsBytes
    else
      val contextBytes = context.fold(0L) { c =>
        val decodedCopies =
          List(RequestContext.ClientCapabilitiesKey, RequestContext.ClientInfoKey)
            .flatMap(c.meta.get)
            .foldLeft(0L)((sum, json) => sum + RetainedBytes.ofJson(json, limit))
        RetainedBytes.MapEntry * (c.meta.size + c.inputResponses.size) + decodedCopies
      }
      val idBytes = requestId.fold(0L) {
        case RequestId.StrId(value) => 2L * RetainedBytes.ofText(value)
        case _ => 0L
      }
      val headerBytes = headers.foldLeft(0L) { case (sum, (name, value)) =>
        sum + RetainedBytes.MapEntry + RetainedBytes.ofText(name) + RetainedBytes.ofText(value)
      }
      paramsBytes + contextBytes + idBytes + headerBytes

  private def decodeName(params: Json): Either[McpError, String] =
    params match
      case Json.Obj(fields) =>
        JsonFields.get(fields, "name") match
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

private[fastmcp] object TaskMiddleware:

  /** Whether a 2026-07-28 request declares the Tasks extension in its per-request capabilities. */
  def declaresTasks(context: RequestContext): Boolean =
    context.clientCapabilities.extensions.exists(_.contains(Tasks.ExtensionId))

  /** `-32021` naming the Tasks extension, as SEP-2663 ("Capability Negotiation") shows it. */
  def missingTasksCapability: McpError =
    McpError.missingRequiredClientCapability(
      Json.Obj("extensions" -> Json.Obj(Tasks.ExtensionId -> Json.Obj()))
    )

  /** The requests (in order) and `requestState` of a `tools/call` result that is an MRTR
    * `input_required` result; `None` for any other result.
    */
  def inputRequired(result: Json): Option[(List[(String, Json)], Option[String])] =
    result match
      case Json.Obj(fields)
          if JsonFields.get(fields, "resultType").contains(Json.Str("input_required")) =>
        val requests = JsonFields.get(fields, "inputRequests") match
          case Some(Json.Obj(entries)) => entries.toList
          case _ => Nil
        val state = JsonFields.get(fields, "requestState").collect { case Json.Str(value) => value }
        Some((requests, state))
      case _ => None

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

  /** SEP-2663 ("Protocol Errors"): a server MUST answer `-32021` to a client that issues
    * `tasks/get`, `tasks/update` or `tasks/cancel` without declaring the extension on that request.
    * It is checked before anything else, so the answer does not depend on whether the id names a
    * task. Legacy sessions (no request context) are not affected. Returns whether the request is a
    * 2026-07-28 one.
    */
  private def requireTasksExtension(session: Session): IO[McpError, Boolean] =
    session.currentRequestContext.flatMap {
      case Some(context) if !TaskMiddleware.declaresTasks(context) =>
        ZIO.fail(TaskMiddleware.missingTasksCapability)
      case context => ZIO.succeed(context.isDefined)
    }

  val get: RequestHandler[R] = (session, params) =>
    for
      _ <- rejectSharedLegacySession(session, Tasks.MethodTasksGet)
      modern <- requireTasksExtension(session)
      req <- decodeParams[TaskIdParams](params, "tasks/get")
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
      modern <- requireTasksExtension(session)
      req <- decodeParams[TaskIdParams](params, "tasks/cancel")
      json <-
        if modern then
          // The extension's cancel is an acknowledgement, not a read of the task: "The server
          // MUST acknowledge the request with an empty result", with an error only for an id that
          // names no task (SEP-2663, Task Cancellation). A task that is already terminal is a
          // known task, so cancelling it again is acknowledged and changes nothing.
          taskManager.get(req.taskId, None).flatMap {
            case None => ZIO.fail(McpError.invalidParams(s"Unknown task: ${req.taskId}"))
            case Some(task) if task.status.isTerminal => ok(EmptyResult())
            case Some(_) => taskManager.cancel(req.taskId, None) *> ok(EmptyResult())
          }
        else
          taskManager.cancel(req.taskId, Some(session.sessionId)).flatMap {
            case Right(task) => ok(task)
            case Left(err) => ZIO.fail(McpError.invalidParams(err))
          }
    yield json

  // Modern-only: legacy sessions are answered -32601 by McpRouter's modernOnlyMethods gate
  // before this handler is ever reached.
  //
  // Like cancel, the reply is an acknowledgement: an error only for an id that names no task.
  // Responses under a key that is not outstanding are ignored, which covers every key sent to a
  // task that is not waiting for input (SEP-2663, Task Update Requests).
  val update: RequestHandler[R] = (session, params) =>
    for
      _ <- requireTasksExtension(session)
      req <- decodeParams[TaskUpdateParams](params, "tasks/update")
      _ <- taskManager
        .offerInput(req.taskId, req.inputResponses)
        .flatMap(ZIO.fromEither(_))
        .mapError(McpError.fromThrowable)
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
      // `input_required`: every request the task is still waiting on (SEP-2663, Task Polling).
      case None if task.status == TaskStatus.InputRequired =>
        List("inputRequests" -> Json.Obj(snapshot.inputRequests*))
      case _ => Nil
    Json.Obj((base ++ details)*)
