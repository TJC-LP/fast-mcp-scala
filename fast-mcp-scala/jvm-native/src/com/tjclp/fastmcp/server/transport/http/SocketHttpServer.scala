package com.tjclp.fastmcp
package server.transport.http

import java.io.{BufferedOutputStream, InputStream, OutputStream}
import java.net.{InetSocketAddress, ServerSocket, Socket, SocketTimeoutException}
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import zio.*
import zio.stm.TSemaphore
import zio.stream.*

import com.tjclp.fastmcp.server.transport.http.Http1.{Framing, Head, Rejection, Version}

/** A minimal HTTP/1.1 + SSE server over `java.net.ServerSocket`, sufficient for MCP streamable HTTP
  * and nothing more. It exists because zio-http has no Scala Native artifacts; on the JVM the same
  * code is the netty-free opt-in. Compiled by both the `jvm` and `scalaNative` modules
  * (`jvm-native/`), so it uses only the javalib subset both platforms link — blocking sockets, no
  * NIO channels, no `Locale`, no TLS.
  *
  * Threading model: the accept loop and every socket read/write run on ZIO's blocking executor via
  * `attemptBlockingCancelable`, whose cancel action closes the socket — the only way to unblock a
  * read on Scala Native, where `Thread.interrupt` has no effect on sockets. One fiber per
  * connection; an idle keep-alive connection or an idle SSE stream costs one parked blocking
  * thread, so the accept loop is gated by [[MaxConnections]] (ZIO's blocking pool is unbounded).
  *
  * Wire behaviour: HTTP/1.1 keep-alive with requests handled sequentially per connection;
  * `Content-Length` and `Transfer-Encoding: chunked` request bodies; `Expect: 100-continue` (curl
  * sends it for bodies over 1 KiB); HTTP/1.0 and `Connection: close` clients get close-delimited
  * responses; SSE replies stream as chunked transfer-encoding with one chunk per event, flushed
  * immediately, and a terminating chunk so the connection stays reusable. While an SSE reply
  * streams, a watcher fiber blocks on the socket's read side so a client that silently disconnects
  * is noticed even when the stream is quiet — that is what lets the MCP layer's finalizers
  * (`session.releaseGet`, request-queue shutdown) run for abandoned GET streams.
  */
private[fastmcp] object SocketHttpServer:

  /** Request line + headers cap; larger heads are rejected with `431`. */
  val MaxHeadBytes: Int = 8 * 1024

  /** Request body cap (declared or chunked); larger bodies are rejected with `413`. */
  val MaxBodyBytes: Int = 16 * 1024 * 1024

  /** Concurrent connection cap — one blocking thread each while idle. Extra connections wait in the
    * kernel backlog.
    */
  val MaxConnections: Int = 256

  val Backlog: Int = 128

  /** A connection with no request activity (or an SSE stream whose client stays silent after it
    * ended) is closed after this long.
    */
  val IdleReadTimeoutMs: Int = 60_000

  /** `accept` wakes this often so a shutdown request is honoured promptly even on platforms where
    * closing the listener does not unblock a pending `accept`.
    */
  val AcceptPollMs: Int = 500

  /** While an SSE reply streams, the read-side watcher polls this often, so it notices the stream
    * ending and hands any bytes it buffered (a pipelined request) back to the request loop without
    * waiting out the idle timeout.
    */
  val WatchPollMs: Int = 1_000

  final case class Bound(host: String, port: Int)

  /** A running server: its address and the accept-loop fiber (join it to serve "forever" — the loop
    * only ends by failing, which must surface rather than leave a listener that never answers).
    */
  final case class Started(bound: Bound, loop: Fiber.Runtime[Throwable, Nothing])

  /** Set `FASTMCP_HTTP_DEBUG=1` to print per-connection failures (a peer resetting mid-request, a
    * rejected head, an idle timeout) to stderr; they are otherwise swallowed, as a misbehaving
    * client must not be able to affect the server. Scala Native has no logging framework to route
    * them to, so this is the one diagnostic knob.
    */
  private val debug: Boolean =
    Option(java.lang.System.getenv("FASTMCP_HTTP_DEBUG")).exists(v => v.nonEmpty && v != "0")

  private def debugLog(message: => String): UIO[Unit] =
    if debug then ZIO.succeed(java.lang.System.err.println(s"[fastmcp-http] $message"))
    else ZIO.unit

  /** Bind `host:port` (port `0` picks a free port — see the returned [[Bound]]) and fork the accept
    * loop into the enclosing `Scope`. Closing the scope interrupts the loop, which closes the
    * listener, and — through fiber supervision — every connection fiber, each of which closes its
    * socket.
    */
  def start[R](host: String, port: Int)(
      handle: HttpRequest => URIO[R, HttpReply]
  ): ZIO[R & Scope, Throwable, Started] =
    for
      server <- ZIO.acquireRelease(
        ZIO.attemptBlocking {
          val s = new ServerSocket()
          s.setReuseAddress(true)
          s.bind(new InetSocketAddress(host, port), Backlog)
          s.setSoTimeout(AcceptPollMs)
          s
        }
      )(s => ZIO.attemptBlocking(s.close()).ignore)
      gate <- TSemaphore.make(MaxConnections.toLong).commit
      loop <- acceptLoop(server, gate, handle)
        .tapErrorCause(cause => debugLog(s"accept loop failed: ${cause.prettyPrint}"))
        .forkScoped
    yield Started(Bound(host, server.getLocalPort), loop)

  private def acceptLoop[R](
      server: ServerSocket,
      gate: TSemaphore,
      handle: HttpRequest => URIO[R, HttpReply]
  ): ZIO[R, Throwable, Nothing] =
    val acceptOne: IO[Throwable, Option[Socket]] =
      ZIO
        .attemptBlockingCancelable(server.accept())(ZIO.succeed(server.close()))
        .asSome
        .catchSome { case _: SocketTimeoutException => ZIO.none }
    val step: ZIO[R, Throwable, Unit] =
      gate.acquire.commit *> acceptOne.foldCauseZIO(
        cause => gate.release.commit *> ZIO.refailCause(cause),
        {
          case None => gate.release.commit
          case Some(socket) =>
            // Plain `fork`: connections are children of the accept loop, so ending the loop
            // (scope close) interrupts them all; per-connection `forkScoped` would pile up
            // finalizers in the server scope for the life of the process.
            serveConnection(socket, handle).ensuring(gate.release.commit).fork.unit
        }
      )
    step.forever

  private def serveConnection[R](
      socket: Socket,
      handle: HttpRequest => URIO[R, HttpReply]
  ): URIO[R, Unit] =
    ZIO
      .scoped {
        ZIO
          .acquireRelease(
            ZIO.attempt {
              socket.setTcpNoDelay(true)
              socket.setSoTimeout(IdleReadTimeoutMs)
              new Connection(socket)
            }
          )(_.close)
          .flatMap(_.run(handle))
      }
      // A misbehaving peer (malformed bytes, reset, idle timeout) affects only its own connection.
      .tapErrorCause(cause => debugLog(s"connection ended: ${cause.prettyPrint}"))
      .catchAllDefect(_ => ZIO.unit)
      .ignore

  /** Outcome of watching a connection's read side while an SSE reply streams. */
  private enum Watch:
    case Dead, Data, Timeout

  /** One accepted socket: the request loop, framing, and response writing. */
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private final class Connection(socket: Socket):
    private val input = new ConnectionInput(socket.getInputStream)
    private val out: OutputStream = new BufferedOutputStream(socket.getOutputStream, 16 * 1024)

    def close: UIO[Unit] = ZIO.attemptBlocking(socket.close()).ignore

    /** Run blocking socket work; interrupting the fiber closes the socket, which unblocks it. */
    private def blocking[A](thunk: => A): Task[A] =
      ZIO.attemptBlockingCancelable(thunk)(close)

    def run[R](handle: HttpRequest => URIO[R, HttpReply]): ZIO[R, Throwable, Unit] =
      handleOne(handle).flatMap(keepAlive => if keepAlive then run(handle) else ZIO.unit)

    /** Serve one request; `true` when the connection can carry another. An idle-timeout while
      * waiting for the next request surfaces as a `SocketTimeoutException` failure, which closes
      * the connection.
      */
    private def handleOne[R](
        handle: HttpRequest => URIO[R, HttpReply]
    ): ZIO[R, Throwable, Boolean] =
      blocking(input.readHead(MaxHeadBytes)).flatMap {
        case Left(rejection) => reject(rejection).as(false)
        case Right(None) => ZIO.succeed(false) // clean EOF between requests
        case Right(Some(headBytes)) =>
          Http1.parseHead(headBytes) match
            case Left(rejection) => reject(rejection).as(false)
            case Right(head) => dispatch(head, handle)
      }

    private def dispatch[R](
        head: Head,
        handle: HttpRequest => URIO[R, HttpReply]
    ): ZIO[R, Throwable, Boolean] =
      head.bodyFraming(MaxBodyBytes) match
        case Left(rejection) => reject(rejection).as(false)
        case Right(framing) =>
          // The body is read lazily: the shared handler forces `request.body` only after its
          // Host/Origin and Accept guards pass, so a request those guards reject never costs the
          // read (up to 16 MiB) — the same ordering the zio-http adapter gets from its lazy body.
          // `100 Continue`, which solicits the body, is sent at that same point; RFC 9110 lets a
          // server answer on the head alone without ever asking for the body.
          val consumed = new AtomicBoolean(false)
          val failed = new AtomicReference[Option[Rejection]](None)
          for
            body <- deferredBody(head, framing, consumed, failed).memoize
            reply <- handle(HttpRequest(head.method, head.path, head.header, body))
            // A body read that failed mid-way keeps its own status (413 for an over-cap chunked
            // body, 400 for truncation) rather than the handler's generic 400.
            finalReply = failed.get.fold(reply)(r =>
              StreamableHttpHandler.transportError(r.status, r.message)
            )
            // A declared body that was never consumed, or failed part-way, leaves unknown bytes on
            // the socket: close after the reply so they are never parsed as the next request.
            bodyClean = framing == Framing.Empty || (consumed.get && failed.get.isEmpty)
            usable <- writeReply(finalReply, head.version, head.wantsClose || !bodyClean)
          yield usable

    private def deferredBody(
        head: Head,
        framing: Framing,
        consumed: AtomicBoolean,
        failed: AtomicReference[Option[Rejection]]
    ): Task[String] =
      val interim =
        if head.expectsContinue && framing != Framing.Empty then
          blocking {
            writeRaw("HTTP/1.1 100 Continue\r\n\r\n")
            out.flush()
          }
        else ZIO.unit
      interim *> readBody(framing).flatMap {
        case Right(body) =>
          ZIO.succeed(consumed.set(true)).as(body)
        case Left(rejection) =>
          ZIO.succeed {
            consumed.set(true)
            failed.set(Some(rejection))
          } *> ZIO.fail(new java.io.IOException(rejection.message))
      }

    private def readBody(framing: Framing): Task[Either[Rejection, String]] =
      framing match
        case Framing.Empty => ZIO.succeed(Right(""))
        case Framing.Length(n) =>
          blocking(input.readExactly(n)).map {
            case Some(bytes) => Right(new String(bytes, StandardCharsets.UTF_8))
            case None => Left(Rejection(400, "Request body ended prematurely"))
          }
        case Framing.Chunked =>
          blocking(input.readChunked(MaxBodyBytes))
            .map(_.map(bytes => new String(bytes, StandardCharsets.UTF_8)))

    /** Transport-level rejection: the same JSON-RPC error body the MCP layer uses, then close. */
    private def reject(rejection: Rejection): Task[Unit] =
      writeJson(
        StreamableHttpHandler.transportError(rejection.status, rejection.message),
        close = true
      ).unit

    private def writeReply(reply: HttpReply, version: Version, close: Boolean): Task[Boolean] =
      reply match
        case HttpReply.Empty(status, headers) =>
          blocking {
            writeHead(status, headers :+ ("content-length" -> "0"), close)
            out.flush()
          }.as(!close)
        case json: HttpReply.Json => writeJson(json, close)
        case HttpReply.Sse(headers, frames) => streamSse(headers, frames, version, close)

    private def writeJson(json: HttpReply.Json, close: Boolean): Task[Boolean] =
      blocking {
        val bytes = json.body.getBytes(StandardCharsets.UTF_8)
        writeHead(
          json.status,
          json.headers ++ List(
            "content-type" -> "application/json",
            "content-length" -> bytes.length.toString
          ),
          close
        )
        out.write(bytes)
        out.flush()
      }.as(!close)

    /** Stream an SSE reply. HTTP/1.1 clients get chunked transfer-encoding and keep the connection;
      * HTTP/1.0 clients get a close-delimited body. Returns whether the connection is still usable
      * for another request.
      */
    private def streamSse(
        headers: List[(String, String)],
        frames: ZStream[Any, Nothing, SseFrame],
        version: Version,
        close: Boolean
    ): Task[Boolean] =
      val chunked = version == Version.Http11
      val closeAfter = close || !chunked
      val stop = new AtomicBoolean(false)
      for
        headWritten <- blocking {
          writeHead(
            200,
            headers ++ List(
              "content-type" -> "text/event-stream",
              "cache-control" -> "no-cache"
            ) ++ (if chunked then List("transfer-encoding" -> "chunked") else Nil),
            closeAfter
          )
          out.flush()
        }.foldCause(_ => false, _ => true)
        dead <- Promise.make[Nothing, Unit]
        // If the peer vanished before the head even went out, the stream must STILL run — halted
        // at once through `dead` — because its finalizers are the only thing that interrupts the
        // request's dispatch fiber, shuts its queue, and releases the session's GET slot. Skipping
        // the stream here would leave a `subscriptions/listen` dispatch running forever and every
        // later GET on the session answering 409.
        _ <- ZIO.unless(headWritten)(dead.succeed(()))
        // The read side is otherwise silent while we stream, so a peer that vanishes would go
        // unnoticed until the next write — never, for a quiet GET channel. Watch it: EOF or an
        // error completes `dead`, which halts the stream and runs the MCP layer's finalizers.
        // Short read timeouts while streaming: the watcher re-checks `stop` every poll instead of
        // sitting in one idle-timeout-long read after the stream has already ended.
        _ <- ZIO.attempt(socket.setSoTimeout(WatchPollMs)).ignore
        watcher <- blocking(input.awaitActivity(stop))
          .tap(outcome => ZIO.when(outcome == Watch.Dead)(dead.succeed(())))
          .catchAll(_ => dead.succeed(()).as(Watch.Dead))
          .fork
        // Run the stream in a fresh fiber and join it: a forked fiber starts ZIO's run loop at
        // depth zero, so the stream interpreter (merge + schedule + interruptWhen) never inherits
        // the connection fiber's nesting. On Scala Native that is the difference between running
        // and a StackOverflowError — ZIO only trampolines at depth 300, and Native frames are
        // large enough that a keepalive-merged stream started deep in a request overflows the
        // 1 MiB thread stack.
        streamed <- frames
          .interruptWhen(dead)
          .runForeach(frame => blocking(writeFrame(frame.encode, chunked)))
          .fork
          .flatMap(_.join)
          .foldCauseZIO(
            cause => if cause.isInterrupted then ZIO.refailCause(cause) else ZIO.succeed(false),
            _ => ZIO.succeed(true)
          )
        clientGone <- dead.isDone
        usable = headWritten && streamed && !clientGone && !closeAfter
        _ <- ZIO.when(headWritten && streamed && !clientGone && chunked)(blocking {
          writeRaw("0\r\n\r\n")
          out.flush()
        })
        _ <- ZIO.succeed(stop.set(true))
        // A usable connection waits for the watcher to hand back the read side: it returns as
        // soon as the client sends its next request (bytes stay buffered), or on idle timeout /
        // EOF, both of which end the connection like any other idle keep-alive would.
        activity <- if usable then watcher.join else watcher.interrupt.as(Watch.Dead)
        _ <- ZIO.attempt(socket.setSoTimeout(IdleReadTimeoutMs)).ignore
      yield usable && activity == Watch.Data

    private def writeHead(status: Int, headers: List[(String, String)], close: Boolean): Unit =
      val head = new StringBuilder(256)
      head ++= s"HTTP/1.1 $status ${Http1.reason(status)}\r\n"
      headers.foreach { case (name, value) => head ++= s"$name: $value\r\n" }
      head ++= s"connection: ${if close then "close" else "keep-alive"}\r\n\r\n"
      writeRaw(head.toString)

    private def writeRaw(s: String): Unit =
      out.write(s.getBytes(StandardCharsets.ISO_8859_1))

    private def writeFrame(encoded: String, chunked: Boolean): Unit =
      val bytes = encoded.getBytes(StandardCharsets.UTF_8)
      if chunked then
        writeRaw(Integer.toHexString(bytes.length))
        writeRaw("\r\n")
        out.write(bytes)
        writeRaw("\r\n")
      else out.write(bytes)
      out.flush()

  /** Single-reader buffered view of a socket's input: byte-exact reads of request heads, fixed
    * lengths, and chunked bodies, plus the SSE-time activity watch. Every method blocks; callers
    * wrap them in `attemptBlockingCancelable`. Exactly one fiber may be inside at a time — the
    * request loop hands the reader to the watcher while streaming and joins it before reading the
    * next head.
    */
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private final class ConnectionInput(in: InputStream):
    private val InitialBufferBytes = 8 * 1024
    private var buf: Array[Byte] = new Array[Byte](InitialBufferBytes)
    private var start = 0
    private var end = 0

    private def available: Int = end - start

    /** One blocking read appended to the buffer; the count read, or -1 at EOF. Compacts or grows
      * the buffer first, so byte offsets *relative to `start`* stay valid across calls.
      */
    def fill(): Int =
      if start == end then
        start = 0
        end = 0
        // Drained: drop a buffer that a large body grew, so one 16 MiB request on a keep-alive
        // connection does not pin 16 MiB for the connection's whole lifetime.
        if buf.length > InitialBufferBytes then buf = new Array[Byte](InitialBufferBytes)
      else if end == buf.length then
        if start > 0 then
          java.lang.System.arraycopy(buf, start, buf, 0, end - start)
          end -= start
          start = 0
        else buf = java.util.Arrays.copyOf(buf, buf.length * 2)
      val n = in.read(buf, end, buf.length - end)
      if n > 0 then end += n
      n

    /** Block until the peer sends something, closes, or errors — or, once `stop` is set, until the
      * socket's read timeout fires. Bytes read stay buffered for the next [[readHead]].
      */
    def awaitActivity(stop: AtomicBoolean): Watch =
      var outcome: Option[Watch] = None
      var idleSince = -1L
      while outcome.isEmpty do
        try
          val n = fill()
          if n < 0 then outcome = Some(Watch.Dead)
          else if stop.get then outcome = Some(Watch.Data)
          else if available > MaxHeadBytes then
            // More than a request head's worth of unsolicited bytes while we stream is not a
            // pipelined request: treat the peer as broken rather than buffer without bound.
            outcome = Some(Watch.Dead)
          // Otherwise the bytes stay buffered (a pipelined request, served after the stream) and
          // the watch continues — returning here would leave a peer that vanishes afterwards
          // undetected, parking the connection (and its permit + GET slot) forever.
        catch
          case _: SocketTimeoutException =>
            if stop.get then
              if available > 0 then outcome = Some(Watch.Data)
              else
                val now = java.lang.System.currentTimeMillis()
                if idleSince < 0 then idleSince = now
                else if now - idleSince >= IdleReadTimeoutMs then outcome = Some(Watch.Timeout)
      outcome.getOrElse(Watch.Dead)

    /** Read a request head up to (excluding) its blank-line terminator (`CRLF CRLF`, or bare `LF
      * LF` / `LF CRLF` for lenient clients). `Right(None)` on EOF before any byte.
      */
    def readHead(max: Int): Either[Rejection, Option[Array[Byte]]] =
      var scanned = 0 // bytes from `start` already scanned without finding the terminator
      var result: Option[Either[Rejection, Option[Array[Byte]]]] = None
      while result.isEmpty do
        // RFC 9112 §2.2: ignore empty lines received before the request-line, so a stray extra
        // CRLF from a proxy is not taken as an (empty) head terminator.
        while start < end && (buf(start) == '\r' || buf(start) == '\n') do
          start += 1
          scanned = math.max(0, scanned - 1)
        headEnd(start + scanned) match
          case Some((headEnd, _)) if headEnd - start > max =>
            result = Some(Left(Rejection(431, s"Request head exceeds $max bytes")))
          case Some((headEnd, terminatorLength)) =>
            val bytes = java.util.Arrays.copyOfRange(buf, start, headEnd)
            start = headEnd + terminatorLength
            result = Some(Right(Some(bytes)))
          case None =>
            if available > max then
              result = Some(Left(Rejection(431, s"Request head exceeds $max bytes")))
            else
              // Re-scan the last 3 bytes next time: a terminator may straddle two reads.
              scanned = math.max(0, available - 3)
              if fill() < 0 then
                result = Some(
                  if available == 0 then Right(None)
                  else Left(Rejection(400, "Truncated request head"))
                )
      result.getOrElse(Right(None))

    /** Index just past the head and the terminator length, if a terminator ends at or after `from`.
      */
    private def headEnd(from: Int): Option[(Int, Int)] =
      var i = math.max(from, start)
      var found: Option[(Int, Int)] = None
      while found.isEmpty && i < end do
        if buf(i) == '\n' then
          if i - 3 >= start && buf(i - 1) == '\r' && buf(i - 2) == '\n' && buf(i - 3) == '\r' then
            found = Some((i - 3, 4))
          else if i - 2 >= start && buf(i - 1) == '\r' && buf(i - 2) == '\n' then
            found = Some((i - 2, 3))
          else if i - 1 >= start && buf(i - 1) == '\n' then found = Some((i - 1, 2))
        i += 1
      found

    /** Exactly `n` bytes, or `None` if the peer closed first. */
    def readExactly(n: Int): Option[Array[Byte]] =
      var eof = false
      while available < n && !eof do if fill() < 0 then eof = true
      if available < n then None
      else
        val bytes = java.util.Arrays.copyOfRange(buf, start, start + n)
        start += n
        Some(bytes)

    /** One line up to LF (CR stripped), or `None` at EOF; `Left(400)` past `max` bytes. */
    private def readLine(max: Int): Either[Rejection, Option[String]] =
      var scanned = 0
      var result: Option[Either[Rejection, Option[String]]] = None
      while result.isEmpty do
        var i = start + scanned
        var lf = -1
        while lf < 0 && i < end do
          if buf(i) == '\n' then lf = i
          i += 1
        if lf >= 0 then
          val lineEnd = if lf > start && buf(lf - 1) == '\r' then lf - 1 else lf
          val line = new String(buf, start, lineEnd - start, StandardCharsets.ISO_8859_1)
          start = lf + 1
          result = Some(Right(Some(line)))
        else if available > max then
          result = Some(Left(Rejection(400, "Chunk header line too long")))
        else
          scanned = available
          if fill() < 0 then result = Some(Right(None))
      result.getOrElse(Right(None))

    /** Decode a `Transfer-Encoding: chunked` body (extensions ignored, trailers skipped). */
    def readChunked(max: Int): Either[Rejection, Array[Byte]] =
      val body = new java.io.ByteArrayOutputStream()
      var result: Option[Either[Rejection, Array[Byte]]] = None
      while result.isEmpty do
        readLine(1024) match
          case Left(rejection) => result = Some(Left(rejection))
          case Right(None) => result = Some(Left(Rejection(400, "Truncated chunked body")))
          case Right(Some(line)) =>
            val sizeToken = line.indexOf(';') match
              case -1 => line.trim
              case i => line.substring(0, i).trim
            parseChunkSize(sizeToken) match
              case None => result = Some(Left(Rejection(400, "Malformed chunk size")))
              case Some(0) => result = Some(skipTrailers().map(_ => body.toByteArray))
              case Some(size) =>
                if body.size() + size > max then
                  result = Some(Left(Rejection(413, s"Request body exceeds $max bytes")))
                else
                  readExactly(size) match
                    case None => result = Some(Left(Rejection(400, "Truncated chunk")))
                    case Some(bytes) =>
                      body.write(bytes)
                      readLine(8) match
                        case Right(Some("")) => ()
                        case _ => result = Some(Left(Rejection(400, "Missing CRLF after chunk")))
      result.getOrElse(Left(Rejection(400, "Truncated chunked body")))

    private def skipTrailers(): Either[Rejection, Unit] =
      var result: Option[Either[Rejection, Unit]] = None
      while result.isEmpty do
        readLine(1024) match
          case Right(Some("")) => result = Some(Right(()))
          case Right(Some(_)) => ()
          case Right(None) => result = Some(Left(Rejection(400, "Truncated chunked trailers")))
          case Left(rejection) => result = Some(Left(rejection))
      result.getOrElse(Right(()))

    /** Hex chunk size, capped at 7 digits (≤ 256 MiB) so it always fits an `Int`. */
    private def parseChunkSize(token: String): Option[Int] =
      if token.isEmpty || token.length > 7 || !token.forall(c => Character.digit(c, 16) >= 0) then
        None
      else Some(Integer.parseInt(token, 16))
