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
  * Only the two genuinely platform-specific pieces live here — how stdin becomes a line stream
  * (read on a dedicated thread so that a signal can end the loop, see [[interruptibleBytes]]), and
  * where randomness comes from. The serving lifecycle is shared ([[StdioLoop]]).
  *
  * Deliberately netty-free: HTTP serving lives on [[JvmHttpBackend]] (the [[HttpTransportBackend]]
  * given), so stdio-only programs — and their GraalVM native images — never reference zio-http or
  * Netty.
  */
object JvmTransportBackend extends TransportBackend:

  /** UUID v4 via `java.util.UUID` (SecureRandom-backed). */
  override def randomId(): UIO[String] = ZIO.succeed(UUID.randomUUID().toString)

  /** Platform SHA-256 (`java.security.MessageDigest`) for skill digests; a fresh instance per call,
    * so it is thread-safe without locking.
    */
  override def sha256(bytes: Array[Byte]): Array[Byte] =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)

  override def serveStdio[R](
      router: McpRouter[R],
      settings: McpServerSettings
  ): ZIO[R, Throwable, Unit] =
    StdioLoop.serve(
      router,
      interruptibleBytes(java.lang.System.in)
        .via(ZPipeline.utf8Decode)
        .via(BoundedLines.pipeline(router.limits.maxFrameChars))
        .filter(MessageLoop.shouldDispatchStdioFrame(_, router.limits)),
      settings.stdioDrainTimeout
    )

  /** Bytes read per `InputStream.read` call. */
  private val ReadBufferBytes = 8192

  /** Chunks the reader thread may hold ahead of the stream: with [[ReadBufferBytes]], at most 128
    * KiB of stdin is buffered in front of a dispatcher that is not keeping up.
    */
  private[fastmcp] val ReadAheadChunks = 16

  /** `in` as a byte stream whose consumer can always be interrupted.
    *
    * `System.in.read` cannot be: a thread blocked in it ignores `Thread.interrupt`, and a fiber
    * running it through `ZStream.fromInputStream` cannot complete its interruption until the read
    * returns. A stdio server built that way acted on SIGTERM and SIGINT only after its stdin closed
    * — ZIO's shutdown hook interrupts the main fiber and waits for it — so `Ctrl-C`, `timeout`, a
    * supervisor or `docker stop` hung until SIGKILL (GH #113).
    *
    * The read therefore happens on a dedicated daemon thread that hands each chunk to a bounded
    * queue, and the stream takes from the queue, which is interruptible. The thread offers through
    * the queue's back-pressure, so it reads no further than [[ReadAheadChunks]] chunks ahead of the
    * consumer. End of input and a read failure travel through the queue as the stream's end and
    * failure.
    *
    * When the stream ends first (interruption), the queue is shut down and the thread stops at its
    * next offer; until then it stays blocked in `read`, which is why it is a daemon. Bytes it reads
    * after that are dropped, so one process serves stdio once.
    */
  private[fastmcp] def interruptibleBytes(
      in: => java.io.InputStream,
      readAhead: Int = ReadAheadChunks
  ): ZStream[Any, Throwable, Byte] =
    ZStream.unwrapScoped {
      for
        queue <- ZIO.acquireRelease(Queue.bounded[Take[Throwable, Byte]](readAhead))(_.shutdown)
        runtime <- ZIO.runtime[Any]
        _ <- ZIO.attempt {
          val source = in
          // False once the queue is shut down: the offer is interrupted, there or while it waits.
          def offer(take: Take[Throwable, Byte]): Boolean =
            Unsafe.unsafe(implicit unsafe => runtime.unsafe.run(queue.offer(take)).isSuccess)
          val reader = new Thread(
            () =>
              val buffer = new Array[Byte](ReadBufferBytes)
              @scala.annotation.tailrec
              def pump(): Unit =
                scala.util.Try(source.read(buffer)) match
                  case scala.util.Failure(error) => val _ = offer(Take.fail(error))
                  case scala.util.Success(n) if n < 0 => val _ = offer(Take.end)
                  case scala.util.Success(n) =>
                    if n == 0 || offer(Take.chunk(Chunk.fromArray(buffer.take(n)))) then pump()
              pump()
            ,
            "fast-mcp-stdin"
          )
          reader.setDaemon(true)
          reader.start()
        }
      yield ZStream.fromQueue(queue).flattenTake
    }

  /** The JVM platform seam, in the impl object so it's exportable (givens can't be wildcard-
    * exported straight from a package). `ExportsJvm` re-exports this so `import
    * com.tjclp.fastmcp.*` puts a `TransportBackend` in scope and `McpServer(...)` resolves.
    */
  given instance: TransportBackend = this
