package com.tjclp.fastmcp
package server.transport

import java.nio.charset.StandardCharsets

import zio.*
import zio.stream.*

import com.tjclp.fastmcp.server.McpServerSettings
import com.tjclp.fastmcp.server.router.McpRouter

/** Scala Native [[TransportBackend]] — pure ZIO over `System.in`/`System.out`, compiled to a
  * standalone binary via LLVM. Stdio only: zio-http is not published for Scala Native, so this
  * platform provides no [[HttpTransportBackend]] given and `McpServerApp[Http]` programs fail to
  * compile at the declaration site (by design — the same seam that keeps netty out of GraalVM stdio
  * images).
  *
  * The serving lifecycle is shared with the JVM ([[StdioLoop]]); only the two pieces that genuinely
  * differ on this platform live here:
  *   - stdin: zio-streams Native has no `ZStream.fromInputStream`; chars are read via
  *     `ZStream.fromReader` (the `InputStreamReader` owns byte→UTF-8 decoding) and re-chunked into
  *     Strings for the shared `BoundedLines.pipeline` (line buffer bounded at
  *     `limits.maxFrameChars`).
  *   - `randomId()`: javalib's `UUID.randomUUID()` does not link on Scala Native 0.5 (it references
  *     `java.security.SecureRandom`, which has no published implementation); 16 bytes of
  *     `/dev/urandom` are formatted as an RFC-4122 v4 UUID instead. Unix-only.
  *
  * ZIO signal handlers and shutdown hooks are silent no-ops on Scala Native; benign for stdio,
  * where the parent closing stdin ends the loop via EOF exactly like the JVM.
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
    StdioLoop.serve(
      router,
      ZStream
        .fromReader(new java.io.InputStreamReader(java.lang.System.in, StandardCharsets.UTF_8))
        .chunks
        .map(chunk => new String(chunk.toArray))
        .via(BoundedLines.pipeline(router.limits.maxFrameChars))
        .filter(MessageLoop.shouldDispatchStdioFrame(_, router.limits))
    )

  /** The Scala Native platform seam, in the impl object so it's exportable (givens can't be
    * wildcard-exported straight from a package). `ExportsNative` re-exports this so `import
    * com.tjclp.fastmcp.*` puts a `TransportBackend` in scope and `McpServer(...)` resolves.
    */
  given instance: TransportBackend = this
