package com.tjclp.fastmcp
package server

import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.{LoggingLevel, LoggingMessageNotificationParams, ProgressToken}
import com.tjclp.fastmcp.core.wire.{
  ClientCapabilities,
  CreateMessageRequestParams,
  CreateMessageResult,
  ElicitRequestParams,
  ElicitRequestUrlParams,
  ElicitResult,
  Implementation,
  ListRootsResult,
  NotificationMethods,
  ProgressNotificationParams
}
import com.tjclp.fastmcp.jsonrpc.{JsonRpcMessage, McpError}
import com.tjclp.fastmcp.server.router.Session

/** Platform-independent MCP request context handed to handlers as `Option[McpContext]`.
  *
  * In the native core there is no per-platform subclass: the context simply carries the request's
  * [[Session]], through which a handler can read the connection id and push server→client messages
  * (log notifications, progress) over the session's outbound channel. The transport drains that
  * channel to the wire.
  */
open class McpContext private[fastmcp] (
    private[fastmcp] val session: Option[Session] = None,
    private val clientInfoSnapshot: Option[Implementation] = None,
    private val clientCapabilitiesSnapshot: Option[ClientCapabilities] = None,
    private val requestMeta: Option[Map[String, Json]] = None,
    private val requestStateSnapshot: Option[String] = None
):

  /** The legacy connection/session id, or the ephemeral request id used by the modern transport. */
  def sessionId: Option[String] = session.map(_.sessionId)

  /** The client's declared identity from per-request metadata or legacy `initialize`, if known. */
  def getClientInfo: Option[Implementation] = clientInfoSnapshot

  /** The client's per-request capabilities, or the legacy initialized capabilities, if known. */
  def getClientCapabilities: Option[ClientCapabilities] = clientCapabilitiesSnapshot

  /** The request's complete `_meta` object. This preserves extension fields and the W3C
    * `traceparent`, `tracestate`, and `baggage` propagation keys defined by MCP 2026-07-28.
    */
  def getRequestMeta: Map[String, Json] = requestMeta.getOrElse(Map.empty)

  /** Read one request metadata value without interpreting extension-owned content. */
  def requestMetadata(key: String): Option[Json] = getRequestMeta.get(key)

  /** Opaque state returned with an earlier `input_required` result and echoed on this retry. */
  def getRequestState: Option[String] = requestStateSnapshot

  /** The request's `_meta.progressToken`, if the client supplied one on this request. Echo it back
    * via [[sendProgress]] so the client correlates progress notifications to its originating call;
    * the client drops progress whose token doesn't match what it sent.
    */
  def progressToken: Option[ProgressToken] =
    requestMeta.flatMap(_.get("progressToken")).flatMap(_.as[ProgressToken].toOption)

  /** Emit a `notifications/message` log to the client, honoring the request's modern `_meta` log
    * level or the legacy `logging/setLevel` threshold. Modern requests without an explicit level
    * never receive log notifications.
    */
  def sendLogMessage(
      level: LoggingLevel,
      data: Json,
      logger: Option[String] = None
  ): UIO[Unit] =
    session match
      case None => ZIO.unit
      case Some(s) =>
        s.currentRequestContext.flatMap {
          case Some(context) =>
            context.logLevel match
              case None => ZIO.unit
              case Some(min) if level.severity < min.severity => ZIO.unit
              case Some(_) => emitLog(s, level, data, logger)
          case None =>
            s.logLevel.flatMap {
              case Some(min) if level.severity < min.severity => ZIO.unit
              case _ => emitLog(s, level, data, logger)
            }
        }

  private def emitLog(
      session: Session,
      level: LoggingLevel,
      data: Json,
      logger: Option[String]
  ): UIO[Unit] =
    val params = LoggingMessageNotificationParams(level, data, logger)
    session.send(
      JsonRpcMessage.Notification(NotificationMethods.Message, params.toJsonAST.toOption)
    )

  /** Emit a `notifications/progress` update for the given progress token. No-op without a session.
    */
  def sendProgress(
      progressToken: ProgressToken,
      progress: Double,
      total: Option[Double] = None,
      message: Option[String] = None
  ): UIO[Unit] =
    session match
      case None => ZIO.unit
      case Some(s) =>
        val params = ProgressNotificationParams(progressToken, progress, total, message)
        s.send(JsonRpcMessage.Notification(NotificationMethods.Progress, params.toJsonAST.toOption))

  // --- additional client input: modern MRTR, with legacy server-request fallback ---

  /** Request additional client input. Modern calls produce an `InputRequiredResult` and consume
    * `inputResponses` on a retry of the original request; legacy calls use a correlated
    * server-initiated request on a session-bearing transport.
    */
  def sendRequest(
      method: String,
      params: Option[Json],
      timeout: Duration = McpContext.DefaultRequestTimeout,
      requestState: Option[String] = None
  ): IO[McpError, Json] =
    session match
      case None =>
        ZIO.fail(
          McpError.internalError(
            "server-initiated requests require a session-bearing transport (stdio or streamable HTTP)"
          )
        )
      case Some(s) =>
        s.currentRequestContext.flatMap {
          case None => s.sendRequest(method, params, timeout)
          case Some(context) =>
            for
              key <- s.nextInputRequestKey
              result <- context.inputResponses.get(key) match
                case Some(response) => ZIO.succeed(response)
                case None =>
                  val modernParams =
                    if method == "elicitation/create" then
                      params.map {
                        case Json.Obj(fields) =>
                          Json.Obj(fields.filterNot(_._1 == "elicitationId")*)
                        case other => other
                      }
                    else params
                  val request = Json.Obj(
                    "method" -> Json.Str(method),
                    "params" -> modernParams.getOrElse(Json.Obj())
                  )
                  ZIO.fail(McpError.inputRequired(key, request, requestState))
            yield result
        }

  /** `roots/list` — ask the client for its workspace roots. Requires the client to have declared
    * the `roots` capability.
    */
  def listRoots(
      timeout: Duration = McpContext.DefaultRequestTimeout
  ): IO[McpError, ListRootsResult] =
    requireCapability("roots", _.roots.isDefined) *>
      sendRequest("roots/list", None, timeout).flatMap(decodeResult[ListRootsResult]("roots/list"))

  /** `sampling/createMessage` — ask the client to sample its LLM. Requires the `sampling`
    * capability.
    */
  def createMessage(
      params: CreateMessageRequestParams,
      timeout: Duration = McpContext.DefaultRequestTimeout
  ): IO[McpError, CreateMessageResult] =
    requireCapability("sampling", _.sampling.isDefined) *>
      requireCapability(
        "sampling.tools",
        _.sampling.flatMap(_.tools).isDefined,
        Some(Json.Obj("sampling" -> Json.Obj("tools" -> Json.Obj())))
      ).when(params.tools.isDefined || params.toolChoice.isDefined).unit *>
      requireCapability(
        "sampling.context",
        _.sampling.flatMap(_.context).isDefined,
        Some(Json.Obj("sampling" -> Json.Obj("context" -> Json.Obj())))
      ).when(params.includeContext.exists(_ != "none")).unit *>
      sendRequest("sampling/createMessage", Some(encode(params)), timeout)
        .flatMap(decodeResult[CreateMessageResult]("sampling/createMessage"))

  /** `elicitation/create` (form mode) — ask the client to collect structured input from the user.
    * Requires the `elicitation` capability.
    */
  def elicit(
      params: ElicitRequestParams,
      timeout: Duration = McpContext.DefaultRequestTimeout
  ): IO[McpError, ElicitResult] =
    requireCapability("elicitation", _.elicitation.isDefined) *>
      sendRequest("elicitation/create", Some(encode(params)), timeout)
        .flatMap(decodeResult[ElicitResult]("elicitation/create"))

  /** `elicitation/create` URL mode. Modern MRTR omits the removed `elicitationId`; applications
    * that need cross-retry correlation carry an integrity-protected identifier in request state.
    * The optional field remains encoded only for the legacy adapter.
    */
  def elicitUrl(
      params: ElicitRequestUrlParams,
      timeout: Duration = McpContext.DefaultRequestTimeout
  ): IO[McpError, ElicitResult] =
    requireCapability(
      "elicitation.url",
      _.elicitation.flatMap(_.url).isDefined,
      Some(Json.Obj("elicitation" -> Json.Obj("url" -> Json.Obj())))
    ) *>
      sendRequest(
        "elicitation/create",
        Some(encode(params)),
        timeout,
        requestState = params.elicitationId
      )
        .flatMap(decodeResult[ElicitResult]("elicitation/create"))

  private def requireCapability(
      name: String,
      check: ClientCapabilities => Boolean,
      requiredCapabilities: Option[Json] = None
  ): IO[McpError, Unit] =
    if clientCapabilitiesSnapshot.exists(check) then ZIO.unit
    else
      session match
        case Some(s) =>
          s.currentRequestContext.flatMap {
            case Some(_) =>
              ZIO.fail(
                McpError.missingRequiredClientCapability(
                  requiredCapabilities.getOrElse(Json.Obj(name -> Json.Obj()))
                )
              )
            case None =>
              ZIO.fail(McpError.invalidRequest(s"client did not declare the '$name' capability"))
          }
        case None =>
          ZIO.fail(McpError.invalidRequest(s"client did not declare the '$name' capability"))

  private def encode[A: JsonEncoder](a: A): Json = a.toJsonAST.getOrElse(Json.Obj())

  private def decodeResult[A: JsonDecoder](method: String)(json: Json): IO[McpError, A] =
    ZIO
      .fromEither(json.as[A])
      .mapError(e => McpError.internalError(s"$method: malformed response: $e"))

object McpContext:

  /** Default timeout for legacy server-initiated requests. Modern MRTR returns immediately.
    */
  val DefaultRequestTimeout: Duration = 60.seconds

  /** Default empty context — used by macros / direct calls when no session is available. */
  def empty: McpContext = new McpContext

  /** Context bound to a request's session (used by the built-in handlers). The client info /
    * capabilities are snapshotted so handlers can read them synchronously.
    */
  def withSession(
      session: Session,
      clientInfo: Option[Implementation] = None,
      clientCapabilities: Option[ClientCapabilities] = None,
      requestMeta: Option[Map[String, Json]] = None,
      requestState: Option[String] = None
  ): McpContext =
    new McpContext(Some(session), clientInfo, clientCapabilities, requestMeta, requestState)
