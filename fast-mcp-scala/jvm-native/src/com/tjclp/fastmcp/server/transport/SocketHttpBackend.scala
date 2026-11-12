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
  * @param entropy
  *   the platform backend whose `randomId()` mints session ids
  * @param maxConnections
  *   concurrent connection cap (one parked thread per idle connection); further connections wait in
  *   the kernel backlog
  * @param trackFiberRoots
  *   whether request fibers are registered in ZIO's `Fiber.roots` (fiber dumps). `false` on Scala
  *   Native, where that registration's `WeakReference` per fiber is not reclaimed fast enough
  */
class SocketHttpBackend(
    entropy: TransportBackend,
    maxConnections: Int = SocketHttpServer.DefaultMaxConnections,
    trackFiberRoots: Boolean = true
) extends HttpTransportBackend:

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
      started <- SocketHttpServer.start(
        settings.host,
        settings.port,
        settings.maxRequestBodyBytes,
        maxConnections,
        trackFiberRoots
      )(app)
    yield started
