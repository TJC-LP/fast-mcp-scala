package com.tjclp.fastmcp.server.transport

import zio.*
import zio.stream.*

/** `ZPipeline.splitLines` with a cap on the buffered line.
  *
  * The stock splitter accumulates a whole line in memory before emitting it, so on stdio a peer
  * that never sends a newline grows the read buffer without bound — `MessageLoop.parseFrame`'s
  * `maxFrameChars` check runs only after the line is complete. This pipeline keeps the first
  * `maxChars + 1` characters of an over-long line, DISCARDS the rest of that line as it streams in
  * (the buffer never grows past the cap), and emits the truncated prefix as the frame at the next
  * newline (or end of stream). `parseFrame` then rejects it as `FrameTooLong` (-32700), so the peer
  * still gets a reply for every line it sent.
  *
  * Line semantics match `splitLines`: `\\n` and `\\r\\n` terminate a line (a `\\r\\n` split across
  * chunks is handled), a lone `\\r` is content, and a trailing partial line is flushed at end of
  * stream.
  */
object BoundedLines:

  def pipeline(maxChars: Int)(implicit trace: Trace): ZPipeline[Any, Nothing, String, String] =
    ZPipeline.suspend {
      val state = new State(maxChars)

      lazy val loop: ZChannel[Any, ZNothing, Chunk[String], Any, Nothing, Chunk[String], Any] =
        ZChannel.readWithCause(
          in =>
            val out = state.feed(in)
            if out.isEmpty then loop else ZChannel.write(out) *> loop
          ,
          err =>
            state.flush.fold(ZChannel.refailCause(err))(
              ZChannel.write(_) *> ZChannel.refailCause(err)
            ),
          done =>
            state.flush.fold(ZChannel.succeed(done))(ZChannel.write(_) *> ZChannel.succeed(done))
        )

      ZPipeline.fromChannel(loop)
    }

  /** Mutable splitter state — one per pipeline instance (`ZPipeline.suspend` allocates it per
    * stream), never shared across fibers.
    */
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private final class State(maxChars: Int):
    // `maxChars + 1` would wrap for LimitSettings(maxFrameChars = Int.MaxValue) — a legal
    // "unbounded" configuration — so saturate instead of overflowing into a negative cap.
    private val cap = if maxChars == Int.MaxValue then Int.MaxValue else maxChars + 1
    private val line = new java.lang.StringBuilder
    private var discarding = false

    /** Append `s(from until until)` to the current line, keeping at most `cap` chars. */
    private def append(s: String, from: Int, until: Int): Unit =
      if !discarding && until > from then
        val room = cap - line.length
        val take = if until - from <= room then until else from + room
        line.append(s, from, take)
        if take < until then discarding = true

    /** Emit the current line (with one trailing `\\r` stripped — CRLF), reset. */
    private def emit(): String =
      if !discarding && line.length > 0 && line.charAt(line.length - 1) == '\r' then
        line.setLength(line.length - 1)
      val out = line.toString
      line.setLength(0)
      discarding = false
      out

    def feed(chunk: Chunk[String]): Chunk[String] =
      val builder = ChunkBuilder.make[String]()
      chunk.foreach { s =>
        var from = 0
        var nl = s.indexOf('\n')
        while nl >= 0 do
          append(s, from, nl)
          builder += emit()
          from = nl + 1
          nl = s.indexOf('\n', from)
        append(s, from, s.length)
      }
      builder.result()

    def flush: Option[Chunk[String]] =
      if line.length == 0 && !discarding then None else Some(Chunk.single(emit()))
