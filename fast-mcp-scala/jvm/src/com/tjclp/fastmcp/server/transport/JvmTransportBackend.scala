package com.tjclp.fastmcp
package server.transport

import java.util.UUID

import zio.*
import zio.http.*
import zio.stream.*

import com.tjclp.fastmcp.core.Protocol
import com.tjclp.fastmcp.server.McpServerSettings
import com.tjclp.fastmcp.server.router.{McpRouter, Session}

/** JVM [[TransportBackend]] — pure ZIO over `System.in`/`System.out` (stdio) and ZIO HTTP. No
  * `Unsafe`, no runtime capture, no Mono: the native router is ZIO, so `R` flows straight through
  * `Server.serve` and the user's `.provide(...)`.
  *
  * `serveHttp` branches on `settings.stateless`:
  *   - **stateless** — one ephemeral [[Session]] per POST, single JSON reply, no SSE, no DELETE.
  *   - **streamable** (default) — durable sessions keyed by the `mcp-session-id` header, a `GET`
  *     SSE channel draining each session's outbound queue (server→client push), and `DELETE` to
  *     terminate. This is the transport MCP Tasks run on (sessions persist across create→poll).
  */
object JvmTransportBackend extends TransportBackend:

  /** Spec header carrying the streamable-HTTP session id (echoed on the `initialize` response). */
  private val SessionIdHeader = "mcp-session-id"

  override def serveStdio[R](
      router: McpRouter[R],
      settings: McpServerSettings
  ): ZIO[R, Throwable, Unit] =
    for
      session <- Session.make("stdio")
      // One writer owns stdout; both replies and server-pushed outbound go through it so lines
      // never interleave.
      outLock <- Semaphore.make(1)
      emit = (line: String) => outLock.withPermit(writeLine(line))
      inLines =
        ZStream
          .fromInputStream(java.lang.System.in)
          .via(ZPipeline.utf8Decode)
          .via(ZPipeline.splitLines)
          .map(_.trim)
          .filter(_.nonEmpty)
      _ <- stdioLoop(router, session, inLines, emit)
    yield ()

  /** The stdio dispatch loop, factored out of [[serveStdio]] so it can be driven over in-memory
    * streams in tests (no real `System.in`/`System.out`).
    *
    * Spawns the outbound drainer (server→client pushes) and consumes each inbound line, dispatching
    * **each frame in its own fiber** so the read loop keeps consuming while a handler is blocked —
    * e.g. a tool awaiting a server→client roots/list or sampling response, whose reply is itself a
    * *later* inbound frame. Sequential dispatch deadlocks such handlers (and a `notifications/
    * cancelled` arriving mid-request). `emit` serializes writes, so forked replies never
    * interleave.
    */
  private[fastmcp] def stdioLoop[R](
      router: McpRouter[R],
      session: Session,
      inLines: ZStream[Any, Throwable, String],
      emit: String => Task[Unit]
  ): ZIO[R, Throwable, Unit] =
    for
      _ <- session.outbound.take
        .flatMap(msg => emit(MessageLoop.encodeOutbound(msg)))
        .forever
        .forkDaemon
      _ <- inLines.runForeach { line =>
        MessageLoop
          .handleFrame(router, session, line)
          .flatMap {
            case Some(reply) => emit(reply)
            case None => ZIO.unit
          }
          .forkDaemon
          .unit
      }
    yield ()

  override def serveHttp[R](
      router: McpRouter[R],
      settings: McpServerSettings
  ): ZIO[R, Throwable, Unit] =
    // Capture the environment ZIO-natively and thread it into each handler via provideEnvironment,
    // so Routes stay `Routes[Any]` — Server.serve then needs only `Server`, avoiding the generic-R
    // HasNoScope constraint. No Unsafe, no runtime capture.
    ZIO.environment[R].flatMap { env =>
      httpRoutes(router, settings, env).flatMap(routes => serve(routes, settings))
    }

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
    if settings.stateless then ZIO.succeed(statelessRoutes(router, ep, env))
    else
      Ref
        .make(Map.empty[String, Session])
        .map(store => streamableRoutes(router, ep, env, store, settings))

  private def serve(
      routes: Routes[Any, Response],
      settings: McpServerSettings
  ): ZIO[Any, Throwable, Unit] =
    Server
      .serve(routes)
      .provideLayer(Server.defaultWith(_.binding(settings.host, settings.port)))
      .unit

  // ---------------------------------------------------------------------------
  // Stateless: request/response, no session state, no SSE.
  // ---------------------------------------------------------------------------

  private def statelessRoutes[R](
      router: McpRouter[R],
      ep: String,
      env: ZEnvironment[R]
  ): Routes[Any, Response] =
    Routes(
      Method.POST / ep -> handler { (request: Request) =>
        handleStatelessPost(router, request).provideEnvironment(env)
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
      request: Request
  ): ZIO[R, Nothing, Response] =
    postHeaderError(request) match
      case Some(err) => ZIO.succeed(err)
      case None => statelessDispatch(router, request)

  private def statelessDispatch[R](
      router: McpRouter[R],
      request: Request
  ): ZIO[R, Nothing, Response] =
    val effect =
      for
        body <- request.body.asString.mapError(e =>
          Option(e.getMessage).getOrElse("body read error")
        )
        session <- Session.make("stateless")
        reply <- MessageLoop.handleFrame(router, session, body)
      yield reply match
        case Some(json) => Response.json(json)
        case None => Response.status(Status.Accepted)
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
        handleStreamablePost(router, store, request).provideEnvironment(env)
      },
      Method.GET / ep -> handler { (request: Request) =>
        handleStreamableGet(store, request)
      },
      Method.DELETE / ep -> handler { (request: Request) =>
        handleStreamableDelete(store, request, settings.disallowDelete)
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
      request: Request
  ): ZIO[R, Nothing, Response] =
    postHeaderError(request) match
      case Some(err) => ZIO.succeed(err)
      case None => streamablePostDispatch(router, store, request)

  private def streamablePostDispatch[R](
      router: McpRouter[R],
      store: Ref[Map[String, Session]],
      request: Request
  ): ZIO[R, Nothing, Response] =
    request.body.asString.either.flatMap {
      case Left(err) =>
        ZIO.succeed(
          errorResponse(Status.BadRequest, Option(err.getMessage).getOrElse("body read error"))
        )
      case Right(body) =>
        request.rawHeader(SessionIdHeader) match
          case Some(id) =>
            store.get.map(_.get(id)).flatMap {
              case None =>
                ZIO.succeed(errorResponse(Status.NotFound, s"Session not found: $id"))
              case Some(session) =>
                MessageLoop
                  .handleFrame(router, session, body)
                  .map(toPostResponse(_, session, isNew = false))
            }
          case None =>
            for
              session <- Session.make(UUID.randomUUID().toString)
              _ <- store.update(_ + (session.sessionId -> session))
              reply <- MessageLoop.handleFrame(router, session, body)
            yield toPostResponse(reply, session, isNew = true)
    }

  /** GET opens the server→client SSE channel for a session: each message offered to the session's
    * outbound queue is emitted as an SSE `message` event. The stream ends when the session is
    * `DELETE`d (queue shutdown) or the client disconnects.
    */
  private def handleStreamableGet(
      store: Ref[Map[String, Session]],
      request: Request
  ): ZIO[Any, Nothing, Response] =
    getHeaderError(request) match
      case Some(err) => ZIO.succeed(err)
      case None => streamableGetDispatch(store, request)

  private def streamableGetDispatch(
      store: Ref[Map[String, Session]],
      request: Request
  ): ZIO[Any, Nothing, Response] =
    request.rawHeader(SessionIdHeader) match
      case None =>
        ZIO.succeed(
          errorResponse(Status.BadRequest, s"Session ID required in $SessionIdHeader header")
        )
      case Some(id) =>
        store.get.map { sessions =>
          sessions.get(id) match
            case None => errorResponse(Status.NotFound, s"Session not found: $id")
            case Some(session) =>
              val sse: ZStream[Any, Nothing, ServerSentEvent[String]] =
                ZStream
                  .fromQueue(session.outbound)
                  .map(msg =>
                    ServerSentEvent(MessageLoop.encodeOutbound(msg), eventType = Some("message"))
                  )
              Response.fromServerSentEvents(sse)
        }

  /** DELETE terminates a session: drop it from the store and shut down its outbound queue (which
    * ends any open `GET` SSE stream). `405` when delete is disallowed by settings.
    */
  private def handleStreamableDelete(
      store: Ref[Map[String, Session]],
      request: Request,
      disallowDelete: Boolean
  ): ZIO[Any, Nothing, Response] =
    if disallowDelete then ZIO.succeed(Response.status(Status.MethodNotAllowed))
    else
      request.rawHeader(SessionIdHeader) match
        case None =>
          ZIO.succeed(
            errorResponse(Status.BadRequest, s"Session ID required in $SessionIdHeader header")
          )
        case Some(id) =>
          store.modify(sessions => (sessions.get(id), sessions - id)).flatMap {
            case Some(session) => session.outbound.shutdown.as(Response.status(Status.Ok))
            case None => ZIO.succeed(errorResponse(Status.NotFound, s"Session not found: $id"))
          }

  /** Render a dispatch reply for a streamable POST, echoing the `mcp-session-id` header on the
    * `initialize` response (the only time `isNew` is true).
    */
  private def toPostResponse(reply: Option[String], session: Session, isNew: Boolean): Response =
    reply match
      case Some(json) =>
        val resp = Response.json(json)
        if isNew then resp.addHeader(Header.Custom(SessionIdHeader, session.sessionId)) else resp
      case None => Response.status(Status.Accepted)

  // --- Header validation (validate `Accept` + `mcp-protocol-version`; lenient when absent, so
  // header-less clients still work while clearly-wrong headers are rejected per spec). ---

  private def acceptsAny(req: Request, types: List[String]): Boolean =
    req.rawHeader("accept") match
      case None => true // absent Accept is treated as "accepts anything"
      case Some(a) =>
        val lower = a.toLowerCase
        lower.contains("*/*") || types.exists(lower.contains)

  private def protocolVersionOk(req: Request): Boolean =
    req.rawHeader("mcp-protocol-version").forall(Protocol.SupportedProtocolVersions.contains)

  /** POST guard: `Accept` (if present) must allow `application/json`; `mcp-protocol-version` (if
    * present) must be supported.
    */
  private def postHeaderError(req: Request): Option[Response] =
    if !protocolVersionOk(req) then
      Some(errorResponse(Status.BadRequest, "Unsupported mcp-protocol-version header"))
    else if !acceptsAny(req, List("application/json", "application/*")) then
      Some(errorResponse(Status.NotAcceptable, "Accept must allow application/json"))
    else None

  /** GET guard (SSE channel): `Accept` (if present) must allow `text/event-stream`. */
  private def getHeaderError(req: Request): Option[Response] =
    if !protocolVersionOk(req) then
      Some(errorResponse(Status.BadRequest, "Unsupported mcp-protocol-version header"))
    else if !acceptsAny(req, List("text/event-stream", "text/*")) then
      Some(errorResponse(Status.NotAcceptable, "Accept must allow text/event-stream"))
    else None

  private def errorResponse(status: Status, message: String): Response =
    Response.text(message).status(status)

  private def writeLine(line: String): Task[Unit] =
    ZIO.attempt {
      val out = java.lang.System.out
      out.print(line)
      out.print('\n')
      out.flush()
    }

  /** The JVM platform seam, in the impl object so it's exportable (givens can't be wildcard-
    * exported straight from a package). `ExportsJvm` re-exports this so `import
    * com.tjclp.fastmcp.*` puts a `TransportBackend` in scope and `McpServer(...)` resolves.
    */
  given instance: TransportBackend = this
