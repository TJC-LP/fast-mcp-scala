package com.tjclp.fastmcp.server.router

import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.{Fnv1a, LoggingLevel}
import com.tjclp.fastmcp.core.wire.{ClientCapabilities, Implementation}
import com.tjclp.fastmcp.jsonrpc.{JsonRpcErrorObject, JsonRpcMessage, McpError, RequestId}

/** Mutable request/connection state owned by a transport.
  *
  * Modern HTTP creates one ephemeral instance per request, while stdio uses one connection object
  * whose modern request data remains fiber-local. The legacy HTTP adapter keeps one instance per
  * `mcp-session-id`. It also owns compatibility handshake state and the in-flight fiber registry.
  *
  * `outbound` is the channel the transport drains for request-scoped notifications and subscription
  * acknowledgements; the legacy adapter also carries server-initiated requests.
  */
final class Session private (
    val sessionId: String,
    /** False only for the shared legacy-stateless session, whose id is not a client identity — task
      * ownership keyed on it would be cross-client. Stdio ("stdio") and streamable sessions are one
      * client each and keep the legacy task surface.
      */
    val supportsTasks: Boolean,
    /** Transport-supplied client identity (peer address on the shipped HTTP backends), used to
      * bucket modern bearer tasks per client. `None` when the transport cannot tell clients apart;
      * never derived from request content.
      */
    val clientKey: Option[String],
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
    private val requestContextRef: FiberRef[Option[RequestContext]],
    private val requestIdRef: FiberRef[Option[RequestId]],
    private val inputKeyOccurrencesRef: FiberRef[Option[Ref[Map[String, Int]]]],
    private val lastSeenRef: java.util.concurrent.atomic.AtomicLong,
    private val activeGetRef: java.util.concurrent.atomic.AtomicBoolean,
    /** `Some(finalizers)` while live; `None` once [[terminate]] has run (the terminated latch). */
    private val finalizersRef: Ref[Option[Map[String, UIO[Unit]]]]
):

  /** Millis timestamp of the last client activity (transports touch on every request). Drives idle
    * eviction of abandoned streamable sessions.
    */
  def lastSeen: UIO[Long] = ZIO.succeed(lastSeenRef.get())

  /** `(lastSeen, hasActiveGet)` read synchronously — for a single-threaded runtime (Bun) that must
    * snapshot every stored session and decide an eviction in ONE step, with no fiber yield between
    * the snapshot and the decision.
    */
  private[fastmcp] def idleStateUnsafe(implicit unsafe: Unsafe): (Long, Boolean) =
    (lastSeenRef.get(), activeGetRef.get())

  def touch: UIO[Unit] =
    ZIO.succeed(lastSeenRef.set(java.lang.System.currentTimeMillis()))

  /** At most one standalone GET SSE stream may drain `outbound` — two would round-robin-steal
    * messages. `tryAcquireGet` is an atomic test-and-set (false = a stream is already live, answer
    * 409); the stream's finalizer must call [[releaseGet]]. Sessions with a live GET are exempt
    * from idle eviction (push-only consumers may never POST).
    */
  def tryAcquireGet: UIO[Boolean] = ZIO.succeed(activeGetRef.compareAndSet(false, true))
  def releaseGet: UIO[Unit] = ZIO.succeed(activeGetRef.set(false))
  def hasActiveGet: UIO[Boolean] = ZIO.succeed(activeGetRef.get())

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

  /** Atomically admit a distinct URI within the cap. Re-subscribing an existing URI is free. */
  def trySubscribe(uri: String, maxSubscriptions: Int): UIO[Boolean] =
    subscriptionsRef.modify { subscriptions =>
      if subscriptions.contains(uri) then (true, subscriptions)
      else if subscriptions.size >= maxSubscriptions then (false, subscriptions)
      else (true, subscriptions + uri)
    }

  def unsubscribe(uri: String): UIO[Unit] = subscriptionsRef.update(_ - uri)
  def isSubscribed(uri: String): UIO[Boolean] = subscriptionsRef.get.map(_.contains(uri))
  def subscriptionCount: UIO[Int] = subscriptionsRef.get.map(_.size)

  // --- termination ---

  /** Register (or replace) a keyed finalizer run by [[terminate]]. Keyed so a component that
    * re-registers on every request stays idempotent (e.g. the task manager releasing the session's
    * tasks). A finalizer registered AFTER the session was terminated runs immediately instead of
    * being parked on a dead session — so a request whose registration completes after a racing
    * `DELETE` / idle eviction still gets its state released.
    */
  def addFinalizer(key: String)(f: UIO[Unit]): UIO[Unit] =
    finalizersRef
      .modify {
        case Some(fs) => (false, Some(fs.updated(key, f)))
        case None => (true, None)
      }
      .flatMap(late => ZIO.when(late)(f.ignore).unit)

  /** True once [[terminate]] has run. */
  def isTerminated: UIO[Boolean] = finalizersRef.get.map(_.isEmpty)

  /** Terminate the session: latch the terminated flag, interrupt the session's in-flight request
    * fibers (a request cannot outlive its session; `interruptFork` so a fiber parked in an
    * uninterruptible region never blocks the caller), run every registered finalizer once (failures
    * ignored), then shut the outbound channel down. Idempotent. A strict superset of
    * `outbound.shutdown`; transports call it on `DELETE` and idle eviction so session-bound state
    * (tasks) does not outlive the session.
    */
  def terminate: UIO[Unit] =
    finalizersRef.getAndSet(None).flatMap {
      case None => ZIO.unit
      case Some(fs) =>
        inflight
          .getAndSet(Map.empty)
          .flatMap(fibers => ZIO.foreachDiscard(fibers.values)(_._2.interruptFork)) *>
          ZIO.foreachDiscard(fs.values)(_.ignore) *>
          outbound.shutdown
    }

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

  /** Run `zio` — and, crucially, anything it forks — with pushes routed to the shared outbound
    * channel even when the caller sits inside a per-POST sink scope ([[runWithSink]]). Task fibers
    * outlive their creating POST; that POST's queue is shut down when its SSE stream ends, and an
    * offer to a shutdown queue interrupts the offering fiber — which would cancel the task itself.
    * Status/progress/log messages from a task belong on the shared channel anyway.
    */
  def runWithoutSink[R, E, A](zio: ZIO[R, E, A]): ZIO[R, E, A] =
    sinkRef.locally(None)(zio)

  /** Bind stateless per-request metadata to the handler fiber and all of its children. The MRTR
    * occurrence cell is a fresh `Ref` per request attempt: forked children copy the `Some(ref)`
    * VALUE — i.e. the same cell — so parallel handler branches share one atomic map, while each
    * retry replay starts from an empty one.
    */
  def runWithRequest[R, E, A](
      id: RequestId,
      context: RequestContext
  )(zio: ZIO[R, E, A]): ZIO[R, E, A] =
    Ref.make(Map.empty[String, Int]).flatMap { occurrences =>
      requestContextRef.locally(Some(context))(
        requestIdRef.locally(Some(id))(inputKeyOccurrencesRef.locally(Some(occurrences))(zio))
      )
    }

  def currentRequestContext: UIO[Option[RequestContext]] = requestContextRef.get
  def currentRequestId: UIO[Option[RequestId]] = requestIdRef.get

  /** Content-derived MRTR key: a stable hash of (method, canonically rendered params) plus an
    * occurrence index for byte-identical repeats within one request. Deterministic in request
    * content — a retry replay of the same handler reallocates the same keys, including inside
    * parallel (`zipPar`) branches, so `inputResponses` route to the branch that asked. Occurrence
    * order among byte-identical PARALLEL questions follows scheduling; such questions are
    * wire-indistinguishable, so any assignment is content-equivalent.
    */
  def nextInputRequestKey(method: String, params: Option[Json]): UIO[String] =
    val hash = Fnv1a.hex64(method + "\n" + params.fold("")(_.toJson))
    inputKeyOccurrencesRef.get.flatMap {
      case Some(cell) =>
        cell.modify { m =>
          val n = m.getOrElse(hash, 0) + 1
          (s"input-$hash-$n", m.updated(hash, n))
        }
      case None => ZIO.succeed(s"input-$hash-1") // defensive: only reachable outside runWithRequest
    }

  // --- cancellation registry ---

  def trackInflight(id: RequestId, method: String, fiber: Fiber.Runtime[?, ?]): UIO[Unit] =
    inflight.update(_ + (id -> (method, fiber)))

  def clearInflight(id: RequestId): UIO[Unit] = inflight.update(_ - id)

  /** Ids currently tracked in the in-flight registry (test seam: lets a cancellation test wait
    * until `trackInflight` has run, since the dispatcher forks before tracking).
    */
  private[fastmcp] def inflightIds: UIO[Set[RequestId]] = inflight.get.map(_.keySet)

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

  def make(
      sessionId: String,
      supportsTasks: Boolean = true,
      clientKey: Option[String] = None
  ): UIO[Session] =
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
      requestContext = Unsafe.unsafe(implicit u =>
        FiberRef.unsafe.make(Option.empty[RequestContext])
      )
      requestId = Unsafe.unsafe(implicit u => FiberRef.unsafe.make(Option.empty[RequestId]))
      inputOccurrences = Unsafe.unsafe(implicit u =>
        FiberRef.unsafe.make(Option.empty[Ref[Map[String, Int]]])
      )
      seen = new java.util.concurrent.atomic.AtomicLong(java.lang.System.currentTimeMillis())
      activeGet = new java.util.concurrent.atomic.AtomicBoolean(false)
      finalizers <- Ref.make(Option(Map.empty[String, UIO[Unit]]))
    yield new Session(
      sessionId,
      supportsTasks,
      clientKey,
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
      requestContext,
      requestId,
      inputOccurrences,
      seen,
      activeGet,
      finalizers
    )
