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
    private val clientCapabilitiesSnapshot: Option[ClientCapabilities] = None
):

  /** The connection/session id, if this request arrived over a session-bearing transport. */
  def sessionId: Option[String] = session.map(_.sessionId)

  /** The client's declared identity from `initialize` (name/version/title), if known. */
  def getClientInfo: Option[Implementation] = clientInfoSnapshot

  /** The client's declared capabilities from `initialize`, if known. */
  def getClientCapabilities: Option[ClientCapabilities] = clientCapabilitiesSnapshot

  /** Emit a `notifications/message` log to the client, honoring the client's `logging/setLevel`
    * threshold. No-op if no session (e.g. a direct in-process call) or below the set level.
    */
  def sendLogMessage(
      level: LoggingLevel,
      data: Json,
      logger: Option[String] = None
  ): UIO[Unit] =
    session match
      case None => ZIO.unit
      case Some(s) =>
        s.logLevel.flatMap {
          case Some(min) if level.severity < min.severity => ZIO.unit
          case _ =>
            val params = LoggingMessageNotificationParams(level, data, logger)
            s.send(
              JsonRpcMessage.Notification(NotificationMethods.Message, params.toJsonAST.toOption)
            )
        }

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

  // --- server→client requests (correlated; need a session-bearing bidirectional transport) ---

  /** Raw server→client request: send `method` + `params` and await the client's response JSON.
    * Fails with [[McpError]] if there is no session — a direct in-process call, or a transport with
    * no server-push channel (stateless HTTP, or the JS HTTP transport).
    */
  def sendRequest(
      method: String,
      params: Option[Json],
      timeout: Duration = McpContext.DefaultRequestTimeout
  ): IO[McpError, Json] =
    session match
      case None =>
        ZIO.fail(
          McpError.internalError(
            "server-initiated requests require a session-bearing transport (stdio or streamable HTTP)"
          )
        )
      case Some(s) => s.sendRequest(method, params, timeout)

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

  private def requireCapability(
      name: String,
      check: ClientCapabilities => Boolean
  ): IO[McpError, Unit] =
    if clientCapabilitiesSnapshot.exists(check) then ZIO.unit
    else ZIO.fail(McpError.invalidRequest(s"client did not declare the '$name' capability"))

  private def encode[A: JsonEncoder](a: A): Json = a.toJsonAST.getOrElse(Json.Obj())

  private def decodeResult[A: JsonDecoder](method: String)(json: Json): IO[McpError, A] =
    ZIO
      .fromEither(json.as[A])
      .mapError(e => McpError.internalError(s"$method: malformed response: $e"))

object McpContext:

  /** Default timeout for server-initiated requests (`sendRequest`, `createMessage`, `elicit`,
    * `listRoots`).
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
      clientCapabilities: Option[ClientCapabilities] = None
  ): McpContext = new McpContext(Some(session), clientInfo, clientCapabilities)
