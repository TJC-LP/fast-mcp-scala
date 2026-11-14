package com.tjclp.fastmcp
package server.transport

import zio.*
import zio.http.*
import zio.http.netty.{ChannelType, NettyConfig}
import zio.stream.*

import com.tjclp.fastmcp.server.McpServerSettings
import com.tjclp.fastmcp.server.router.McpRouter
import com.tjclp.fastmcp.server.transport.http.{HttpReply, HttpRequest, StreamableHttpHandler}

/** JVM [[HttpTransportBackend]] — ZIO HTTP (Netty). Pure ZIO: the native router is ZIO, so `R`
  * flows straight through `Server.serve` and the user's `.provide(...)`. No `Unsafe`, no runtime
  * capture, no Mono.
  *
  * Every streamable-HTTP decision — the header gates, the body and session caps, session minting
  * and eviction, request→SSE, the 2026-07-28 stateless path — lives in the shared
  * [[StreamableHttpHandler]]; this object is the zio-http adapter around it (request →
  * [[HttpRequest]], [[HttpReply]] → `Response`) plus the Netty channel-type pinning GraalVM native
  * images need. The same handler backs the netty-free socket backend (`SocketHttpBackend`) over
  * `java.net.ServerSocket`.
  *
  * Split from [[JvmTransportBackend]] so stdio-only programs (and their GraalVM native images)
  * never reference zio-http or Netty.
  */
object JvmHttpBackend extends HttpTransportBackend:

  override def serveHttp[R](
      router: McpRouter[R],
      settings: McpServerSettings
  ): ZIO[R, Throwable, Unit] =
    // Capture the environment ZIO-natively and thread it into each handler via provideEnvironment,
    // so Routes stay `Routes[Any]` — Server.serve then needs only `Server`, avoiding the generic-R
    // HasNoScope constraint. No Unsafe, no runtime capture.
    // zio-http percent-decodes the request path before the handler compares it.
    StreamableHttpHandler.validateSettings(settings, decodedPaths = true) *>
      ZIO.environment[R].flatMap { env =>
        StreamableHttpHandler.make(router, settings, JvmTransportBackend.randomId()).flatMap {
          mcp =>
            // The idle-session sweeper is forked here (scoped to the server's lifetime), NOT in
            // httpRoutes — tests drive httpRoutes directly and must not leak a sweeper fiber each.
            ZIO.scoped {
              mcp.evictIdleSessions.forkScoped *> serve(routes(mcp, env), settings)
            }
        }
      }

  /** Build the HTTP routes, allocating a fresh handler (and, unless stateless, session store).
    * Exposed to tests so specs can drive requests through `routes.runZIO(...)` in-memory, without
    * binding a TCP port.
    */
  private[fastmcp] def httpRoutes[R](
      router: McpRouter[R],
      settings: McpServerSettings,
      env: ZEnvironment[R]
  ): UIO[Routes[Any, Response]] =
    StreamableHttpHandler.make(router, settings, JvmTransportBackend.randomId()).map(routes(_, env))

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
    // `disableRequestStreaming(n)` installs netty's HttpObjectAggregator with `n` as its cap: bodies
    // above `maxRequestBodyBytes` (declared or chunked) are answered 413 on the wire before the
    // handler runs. The first-party checks in `postGate` / `bodyTooLarge` give the same verdict on
    // paths with no aggregator in front (in-memory route tests).
    val config = Server.Config.default
      .binding(settings.host, settings.port)
      .disableRequestStreaming(settings.maxRequestBodyBytes)
    val netty = nettyConfigFor(resolveChannelType())
    Server
      .serve(routes)
      .provideLayer((ZLayer.succeed(config) ++ ZLayer.succeed(netty)) >>> Server.customized)
      .unit

  // ---------------------------------------------------------------------------
  // zio-http adapter: ONE catch-all route that funnels every request into the shared handler
  // behind `guarded`. Path and method are judged there (`StreamableHttpHandler.handle`), not by
  // zio-http's router: its path codec also matches `/mcp/`, and with per-method routes a PUT or an
  // OPTIONS on the endpoint was answered 404 without ever meeting the Host/Origin guard. Routed
  // this way the zio-http backend gives the same 404 / 403 / 405 as the socket backend and Bun.
  // ---------------------------------------------------------------------------

  private def routes[R](
      mcp: StreamableHttpHandler[R],
      env: ZEnvironment[R]
  ): Routes[Any, Response] =
    Routes(
      Method.ANY / trailing -> Handler.scoped[Any](handler { (_: Path, request: Request) =>
        guarded(mcp.handle(toRequest(request)).provideEnvironment(env).flatMap(respond))
      })
    )

  /** Render a reply, tying a streamed one to the request it answers. zio-http gives every request a
    * `Scope` that it closes when the request is over — after the response body has been written, or
    * when the connection goes away first, in which case the fiber that would have run the body is
    * interrupted and may never have started it. Closing the scope that way abandons the reply at
    * once (`HttpReply.Sse.abandon`: a no-op for a stream that did start, whose own finalizers ran),
    * so a session's GET slot or a request's dispatch is not kept for the handler's unstarted-reply
    * bound. A scope that closes normally leaves the reply alone: the in-memory route tests close it
    * before they read the body.
    *
    * The scope is looked up only for a streamed reply; every other reply is rendered directly.
    */
  private def respond(reply: HttpReply): URIO[Scope, Response] =
    reply match
      case sse: HttpReply.Sse =>
        ZIO
          .addFinalizerExit(exit => if exit.isSuccess then ZIO.unit else sse.abandon)
          .as(toResponse(sse))
          // Registered or not at all: once registered, the response is on its way out.
          .uninterruptible
      case other => ZIO.succeed(toResponse(other))

  /** First-party error boundary ([[StreamableHttpHandler.guardedWith]]) rendered as a zio-http
    * `Response`: any non-interrupt Cause becomes a JSON-RPC 500 with a fixed message; the full
    * cause is logged server-side only. Interrupts (client disconnect) keep zio-http's own 408 path.
    * `effect` is by-name so a synchronous throw while the handler effect is BUILT (the eager header
    * gates, the request adaptation) is caught too — parity with the Bun boundary.
    */
  private[fastmcp] def guarded[R](effect: => URIO[R, Response]): URIO[R, Response] =
    StreamableHttpHandler.guardedWith[R, Response](
      toResponse(
        StreamableHttpHandler.transportError(500, HttpRequestGuards.InternalErrorMessage)
      )
    )(effect)

  private def toRequest(request: Request): HttpRequest =
    HttpRequest(
      method = request.method.name,
      path = request.url.path.encode,
      header = name => request.rawHeader(name),
      gateHeader = gateHeader(request),
      body = request.body.asString,
      // The TCP peer address as seen by netty (`None` for in-memory `routes.runZIO` requests).
      clientKey = request.remoteAddress.map(_.getHostAddress),
      // One entry per field line: zio-http keeps a repeated header as separate entries.
      headerNames = () => request.headers.iterator.map(_.headerName.toString)
    )

  /** Header lookup for the gates: a header sent more than once is seen as its `", "`-joined value
    * (what Bun's `Headers.get` returns), so a duplicated `Host`/`Origin` fails the origin parser
    * closed (403) on every backend instead of only the first value being checked.
    */
  private def gateHeader(req: Request)(name: String): Option[String] =
    val values = req.headers.iterator
      .filter(_.headerName.equalsIgnoreCase(name))
      .map(_.renderedValue)
      .toList
    if values.isEmpty then None else Some(values.mkString(", "))

  private def toResponse(reply: HttpReply): Response =
    val base = reply match
      case HttpReply.Empty(status, _) =>
        Response.status(Status.fromInt(status))
      case HttpReply.Json(status, body, _) =>
        Response.json(body).status(Status.fromInt(status))
      case HttpReply.Body(status, body, contentType, _) =>
        Response(
          Status.fromInt(status),
          Headers(Header.Custom("content-type", contentType)),
          Body.fromString(body)
        )
      case HttpReply.SseComplete(_, frame) =>
        // The whole body is known: a fixed-length response with the content type an SSE stream
        // carries, instead of zio-http's chunked stream body.
        Response(
          Status.Ok,
          Headers(Header.ContentType(MediaType.text.`event-stream`)),
          Body.fromString(frame.encode)
        )
      case HttpReply.Sse(_, frames, keepAlive) =>
        Response.fromServerSentEvents(
          withKeepAlive(frames.map(f => ServerSentEvent(f.data, eventType = f.event)), keepAlive)
        )
    reply.headers.foldLeft(base) { case (resp, (name, value)) =>
      resp.addHeader(Header.Custom(name, value))
    }

  /** Merge a heartbeat into an SSE stream so proxies / idle timeouts don't kill long-quiet
    * connections. The `ping` event type is ignored by conforming clients (the TS SDK only parses
    * `message` events). zio-http 3.11.4 can write an SSE comment frame (a `data` string starting
    * with `:` is emitted verbatim, no `data:` prefix), but the heartbeat keeps the `ping` event
    * shape it has had since zio-http 3.4.0 (which had no comment support) so the wire is unchanged
    * across the bump. Halts with the data stream.
    */
  private def withKeepAlive(
      sse: ZStream[Any, Nothing, ServerSentEvent[String]],
      keepAlive: Option[Duration]
  ): ZStream[Any, Nothing, ServerSentEvent[String]] =
    keepAlive match
      case None => sse
      case Some(interval) =>
        val pings = ZStream.repeatWithSchedule(
          ServerSentEvent[String]("", eventType = Some("ping")),
          Schedule.spaced(interval)
        )
        sse.mergeHaltLeft(pings)

  /** The JVM HTTP seam, in the impl object so it's exportable. `ExportsJvm` re-exports this so
    * `import com.tjclp.fastmcp.*` puts an `HttpTransportBackend` in scope and `runHttp()` /
    * `McpServerApp[Http, ...]` resolve.
    */
  given httpInstance: HttpTransportBackend = this
