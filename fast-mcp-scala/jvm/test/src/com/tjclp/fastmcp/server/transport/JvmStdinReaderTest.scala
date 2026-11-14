package com.tjclp.fastmcp
package server.transport

import java.io.{IOException, InputStream, PipedInputStream, PipedOutputStream}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*

/** `JvmTransportBackend.interruptibleBytes`: stdin read on a dedicated thread behind a bounded
  * queue, so the stream — and with it a stdio server under SIGTERM — can be interrupted while the
  * read is blocked (GH #113), without the reader running ahead of a slow consumer.
  */
class JvmStdinReaderTest extends AnyFunSuite with Matchers:

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u =>
      Runtime.default.unsafe
        .run(effect.timeoutFail(new RuntimeException("stdin reader test hung"))(60.seconds))
        .getOrThrowFiberFailure()
    )

  test("every byte arrives in order and end of input ends the stream") {
    val bytes = Array.tabulate[Byte](100 * 1024 + 7)(i => (i % 251).toByte)
    val read = runUnsafe(
      JvmTransportBackend.interruptibleBytes(new java.io.ByteArrayInputStream(bytes)).runCollect
    )
    read.toArray.toSeq shouldBe bytes.toSeq
  }

  test("a read failure fails the stream after the bytes read before it") {
    val in = new InputStream:
      private val calls = new AtomicInteger()
      override def read(): Int = throw new IOException("single-byte read is not used")
      override def read(buffer: Array[Byte]): Int =
        if calls.getAndIncrement() == 0 then
          buffer(0) = 42
          1
        else throw new IOException("boom")
    val (seen, outcome) = runUnsafe(
      for
        seen <- Ref.make(Chunk.empty[Byte])
        outcome <- JvmTransportBackend
          .interruptibleBytes(in)
          .runForeach(b => seen.update(_ :+ b))
          .either
        bytes <- seen.get
      yield (bytes, outcome)
    )
    seen shouldBe Chunk(42.toByte)
    outcome.left.map(_.getMessage) shouldBe Left("boom")
  }

  test("the stream is interrupted while the read is blocked") {
    val source = new PipedOutputStream()
    val in = new PipedInputStream(source)
    val exit = runUnsafe(
      for
        first <- Promise.make[Nothing, Unit]
        consumer <- JvmTransportBackend
          .interruptibleBytes(in)
          .runForeach(_ => first.succeed(()))
          .fork
        // One byte through, then nothing: the reader thread goes back into a read that will not
        // return while this test runs.
        _ <- ZIO.attemptBlocking { source.write(1); source.flush() }
        _ <- first.await
        exit <- consumer.interrupt
      yield exit
    )
    exit.isInterrupted shouldBe true
  }

  test("the reader thread stops reading when the consumer does not keep up") {
    val readAhead = 4
    // The consumer's first take holds at most `readAhead` chunks, the queue refills to
    // `readAhead`, and one more read waits in its offer.
    val mostReadsWhileBlocked = 2 * readAhead + 1
    val reads = new AtomicInteger()
    val ranAhead = new CountDownLatch(1)
    val in = new InputStream:
      override def read(): Int = throw new IOException("single-byte read is not used")
      override def read(buffer: Array[Byte]): Int =
        if reads.incrementAndGet() > mostReadsWhileBlocked + 1 then ranAhead.countDown()
        buffer(0) = 7
        1
    val (whileBlocked, afterRelease) = runUnsafe(
      for
        gate <- Promise.make[Nothing, Unit]
        consumer <- JvmTransportBackend
          .interruptibleBytes(in, readAhead)
          .runForeach(_ => gate.await)
          .fork
        // The source never runs dry, so only back-pressure can keep the latch closed. This can
        // pass by luck on a stalled machine but cannot fail on a correct reader.
        whileBlocked <- ZIO.attemptBlocking(ranAhead.await(300, TimeUnit.MILLISECONDS))
        _ <- gate.succeed(())
        afterRelease <- ZIO.attemptBlocking(ranAhead.await(20, TimeUnit.SECONDS))
        _ <- consumer.interrupt
      yield (whileBlocked, afterRelease)
    )
    whileBlocked shouldBe false
    afterRelease shouldBe true
  }
