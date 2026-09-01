package com.tjclp.fastmcp
package server.transport

import java.util.UUID

import zio.*
import zio.stream.*

import com.tjclp.fastmcp.server.McpServerSettings
import com.tjclp.fastmcp.server.router.McpRouter

/** JVM [[TransportBackend]] — pure ZIO over `System.in`/`System.out`. No `Unsafe`, no runtime
  * capture, no Mono: the native router is ZIO, so `R` flows straight through.
  *
  * Only the two genuinely platform-specific pieces live here — how stdin becomes a line stream, and
  * where randomness comes from. The serving lifecycle is shared ([[StdioLoop]]).
  *
  * Deliberately netty-free: HTTP serving lives on [[JvmHttpBackend]] (the [[HttpTransportBackend]]
  * given), so stdio-only programs — and their GraalVM native images — never reference zio-http or
  * Netty.
  */
object JvmTransportBackend extends TransportBackend:

  /** UUID v4 via `java.util.UUID` (SecureRandom-backed). */
  override def randomId(): UIO[String] = ZIO.succeed(UUID.randomUUID().toString)

  override def serveStdio[R](
      router: McpRouter[R],
      settings: McpServerSettings
  ): ZIO[R, Throwable, Unit] =
    StdioLoop.serve(
      router,
      ZStream
        .fromInputStream(java.lang.System.in)
        .via(ZPipeline.utf8Decode)
        .via(ZPipeline.splitLines)
        .map(_.trim)
        .filter(_.nonEmpty)
    )

  /** The JVM platform seam, in the impl object so it's exportable (givens can't be wildcard-
    * exported straight from a package). `ExportsJvm` re-exports this so `import
    * com.tjclp.fastmcp.*` puts a `TransportBackend` in scope and `McpServer(...)` resolves.
    */
  given instance: TransportBackend = this
