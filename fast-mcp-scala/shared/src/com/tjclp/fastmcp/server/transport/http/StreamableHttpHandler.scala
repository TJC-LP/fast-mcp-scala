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
    store: Option[Ref.Synchronized[Map[String, Session]]],
    // How long a streamed reply may sit unstarted before its resources are released.
    unstartedReplyGrace: Duration
):
  import StreamableHttpHandler.*

  /** The MCP endpoint path, always with a leading slash (`/mcp` by default). A request is on the
    * endpoint when its path equals this string exactly (`HttpRequestGuards.pathMatches`).
    */
  val endpoint: String = HttpRequestGuards.endpointPath(settings)

  /** SSE heartbeat interval handed to the backend with every stream (see [[HttpReply.Sse]]). */
  private val keepAlive: Option[Duration] = settings.keepAliveInterval.map(Duration.fromJava)

  /** `settings.contextHeaders` in matching form (lower-cased); empty for most servers. */
  private val contextHeaders: Set[String] = RequestHeaders.normalise(settings.contextHeaders)

  /** Headers-only verdict for backends that read the request body themselves: `Some` is the final
    * reply — `404` off the endpoint, a refused `Host`/`Origin` (`403`), `405` for a method the
    * endpoint does not serve, or a POST refused by the header gates — and the body must not be
    * read. `None` admits the request to [[handle]], which re-runs the same (pure) gates, so the two
    * can never disagree.
    */
  def admit(req: HttpRequest): Option[HttpReply] =
    if !HttpRequestGuards.pathMatches(req.path, settings) then Some(HttpReply.Empty(404))
    else
      req.method match
        case "POST" =>
          HttpRequestGuards
            .postGate(req.gateHeader, settings, requireSse = store.isDefined)
            .map(reject)
        case _ => nonPostError(req)

  /** Full routing, behind the first-party error boundary: anything off the endpoint is `404` (an
    * exact path match, see [[endpoint]]); on it, every method meets the Host/Origin guard first and
    * a method the endpoint does not serve is then `405` with an `Allow` header.
    */
  def handle(req: HttpRequest): URIO[R, HttpReply] =
    guarded {
      if !HttpRequestGuards.pathMatches(req.path, settings) then ZIO.succeed(HttpReply.Empty(404))
      else
        req.method match
          case "POST" => post(req)
          case "GET" => get(req)
          case "DELETE" => delete(req)
          // Never admitted: `nonPostGate` refuses every method but GET and DELETE.
          case _ => ZIO.succeed(nonPostError(req).getOrElse(HttpReply.Empty(405)))
    }

  /** The verdict of `HttpRequestGuards.nonPostGate` on a request that is not a POST, rendered: 403
    * Host/Origin, then 405 for a method this endpoint does not serve in this mode (GET and DELETE
    * exist only on the durable legacy adapter; DELETE not with `disallowDelete`) or that MCP
    * 2026-07-28 does not use. `None` admits a GET or DELETE.
    */
  private def nonPostError(req: HttpRequest): Option[HttpReply] =
    HttpRequestGuards
      .nonPostGate(req.method, req.gateHeader, settings, pushChannel = true)
      .map(rejection => rejectFor(req.method, rejection))

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
    * terminated (queue shutdown) or the client disconnects.
    *
    * In order: `403` Host/Origin → `405` on the stateless adapter and for 2026-07-28 clients
    * (neither has a session to stream) → `400` unsupported `mcp-protocol-version` → `406` unless
    * `Accept` allows `text/event-stream` → `400` without an `mcp-session-id` → `404` unknown
    * session → `409` when the session's stream is already open.
    */
  def get(req: HttpRequest): UIO[HttpReply] =
    (nonPostError(req.copy(method = "GET")), store) match
      case (Some(err), _) => ZIO.succeed(err)
      // Unreachable: without a store `nonPostGate` has already refused the method.
      case (None, None) => ZIO.succeed(HttpReply.Empty(405))
      case (None, Some(sessions)) =>
        getHeaderError(req) match
          case Some(err) => ZIO.succeed(err)
          case None => streamableGetDispatch(sessions, req)

  /** DELETE terminates a session: drop it from the store and `Session.terminate` it (in-flight
    * requests interrupted, its tasks released, outbound queue shut down — which ends any open GET
    * SSE stream).
    *
    * In order: `403` Host/Origin → `405` when delete is disallowed by settings, on the stateless
    * adapter, or for 2026-07-28 clients → `400` without an `mcp-session-id` → `404` unknown
    * session.
    */
  def delete(req: HttpRequest): UIO[HttpReply] =
    (nonPostError(req.copy(method = "DELETE")), store) match
      case (Some(err), _) => ZIO.succeed(err)
      // Unreachable: without a store `nonPostGate` has already refused the method.
      case (None, None) => ZIO.succeed(HttpReply.Empty(405))
      case (None, Some(sessions)) =>
        HttpRequestGuards.sessionId(req.gateHeader) match
          case None => ZIO.succeed(reject(HttpRequestGuards.SessionRequired))
          case Some(id) =>
            // One uninterruptible step: a session that left the store can no longer be reached
            // by id, so a DELETE whose client goes away mid-way must still terminate it.
            sessions
              .modify(all => (all.get(id), all - id))
              .flatMap {
                case Some(session) => session.terminate.as(HttpReply.Empty(200))
                case None => ZIO.succeed(transportError(404, s"Session not found: $id"))
              }
              .uninterruptible

  /** The idle-session sweeper for this handler's store — a no-op on the stateless adapter. Fork it
    * scoped to the listener's lifetime (never per request).
    */
  def evictIdleSessions: UIO[Unit] =
    store match
      case None => ZIO.unit
      case Some(sessions) => StreamableHttpHandler.evictIdleSessions(sessions, settings)

  /** The stored legacy session with this id, if any (test seam). */
  private[fastmcp] def session(id: String): UIO[Option[Session]] =
    store match
      case None => ZIO.none
      case Some(sessions) => sessions.get.map(_.get(id))

  // ---------------------------------------------------------------------------
  // Legacy stateless: request/response, no session state, no SSE.
  // ---------------------------------------------------------------------------

  private def legacyStatelessDispatch(message: JsonRpcMessage): URIO[R, HttpReply] =
    for
      session <- Session.makeRequestScoped("stateless", supportsTasks = false)
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
    // An empty header names no session: it is treated as absent, not looked up.
    HttpRequestGuards.sessionId(req.gateHeader) match
      case Some(id) =>
        sessions.get.map(_.get(id)).flatMap {
          case None => ZIO.succeed(transportError(404, s"Session not found: $id"))
          case Some(session) => session.touch *> respondStreamable(session, message, isNew = false)
        }
      case None =>
        message match
          case rpc: JsonRpcMessage.Request if MessageLoop.isInitialize(rpc) =>
            mintSession(sessions, req, rpc)
          case _ =>
            ZIO.succeed(
              transportError(
                400,
                s"$SessionIdHeader header is required (only initialize may open a session)"
              )
            )

  /** Answer a header-less legacy `initialize`, and store a durable session for it only once it has
    * succeeded.
    *
    * The `initialize` is dispatched on a session that is not in the store. A failed one — params
    * that do not decode, or `clientInfo` / `capabilities` over the retention budget
    * (`ClientRetention`, `-32602`) — is answered without an `mcp-session-id` and leaves the store
    * exactly as it was: no slot taken, nobody evicted. A successful one goes through [[admit]]; if
    * the store has no room for it the answer is 503 and the session is discarded.
    *
    * The reply has the shape every other request's reply has: the handler's frames as one
    * `text/event-stream` body. It is complete before the head is sent, since the head carries the
    * session id and that exists only if `initialize` succeeded.
    *
    * Nothing here can leave a session that only this request knows of behind:
    *   - the dispatch runs on this request's own fiber, so a client that goes away stops it, and
    *     the session — stored by then or not — is dropped and terminated (`onInterrupt`): the
    *     client never learned its id;
    *   - the store update and the terminations it calls for are one uninterruptible step
    *     ([[answerInitialize]]). Once a victim has left the store nothing can reach it by id — not
    *     DELETE, not the idle sweeper — so an `initialize` whose client goes away right then must
    *     still terminate it, or its in-flight requests and tasks would never be released;
    *   - an interruption that arrives during that step is taken at an explicit interruption point
    *     right after it, still within `onInterrupt`'s reach. Without one it would be taken only
    *     after this method had returned: ZIO does not interrupt a fiber that leaves one
    *     uninterruptible region straight into another, and `onInterrupt` is such a region. The
    *     session would then sit in the store, under an id nobody was ever told, until the idle
    *     sweeper or a cap eviction found it.
    */
  private def mintSession(
      sessions: Ref.Synchronized[Map[String, Session]],
      req: HttpRequest,
      message: JsonRpcMessage.Request
  ): URIO[R, HttpReply] =
    for
      id <- randomId
      // The peer address counts this client's sessions at the cap (`admit`); the capacity bounds
      // what the session buffers for a GET stream the client may never read.
      session <- Session.makeProtocolSession(
        id,
        req.clientKey,
        settings.limits.maxOutboundMessages
      )
      // A client that goes away before the reply is built never learns the id: drop the session.
      reply <- ZIO.uninterruptibleMask { restore =>
        restore(answerInitialize(sessions, session, message))
          // The interruption point: entering an interruptible region interrupts a fiber whose
          // interruption is pending.
          .flatMap(reply => restore(ZIO.succeed(reply)))
          .onInterrupt(sessions.update(_ - id) *> session.terminate)
      }
    yield reply

  private def answerInitialize(
      sessions: Ref.Synchronized[Map[String, Session]],
      session: Session,
      message: JsonRpcMessage.Request
  ): URIO[R, HttpReply] =
    for
      // Anything the handler chain sends while it runs rides this reply, ahead of the result,
      // exactly as on a stored session's request.
      sink <- Queue.unbounded[JsonRpcMessage]
      answer <- session.runWithSink(sink)(router.dispatch(session, message))
      sent <- sink.takeAll <* sink.shutdown
      reply <- answer match
        case Some(_: JsonRpcMessage.Success) =>
          // Uninterruptible, from the store update to the last termination it calls for: once the
          // store has swapped a session out for this one, that session must be terminated, or its
          // requests, tasks and GET stream outlive its slot; and a newcomer the store refused is
          // terminated before anything can interrupt this request.
          admit(sessions, session).flatMap {
            case Left(()) =>
              session.terminate.as(transportError(503, HttpRequestGuards.SessionLimitMessage))
            case Right(evicted) =>
              ZIO
                .foreachDiscard(evicted) { victim =>
                  victim.hasActiveGet.flatMap { heldGet =>
                    val detail = if heldGet then " (its GET stream is closed)" else ""
                    ZIO.logWarning(
                      s"Legacy session cap ${settings.maxSessions.getOrElse(0)} reached; evicted session ${victim.sessionId}$detail"
                    )
                  } *> victim.terminate
                }
                .as(initializeReply(sent, answer, sessionHeader(session, isNew = true)))
          }.uninterruptible
        case _ => session.terminate.as(initializeReply(sent, answer, Nil))
    yield reply

  /** Put a freshly initialized session in the store, bounded by `settings.maxSessions`. The cap
    * check, the snapshot of every stored session, the choice of the session that makes room and the
    * insert all run inside ONE `modifyZIO` on the `Ref.Synchronized` store, so concurrent
    * initializes are serialised at the cap: the store never exceeds it, and a newcomer is never
    * refused while a session can make room for it.
    *
    * `Right(None)`: stored, there was room. `Right(Some(victim))`: stored in the place of `victim`,
    * which the caller terminates — its in-flight requests and tasks are released along with its
    * queue, and shutting the queue ends its GET stream, the only way a GET peer that vanished
    * without closing its connection ever frees its slot on a backend that cannot see the
    * disconnect. `Left`: the store is full and nobody gives a session up (503).
    *
    * Which session makes room is `HttpRequestGuards.pickVictim`'s decision: a client holding more
    * sessions than the newcomer's client gives one up whatever state its sessions are in; among
    * clients holding their share, the longest-idle session without a live GET, else the
    * longest-idle GET holder idle past `sessionIdleTimeout`.
    *
    * Call it from an uninterruptible region together with the termination of what it returns (see
    * [[answerInitialize]]).
    */
  private def admit(
      sessions: Ref.Synchronized[Map[String, Session]],
      session: Session
  ): UIO[Either[Unit, Option[Session]]] =
    val id = session.sessionId
    sessions.modifyZIO { m =>
      if !HttpRequestGuards.capReached(m.size, settings) then
        ZIO.succeed((Right(None), m + (id -> session)))
      else
        ZIO
          .foreach(m.values.toList) { s =>
            (s.lastSeen zip s.hasActiveGet).map { (seen, live) =>
              HttpRequestGuards.StoredSession(s.sessionId, seen, live, s.clientKey)
            }
          }
          .map { snapshot =>
            val now = Session.monotonicMs()
            val idleMs = settings.sessionIdleTimeout.map(_.toMillis)
            HttpRequestGuards.pickVictim(snapshot, session.clientKey, now, idleMs) match
              case Some(victim) => (Right(Some(m(victim))), (m - victim) + (id -> session))
              case None => (Left(()), m)
          }
    }

  /** The reply to a header-less `initialize`: what the handler chain sent, then its answer, as one
    * fixed-length `text/event-stream` body when that is a single frame (which it is unless a
    * middleware sent something first).
    *
    * The several-frame form is a stream over frames that already exist: it holds no dispatch fiber,
    * no queue and no GET slot, so there is nothing for it to give back when it is never run
    * (`abandon` does nothing, and it needs no [[ownedSse]] watchdog).
    */
  private def initializeReply(
      sent: Chunk[JsonRpcMessage],
      answer: Option[JsonRpcMessage],
      headers: List[(String, String)]
  ): HttpReply =
    val frames = (sent ++ Chunk.fromIterable(answer)).map(SseFrame.message)
    if frames.size == 1 then HttpReply.SseComplete(headers, frames(0))
    else HttpReply.Sse(headers, ZStream.fromChunk(frames), keepAlive)(ZIO.unit)

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
    *
    * The dispatch runs on a fiber of its own that nothing else supervises, so from the fork onwards
    * something must always be answerable for stopping it and shutting its queue down. That is this
    * region until the reply is handed back, and the reply itself afterwards:
    *   - the region is uninterruptible except while it waits for the first frame, and any way out
    *     of it other than a reply — the client going away during that wait, a defect — stops the
    *     dispatch (`onError`);
    *   - a reply that is complete ([[HttpReply.SseComplete]], a status-mapped JSON error) has
    *     already released both when it is returned;
    *   - a streamed reply ([[HttpReply.Sse]]) carries them on, see [[ownedSse]].
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
    ZIO.uninterruptibleMask { restore =>
      for
        reqQueue <- Queue.unbounded[JsonRpcMessage]
        // `ensuring` (uninterruptible) offers the close sentinel after the dispatch delivers its
        // reply — or after it is cancelled / dies replyless — so a streamed reply always ends.
        //
        // A fork inherits the region it is made in, so the fiber starts uninterruptible and only
        // the dispatch itself is made interruptible. An interrupt that reaches the fiber before it
        // has run a step therefore lands inside the `ensuring`, and the sentinel is still offered.
        dispatchFiber <- session
          .runWithSink(reqQueue)(dispatch)
          .flatMap(reply => ZIO.foreachDiscard(reply)(reqQueue.offer))
          .interruptible
          .ensuring(reqQueue.offer(MessageLoop.CloseSentinel))
          .forkDaemon
        response <- firstFrameReply(
          restore,
          reqQueue,
          message.id,
          session,
          isNew,
          dispatchFiber,
          modern.isDefined
        ).onError(_ => dispatchFiber.interrupt *> reqQueue.shutdown)
      yield response
    }

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
    *
    * Runs inside [[streamRequest]]'s uninterruptible region: only the wait for the first frame is
    * interruptible (`restore`), and the caller stops the dispatch if this ends without a reply.
    */
  private def firstFrameReply(
      restore: ZIO.InterruptibilityRestorer,
      reqQueue: Queue[JsonRpcMessage],
      reqId: RequestId,
      session: Session,
      isNew: Boolean,
      dispatchFiber: Fiber.Runtime[?, ?],
      modern: Boolean
  ): UIO[HttpReply] =
    val headers = (if modern then ModernSseHeaders else Nil) ++ sessionHeader(session, isNew)
    // The dispatch has nothing more to say once its reply is the first frame. It is past its last
    // step, so it is signalled rather than awaited: waiting for its exit would park this request
    // (uninterruptibly, here) behind a fiber that has nothing left to do.
    val done = signalInterrupt(dispatchFiber) *> reqQueue.shutdown
    def sse(first: Option[JsonRpcMessage], openedByHeartbeat: Boolean): UIO[HttpReply] =
      requestSse(reqQueue, reqId, headers, dispatchFiber, first, openedByHeartbeat)
    def shaped(first: JsonRpcMessage): UIO[HttpReply] =
      (if modern then ModernHttpValidation.errorStatus(first) else None) match
        // Both complete replies release the request's resources here; a streamed reply carries
        // them on (`ownedSse`).
        case Some(status) => done.as(HttpReply.Json(status, first.toJson))
        case None if isFinalReply(first, reqId) =>
          done.as(HttpReply.SseComplete(headers, SseFrame.message(first)))
        case None => sse(Some(first), openedByHeartbeat = false)
    val bound: Option[Duration] =
      if modern then keepAlive
      else Some(keepAlive.fold(LegacyHeadGrace)(_.min(LegacyHeadGrace)))
    bound match
      case None => restore(reqQueue.take).flatMap(shaped)
      case Some(interval) =>
        for
          // An offer into a queue that was shut down meanwhile interrupts this fiber by itself.
          // `interruptible` for the same reason as the dispatch fiber: the timer must be stoppable.
          timer <- (ZIO.sleep(interval) *> reqQueue.offer(
            MessageLoop.KeepAliveSentinel
          )).interruptible.forkDaemon
          first <- restore(reqQueue.take).onInterrupt(signalInterrupt(timer))
          reply <-
            if MessageLoop.isKeepAliveSentinel(first) then
              sse(None, openedByHeartbeat = keepAlive.isDefined)
            else signalInterrupt(timer) *> shaped(first)
        yield reply

  private def requestSse(
      reqQueue: Queue[JsonRpcMessage],
      reqId: RequestId,
      headers: List[(String, String)],
      dispatchFiber: Fiber.Runtime[?, ?],
      first: Option[JsonRpcMessage],
      openedByHeartbeat: Boolean
  ): UIO[HttpReply] =
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
      if openedByHeartbeat then ZStream.succeed(SseFrame.Ping) ++ messages else messages
    // The release runs on the normal end of stream too, not just client abort. Task fibers are
    // safe: they are forked under Session.runWithoutSink (TaskRouting), so they never hold this
    // queue.
    ownedSse(headers, frames, dispatchFiber.interrupt *> reqQueue.shutdown)

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
              .makeRequestScoped(s"request-${rpc.id.toString}", clientKey = req.clientKey)
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
              .makeRequestScoped("notification")
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
    HttpRequestGuards.sessionId(req.gateHeader) match
      case None => ZIO.succeed(reject(HttpRequestGuards.SessionRequired))
      case Some(id) =>
        sessions.get.map(_.get(id)).flatMap {
          case None => ZIO.succeed(transportError(404, s"Session not found: $id"))
          case Some(session) =>
            // Taking the slot and handing it to the reply are one uninterruptible step, so the
            // slot is never held by nobody; from then on the reply gives it back ([[ownedSse]])
            // whether or not its stream is ever run.
            (session.touch *> session.tryAcquireGet.flatMap {
              case false =>
                // A live GET already drains this session's outbound queue; a second stream would
                // round-robin-steal its messages (TS SDK answers 409 too).
                ZIO.succeed(
                  transportError(409, "A GET SSE stream is already open for this session")
                )
              case true =>
                val frames = ZStream.fromQueue(session.outbound).map(SseFrame.message)
                ownedSse(Nil, frames, session.releaseGet)
            }).uninterruptible
        }

  /** A streamed reply that answers for `release` from the moment it exists, whether or not a
    * backend ever runs its stream. `release` runs exactly once, by whichever of these is first:
    *   - the stream ending or being interrupted, once it was started — the ordinary case;
    *   - [[HttpReply.Sse.abandon]], called by a backend that will not run the stream;
    *   - a watchdog, when the stream has not been started `unstartedReplyGrace` after the reply was
    *     built (counted in ticks, so time the process spent frozen does not count). A backend pulls
    *     a reply's stream as soon as it has written the response head, so a reply only gets that
    *     old when nothing is going to run it: the client left as the head went out, or the request
    *     was interrupted between this handler and the backend.
    * A stream started only after its reply was released that way ends at once, empty: it must not
    * read a queue (or hold a GET slot) that is no longer its own.
    *
    * The watchdog is one sleeping fiber per STREAMED reply, stopped at the stream's first pull; a
    * reply that is complete at its first frame never builds a stream and never gets here. Call it
    * from an uninterruptible region, so that the reply cannot be lost between being built and being
    * returned.
    */
  private def ownedSse(
      headers: List[(String, String)],
      frames: ZStream[Any, Nothing, SseFrame],
      release: UIO[Unit]
  ): UIO[HttpReply.Sse] =
    ZIO.suspendSucceed {
      val state = new java.util.concurrent.atomic.AtomicInteger(ReplyPending)
      val giveUp: UIO[Unit] =
        ZIO.suspendSucceed {
          if state.compareAndSet(ReplyPending, ReplyReleased) then release else ZIO.unit
        }
      // The grace is counted in short ticks, not waited out as one deadline. A process that is
      // frozen for longer than the grace (a stop-the-world pause, a suspended VM, a paused
      // container) finds a single deadline already passed when it runs again, and would release a
      // reply its backend is about to stream: the client then gets a 200 with an empty body. A
      // freeze of any length uses up one tick, and the rest take their real time, which is far
      // more than a backend needs to start the stream.
      // Interruptible only while it sleeps: once it has claimed the reply it finishes the release.
      val tick = unstartedReplyGrace.dividedBy(UnstartedReplyTicks.toLong).max(1.millis)
      val grace = ZIO.sleep(tick).repeatN(UnstartedReplyTicks - 1)
      (grace.interruptible *> giveUp).uninterruptible.forkDaemon.map { watchdog =>
        val stopWatchdog = signalInterrupt(watchdog)
        // Decided at the first pull: this run owns the reply, or the reply is gone.
        val started = ZChannel.unwrap {
          ZIO.suspendSucceed {
            if state.compareAndSet(ReplyPending, ReplyStarted) then stopWatchdog.as(frames.channel)
            else ZIO.succeed(NoFrames)
          }
        }
        // Registered when the stream is run, before the claim above; it also covers a stream
        // that is halted before its first pull.
        val finish = ZIO.suspendSucceed {
          if state.getAndSet(ReplyReleased) != ReplyReleased then release *> stopWatchdog
          else ZIO.unit
        }
        HttpReply.Sse(headers, ZStream.fromChannel(started).ensuring(finish), keepAlive)(
          giveUp *> stopWatchdog
        )
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

  /** GET guard (SSE channel), after the Host/Origin and method gates: a supported
    * `mcp-protocol-version`, and an `Accept` that (if present) allows `text/event-stream`.
    */
  private def getHeaderError(req: HttpRequest): Option[HttpReply] =
    if !protocolVersionOk(req) then
      Some(transportError(400, "Unsupported mcp-protocol-version header"))
    else if !HttpRequestGuards.acceptAllows(req.gateHeader("accept"), "text/event-stream") then
      Some(transportError(406, "Accept must allow text/event-stream"))
    else None

private[fastmcp] object StreamableHttpHandler:

  /** Compatibility header for initialization-based Streamable HTTP revisions. */
  val SessionIdHeader: String = HttpRequestGuards.SessionIdHeader

  /** The longest a legacy (pre-2026-07-28) POST reply waits for its first frame before the SSE
    * response is opened anyway. Long enough that a call which answers promptly gets the
    * fixed-length reply; short enough that no client's response-header timeout can tell a quiet
    * call from the days when the head was sent at once.
    */
  private[fastmcp] val LegacyHeadGrace: Duration = 1.second

  /** How long a streamed reply ([[HttpReply.Sse]]) may sit unstarted after the handler returned it
    * before its resources are released as if the client had gone. A backend pulls a reply's stream
    * as soon as it has written the response head, so only a reply that is never going to be
    * streamed gets this old.
    */
  private[fastmcp] val UnstartedReplyGrace: Duration = 5.seconds

  /** The number of equal ticks [[UnstartedReplyGrace]] is counted in, so that a freeze of the whole
    * process costs a reply one tick of its grace, not all of it (see `ownedSse`).
    */
  private[fastmcp] val UnstartedReplyTicks: Int = 10

  // Who answers for a streamed reply's resources, see `ownedSse`: nobody has run its stream yet;
  // a run of its stream does; or they have been released.
  private val ReplyPending = 0
  private val ReplyStarted = 1
  private val ReplyReleased = 2

  /** The stream of a reply that was released before anything ran it. */
  private val NoFrames: ZChannel[Any, Any, Any, Any, Nothing, Chunk[SseFrame], Any] = ZChannel.unit

  /** Tell a fiber to stop without waiting for it to exit and without forking a fiber to wait. */
  private[fastmcp] def signalInterrupt(fiber: Fiber.Runtime[?, ?]): UIO[Unit] =
    ZIO.fiberIdWith(id => fiber.interruptAsFork(id))

  /** Sent with every 2026-07-28 SSE reply: tells a buffering proxy (nginx) to pass events on. */
  private val ModernSseHeaders: List[(String, String)] = List("X-Accel-Buffering" -> "no")

  /** Startup validation every backend runs before binding (`HttpRequestGuards.validateSettings`),
    * followed by its startup diagnostics at WARN (`HttpRequestGuards.startupWarnings`: a
    * non-loopback bind address with no Host/Origin allow-list).
    */
  def validateSettings(settings: McpServerSettings): IO[IllegalArgumentException, Unit] =
    ZIO
      .fromEither(HttpRequestGuards.validateSettings(settings))
      .mapError(msg => new IllegalArgumentException(s"Invalid HTTP settings: $msg")) *>
      ZIO.foreachDiscard(HttpRequestGuards.startupWarnings(settings))(ZIO.logWarning(_))

  /** Build a handler, allocating the durable session store unless `settings.stateless`. `randomId`
    * is the platform CSPRNG (`TransportBackend.randomId()`): session ids are bearer handles and
    * must be unguessable.
    */
  def make[R](
      router: McpRouter[R],
      settings: McpServerSettings,
      randomId: UIO[String],
      unstartedReplyGrace: Duration = UnstartedReplyGrace
  ): UIO[StreamableHttpHandler[R]] =
    if settings.stateless then
      ZIO.succeed(
        new StreamableHttpHandler[R](router, settings, randomId, None, unstartedReplyGrace)
      )
    else
      Ref.Synchronized
        .make(Map.empty[String, Session])
        .map(store =>
          new StreamableHttpHandler[R](router, settings, randomId, Some(store), unstartedReplyGrace)
        )

  /** Periodically drop streamable sessions idle past `settings.sessionIdleTimeout` — abandoned
    * clients would otherwise grow the store forever. Sessions with a live GET stream are exempt
    * here (push-only consumers may never POST, and while capacity is free there is no reason to
    * close their stream); they become evictable only at the `maxSessions` cap, see `admit`.
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
            now <- ZIO.succeed(Session.monotonicMs())
            all <- store.get
            expired <- ZIO.filter(all.values.toList) { s =>
              (s.lastSeen zip s.hasActiveGet).map((seen, live) => !live && now - seen > timeoutMs)
            }
            // One uninterruptible step, as for cap eviction: a session that left the store is
            // terminated even when the sweeper is stopped right then (server shutdown).
            _ <- (store.update(_ -- expired.map(_.sessionId)) *>
              ZIO.foreachDiscard(expired)(_.terminate)).uninterruptible
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

  /** A [[HttpRequestGuards.Rejection]] as its JSON-RPC error reply, with the headers it carries
    * (`Allow` on a 405).
    */
  def reject(rejection: HttpRequestGuards.Rejection): HttpReply.Json =
    val failure: JsonRpcMessage = JsonRpcMessage.Failure(
      None,
      McpError(rejection.errorCode, rejection.message).toErrorObject
    )
    HttpReply.Json(rejection.status, failure.toJson, rejection.headers)

  /** [[reject]] for a request made with `method`: the reply to a `HEAD` has no body (RFC 9110
    * §9.3.2), so it keeps the status and headers only.
    */
  def rejectFor(method: String, rejection: HttpRequestGuards.Rejection): HttpReply =
    if method == "HEAD" then HttpReply.Empty(rejection.status, rejection.headers)
    else reject(rejection)

  /** Transport-level rejection as a JSON-RPC error body (TS SDK parity — clients surface
    * `error.message` uniformly instead of sniffing text/plain bodies). `404` carries
    * [[ErrorCodes.SessionNotFound]]; every other status [[ErrorCodes.TransportError]].
    */
  def transportError(status: Int, message: String): HttpReply.Json =
    reject(HttpRequestGuards.Rejection(status, message))
