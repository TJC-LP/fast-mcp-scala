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
import com.tjclp.fastmcp.server.router.{RequestHeaders, Session, TaskHandoff}

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
    private val requestStateSnapshot: Option[String] = None,
    private val requestHeaders: Map[String, String] = Map.empty
):

  /** The legacy connection/session id, or a stand-in: the ephemeral request id used by the modern
    * transport, `"stateless"` on the legacy stateless adapter, `"stdio"` on stdio. Use
    * [[protocolSessionId]] for "the protocol session, if there is one".
    */
  def sessionId: Option[String] = session.map(_.sessionId)

  /** The MCP protocol session this request belongs to: the value of `Mcp-Session-Id` on a durable
    * legacy (pre-2026-07-28) streamable-HTTP session. `None` for a 2026-07-28 request, for legacy
    * stateless mode and for stdio — none of which has a protocol session, whatever [[sessionId]]
    * returns for them.
    */
  def protocolSessionId: Option[String] = session.filter(_.isProtocolSession).map(_.sessionId)

  /** The HTTP request headers the server allow-listed in `McpServerSettings.contextHeaders`, keyed
    * by lower-cased name. Empty unless the server lists names, and always empty on stdio.
    *
    * The values are those of the HTTP request that carried this call — read per request, so a
    * header that changes between two calls of one legacy session is seen with its new value. A
    * header sent more than once appears as its `", "`-joined value. A task keeps the headers of the
    * request that created it.
    *
    * These are whatever the peer sent. They are trustworthy only when a proxy in front of the
    * server sets or strips them; a client that reaches the server directly can send any header.
    */
  def headers: Map[String, String] = requestHeaders

  /** One allow-listed request header, matched case-insensitively. See [[headers]]. */
  def header(name: String): Option[String] =
    if requestHeaders.isEmpty then None
    else requestHeaders.get(RequestHeaders.normaliseName(name))

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
    *
    * Modern keys are opaque to clients and derived from the request content (see
    * [[com.tjclp.fastmcp.server.router.Session.nextInputRequestKey]]) — the derivation assumes the
    * handler builds the same params on every replay, which holds for derived encoders and fixed
    * construction. N parallel questions cost N round trips: the dispatcher surfaces one
    * `input_required` per trip and `zipPar` interrupts siblings after the first failure; unanswered
    * siblings re-ask on the next replay.
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
            // Strip BEFORE key derivation so the key hashes exactly what the client sees.
            val modernParams =
              if method == "elicitation/create" then
                params.map {
                  case Json.Obj(fields) =>
                    Json.Obj(fields.filterNot(_._1 == "elicitationId")*)
                  case other => other
                }
              else params
            for
              key <- s.nextInputRequestKey(method, modernParams)
              result <- context.inputResponses.get(key) match
                case Some(response) => ZIO.succeed(response)
                case None =>
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
        Some(Json.Obj("sampling" -> Json.Obj("tools" -> Json.Obj()))),
        legacyCheck = Some(_.sampling.isDefined)
      ).when(params.tools.isDefined || params.toolChoice.isDefined).unit *>
      requireCapability(
        "sampling.context",
        _.sampling.flatMap(_.context).isDefined,
        Some(Json.Obj("sampling" -> Json.Obj("context" -> Json.Obj()))),
        legacyCheck = Some(_.sampling.isDefined)
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
    * The optional field remains encoded only for the legacy adapter, which also keeps accepting a
    * bare `elicitation: {}` declaration (the `url` sub-capability is a 2026-07-28 shape).
    */
  def elicitUrl(
      params: ElicitRequestUrlParams,
      timeout: Duration = McpContext.DefaultRequestTimeout
  ): IO[McpError, ElicitResult] =
    requireCapability(
      "elicitation.url",
      _.elicitation.flatMap(_.url).isDefined,
      Some(Json.Obj("elicitation" -> Json.Obj("url" -> Json.Obj()))),
      legacyCheck = Some(_.elicitation.isDefined)
    ) *>
      sendRequest(
        "elicitation/create",
        Some(encode(params)),
        timeout,
        requestState = params.elicitationId
      )
        .flatMap(decodeResult[ElicitResult]("elicitation/create"))

  /** Hand the rest of this tool call over to a task (SEP-2663, "Composition with Multi Round-Trip
    * Requests"): the client gets a task handle as the reply to its request, and everything after
    * this call runs in the task.
    *
    * It is for a tool registered with `McpTool.withDeferredTask`. Such a tool starts each call as
    * an ordinary request, so what it asks its client before this call ([[elicit]],
    * [[createMessage]], [[listRoots]], [[sendRequest]]) is asked with an `input_required` result on
    * the request itself and no task exists yet. Calling this is what creates the task.
    *
    * Returns `true` when the handler is running as a task from here on, and `false` when the call
    * stays a request: the server has tasks off, the tool's task support is `forbidden`, the client
    * did not declare the tasks extension for a tool whose task support is `optional`, or the
    * session speaks 2025-11-25 and did not ask for a task. In a tool that was not registered with
    * `withDeferredTask` the task, if there is one, exists before the handler starts, and this only
    * reports it.
    *
    * The hand-off is a replay, like an MRTR retry: on the request this call ends the attempt, the
    * server creates the task, and the handler runs again from the start inside it with the same
    * arguments and `inputResponses` — the questions it asked are answered, this call returns
    * `true`, and the handler continues. So everything before this call runs once more in the task;
    * keep it to gathering input, and do the work that must happen once after it. Do not recover
    * from this call's failure (`catchAll`, `orElse`, `either`): on the request it is how the
    * hand-off is signalled.
    *
    * The failure can also be a real one: the task store refuses a task it has no room for
    * (`-32003`, or `-32602` for a request above `TaskSettings.maxRequestBytes`), and the client
    * gets that error.
    */
  def continueAsTask: IO[McpError, Boolean] = TaskHandoff.continue

  /** Fail unless the client declared the capability `check` looks for. The predicate is picked by
    * era first: 2026-07-28 sub-capability requirements must not retroactively break legacy clients
    * that declared only the base capability (pass the pre-2026 predicate as `legacyCheck`).
    * Failures are era-shaped too: -32021 with `requiredCapabilities` on the modern path, -32600 on
    * the legacy one. No session means legacy semantics (fixtures and direct in-process calls).
    */
  private def requireCapability(
      name: String,
      check: ClientCapabilities => Boolean,
      requiredCapabilities: Option[Json] = None,
      legacyCheck: Option[ClientCapabilities => Boolean] = None
  ): IO[McpError, Unit] =
    def evaluate(modern: Boolean): IO[McpError, Unit] =
      val effective = if modern then check else legacyCheck.getOrElse(check)
      if clientCapabilitiesSnapshot.exists(effective) then ZIO.unit
      else if modern then
        ZIO.fail(
          McpError.missingRequiredClientCapability(
            requiredCapabilities.getOrElse(Json.Obj(name -> Json.Obj()))
          )
        )
      else ZIO.fail(McpError.invalidRequest(s"client did not declare the '$name' capability"))

    session match
      case Some(s) => s.currentRequestContext.flatMap(ctx => evaluate(ctx.isDefined))
      case None => evaluate(modern = false)

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

  /** [[withSession]] plus the request's allow-listed HTTP headers (the built-in handlers' form). */
  private[fastmcp] def forRequest(
      session: Session,
      clientInfo: Option[Implementation],
      clientCapabilities: Option[ClientCapabilities],
      requestMeta: Option[Map[String, Json]],
      requestState: Option[String],
      requestHeaders: Map[String, String]
  ): McpContext =
    new McpContext(
      Some(session),
      clientInfo,
      clientCapabilities,
      requestMeta,
      requestState,
      requestHeaders
    )
