package com.tjclp.fastmcp.server

import java.io.ByteArrayInputStream
import java.io.FilterInputStream

import scala.concurrent.Promise

import org.scalatest.funsuite.AnyFunSuite

/** Tests for the stdin EOF shutdown mechanism introduced to fix GitHub issue #24.
  *
  * Validates that the FilterInputStream + Promise pattern correctly detects EOF and signals
  * completion, allowing runStdio() to terminate when stdin closes.
  */
@SuppressWarnings(Array("org.wartremover.warts.Null"))
class FastMcpServerStdinEofSpec extends AnyFunSuite {

  /** Creates a FilterInputStream wrapper that signals a Promise on EOF, mirroring the production
    * implementation in FastMcpServer.runStdio().
    */
  private def makeEofDetectingStream(
      underlying: java.io.InputStream,
      signalEof: () => Unit
  ): FilterInputStream =
    new FilterInputStream(underlying) {
      override def read(): Int = {
        val b = super.read()
        if (b == -1) signalEof()
        b
      }
      override def read(buf: Array[Byte], off: Int, len: Int): Int = {
        val n = super.read(buf, off, len)
        if (n == -1) signalEof()
        n
      }
    }

  test("FilterInputStream wrapper detects EOF from read() and completes promise") {
    val stdinClosed = Promise[Unit]()
    val emptyStream = new ByteArrayInputStream(Array.emptyByteArray)
    val wrappedIn = makeEofDetectingStream(emptyStream, () => stdinClosed.trySuccess(()): Unit)

    val result = wrappedIn.read()

    assert(result == -1 && stdinClosed.isCompleted)
  }

  test("FilterInputStream wrapper detects EOF from read(buf, off, len) and completes promise") {
    val stdinClosed = Promise[Unit]()
    val emptyStream = new ByteArrayInputStream(Array.emptyByteArray)
    val wrappedIn = makeEofDetectingStream(emptyStream, () => stdinClosed.trySuccess(()): Unit)
    val buf = new Array[Byte](64)

    val result = wrappedIn.read(buf, 0, buf.length)

    assert(result == -1 && stdinClosed.isCompleted)
  }

  test("FilterInputStream wrapper passes through data before EOF") {
    val stdinClosed = Promise[Unit]()
    val data = "hello".getBytes("UTF-8")
    val dataStream = new ByteArrayInputStream(data)
    val wrappedIn = makeEofDetectingStream(dataStream, () => stdinClosed.trySuccess(()): Unit)

    val b0 = wrappedIn.read()
    val b1 = wrappedIn.read()
    val b2 = wrappedIn.read()
    val b3 = wrappedIn.read()
    val b4 = wrappedIn.read()
    val isDoneBefore = stdinClosed.isCompleted
    val eof = wrappedIn.read()

    assert(
      b0 == 'h'.toInt &&
        b1 == 'e'.toInt &&
        b2 == 'l'.toInt &&
        b3 == 'l'.toInt &&
        b4 == 'o'.toInt &&
        !isDoneBefore &&
        eof == -1 &&
        stdinClosed.isCompleted
    )
  }

  test("Promise.trySuccess is idempotent - multiple EOF signals do not error") {
    val stdinClosed = Promise[Unit]()
    val emptyStream = new ByteArrayInputStream(Array.emptyByteArray)
    val wrappedIn = makeEofDetectingStream(emptyStream, () => stdinClosed.trySuccess(()): Unit)

    // Both read methods will see EOF and try to signal.
    wrappedIn.read()
    wrappedIn.read(new Array[Byte](1), 0, 1)

    assert(stdinClosed.isCompleted)
  }
}
