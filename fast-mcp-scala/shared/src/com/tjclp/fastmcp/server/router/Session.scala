package com.tjclp.fastmcp.server.router

import zio.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.LoggingLevel
import com.tjclp.fastmcp.core.wire.{ClientCapabilities, Implementation}
import com.tjclp.fastmcp.jsonrpc.{JsonRpcErrorObject, JsonRpcMessage, McpError, RequestId}

/** Per-connection MCP session state.
  *
  * One `Session` exists per transport connection (one for stdio; one per `mcp-session-id` for
  * streamable HTTP; an ephemeral one per request for stateless HTTP). It carries the negotiated
  * protocol version, the client's requested log level, subscription set, a server-request counter
  * (for server-initiated requests like sampling/elicit), and the in-flight fiber registry used to
  * honor `notifications/cancelled`.
  *
  * `outbound` is the channel the transport drains to push server→client messages (log
  * notifications, progress, server-initiated requests). Transports supply the sink.
  */
final class Session private (
    val sessionId: String,
    private val protocolVersionRef: Ref[String],
    private val logLevelRef: Ref[Option[LoggingLevel]],
    private val initializedRef: Ref[Boolean],
    private val subscriptionsRef: Ref[Set[String]],
    private val serverRequestCounter: Ref[Long],
    private val inflight: Ref[Map[RequestId, (String, Fiber.Runtime[?, ?])]],
    private val pendingRef: Ref[Map[RequestId, Promise[McpError, Json]]],
    private val clientInfoRef: Ref[Option[Implementation]],
    private val clientCapabilitiesRef: Ref[Option[ClientCapabilities]],
    val outbound: Queue[JsonRpcMessage],
    private val sinkRef: FiberRef[Option[Queue[JsonRpcMessage]]],
    private val lastSeenRef: Ref[Long],
    private val activeGetRef: Ref[Boolean]
):

  /** Millis timestamp of the last client activity (transports touch on every request). Drives idle
    * eviction of abandoned streamable sessions.
    */
  def lastSeen: UIO[Long] = lastSeenRef.get

  def touch: UIO[Unit] =
    ZIO.succeed(java.lang.System.currentTimeMillis()).flatMap(lastSeenRef.set)

  /** At most one standalone GET SSE stream may drain `outbound` — two would round-robin-steal
    * messages. `tryAcquireGet` is an atomic test-and-set (false = a stream is already live, answer
    * 409); the stream's finalizer must call [[releaseGet]]. Sessions with a live GET are exempt
    * from idle eviction (push-only consumers may never POST).
    */
  def tryAcquireGet: UIO[Boolean] = activeGetRef.modify(active => (!active, true))
  def releaseGet: UIO[Unit] = activeGetRef.set(false)
  def hasActiveGet: UIO[Boolean] = activeGetRef.get

  def protocolVersion: UIO[String] = protocolVersionRef.get
  def setProtocolVersion(v: String): UIO[Unit] = protocolVersionRef.set(v)

  def logLevel: UIO[Option[LoggingLevel]] = logLevelRef.get
  def setLogLevel(level: LoggingLevel): UIO[Unit] = logLevelRef.set(Some(level))

  /** Client identity + capabilities, captured from the `initialize` request. */
  def clientInfo: UIO[Option[Implementation]] = clientInfoRef.get
  def clientCapabilities: UIO[Option[ClientCapabilities]] = clientCapabilitiesRef.get

  def setClientInfo(info: Implementation, caps: ClientCapabilities): UIO[Unit] =
    clientInfoRef.set(Some(info)) *> clientCapabilitiesRef.set(Some(caps))

  def markInitialized: UIO[Unit] = initializedRef.set(true)
  def isInitialized: UIO[Boolean] = initializedRef.get

  def subscribe(uri: String): UIO[Unit] = subscriptionsRef.update(_ + uri)
  def unsubscribe(uri: String): UIO[Unit] = subscriptionsRef.update(_ - uri)
  def isSubscribed(uri: String): UIO[Boolean] = subscriptionsRef.get.map(_.contains(uri))

  /** Allocate the next id for a server-initiated request. Prefixed so server ids never collide with
    * client-issued ids on the same connection.
    */
  def nextServerRequestId: UIO[RequestId] =
    serverRequestCounter.updateAndGet(_ + 1).map(n => RequestId.StrId(s"srv-$n"))

  /** Push a message to the client. Routed to the current request's per-POST SSE sink when one is
    * active (see [[runWithSink]]), else to the shared outbound channel drained by the GET SSE
    * stream.
    */
  def send(message: JsonRpcMessage): UIO[Unit] =
    sinkRef.get.flatMap {
      case Some(q) => q.offer(message).unit
      case None => outbound.offer(message).unit
    }

  /** Route server→client messages emitted by `zio` (and its forked children) to `q` instead of the
    * shared outbound channel. Lets the streamable POST handler stream a request's notifications and
    * sub-requests (progress, sampling, elicitation) on that request's own SSE response, ordered
    * before the final reply — eliminating the cross-stream race a GET-only side channel has.
    */
  def runWithSink[R, E, A](q: Queue[JsonRpcMessage])(zio: ZIO[R, E, A]): ZIO[R, E, A] =
    sinkRef.locally(Some(q))(zio)

  // --- cancellation registry ---

  def trackInflight(id: RequestId, method: String, fiber: Fiber.Runtime[?, ?]): UIO[Unit] =
    inflight.update(_ + (id -> (method, fiber)))

  def clearInflight(id: RequestId): UIO[Unit] = inflight.update(_ - id)

  /** Interrupt the in-flight fiber for `id`, if any (drives `notifications/cancelled`). The
    * `initialize` request is exempt — the spec forbids cancelling it.
    */
  def cancelInflight(id: RequestId): UIO[Unit] =
    inflight.get.flatMap(_.get(id) match
      case Some((Methods.Initialize, _)) => ZIO.unit
      case Some((_, fiber)) => fiber.interrupt.unit *> clearInflight(id)
      case None => ZIO.unit
    )

  // --- server-initiated request/response correlation ---

  /** Send a server→client request and await the client's response. Allocates a server id, registers
    * a pending promise, pushes the request to `outbound`, and resolves when the matching response
    * arrives (via [[completePending]]) or fails on timeout. The pending entry is always cleaned up
    * (success / timeout / interrupt).
    */
  def sendRequest(method: String, params: Option[Json], timeout: Duration): IO[McpError, Json] =
    for
      id <- nextServerRequestId
      promise <- Promise.make[McpError, Json]
      _ <- pendingRef.update(_ + (id -> promise))
      result <-
        (send(JsonRpcMessage.Request(id, method, params)) *>
          promise.await.timeoutFail(
            McpError.internalError(s"Server request '$method' timed out")
          )(timeout)).ensuring(pendingRef.update(_ - id))
    yield result

  /** Resolve a pending server→client request with the client's response. No-op if the id is unknown
    * or already completed (so stray/duplicate responses are harmless).
    */
  def completePending(id: RequestId, outcome: Either[JsonRpcErrorObject, Json]): UIO[Unit] =
    pendingRef.modify(m => (m.get(id), m - id)).flatMap {
      case None => ZIO.unit
      case Some(promise) =>
        outcome match
          case Right(json) => promise.succeed(json).unit
          case Left(err) => promise.fail(McpError(err.code, err.message, err.data)).unit
    }

object Session:

  def make(sessionId: String): UIO[Session] =
    for
      pv <- Ref.make("")
      ll <- Ref.make(Option.empty[LoggingLevel])
      init <- Ref.make(false)
      subs <- Ref.make(Set.empty[String])
      cnt <- Ref.make(0L)
      inflight <- Ref.make(Map.empty[RequestId, (String, Fiber.Runtime[?, ?])])
      pending <- Ref.make(Map.empty[RequestId, Promise[McpError, Json]])
      cInfo <- Ref.make(Option.empty[Implementation])
      cCaps <- Ref.make(Option.empty[ClientCapabilities])
      outbound <- Queue.unbounded[JsonRpcMessage]
      sink = Unsafe.unsafe(implicit u => FiberRef.unsafe.make(Option.empty[Queue[JsonRpcMessage]]))
      seen <- Ref.make(java.lang.System.currentTimeMillis())
      activeGet <- Ref.make(false)
    yield new Session(
      sessionId,
      pv,
      ll,
      init,
      subs,
      cnt,
      inflight,
      pending,
      cInfo,
      cCaps,
      outbound,
      sink,
      seen,
      activeGet
    )
