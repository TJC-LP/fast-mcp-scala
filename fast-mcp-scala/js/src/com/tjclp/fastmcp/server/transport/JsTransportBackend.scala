package com.tjclp.fastmcp
package server.transport

import scala.scalajs.js

import zio.*
import zio.json.*

import com.tjclp.fastmcp.core.{ErrorCodes, Protocol}
import com.tjclp.fastmcp.jsonrpc.{JsonRpcMessage, McpError, RequestId}
import com.tjclp.fastmcp.facades.node.NodeProcess
import com.tjclp.fastmcp.facades.runtime.{
  Bun,
  BunServeOptions,
  BunServer,
  WebCrypto,
  WebResponse,
  WebResponseInit
}
import com.tjclp.fastmcp.interop.ZioJsPromise
import com.tjclp.fastmcp.server.McpServerSettings
import com.tjclp.fastmcp.server.router.{McpRouter, RequestContext, Session}

/** Scala.js (Bun-first) [[TransportBackend]].
  *
  * Mirrors the JVM backend behavior over the shared [[MessageLoop]]; the only inherent difference
  * is the `Bun.serve`/Node-stdin boundary, which is callback-based and synchronous, so we capture a
  * `Runtime[R]` once (`ZIO.runtime[R]`) and bridge each request/line through that runtime via
  * [[ZioJsPromise]].
  *
  * MCP 2026-07-28 uses stateless POSTs and request-scoped SSE responses. The older
  * initialize/session lifecycle remains a version-selected compatibility path; Bun continues to
  * answer 405 for its standalone legacy GET channel. Modern semantics match the JVM backend; only
  * the `Bun.serve` / `ReadableStream` shim differs.
  */
object JsTransportBackend extends TransportBackend:

  /** UUID v4 via Web Crypto (`crypto.randomUUID`) — the JS runtime's CSPRNG. */
  override def randomId(): UIO[String] = ZIO.succeed(WebCrypto.randomUUID())

  private val SessionIdHeader = "mcp-session-id"

  // -------------------------------------------------------------------------
  // stdio
  // -------------------------------------------------------------------------

  override def serveStdio[R](
      router: McpRouter[R],
      settings: McpServerSettings
  ): ZIO[R, Throwable, Unit] =
    for
      rt <- ZIO.runtime[R]
      session <- Session.make("stdio")
      // Drain server-initiated messages (log/progress notifications) to stdout.
      drainer <- session.outbound.take
        .flatMap(msg => writeLine(MessageLoop.encodeOutbound(msg)))
        .forever
        .forkDaemon
      // stdin EOF completes the async; take the drainer down with it.
      _ <- ZIO
        .async[R, Throwable, Unit](cb => wireStdin(router, session, rt, cb))
        .ensuring(drainer.interrupt)
    yield ()

  private def wireStdin[R](
      router: McpRouter[R],
      session: Session,
      rt: Runtime[R],
      done: ZIO[R, Throwable, Unit] => Unit
  ): Unit =
    val stdin = NodeProcess.stdin
    stdin.setEncoding("utf8")
    var buffer = "" // accumulates partial lines across `data` chunks
    stdin.on(
      "data",
      (chunk: js.Any) =>
        buffer += chunk.asInstanceOf[String]
        var nl = buffer.indexOf("\n")
        while nl >= 0 do
          val line = buffer.substring(0, nl).trim
          buffer = buffer.substring(nl + 1)
          if line.nonEmpty then
            val _ = ZioJsPromise.zioToPromise(rt)(
              MessageLoop.handleFrame(router, session, line).flatMap {
                case Some(reply) => writeLine(reply)
                case None => ZIO.unit
              }
            )
          nl = buffer.indexOf("\n")
    )
    stdin.on("end", (_: js.Any) => done(ZIO.unit))

  private def writeLine(line: String): UIO[Unit] =
    ZIO.succeed {
      val _ = NodeProcess.stdout.write(line + "\n")
    }

  // -------------------------------------------------------------------------
  // HTTP (Bun.serve)
  // -------------------------------------------------------------------------

  override def serveHttp[R](
      router: McpRouter[R],
      settings: McpServerSettings
  ): ZIO[R, Throwable, Unit] =
    ZIO.runtime[R].flatMap { rt =>
      val store = js.Dictionary.empty[Session]
      ZIO.acquireReleaseWith(ZIO.attempt(startBun(router, rt, settings, store)))(server =>
        ZIO.succeed(server.stop())
      )(_ =>
        // Idle-session sweeper scoped to the server's lifetime (test helpers that call startBun
        // directly get no sweeper — documented there).
        ZIO.scoped(evictIdleSessions(store, settings).forkScoped *> ZIO.never)
      )
    }

  /** Periodically drop streamable sessions idle past `settings.sessionIdleTimeout` (JVM twin:
    * `JvmTransportBackend.evictIdleSessions`). Bun is single-threaded, so mutating the dictionary
    * in-place is safe.
    */
  private def evictIdleSessions(
      store: js.Dictionary[Session],
      settings: McpServerSettings
  ): UIO[Unit] =
    settings.sessionIdleTimeout match
      case None => ZIO.unit
      case Some(timeout) =>
        val timeoutMs = timeout.toMillis
        val sweep =
          for
            now <- ZIO.succeed(java.lang.System.currentTimeMillis())
            expired <- ZIO.filter(store.toList) { case (_, s) =>
              (s.lastSeen zip s.hasActiveGet).map((seen, live) => !live && now - seen > timeoutMs)
            }
            _ <- ZIO.foreachDiscard(expired) { case (sid, s) =>
              ZIO.succeed(store -= sid) *> s.outbound.shutdown
            }
          yield ()
        val interval = Duration.fromMillis(math.max(timeoutMs / 4, 1000L))
        sweep.repeat(Schedule.spaced(interval)).unit

  /** Start the Bun HTTP listener and return its handle. Used by `serveHttp` (wrapped in
    * acquire/release, with the idle sweeper running alongside) and directly by JS integration tests
    * that want a `stop()`-able handle (no sweeper — tests manage session lifetimes).
    */
  def startBun[R](
      router: McpRouter[R],
      runtime: Runtime[R],
      settings: McpServerSettings,
      store: js.Dictionary[Session] = js.Dictionary.empty[Session] // Bun is single-threaded
  ): BunServer =
    Bun.serve(
      BunServeOptions(
        port = settings.port,
        hostname = settings.host,
        fetch = js.Any.fromFunction1((req: js.Dynamic) =>
          ZioJsPromise.zioToPromise(runtime)(handleFetch(router, settings, store, req))
        )
      )
    )

  private def handleFetch[R](
      router: McpRouter[R],
      settings: McpServerSettings,
      store: js.Dictionary[Session],
      req: js.Dynamic
  ): ZIO[R, Throwable, js.Dynamic] =
    if pathOf(req) != settings.httpEndpoint then ZIO.succeed(webResponse(404, "Not Found"))
    else
      val allowedHosts = settings.allowedHosts.getOrElse(Set.empty)
      val headerErr =
        hostError(req, allowedHosts)
          .orElse(
            if methodOf(req) == "POST" then postHeaderError(req, requireSse = !settings.stateless)
            else None
          )
      headerErr match
        case Some(err) => ZIO.succeed(err)
        case None =>
          if settings.stateless then handleStateless(router, req)
          else handleStreamable(router, settings, store, req)

  private def handleStateless[R](
      router: McpRouter[R],
      req: js.Dynamic
  ): ZIO[R, Throwable, js.Dynamic] =
    methodOf(req) match
      case "POST" =>
        readBody(req).flatMap { body =>
          MessageLoop.parseFrame(body) match
            case Left(parseFailure) =>
              ZIO.succeed(jsonResponse(parseFailure.toJson, Map.empty, status = 400))
            case Right(message) =>
              if isModernRequest(req, message) then
                modernPost(router, req, message, McpServerSettings(stateless = true))
              else
                for
                  session <- Session.make("stateless", supportsTasks = false)
                  // Legacy stateless compatibility mode starts ready without a handshake.
                  _ <- session.markInitialized
                  reply <- router.dispatch(session, message)
                yield (message, reply) match
                  case (_: JsonRpcMessage.Invalid, Some(r)) =>
                    jsonResponse(r.toJson, Map.empty, status = 400)
                  case (_, Some(r)) => jsonResponse(r.toJson, Map.empty)
                  case (_, None) => webResponse(202, "")
        }
      case _ =>
        ZIO.succeed(jsonRpcErrorResponse(405, "Stateless mode only accepts POST"))

  private def handleStreamable[R](
      router: McpRouter[R],
      settings: McpServerSettings,
      store: js.Dictionary[Session],
      req: js.Dynamic
  ): ZIO[R, Throwable, js.Dynamic] =
    methodOf(req) match
      case "POST" =>
        readBody(req).flatMap { body =>
          // Parse BEFORE touching the session store: a malformed or non-initialize body must
          // never mint a durable session (JVM transport does the same).
          MessageLoop.parseFrame(body) match
            case Left(parseFailure) =>
              ZIO.succeed(jsonResponse(parseFailure.toJson, Map.empty, status = 400))
            case Right(message) =>
              if isModernRequest(req, message) then modernPost(router, req, message, settings)
              else
                sessionIdHeader(req) match
                  case Some(sid) =>
                    store.get(sid) match
                      case None =>
                        ZIO.succeed(jsonRpcErrorResponse(404, s"Session not found: $sid"))
                      case Some(session) =>
                        session.touch *>
                          respondStreamable(router, session, message, isNew = false, settings)
                  case None if MessageLoop.isInitialize(message) =>
                    for
                      sid <- randomId()
                      session <- Session.make(sid)
                      _ <- ZIO.succeed { store(session.sessionId) = session }
                      resp <- respondStreamable(router, session, message, isNew = true, settings)
                    yield resp
                  case None =>
                    ZIO.succeed(
                      jsonRpcErrorResponse(
                        400,
                        s"$SessionIdHeader header is required (only initialize may open a session)"
                      )
                    )
        }

      case "DELETE" =>
        val version = Option(req.headers.get("mcp-protocol-version").asInstanceOf[String])
        if version.exists(Protocol.isStatelessVersion) then
          ZIO.succeed(webResponse(405, "Method Not Allowed"))
        else if settings.disallowDelete then
          ZIO.succeed(jsonRpcErrorResponse(405, "DELETE disabled"))
        else
          sessionIdHeader(req) match
            case None =>
              ZIO.succeed(webResponse(405, "Method Not Allowed"))
            case Some(sid) =>
              store.get(sid) match
                case Some(session) =>
                  ZIO.succeed { store -= sid } *> session.outbound.shutdown.as(webResponse(200, ""))
                case None =>
                  ZIO.succeed(jsonRpcErrorResponse(404, s"Session not found: $sid"))

      case _ =>
        // GET is a no-op 405: every server→client message streams on its request's own POST SSE
        // response (see respondStreamable), so a standalone GET push channel isn't needed.
        ZIO.succeed(
          jsonRpcErrorResponse(405, "No standalone GET stream; server→client rides the POST SSE")
        )

  /** Dispatch one streamable POST frame. A *request* gets a `text/event-stream` response that
    * streams the notifications and sub-requests it emits (progress, sampling, elicitation) followed
    * by the final JSON-RPC reply — one ordered stream, identical semantics to the JVM transport.
    * Reuses the shared [[Session.runWithSink]] + [[McpRouter.dispatch]]; only the Bun
    * `ReadableStream` shim is platform-specific. Notifications / client responses get `202`; a
    * parse error returns JSON.
    */
  private def respondStreamable[R](
      router: McpRouter[R],
      session: Session,
      message: JsonRpcMessage,
      isNew: Boolean,
      settings: McpServerSettings
  ): ZIO[R, Throwable, js.Dynamic] =
    message match
      case req: JsonRpcMessage.Request =>
        for
          reqQueue <- Queue.unbounded[JsonRpcMessage]
          // `ensuring` offers the close sentinel when dispatch ends replyless (cancellation) so
          // the SSE stream terminates; the bound fiber lets the stream's `cancel` hook interrupt
          // the dispatch when the client disconnects mid-request.
          fiber <- session
            .runWithSink(reqQueue)(router.dispatch(session, req))
            .flatMap(reply => ZIO.foreachDiscard(reply)(reqQueue.offer))
            .ensuring(reqQueue.offer(MessageLoop.CloseSentinel))
            .forkDaemon
        yield sseResponse(reqQueue, req.id, session, isNew, fiber, settings, modern = false)
      case other =>
        val extra =
          if isNew then Map(SessionIdHeader -> session.sessionId) else Map.empty[String, String]
        router.dispatch(session, other).map {
          // Only structurally-Invalid frames produce a reply here: 400 with the -32600 body.
          case Some(reply) => jsonResponse(reply.toJson, extra, status = 400)
          case None => webResponse(202, "")
        }

  /** Build a Bun SSE `Response` whose `ReadableStream` is pull-fed from `reqQueue`: each take emits
    * one `event: message` frame, and the stream closes right after the request's final reply.
    */
  private def sseResponse(
      reqQueue: Queue[JsonRpcMessage],
      reqId: RequestId,
      session: Session,
      isNew: Boolean,
      dispatchFiber: Fiber.Runtime[?, ?],
      settings: McpServerSettings,
      modern: Boolean,
      initial: Option[JsonRpcMessage] = None
  ): js.Dynamic =
    val encoder = js.Dynamic.newInstance(js.Dynamic.global.TextEncoder)()
    var initialMessage = initial
    // Keepalive: when configured, a quiet `take` emits an SSE comment frame instead of blocking
    // forever, so proxies / idle timeouts don't kill long-running calls.
    val takeNext: UIO[Option[JsonRpcMessage]] = ZIO.suspendSucceed {
      initialMessage match
        case Some(message) =>
          initialMessage = None
          ZIO.some(message)
        case None =>
          settings.keepAliveInterval match
            case None => reqQueue.take.map(Some(_))
            case Some(interval) => reqQueue.take.timeout(Duration.fromJava(interval))
    }
    val source = js.Dynamic.literal(
      pull = js.Any.fromFunction1((controller: js.Dynamic) =>
        ZioJsPromise.zioToPromise(
          takeNext.map {
            case None =>
              val _ = controller.enqueue(encoder.encode(": ping\n\n"))
            case Some(msg) if MessageLoop.isCloseSentinel(msg) =>
              // Dispatch ended replyless (cancelled): end the stream without emitting a frame.
              val _ = controller.close()
            case Some(msg) =>
              val frame = s"event: message\ndata: ${MessageLoop.encodeOutbound(msg)}\n\n"
              val _ = controller.enqueue(encoder.encode(frame))
              if isFinalReply(msg, reqId) then
                val _ = controller.close()
          }
        )
      ),
      // Client disconnected mid-request: interrupt the dispatch and drop the queue, otherwise the
      // pending `take` leaks a fiber per aborted request.
      cancel = js.Any.fromFunction1((_: js.Any) =>
        ZioJsPromise.zioToPromise(dispatchFiber.interrupt *> reqQueue.shutdown)
      )
    )
    val stream = js.Dynamic.newInstance(js.Dynamic.global.ReadableStream)(source)
    val headers =
      Map("content-type" -> "text/event-stream", "cache-control" -> "no-cache") ++
        (if modern then Map("x-accel-buffering" -> "no") else Map.empty[String, String]) ++
        (if isNew then Map(SessionIdHeader -> session.sessionId) else Map.empty[String, String])
    val init = js.Dynamic.literal(status = 200, headers = js.Dictionary[String](headers.toSeq*))
    js.Dynamic.newInstance(js.Dynamic.global.Response)(stream, init)

  private def isFinalReply(message: JsonRpcMessage, reqId: RequestId): Boolean =
    message match
      case JsonRpcMessage.Success(id, _) => id == reqId
      case JsonRpcMessage.Failure(Some(id), _) => id == reqId
      case _ => false

  /** Process a 2026-07-28 POST using one ephemeral request context. Legacy session headers are
    * intentionally ignored and never echoed.
    */
  private def modernPost[R](
      router: McpRouter[R],
      req: js.Dynamic,
      message: JsonRpcMessage,
      settings: McpServerSettings
  ): ZIO[R, Throwable, js.Dynamic] =
    message match
      case rpc: JsonRpcMessage.Request =>
        validateModernRequest(router, req, rpc) match
          case Left((status, error)) =>
            val failure: JsonRpcMessage =
              JsonRpcMessage.Failure(Some(rpc.id), error.toErrorObject)
            ZIO.succeed(jsonResponse(failure.toJson, Map.empty, status))
          case Right(_) =>
            for
              session <- Session.make(s"request-${rpc.id.toString}")
              reqQueue <- Queue.unbounded[JsonRpcMessage]
              fiber <- session
                .runWithSink(reqQueue)(router.dispatch(session, rpc))
                .flatMap(reply => ZIO.foreachDiscard(reply)(reqQueue.offer))
                .ensuring(reqQueue.offer(MessageLoop.CloseSentinel))
                .forkDaemon
              first <- reqQueue.take.onInterrupt(fiber.interrupt *> reqQueue.shutdown)
              response <- modernErrorStatus(first) match
                case Some(status) =>
                  (fiber.interrupt *> reqQueue.shutdown).as(
                    jsonResponse(first.toJson, Map.empty, status)
                  )
                case None =>
                  ZIO.succeed(
                    sseResponse(
                      reqQueue,
                      rpc.id,
                      session,
                      isNew = false,
                      fiber,
                      settings,
                      modern = true,
                      initial = Some(first)
                    )
                  )
            yield response
      case _: JsonRpcMessage.Invalid =>
        val failure: JsonRpcMessage =
          JsonRpcMessage.Failure(None, McpError.invalidRequest("Invalid Request").toErrorObject)
        ZIO.succeed(jsonResponse(failure.toJson, Map.empty, status = 400))
      case notification: JsonRpcMessage.Notification =>
        validateModernNotification(router, req, notification) match
          case Left((status, error)) =>
            val failure: JsonRpcMessage = JsonRpcMessage.Failure(None, error.toErrorObject)
            ZIO.succeed(jsonResponse(failure.toJson, Map.empty, status))
          case Right(_) =>
            Session
              .make("notification")
              .flatMap(session => router.dispatch(session, notification))
              .as(webResponse(202, ""))
      case _ =>
        val failure: JsonRpcMessage = JsonRpcMessage.Failure(
          None,
          McpError
            .invalidRequest("HTTP POST bodies must be JSON-RPC requests or notifications")
            .toErrorObject
        )
        ZIO.succeed(jsonResponse(failure.toJson, Map.empty, status = 400))

  private def validateModernNotification[R](
      router: McpRouter[R],
      req: js.Dynamic,
      notification: JsonRpcMessage.Notification
  ): Either[(Int, McpError), Unit] =
    def header(name: String): Option[String] =
      Option(req.headers.get(name).asInstanceOf[String])
    val acceptOk = header("accept").exists { value =>
      val lower = value.toLowerCase
      (lower.contains("*/*") || lower.contains("application/json")) &&
      (lower.contains("*/*") || lower.contains("text/event-stream"))
    }
    val contentTypeOk = header("content-type").exists(_.toLowerCase.contains("application/json"))
    for
      _ <- Either.cond(
        contentTypeOk,
        (),
        415 -> McpError.headerMismatch("Content-Type must be application/json")
      )
      _ <- Either.cond(
        acceptOk,
        (),
        406 -> McpError.headerMismatch(
          "Accept must include application/json and text/event-stream"
        )
      )
      version <- header("mcp-protocol-version").toRight(
        400 -> McpError.headerMismatch("Missing required MCP-Protocol-Version header")
      )
      _ <- Either.cond(
        version == Protocol.LatestProtocolVersion,
        (),
        400 -> McpError.unsupportedProtocolVersion(
          version,
          List(Protocol.LatestProtocolVersion)
        )
      )
      _ <- router.validateHttpMethod(notification.method, header).left.map(400 -> _)
    yield ()

  private def modernErrorStatus(message: JsonRpcMessage): Option[Int] =
    message match
      case JsonRpcMessage.Failure(_, error)
          if Set(
            ErrorCodes.HeaderMismatch,
            ErrorCodes.MissingRequiredClientCapability,
            ErrorCodes.UnsupportedProtocolVersion
          ).contains(error.code) =>
        Some(400)
      case _ => None

  private def validateModernRequest[R](
      router: McpRouter[R],
      req: js.Dynamic,
      rpc: JsonRpcMessage.Request
  ): Either[(Int, McpError), Unit] =
    def header(name: String): Option[String] =
      Option(req.headers.get(name).asInstanceOf[String])
    val acceptOk = header("accept").exists { value =>
      val lower = value.toLowerCase
      (lower.contains("*/*") || lower.contains("application/json")) &&
      (lower.contains("*/*") || lower.contains("text/event-stream"))
    }
    val contentTypeOk = header("content-type").exists(_.toLowerCase.contains("application/json"))
    for
      _ <- Either.cond(
        contentTypeOk,
        (),
        415 -> McpError.headerMismatch("Content-Type must be application/json")
      )
      _ <- Either.cond(
        acceptOk,
        (),
        406 -> McpError.headerMismatch(
          "Accept must include application/json and text/event-stream"
        )
      )
      context <- RequestContext
        .decode(rpc.params.getOrElse(zio.json.ast.Json.Null))
        .left
        .map(400 -> _)
      headerVersion <- header("mcp-protocol-version").toRight(
        400 -> McpError.headerMismatch("Missing required MCP-Protocol-Version header")
      )
      _ <- Either.cond(
        headerVersion == context.protocolVersion,
        (),
        400 -> McpError.headerMismatch(
          "MCP-Protocol-Version header does not match request metadata"
        )
      )
      _ <- Either.cond(
        context.protocolVersion == Protocol.LatestProtocolVersion,
        (),
        400 -> McpError.unsupportedProtocolVersion(
          context.protocolVersion,
          List(Protocol.LatestProtocolVersion)
        )
      )
      _ <- router.validateHttpHeaders(rpc, header).left.map(400 -> _)
      _ <- Either.cond(
        router.hasModernMethod(rpc.method),
        (),
        404 -> McpError.methodNotFound(rpc.method)
      )
    yield ()

  private def isModernRequest(req: js.Dynamic, message: JsonRpcMessage): Boolean =
    val bodyVersion = message match
      case JsonRpcMessage.Request(_, _, params) =>
        RequestContext.declaredProtocolVersion(params.getOrElse(zio.json.ast.Json.Null))
      case _ => None
    val headerVersion = Option(
      req.headers.get("mcp-protocol-version").asInstanceOf[String]
    )
    bodyVersion.isDefined || headerVersion.exists(version =>
      !Protocol.LegacyProtocolVersions.contains(version)
    )

  // -------------------------------------------------------------------------
  // Web Request / Response helpers
  // -------------------------------------------------------------------------

  private def methodOf(req: js.Dynamic): String = req.method.asInstanceOf[String]

  /** POST guard: `Accept` (if present) must allow application/json; `mcp-protocol-version` (if
    * present) must be supported. Lenient when headers are absent. Mirrors the JVM transport.
    */
  /** Reject (403) when DNS-rebinding protection is on and the request's Host/Origin isn't allowed.
    */
  private def hostError(req: js.Dynamic, allowedHosts: Set[String]): Option[js.Dynamic] =
    val host = Option(req.headers.get("host").asInstanceOf[String])
    val origin = Option(req.headers.get("origin").asInstanceOf[String])
    if HostGuard.isAllowed(host, origin, allowedHosts) then None
    else Some(webResponse(403, "Host/Origin not allowed (DNS-rebinding protection)"))

  /** POST guard, mirroring the JVM backend: `mcp-protocol-version` must be supported (absent ⇒ the
    * pre-header default); `Accept` (if present) must allow `application/json` — and on the
    * streamable transport (`requireSse`) `text/event-stream` too, since request replies stream as
    * SSE.
    */
  private def postHeaderError(req: js.Dynamic, requireSse: Boolean): Option[js.Dynamic] =
    val accept = Option(req.headers.get("accept").asInstanceOf[String]).map(_.toLowerCase)
    val acceptsJson =
      accept.forall(a =>
        a.contains("*/*") || a.contains("application/json") || a.contains("application/*")
      )
    val acceptsSse =
      accept.forall(a =>
        a.contains("*/*") || a.contains("text/event-stream") || a.contains("text/*")
      )
    if !acceptsJson then Some(jsonRpcErrorResponse(406, "Accept must allow application/json"))
    else if requireSse && !acceptsSse then
      Some(jsonRpcErrorResponse(406, "Accept must allow text/event-stream"))
    else None

  private def pathOf(req: js.Dynamic): String =
    // `new URL(req.url).pathname` is the Web-Standard way to pull the path from a Request.
    js.Dynamic.newInstance(js.Dynamic.global.URL)(req.url).pathname.asInstanceOf[String]

  private def sessionIdHeader(req: js.Dynamic): Option[String] =
    Option(req.headers.get(SessionIdHeader).asInstanceOf[String]).filter(_.nonEmpty)

  private def readBody(req: js.Dynamic): ZIO[Any, Throwable, String] =
    ZioJsPromise.fromJsPromise(req.text().asInstanceOf[js.Promise[String]])

  private def jsonResponse(
      body: String,
      extraHeaders: Map[String, String],
      status: Int = 200
  ): js.Dynamic =
    webResponse(status, body, Map("content-type" -> "application/json") ++ extraHeaders)

  /** Transport-level rejection as a JSON-RPC error body (mirrors the JVM `errorResponse`). */
  private def jsonRpcErrorResponse(status: Int, message: String): js.Dynamic =
    val code = if status == 404 then ErrorCodes.SessionNotFound else ErrorCodes.TransportError
    val failure: JsonRpcMessage =
      JsonRpcMessage.Failure(None, McpError(code, message).toErrorObject)
    jsonResponse(failure.toJson, Map.empty, status = status)

  private def webResponse(
      status: Int,
      body: String,
      headers: Map[String, String] = Map("content-type" -> "text/plain")
  ): js.Dynamic =
    new WebResponse(body, WebResponseInit(status, headers)).asInstanceOf[js.Dynamic]

  /** The JS platform seam — re-exported by `ExportsJs` so `import com.tjclp.fastmcp.*` resolves a
    * `TransportBackend` and `McpServer(...)` works on Scala.js.
    */
  given instance: TransportBackend = this

import com.tjclp.fastmcp.server.McpServer

/** JS-only convenience entry points used by integration tests that want a synchronous,
  * `stop()`-able Bun handle instead of forking `runHttp()` (a `ZIO.never`). They build the router
  * eagerly on the default runtime, so they apply to `McpServer[Any]`.
  */
extension (server: McpServer[Any])

  def startStatelessHttp(): BunServer = startHttpHandle(server)
  def startStatefulHttp(): BunServer = startHttpHandle(server)

private def startHttpHandle(server: McpServer[Any]): BunServer =
  val router = Unsafe.unsafe(implicit u =>
    Runtime.default.unsafe.run(server.buildRouter).getOrThrowFiberFailure()
  )
  JsTransportBackend.startBun(router, Runtime.default, server.settings)
