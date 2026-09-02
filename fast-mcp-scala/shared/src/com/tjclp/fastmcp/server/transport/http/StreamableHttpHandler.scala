package com.tjclp.fastmcp
package server.transport.http

import zio.*
import zio.json.*
import zio.stream.*

import com.tjclp.fastmcp.core.{ErrorCodes, Protocol}
import com.tjclp.fastmcp.jsonrpc.{JsonRpcMessage, McpError, RequestId}
import com.tjclp.fastmcp.server.McpServerSettings
import com.tjclp.fastmcp.server.router.{McpRouter, Session}
import com.tjclp.fastmcp.server.transport.{HostGuard, MessageLoop, ModernHttpValidation}

/** The streamable-HTTP MCP semantics, written once against the platform-neutral [[HttpRequest]] /
  * [[HttpReply]] model: the session store and its idle sweeper, the rule that only `initialize` may
  * mint a session, request replies streamed as SSE with the request's own server→client messages,
  * the standalone GET push channel, DELETE termination, the 2026-07-28 stateless path, and the
  * transport-level JSON-RPC error bodies (TS SDK parity).
  *
  * Backends are thin adapters: they turn their request type into an [[HttpRequest]], call [[post]]
  * / [[get]] / [[delete]] (or [[handle]] when they also delegate method/path routing), render the
  * [[HttpReply]], and fork [[evictIdleSessions]] for the server's lifetime.
  *
  * MCP 2026-07-28 always uses one ephemeral request context per POST and an optional request-scoped
  * SSE response. `settings.stateless` selects whether the older initialize/session/GET/DELETE
  * compatibility adapter is sessionless or durable; it does not make modern calls stateful.
  */
private[fastmcp] final class StreamableHttpHandler[R] private (
    router: McpRouter[R],
    settings: McpServerSettings,
    randomId: UIO[String],
    // `None` iff `settings.stateless`: the legacy adapter then has no durable sessions at all.
    store: Option[Ref[Map[String, Session]]]
):
  import StreamableHttpHandler.*

  /** The MCP endpoint path, always with a leading slash (`/mcp` by default). */
  val endpoint: String = "/" + settings.httpEndpoint.stripPrefix("/")

  private val allowedHosts: Set[String] = settings.allowedHosts.getOrElse(Set.empty)

  /** Full routing for backends without their own router: anything off the endpoint is `404`,
    * methods other than POST/GET/DELETE are `405`.
    */
  def handle(req: HttpRequest): URIO[R, HttpReply] =
    if req.path.stripSuffix("/") != endpoint.stripSuffix("/") then ZIO.succeed(HttpReply.Empty(404))
    else
      req.method match
        case "POST" => post(req)
        case "GET" => get(req)
        case "DELETE" => delete(req)
        case _ => ZIO.succeed(HttpReply.Empty(405, List("allow" -> "POST, GET, DELETE")))

  /** POST: one JSON-RPC frame.
    *
    * Stateless: a fresh ephemeral session per request, a single JSON reply (or `202` for a
    * notification). Streamable: no `mcp-session-id` header → must be the opening `initialize` (mint
    * a session, echo the id back); with the header → look up the durable session (`404` if unknown)
    * and dispatch in its context. A *request* gets an SSE response streaming the notifications and
    * sub-requests it emits followed by its final reply; notifications / client responses get `202`.
    */
  def post(req: HttpRequest): URIO[R, HttpReply] =
    store match
      case None =>
        postHeaderError(req, requireSse = false) match
          case Some(err) => ZIO.succeed(err)
          case None => statelessDispatch(req)
      case Some(sessions) =>
        postHeaderError(req, requireSse = true) match
          case Some(err) => ZIO.succeed(err)
          case None => streamablePostDispatch(sessions, req)

  /** GET opens the server→client SSE channel for a session: each message offered to the session's
    * outbound queue is emitted as an SSE `message` event. The stream ends when the session is
    * `DELETE`d (queue shutdown) or the client disconnects. `405` on the stateless adapter and for
    * 2026-07-28 clients (which have no session to stream).
    */
  def get(req: HttpRequest): UIO[HttpReply] =
    store match
      case None => ZIO.succeed(HttpReply.Empty(405))
      case Some(sessions) =>
        hostError(req) match
          case Some(err) => ZIO.succeed(err)
          case None if req.header("mcp-protocol-version").exists(Protocol.isStatelessVersion) =>
            ZIO.succeed(HttpReply.Empty(405))
          case None =>
            getHeaderError(req) match
              case Some(err) => ZIO.succeed(err)
              case None => streamableGetDispatch(sessions, req)

  /** DELETE terminates a session: drop it from the store and shut down its outbound queue (which
    * ends any open GET SSE stream). `405` when delete is disallowed by settings, on the stateless
    * adapter, or for 2026-07-28 clients.
    */
  def delete(req: HttpRequest): UIO[HttpReply] =
    store match
      case None => ZIO.succeed(HttpReply.Empty(405))
      case Some(sessions) =>
        hostError(req) match
          case Some(err) => ZIO.succeed(err)
          case None if req.header("mcp-protocol-version").exists(Protocol.isStatelessVersion) =>
            ZIO.succeed(HttpReply.Empty(405))
          case None if settings.disallowDelete => ZIO.succeed(HttpReply.Empty(405))
          case None =>
            req.header(SessionIdHeader) match
              case None => ZIO.succeed(HttpReply.Empty(405))
              case Some(id) =>
                sessions.modify(all => (all.get(id), all - id)).flatMap {
                  case Some(session) => session.outbound.shutdown.as(HttpReply.Empty(200))
                  case None => ZIO.succeed(transportError(404, s"Session not found: $id"))
                }

  /** The idle-session sweeper for this handler's store — a no-op on the stateless adapter. Fork it
    * scoped to the server's lifetime (never per request).
    */
  def evictIdleSessions: UIO[Unit] =
    store match
      case None => ZIO.unit
      case Some(sessions) => StreamableHttpHandler.evictIdleSessions(sessions, settings)

  // ---------------------------------------------------------------------------
  // Stateless: request/response, no session state, no SSE.
  // ---------------------------------------------------------------------------

  private def statelessDispatch(req: HttpRequest): URIO[R, HttpReply] =
    val effect =
      for
        body <- req.body.mapError(e => Option(e.getMessage).getOrElse("body read error"))
        resp <- MessageLoop.parseFrame(body) match
          case Left(parseFailure) =>
            ZIO.succeed(HttpReply.Json(400, parseFailure.toJson))
          case Right(message) =>
            if isModernRequest(req, message) then modernPost(req, message)
            else
              for
                session <- Session.make("stateless", supportsTasks = false)
                // Legacy stateless compatibility mode starts ready without a handshake.
                _ <- session.markInitialized
                reply <- router.dispatch(session, message)
              yield (message, reply) match
                case (_: JsonRpcMessage.Invalid, Some(r)) => HttpReply.Json(400, r.toJson)
                case (_, Some(r)) => HttpReply.Json(200, r.toJson)
                case (_, None) => HttpReply.Empty(202)
      yield resp
    effect.catchAll(msg => ZIO.succeed(transportError(400, msg)))

  // ---------------------------------------------------------------------------
  // Streamable: durable sessions + SSE server-push + DELETE termination.
  // ---------------------------------------------------------------------------

  private def streamablePostDispatch(
      sessions: Ref[Map[String, Session]],
      req: HttpRequest
  ): URIO[R, HttpReply] =
    req.body.either.flatMap {
      case Left(err) =>
        ZIO.succeed(transportError(400, Option(err.getMessage).getOrElse("body read error")))
      case Right(body) =>
        // Parse BEFORE touching the session store: a malformed or non-initialize body must never
        // mint a durable session (it used to — an unauthenticated memory leak).
        MessageLoop.parseFrame(body) match
          case Left(parseFailure) =>
            ZIO.succeed(HttpReply.Json(400, parseFailure.toJson))
          case Right(message) =>
            if isModernRequest(req, message) then modernPost(req, message)
            else
              req.header(SessionIdHeader) match
                case Some(id) =>
                  sessions.get.map(_.get(id)).flatMap {
                    case None =>
                      ZIO.succeed(transportError(404, s"Session not found: $id"))
                    case Some(session) =>
                      session.touch *> respondStreamable(session, message, isNew = false)
                  }
                case None if MessageLoop.isInitialize(message) =>
                  for
                    id <- randomId
                    session <- Session.make(id)
                    _ <- sessions.update(_ + (session.sessionId -> session))
                    resp <- respondStreamable(session, message, isNew = true)
                  yield resp
                case None =>
                  ZIO.succeed(
                    transportError(
                      400,
                      s"$SessionIdHeader header is required (only initialize may open a session)"
                    )
                  )
    }

  /** Dispatch one streamable POST frame. A *request* gets an SSE response that streams the
    * notifications and sub-requests it emits (progress, sampling, elicitation) followed by its
    * final JSON-RPC reply — one ordered stream, so a fire-and-forget notification can never race
    * the reply across two HTTP streams. Notifications / client responses produce no reply (`202`);
    * a parse error returns a single JSON error.
    */
  private def respondStreamable(
      session: Session,
      message: JsonRpcMessage,
      isNew: Boolean
  ): URIO[R, HttpReply] =
    message match
      case rpc: JsonRpcMessage.Request =>
        streamRequest(session, rpc, isNew, modern = false)
      case other =>
        router.dispatch(session, other).map {
          // Only structurally-Invalid frames produce a reply here (notifications and inbound
          // responses are fire-and-forget): answer 400 with the router's -32600 body.
          case Some(reply) => HttpReply.Json(400, reply.toJson, sessionHeader(session, isNew))
          case None => HttpReply.Empty(202)
        }

  /** Run a request's dispatch with its server→client messages routed to a per-request queue, and
    * return an SSE reply that emits each and ends right after the request's final reply.
    */
  private def streamRequest(
      session: Session,
      message: JsonRpcMessage.Request,
      isNew: Boolean,
      modern: Boolean
  ): URIO[R, HttpReply] =
    val reqId = message.id
    for
      reqQueue <- Queue.unbounded[JsonRpcMessage]
      // `ensuring` (uninterruptible) offers the close sentinel after the dispatch delivers its
      // reply — or after it is cancelled / dies replyless — so the SSE stream below always ends.
      dispatchFiber <- session
        .runWithSink(reqQueue)(router.dispatch(session, message))
        .flatMap(reply => ZIO.foreachDiscard(reply)(reqQueue.offer))
        .ensuring(reqQueue.offer(MessageLoop.CloseSentinel))
        .forkDaemon
      response <-
        if modern then
          reqQueue.take.onInterrupt(dispatchFiber.interrupt *> reqQueue.shutdown).flatMap { first =>
            ModernHttpValidation.errorStatus(first) match
              case Some(status) =>
                (dispatchFiber.interrupt *> reqQueue.shutdown)
                  .as(HttpReply.Json(status, first.toJson))
              case None =>
                ZIO.succeed(
                  requestSse(reqQueue, reqId, session, isNew, dispatchFiber, modern, List(first))
                )
          }
        else ZIO.succeed(requestSse(reqQueue, reqId, session, isNew, dispatchFiber, modern, Nil))
    yield response

  private def requestSse(
      reqQueue: Queue[JsonRpcMessage],
      reqId: RequestId,
      session: Session,
      isNew: Boolean,
      dispatchFiber: Fiber.Runtime[?, ?],
      modern: Boolean,
      initial: List[JsonRpcMessage]
  ): HttpReply =
    val messages = ZStream.fromIterable(initial) ++ ZStream.fromQueue(reqQueue)
    val frames: ZStream[Any, Nothing, SseFrame] =
      messages
        .takeUntil(msg => isFinalReply(msg, reqId) || MessageLoop.isCloseSentinel(msg))
        .filter(!MessageLoop.isCloseSentinel(_))
        .map(SseFrame.message)
        // Runs on the normal end of stream too, not just client abort. Task fibers are safe: they
        // are forked under Session.runWithoutSink (TaskRouting), so they never hold this queue.
        .ensuring(dispatchFiber.interrupt *> reqQueue.shutdown)
    val unbuffered = if modern then List("X-Accel-Buffering" -> "no") else Nil
    HttpReply.Sse(unbuffered ++ sessionHeader(session, isNew), withKeepAlive(frames))

  /** Dispatch a 2026-07-28 request without consulting or mutating the legacy session store. */
  private def modernPost(req: HttpRequest, message: JsonRpcMessage): URIO[R, HttpReply] =
    message match
      case rpc: JsonRpcMessage.Request =>
        ModernHttpValidation.validateRequest(router, rpc, req.header) match
          case Left((status, error)) =>
            val failure: JsonRpcMessage =
              JsonRpcMessage.Failure(Some(rpc.id), error.toErrorObject)
            ZIO.succeed(HttpReply.Json(status, failure.toJson))
          case Right(_) =>
            Session.make(s"request-${rpc.id.toString}").flatMap { session =>
              streamRequest(session, rpc, isNew = false, modern = true)
            }
      case _: JsonRpcMessage.Invalid =>
        val failure: JsonRpcMessage = JsonRpcMessage.Failure(
          None,
          McpError.invalidRequest("Invalid Request").toErrorObject
        )
        ZIO.succeed(HttpReply.Json(400, failure.toJson))
      case notification: JsonRpcMessage.Notification =>
        ModernHttpValidation.validateNotification(router, notification, req.header) match
          case Left((status, error)) =>
            val failure: JsonRpcMessage = JsonRpcMessage.Failure(None, error.toErrorObject)
            ZIO.succeed(HttpReply.Json(status, failure.toJson))
          case Right(_) =>
            Session
              .make("notification")
              .flatMap(session => router.dispatch(session, notification))
              .as(HttpReply.Empty(202))
      case _ =>
        val failure: JsonRpcMessage = JsonRpcMessage.Failure(
          None,
          McpError
            .invalidRequest("HTTP POST bodies must be JSON-RPC requests or notifications")
            .toErrorObject
        )
        ZIO.succeed(HttpReply.Json(400, failure.toJson))

  private def isModernRequest(req: HttpRequest, message: JsonRpcMessage): Boolean =
    ModernHttpValidation.isModern(req.header, message)

  /** Merge a heartbeat into an SSE stream so proxies / idle timeouts don't kill long-quiet
    * connections. Halts with the data stream.
    */
  private def withKeepAlive(
      frames: ZStream[Any, Nothing, SseFrame]
  ): ZStream[Any, Nothing, SseFrame] =
    settings.keepAliveInterval match
      case None => frames
      case Some(interval) =>
        val pings =
          ZStream.repeatWithSchedule(SseFrame.Ping, Schedule.spaced(Duration.fromJava(interval)))
        frames.mergeHaltLeft(pings)

  private def isFinalReply(message: JsonRpcMessage, reqId: RequestId): Boolean =
    message match
      case JsonRpcMessage.Success(id, _) => id == reqId
      case JsonRpcMessage.Failure(Some(id), _) => id == reqId
      case _ => false

  private def sessionHeader(session: Session, isNew: Boolean): List[(String, String)] =
    if isNew then List(SessionIdHeader -> session.sessionId) else Nil

  private def streamableGetDispatch(
      sessions: Ref[Map[String, Session]],
      req: HttpRequest
  ): UIO[HttpReply] =
    req.header(SessionIdHeader) match
      case None => ZIO.succeed(HttpReply.Empty(405))
      case Some(id) =>
        sessions.get.map(_.get(id)).flatMap {
          case None => ZIO.succeed(transportError(404, s"Session not found: $id"))
          case Some(session) =>
            session.touch *> session.tryAcquireGet.map {
              case false =>
                // A live GET already drains this session's outbound queue; a second stream would
                // round-robin-steal its messages (TS SDK answers 409 too).
                transportError(409, "A GET SSE stream is already open for this session")
              case true =>
                val frames = ZStream
                  .fromQueue(session.outbound)
                  .map(SseFrame.message)
                  .ensuring(session.releaseGet)
                HttpReply.Sse(Nil, withKeepAlive(frames))
            }
        }

  // --- Header validation (validate `Accept` + `mcp-protocol-version`; lenient when absent, so
  // header-less clients still work while clearly-wrong headers are rejected per spec). ---

  private def acceptsAny(req: HttpRequest, types: List[String]): Boolean =
    req.header("accept") match
      case None => true // absent Accept is treated as "accepts anything"
      case Some(a) =>
        val lower = a.toLowerCase
        lower.contains("*/*") || types.exists(lower.contains)

  /** `mcp-protocol-version` header validation. Absent ⇒ assume
    * [[Protocol.DefaultNegotiatedProtocolVersion]] per the spec's backwards-compatibility rule
    * (clients predating the header speak 2025-03-26); present ⇒ must be a supported version.
    */
  private def protocolVersionOk(req: HttpRequest): Boolean =
    val declared =
      req.header("mcp-protocol-version").getOrElse(Protocol.DefaultNegotiatedProtocolVersion)
    Protocol.SupportedProtocolVersions.contains(declared)

  /** Reject (403) when DNS-rebinding protection is on and the request's Host/Origin isn't allowed.
    */
  private def hostError(req: HttpRequest): Option[HttpReply] =
    if HostGuard.isAllowed(req.header("host"), req.header("origin"), allowedHosts) then None
    else Some(transportError(403, "Host/Origin not allowed (DNS-rebinding protection)"))

  /** POST guard: `Accept` (if present) must allow `application/json` — and on the streamable
    * transport (`requireSse`) `text/event-stream` too, since request replies stream as SSE (spec
    * requires clients to accept both).
    *
    * Note this guard does NOT check `mcp-protocol-version`: on POST the version travels in the
    * initialize payload (legacy) or is validated by [[ModernHttpValidation]] (modern, `-32022`).
    * The header itself is only enforced on the GET channel — see [[getHeaderError]].
    */
  private def postHeaderError(req: HttpRequest, requireSse: Boolean): Option[HttpReply] =
    hostError(req).orElse {
      if !acceptsAny(req, List("application/json", "application/*")) then
        Some(transportError(406, "Accept must allow application/json"))
      else if requireSse && !acceptsAny(req, List("text/event-stream", "text/*")) then
        Some(transportError(406, "Accept must allow text/event-stream"))
      else None
    }

  /** GET guard (SSE channel): `Accept` (if present) must allow `text/event-stream`. */
  private def getHeaderError(req: HttpRequest): Option[HttpReply] =
    hostError(req).orElse {
      if !protocolVersionOk(req) then
        Some(transportError(400, "Unsupported mcp-protocol-version header"))
      else if !acceptsAny(req, List("text/event-stream", "text/*")) then
        Some(transportError(406, "Accept must allow text/event-stream"))
      else None
    }

private[fastmcp] object StreamableHttpHandler:

  /** Compatibility header for initialization-based Streamable HTTP revisions. */
  val SessionIdHeader = "mcp-session-id"

  /** Build a handler, allocating the durable session store unless `settings.stateless`. `randomId`
    * is the platform CSPRNG (`TransportBackend.randomId()`): session ids are bearer handles and
    * must be unguessable.
    */
  def make[R](
      router: McpRouter[R],
      settings: McpServerSettings,
      randomId: UIO[String]
  ): UIO[StreamableHttpHandler[R]] =
    if settings.stateless then
      ZIO.succeed(new StreamableHttpHandler[R](router, settings, randomId, None))
    else
      Ref
        .make(Map.empty[String, Session])
        .map(store => new StreamableHttpHandler[R](router, settings, randomId, Some(store)))

  /** Periodically drop streamable sessions idle past `settings.sessionIdleTimeout` — abandoned
    * clients would otherwise grow the store forever. Sessions with a live GET stream are exempt
    * (push-only consumers may never POST). Eviction shuts the outbound queue down; the session's
    * tasks stay in the TaskManager until their own TTL.
    */
  def evictIdleSessions(
      store: Ref[Map[String, Session]],
      settings: McpServerSettings
  ): UIO[Unit] =
    settings.sessionIdleTimeout match
      case None => ZIO.unit
      case Some(timeout) =>
        val timeoutMs = timeout.toMillis
        val sweep =
          for
            now <- ZIO.succeed(java.lang.System.currentTimeMillis())
            all <- store.get
            expired <- ZIO.filter(all.values.toList) { s =>
              (s.lastSeen zip s.hasActiveGet).map((seen, live) => !live && now - seen > timeoutMs)
            }
            _ <- store.update(_ -- expired.map(_.sessionId))
            _ <- ZIO.foreachDiscard(expired)(_.outbound.shutdown)
          yield ()
        val interval = Duration.fromMillis(math.max(timeoutMs / 4, 1000L))
        sweep.repeat(Schedule.spaced(interval)).unit

  /** Transport-level rejection as a JSON-RPC error body (TS SDK parity — clients surface
    * `error.message` uniformly instead of sniffing text/plain bodies). `404` carries
    * [[ErrorCodes.SessionNotFound]]; every other status [[ErrorCodes.TransportError]].
    */
  def transportError(status: Int, message: String): HttpReply.Json =
    val code = if status == 404 then ErrorCodes.SessionNotFound else ErrorCodes.TransportError
    val failure: JsonRpcMessage =
      JsonRpcMessage.Failure(None, McpError(code, message).toErrorObject)
    HttpReply.Json(status, failure.toJson)
