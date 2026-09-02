package com.tjclp.fastmcp
package server.transport

/** The netty-free JVM [[HttpTransportBackend]]: the same `java.net.ServerSocket` server that is the
  * Scala Native HTTP backend, with session ids from `java.util.UUID` (SecureRandom) via
  * [[JvmTransportBackend]]. Behaviour on the wire matches [[JvmHttpBackend]] because both render
  * the shared `StreamableHttpHandler`.
  *
  * This is an explicit opt-in — the root `import com.tjclp.fastmcp.{*, given}` keeps resolving
  * [[JvmHttpBackend]] (ZIO HTTP / Netty), and this object deliberately carries no `given` so it can
  * never make that import ambiguous. To use it, declare a given whose type is this object's
  * singleton type, which beats the imported `HttpTransportBackend` on specificity:
  *
  * {{{
  * import com.tjclp.fastmcp.{*, given}
  * import com.tjclp.fastmcp.server.transport.JvmSocketHttpBackend
  *
  * given JvmSocketHttpBackend.type = JvmSocketHttpBackend
  *
  * object MyServer extends McpServerApp[Http, MyServer.type]:
  *   @Tool() def add(a: Int, b: Int): Int = a + b
  * }}}
  *
  * Pair it with excluding `dev.zio::zio-http` from your dependencies to drop Netty from the
  * classpath entirely (and from GraalVM native images, where the seam split already keeps it
  * unreachable for stdio-only programs).
  */
object JvmSocketHttpBackend extends SocketHttpBackend(JvmTransportBackend)
