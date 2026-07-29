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
import com.tjclp.fastmcp.server.router.{McpRouter, Session}

/** Scala.js (Bun-first) [[TransportBackend]].
  *
  * Mirrors the JVM backend behavior over the shared [[MessageLoop]]; the only inherent difference
  * is the `Bun.serve`/Node-stdin boundary, which is callback-based and synchronous, so we capture a
  * `Runtime[R]` once (`ZIO.runtime[R]`) and bridge each request/line through that runtime via
  * [[ZioJsPromise]].
  *
  * HTTP: stateless and streamable (durable sessions keyed by the `mcp-session-id` header). Each
  * streamable POST *request* gets a `text/event-stream` response that carries the notifications and
  * sub-requests it emits (progress, sampling, elicitation) before its final reply — so
  * server→client messaging works without a standalone `GET` push channel (`GET` is a spec-allowed
  * `405`). Same semantics as the JVM backend; only the `Bun.serve` / `ReadableStream` shim differs.
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
      _ <- session.outbound.take
        .flatMap(msg => writeLine(MessageLoop.encodeOutbound(msg)))
        .forever
        .forkDaemon
      _ <- ZIO.async[R, Throwable, Unit](cb => wireStdin(router, session, rt, cb))
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
          .orElse(if methodOf(req) == "POST" then postHeaderError(req) else None)
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
              for
                session <- Session.make("stateless")
                reply <- router.dispatch(session, message)
              yield (message, reply) match
                // Structurally invalid frames (id:null, wrong jsonrpc, ...) are client errors.
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
              sessionIdHeader(req) match
                case Some(sid) =>
                  store.get(sid) match
                    case None =>
                      ZIO.succeed(jsonRpcErrorResponse(404, s"Session not found: $sid"))
                    case Some(session) =>
                      session.touch *> respondStreamable(router, session, message, isNew = false)
                case None if MessageLoop.isInitialize(message) =>
                  for
                    sid <- randomId()
                    session <- Session.make(sid)
                    _ <- ZIO.succeed { store(session.sessionId) = session }
                    resp <- respondStreamable(router, session, message, isNew = true)
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
        if settings.disallowDelete then ZIO.succeed(jsonRpcErrorResponse(405, "DELETE disabled"))
        else
          sessionIdHeader(req) match
            case None =>
              ZIO.succeed(
                jsonRpcErrorResponse(400, s"Session ID required in $SessionIdHeader header")
              )
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
      isNew: Boolean
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
        yield sseResponse(reqQueue, req.id, session, isNew, fiber)
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
      dispatchFiber: Fiber.Runtime[?, ?]
  ): js.Dynamic =
    val encoder = js.Dynamic.newInstance(js.Dynamic.global.TextEncoder)()
    val source = js.Dynamic.literal(
      pull = js.Any.fromFunction1((controller: js.Dynamic) =>
        ZioJsPromise.zioToPromise(
          reqQueue.take.map { msg =>
            if MessageLoop.isCloseSentinel(msg) then
              // Dispatch ended replyless (cancelled): end the stream without emitting a frame.
              val _ = controller.close()
            else
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
        (if isNew then Map(SessionIdHeader -> session.sessionId) else Map.empty[String, String])
    val init = js.Dynamic.literal(status = 200, headers = js.Dictionary[String](headers.toSeq*))
    js.Dynamic.newInstance(js.Dynamic.global.Response)(stream, init)

  private def isFinalReply(message: JsonRpcMessage, reqId: RequestId): Boolean =
    message match
      case JsonRpcMessage.Success(id, _) => id == reqId
      case JsonRpcMessage.Failure(Some(id), _) => id == reqId
      case _ => false

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

  private def postHeaderError(req: js.Dynamic): Option[js.Dynamic] =
    val accept = Option(req.headers.get("accept").asInstanceOf[String]).map(_.toLowerCase)
    val acceptsJson =
      accept.forall(a =>
        a.contains("*/*") || a.contains("application/json") || a.contains("application/*")
      )
    val versionOk =
      Option(req.headers.get("mcp-protocol-version").asInstanceOf[String])
        .forall(Protocol.SupportedProtocolVersions.contains)
    if !versionOk then Some(webResponse(400, "Unsupported mcp-protocol-version header"))
    else if !acceptsJson then Some(webResponse(406, "Accept must allow application/json"))
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
