package com.tjclp.fastmcp
package server.transport

import zio.*

import com.tjclp.fastmcp.server.McpServerSettings
import com.tjclp.fastmcp.server.router.McpRouter
import com.tjclp.fastmcp.server.transport.http.{
  HttpReply,
  HttpRequest,
  SocketHttpServer,
  StreamableHttpHandler
}

/** [[HttpTransportBackend]] over `java.net.ServerSocket` — the shared [[StreamableHttpHandler]]
  * served by [[SocketHttpServer]], with no HTTP library underneath. It is the Scala Native HTTP
  * backend (`NativeHttpBackend`, where zio-http does not exist) and the JVM's netty-free opt-in
  * (`JvmSocketHttpBackend`); the two differ only in which platform [[TransportBackend]] supplies
  * `randomId()` for session ids (`/dev/urandom` on Native, `SecureRandom` on the JVM).
  *
  * Semantics are byte-for-byte those of the zio-http backend because both render the same handler;
  * the transport-level differences are the ones listed on [[SocketHttpServer]] (caps, idle timeout,
  * connection gate, plaintext only).
  *
  * The constructor is internal on purpose. A backend is obtained from the platform object —
  * `JvmSocketHttpBackend` or `JvmSocketHttpBackend.withSettings(...)` on the JVM,
  * `NativeHttpBackend` or `NativeHttpBackend.withSettings(...)` on Scala Native — so that on Scala
  * Native every route to a socket backend goes through an `@experimental` definition and has the
  * fiber-roots registration off. A public constructor let stable code build one directly, without
  * the experimental opt-in and with the registration on, which is what made the heap grow by
  * gigabytes per minute there.
  *
  * @param entropy
  *   the platform backend whose `randomId()` mints session ids
  * @param maxConnections
  *   concurrent connection cap (one parked thread per idle connection); further connections wait in
  *   the kernel backlog
  * @param trackFiberRoots
  *   whether request fibers are registered in ZIO's `Fiber.roots` (fiber dumps). Off on Scala
  *   Native — also by default, see `SocketHttpBackend.TrackFiberRootsByDefault` — where that
  *   registration's `WeakReference` per fiber is not reclaimed fast enough
  * @param idleTimeout
  *   a connection with no request activity is closed after this long
  * @param requestReadTimeout
  *   the allowance for a whole request (head + body) from its first byte, extended by one second
  *   per 4 KiB received: a steady slow upload is given the time it needs, a trickle gets 408
  * @param writeStallTimeout
  *   a write blocked this long — the peer stopped reading a reply or an event stream — closes the
  *   connection, so a stalled client cannot hold one of the `maxConnections` slots indefinitely
  */
class SocketHttpBackend private[fastmcp] (
    entropy: TransportBackend,
    maxConnections: Int = SocketHttpServer.DefaultMaxConnections,
    trackFiberRoots: Boolean = SocketHttpBackend.TrackFiberRootsByDefault,
    idleTimeout: Duration = 60.seconds,
    requestReadTimeout: Duration = 60.seconds,
    writeStallTimeout: Duration = 30.seconds
) extends HttpTransportBackend:

  private def millis(duration: Duration): Int =
    math.max(1L, math.min(duration.toMillis, Int.MaxValue.toLong)).toInt

  override def serveHttp[R](
      router: McpRouter[R],
      settings: McpServerSettings
  ): ZIO[R, Throwable, Unit] =
    // The accept loop runs forever; joining it means a loop failure (a bind that later breaks, a
    // platform surprise in `accept`) fails `serveHttp` loudly instead of leaving a deaf listener.
    ZIO.scoped(start(router, settings).flatMap(_.loop.join))

  /** Bind and start serving inside the enclosing `Scope`, returning the bound address and the
    * accept-loop fiber — the seam tests use (`settings.port = 0` picks a free port; closing the
    * scope stops the server).
    */
  private[fastmcp] def start[R](
      router: McpRouter[R],
      settings: McpServerSettings
  ): ZIO[R & Scope, Throwable, SocketHttpServer.Started] =
    for
      _ <- StreamableHttpHandler.validateSettings(settings)
      handler <- StreamableHttpHandler.make(router, settings, entropy.randomId())
      // Scoped to the server's lifetime, exactly like the zio-http backend forks it.
      _ <- handler.evictIdleSessions.forkScoped
      // The server judges each request on its head through `admit` (the shared gates) before it
      // reads a body, bounds that body at `maxRequestBodyBytes` on the wire, and runs `handle`,
      // which carries the first-party error boundary (defect → JSON-RPC 500).
      app = new SocketHttpServer.App[R]:
        def admit(req: HttpRequest): Option[HttpReply] = handler.admit(req)
        def handle(req: HttpRequest): URIO[R, HttpReply] = handler.handle(req)
      config = SocketHttpServer.Config(
        maxBodyBytes = settings.maxRequestBodyBytes,
        maxConnections = maxConnections,
        trackFiberRoots = trackFiberRoots,
        idleTimeoutMs = millis(idleTimeout),
        requestReadTimeoutMs = millis(requestReadTimeout),
        writeStallTimeoutMs = millis(writeStallTimeout)
      )
      started <- SocketHttpServer.start(settings.host, settings.port, config)(app)
    yield started

object SocketHttpBackend:

  /** Whether this binary is a Scala Native one (its javalib reports itself in `java.vm.name`). */
  private[fastmcp] val OnScalaNative: Boolean =
    Option(java.lang.System.getProperty("java.vm.name")).exists(_.contains("Scala Native"))

  /** The default for `trackFiberRoots`: on everywhere except Scala Native, so that no way of
    * building a backend there — including the tests in this package — leaves the registration on.
    */
  private[fastmcp] val TrackFiberRootsByDefault: Boolean = !OnScalaNative
