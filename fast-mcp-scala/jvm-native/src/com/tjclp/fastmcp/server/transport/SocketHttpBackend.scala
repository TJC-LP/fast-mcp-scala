package com.tjclp.fastmcp
package server.transport

import zio.*

import com.tjclp.fastmcp.server.McpServerSettings
import com.tjclp.fastmcp.server.router.McpRouter
import com.tjclp.fastmcp.server.transport.http.{SocketHttpServer, StreamableHttpHandler}

/** [[HttpTransportBackend]] over `java.net.ServerSocket` — the shared [[StreamableHttpHandler]]
  * served by [[SocketHttpServer]], with no HTTP library underneath. It is the Scala Native HTTP
  * backend (`NativeHttpBackend`, where zio-http does not exist) and the JVM's netty-free opt-in
  * (`JvmSocketHttpBackend`); the two differ only in which platform [[TransportBackend]] supplies
  * `randomId()` for session ids (`/dev/urandom` on Native, `SecureRandom` on the JVM).
  *
  * Semantics are byte-for-byte those of the zio-http backend because both render the same handler;
  * the transport-level differences are the ones listed on [[SocketHttpServer]] (caps, idle timeout,
  * connection gate, plaintext only).
  */
class SocketHttpBackend(entropy: TransportBackend) extends HttpTransportBackend:

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
      handler <- StreamableHttpHandler.make(router, settings, entropy.randomId())
      // Scoped to the server's lifetime, exactly like the zio-http backend forks it.
      _ <- handler.evictIdleSessions.forkScoped
      started <- SocketHttpServer.start(settings.host, settings.port)(handler.handle)
    yield started
