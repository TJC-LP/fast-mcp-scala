package com.tjclp.fastmcp
package server.transport

import zio.*

import com.tjclp.fastmcp.server.transport.http.SocketHttpServer

/** The netty-free JVM [[HttpTransportBackend]]: the same `java.net.ServerSocket` server that is the
  * Scala Native HTTP backend, with session ids from `java.util.UUID` (SecureRandom) via
  * [[JvmTransportBackend]]. Behaviour on the wire matches [[JvmHttpBackend]] because both render
  * the shared `StreamableHttpHandler`.
  *
  * This is an explicit opt-in — the root `import com.tjclp.fastmcp.{*, given}` keeps resolving
  * [[JvmHttpBackend]] (ZIO HTTP / Netty). To use this backend instead, import its given BY NAME
  * next to the root import:
  *
  * {{{
  * import com.tjclp.fastmcp.{*, given}
  * import com.tjclp.fastmcp.server.transport.JvmSocketHttpBackend.httpInstance
  *
  * object MyServer extends McpServerApp[Http, MyServer.type]:
  *   @Tool() def add(a: Int, b: Int): Int = a + b
  * }}}
  *
  * The given deliberately has the same name as the default backend's (`httpInstance`): a named
  * import outranks the root wildcard import, so it replaces the default everywhere in the file — at
  * the top level, inside objects, and in the parent clause of an `McpServerApp` object alike.
  * Declaring your own differently-named given does NOT do that: a top-level `given
  * JvmSocketHttpBackend.type = JvmSocketHttpBackend` loses to the root import inside any object
  * (the import is the more deeply nested binding), and the server silently binds Netty. For a
  * one-off, `server.runHttp()(using JvmSocketHttpBackend)` passes the backend explicitly.
  *
  * To change the connection cap or the timeouts, build a configured backend with [[withSettings]]
  * and either pass it explicitly or shadow the default by declaring it under the same name:
  *
  * {{{
  * given httpInstance: HttpTransportBackend = JvmSocketHttpBackend.withSettings(maxConnections = 1024)
  * }}}
  *
  * Pair it with excluding `dev.zio::zio-http` from your dependencies to drop Netty from the
  * classpath entirely (and from GraalVM native images, where the seam split already keeps it
  * unreachable for stdio-only programs).
  */
object JvmSocketHttpBackend extends SocketHttpBackend(JvmTransportBackend):

  /** The opt-in seam — see the recipe above. Not exported from the package root. */
  given httpInstance: HttpTransportBackend = this

  /** A socket backend with non-default limits. Each connection holds one of `maxConnections` slots
    * (and one thread); `idleTimeout`, `requestReadTimeout` and `writeStallTimeout` are what keep a
    * client from holding a slot without making progress — see docs/transports.md, "The socket
    * backend".
    */
  def withSettings(
      maxConnections: Int = SocketHttpServer.DefaultMaxConnections,
      idleTimeout: Duration = 60.seconds,
      requestReadTimeout: Duration = 60.seconds,
      writeStallTimeout: Duration = 30.seconds
  ): SocketHttpBackend =
    new SocketHttpBackend(
      JvmTransportBackend,
      maxConnections = maxConnections,
      idleTimeout = idleTimeout,
      requestReadTimeout = requestReadTimeout,
      writeStallTimeout = writeStallTimeout
    )
