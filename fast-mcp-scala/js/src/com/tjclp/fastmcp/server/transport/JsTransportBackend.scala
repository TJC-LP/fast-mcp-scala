package com.tjclp.fastmcp
package server.transport

import scala.scalajs.js

import zio.*

import com.tjclp.fastmcp.core.Protocol
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
  * HTTP: stateless and streamable (durable sessions keyed by the `mcp-session-id` header) are both
  * supported for request/response. The streamable `GET` SSE *push* channel is not implemented on JS
  * yet — `GET` returns `405`, which the spec permits for servers that don't offer a server→client
  * stream. (The JVM backend serves the full SSE channel.)
  */
object JsTransportBackend extends TransportBackend:

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
      ZIO.acquireReleaseWith(ZIO.attempt(startBun(router, rt, settings)))(server =>
        ZIO.succeed(server.stop())
      )(_ => ZIO.never)
    }

  /** Start the Bun HTTP listener and return its handle. Used by `serveHttp` (wrapped in
    * acquire/release) and directly by JS integration tests that want a `stop()`-able handle.
    */
  def startBun[R](
      router: McpRouter[R],
      runtime: Runtime[R],
      settings: McpServerSettings
  ): BunServer =
    val store = js.Dictionary.empty[Session] // Bun is single-threaded — no concurrent access
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
      val headerErr = if methodOf(req) == "POST" then postHeaderError(req) else None
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
        for
          body <- readBody(req)
          session <- Session.make("stateless")
          reply <- MessageLoop.handleFrame(router, session, body)
        yield reply match
          case Some(json) => jsonResponse(json, Map.empty)
          case None => webResponse(202, "")
      case _ =>
        ZIO.succeed(webResponse(405, "Stateless mode only accepts POST"))

  private def handleStreamable[R](
      router: McpRouter[R],
      settings: McpServerSettings,
      store: js.Dictionary[Session],
      req: js.Dynamic
  ): ZIO[R, Throwable, js.Dynamic] =
    methodOf(req) match
      case "POST" =>
        readBody(req).flatMap { body =>
          sessionIdHeader(req) match
            case Some(sid) =>
              store.get(sid) match
                case None =>
                  ZIO.succeed(webResponse(404, s"Session not found: $sid"))
                case Some(session) =>
                  MessageLoop
                    .handleFrame(router, session, body)
                    .map(toPostResponse(_, session, isNew = false))
            case None =>
              for
                session <- Session.make(WebCrypto.randomUUID())
                _ <- ZIO.succeed { store(session.sessionId) = session }
                reply <- MessageLoop.handleFrame(router, session, body)
              yield toPostResponse(reply, session, isNew = true)
        }

      case "DELETE" =>
        if settings.disallowDelete then ZIO.succeed(webResponse(405, "DELETE disabled"))
        else
          sessionIdHeader(req) match
            case None =>
              ZIO.succeed(webResponse(400, s"Session ID required in $SessionIdHeader header"))
            case Some(sid) =>
              store.get(sid) match
                case Some(session) =>
                  ZIO.succeed { store -= sid } *> session.outbound.shutdown.as(webResponse(200, ""))
                case None =>
                  ZIO.succeed(webResponse(404, s"Session not found: $sid"))

      case _ =>
        // GET (server→client SSE push) is not offered on the JS transport — spec-allowed 405.
        ZIO.succeed(webResponse(405, "SSE streaming is not supported on the JS transport"))

  private def toPostResponse(reply: Option[String], session: Session, isNew: Boolean): js.Dynamic =
    reply match
      case Some(json) =>
        val extra =
          if isNew then Map(SessionIdHeader -> session.sessionId) else Map.empty[String, String]
        jsonResponse(json, extra)
      case None => webResponse(202, "")

  // -------------------------------------------------------------------------
  // Web Request / Response helpers
  // -------------------------------------------------------------------------

  private def methodOf(req: js.Dynamic): String = req.method.asInstanceOf[String]

  /** POST guard: `Accept` (if present) must allow application/json; `mcp-protocol-version` (if
    * present) must be supported. Lenient when headers are absent. Mirrors the JVM transport.
    */
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

  private def jsonResponse(body: String, extraHeaders: Map[String, String]): js.Dynamic =
    webResponse(200, body, Map("content-type" -> "application/json") ++ extraHeaders)

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
