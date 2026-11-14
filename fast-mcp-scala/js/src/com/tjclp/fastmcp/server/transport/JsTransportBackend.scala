package com.tjclp.fastmcp
package server.transport

import scala.scalajs.js

import zio.*
import zio.json.*

import com.tjclp.fastmcp.core.ErrorCodes
import com.tjclp.fastmcp.jsonrpc.{JsonRpcMessage, McpError, RequestId}
import com.tjclp.fastmcp.facades.node.{NodeProcess, NodeReadableStream}
import com.tjclp.fastmcp.facades.runtime.{
  Bun,
  BunServeOptions,
  BunServer,
  WebCrypto,
  WebResponse,
  WebResponseInit
}
import com.tjclp.fastmcp.interop.ZioJsPromise
import com.tjclp.fastmcp.server.{McpServerSettings, ServerClock}
import com.tjclp.fastmcp.server.router.{McpRouter, RequestContext, RequestHeaders, Session}
import com.tjclp.fastmcp.server.transport.http.StreamableHttpHandler

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

  private val SessionIdHeader = HttpRequestGuards.SessionIdHeader

  // -------------------------------------------------------------------------
  // stdio
  // -------------------------------------------------------------------------

  override def serveStdio[R](
      router: McpRouter[R],
      settings: McpServerSettings
  ): ZIO[R, Throwable, Unit] =
    for
      rt <- ZIO.runtime[R]
      session <- Session.makeDrained("stdio", router.limits.maxOutboundMessages, router.clock)
      // Drain server-initiated messages (log/progress notifications) to stdout. The channel is
      // bounded and back-pressured: an emitter waits for this writer rather than lose a message.
      // The writer stops at the close sentinel, offered below after the last reply at stdin EOF.
      drainer <- session.outbound.take
        .tap(msg =>
          ZIO.unless(MessageLoop.isCloseSentinel(msg))(writeLine(MessageLoop.encodeOutbound(msg)))
        )
        .repeatUntil(MessageLoop.isCloseSentinel)
        .forkDaemon
      // stdin EOF completes the async once every frame read has been answered (or the drain
      // timeout has passed); interruption stops the in-flight dispatches instead. Then whatever is
      // still queued for the writer is flushed, the drainer goes down, and nothing is buffered for
      // a writer that is gone.
      _ <- ZIO
        .asyncInterrupt[R, Throwable, Unit](cb =>
          Left(
            wireStdin(
              router,
              session,
              rt,
              cb,
              NodeProcess.stdin,
              writeLine,
              settings.stdioDrainTimeout
            )
          )
        )
        .zipLeft(
          (session.outbound.offer(MessageLoop.CloseSentinel) *> drainer.await)
            .timeout(StdioLoop.SettleTimeout)
            .exit
        )
        .ensuring(drainer.interruptFork *> session.closeOutbound)
    yield ()

  /** The Bun/Node stdin callbacks: the same lifecycle contract as the JVM and Scala Native
    * `StdioLoop`, on callback IO.
    *
    *   - Every frame read is answered: `end` completes `done` only when no dispatch is in flight,
    *     or after `drainTimeout`, when what is still running is interrupted.
    *   - The final line is a frame with or without its newline.
    *   - `initialize` is finished before the next frame runs; lines that arrive meanwhile wait in
    *     order. Every other frame is dispatched concurrently.
    *
    * Returns the effect that interrupts the in-flight dispatches (`serveStdio` runs it when the
    * server is interrupted). Input/output seams let tests drive the real chunk callbacks without
    * replacing process IO.
    */
  private[fastmcp] def wireStdin[R](
      router: McpRouter[R],
      session: Session,
      rt: Runtime[R],
      done: ZIO[R, Throwable, Unit] => Unit,
      stdin: NodeReadableStream,
      emit: String => UIO[Unit],
      drainTimeout: Duration = McpServerSettings.DefaultStdioDrainTimeout
  ): UIO[Unit] =
    stdin.setEncoding("utf8")
    // Same contract as the JVM/Native `BoundedLines`: keep at most `maxFrameChars + 1` chars of
    // an over-long line (so `parseFrame` still answers -32700 FrameTooLong for it), discard the
    // rest of that line as it streams in, and never let the accumulator grow past the cap.
    val max = router.limits.maxFrameChars
    val cap = if max == Int.MaxValue then Int.MaxValue else max + 1
    var buffer = "" // accumulates partial lines across `data` chunks (bounded at `cap`)
    var discarding = false // inside an over-long line: drop input until the next newline

    // Lifecycle state. Bun runs these callbacks and every fiber on one thread, so plain fields
    // are safe: nothing here is touched between two statements of a callback.
    val inFlight = scala.collection.mutable.Map.empty[Long, Option[Fiber.Runtime[Nothing, Unit]]]
    val held = scala.collection.mutable.Queue.empty[String] // lines behind a running `initialize`
    var nextId = 0L
    var initializing = false
    var ended = false
    val idle = Unsafe.unsafe(implicit unsafe => Promise.unsafe.make[Nothing, Unit](FiberId.None))

    // Completed once stdin has ended and every ordered frame read (`initialize`, a client's
    // answer to a server→client request) has run: only then may the session stop accepting answers.
    val orderedDone =
      Unsafe.unsafe(implicit unsafe => Promise.unsafe.make[Nothing, Unit](FiberId.None))

    def settleIfIdle(): Unit =
      if ended && !initializing && held.isEmpty then
        val _ =
          Unsafe.unsafe(implicit unsafe => Runtime.default.unsafe.fork(orderedDone.succeed(())))
        if inFlight.isEmpty then
          val _ = Unsafe.unsafe(implicit unsafe => Runtime.default.unsafe.fork(idle.succeed(())))

    def run(line: String): Unit =
      val parsed = MessageLoop.parseFrame(line, router.limits)
      val ordered = MessageLoop.isOrderedStdioFrame(parsed)
      val id = nextId
      nextId += 1
      // Entered before the fork and removed by the fiber's exit observer, so the entry cannot
      // outlive the fiber even when the fiber ends before `fork` returns.
      inFlight(id) = None
      if ordered then initializing = true
      val answer = MessageLoop
        .dispatchParsed(router, session, parsed)
        .flatMap {
          case Some(reply) => emit(reply)
          case None => ZIO.unit
        }
        // A defect never becomes an unhandled promise rejection or a dead fiber nobody saw. The
        // cause goes to the ZIO logger (stderr under the stdio runner) and the client gets no
        // frame.
        .catchAllCause(cause => ZIO.logWarningCause("stdio dispatch failed", cause))
      Unsafe.unsafe { implicit unsafe =>
        val fiber = rt.unsafe.fork(answer)
        fiber.unsafe.addObserver(_ => finished(id, ordered))
        if inFlight.contains(id) then inFlight(id) = Some(fiber)
      }

    def finished(id: Long, ordered: Boolean): Unit =
      val _ = inFlight.remove(id)
      if ordered then
        initializing = false
        while !initializing && held.nonEmpty do run(held.dequeue())
      settleIfIdle()

    // `initialize` is answered before any later frame runs, so a client that writes its handshake
    // and its first requests in one burst is never told "not initialized". The lines held back are
    // those of the chunks delivered while it runs; it is a built-in that awaits nothing.
    def dispatch(line: String): Unit =
      if MessageLoop.shouldDispatchStdioFrame(line, router.limits) then
        if initializing then held.enqueue(line) else run(line)

    val interruptInFlight: UIO[Unit] = ZIO.suspendSucceed {
      held.clear()
      ZIO.foreachDiscard(inFlight.values.flatten.toList)(_.interruptFork)
    }

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
            if nl - from > room then discarding = true
          // Strip only a real CRLF terminator. A truncated prefix ending in CR must retain its
          // overflow indication, just like BoundedLines on the JVM and Native.
          val frame =
            if !discarding && buffer.endsWith("\r") then buffer.dropRight(1) else buffer
          dispatch(frame)
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
    stdin.on(
      "end",
      (_: js.Any) =>
        // A final line without a newline is a frame, as `BoundedLines` flushes it on the JVM and
        // Scala Native.
        if buffer.nonEmpty || discarding then
          val frame =
            if !discarding && buffer.endsWith("\r") then buffer.dropRight(1) else buffer
          buffer = ""
          discarding = false
          dispatch(frame)
        ended = true
        settleIfIdle()
        // No frame can arrive any more: a handler waiting for the client's answer to a
        // server→client request is failed now. Then wait for the replies owed, up to the drain
        // timeout; what is still running after it is interrupted and given a moment to finish.
        done(
          orderedDone.await *> session.closeInbound *>
            idle.await.timeout(drainTimeout).flatMap {
              case Some(_) => ZIO.unit
              case None => interruptInFlight *> idle.await.timeout(StdioLoop.SettleTimeout).unit
            }
        )
    )
    interruptInFlight

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
    * `StreamableHttpHandler.evictIdleSessions`). Bun is single-threaded, so mutating the dictionary
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
      settings: McpServerSettings,
      clock: ServerClock = ServerClock.system
  ): UIO[Unit] =
    settings.sessionIdleTimeout match
      case None => ZIO.unit
      case Some(timeout) =>
        val timeoutMs = timeout.toMillis
        val sweep =
          for
            now <- ZIO.succeed(clock.monotonicMs())
            expired <- ZIO.filter(store.toList) { case (_, s) =>
              (s.lastSeen zip s.hasActiveGet).map((seen, live) => !live && now - seen > timeoutMs)
            }
            _ <- ZIO.foreachDiscard(expired) { case (sid, s) =>
              ZIO.succeed(store -= sid) *> s.terminate
            }
          yield ()
        val interval = Duration.fromMillis(math.max(timeoutMs / 4, 1000L))
        (sweep.catchAllCause(cause => ZIO.logWarningCause("Idle-session sweep failed", cause)) *>
          clock.sleep(interval)).forever

  /** Last-resort `Bun.serve` `error` callback: whatever Bun still sees (nothing should reach it —
    * `guarded` catches every Cause) is answered as a fixed JSON-RPC 500, never the debug page.
    */
  private val bunErrorHandler: js.Function1[js.Dynamic, js.Dynamic] =
    (_: js.Dynamic) => jsonRpcErrorResponse(500, HttpRequestGuards.InternalErrorMessage)

  /** The options handed to `Bun.serve` — a seam so tests can assert `development == false`, the
    * `error` callback, `maxRequestBodySize` and `idleTimeout == 0` without depending on `NODE_ENV`.
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
          guarded(settings)(
            handleFetch(
              router,
              settings,
              store,
              req,
              // The peer address buckets bearer tasks: skip the per-request lookup when tasks are
              // off. The one other reader — a header-less legacy `initialize`, which records who
              // opens a session — looks it up itself from `server`.
              if settings.tasks.enabled then clientKeyOf(server, req) else None,
              server
            )
          )
        )
      ),
      maxRequestBodySize = settings.maxRequestBodyBytes,
      development = false,
      error = bunErrorHandler,
      // Bun's 10 s default would cut a quiet SSE response before a slow tool replies (D6 8.15);
      // 0 disables the runtime idle close, as on the JVM listener. See `BunServeOptions`.
      idleTimeout = 0
    )

  /** First-party error boundary, independent of `NODE_ENV`. The by-name `effect` is evaluated
    * inside `suspendSucceed`, so `handleFetch`'s synchronous prefix (`new URL`, header gates) runs
    * in the fiber and a throw becomes a defect; every Cause is then rendered as a JSON-RPC error
    * with a fixed message — the full cause goes to the server log only. The one interruption that
    * reaches here is a client that went away while its request waited for a first frame (or, for a
    * header-less `initialize`, for its dispatch); it is not logged as a failure.
    */
  private def guarded[R](settings: McpServerSettings)(
      effect: => ZIO[R, Throwable, js.Dynamic]
  ): URIO[R, js.Dynamic] =
    ZIO.suspendSucceed(effect).catchAllCause { cause =>
      val response = cause.failureOption match
        case Some(BodyReadFailure(t))
            if Option(t.getMessage).exists(_.contains("maxRequestBodySize")) =>
          // Bun aborted the body read at its own cap (Bun 1.4.1 rejects `text()` with
          // "... exceeds maxRequestBodySize"); mirror the first-party 413. Should the message
          // ever change, the read failure still falls to the fixed 400 below.
          reject(HttpRequestGuards.bodyTooLargeRejection(settings))
        case Some(_: BodyReadFailure) => jsonRpcErrorResponse(400, "Body read error")
        // Any other typed failure is a server-side problem, not the client's body.
        case _ => jsonRpcErrorResponse(500, HttpRequestGuards.InternalErrorMessage)
      // The client went away while the request waited for its first frame (`onClientAbort` shut
      // the request's queue down, or stopped an `initialize`): nobody is left to answer, and it is
      // not a handler failure.
      if cause.isInterruptedOnly then ZIO.succeed(response)
      else ZIO.logWarningCause("HTTP handler failed", cause).as(response)
    }

  /** Marks a failure raised while reading the request body (`req.text()`), so `guarded` can
    * distinguish a client-side body problem (400/413) from every other failure (500).
    */
  private final case class BodyReadFailure(cause: Throwable)
      extends Exception(Option(cause.getMessage).getOrElse("body read failed"), cause)

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
    * `IllegalArgumentException` when `HttpRequestGuards.validateSettings` rejects the settings, and
    * logs `HttpRequestGuards.startupWarnings` at WARN.
    */
  private[fastmcp] def startBun[R](
      router: McpRouter[R],
      runtime: Runtime[R],
      settings: McpServerSettings,
      store: js.Dictionary[Session] = js.Dictionary.empty[Session], // Bun is single-threaded
      onStop: URIO[R, Any] = ZIO.unit // e.g. `TaskManager.shutdown` for the handle-based entries
  ): BunHttpHandle =
    HttpRequestGuards
      .validateSettings(settings)
      .left
      .foreach(msg => throw new IllegalArgumentException(s"Invalid HTTP settings: $msg"))
    // Startup diagnostics (a non-loopback bind with no Host/Origin allow-list), through the app's
    // ZIO logger like every other warning of this backend. Forked: see the sweeper note below.
    HttpRequestGuards.startupWarnings(settings).foreach { msg =>
      Unsafe.unsafe(implicit u => { val _ = runtime.unsafe.fork(ZIO.logWarning(msg)) })
    }
    val server = Bun.serve(serveOptions(router, runtime, settings, store))
    // `unsafe.fork`, never `unsafe.run`: Runtime.unsafe.run throws on Scala.js when the effect
    // suspends ("Cannot block for result to be set in JavaScript").
    val sweeper =
      Unsafe.unsafe(implicit u =>
        runtime.unsafe.fork(evictIdleSessions(store, settings, router.clock))
      )
    new BunHttpHandle(
      server,
      () =>
        Unsafe.unsafe(implicit u => { val _ = runtime.unsafe.fork(sweeper.interrupt *> onStop) })
    )

  private def handleFetch[R](
      router: McpRouter[R],
      settings: McpServerSettings,
      store: js.Dictionary[Session],
      req: js.Dynamic,
      clientKey: Option[String],
      server: js.Dynamic
  ): ZIO[R, Throwable, js.Dynamic] =
    // `new URL(req.url)` throws on an unparseable authority (e.g. `Host: 127.0.0.1:99999`). With
    // the Host/Origin guard on, the guard refuses such a `Host` itself (403, as on the JVM); with
    // it off Bun still cannot tell which path was asked for, so the answer is a plain 400 — never
    // a defect. (The JVM backends, which do not build a URL, serve that request when the guard is
    // off.)
    val path = scala.util.Try(pathOf(req)).toOption
    val method = methodOf(req)
    if path.isEmpty then
      ZIO.succeed(
        hostError(req, settings).getOrElse(jsonRpcErrorResponse(400, "Malformed request URL"))
      )
    // The endpoint is an exact path match, as on the JVM backends (`HttpRequestGuards.pathMatches`;
    // `URL.pathname` carries no query string). Anything else is an empty 404, before any gate.
    else if !path.exists(HttpRequestGuards.pathMatches(_, settings)) then
      // ... unless `settings.httpRoutes` has a route for that path: the Host/Origin guard, 405 for
      // any method but GET, then the author's handler (JVM twin: `StreamableHttpHandler.handle`).
      // The request body is never read, and neither the router nor the session store is touched.
      path.flatMap(p => HttpRequestGuards.routeFor(p, settings).map(p -> _)) match
        case None => ZIO.succeed(emptyResponse(404))
        case Some((routePath, route)) =>
          HttpRequestGuards.routeGate(method, headerOf(req), settings) match
            case Some(rejection) => ZIO.succeed(reject(rejection))
            case None =>
              HttpRequestGuards.runRoute(route, routePath, headerOf(req)).map { r =>
                webResponse(r.status, r.body, r.headers.toMap + ("content-type" -> r.contentType))
              }
    else
      // POST: the header gates. Anything else: Host/Origin, then 405 for a method this endpoint
      // does not serve — every method but DELETE here, since Bun has no standalone GET stream
      // (`pushChannel = false`). The JVM twin is `StreamableHttpHandler.admit`.
      val headerErr =
        if method == "POST" then postHeaderError(req, settings)
        else
          HttpRequestGuards
            .nonPostGate(method, headerOf(req), settings, pushChannel = false)
            .map(reject)
      headerErr match
        case Some(err) => ZIO.succeed(err)
        case None =>
          val handled =
            if settings.stateless then handleStateless(router, settings, req, clientKey)
            else handleStreamable(router, settings, store, req, clientKey, server)
          // Bind the allow-listed headers of THIS request around its dispatch (JVM twin:
          // `StreamableHttpHandler.post`). `Headers.get` already joins a repeated header.
          if settings.contextHeaders.isEmpty || methodOf(req) != "POST" then handled
          else
            RequestHeaders.scoped(RequestHeaders.normalise(settings.contextHeaders), headerOf(req))(
              handled
            )

  /** A shared-gate refusal as its JSON-RPC error response, with the code and headers it carries
    * (`Allow` on a 405; JVM twin: `StreamableHttpHandler.reject`). Bun drops the body of a reply to
    * a `HEAD` by itself.
    */
  private def reject(r: HttpRequestGuards.Rejection): js.Dynamic =
    val failure: JsonRpcMessage =
      JsonRpcMessage.Failure(None, McpError(r.errorCode, r.message).toErrorObject)
    jsonResponse(failure.toJson, r.headers.toMap, status = r.status)

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
                    session <- Session.makeRequestScoped(
                      "stateless",
                      supportsTasks = false,
                      clock = router.clock
                    )
                    // Legacy stateless compatibility mode starts ready without a handshake.
                    _ <- session.markInitialized
                    reply <- router.dispatch(session, message)
                  yield (message, reply) match
                    case (_: JsonRpcMessage.Invalid, Some(r)) =>
                      jsonResponse(r.toJson, Map.empty, status = 400)
                    case (_, Some(r)) => jsonResponse(r.toJson, Map.empty)
                    case (_, None) => webResponse(202, "")
        }
      case other =>
        // Unreachable: `handleFetch` admits only POST on the stateless adapter.
        ZIO.succeed(methodNotAllowed(other, settings))

  private def handleStreamable[R](
      router: McpRouter[R],
      settings: McpServerSettings,
      store: js.Dictionary[Session],
      req: js.Dynamic,
      clientKey: Option[String],
      server: js.Dynamic
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
                            respondStreamable(
                              router,
                              session,
                              message,
                              isNew = false,
                              settings,
                              req
                            )
                    case None =>
                      message match
                        case rpc: JsonRpcMessage.Request if MessageLoop.isInitialize(rpc) =>
                          // The peer address, read here when the task buckets did not need it.
                          val peer = clientKey.orElse(clientKeyOf(server, req))
                          mintSession(router, store, rpc, settings, peer, req)
                        case _ =>
                          ZIO.succeed(
                            jsonRpcErrorResponse(
                              400,
                              s"$SessionIdHeader header is required (only initialize may open a session)"
                            )
                          )
        }

      case "DELETE" =>
        // `handleFetch` has already refused a DELETE that is disabled (`disallowDelete`) or that
        // declares 2026-07-28 (405). One without a session id is 400, an unknown id 404.
        sessionIdHeader(req) match
          case None => ZIO.succeed(reject(HttpRequestGuards.SessionRequired))
          case Some(sid) =>
            store.get(sid) match
              case Some(session) =>
                ZIO.succeed { store -= sid } *> session.terminate.as(webResponse(200, ""))
              case None =>
                ZIO.succeed(jsonRpcErrorResponse(404, s"Session not found: $sid"))

      case other =>
        // Unreachable: `handleFetch` admits only POST and DELETE. GET in particular is 405 there —
        // every server→client message streams on its request's own POST SSE response (see
        // respondStreamable), so there is no standalone GET push channel on Bun.
        ZIO.succeed(methodNotAllowed(other, settings))

  /** Answer a header-less legacy `initialize`, and store a durable session for it only once it has
    * succeeded (JVM twin: `StreamableHttpHandler.mintSession` / `admit`, which document the rules).
    *
    * The `initialize` is dispatched on a session that is not in the store. A failed one — params
    * that do not decode, or `clientInfo` / `capabilities` over the retention budget
    * (`ClientRetention`, `-32602`) — is answered without an `mcp-session-id` and leaves the store
    * exactly as it was. A successful one is admitted against `settings.maxSessions`: Bun is
    * single-threaded, so the snapshot of every stored session, the choice of the session that makes
    * room (`HttpRequestGuards.pickVictim`), the eviction and the insert all happen in ONE
    * synchronous `ZIO.succeed` block (`Session.idleStateUnsafe`, no fiber yield in between). The
    * store never exceeds the cap with N initializes in flight, and a newcomer is never refused
    * while a session can make room for it. The evicted session is terminated (`Session.terminate`:
    * in-flight requests interrupted, its tasks released, queue shut down; WARN logged). There is no
    * standalone GET stream on Bun, so no stored session is ever exempt and a full store always has
    * a session to give up; the 503 is kept for parity.
    *
    * The store update and the termination it calls for are one uninterruptible step, as on the JVM:
    * a session that has left the store cannot be reached by id any more, so nothing else would ever
    * terminate it.
    *
    * A client that goes away is seen through the request's abort signal, as for every other request
    * on Bun (`onClientAbort`; a request fiber is never interrupted from outside here). The dispatch
    * runs on a fiber of its own that the signal interrupts, and the signal is read once more in the
    * admission block itself: a client that left before then never learns the session's id, so
    * nothing is stored and nobody is evicted for it.
    *
    * `peer` is the address the `initialize` came from: at the cap a client holding more sessions
    * than the newcomer's client is the one that gives a session up. `http` is the Bun `Request`,
    * for its abort signal.
    */
  private def mintSession[R](
      router: McpRouter[R],
      store: js.Dictionary[Session],
      message: JsonRpcMessage.Request,
      settings: McpServerSettings,
      peer: Option[String],
      http: js.Dynamic
  ): ZIO[R, Throwable, js.Dynamic] =
    for
      sid <- randomId()
      // Bun has no standalone GET stream (405), so nothing would ever read this session's
      // out-of-request channel: it buffers nothing (see `Session.outboundCapacity`).
      session <- Session.makeProtocolSession(sid, peer, outboundCapacity = 0, router.clock)
      // Anything the handler chain sends while it runs rides this reply, ahead of the result.
      sink <- Queue.unbounded[JsonRpcMessage]
      dispatch <- session.runWithSink(sink)(router.dispatch(session, message)).fork
      _ <- ZIO.succeed(onClientAbort(http)(dispatch.interrupt))
      exit <- dispatch.await
      sent <- sink.takeAll <* sink.shutdown
      resp <- exit match
        case Exit.Success(answer @ Some(_: JsonRpcMessage.Success)) =>
          ZIO
            // `None`: the client has gone, the store is left alone.
            .succeed[Option[Either[Unit, Option[Session]]]] {
              if clientGone(http) then None
              else if !HttpRequestGuards.capReached(store.size, settings) then
                store(sid) = session
                Some(Right(None))
              else
                // Least recently used first: sessions that tie on `lastSeen` (one
                // millisecond) are then told apart by the order they were used in.
                val snapshot = Unsafe.unsafe { implicit u =>
                  store.toList.sortBy(_._2.touchOrder).map { case (id, s) =>
                    val (seen, live) = s.idleStateUnsafe
                    HttpRequestGuards.StoredSession(id, seen, live, s.clientKey)
                  }
                }
                val now = router.clock.monotonicMs()
                val idleMs = settings.sessionIdleTimeout.map(_.toMillis)
                HttpRequestGuards.pickVictim(snapshot, peer, now, idleMs).flatMap(store.get) match
                  case Some(victim) =>
                    store -= victim.sessionId
                    store(sid) = session
                    Some(Right(Some(victim)))
                  case None => Some(Left(()))
            }
            .flatMap {
              case None =>
                // Nobody reads this reply; it carries no session id because none was stored.
                session.terminate.as(initializeResponse(sent, answer, session, minted = false))
              case Some(Left(())) =>
                session.terminate
                  .as(jsonRpcErrorResponse(503, HttpRequestGuards.SessionLimitMessage))
              case Some(Right(evicted)) =>
                ZIO
                  .foreachDiscard(evicted) { victim =>
                    ZIO.logWarning(
                      s"Legacy session cap ${settings.maxSessions.getOrElse(0)} reached; evicted session ${victim.sessionId}"
                    ) *> victim.terminate
                  }
                  .as(initializeResponse(sent, answer, session, minted = true))
            }
            .uninterruptible
        case Exit.Success(answer) =>
          session.terminate.as(initializeResponse(sent, answer, session, minted = false))
        // The client went away (or the handler chain died): `guarded` answers, as it does for a
        // request abandoned while it waited for its first frame.
        case Exit.Failure(cause) => session.terminate *> ZIO.failCause(cause)
    yield resp

  /** The reply to a header-less `initialize`: what the handler chain sent, then its answer, as one
    * fixed-length `text/event-stream` body. Only a stored session's id is echoed.
    */
  private def initializeResponse(
      sent: Chunk[JsonRpcMessage],
      answer: Option[JsonRpcMessage],
      session: Session,
      minted: Boolean
  ): js.Dynamic =
    val body = (sent ++ Chunk.fromIterable(answer))
      .map(message => s"event: message\ndata: ${MessageLoop.encodeOutbound(message)}\n\n")
      .mkString
    webResponse(200, body, sseHeaders(session, isNew = minted, modern = false))

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
      settings: McpServerSettings,
      http: js.Dynamic
  ): ZIO[R, Throwable, js.Dynamic] =
    message match
      case req: JsonRpcMessage.Request =>
        requestResponse(router, session, req, isNew, settings, context = None, http)
      case other =>
        val extra =
          if isNew then Map(SessionIdHeader -> session.sessionId) else Map.empty[String, String]
        router.dispatch(session, other).map {
          // Only structurally-Invalid frames produce a reply here: 400 with the -32600 body.
          case Some(reply) => jsonResponse(reply.toJson, extra, status = 400)
          case None => webResponse(202, "")
        }

  /** Run a request's dispatch with its server→client messages routed to a per-request queue, and
    * choose the HTTP shape of the reply from its FIRST frame (JVM twin:
    * `StreamableHttpHandler.streamRequest` / `firstFrameReply`, which documents the rules):
    *   - 2026-07-28 only: an error that maps to an HTTP status is plain JSON with that status;
    *   - the final reply itself: one fixed-length `text/event-stream` body — a string `Response`,
    *     no `ReadableStream`, no pull and no heartbeat fiber;
    *   - anything else opens the SSE stream, which carries that frame and the rest.
    *
    * The wait for the first frame is bounded the same way as on the JVM: a legacy reply waits at
    * most `StreamableHttpHandler.LegacyHeadGrace` (or one `keepAliveInterval`, if shorter), a
    * 2026-07-28 reply one `keepAliveInterval` when set. A timer OFFERS the heartbeat sentinel, and
    * a reply that is still silent then opens its stream. The wait itself stays one plain `take`, so
    * the timer cannot discard a frame.
    *
    * Until a `Response` exists there is no `ReadableStream` whose `cancel` hook could tell us the
    * client went away, so the request's own abort signal does: it interrupts the dispatch and shuts
    * the queue down, which also ends the wait.
    */
  private def requestResponse[R](
      router: McpRouter[R],
      session: Session,
      req: JsonRpcMessage.Request,
      isNew: Boolean,
      settings: McpServerSettings,
      // `Some` for a 2026-07-28 request: its context, decoded once by the header validation and
      // handed to the router so the dispatch does not decode `_meta` a second time.
      context: Option[RequestContext],
      // The Bun `Request`, for its abort signal.
      http: js.Dynamic
  ): ZIO[R, Throwable, js.Dynamic] =
    val modern = context.isDefined
    val dispatch = context match
      case Some(ctx) => router.dispatchModern(session, req, ctx)
      case None => router.dispatch(session, req)
    for
      reqQueue <- Queue.unbounded[JsonRpcMessage]
      // `ensuring` offers the close sentinel when dispatch ends replyless (cancellation) so a
      // streamed reply terminates; the bound fiber lets the stream's `cancel` hook interrupt the
      // dispatch when the client disconnects mid-request.
      fiber <- session
        .runWithSink(reqQueue)(dispatch)
        .flatMap(reply => ZIO.foreachDiscard(reply)(reqQueue.offer))
        .ensuring(reqQueue.offer(MessageLoop.CloseSentinel))
        .forkDaemon
      _ <- ZIO.succeed(onClientAbort(http)(fiber.interrupt *> reqQueue.shutdown))
      keepAlive = settings.keepAliveInterval.map(Duration.fromJava)
      grace = StreamableHttpHandler.LegacyHeadGrace
      bound = if modern then keepAlive else Some(keepAlive.fold(grace)(_.min(grace)))
      timer <- bound match
        case None => ZIO.none
        case Some(interval) =>
          (router.clock.sleep(interval) *> reqQueue.offer(KeepAliveSentinel)).forkDaemon
            .map(Some(_))
      stopTimer = timer.fold(ZIO.unit)(StreamableHttpHandler.signalInterrupt)
      first <- reqQueue.take.onInterrupt(stopTimer *> fiber.interrupt *> reqQueue.shutdown)
      _ <- stopTimer
      response <- (if modern then modernErrorStatus(first) else None) match
        case Some(status) =>
          (fiber.interrupt *> reqQueue.shutdown).as(jsonResponse(first.toJson, Map.empty, status))
        // The dispatch fiber is past its last step: signal it, do not wait for its exit.
        case None if isFinalReply(first, req.id) =>
          (StreamableHttpHandler.signalInterrupt(fiber) *> reqQueue.shutdown).as(
            webResponse(
              200,
              s"event: message\ndata: ${MessageLoop.encodeOutbound(first)}\n\n",
              sseHeaders(session, isNew, modern)
            )
          )
        case None =>
          sseResponse(
            reqQueue,
            req.id,
            session,
            isNew,
            fiber,
            settings,
            modern,
            Some(first),
            router.clock
          )
    yield response

  /** Run `cleanup` if the client goes away (Bun aborts `request.signal` when the connection
    * closes). Registered once per request; firing after the reply was sent is harmless, since by
    * then the fiber is finished and the queue is shut.
    *
    * This — not the reply stream's `cancel` hook — is what answers for a request's dispatch and
    * queue on Bun: it does not depend on the stream ever being pulled. A signal that aborted before
    * this point (the client left while its body was being read) never fires the event again, so
    * that case runs `cleanup` directly.
    */
  private def onClientAbort(http: js.Dynamic)(cleanup: UIO[Any]): Unit =
    val signal = http.signal
    if !js.isUndefined(signal) && signal != null then
      val run: js.Function0[Unit] = () =>
        val _ = Unsafe.unsafe(implicit u => Runtime.default.unsafe.fork(cleanup))
      if clientGone(http) then run()
      else
        val _ = signal.addEventListener("abort", run, js.Dynamic.literal(once = true))

  /** True once the request's abort signal has fired: the client closed the connection. */
  private def clientGone(http: js.Dynamic): Boolean =
    val signal = http.signal
    !js.isUndefined(signal) && signal != null &&
    js.typeOf(signal.aborted) == "boolean" && signal.aborted.asInstanceOf[Boolean]

  private def sseHeaders(session: Session, isNew: Boolean, modern: Boolean): Map[String, String] =
    Map("content-type" -> "text/event-stream", "cache-control" -> "no-cache") ++
      (if modern then Map("x-accel-buffering" -> "no") else Map.empty[String, String]) ++
      (if isNew then Map(SessionIdHeader -> session.sessionId) else Map.empty[String, String])

  /** Heartbeat marker the keepalive fiber offers into a request's `reqQueue`; the pull turns it
    * into an SSE comment frame (`: ping`). Shared with the JVM handler, which uses it to open a
    * silent 2026-07-28 reply on the first heartbeat.
    */
  private val KeepAliveSentinel: JsonRpcMessage = MessageLoop.KeepAliveSentinel

  /** Build a Bun SSE `Response` whose `ReadableStream` is pull-fed from `reqQueue`: each take emits
    * one `event: message` frame, and the stream closes right after the request's final reply.
    *
    * Keepalive (`settings.keepAliveInterval`) is a daemon heartbeat fiber that OFFERS
    * [[KeepAliveSentinel]] into `reqQueue` on a `sleep *> offer` loop — the Bun twin of the JVM's
    * `sse.mergeHaltLeft(pings)`. The pull stays a plain `take`, so a heartbeat can never discard a
    * reply (TJC-2337: `take.timeout(interval)` interrupted a take whose promise already held the
    * reply; the next pull took the close sentinel and the stream ended pings-only, HTTP 200, with
    * nothing logged). A sleep loop rather than `Schedule.spaced` for the reason given on
    * [[evictIdleSessions]]. The heartbeat is interrupted when the stream closes (final reply or
    * close sentinel), when the client cancels, and when a pull fails; an offer into an already
    * shut-down queue interrupts it on its own.
    */
  private def sseResponse(
      reqQueue: Queue[JsonRpcMessage],
      reqId: RequestId,
      session: Session,
      isNew: Boolean,
      dispatchFiber: Fiber.Runtime[?, ?],
      settings: McpServerSettings,
      modern: Boolean,
      initial: Option[JsonRpcMessage],
      clock: ServerClock
  ): UIO[js.Dynamic] =
    val heartbeat: UIO[Option[Fiber.Runtime[Nothing, Nothing]]] =
      settings.keepAliveInterval match
        case None => ZIO.none
        case Some(interval) =>
          val tick = Duration.fromJava(interval)
          (clock.sleep(tick) *> reqQueue.offer(KeepAliveSentinel)).forever.forkDaemon.map(Some(_))
    heartbeat.map { hb =>
      val stopHeartbeat: UIO[Unit] = hb.fold(ZIO.unit)(_.interrupt.unit)
      val encoder = js.Dynamic.newInstance(js.Dynamic.global.TextEncoder)()
      var initialMessage = initial
      val takeNext: UIO[JsonRpcMessage] = ZIO.suspendSucceed {
        initialMessage match
          case Some(message) =>
            initialMessage = None
            ZIO.succeed(message)
          case None => reqQueue.take
      }
      val source = js.Dynamic.literal(
        pull = js.Any.fromFunction1((controller: js.Dynamic) =>
          ZioJsPromise.zioToPromise(
            takeNext
              .flatMap {
                case msg if msg == KeepAliveSentinel =>
                  ZIO.succeed { val _ = controller.enqueue(encoder.encode(": ping\n\n")) }
                case msg if MessageLoop.isCloseSentinel(msg) =>
                  // Dispatch ended replyless (cancelled): end the stream without emitting a frame.
                  stopHeartbeat *> ZIO.succeed { val _ = controller.close() }
                case msg =>
                  val frame = s"event: message\ndata: ${MessageLoop.encodeOutbound(msg)}\n\n"
                  ZIO.succeed {
                    val _ = controller.enqueue(encoder.encode(frame))
                  } *>
                    (if isFinalReply(msg, reqId) then
                       stopHeartbeat *> ZIO.succeed { val _ = controller.close() }
                     else ZIO.unit)
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
                  stopHeartbeat *>
                  ZIO.succeed {
                    val _ = scala.util.Try(controller.error(js.Error("stream failed")))
                  }
              }
          )
        ),
        // Client disconnected mid-request: stop the heartbeat, interrupt the dispatch and drop the
        // queue, otherwise the pending `take` leaks a fiber per aborted request.
        cancel = js.Any.fromFunction1((_: js.Any) =>
          ZioJsPromise.zioToPromise(stopHeartbeat *> dispatchFiber.interrupt *> reqQueue.shutdown)
        )
      )
      val stream = js.Dynamic.newInstance(js.Dynamic.global.ReadableStream)(source)
      val headers = sseHeaders(session, isNew, modern)
      val init = js.Dynamic.literal(status = 200, headers = js.Dictionary[String](headers.toSeq*))
      js.Dynamic.newInstance(js.Dynamic.global.Response)(stream, init)
    }

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
          case Right(context) =>
            Session
              .makeRequestScoped(
                s"request-${rpc.id.toString}",
                clientKey = clientKey,
                clock = router.clock
              )
              .flatMap(session =>
                requestResponse(router, session, rpc, isNew = false, settings, Some(context), req)
              )
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
              .makeRequestScoped("notification", clock = router.clock)
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
  ): Either[(Int, McpError), RequestContext] =
    ModernHttpValidation.validateRequestContext(router, rpc, headerOf(req))

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

  /** The request's `mcp-session-id`; an empty header counts as absent (the shared rule,
    * `HttpRequestGuards.sessionId`).
    */
  private def sessionIdHeader(req: js.Dynamic): Option[String] =
    HttpRequestGuards.sessionId(headerOf(req))

  /** The shared 405 for `method` (fallback of the per-mode handlers; `handleFetch` normally answers
    * it first).
    */
  private def methodNotAllowed(method: String, settings: McpServerSettings): js.Dynamic =
    HttpRequestGuards
      .nonPostGate(method, _ => None, settings, pushChannel = false)
      .fold(emptyResponse(405))(reject)

  /** A response with a status and no body (the 404 of a path that is not the endpoint). */
  private def emptyResponse(status: Int): js.Dynamic =
    webResponse(status, "", Map.empty)

  private def readBody(req: js.Dynamic): ZIO[Any, Throwable, String] =
    ZioJsPromise
      .fromJsPromise(req.text().asInstanceOf[js.Promise[String]])
      .mapError(BodyReadFailure(_))

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
  * exactly like `runHttp()`; `stop()` on the returned [[BunHttpHandle]] tears the listener, the
  * sweeper and (when tasks are enabled) the `TaskManager` down. The two names are aliases kept for
  * source compatibility: the mode comes from `settings.stateless`, not from the method called.
  */
extension (server: McpServer[Any])

  def startStatelessHttp(): BunHttpHandle = startHttpHandle(server)
  def startStatefulHttp(): BunHttpHandle = startHttpHandle(server)

private def startHttpHandle(server: McpServer[Any]): BunHttpHandle =
  startHttpHandleOn(server, ServerClock.system)

/** [[startStatefulHttp]] with the server's clock stated: a test that asserts on idle time, task
  * expiry or heartbeats moves the clock itself (CONTRIBUTING.md, "Tests and time").
  */
private[fastmcp] def startHttpHandleOn(server: McpServer[Any], clock: ServerClock): BunHttpHandle =
  val (router, taskManager) = Unsafe.unsafe(implicit u =>
    Runtime.default.unsafe.run(server.buildRouterWithTasksOn(clock)).getOrThrowFiberFailure()
  )
  JsTransportBackend.startBun(
    router,
    Runtime.default,
    server.settings,
    onStop = taskManager.fold(ZIO.unit)(_.shutdown)
  )
