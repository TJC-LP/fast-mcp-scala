package com.tjclp.fastmcp.server

import java.io.ByteArrayInputStream
import java.io.FilterInputStream

import zio.*
import zio.test.*

/** Tests for the stdin EOF shutdown mechanism introduced to fix GitHub issue #24.
  *
  * Validates that the FilterInputStream + Promise pattern correctly detects EOF and signals the ZIO
  * fiber, allowing runStdio() to complete when stdin closes.
  */
@SuppressWarnings(Array("org.wartremover.warts.Null"))
object FastMcpServerStdinEofSpec extends ZIOSpecDefault {

  /** Creates a FilterInputStream wrapper that signals a Promise on EOF, mirroring the production
    * implementation in FastMcpServer.runStdio().
    */
  private def makeEofDetectingStream(
      underlying: java.io.InputStream,
      stdinClosed: Promise[Nothing, Unit]
  ): FilterInputStream =
    new FilterInputStream(underlying) {
      override def read(): Int = {
        val b = super.read()
        if (b == -1) signalEof(stdinClosed)
        b
      }
      override def read(buf: Array[Byte], off: Int, len: Int): Int = {
        val n = super.read(buf, off, len)
        if (n == -1) signalEof(stdinClosed)
        n
      }
      private def signalEof(p: Promise[Nothing, Unit]): Unit =
        Unsafe.unsafe { implicit unsafe =>
          val _ = Runtime.default.unsafe.run(p.succeed(())).getOrThrowFiberFailure()
        }
    }

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("FastMcpServer stdin EOF shutdown")(
      test("FilterInputStream wrapper detects EOF from read() and completes promise") {
        for {
          stdinClosed <- Promise.make[Nothing, Unit]
          emptyStream = new ByteArrayInputStream(Array.emptyByteArray)
          wrappedIn = makeEofDetectingStream(emptyStream, stdinClosed)
          result <- ZIO.attempt(wrappedIn.read())
          _ <- stdinClosed.await
        } yield assertTrue(result == -1)
      },
      test("FilterInputStream wrapper detects EOF from read(buf, off, len) and completes promise") {
        for {
          stdinClosed <- Promise.make[Nothing, Unit]
          emptyStream = new ByteArrayInputStream(Array.emptyByteArray)
          wrappedIn = makeEofDetectingStream(emptyStream, stdinClosed)
          buf = new Array[Byte](64)
          result <- ZIO.attempt(wrappedIn.read(buf, 0, buf.length))
          _ <- stdinClosed.await
        } yield assertTrue(result == -1)
      },
      test("FilterInputStream wrapper passes through data before EOF") {
        for {
          stdinClosed <- Promise.make[Nothing, Unit]
          data = "hello".getBytes("UTF-8")
          dataStream = new ByteArrayInputStream(data)
          wrappedIn = makeEofDetectingStream(dataStream, stdinClosed)
          // Read all bytes — should get the data first
          b0 <- ZIO.attempt(wrappedIn.read())
          b1 <- ZIO.attempt(wrappedIn.read())
          b2 <- ZIO.attempt(wrappedIn.read())
          b3 <- ZIO.attempt(wrappedIn.read())
          b4 <- ZIO.attempt(wrappedIn.read())
          // Promise should NOT be completed yet
          isDoneBefore <- stdinClosed.isDone
          // Next read triggers EOF
          eof <- ZIO.attempt(wrappedIn.read())
          _ <- stdinClosed.await
        } yield assertTrue(
          b0 == 'h'.toInt,
          b1 == 'e'.toInt,
          b2 == 'l'.toInt,
          b3 == 'l'.toInt,
          b4 == 'o'.toInt,
          !isDoneBefore,
          eof == -1
        )
      },
      test("Promise.succeed is idempotent — multiple EOF signals do not error") {
        for {
          stdinClosed <- Promise.make[Nothing, Unit]
          emptyStream = new ByteArrayInputStream(Array.emptyByteArray)
          wrappedIn = makeEofDetectingStream(emptyStream, stdinClosed)
          // Both read methods will see EOF and try to signal
          _ <- ZIO.attempt(wrappedIn.read())
          _ <- ZIO.attempt(wrappedIn.read(new Array[Byte](1), 0, 1))
          _ <- stdinClosed.await
        } yield assertTrue(true)
      }
    )
}
