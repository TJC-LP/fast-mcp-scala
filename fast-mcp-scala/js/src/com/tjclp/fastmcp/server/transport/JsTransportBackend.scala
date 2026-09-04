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
  * MCP 2026-07-28 uses stateless POSTs and request-scoped SSE responses. The older
  * initialize/session lifecycle remains a version-selected compatibility path; Bun continues to
  * answer 405 for its standalone legacy GET channel. Modern semantics match the JVM backend; only
  * the `Bun.serve` / `ReadableStream` shim differs.
  */
object JsTransportBackend extends TransportBackend with HttpTransportBackend:

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
    // Same contract as the JVM/Native `BoundedLines`: keep at most `maxFrameChars + 1` chars of
    // an over-long line (so `parseFrame` still answers -32700 FrameTooLong for it), discard the
    // rest of that line as it streams in, and never let the accumulator grow past the cap.
    val max = router.limits.maxFrameChars
    val cap = if max == Int.MaxValue then Int.MaxValue else max + 1
    var buffer = "" // accumulates partial lines across `data` chunks (bounded at `cap`)
    var discarding = false // inside an over-long line: drop input until the next newline
    def dispatch(line: String): Unit =
      if line.nonEmpty then
        val _ = ZioJsPromise.zioToPromise(rt)(
          MessageLoop.handleFrame(router, session, line).flatMap {
            case Some(reply) => writeLine(reply)
            case None => ZIO.unit
          }
        )
    stdin.on(
      "data",
      (chunk: js.Any) =>
        val text = chunk.asInstanceOf[String]
        var from = 0
        var nl = text.indexOf("\n")
        while nl >= 0 do
          if !discarding then
            val room = cap - buffer.length // >= 0; avoid `from + room` overflow at Int.MaxValue
            buffer += text.substring(from, if nl - from <= room then nl else from + room)
          dispatch(buffer.trim)
          buffer = ""
          discarding = false
          from = nl + 1
          nl = text.indexOf("\n", from)
        if !discarding && from < text.length then
          val room = cap - buffer.length
          if text.length - from <= room then buffer += text.substring(from)
          else
            buffer += text.substring(from, from + room)
            discarding = true
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
      // `startBun` validates the settings (IllegalArgumentException → this effect fails), starts
      // the listener and forks the idle-session sweeper; the handle's `stop()` tears both down.
      ZIO.acquireReleaseWith(ZIO.attempt(startBun(router, rt, settings)))(handle =>
        ZIO.succeed(handle.stop())
      )(_ => ZIO.never)
    }

  /** Periodically drop streamable sessions idle past `settings.sessionIdleTimeout` (JVM twin:
    * `JvmHttpBackend.evictIdleSessions`). Bun is single-threaded, so mutating the dictionary
    * in-place is safe. Runs for the listener's lifetime on EVERY start entry (`serveHttp`,
    * `startStatefulHttp()`, `startStatelessHttp()`); the store is additionally bounded by
    * `settings.maxSessions` at mint time.
    *
    * The loop is `sweep *> sleep`, NOT `repeat(Schedule.spaced(..))`: a `Schedule` driver reads
    * `Clock.currentDateTime`, which on Scala.js goes through scala-java-time's
    * `ZoneId.systemDefault()` and throws `ZoneRulesException` without the tzdb artifact — the fiber
    * then died silently after its first tick and no idle session was ever evicted. A failed sweep
    * is logged and the loop continues.
    */
  private[fastmcp] def evictIdleSessions(
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
              ZIO.succeed(store -= sid) *> s.terminate
            }
          yield ()
        val interval = Duration.fromMillis(math.max(timeoutMs / 4, 1000L))
        (sweep.catchAllCause(cause => ZIO.logWarningCause("Idle-session sweep failed", cause)) *>
          ZIO.sleep(interval)).forever

  /** Last-resort `Bun.serve` `error` callback: whatever Bun still sees (nothing should reach it —
    * `guarded` catches every Cause) is answered as a fixed JSON-RPC 500, never the debug page.
    */
  private val bunErrorHandler: js.Function1[js.Dynamic, js.Dynamic] =
    (_: js.Dynamic) => jsonRpcErrorResponse(500, HttpRequestGuards.InternalErrorMessage)

  /** The options handed to `Bun.serve` — a seam so tests can assert `development == false`, the
    * `error` callback and `maxRequestBodySize` without depending on `NODE_ENV`.
    */
  private[fastmcp] def serveOptions[R](
      router: McpRouter[R],
      runtime: Runtime[R],
      settings: McpServerSettings,
      store: js.Dictionary[Session]
  ): BunServeOptions =
    BunServeOptions(
      port = settings.port,
      hostname = settings.host,
      fetch = js.Any.fromFunction2((req: js.Dynamic, server: js.Dynamic) =>
        ZioJsPromise.zioToPromise(runtime)(
          guarded(settings)(handleFetch(router, settings, store, req, clientKeyOf(server, req)))
        )
      ),
      maxRequestBodySize = settings.maxRequestBodyBytes,
      development = false,
      error = bunErrorHandler
    )

  /** First-party error boundary, independent of `NODE_ENV`. The by-name `effect` is evaluated
    * inside `suspendSucceed`, so `handleFetch`'s synchronous prefix (`new URL`, header gates) runs
    * in the fiber and a throw becomes a defect; every Cause is then rendered as a JSON-RPC error
    * with a fixed message — the full cause goes to the server log only. Nothing interrupts the
    * fetch fiber, so no interrupt path exists here.
    */
  private def guarded[R](settings: McpServerSettings)(
      effect: => ZIO[R, Throwable, js.Dynamic]
  ): URIO[R, js.Dynamic] =
    ZIO.suspendSucceed(effect).catchAllCause { cause =>
      val response = cause.failureOption match
        case Some(t) if Option(t.getMessage).exists(_.contains("maxRequestBodySize")) =>
          // Bun aborted the body read at its own cap; mirror the first-party 413.
          reject(HttpRequestGuards.bodyTooLargeRejection(settings))
        case Some(_) => jsonRpcErrorResponse(400, "Body read error")
        case None => jsonRpcErrorResponse(500, HttpRequestGuards.InternalErrorMessage)
      ZIO.logWarningCause("HTTP handler failed", cause).as(response)
    }

  /** Peer address of the request via `server.requestIP(req)` — the default owner key for
    * bearer-task buckets. `None` when the socket is already gone or the server handle is absent.
    */
  private def clientKeyOf(server: js.Dynamic, req: js.Dynamic): Option[String] =
    scala.util
      .Try {
        Option(server)
          .filterNot(js.isUndefined)
          .flatMap(s => Option(s.requestIP(req)))
          .filterNot(js.isUndefined)
          .flatMap(a => Option(a.address.asInstanceOf[String]))
      }
      .toOption
      .flatten

  /** Start the Bun HTTP listener with the idle-session sweeper forked alongside it, and return a
    * [[BunHttpHandle]] whose `stop()` tears both down. Every entry — `serveHttp` / `runHttp()`,
    * `startStatefulHttp()`, `startStatelessHttp()` — goes through here, so `sessionIdleTimeout` and
    * `maxSessions` are honoured for the listener's whole lifetime. Fails fast with
    * `IllegalArgumentException` when `HttpRequestGuards.validateSettings` rejects the settings.
    */
  private[fastmcp] def startBun[R](
      router: McpRouter[R],
      runtime: Runtime[R],
      settings: McpServerSettings,
      store: js.Dictionary[Session] = js.Dictionary.empty[Session] // Bun is single-threaded
  ): BunHttpHandle =
    HttpRequestGuards
      .validateSettings(settings)
      .left
      .foreach(msg => throw new IllegalArgumentException(s"Invalid HTTP settings: $msg"))
    val server = Bun.serve(serveOptions(router, runtime, settings, store))
    // `unsafe.fork`, never `unsafe.run`: Runtime.unsafe.run throws on Scala.js when the effect
    // suspends ("Cannot block for result to be set in JavaScript").
    val sweeper =
      Unsafe.unsafe(implicit u => runtime.unsafe.fork(evictIdleSessions(store, settings)))
    new BunHttpHandle(
      server,
      () => Unsafe.unsafe(implicit u => { val _ = runtime.unsafe.fork(sweeper.interrupt) })
    )

  private def handleFetch[R](
      router: McpRouter[R],
      settings: McpServerSettings,
      store: js.Dictionary[Session],
      req: js.Dynamic,
      clientKey: Option[String]
  ): ZIO[R, Throwable, js.Dynamic] =
    if pathOf(req) != settings.httpEndpoint then ZIO.succeed(webResponse(404, "Not Found"))
    else
      val headerErr =
        if methodOf(req) == "POST" then postHeaderError(req, settings)
        else hostError(req, settings)
      headerErr match
        case Some(err) => ZIO.succeed(err)
        case None =>
          if settings.stateless then handleStateless(router, settings, req, clientKey)
          else handleStreamable(router, settings, store, req, clientKey)

  private def reject(r: HttpRequestGuards.Rejection): js.Dynamic =
    jsonRpcErrorResponse(r.status, r.message)

  private def handleStateless[R](
      router: McpRouter[R],
      settings: McpServerSettings,
      req: js.Dynamic,
      clientKey: Option[String]
  ): ZIO[R, Throwable, js.Dynamic] =
    methodOf(req) match
      case "POST" =>
        readBody(req).flatMap { body =>
          if HttpRequestGuards.bodyTooLarge(body, settings) then
            ZIO.succeed(reject(HttpRequestGuards.bodyTooLargeRejection(settings)))
          else
            MessageLoop.parseFrame(body, router.limits) match
              case Left(parseFailure) =>
                ZIO.succeed(jsonResponse(parseFailure.toJson, Map.empty, status = 400))
              case Right(message) =>
                if isModernRequest(req, message) then
                  modernPost(router, req, message, settings, clientKey)
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
      req: js.Dynamic,
      clientKey: Option[String]
  ): ZIO[R, Throwable, js.Dynamic] =
    methodOf(req) match
      case "POST" =>
        readBody(req).flatMap { body =>
          if HttpRequestGuards.bodyTooLarge(body, settings) then
            ZIO.succeed(reject(HttpRequestGuards.bodyTooLargeRejection(settings)))
          else
            // Parse BEFORE touching the session store: a malformed or non-initialize body must
            // never mint a durable session (JVM transport does the same).
            MessageLoop.parseFrame(body, router.limits) match
              case Left(parseFailure) =>
                ZIO.succeed(jsonResponse(parseFailure.toJson, Map.empty, status = 400))
              case Right(message) =>
                if isModernRequest(req, message) then
                  modernPost(router, req, message, settings, clientKey)
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
                      mintSession(router, store, message, settings)
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
                  ZIO.succeed { store -= sid } *> session.terminate.as(webResponse(200, ""))
                case None =>
                  ZIO.succeed(jsonRpcErrorResponse(404, s"Session not found: $sid"))

      case _ =>
        // GET is a no-op 405: every server→client message streams on its request's own POST SSE
        // response (see respondStreamable), so a standalone GET push channel isn't needed.
        ZIO.succeed(
          jsonRpcErrorResponse(405, "No standalone GET stream; server→client rides the POST SSE")
        )

  /** Mint a durable session for a header-less legacy `initialize`, bounded by
    * `settings.maxSessions` (JVM twin: `JvmHttpBackend.mintSession`). Bun is single-threaded, so
    * the admission decision, the eviction and the insert happen in ONE synchronous `ZIO.succeed`
    * block and the store never exceeds the cap even with N initializes in flight. At the cap the
    * longest-idle session WITHOUT a live GET is evicted (queue shut down, WARN logged) and the
    * newcomer admitted; only when every stored session holds a live GET is the request refused with
    * 503.
    */
  private def mintSession[R](
      router: McpRouter[R],
      store: js.Dictionary[Session],
      message: JsonRpcMessage,
      settings: McpServerSettings
  ): ZIO[R, Throwable, js.Dynamic] =
    for
      sid <- randomId()
      snapshot <-
        if HttpRequestGuards.capReached(store.size, settings) then
          ZIO.foreach(store.toList) { case (id, s) =>
            (s.lastSeen zip s.hasActiveGet).map((seen, live) => (id, seen, live))
          }
        else ZIO.succeed(Nil)
      session <- Session.make(sid)
      outcome <- ZIO.succeed {
        if !HttpRequestGuards.capReached(store.size, settings) then
          store(sid) = session
          Right(None)
        else
          HttpRequestGuards.pickEvictable(snapshot).flatMap(store.get) match
            case Some(victim) =>
              store -= victim.sessionId
              store(sid) = session
              Right(Some(victim))
            case None => Left(())
      }
      resp <- outcome match
        case Right(None) => respondStreamable(router, session, message, isNew = true, settings)
        case Right(Some(victim)) =>
          ZIO.logWarning(
            s"Legacy session cap ${settings.maxSessions.getOrElse(0)} reached; evicted idle session ${victim.sessionId}"
          ) *> victim.terminate *>
            respondStreamable(router, session, message, isNew = true, settings)
        case Left(()) =>
          session.terminate
            .as(jsonRpcErrorResponse(503, HttpRequestGuards.SessionLimitMessage))
    yield resp

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
          takeNext
            .map {
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
            // A defect mid-stream ends the stream instead of rejecting the pull promise — Bun
            // would otherwise surface the raw error itself. A pull racing the client's cancel
            // (interrupted `take`, or the controller already closed) is benign and not logged.
            .catchAllCause { cause =>
              val benign = cause.isInterruptedOnly || cause.dieOption.exists {
                case _: js.JavaScriptException => true
                case _ => false
              }
              (if benign then ZIO.unit else ZIO.logWarningCause("SSE pull failed", cause)) *>
                ZIO.succeed { val _ = scala.util.Try(controller.error(js.Error("stream failed"))) }
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
      settings: McpServerSettings,
      clientKey: Option[String]
  ): ZIO[R, Throwable, js.Dynamic] =
    // `clientKey` is the peer address (`server.requestIP`) — the default owner key for bearer-task
    // buckets (`TaskOwnerKey.Transport`); behind a reverse proxy use `TaskOwnerKey.Custom`.
    message match
      case rpc: JsonRpcMessage.Request =>
        validateModernRequest(router, req, rpc) match
          case Left((status, error)) =>
            val failure: JsonRpcMessage =
              JsonRpcMessage.Failure(Some(rpc.id), error.toErrorObject)
            ZIO.succeed(jsonResponse(failure.toJson, Map.empty, status))
          case Right(_) =>
            for
              session <- Session.make(s"request-${rpc.id.toString}", clientKey = clientKey)
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

  private def headerOf(req: js.Dynamic)(name: String): Option[String] =
    Option(req.headers.get(name).asInstanceOf[String])

  private def validateModernNotification[R](
      router: McpRouter[R],
      req: js.Dynamic,
      notification: JsonRpcMessage.Notification
  ): Either[(Int, McpError), Unit] =
    ModernHttpValidation.validateNotification(router, notification, headerOf(req))

  private def modernErrorStatus(message: JsonRpcMessage): Option[Int] =
    ModernHttpValidation.errorStatus(message)

  private def validateModernRequest[R](
      router: McpRouter[R],
      req: js.Dynamic,
      rpc: JsonRpcMessage.Request
  ): Either[(Int, McpError), Unit] =
    ModernHttpValidation.validateRequest(router, rpc, headerOf(req))

  private def isModernRequest(req: js.Dynamic, message: JsonRpcMessage): Boolean =
    ModernHttpValidation.isModern(headerOf(req), message)

  // -------------------------------------------------------------------------
  // Web Request / Response helpers
  // -------------------------------------------------------------------------

  private def methodOf(req: js.Dynamic): String = req.method.asInstanceOf[String]

  /** Reject (403) when DNS-rebinding protection is on and the request's Host/Origin isn't allowed
    * (full-origin match — the shared [[HostGuard]] via [[HttpRequestGuards.hostGate]]).
    */
  private def hostError(req: js.Dynamic, settings: McpServerSettings): Option[js.Dynamic] =
    HttpRequestGuards.hostGate(headerOf(req), settings).map(reject)

  /** POST guard, identical to the JVM backend through the shared [[HttpRequestGuards.postGate]]:
    * 403 Host/Origin → 415 unless `Content-Type` is `application/json` → 413 when the declared
    * `Content-Length` exceeds `maxRequestBodyBytes` → 406 unless `Accept` allows `application/json`
    * (and `text/event-stream` on the streamable transport). Runs BEFORE the body is read or any
    * session is minted.
    *
    * As on the JVM, `mcp-protocol-version` is deliberately not checked here: on POST the version
    * comes from the initialize payload (legacy) or from the modern validation path (`-32022`).
    */
  private def postHeaderError(req: js.Dynamic, settings: McpServerSettings): Option[js.Dynamic] =
    HttpRequestGuards
      .postGate(headerOf(req), settings, requireSse = !settings.stateless)
      .map(reject)

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
    * `TransportBackend` and `McpServer(...)` works on Scala.js. One object provides both halves:
    * Scala.js DCE follows call sites the same way native-image reachability does, so stdio-only
    * bundles still shed `Bun.serve`.
    */
  given instance: TransportBackend = this
  given httpInstance: HttpTransportBackend = this

import com.tjclp.fastmcp.server.McpServer

/** Stop-able Bun listener: the `Bun.serve` handle plus the idle-session sweeper that runs for its
  * lifetime. Returned by EVERY start entry (`startStatelessHttp()` / `startStatefulHttp()`; used
  * internally by `runHttp()`), so `sessionIdleTimeout` and `maxSessions` are honoured everywhere.
  * `stop()` interrupts the sweeper and stops the listener. The raw Bun server is `server`.
  */
final class BunHttpHandle private[transport] (val server: BunServer, release: () => Unit):
  def port: Int = server.port
  def hostname: String = server.hostname
  def url: js.Dynamic = server.url

  def stop(): Unit =
    release()
    server.stop()

/** JS-only convenience entry points for callers that want a synchronous, `stop()`-able handle
  * instead of forking `runHttp()` (a `ZIO.never`) — integration tests and the shipped
  * `ConformanceServerJs`. They build the router eagerly on the default runtime, so they apply to
  * `McpServer[Any]`. Both run the idle-session sweeper and honour every HTTP hardening setting
  * exactly like `runHttp()`; call `stop()` on the returned [[BunHttpHandle]] to tear down.
  */
extension (server: McpServer[Any])

  def startStatelessHttp(): BunHttpHandle = startHttpHandle(server)
  def startStatefulHttp(): BunHttpHandle = startHttpHandle(server)

private def startHttpHandle(server: McpServer[Any]): BunHttpHandle =
  val router = Unsafe.unsafe(implicit u =>
    Runtime.default.unsafe.run(server.buildRouter).getOrThrowFiberFailure()
  )
  JsTransportBackend.startBun(router, Runtime.default, server.settings)
