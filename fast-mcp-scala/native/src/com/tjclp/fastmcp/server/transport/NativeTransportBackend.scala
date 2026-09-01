package com.tjclp.fastmcp
package server.transport

import java.nio.charset.StandardCharsets

import zio.*
import zio.stream.*

import com.tjclp.fastmcp.server.McpServerSettings
import com.tjclp.fastmcp.server.router.{McpRouter, Session}

/** Scala Native [[TransportBackend]] — pure ZIO over `System.in`/`System.out`, compiled to a
  * standalone binary via LLVM. Stdio only: zio-http is not published for Scala Native, so this
  * platform provides no [[HttpTransportBackend]] given and `McpServerApp[Http]` programs fail to
  * compile at the declaration site (by design — the same seam that keeps netty out of GraalVM stdio
  * images).
  *
  * Platform notes vs. the JVM backend:
  *   - stdin: zio-streams Native has no `ZStream.fromInputStream`; chars are read via
  *     `ZStream.fromReader` (the `InputStreamReader` owns byte→UTF-8 decoding) and re-chunked into
  *     Strings for the shared `ZPipeline.splitLines`.
  *   - `randomId()`: javalib's `UUID.randomUUID()` does not link on Scala Native 0.5 (it references
  *     `java.security.SecureRandom`, which has no published implementation); 16 bytes of
  *     `/dev/urandom` are formatted as an RFC-4122 v4 UUID instead. Unix-only.
  *   - ZIO signal handlers and shutdown hooks are silent no-ops on Scala Native; benign for stdio,
  *     where the parent closing stdin ends the loop via EOF exactly like the JVM.
  */
object NativeTransportBackend extends TransportBackend:

  /** UUID v4 from 16 bytes of `/dev/urandom` (per-call open; ids are minted rarely — session and
    * task creation). A missing `/dev/urandom` is a broken platform and dies as a defect.
    */
  override def randomId(): UIO[String] = ZIO.succeed {
    val bytes = new Array[Byte](16)
    val in = new java.io.FileInputStream("/dev/urandom")
    try
      var off = 0
      while off < bytes.length do
        val n = in.read(bytes, off, bytes.length - off)
        if n < 0 then throw new java.io.EOFException("unexpected EOF from /dev/urandom")
        off += n
    finally in.close()
    bytes(6) = ((bytes(6) & 0x0f) | 0x40).toByte // version 4
    bytes(8) = ((bytes(8) & 0x3f) | 0x80).toByte // IETF variant
    val hex = bytes.map(b => f"${b & 0xff}%02x").mkString
    s"${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
      s"${hex.substring(16, 20)}-${hex.substring(20)}"
  }

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
          .fromReader(new java.io.InputStreamReader(java.lang.System.in, StandardCharsets.UTF_8))
          .chunks
          .map(chunk => new String(chunk.toArray))
          .via(ZPipeline.splitLines)
          .map(_.trim)
          .filter(_.nonEmpty)
      _ <- stdioLoop(router, session, inLines, emit)
    yield ()

  /** The stdio dispatch loop, factored out of [[serveStdio]] so it can be driven over in-memory
    * streams in tests — identical semantics to `JvmTransportBackend.stdioLoop`: the outbound
    * drainer is forked as a daemon, each inbound frame dispatches in its own fiber (so a handler
    * blocked on a server→client round trip never stalls the read loop), and stdin EOF ends the
    * loop, taking the drainer down via `.ensuring`.
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

  /** The Scala Native platform seam, in the impl object so it's exportable (givens can't be
    * wildcard-exported straight from a package). `ExportsNative` re-exports this so `import
    * com.tjclp.fastmcp.*` puts a `TransportBackend` in scope and `McpServer(...)` resolves.
    */
  given instance: TransportBackend = this
