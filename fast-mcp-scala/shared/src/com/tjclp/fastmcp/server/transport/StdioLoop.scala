package com.tjclp.fastmcp
package server.transport

import zio.*
import zio.stream.*

import com.tjclp.fastmcp.server.router.{McpRouter, Session}

/** The platform-neutral stdio serving loop, shared by every backend whose stdin can be presented as
  * a `ZStream` of NDJSON lines and whose stdout is a blocking `System.out` (JVM and Scala Native
  * today; the JS backend drives Node's callback IO directly and does not use this).
  *
  * [[MessageLoop]] owns wire↔dispatch; this owns the *lifecycle* around it — the pieces that were
  * previously duplicated verbatim across backends: one writer owning stdout, the outbound drainer
  * fiber, per-frame forked dispatch, and EOF teardown.
  */
private[fastmcp] object StdioLoop:

  /** Serve stdio end to end: mint the session, serialize stdout behind a single writer, and run
    * [[run]]. The caller supplies only the platform's line stream, which is the sole thing that
    * differs between the JVM and Scala Native backends.
    */
  def serve[R](
      router: McpRouter[R],
      inLines: ZStream[Any, Throwable, String]
  ): ZIO[R, Throwable, Unit] =
    for
      session <- Session.make("stdio")
      // One writer owns stdout; both replies and server-pushed outbound go through it so lines
      // never interleave.
      outLock <- Semaphore.make(1)
      emit = (line: String) => outLock.withPermit(writeLine(line))
      _ <- run(router, session, inLines, emit)
    yield ()

  /** The stdio dispatch loop, separate from [[serve]] so it can be driven over in-memory streams in
    * tests (no real `System.in`/`System.out`).
    *
    * Spawns the outbound drainer (server→client pushes) and consumes each inbound line, dispatching
    * **each frame in its own fiber** so the read loop keeps consuming while a handler is blocked —
    * e.g. a tool awaiting a server→client roots/list or sampling response, whose reply is itself a
    * *later* inbound frame. Sequential dispatch deadlocks such handlers (and a `notifications/
    * cancelled` arriving mid-request). `emit` serializes writes, so forked replies never
    * interleave.
    */
  def run[R](
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

  /** Blocking line write to `System.out`. Identical on the JVM and Scala Native (SN's javalib
    * provides the same `System.out` surface).
    */
  private def writeLine(line: String): Task[Unit] =
    ZIO.attempt {
      val out = java.lang.System.out
      out.print(line)
      out.print('\n')
      out.flush()
    }
