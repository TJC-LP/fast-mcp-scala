package com.tjclp.fastmcp
package server.transport

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.stream.*

import com.tjclp.fastmcp.server.LimitSettings

/** [[BoundedLines]] — `splitLines` parity plus the read-buffer cap that keeps a newline-less stdio
  * peer from growing the accumulator without bound (the frame is truncated to `maxChars + 1` and
  * `parseFrame` rejects it as FrameTooLong).
  */
class BoundedLinesTest extends AnyFunSuite with Matchers:

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private def split(maxChars: Int, chunks: String*): Chunk[String] =
    runUnsafe(ZStream.fromIterable(chunks).via(BoundedLines.pipeline(maxChars)).runCollect)

  test("splitLines parity: LF, CRLF, trailing partial line") {
    split(100, "a\nbb\r\nccc") shouldBe Chunk("a", "bb", "ccc")
    split(100, "a\nbb\r\nccc\n") shouldBe Chunk("a", "bb", "ccc")
    split(100, "one", "\ntwo") shouldBe Chunk("one", "two")
    split(100, "") shouldBe Chunk.empty
    split(100, "\n\n") shouldBe Chunk("", "")
  }

  test("splitLines parity: CRLF split across chunks, lone CR is content") {
    split(100, "a\r", "\nb") shouldBe Chunk("a", "b")
    split(100, "a\rb\n") shouldBe Chunk("a\rb")
    // Same shapes through the stock splitter, to pin parity.
    def stock(chunks: String*) =
      runUnsafe(ZStream.fromIterable(chunks).via(ZPipeline.splitLines).runCollect)
    stock("a\r", "\nb") shouldBe Chunk("a", "b")
    stock("a\rb\n") shouldBe Chunk("a\rb")
    stock("a\nbb\r\nccc") shouldBe Chunk("a", "bb", "ccc")
  }

  test("an over-long line is truncated to maxChars + 1 and the next line arrives intact") {
    val out = split(10, "x" * 1000 + "\nnext\n")
    out shouldBe Chunk("x" * 11, "next")
    // ... also when the long line is spread over many chunks and never terminated
    val pieces = Seq.fill(50)("y" * 20) :+ "\nafter"
    split(10, pieces*) shouldBe Chunk("y" * 11, "after")
  }

  test("50 MB without a newline is bounded to maxChars + 1 and emitted at end of stream") {
    val chunk = "x" * (64 * 1024)
    val stream = ZStream.repeatZIO(ZIO.succeed(chunk)).take(800) // 800 × 64 KiB = 50 MiB
    val out = runUnsafe(stream.via(BoundedLines.pipeline(10)).runCollect)
    out.map(_.length) shouldBe Chunk(11)
  }

  test("the truncated frame is rejected by parseFrame as maxFrameChars") {
    val frame = split(10, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n").head
    frame.length shouldBe 11
    MessageLoop.parseFrame(frame, LimitSettings(maxFrameChars = 10)) match
      case Left(jsonrpc.JsonRpcMessage.Failure(None, err)) =>
        err.code shouldBe -32700
        err.message should include("maxFrameChars")
      case other => fail(s"unexpected $other")
  }
