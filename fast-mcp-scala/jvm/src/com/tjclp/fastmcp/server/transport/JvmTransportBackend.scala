package com.tjclp.fastmcp
package server.transport

import java.util.UUID

import zio.*
import zio.stream.*

import com.tjclp.fastmcp.server.McpServerSettings
import com.tjclp.fastmcp.server.router.{McpRouter, Session}

/** JVM [[TransportBackend]] — pure ZIO over `System.in`/`System.out`. No `Unsafe`, no runtime
  * capture, no Mono: the native router is ZIO, so `R` flows straight through.
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
    for
      session <- Session.make("stdio")
      // One writer owns stdout; both replies and server-pushed outbound go through it so lines
      // never interleave.
      outLock <- Semaphore.make(1)
      emit = (line: String) => outLock.withPermit(writeLine(line))
      inLines =
        ZStream
          .fromInputStream(java.lang.System.in)
          .via(ZPipeline.utf8Decode)
          .via(ZPipeline.splitLines)
          .map(_.trim)
          .filter(_.nonEmpty)
      _ <- stdioLoop(router, session, inLines, emit)
    yield ()

  /** The stdio dispatch loop, factored out of [[serveStdio]] so it can be driven over in-memory
    * streams in tests (no real `System.in`/`System.out`).
    *
    * Spawns the outbound drainer (server→client pushes) and consumes each inbound line, dispatching
    * **each frame in its own fiber** so the read loop keeps consuming while a handler is blocked —
    * e.g. a tool awaiting a server→client roots/list or sampling response, whose reply is itself a
    * *later* inbound frame. Sequential dispatch deadlocks such handlers (and a `notifications/
    * cancelled` arriving mid-request). `emit` serializes writes, so forked replies never
    * interleave.
    */
  private[fastmcp] def stdioLoop[R](
      router: McpRouter[R],
      session: Session,
      inLines: ZStream[Any, Throwable, String],
      emit: String => Task[Unit]
  ): ZIO[R, Throwable, Unit] =
    for
      drainer <- session.outbound.take
        .flatMap(msg => emit(MessageLoop.encodeOutbound(msg)))
        .forever
        .forkDaemon
      _ <- inLines
        .runForeach { line =>
          MessageLoop
            .handleFrame(router, session, line)
            .flatMap {
              case Some(reply) => emit(reply)
              case None => ZIO.unit
            }
            .forkDaemon
            .unit
        }
        // stdin EOF (or scope interruption) ends the loop; take the drainer down with it.
        .ensuring(drainer.interrupt)
    yield ()

  private def writeLine(line: String): Task[Unit] =
    ZIO.attempt {
      val out = java.lang.System.out
      out.print(line)
      out.print('\n')
      out.flush()
    }

  /** The JVM platform seam, in the impl object so it's exportable (givens can't be wildcard-
    * exported straight from a package). `ExportsJvm` re-exports this so `import
    * com.tjclp.fastmcp.*` puts a `TransportBackend` in scope and `McpServer(...)` resolves.
    */
  given instance: TransportBackend = this
