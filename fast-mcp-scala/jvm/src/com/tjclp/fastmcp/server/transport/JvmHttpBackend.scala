package com.tjclp.fastmcp
package server.transport

import zio.*
import zio.http.*
import zio.http.netty.{ChannelType, NettyConfig}
import zio.json.*
import zio.stream.*

import com.tjclp.fastmcp.core.{ErrorCodes, Protocol}
import com.tjclp.fastmcp.jsonrpc.{JsonRpcMessage, McpError, RequestId}
import com.tjclp.fastmcp.server.McpServerSettings
import com.tjclp.fastmcp.server.router.{McpRouter, Session}

/** JVM [[HttpTransportBackend]] — ZIO HTTP (Netty). Pure ZIO: the native router is ZIO, so `R`
  * flows straight through `Server.serve` and the user's `.provide(...)`. No `Unsafe`, no runtime
  * capture, no Mono.
  *
  * Split from [[JvmTransportBackend]] so stdio-only programs (and their GraalVM native images)
  * never reference zio-http or Netty.
  *
  * MCP 2026-07-28 always uses one ephemeral request context per POST and an optional request-scoped
  * SSE response. `settings.stateless` selects whether the older initialize/session/ GET/DELETE
  * compatibility adapter is sessionless or durable; it does not make modern calls stateful.
  */
object JvmHttpBackend extends HttpTransportBackend:

  /** Compatibility header for initialization-based Streamable HTTP revisions. */
  private val SessionIdHeader = "mcp-session-id"

  override def serveHttp[R](
      router: McpRouter[R],
      settings: McpServerSettings
  ): ZIO[R, Throwable, Unit] =
    // Capture the environment ZIO-natively and thread it into each handler via provideEnvironment,
    // so Routes stay `Routes[Any]` — Server.serve then needs only `Server`, avoiding the generic-R
    // HasNoScope constraint. No Unsafe, no runtime capture.
    ZIO.environment[R].flatMap { env =>
      val ep = settings.httpEndpoint.stripPrefix("/")
      if settings.stateless then serve(statelessRoutes(router, env, settings), settings)
      else
        // The idle-session sweeper is forked here (scoped to the server's lifetime), NOT in
        // httpRoutes — tests drive httpRoutes directly and must not leak a sweeper fiber each.
        Ref.make(Map.empty[String, Session]).flatMap { store =>
          val routes = streamableRoutes(router, ep, env, store, settings)
          ZIO.scoped {
            evictIdleSessions(store, settings).forkScoped *> serve(routes, settings)
          }
        }
    }

  /** Periodically drop streamable sessions idle past `settings.sessionIdleTimeout` — abandoned
    * clients would otherwise grow the store forever. Sessions with a live GET stream are exempt
    * (push-only consumers may never POST). Eviction shuts the outbound queue down; the session's
    * tasks stay in the TaskManager until their own TTL.
    */
  private[fastmcp] def evictIdleSessions(
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

  /** Build the HTTP routes, branching on `settings.stateless` and allocating a fresh session store
    * for the streamable case. Exposed to tests so specs can drive requests through
    * `routes.runZIO(...)` in-memory, without binding a TCP port.
    */
  private[fastmcp] def httpRoutes[R](
      router: McpRouter[R],
      settings: McpServerSettings,
      env: ZEnvironment[R]
  ): UIO[Routes[Any, Response]] =
    val ep = settings.httpEndpoint.stripPrefix("/")
    if settings.stateless then ZIO.succeed(statelessRoutes(router, env, settings))
    else
      Ref
        .make(Map.empty[String, Session])
        .map(store => streamableRoutes(router, ep, env, store, settings))

  /** Netty channel type: `AUTO` (epoll/kqueue when available) on a normal JVM — today's zio-http
    * default — but `NIO` inside a GraalVM native image, where `AUTO`'s runtime transport probing
    * (epoll/kqueue/io_uring) is exactly the code path closed-world analysis can't tolerate.
    * `-Dfastmcp.http.channelType=nio|epoll|kqueue|auto` overrides either default.
    */
  private[transport] def channelTypeFor(
      configured: Option[String],
      inNativeImage: Boolean
  ): ChannelType =
    configured.map(_.trim.toLowerCase(java.util.Locale.ROOT)) match
      case Some("nio") => ChannelType.NIO
      case Some("epoll") => ChannelType.EPOLL
      case Some("kqueue") => ChannelType.KQUEUE
      case Some("auto") => ChannelType.AUTO
      case _ => if inNativeImage then ChannelType.NIO else ChannelType.AUTO

  /** Full [[NettyConfig]] for [[serve]]: the channel type pinned on BOTH event-loop groups.
    * NettyConfig.channelType covers the worker group and the channel factory, but the boss (accept)
    * group carries its own nested config (NettyConfig.bossGroup) — zio-http's ServerEventLoopGroups
    * builds it separately. An unpinned boss group probes epoll/kqueue at runtime, which crashes
    * native images (FFM shared arenas in netty's cleanup) and then fails channel registration with
    * "incompatible event loop type".
    */
  private[transport] def nettyConfigFor(ct: ChannelType): NettyConfig =
    val base = NettyConfig.default.channelType(ct)
    base.bossGroup(base.bossGroup.copy(channelType = ct))

  private def resolveChannelType(): ChannelType =
    channelTypeFor(
      sys.props.get("fastmcp.http.channelType"),
      // Set to "buildtime"/"runtime" by the native-image builder/binary; absent on a normal JVM.
      sys.props.contains("org.graalvm.nativeimage.imagecode")
    )

  private def serve(
      routes: Routes[Any, Response],
      settings: McpServerSettings
  ): ZIO[Any, Throwable, Unit] =
    // Server.customized with NettyConfig.default is behavior-identical to Server.defaultWith
    // (Server.live supplies NettyConfig.default internally); going through it is what lets the
    // channel type be pinned.
    val config = Server.Config.default.binding(settings.host, settings.port)
    val netty = nettyConfigFor(resolveChannelType())
    Server
      .serve(routes)
      .provideLayer((ZLayer.succeed(config) ++ ZLayer.succeed(netty)) >>> Server.customized)
      .unit

  // ---------------------------------------------------------------------------
  // Stateless: request/response, no session state, no SSE.
  // ---------------------------------------------------------------------------

  private def statelessRoutes[R](
      router: McpRouter[R],
      env: ZEnvironment[R],
      settings: McpServerSettings
  ): Routes[Any, Response] =
    val ep = settings.httpEndpoint.stripPrefix("/")
    Routes(
      Method.POST / ep -> handler { (request: Request) =>
        handleStatelessPost(router, request, settings).provideEnvironment(env)
      },
      Method.GET / ep -> handler((_: Request) =>
        ZIO.succeed(Response.status(Status.MethodNotAllowed))
      ),
      Method.DELETE / ep -> handler((_: Request) =>
        ZIO.succeed(Response.status(Status.MethodNotAllowed))
      )
    )

  /** One stateless POST: fresh ephemeral session, dispatch a single frame, return the reply (or
    * `202 Accepted` for a notification, which produces no body).
    */
  private def handleStatelessPost[R](
      router: McpRouter[R],
      request: Request,
      settings: McpServerSettings
  ): ZIO[R, Nothing, Response] =
    postHeaderError(request, settings.allowedHosts.getOrElse(Set.empty), requireSse = false) match
      case Some(err) => ZIO.succeed(err)
      case None => statelessDispatch(router, request, settings)

  private def statelessDispatch[R](
      router: McpRouter[R],
      request: Request,
      settings: McpServerSettings
  ): ZIO[R, Nothing, Response] =
    val effect =
      for
        body <- request.body.asString.mapError(e =>
          Option(e.getMessage).getOrElse("body read error")
        )
        resp <- MessageLoop.parseFrame(body, router.limits) match
          case Left(parseFailure) =>
            ZIO.succeed(Response.json(parseFailure.toJson).status(Status.BadRequest))
          case Right(message) =>
            if isModernRequest(request, message) then modernPost(router, request, message, settings)
            else
              for
                session <- Session.make("stateless", supportsTasks = false)
                // Legacy stateless compatibility mode starts ready without a handshake.
                _ <- session.markInitialized
                reply <- router.dispatch(session, message)
              yield (message, reply) match
                case (_: JsonRpcMessage.Invalid, Some(r)) =>
                  Response.json(r.toJson).status(Status.BadRequest)
                case (_, Some(r)) => Response.json(r.toJson)
                case (_, None) => Response.status(Status.Accepted)
      yield resp
    effect.catchAll(msg => ZIO.succeed(errorResponse(Status.BadRequest, msg)))

  // ---------------------------------------------------------------------------
  // Streamable: durable sessions + SSE server-push + DELETE termination.
  // ---------------------------------------------------------------------------

  private def streamableRoutes[R](
      router: McpRouter[R],
      ep: String,
      env: ZEnvironment[R],
      store: Ref[Map[String, Session]],
      settings: McpServerSettings
  ): Routes[Any, Response] =
    Routes(
      Method.POST / ep -> handler { (request: Request) =>
        handleStreamablePost(router, store, request, settings).provideEnvironment(env)
      },
      Method.GET / ep -> handler { (request: Request) =>
        handleStreamableGet(store, request, settings)
      },
      Method.DELETE / ep -> handler { (request: Request) =>
        handleStreamableDelete(
          store,
          request,
          settings.disallowDelete,
          settings.allowedHosts.getOrElse(Set.empty)
        )
      }
    )

  /** POST on the streamable transport.
    *
    *   - No `mcp-session-id` header → treat as the opening `initialize`: mint a session, dispatch,
    *     and echo the new id back in the response header.
    *   - With the header → look up the durable session (404 if unknown) and dispatch in its context
    *     (so client info, log level, and in-flight task state persist across requests).
    *
    * A request reply is returned as a single JSON body; notifications get `202 Accepted`. Any
    * server→client messages a handler pushes go out over the session's `GET` SSE channel.
    */
  private def handleStreamablePost[R](
      router: McpRouter[R],
      store: Ref[Map[String, Session]],
      request: Request,
      settings: McpServerSettings
  ): ZIO[R, Nothing, Response] =
    postHeaderError(request, settings.allowedHosts.getOrElse(Set.empty), requireSse = true) match
      case Some(err) => ZIO.succeed(err)
      case None => streamablePostDispatch(router, store, request, settings)

  private def streamablePostDispatch[R](
      router: McpRouter[R],
      store: Ref[Map[String, Session]],
      request: Request,
      settings: McpServerSettings
  ): ZIO[R, Nothing, Response] =
    request.body.asString.either.flatMap {
      case Left(err) =>
        ZIO.succeed(
          errorResponse(Status.BadRequest, Option(err.getMessage).getOrElse("body read error"))
        )
      case Right(body) =>
        // Parse BEFORE touching the session store: a malformed or non-initialize body must never
        // mint a durable session (it used to — an unauthenticated memory leak).
        MessageLoop.parseFrame(body, router.limits) match
          case Left(parseFailure) =>
            ZIO.succeed(Response.json(parseFailure.toJson).status(Status.BadRequest))
          case Right(message) =>
            if isModernRequest(request, message) then modernPost(router, request, message, settings)
            else
              request.rawHeader(SessionIdHeader) match
                case Some(id) =>
                  store.get.map(_.get(id)).flatMap {
                    case None =>
                      ZIO.succeed(errorResponse(Status.NotFound, s"Session not found: $id"))
                    case Some(session) =>
                      session.touch *>
                        respondStreamable(router, session, message, isNew = false, settings)
                  }
                case None if MessageLoop.isInitialize(message) =>
                  for
                    id <- JvmTransportBackend.randomId()
                    session <- Session.make(id)
                    _ <- store.update(_ + (session.sessionId -> session))
                    resp <- respondStreamable(router, session, message, isNew = true, settings)
                  yield resp
                case None =>
                  ZIO.succeed(
                    errorResponse(
                      Status.BadRequest,
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
  private def respondStreamable[R](
      router: McpRouter[R],
      session: Session,
      message: JsonRpcMessage,
      isNew: Boolean,
      settings: McpServerSettings
  ): URIO[R, Response] =
    message match
      case req: JsonRpcMessage.Request =>
        streamRequest(router, session, req, isNew, settings)
      case other =>
        router.dispatch(session, other).map {
          // Only structurally-Invalid frames produce a reply here (notifications and inbound
          // responses are fire-and-forget): answer 400 with the router's -32600 body.
          case Some(reply) =>
            withSessionHeader(Response.json(reply.toJson).status(Status.BadRequest), session, isNew)
          case None => Response.status(Status.Accepted)
        }

  /** Run a request's dispatch with its server→client messages routed to a per-request queue, and
    * return an SSE response that emits each and ends right after the request's final reply.
    */
  private def streamRequest[R](
      router: McpRouter[R],
      session: Session,
      message: JsonRpcMessage.Request,
      isNew: Boolean,
      settings: McpServerSettings,
      modern: Boolean = false
  ): URIO[R, Response] =
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
            modernErrorStatus(first) match
              case Some(status) =>
                (dispatchFiber.interrupt *> reqQueue.shutdown).as(
                  Response.json(first.toJson).status(status)
                )
              case None =>
                ZIO.succeed(
                  requestSseResponse(
                    reqQueue,
                    reqId,
                    session,
                    isNew,
                    dispatchFiber,
                    settings,
                    modern = true,
                    initial = List(first)
                  )
                )
          }
        else
          ZIO.succeed(
            requestSseResponse(
              reqQueue,
              reqId,
              session,
              isNew,
              dispatchFiber,
              settings,
              modern = false
            )
          )
    yield response

  private def requestSseResponse(
      reqQueue: Queue[JsonRpcMessage],
      reqId: RequestId,
      session: Session,
      isNew: Boolean,
      dispatchFiber: Fiber.Runtime[?, ?],
      settings: McpServerSettings,
      modern: Boolean,
      initial: List[JsonRpcMessage] = Nil
  ): Response =
    val messages = ZStream.fromIterable(initial) ++ ZStream.fromQueue(reqQueue)
    val sse: ZStream[Any, Nothing, ServerSentEvent[String]] =
      messages
        .takeUntil(msg => isFinalReply(msg, reqId) || MessageLoop.isCloseSentinel(msg))
        .filter(!MessageLoop.isCloseSentinel(_))
        .map(msg => ServerSentEvent(MessageLoop.encodeOutbound(msg), eventType = Some("message")))
        // Runs on the normal end of stream too, not just client abort. Task fibers are safe: they
        // are forked under Session.runWithoutSink (TaskRouting), so they never hold this queue.
        .ensuring(dispatchFiber.interrupt *> reqQueue.shutdown)
    val response = Response.fromServerSentEvents(withKeepAlive(sse, settings))
    val unbuffered =
      if modern then response.addHeader(Header.Custom("X-Accel-Buffering", "no")) else response
    withSessionHeader(unbuffered, session, isNew)

  private def modernErrorStatus(message: JsonRpcMessage): Option[Status] =
    ModernHttpValidation.errorStatus(message).map(Status.fromInt)

  /** Dispatch a 2026-07-28 request without consulting or mutating the legacy session store. */
  private def modernPost[R](
      router: McpRouter[R],
      request: Request,
      message: JsonRpcMessage,
      settings: McpServerSettings
  ): URIO[R, Response] =
    message match
      case req: JsonRpcMessage.Request =>
        validateModernRequest(router, request, req) match
          case Left((status, error)) =>
            val failure: JsonRpcMessage =
              JsonRpcMessage.Failure(Some(req.id), error.toErrorObject)
            ZIO.succeed(
              Response
                .json(failure.toJson)
                .status(status)
            )
          case Right(_) =>
            Session.make(s"request-${req.id.toString}").flatMap { session =>
              streamRequest(router, session, req, isNew = false, settings, modern = true)
            }
      case _: JsonRpcMessage.Invalid =>
        val failure: JsonRpcMessage = JsonRpcMessage.Failure(
          None,
          McpError.invalidRequest("Invalid Request").toErrorObject
        )
        ZIO.succeed(
          Response
            .json(failure.toJson)
            .status(Status.BadRequest)
        )
      case notification: JsonRpcMessage.Notification =>
        validateModernNotification(router, request, notification) match
          case Left((status, error)) =>
            val failure: JsonRpcMessage = JsonRpcMessage.Failure(None, error.toErrorObject)
            ZIO.succeed(Response.json(failure.toJson).status(status))
          case Right(_) =>
            Session
              .make("notification")
              .flatMap(session => router.dispatch(session, notification))
              .as(Response.status(Status.Accepted))
      case _ =>
        val failure: JsonRpcMessage = JsonRpcMessage.Failure(
          None,
          McpError
            .invalidRequest("HTTP POST bodies must be JSON-RPC requests or notifications")
            .toErrorObject
        )
        ZIO.succeed(Response.json(failure.toJson).status(Status.BadRequest))

  private def validateModernNotification[R](
      router: McpRouter[R],
      request: Request,
      notification: JsonRpcMessage.Notification
  ): Either[(Status, McpError), Unit] =
    ModernHttpValidation
      .validateNotification(router, notification, name => request.rawHeader(name))
      .left
      .map((code, err) => (Status.fromInt(code), err))

  private def validateModernRequest[R](
      router: McpRouter[R],
      request: Request,
      rpc: JsonRpcMessage.Request
  ): Either[(Status, McpError), Unit] =
    ModernHttpValidation
      .validateRequest(router, rpc, name => request.rawHeader(name))
      .left
      .map((code, err) => (Status.fromInt(code), err))

  private def isModernRequest(request: Request, message: JsonRpcMessage): Boolean =
    ModernHttpValidation.isModern(name => request.rawHeader(name), message)

  /** Merge a heartbeat into an SSE stream so proxies / idle timeouts don't kill long-quiet
    * connections. The `ping` event type is ignored by conforming clients (the TS SDK only parses
    * `message` events); zio-http 3.4.0 has no comment-frame support. Halts with the data stream.
    */
  private def withKeepAlive(
      sse: ZStream[Any, Nothing, ServerSentEvent[String]],
      settings: McpServerSettings
  ): ZStream[Any, Nothing, ServerSentEvent[String]] =
    settings.keepAliveInterval match
      case None => sse
      case Some(interval) =>
        val pings = ZStream.repeatWithSchedule(
          ServerSentEvent[String]("", eventType = Some("ping")),
          Schedule.spaced(Duration.fromJava(interval))
        )
        sse.mergeHaltLeft(pings)

  private def isFinalReply(message: JsonRpcMessage, reqId: RequestId): Boolean =
    message match
      case JsonRpcMessage.Success(id, _) => id == reqId
      case JsonRpcMessage.Failure(Some(id), _) => id == reqId
      case _ => false

  private def withSessionHeader(resp: Response, session: Session, isNew: Boolean): Response =
    if isNew then resp.addHeader(Header.Custom(SessionIdHeader, session.sessionId)) else resp

  /** GET opens the server→client SSE channel for a session: each message offered to the session's
    * outbound queue is emitted as an SSE `message` event. The stream ends when the session is
    * `DELETE`d (queue shutdown) or the client disconnects.
    */
  private def handleStreamableGet(
      store: Ref[Map[String, Session]],
      request: Request,
      settings: McpServerSettings
  ): ZIO[Any, Nothing, Response] =
    val allowedHosts = settings.allowedHosts.getOrElse(Set.empty)
    hostError(request, allowedHosts) match
      case Some(err) => ZIO.succeed(err)
      case None if request.rawHeader("mcp-protocol-version").exists(Protocol.isStatelessVersion) =>
        ZIO.succeed(Response.status(Status.MethodNotAllowed))
      case None =>
        getHeaderError(request, allowedHosts) match
          case Some(err) => ZIO.succeed(err)
          case None => streamableGetDispatch(store, request, settings)

  private def streamableGetDispatch(
      store: Ref[Map[String, Session]],
      request: Request,
      settings: McpServerSettings
  ): ZIO[Any, Nothing, Response] =
    request.rawHeader(SessionIdHeader) match
      case None =>
        ZIO.succeed(Response.status(Status.MethodNotAllowed))
      case Some(id) =>
        store.get.map(_.get(id)).flatMap {
          case None => ZIO.succeed(errorResponse(Status.NotFound, s"Session not found: $id"))
          case Some(session) =>
            session.touch *> session.tryAcquireGet.map {
              case false =>
                // A live GET already drains this session's outbound queue; a second stream would
                // round-robin-steal its messages (TS SDK answers 409 too).
                errorResponse(Status.Conflict, "A GET SSE stream is already open for this session")
              case true =>
                val sse: ZStream[Any, Nothing, ServerSentEvent[String]] =
                  ZStream
                    .fromQueue(session.outbound)
                    .map(msg =>
                      ServerSentEvent(MessageLoop.encodeOutbound(msg), eventType = Some("message"))
                    )
                    .ensuring(session.releaseGet)
                Response.fromServerSentEvents(withKeepAlive(sse, settings))
            }
        }

  /** DELETE terminates a session: drop it from the store and shut down its outbound queue (which
    * ends any open `GET` SSE stream). `405` when delete is disallowed by settings.
    */
  private def handleStreamableDelete(
      store: Ref[Map[String, Session]],
      request: Request,
      disallowDelete: Boolean,
      allowedHosts: Set[String]
  ): ZIO[Any, Nothing, Response] =
    hostError(request, allowedHosts) match
      case Some(err) => ZIO.succeed(err)
      case None if request.rawHeader("mcp-protocol-version").exists(Protocol.isStatelessVersion) =>
        ZIO.succeed(Response.status(Status.MethodNotAllowed))
      case None if disallowDelete => ZIO.succeed(Response.status(Status.MethodNotAllowed))
      case None =>
        request.rawHeader(SessionIdHeader) match
          case None =>
            ZIO.succeed(Response.status(Status.MethodNotAllowed))
          case Some(id) =>
            store.modify(sessions => (sessions.get(id), sessions - id)).flatMap {
              case Some(session) => session.outbound.shutdown.as(Response.status(Status.Ok))
              case None => ZIO.succeed(errorResponse(Status.NotFound, s"Session not found: $id"))
            }

  // --- Header validation (validate `Accept` + `mcp-protocol-version`; lenient when absent, so
  // header-less clients still work while clearly-wrong headers are rejected per spec). ---

  private def acceptsAny(req: Request, types: List[String]): Boolean =
    req.rawHeader("accept") match
      case None => true // absent Accept is treated as "accepts anything"
      case Some(a) =>
        val lower = a.toLowerCase
        lower.contains("*/*") || types.exists(lower.contains)

  /** `mcp-protocol-version` header validation. Absent ⇒ assume
    * [[Protocol.DefaultNegotiatedProtocolVersion]] per the spec's backwards-compatibility rule
    * (clients predating the header speak 2025-03-26); present ⇒ must be a supported version.
    */
  private def protocolVersionOk(req: Request): Boolean =
    val declared = req
      .rawHeader("mcp-protocol-version")
      .getOrElse(Protocol.DefaultNegotiatedProtocolVersion)
    Protocol.SupportedProtocolVersions.contains(declared)

  /** Reject (403) when DNS-rebinding protection is on and the request's Host/Origin isn't allowed.
    */
  private def hostError(req: Request, allowedHosts: Set[String]): Option[Response] =
    if HostGuard.isAllowed(req.rawHeader("host"), req.rawHeader("origin"), allowedHosts) then None
    else Some(errorResponse(Status.Forbidden, "Host/Origin not allowed (DNS-rebinding protection)"))

  /** POST guard: `Accept` (if present) must allow `application/json` — and on the streamable
    * transport (`requireSse`) `text/event-stream` too, since request replies stream as SSE (spec
    * requires clients to accept both).
    *
    * Note this guard does NOT check `mcp-protocol-version`: on POST the version travels in the
    * initialize payload (legacy) or is validated by [[ModernHttpValidation]] (modern, `-32022`).
    * The header itself is only enforced on the GET channel — see [[getHeaderError]].
    */
  private def postHeaderError(
      req: Request,
      allowedHosts: Set[String],
      requireSse: Boolean
  ): Option[Response] =
    hostError(req, allowedHosts).orElse {
      if !acceptsAny(req, List("application/json", "application/*")) then
        Some(errorResponse(Status.NotAcceptable, "Accept must allow application/json"))
      else if requireSse && !acceptsAny(req, List("text/event-stream", "text/*")) then
        Some(errorResponse(Status.NotAcceptable, "Accept must allow text/event-stream"))
      else None
    }

  /** GET guard (SSE channel): `Accept` (if present) must allow `text/event-stream`. */
  private def getHeaderError(req: Request, allowedHosts: Set[String]): Option[Response] =
    hostError(req, allowedHosts).orElse {
      if !protocolVersionOk(req) then
        Some(errorResponse(Status.BadRequest, "Unsupported mcp-protocol-version header"))
      else if !acceptsAny(req, List("text/event-stream", "text/*")) then
        Some(errorResponse(Status.NotAcceptable, "Accept must allow text/event-stream"))
      else None
    }

  /** Transport-level rejection as a JSON-RPC error body (TS SDK parity — clients surface
    * `error.message` uniformly instead of sniffing text/plain bodies).
    */
  private def errorResponse(status: Status, message: String): Response =
    val code =
      if status == Status.NotFound then ErrorCodes.SessionNotFound else ErrorCodes.TransportError
    val failure: JsonRpcMessage =
      JsonRpcMessage.Failure(None, McpError(code, message).toErrorObject)
    Response.json(failure.toJson).status(status)

  /** The JVM HTTP seam, in the impl object so it's exportable. `ExportsJvm` re-exports this so
    * `import com.tjclp.fastmcp.*` puts an `HttpTransportBackend` in scope and `runHttp()` /
    * `McpServerApp[Http, ...]` resolve.
    */
  given httpInstance: HttpTransportBackend = this
