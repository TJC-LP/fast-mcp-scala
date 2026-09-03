package com.tjclp.fastmcp
package server.transport

import zio.*
import zio.http.*
import zio.http.netty.{ChannelType, NettyConfig}

import com.tjclp.fastmcp.server.McpServerSettings
import com.tjclp.fastmcp.server.router.{McpRouter, Session}
import com.tjclp.fastmcp.server.transport.http.{HttpReply, HttpRequest, StreamableHttpHandler}

/** JVM [[HttpTransportBackend]] — ZIO HTTP (Netty). Pure ZIO: the native router is ZIO, so `R`
  * flows straight through `Server.serve` and the user's `.provide(...)`. No `Unsafe`, no runtime
  * capture, no Mono.
  *
  * Every streamable-HTTP decision lives in the shared [[StreamableHttpHandler]]; this object is the
  * zio-http adapter around it (request → [[HttpRequest]], [[HttpReply]] → `Response`) plus the
  * Netty channel-type pinning GraalVM native images need. The same handler backs the netty-free
  * socket backend (`SocketHttpBackend`) over `java.net.ServerSocket`.
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
    ZIO.environment[R].flatMap { env =>
      StreamableHttpHandler.make(router, settings, JvmTransportBackend.randomId()).flatMap { mcp =>
        // The idle-session sweeper is forked here (scoped to the server's lifetime), NOT in
        // httpRoutes — tests drive httpRoutes directly and must not leak a sweeper fiber each.
        ZIO.scoped {
          mcp.evictIdleSessions.forkScoped *> serve(routes(mcp, env), settings)
        }
      }
    }

  /** The shared idle-session sweeper, kept here as a seam for `JvmHttpTransportTest`. */
  private[fastmcp] def evictIdleSessions(
      store: Ref[Map[String, Session]],
      settings: McpServerSettings
  ): UIO[Unit] =
    StreamableHttpHandler.evictIdleSessions(store, settings)

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
    val config = Server.Config.default.binding(settings.host, settings.port)
    val netty = nettyConfigFor(resolveChannelType())
    Server
      .serve(routes)
      .provideLayer((ZLayer.succeed(config) ++ ZLayer.succeed(netty)) >>> Server.customized)
      .unit

  // ---------------------------------------------------------------------------
  // zio-http adapter: per-method routes on the endpoint, so zio-http keeps answering its own
  // 404/405 for anything else; each route funnels into the shared handler.
  // ---------------------------------------------------------------------------

  private def routes[R](
      mcp: StreamableHttpHandler[R],
      env: ZEnvironment[R]
  ): Routes[Any, Response] =
    val ep = mcp.endpoint.stripPrefix("/")
    Routes(
      Method.POST / ep -> handler { (request: Request) =>
        mcp.post(toRequest(request)).map(toResponse).provideEnvironment(env)
      },
      Method.GET / ep -> handler { (request: Request) =>
        mcp.get(toRequest(request)).map(toResponse)
      },
      Method.DELETE / ep -> handler { (request: Request) =>
        mcp.delete(toRequest(request)).map(toResponse)
      }
    )

  private def toRequest(request: Request): HttpRequest =
    HttpRequest(
      method = request.method.name,
      path = request.url.path.encode,
      header = name => request.rawHeader(name),
      body = request.body.asString
    )

  private def toResponse(reply: HttpReply): Response =
    val base = reply match
      case HttpReply.Empty(status, _) =>
        Response.status(Status.fromInt(status))
      case HttpReply.Json(status, body, _) =>
        Response.json(body).status(Status.fromInt(status))
      case HttpReply.Sse(_, frames) =>
        Response.fromServerSentEvents(
          frames.map(f => ServerSentEvent(f.data, eventType = f.event))
        )
    reply.headers.foldLeft(base) { case (resp, (name, value)) =>
      resp.addHeader(Header.Custom(name, value))
    }

  /** The JVM HTTP seam, in the impl object so it's exportable. `ExportsJvm` re-exports this so
    * `import com.tjclp.fastmcp.*` puts an `HttpTransportBackend` in scope and `runHttp()` /
    * `McpServerApp[Http, ...]` resolve.
    */
  given httpInstance: HttpTransportBackend = this
