package com.tjclp.fastmcp.server.router

import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.core.wire.*
import com.tjclp.fastmcp.jsonrpc.*
import com.tjclp.fastmcp.jsonrpc.JsonRpcMessage.*

/** Canonical MCP request method names. */
object Methods:
  val Initialize = "initialize"
  val Ping = "ping"
  val ToolsList = "tools/list"
  val ToolsCall = "tools/call"
  val ResourcesList = "resources/list"
  val ResourcesRead = "resources/read"
  val ResourcesTemplatesList = "resources/templates/list"
  val ResourcesSubscribe = "resources/subscribe"
  val ResourcesUnsubscribe = "resources/unsubscribe"
  val PromptsList = "prompts/list"
  val PromptsGet = "prompts/get"
  val CompletionComplete = "completion/complete"
  val LoggingSetLevel = "logging/setLevel"

/** The MCP dispatcher — the native-Scala replacement for the Java SDK's `McpAsyncServer` and the TS
  * SDK's `Server`.
  *
  * Built once per server start from an immutable handler map (M5 registers the built-ins). The
  * dispatcher:
  *   - routes each [[JsonRpcMessage]] to its registered handler, wrapping the request pipeline in
  *     the middleware chain (validation → tasks → handler → error mapping),
  *   - maps handler `McpError`s to JSON-RPC error responses; unknown methods → `-32601`,
  *   - forks each request so an incoming `notifications/cancelled` can interrupt it by id,
  *   - **derives [[ServerCapabilities]] from which handlers are registered** — `logging` is
  *     advertised iff a `logging/setLevel` handler is wired, which fixes issue #56 by construction.
  *
  * @param requestHandlers
  *   terminal handlers keyed by method
  * @param notificationHandlers
  *   terminal notification handlers keyed by method
  * @param middlewares
  *   applied around every request handler; head = outermost
  * @param hooks
  *   observability hooks
  * @param tasksEnabled
  *   whether the `tasks` capability is advertised
  * @param resourcesSubscribe
  *   advertise `resources.subscribe`
  * @param listChanged
  *   advertise `listChanged` on tools/resources/prompts
  */
final class McpRouter[R](
    requestHandlers: Map[String, RequestHandler[R]],
    notificationHandlers: Map[String, NotificationHandler[R]],
    middlewares: List[Middleware[R]],
    hooks: ServerHooks[R],
    tasksEnabled: Boolean,
    resourcesSubscribe: Boolean,
    listChanged: Boolean
):

  /** Capabilities derived from the registered handler set. Issue #56 dies here: a capability is
    * present only when its handler is wired.
    */
  val capabilities: ServerCapabilities =
    McpRouter.deriveCapabilities(
      requestHandlers.keySet,
      tasksEnabled,
      resourcesSubscribe,
      listChanged
    )

  /** Dispatch one inbound message. Never fails — handler errors become JSON-RPC error responses.
    * Returns `Some(response)` for requests, `None` for notifications and cancelled requests.
    * Inbound responses (`Success`/`Failure`) are routed to the matching pending server-initiated
    * request via [[Session.completePending]] (a null-id error response can't be correlated, so it's
    * ignored).
    */
  def dispatch(session: Session, message: JsonRpcMessage): URIO[R, Option[JsonRpcMessage]] =
    message match
      case Request(id, method, params) =>
        dispatchRequest(session, id, method, params.getOrElse(Json.Null))
      case Notification(method, params) =>
        dispatchNotification(session, method, params.getOrElse(Json.Null)).as(None)
      case Success(id, result) =>
        session.completePending(id, Right(result)).as(None)
      case Failure(Some(id), error) =>
        session.completePending(id, Left(error)).as(None)
      case Failure(None, _) =>
        ZIO.none
      case Invalid(id, reason) =>
        // Structurally invalid frame: answer -32600 with the offender's id (null when unknown).
        ZIO.some(Failure(id, McpError.invalidRequest(s"Invalid Request: $reason").toErrorObject))

  private def dispatchRequest(
      session: Session,
      id: RequestId,
      method: String,
      params: Json
  ): URIO[R, Option[JsonRpcMessage]] =
    session.isInitialized.flatMap { initialized =>
      // Spec lifecycle: before initialization completes, only initialize and ping are served.
      // Stateless/ephemeral sessions are created pre-marked by their transports.
      if !initialized && method != Methods.Initialize && method != Methods.Ping then
        ZIO.succeed(
          Some(
            Failure(
              Some(id),
              McpError
                .invalidRequest(s"Server not initialized — send initialize before '$method'")
                .toErrorObject
            )
          )
        )
      else dispatchInitialized(session, id, method, params)
    }

  private def dispatchInitialized(
      session: Session,
      id: RequestId,
      method: String,
      params: Json
  ): URIO[R, Option[JsonRpcMessage]] =
    requestHandlers.get(method) match
      case None =>
        ZIO.succeed(Some(Failure(Some(id), McpError.methodNotFound(method).toErrorObject)))
      case Some(handler) =>
        val pipeline = Middleware.chain(middlewares, method, handler)
        for
          fiber <- pipeline(session, params).fork
          _ <- session.trackInflight(id, method, fiber)
          // `ensuring` so an interrupted dispatch can't leave a stale registry entry behind.
          exit <- fiber.await.ensuring(session.clearInflight(id))
          resp <- exit match
            case Exit.Success(result) =>
              ZIO.succeed(Some(Success(id, WireMapping.completeResult(result))))
            case Exit.Failure(cause) =>
              cause.failureOption match
                case Some(err) =>
                  hooks.onError(method, err, session).as(Some(Failure(Some(id), err.toErrorObject)))
                case None =>
                  if cause.isInterruptedOnly then ZIO.none // cancelled — emit no response
                  else
                    val err = McpError.internalError(
                      Option(cause.squashWith(identity).getMessage).getOrElse("internal error")
                    )
                    hooks
                      .onError(method, err, session)
                      .as(Some(Failure(Some(id), err.toErrorObject)))
        yield resp

  private def dispatchNotification(session: Session, method: String, params: Json): URIO[R, Unit] =
    if method == NotificationMethods.Cancelled then handleCancellation(session, params)
    else
      notificationHandlers.get(method) match
        case Some(h) => h(session, params).ignore // unknown-shape notifications are non-fatal
        case None => ZIO.unit // unknown notifications MUST be ignored per JSON-RPC

  private def handleCancellation(session: Session, params: Json): UIO[Unit] =
    params.as[CancelledNotificationParams] match
      case Right(p) =>
        p.requestId.flatMap(_.as[RequestId].toOption) match
          case Some(reqId) => session.cancelInflight(reqId)
          case None => ZIO.unit
      case Left(_) => ZIO.unit

object McpRouter:

  /** Derive [[ServerCapabilities]] from the set of registered request-method names plus settings.
    * Shared by the router instance and the `initialize` built-in so both report identical caps. The
    * crux of issue #56: `logging` appears only when `logging/setLevel` is registered.
    */
  def deriveCapabilities(
      methods: Set[String],
      tasksEnabled: Boolean,
      resourcesSubscribe: Boolean,
      listChanged: Boolean
  ): ServerCapabilities =
    val lc = Option.when(listChanged)(true)
    ServerCapabilities(
      tools = Option.when(methods.contains(Methods.ToolsList))(ToolsCapability(listChanged = lc)),
      resources = Option.when(methods.contains(Methods.ResourcesList))(
        ResourcesCapability(subscribe = Option.when(resourcesSubscribe)(true), listChanged = lc)
      ),
      prompts =
        Option.when(methods.contains(Methods.PromptsList))(PromptsCapability(listChanged = lc)),
      completions = Option.when(methods.contains(Methods.CompletionComplete))(Json.Obj()),
      logging = Option.when(methods.contains(Methods.LoggingSetLevel))(Json.Obj()),
      tasks = Option.when(tasksEnabled)(
        ServerTasksCapability(requests =
          Some(ServerTasksRequests(tools = Some(ServerTasksToolsRequest(call = Some(Json.Obj())))))
        )
      )
    )
