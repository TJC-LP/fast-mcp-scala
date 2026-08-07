package com.tjclp.fastmcp.server.router

import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.core.wire.*
import com.tjclp.fastmcp.jsonrpc.*
import com.tjclp.fastmcp.jsonrpc.JsonRpcMessage.*
import com.tjclp.fastmcp.server.transport.HttpHeaderValidation

/** Canonical MCP request method names. */
object Methods:
  val ServerDiscover = "server/discover"
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
  val SubscriptionsListen = "subscriptions/listen"

/** The MCP dispatcher — the native-Scala replacement for the Java SDK's `McpAsyncServer` and the TS
  * SDK's `Server`.
  *
  * Built once per server start from an immutable handler map (M5 registers the built-ins). The
  * dispatcher:
  *   - routes each [[JsonRpcMessage]] to its registered handler, wrapping the request pipeline in
  *     the middleware chain (validation → tasks → handler → error mapping),
  *   - maps handler `McpError`s to JSON-RPC error responses; unknown methods → `-32601`,
  *   - forks each request so an incoming `notifications/cancelled` can interrupt it by id,
  *   - **derives [[ServerCapabilities]] from which handlers are registered** and renders separate
  *     modern-discovery and legacy-initialize capability shapes.
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
  *   whether legacy Tasks and the modern Tasks extension are advertised
  * @param resourcesSubscribe
  *   advertise legacy `resources.subscribe`
  * @param listChanged
  *   advertise `listChanged` on tools/resources/prompts
  */
final class McpRouter[R](
    serverInfo: Implementation,
    requestHandlers: Map[String, RequestHandler[R]],
    notificationHandlers: Map[String, NotificationHandler[R]],
    middlewares: List[Middleware[R]],
    hooks: ServerHooks[R],
    toolInputSchemas: Map[String, Json],
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

  /** The 2026 discovery surface advertises Tasks only through the official extension map. The
    * legacy initialize adapter continues to expose the former top-level field to old clients.
    */
  val modernCapabilities: ServerCapabilities =
    McpRouter.toModernCapabilities(capabilities, tasksEnabled)

  def hasModernMethod(method: String): Boolean =
    requestHandlers.contains(method) && !removedFromStateless.contains(method)

  def validateHttpHeaders(
      request: JsonRpcMessage.Request,
      header: String => Option[String]
  ): Either[McpError, Unit] =
    HttpHeaderValidation.validate(request, header, toolInputSchemas)

  def validateHttpMethod(
      method: String,
      header: String => Option[String]
  ): Either[McpError, Unit] =
    HttpHeaderValidation.validateMethod(method, header)

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
    RequestContext.declaredProtocolVersion(params) match
      case Some(_) => dispatchStateless(session, id, method, params)
      case None if modernOnlyMethods.contains(method) =>
        // Method identity beats session state: -32601 is the truthful answer whether or not the
        // legacy session initialized, mirroring removedFromStateless on the modern direction.
        ZIO.succeed(
          Some(Failure(Some(id), McpError.methodNotFound(method).toErrorObject))
        )
      case None =>
        session.isInitialized.flatMap { initialized =>
          // Compatibility adapter for MCP <= 2025-11-25.
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
          else dispatchInitialized(session, id, method, params, modern = false)
        }

  private val removedFromStateless: Set[String] = Set(
    Methods.Initialize,
    Methods.Ping,
    Methods.LoggingSetLevel,
    Methods.ResourcesSubscribe,
    Methods.ResourcesUnsubscribe,
    Tasks.MethodTasksList,
    Tasks.MethodTasksResult
  )

  /** Methods that exist only on the 2026-07-28 stateless path — the mirror of
    * [[removedFromStateless]]: legacy sessions get -32601, never a bogus success (server/discover)
    * or a misleading -32603 (subscriptions/listen).
    */
  private val modernOnlyMethods: Set[String] = Set(
    Methods.ServerDiscover,
    Methods.SubscriptionsListen,
    Tasks.MethodTasksUpdate
  )

  private val mrtrMethods: Set[String] = Set(
    Methods.ToolsCall,
    Methods.ResourcesRead,
    Methods.PromptsGet
  )

  private def dispatchStateless(
      session: Session,
      id: RequestId,
      method: String,
      params: Json
  ): URIO[R, Option[JsonRpcMessage]] =
    RequestContext.decode(params) match
      case Left(err) => ZIO.some(Failure(Some(id), err.toErrorObject))
      case Right(context) if context.protocolVersion != Protocol.LatestProtocolVersion =>
        ZIO.some(
          Failure(
            Some(id),
            McpError
              .unsupportedProtocolVersion(
                context.protocolVersion,
                List(Protocol.LatestProtocolVersion)
              )
              .toErrorObject
          )
        )
      case Right(_) if removedFromStateless.contains(method) =>
        ZIO.some(Failure(Some(id), McpError.methodNotFound(method).toErrorObject))
      case Right(context) =>
        val effectiveContext =
          if modernCapabilities.logging.isDefined then context else context.copy(logLevel = None)
        session.runWithRequest(id, effectiveContext)(
          dispatchInitialized(session, id, method, params, modern = true)
        )

  private def dispatchInitialized(
      session: Session,
      id: RequestId,
      method: String,
      params: Json,
      modern: Boolean
  ): URIO[R, Option[JsonRpcMessage]] =
    requestHandlers.get(method) match
      case None =>
        ZIO.succeed(Some(Failure(Some(id), McpError.methodNotFound(method).toErrorObject)))
      case Some(handler) =>
        val pipeline = Middleware.chain(middlewares, method, handler)
        for
          fiber <- pipeline(session, params).fork
          _ <- session.trackInflight(id, method, fiber)
          // Interrupt the handler explicitly when its request-scoped response stream closes. A
          // long-lived subscriptions/listen handler otherwise outlives the dispatch fiber and
          // leaves a stale registry entry behind.
          exit <- fiber.await
            .onInterrupt(fiber.interrupt.unit)
            .ensuring(session.clearInflight(id))
          resp <- exit match
            case Exit.Success(result) =>
              ZIO.succeed(Some(Success(id, WireMapping.completeResult(result, serverInfo, modern))))
            case Exit.Failure(cause) =>
              cause.failureOption match
                case Some(err)
                    if modern && mrtrMethods.contains(method) &&
                      McpError.inputRequiredResult(err).isDefined =>
                  ZIO.succeed(
                    Some(
                      Success(
                        id,
                        WireMapping.completeResult(
                          McpError.inputRequiredResult(err).get,
                          serverInfo,
                          modern = true
                        )
                      )
                    )
                  )
                case Some(err) if modern && McpError.inputRequiredResult(err).isDefined =>
                  val unsupported = McpError.internalError(
                    s"$method cannot return an input_required result"
                  )
                  hooks
                    .onError(method, unsupported, session)
                    .as(Some(Failure(Some(id), unsupported.toErrorObject)))
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

  def toModernCapabilities(
      capabilities: ServerCapabilities,
      tasksEnabled: Boolean
  ): ServerCapabilities =
    capabilities.copy(
      tasks = None,
      resources = capabilities.resources.map(_.copy(subscribe = None)),
      extensions =
        if tasksEnabled then
          Some(capabilities.extensions.getOrElse(Map.empty) + (Tasks.ExtensionId -> Json.Obj()))
        else capabilities.extensions
    )

  /** Derive the compatibility capability superset from registered methods plus settings. Modern
    * discovery transforms removed fields into their extension-era representation.
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
