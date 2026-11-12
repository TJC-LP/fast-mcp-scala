package com.tjclp.fastmcp
package server.transport.http

import zio.*
import zio.json.*
import zio.stream.*

import com.tjclp.fastmcp.core.{ErrorCodes, Protocol}
import com.tjclp.fastmcp.jsonrpc.{JsonRpcMessage, McpError, RequestId}
import com.tjclp.fastmcp.server.McpServerSettings
import com.tjclp.fastmcp.server.router.{McpRouter, RequestContext, RequestHeaders, Session}
import com.tjclp.fastmcp.server.transport.{
  HttpHeaderValidation,
  HttpRequestGuards,
  MessageLoop,
  ModernHttpValidation
}

/** The streamable-HTTP MCP semantics, written once against the platform-neutral [[HttpRequest]] /
  * [[HttpReply]] model: the legacy session store with its cap, eviction and idle sweeper, the rule
  * that only `initialize` may mint a session, request replies streamed as SSE with the request's
  * own server→client messages, the standalone GET push channel, DELETE termination, the 2026-07-28
  * stateless path, and the transport-level JSON-RPC error bodies (TS SDK parity).
  *
  * Every admission decision is taken by the shared, pure [[HttpRequestGuards]]; this class only
  * sequences them (the order is the one documented there) and renders the verdicts, so the
  * hardening contract on `HttpTransportBackend` is honoured identically by every backend built on
  * it.
  *
  * Backends are thin adapters: they turn their request type into an [[HttpRequest]], call [[post]]
  * / [[get]] / [[delete]] (or [[handle]] when they also delegate method/path routing), render the
  * [[HttpReply]], fork [[evictIdleSessions]] for the server's lifetime, and run
  * [[StreamableHttpHandler.validateSettings]] before binding.
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
    store: Option[Ref.Synchronized[Map[String, Session]]]
):
  import StreamableHttpHandler.*

  /** The MCP endpoint path, always with a leading slash (`/mcp` by default). */
  val endpoint: String = "/" + settings.httpEndpoint.stripPrefix("/")

  private val endpointNoSlash: String = endpoint.stripSuffix("/")

  /** SSE heartbeat interval handed to the backend with every stream (see [[HttpReply.Sse]]). */
  private val keepAlive: Option[Duration] = settings.keepAliveInterval.map(Duration.fromJava)

  /** `settings.contextHeaders` in matching form (lower-cased); empty for most servers. */
  private val contextHeaders: Set[String] = RequestHeaders.normalise(settings.contextHeaders)

  /** Headers-only verdict for backends that read the request body themselves: `Some` is the final
    * reply — `404` off the endpoint, `405` for an unsupported method, or a POST refused by the
    * header gates — and the body must not be read. `None` admits the request to [[handle]], which
    * re-runs the same (pure) gates, so the two can never disagree.
    */
  def admit(req: HttpRequest): Option[HttpReply] =
    if req.path.stripSuffix("/") != endpointNoSlash then Some(HttpReply.Empty(404))
    else
      req.method match
        case "POST" =>
          HttpRequestGuards
            .postGate(req.gateHeader, settings, requireSse = store.isDefined)
            .map(reject)
        case "GET" | "DELETE" => None
        case _ => Some(MethodNotAllowed)

  /** Full routing for backends without their own router, behind the first-party error boundary:
    * anything off the endpoint is `404`, methods other than POST/GET/DELETE are `405`.
    */
  def handle(req: HttpRequest): URIO[R, HttpReply] =
    guarded {
      if req.path.stripSuffix("/") != endpointNoSlash then ZIO.succeed(HttpReply.Empty(404))
      else
        req.method match
          case "POST" => post(req)
          case "GET" => get(req)
          case "DELETE" => delete(req)
          case _ => ZIO.succeed(MethodNotAllowed)
    }

  /** POST: one JSON-RPC frame.
    *
    * Stateless: a fresh ephemeral session per request, a single JSON reply (or `202` for a
    * notification). Streamable: no `mcp-session-id` header → must be the opening `initialize` (mint
    * a session, echo the id back); with the header → look up the durable session (`404` if unknown)
    * and dispatch in its context. A *request* gets an SSE response streaming the notifications and
    * sub-requests it emits followed by its final reply; notifications / client responses get `202`.
    *
    * The header gates run first, on headers only — before the body is read or any session state is
    * touched: 403 Host/Origin → 415 unless `Content-Type` is `application/json` → 413 when the
    * declared `Content-Length` exceeds `maxRequestBodyBytes` → 406 unless `Accept` allows
    * `application/json` (and `text/event-stream` too on the streamable transport).
    *
    * Note the gate does NOT check `mcp-protocol-version`: on POST the version travels in the
    * initialize payload (legacy) or is validated by [[ModernHttpValidation]] (modern, `-32022`).
    * The header itself is only enforced on the GET channel.
    */
  def post(req: HttpRequest): URIO[R, HttpReply] =
    HttpRequestGuards.postGate(req.gateHeader, settings, requireSse = store.isDefined) match
      case Some(rejection) => ZIO.succeed(reject(rejection))
      case None =>
        req.body.either.flatMap {
          case Left(err) =>
            ZIO.succeed(transportError(400, Option(err.getMessage).getOrElse("body read error")))
          case Right(body) if HttpRequestGuards.bodyTooLarge(body, settings) =>
            ZIO.succeed(reject(HttpRequestGuards.bodyTooLargeRejection(settings)))
          case Right(body) =>
            // Parse BEFORE touching the session store: a malformed or non-initialize body must
            // never mint a durable session (it used to — an unauthenticated memory leak).
            MessageLoop.parseFrame(body, router.limits) match
              case Left(parseFailure) =>
                ZIO.succeed(HttpReply.Json(400, parseFailure.toJson))
              case Right(message) =>
                // Bind the allow-listed headers of THIS request around its dispatch (a no-op with
                // an empty allow-list). The joined lookup, as for the gates: a header a client
                // sent and a proxy then appended to shows up as one combined value, not as the
                // client's.
                RequestHeaders.scoped(contextHeaders, req.gateHeader) {
                  val modern = ModernHttpValidation.isModern(req.header, message)
                  // `req.header` returns one occurrence of a header. A routing header sent twice
                  // is refused before any of them is read: always for the version header (its
                  // first value must not pick this request's path while another says otherwise),
                  // and for the 2026-07-28 header/body checks' other inputs on that path.
                  HttpHeaderValidation.repeatedHeader(req.headerNames()) match
                    case Some(name)
                        if modern || name == HttpHeaderValidation.ProtocolVersionHeader =>
                      ZIO.succeed(repeatedHeaderReply(message, name))
                    case _ =>
                      if modern then modernPost(req, message)
                      else
                        store match
                          case None => legacyStatelessDispatch(message)
                          case Some(sessions) => legacySessionDispatch(sessions, req, message)
                }
        }

  /** GET opens the server→client SSE channel for a session: each message offered to the session's
    * outbound queue is emitted as an SSE `message` event. The stream ends when the session is
    * terminated (queue shutdown) or the client disconnects. `405` on the stateless adapter and for
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

  /** DELETE terminates a session: drop it from the store and `Session.terminate` it (in-flight
    * requests interrupted, its tasks released, outbound queue shut down — which ends any open GET
    * SSE stream). `405` when delete is disallowed by settings, on the stateless adapter, or for
    * 2026-07-28 clients.
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
                  case Some(session) => session.terminate.as(HttpReply.Empty(200))
                  case None => ZIO.succeed(transportError(404, s"Session not found: $id"))
                }

  /** The idle-session sweeper for this handler's store — a no-op on the stateless adapter. Fork it
    * scoped to the listener's lifetime (never per request).
    */
  def evictIdleSessions: UIO[Unit] =
    store match
      case None => ZIO.unit
      case Some(sessions) => StreamableHttpHandler.evictIdleSessions(sessions, settings)

  // ---------------------------------------------------------------------------
  // Legacy stateless: request/response, no session state, no SSE.
  // ---------------------------------------------------------------------------

  private def legacyStatelessDispatch(message: JsonRpcMessage): URIO[R, HttpReply] =
    for
      session <- Session.make("stateless", supportsTasks = false)
      // Legacy stateless compatibility mode starts ready without a handshake.
      _ <- session.markInitialized
      reply <- router.dispatch(session, message)
    yield (message, reply) match
      case (_: JsonRpcMessage.Invalid, Some(r)) => HttpReply.Json(400, r.toJson)
      case (_, Some(r)) => HttpReply.Json(200, r.toJson)
      case (_, None) => HttpReply.Empty(202)

  // ---------------------------------------------------------------------------
  // Legacy streamable: durable sessions + SSE server-push + DELETE termination.
  // ---------------------------------------------------------------------------

  private def legacySessionDispatch(
      sessions: Ref.Synchronized[Map[String, Session]],
      req: HttpRequest,
      message: JsonRpcMessage
  ): URIO[R, HttpReply] =
    req.header(SessionIdHeader) match
      case Some(id) =>
        sessions.get.map(_.get(id)).flatMap {
          case None => ZIO.succeed(transportError(404, s"Session not found: $id"))
          case Some(session) => session.touch *> respondStreamable(session, message, isNew = false)
        }
      case None if MessageLoop.isInitialize(message) => mintSession(sessions, message)
      case None =>
        ZIO.succeed(
          transportError(
            400,
            s"$SessionIdHeader header is required (only initialize may open a session)"
          )
        )

  /** Mint a durable session for a header-less legacy `initialize`, bounded by
    * `settings.maxSessions`. The cap check, the idle/live snapshot, the victim choice and the
    * insert all run inside ONE `store.modifyZIO` on the `Ref.Synchronized` store, so concurrent
    * initializes are serialised at the cap: the store never exceeds it, and a newcomer is never
    * refused while an evictable session exists. When the store is full the longest-idle session
    * WITHOUT a live GET is evicted — terminated, so its in-flight requests and tasks are released
    * along with its queue — and the newcomer admitted. When every stored session holds a live GET,
    * the longest-idle of them is evicted instead, provided it has been idle longer than
    * `sessionIdleTimeout` (`HttpRequestGuards.pickEvictable` with the idle fallback): terminating
    * it shuts its outbound queue, which ends the GET stream — the only way a GET peer that vanished
    * without closing its connection ever frees its slot on a backend that cannot see the
    * disconnect, since nothing is written on a quiet stream unless `keepAliveInterval` is set. Only
    * when no session qualifies under either rule is the request refused with 503.
    */
  private def mintSession(
      sessions: Ref.Synchronized[Map[String, Session]],
      message: JsonRpcMessage
  ): URIO[R, HttpReply] =
    for
      id <- randomId
      session <- Session.makeProtocolSession(id)
      outcome <- sessions.modifyZIO { m =>
        if !HttpRequestGuards.capReached(m.size, settings) then
          ZIO.succeed((Right(None), m + (id -> session)))
        else
          ZIO
            .foreach(m.values.toList) { s =>
              (s.lastSeen zip s.hasActiveGet).map((seen, live) => (s.sessionId, seen, live))
            }
            .map { snapshot =>
              val now = java.lang.System.currentTimeMillis()
              val idleMs = settings.sessionIdleTimeout.map(_.toMillis)
              HttpRequestGuards.pickEvictable(snapshot, now, idleMs) match
                case Some(victim) => (Right(Some(m(victim))), (m - victim) + (id -> session))
                case None => (Left(()), m)
            }
      }
      resp <- outcome match
        case Right(None) => respondStreamable(session, message, isNew = true)
        case Right(Some(victim)) =>
          victim.hasActiveGet.flatMap { heldGet =>
            val detail =
              if heldGet then " (idle past sessionIdleTimeout; its GET stream is closed)" else ""
            ZIO.logWarning(
              s"Legacy session cap ${settings.maxSessions.getOrElse(0)} reached; evicted idle session ${victim.sessionId}$detail"
            )
          } *> victim.terminate *> respondStreamable(session, message, isNew = true)
        case Left(()) =>
          session.terminate.as(transportError(503, HttpRequestGuards.SessionLimitMessage))
    yield resp

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
        streamRequest(session, rpc, isNew, modern = None)
      case other =>
        router.dispatch(session, other).map {
          // Only structurally-Invalid frames produce a reply here (notifications and inbound
          // responses are fire-and-forget): answer 400 with the router's -32600 body.
          case Some(reply) => HttpReply.Json(400, reply.toJson, sessionHeader(session, isNew))
          case None => HttpReply.Empty(202)
        }

  /** Run a request's dispatch with its server→client messages routed to a per-request queue, and
    * answer from that queue: see [[firstFrameReply]].
    */
  private def streamRequest(
      session: Session,
      message: JsonRpcMessage.Request,
      isNew: Boolean,
      // `Some` for a 2026-07-28 request: its context, decoded once by the header validation and
      // handed to the router so the dispatch does not decode `_meta` a second time.
      modern: Option[RequestContext]
  ): URIO[R, HttpReply] =
    val dispatch = modern match
      case Some(context) => router.dispatchModern(session, message, context)
      case None => router.dispatch(session, message)
    for
      reqQueue <- Queue.unbounded[JsonRpcMessage]
      // `ensuring` (uninterruptible) offers the close sentinel after the dispatch delivers its
      // reply — or after it is cancelled / dies replyless — so a streamed reply always ends.
      dispatchFiber <- session
        .runWithSink(reqQueue)(dispatch)
        .flatMap(reply => ZIO.foreachDiscard(reply)(reqQueue.offer))
        .ensuring(reqQueue.offer(MessageLoop.CloseSentinel))
        .forkDaemon
      response <- firstFrameReply(
        reqQueue,
        message.id,
        session,
        isNew,
        dispatchFiber,
        modern.isDefined
      )
    yield response

  /** Choose the HTTP shape of a request's reply from its FIRST frame:
    *   - 2026-07-28 only: an error that maps to an HTTP status is plain JSON with that status;
    *   - the final reply itself (a call that emitted no progress, log or sub-request first, which
    *     is most calls): the whole body is known, so it is one fixed-length `text/event-stream`
    *     body ([[HttpReply.SseComplete]]) — nothing is streamed, pulled or finalized;
    *   - anything else opens the request's SSE stream, which carries that frame and the rest.
    *
    * How long the reply may wait for that frame:
    *   - legacy sessions: at most [[StreamableHttpHandler.LegacyHeadGrace]] (or one
    *     `keepAliveInterval`, if that is shorter). A legacy reply's `200` head used to be sent at
    *     once; a client that times out on response headers must not see a quiet call any
    *     differently, so the stream is opened after that grace whether or not anything arrived.
    *   - 2026-07-28: one `keepAliveInterval` when set, otherwise until the frame arrives (as it
    *     always has) — the first frame decides the HTTP status there.
    * A stream opened by the bound starts with a heartbeat when `keepAliveInterval` is set, so a
    * proxy in front sees bytes at that cadence during a long quiet call. The cost on the 2026-07-28
    * path is that a status-mapped error arriving after the stream opened is delivered in-band (HTTP
    * 200, the JSON-RPC error as the stream's final event) — the status line is gone by then.
    *
    * The bound is a timer fiber that OFFERS [[MessageLoop.KeepAliveSentinel]] into the request's
    * queue; the wait itself stays one plain `take`, so the timer can never discard a frame.
    */
  private def firstFrameReply(
      reqQueue: Queue[JsonRpcMessage],
      reqId: RequestId,
      session: Session,
      isNew: Boolean,
      dispatchFiber: Fiber.Runtime[?, ?],
      modern: Boolean
  ): UIO[HttpReply] =
    val headers = (if modern then ModernSseHeaders else Nil) ++ sessionHeader(session, isNew)
    val abandon = dispatchFiber.interrupt *> reqQueue.shutdown
    def sse(first: Option[JsonRpcMessage], openedByHeartbeat: Boolean): HttpReply =
      requestSse(reqQueue, reqId, headers, dispatchFiber, first, openedByHeartbeat)
    def shaped(first: JsonRpcMessage): UIO[HttpReply] =
      (if modern then ModernHttpValidation.errorStatus(first) else None) match
        case Some(status) => abandon.as(HttpReply.Json(status, first.toJson))
        // The dispatch has nothing more to say. Release the request's resources here (the
        // stream's finalizer does it for a streamed reply). The dispatch fiber is past its last
        // step, so it is signalled rather than awaited: waiting for its exit would park this
        // request behind a fiber that has nothing left to do.
        case None if isFinalReply(first, reqId) =>
          (signalInterrupt(dispatchFiber) *> reqQueue.shutdown)
            .as(HttpReply.SseComplete(headers, SseFrame.message(first)))
        case None => ZIO.succeed(sse(Some(first), openedByHeartbeat = false))
    val bound: Option[Duration] =
      if modern then keepAlive
      else Some(keepAlive.fold(LegacyHeadGrace)(_.min(LegacyHeadGrace)))
    bound match
      case None => reqQueue.take.onInterrupt(abandon).flatMap(shaped)
      case Some(interval) =>
        for
          // An offer into a queue that was shut down meanwhile interrupts this fiber by itself.
          timer <- (ZIO.sleep(interval) *> reqQueue.offer(MessageLoop.KeepAliveSentinel)).forkDaemon
          first <- reqQueue.take.onInterrupt(signalInterrupt(timer) *> abandon)
          reply <-
            if MessageLoop.isKeepAliveSentinel(first) then
              ZIO.succeed(sse(None, openedByHeartbeat = keepAlive.isDefined))
            else signalInterrupt(timer) *> shaped(first)
        yield reply

  private def requestSse(
      reqQueue: Queue[JsonRpcMessage],
      reqId: RequestId,
      headers: List[(String, String)],
      dispatchFiber: Fiber.Runtime[?, ?],
      first: Option[JsonRpcMessage],
      openedByHeartbeat: Boolean
  ): HttpReply =
    // `ZStream.succeed`, not `ZStream.fromIterable(List(first))`: the iterable form sizes its chunk
    // builder for a full default chunk (4096 slots, a 32 KiB array) to carry this one message — on
    // every streamed request. Besides the waste, an allocation that size takes Scala Native's
    // large-object path, which is not safe under concurrent allocation in 0.5.12.
    val queued = ZStream.fromQueue(reqQueue)
    val messages: ZStream[Any, Nothing, SseFrame] =
      first
        .fold(queued)(message => ZStream.succeed(message) ++ queued)
        .takeUntil(msg => isFinalReply(msg, reqId) || MessageLoop.isCloseSentinel(msg))
        // The opening timer's sentinel can land after the first frame was taken (it is
        // signalled, not awaited); it is not a message.
        .filter(msg => !MessageLoop.isCloseSentinel(msg) && !MessageLoop.isKeepAliveSentinel(msg))
        .map(SseFrame.message)
    val frames: ZStream[Any, Nothing, SseFrame] =
      (if openedByHeartbeat then ZStream.succeed(SseFrame.Ping) ++ messages else messages)
        // Runs on the normal end of stream too, not just client abort. Task fibers are safe: they
        // are forked under Session.runWithoutSink (TaskRouting), so they never hold this queue.
        .ensuring(dispatchFiber.interrupt *> reqQueue.shutdown)
    HttpReply.Sse(headers, frames, keepAlive)

  /** `400` with the header-mismatch error (`-32020`), addressed to the request when it has an id.
    */
  private def repeatedHeaderReply(message: JsonRpcMessage, header: String): HttpReply =
    val id = message match
      case rpc: JsonRpcMessage.Request => Some(rpc.id)
      case _ => None
    val failure: JsonRpcMessage =
      JsonRpcMessage.Failure(id, HttpHeaderValidation.repeatedHeaderError(header).toErrorObject)
    HttpReply.Json(400, failure.toJson)

  /** Dispatch a 2026-07-28 request without consulting or mutating the legacy session store. */
  private def modernPost(req: HttpRequest, message: JsonRpcMessage): URIO[R, HttpReply] =
    message match
      case rpc: JsonRpcMessage.Request =>
        ModernHttpValidation.validateRequestContext(router, rpc, req.header) match
          case Left((status, error)) =>
            val failure: JsonRpcMessage =
              JsonRpcMessage.Failure(Some(rpc.id), error.toErrorObject)
            ZIO.succeed(HttpReply.Json(status, failure.toJson))
          case Right(context) =>
            // Peer address of the request — the default owner key for bearer-task buckets
            // (`TaskOwnerKey.Transport`); behind a reverse proxy use `TaskOwnerKey.Custom`.
            Session
              .make(s"request-${rpc.id.toString}", clientKey = req.clientKey)
              .flatMap(session => streamRequest(session, rpc, isNew = false, Some(context)))
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
                HttpReply.Sse(Nil, frames, keepAlive)
            }
        }

  // --- Header validation. The decisions live in the shared [[HttpRequestGuards]] (one truth for
  // every backend); this class only renders the verdicts. `mcp-protocol-version` stays lenient
  // when absent so header-less clients still work while clearly-wrong headers are rejected per
  // spec. ---

  /** `mcp-protocol-version` header validation. Absent ⇒ assume
    * [[Protocol.DefaultNegotiatedProtocolVersion]] per the spec's backwards-compatibility rule
    * (clients predating the header speak 2025-03-26); present ⇒ must be a supported version.
    */
  private def protocolVersionOk(req: HttpRequest): Boolean =
    val declared =
      req.header("mcp-protocol-version").getOrElse(Protocol.DefaultNegotiatedProtocolVersion)
    Protocol.SupportedProtocolVersions.contains(declared)

  /** Reject (403) when DNS-rebinding protection is on and the request's Host/Origin isn't allowed
    * (full-origin match, see `HostGuard`).
    */
  private def hostError(req: HttpRequest): Option[HttpReply] =
    HttpRequestGuards.hostGate(req.gateHeader, settings).map(reject)

  /** GET guard (SSE channel): `Accept` (if present) must allow `text/event-stream`. */
  private def getHeaderError(req: HttpRequest): Option[HttpReply] =
    hostError(req).orElse {
      if !protocolVersionOk(req) then
        Some(transportError(400, "Unsupported mcp-protocol-version header"))
      else if !HttpRequestGuards.acceptsAny(
          req.header("accept"),
          List("text/event-stream", "text/*")
        )
      then Some(transportError(406, "Accept must allow text/event-stream"))
      else None
    }

private[fastmcp] object StreamableHttpHandler:

  /** Compatibility header for initialization-based Streamable HTTP revisions. */
  val SessionIdHeader = "mcp-session-id"

  private val MethodNotAllowed: HttpReply =
    HttpReply.Empty(405, List("allow" -> "POST, GET, DELETE"))

  /** Startup validation every backend runs before binding (`HttpRequestGuards.validateSettings`).
    */
  /** The longest a legacy (pre-2026-07-28) POST reply waits for its first frame before the SSE
    * response is opened anyway. Long enough that a call which answers promptly gets the
    * fixed-length reply; short enough that no client's response-header timeout can tell a quiet
    * call from the days when the head was sent at once.
    */
  private[fastmcp] val LegacyHeadGrace: Duration = 1.second

  /** Tell a fiber to stop without waiting for it to exit and without forking a fiber to wait. */
  private[fastmcp] def signalInterrupt(fiber: Fiber.Runtime[?, ?]): UIO[Unit] =
    ZIO.fiberIdWith(id => fiber.interruptAsFork(id))

  /** Sent with every 2026-07-28 SSE reply: tells a buffering proxy (nginx) to pass events on. */
  private val ModernSseHeaders: List[(String, String)] = List("X-Accel-Buffering" -> "no")

  def validateSettings(settings: McpServerSettings): IO[IllegalArgumentException, Unit] =
    ZIO
      .fromEither(HttpRequestGuards.validateSettings(settings))
      .mapError(msg => new IllegalArgumentException(s"Invalid HTTP settings: $msg"))

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
      Ref.Synchronized
        .make(Map.empty[String, Session])
        .map(store => new StreamableHttpHandler[R](router, settings, randomId, Some(store)))

  /** Periodically drop streamable sessions idle past `settings.sessionIdleTimeout` — abandoned
    * clients would otherwise grow the store forever. Sessions with a live GET stream are exempt
    * here (push-only consumers may never POST, and while capacity is free there is no reason to
    * close their stream); they become evictable only at the `maxSessions` cap, see `mintSession`.
    * Eviction calls `session.terminate`, which interrupts the session's in-flight requests,
    * releases (interrupts) its tasks and shuts the outbound queue down. The store is additionally
    * bounded by `settings.maxSessions` at mint time.
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
            _ <- ZIO.foreachDiscard(expired)(_.terminate)
          yield ()
        val interval = Duration.fromMillis(math.max(timeoutMs / 4, 1000L))
        sweep.repeat(Schedule.spaced(interval)).unit

  /** First-party error boundary: any non-interrupt Cause (a defect, or a failure no handler mapped)
    * becomes `onFailure` — a JSON-RPC 500 with a fixed message — and the full cause is logged
    * server-side only. Interrupts (client disconnect) pass through untouched. `effect` is by-name
    * and suspended so a synchronous throw while the handler effect is BUILT (the eager header
    * gates) is caught too. Generic in the reply type so the zio-http adapter can render its own
    * `Response` through the same boundary.
    */
  def guardedWith[R, A](onFailure: => A)(effect: => URIO[R, A]): URIO[R, A] =
    ZIO.suspendSucceed(effect).catchSomeCause {
      case cause if !cause.isInterruptedOnly =>
        ZIO.logWarningCause("HTTP handler failed", cause).as(onFailure)
    }

  /** [[guardedWith]] rendered as the neutral JSON-RPC 500 reply. */
  def guarded[R](effect: => URIO[R, HttpReply]): URIO[R, HttpReply] =
    guardedWith[R, HttpReply](transportError(500, HttpRequestGuards.InternalErrorMessage))(effect)

  /** A [[HttpRequestGuards.Rejection]] as its JSON-RPC error reply. */
  def reject(rejection: HttpRequestGuards.Rejection): HttpReply.Json =
    transportError(rejection.status, rejection.message)

  /** Transport-level rejection as a JSON-RPC error body (TS SDK parity — clients surface
    * `error.message` uniformly instead of sniffing text/plain bodies). `404` carries
    * [[ErrorCodes.SessionNotFound]]; every other status [[ErrorCodes.TransportError]].
    */
  def transportError(status: Int, message: String): HttpReply.Json =
    val code = if status == 404 then ErrorCodes.SessionNotFound else ErrorCodes.TransportError
    val failure: JsonRpcMessage =
      JsonRpcMessage.Failure(None, McpError(code, message).toErrorObject)
    HttpReply.Json(status, failure.toJson)
