package com.tjclp.fastmcp
package server.transport.http

import java.io.{IOException, InputStream, OutputStream}
import java.net.{InetSocketAddress, ServerSocket, Socket, SocketException, SocketTimeoutException}
import java.nio.charset.StandardCharsets
import java.util.concurrent.{ConcurrentHashMap, Semaphore, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

import scala.util.control.NonFatal

import zio.*
import zio.stream.*

import com.tjclp.fastmcp.server.transport.HttpRequestGuards
import com.tjclp.fastmcp.server.transport.http.Http1.{Framing, Head, Rejection, Version}

/** A minimal HTTP/1.1 + SSE server over `java.net.ServerSocket`, sufficient for MCP streamable HTTP
  * and nothing more. It exists because zio-http has no Scala Native artifacts; on the JVM the same
  * code is the netty-free opt-in. Compiled by both the `jvm` and `scalaNative` modules
  * (`jvm-native/`), so it uses only the javalib subset both platforms link — blocking sockets, no
  * NIO channels, no `Locale`, no TLS.
  *
  * Threading model: one thread per connection, taken from ZIO's blocking executor, that owns the
  * socket for the connection's whole life and does plain blocking I/O on it — no fiber, no executor
  * hop and no ZIO effect per read or write. The thread enters ZIO only to run the MCP handler, via
  * `Runtime.unsafe.runOrFork`: the effect runs on the connection thread itself until it first
  * suspends, so a request the handler can answer synchronously costs no thread handoff at all, and
  * one that suspends (a dispatch running on ZIO's scheduler) costs exactly the wake-up that
  * delivers its result. An idle keep-alive connection or an idle SSE stream parks its one thread,
  * so the accept loop is gated by `maxConnections` (ZIO's blocking pool is unbounded; with a
  * Loom-based blocking executor installed the threads are virtual).
  *
  * Wire behaviour: HTTP/1.1 keep-alive with requests handled sequentially per connection;
  * `Content-Length` and `Transfer-Encoding: chunked` request bodies; `Expect: 100-continue` (curl
  * sends it for bodies over 1 KiB); HTTP/1.0 and `Connection: close` clients get close-delimited
  * responses; SSE replies stream as chunked transfer-encoding with a terminating chunk so the
  * connection stays reusable. Response bytes are coalesced: a reply whose frames are already
  * available goes out as ONE write (head, frames and terminator together), and whatever is pending
  * is flushed before the thread blocks waiting for the next frame.
  *
  * Admission is two-phase. [[App.admit]] judges the request on its head alone — path, method and
  * the shared header gates — and a refusal is answered without the body ever being read (an unread
  * declared body then closes the connection). Only an admitted `POST` has its body read, bounded by
  * `maxBodyBytes` on the wire for declared and chunked bodies alike, after the interim `100
  * Continue` when the client asked for one.
  *
  * Peer liveness: while the thread waits on the handler or on a quiet SSE stream nobody reads the
  * socket, so a peer that vanishes would go unnoticed until the next write — never, for a quiet GET
  * channel. Every [[WatchPollMs]] of quiet the thread therefore probes the read side with a 1 ms
  * read: EOF or an error means the peer is gone, and the pending fiber is interrupted so the MCP
  * layer's finalizers (`session.releaseGet`, the request queue shutdown) run. Bytes the probe reads
  * stay buffered as a pipelined request. A request that completes within one poll interval never
  * pays for a probe. SSE keepalive pings are written from the same wait loop.
  */
private[fastmcp] object SocketHttpServer:

  /** Request line + headers cap; larger heads are rejected with `431`. */
  val MaxHeadBytes: Int = 8 * 1024

  /** Default concurrent connection cap — one parked thread each while idle. Extra connections wait
    * in the kernel backlog.
    */
  val DefaultMaxConnections: Int = 256

  val Backlog: Int = 128

  /** A connection with no request activity is closed after this long. */
  val IdleReadTimeoutMs: Int = 60_000

  /** `accept` wakes this often so a shutdown request is honoured promptly even on platforms where
    * closing the listener does not unblock a pending `accept`.
    */
  val AcceptPollMs: Int = 500

  /** How long the connection thread waits on a quiet handler / SSE stream between peer probes. */
  val WatchPollMs: Int = 1_000

  /** Read timeout of one liveness probe. */
  private val ProbeTimeoutMs = 1

  /** Pending response bytes above this are flushed even when more frames are ready. */
  private val FlushThresholdBytes = 32 * 1024

  /** On shutdown, how long to wait for connection threads to notice their closed sockets. */
  private val DrainTimeoutMs = 5_000L

  final case class Bound(host: String, port: Int)

  /** A running server: its address and the accept-loop fiber (join it to serve "forever" — the loop
    * only ends by failing, which must surface rather than leave a listener that never answers).
    */
  final case class Started(bound: Bound, loop: Fiber.Runtime[Throwable, Nothing])

  /** What the server needs from the MCP layer (`StreamableHttpHandler` implements both halves). */
  trait App[-R]:

    /** Headers-only verdict, taken before the body is read. `Some` is the final reply. Pure. */
    def admit(req: HttpRequest): Option[HttpReply]

    /** The full handler, behind its own error boundary; `req.body` is already in memory. */
    def handle(req: HttpRequest): URIO[R, HttpReply]

  /** Set `FASTMCP_HTTP_DEBUG=1` to print per-connection failures (a peer resetting mid-request, a
    * rejected head, an idle timeout) to stderr; they are otherwise swallowed, as a misbehaving
    * client must not be able to affect the server. Scala Native has no logging framework to route
    * them to, so this is the one diagnostic knob.
    */
  private val debug: Boolean =
    Option(java.lang.System.getenv("FASTMCP_HTTP_DEBUG")).exists(v => v.nonEmpty && v != "0")

  private def debugLog(message: => String): Unit =
    if debug then java.lang.System.err.println(s"[fastmcp-http] $message")

  /** Bind `host:port` (port `0` picks a free port — see the returned [[Bound]]) and fork the accept
    * loop into the enclosing `Scope`. Closing the scope closes the listener and every open
    * connection, which ends their threads and interrupts whatever handler fiber each was waiting
    * on.
    */
  def start[R](
      host: String,
      port: Int,
      maxBodyBytes: Int,
      maxConnections: Int,
      trackFiberRoots: Boolean
  )(app: App[R]): ZIO[R & Scope, Throwable, Started] =
    for
      ambient <- ZIO.runtime[R]
      // Every request runs on this runtime. With `trackFiberRoots = false` its fibers — and the
      // daemon fibers they fork, which inherit the flag — are not registered in ZIO's global
      // `Fiber.roots` bag, which costs one `WeakReference` per fiber. See `SocketHttpBackend`.
      runtime =
        if trackFiberRoots then ambient
        else
          Runtime(
            ambient.environment,
            ambient.fiberRefs,
            RuntimeFlags.disable(ambient.runtimeFlags)(RuntimeFlag.FiberRoots)
          )
      executor <- ZIO.blockingExecutor
      listener <- ZIO.acquireRelease(
        ZIO.attemptBlocking {
          val server = new ServerSocket()
          server.setReuseAddress(true)
          server.bind(new InetSocketAddress(host, port), Backlog)
          server.setSoTimeout(AcceptPollMs)
          new Listener[R](server, runtime, executor, app, maxBodyBytes, maxConnections)
        }
      )(l => ZIO.attemptBlocking(l.shutdown()).ignore)
      loop <- ZIO
        .attemptBlockingCancelable[Any, Nothing](listener.acceptLoop())(
          ZIO.attemptBlocking(listener.shutdown()).ignore
        )
        .tapErrorCause(cause => ZIO.succeed(debugLog(s"accept loop failed: ${cause.prettyPrint}")))
        .forkScoped
    yield Started(Bound(host, listener.port), loop)

  /** The listening socket, the connection gate and the set of live connections. */
  private final class Listener[R](
      server: ServerSocket,
      runtime: Runtime[R],
      executor: Executor,
      val app: App[R],
      val maxBodyBytes: Int,
      maxConnections: Int
  ):
    private val closed = new AtomicBoolean(false)
    private val gate = new Semaphore(maxConnections)
    private val live = new ConcurrentHashMap[Connection[R], java.lang.Boolean]()

    def port: Int = server.getLocalPort

    def isClosed: Boolean = closed.get

    /** Accept until shut down. Runs on one blocking thread; never returns normally. */
    @SuppressWarnings(Array("org.wartremover.warts.Var"))
    def acceptLoop(): Nothing =
      while !closed.get do
        if gate.tryAcquire(AcceptPollMs.toLong, TimeUnit.MILLISECONDS) then
          var handedOff = false
          try
            val socket = server.accept()
            val connection = new Connection[R](socket, this)
            live.put(connection, java.lang.Boolean.TRUE)
            try
              // Re-check after registering: a shutdown that ran in between has already swept
              // `live` and would otherwise miss this socket.
              if closed.get then throw new SocketException("listener closed")
              Unsafe.unsafe(implicit u => executor.submitOrThrow(connection))
              handedOff = true
            finally
              if !handedOff then
                live.remove(connection)
                connection.closeSocket()
          catch case _: SocketTimeoutException => ()
          finally if !handedOff then gate.release()
      throw new SocketException("listener closed")

    /** Called by a connection thread as it exits. */
    def release(connection: Connection[R]): Unit =
      live.remove(connection)
      gate.release()

    /** Close the listener and every live connection, then give their threads a moment to finish.
      * Idempotent.
      */
    def shutdown(): Unit =
      if closed.compareAndSet(false, true) then
        try server.close()
        catch case NonFatal(_) => ()
        val connections = live.keySet().iterator()
        while connections.hasNext do
          val connection = connections.next()
          connection.closeSocket()
          connection.wake()
        val deadline = java.lang.System.nanoTime() + DrainTimeoutMs * 1_000_000L
        while !live.isEmpty && java.lang.System.nanoTime() < deadline do Thread.sleep(2)

    def runSync[A](effect: URIO[R, A]): Exit[Nothing, A] =
      Unsafe.unsafe(implicit u => runtime.unsafe.run(effect))

    /** Run `effect` on the calling thread until it completes or first suspends. */
    def runOrFork[A](effect: URIO[R, A]): Either[Fiber.Runtime[Nothing, A], Exit[Nothing, A]] =
      Unsafe.unsafe(implicit u => runtime.unsafe.runOrFork(effect))

  /** One-shot hand-off from a fiber observer to the connection thread that created it. `poke` wakes
    * the waiter without a value (shutdown).
    *
    * Built on `LockSupport.park` / `unpark`, deliberately not on `synchronized` / `wait` /
    * `notify`: with an object monitor the woken thread must re-acquire the monitor before it can
    * run, and on JDK 25 notified connection threads were observed parked in that re-acquire for
    * tens to hundreds of milliseconds on monitors no thread held, which collapsed throughput under
    * sustained load. `unpark` has no monitor to re-enter, and its permit makes a wake-up that
    * arrives before the park a no-op park instead of a lost signal.
    */
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private final class Signal[A]:
    private val waiter: Thread = Thread.currentThread()
    @volatile private var value: Option[A] = None
    @volatile private var poked = false

    def set(a: A): Unit =
      value = Some(a)
      LockSupport.unpark(waiter)

    def poke(): Unit =
      poked = true
      LockSupport.unpark(waiter)

    /** The value, waiting up to `millis` for it; `None` on timeout or when poked. Spurious wake-ups
      * (and a stale permit left by an earlier signal) just park again.
      */
    def await(millis: Long): Option[A] =
      val deadline = java.lang.System.nanoTime() + math.max(1L, millis) * 1_000_000L
      var remaining = deadline - java.lang.System.nanoTime()
      while value.isEmpty && !poked && remaining > 0 do
        LockSupport.parkNanos(this, remaining)
        remaining = deadline - java.lang.System.nanoTime()
      // A poke is consumed by the wait it interrupts, so the caller's next wait parks normally.
      poked = false
      value

  /** Growable response buffer, reused for every reply on a connection. */
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private final class OutBuffer(initial: Int):
    private var bytes = new Array[Byte](initial)
    private var length = 0

    def size: Int = length

    private def ensure(extra: Int): Unit =
      if length + extra > bytes.length then
        bytes = java.util.Arrays.copyOf(bytes, math.max(bytes.length * 2, length + extra))

    /** Append an ASCII / ISO-8859-1 string (status line, header, chunk size). */
    def ascii(s: String): Unit =
      val n = s.length
      ensure(n)
      var i = 0
      while i < n do
        bytes(length + i) = s.charAt(i).toByte
        i += 1
      length += n

    def append(a: Array[Byte]): Unit =
      ensure(a.length)
      java.lang.System.arraycopy(a, 0, bytes, length, a.length)
      length += a.length

    /** Write everything pending in one call and reset; drops a buffer a large reply grew. */
    def flushTo(out: OutputStream): Unit =
      if length > 0 then
        val n = length
        length = 0
        out.write(bytes, 0, n)
        out.flush()
        if bytes.length > 4 * FlushThresholdBytes then bytes = new Array[Byte](initial)

  private val NoBody: IO[Throwable, String] = ZIO.succeed("")

  /** `scope` + the stream's pull, opened in one synchronous step. */
  private def openPull(
      frames: ZStream[Any, Nothing, SseFrame]
  ): UIO[(Scope.Closeable, ZIO[Any, Option[Nothing], Chunk[SseFrame]])] =
    Scope.make.flatMap(scope => scope.extend[Any](frames.toPull).map(pull => (scope, pull)))

  /** Run an SSE reply's finalizers without emitting it: the stream is started already halted. Its
    * `ensuring` hooks are the only thing that interrupts the request's dispatch fiber, shuts its
    * queue and releases the session's GET slot, so a reply that cannot be delivered must still be
    * run this way — dropping it would leave every later GET on the session answering 409.
    */
  private def abandon(frames: ZStream[Any, Nothing, SseFrame]): UIO[Unit] =
    Promise.make[Nothing, Unit].flatMap { halted =>
      halted.succeed(()) *> frames.interruptWhen(halted).runDrain
    }

  /** One accepted socket: the request loop, framing, and response writing, all on one thread. */
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private final class Connection[R](socket: Socket, owner: Listener[R]) extends Runnable:
    private val input = new ConnectionInput(socket.getInputStream)
    private val out: OutputStream = socket.getOutputStream
    private val buf = new OutBuffer(4 * 1024)

    /** The TCP peer address — the default owner key for bearer-task buckets. */
    private val peer: Option[String] = Option(socket.getInetAddress).map(_.getHostAddress)

    /** The signal this thread is currently parked on, for [[wake]]. */
    @volatile private var parkedOn: Option[Signal[?]] = None

    /** Whether the SSE reply in progress is chunked — read by the keepalive writer. */
    private var pingChunked = true

    def closeSocket(): Unit =
      try socket.close()
      catch case NonFatal(_) => ()

    /** Wake the thread if it is waiting on a fiber (shutdown). */
    def wake(): Unit = parkedOn.foreach(_.poke())

    override def run(): Unit =
      try
        socket.setTcpNoDelay(true)
        socket.setSoTimeout(IdleReadTimeoutMs)
        var keepAlive = true
        while keepAlive do keepAlive = serveOne()
      catch
        // A misbehaving peer (reset, idle timeout, a write to a closed socket) affects only its own
        // connection.
        case NonFatal(e) => debugLog(s"connection ended: $e")
      finally
        closeSocket()
        owner.release(this)

    /** Serve one request; `true` when the connection can carry another. An idle timeout while
      * waiting for the next request surfaces as a `SocketTimeoutException`, which closes the
      * connection.
      */
    private def serveOne(): Boolean =
      input.readHead(MaxHeadBytes) match
        case Left(rejection) => reject(rejection)
        case Right(None) => false // clean EOF between requests
        case Right(Some(headBytes)) =>
          Http1.parseHead(headBytes) match
            case Left(rejection) => reject(rejection)
            case Right(head) => dispatch(head)

    private def dispatch(head: Head): Boolean =
      // Structural framing errors first (contradictory or unsupported headers); the size cap is
      // judged after admission so the shared gates keep their order (403 → 415 → 413 → 406).
      head.bodyFraming(Int.MaxValue) match
        case Left(rejection) => reject(rejection)
        case Right(framing) =>
          val request =
            HttpRequest(head.method, head.path, head.header, head.headerJoined, NoBody, peer)
          admit(request) match
            case Some(reply) =>
              // Answered on the head alone. A declared body that was never read leaves unknown
              // bytes on the socket: close after the reply so they are never parsed as a request.
              writeReply(reply, head.version, head.wantsClose || framing != Framing.Empty)
            case None if head.method != "POST" =>
              // GET / DELETE carry no body the handler reads; a declared one is left unread.
              handle(request, head, head.wantsClose || framing != Framing.Empty)
            case None =>
              readBody(head, framing) match
                case Left(rejection) => reject(rejection)
                case Right(body) =>
                  handle(request.copy(body = ZIO.succeed(body)), head, head.wantsClose)

    private def admit(request: HttpRequest): Option[HttpReply] =
      try owner.app.admit(request)
      catch
        case NonFatal(e) =>
          debugLog(s"admission failed: $e")
          Some(internalError)

    private def internalError: HttpReply =
      StreamableHttpHandler.transportError(500, HttpRequestGuards.InternalErrorMessage)

    private def handle(request: HttpRequest, head: Head, close: Boolean): Boolean =
      val (exit, peerGone) = awaitFiber(owner.runOrFork(owner.app.handle(request)), None)
      exit match
        case Exit.Success(reply) if !peerGone => writeReply(reply, head.version, close)
        case Exit.Success(reply) =>
          // The peer vanished while the handler ran, but it still produced a reply: run an SSE
          // reply's finalizers before dropping it.
          reply match
            case sse: HttpReply.Sse =>
              val _ = owner.runSync(abandon(sse.frames))
            case _ => ()
          false
        case Exit.Failure(cause) =>
          // Only an interrupt reaches here (the handler carries its own error boundary).
          if !peerGone then
            debugLog(s"handler failed: ${cause.prettyPrint}")
            val _ = writeReply(internalError, head.version, close = true)
          false

    /** Resolve a `runOrFork`: at once when it ran to completion on this thread, otherwise by
      * parking until the fiber's observer fires. Every [[WatchPollMs]] of quiet the peer is probed
      * (and, with `keepAliveMs`, a ping written once the stream has been quiet that long). When the
      * peer is gone — or the server is shutting down — the fiber is interrupted and its exit still
      * awaited, so the caller can release whatever it produced. Returns the exit and whether the
      * peer is gone.
      */
    private def awaitFiber[A](
        started: Either[Fiber.Runtime[Nothing, A], Exit[Nothing, A]],
        keepAliveMs: Option[Long]
    ): (Exit[Nothing, A], Boolean) =
      started match
        case Right(exit) => (exit, false)
        case Left(fiber) =>
          val signal = new Signal[Exit[Nothing, A]]
          parkedOn = Some(signal)
          try
            Unsafe.unsafe(implicit u => fiber.unsafe.addObserver(exit => signal.set(exit)))
            var lastWrite = java.lang.System.nanoTime()
            var peerGone = false
            var result = signal.await(nextWaitMs(keepAliveMs, lastWrite))
            while result.isEmpty && !peerGone do
              if owner.isClosed || !probe() then peerGone = true
              else
                keepAliveMs.foreach { interval =>
                  if (java.lang.System.nanoTime() - lastWrite) / 1_000_000L >= interval then
                    try
                      appendFrame(SseFrame.Ping, pingChunked)
                      buf.flushTo(out)
                      lastWrite = java.lang.System.nanoTime()
                    catch case _: IOException => peerGone = true
                }
                if !peerGone then result = signal.await(nextWaitMs(keepAliveMs, lastWrite))
            if peerGone then
              Unsafe.unsafe(implicit u => fiber.unsafe.interrupt(Cause.interrupt(FiberId.None)))
              // An interrupted fiber still exits (its finalizers run first); wait for that.
              while result.isEmpty do result = signal.await(WatchPollMs.toLong)
            (result.getOrElse(Exit.interrupt(FiberId.None)), peerGone)
          finally parkedOn = None

    private def nextWaitMs(keepAliveMs: Option[Long], lastWriteNanos: Long): Long =
      keepAliveMs match
        case None => WatchPollMs.toLong
        case Some(interval) =>
          val due = interval - (java.lang.System.nanoTime() - lastWriteNanos) / 1_000_000L
          math.max(1L, math.min(WatchPollMs.toLong, due))

    /** `true` while the peer still holds its end open. Bytes read here stay buffered as a pipelined
      * request; more than a request head's worth of unsolicited bytes is treated as a broken peer
      * rather than buffered without bound.
      */
    private def probe(): Boolean =
      try
        socket.setSoTimeout(ProbeTimeoutMs)
        try
          if input.available > MaxHeadBytes then false
          else input.fill() >= 0
        catch
          case _: SocketTimeoutException => true
          case _: IOException => false
        finally socket.setSoTimeout(IdleReadTimeoutMs)
      catch case _: IOException => false

    private def readBody(head: Head, framing: Framing): Either[Rejection, String] =
      val max = owner.maxBodyBytes
      framing match
        case Framing.Empty => Right("")
        case Framing.Length(n) if n > max =>
          Left(Rejection(413, s"Request body exceeds $max bytes"))
        case Framing.Length(n) =>
          sendContinue(head)
          input.readString(n).toRight(Rejection(400, "Request body ended prematurely"))
        case Framing.Chunked =>
          sendContinue(head)
          input.readChunked(max).map(bytes => new String(bytes, StandardCharsets.UTF_8))

    /** `100 Continue`, which solicits the body, is sent only once the request has been admitted;
      * RFC 9110 lets a server answer on the head alone without ever asking for the body.
      */
    private def sendContinue(head: Head): Unit =
      if head.expectsContinue then
        buf.ascii("HTTP/1.1 100 Continue\r\n\r\n")
        buf.flushTo(out)

    /** Transport-level rejection: the same JSON-RPC error body the MCP layer uses, then close. */
    private def reject(rejection: Rejection): Boolean =
      val _ = writeJson(
        StreamableHttpHandler.transportError(rejection.status, rejection.message),
        close = true
      )
      false

    private def writeReply(reply: HttpReply, version: Version, close: Boolean): Boolean =
      reply match
        case HttpReply.Empty(status, headers) =>
          appendHead(status, headers, close)
          buf.ascii("content-length: 0\r\n\r\n")
          buf.flushTo(out)
          !close
        case json: HttpReply.Json => writeJson(json, close)
        case sse: HttpReply.Sse => streamSse(sse, version, close)

    private def writeJson(json: HttpReply.Json, close: Boolean): Boolean =
      val body = json.body.getBytes(StandardCharsets.UTF_8)
      appendHead(json.status, json.headers, close)
      buf.ascii("content-type: application/json\r\ncontent-length: ")
      buf.ascii(Integer.toString(body.length))
      buf.ascii("\r\n\r\n")
      buf.append(body)
      buf.flushTo(out)
      !close

    /** Stream an SSE reply. HTTP/1.1 clients get chunked transfer-encoding and keep the connection;
      * HTTP/1.0 clients get a close-delimited body. Returns whether the connection is still usable
      * for another request.
      *
      * Frames are pulled with `runOrFork`: a pull that completes on this thread just appends to the
      * pending buffer, and the buffer is flushed only when a pull has to wait (or it grows large).
      * A request whose reply is ready by the time it is pulled therefore leaves as a single write.
      */
    private def streamSse(sse: HttpReply.Sse, version: Version, close: Boolean): Boolean =
      val chunked = version == Version.Http11
      val closeAfter = close || !chunked
      val keepAliveMs = sse.keepAlive.map(d => math.max(1L, d.toMillis))
      pingChunked = chunked
      owner.runSync(openPull(sse.frames)) match
        case Exit.Failure(cause) =>
          debugLog(s"could not open the reply stream: ${cause.prettyPrint}")
          val _ = writeReply(internalError, version, close = true)
          false
        case Exit.Success((scope, pull)) =>
          var finished = false
          var broken = false
          try
            appendHead(200, sse.headers, closeAfter)
            buf.ascii("content-type: text/event-stream\r\ncache-control: no-cache\r\n")
            buf.ascii(if chunked then "transfer-encoding: chunked\r\n\r\n" else "\r\n")
            while !finished && !broken do
              val started = owner.runOrFork(pull.either)
              // About to wait: everything gathered so far (at least the head) goes out now.
              if started.isLeft then
                try buf.flushTo(out)
                catch
                  case e: IOException =>
                    // The pull is already running: stop it before the scope closes under it.
                    started.left.foreach { fiber =>
                      val _ = owner.runSync(fiber.interrupt)
                    }
                    throw e
              val (exit, peerGone) = awaitFiber(started, keepAliveMs)
              if peerGone then broken = true
              else
                exit match
                  case Exit.Success(Right(frames)) =>
                    frames.foreach(frame => appendFrame(frame, chunked))
                    if buf.size >= FlushThresholdBytes then buf.flushTo(out)
                  case Exit.Success(Left(_)) => finished = true // end of stream
                  case Exit.Failure(_) => broken = true
            if finished then
              if chunked then buf.ascii("0\r\n\r\n")
              buf.flushTo(out)
          catch
            case e: IOException =>
              // The peer went away mid-write; the finalizers below still run.
              debugLog(s"SSE write failed: $e")
              broken = true
          finally
            // Runs the stream's finalizers (dispatch interrupt, queue shutdown, GET-slot release)
            // on every path: normal end, client disconnect, write failure, shutdown.
            val _ = owner.runSync(scope.close(Exit.unit))
          finished && !broken && !closeAfter

    private def appendHead(status: Int, headers: List[(String, String)], close: Boolean): Unit =
      buf.ascii(Http1.statusLine(status))
      var rest = headers
      while rest.nonEmpty do
        val (name, value) = rest.head
        buf.ascii(name)
        buf.ascii(": ")
        buf.ascii(value)
        buf.ascii("\r\n")
        rest = rest.tail
      buf.ascii(if close then "connection: close\r\n" else "connection: keep-alive\r\n")

    private def appendFrame(frame: SseFrame, chunked: Boolean): Unit =
      val bytes = frame.encode.getBytes(StandardCharsets.UTF_8)
      if chunked then
        buf.ascii(Integer.toHexString(bytes.length))
        buf.ascii("\r\n")
        buf.append(bytes)
        buf.ascii("\r\n")
      else buf.append(bytes)

  /** Single-reader buffered view of a socket's input: byte-exact reads of request heads, fixed
    * lengths, and chunked bodies. Every method blocks; only the connection's own thread calls in.
    */
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private final class ConnectionInput(in: InputStream):
    // Deliberately below 8 KiB: Scala Native allocates objects of 8 KiB and up through its
    // large-object allocator, whose slow path can hand a racing thread a null pointer (0.5.12:
    // `LargeAllocator_Alloc` retries once after growing the heap by exactly one object). A burst of
    // new connections must not put one such allocation per connection on that path. The buffer
    // still grows on demand for larger heads and bodies.
    private val InitialBufferBytes = 4 * 1024
    private var buf: Array[Byte] = new Array[Byte](InitialBufferBytes)
    private var start = 0
    private var end = 0

    /** Bytes read from the socket but not yet consumed. */
    def available: Int = end - start

    /** One blocking read appended to the buffer; the count read, or -1 at EOF. Compacts or grows
      * the buffer first, so byte offsets *relative to `start`* stay valid across calls.
      */
    def fill(): Int =
      if start == end then
        start = 0
        end = 0
        // Drained: drop a buffer that a large body grew, so one large request on a keep-alive
        // connection does not pin its size for the connection's whole lifetime.
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

    /** Wait until `n` bytes are buffered; `false` if the peer closed first. */
    private def require(n: Int): Boolean =
      var eof = false
      while available < n && !eof do if fill() < 0 then eof = true
      available >= n

    /** Exactly `n` bytes, or `None` if the peer closed first. */
    def readExactly(n: Int): Option[Array[Byte]] =
      if !require(n) then None
      else
        val bytes = java.util.Arrays.copyOfRange(buf, start, start + n)
        start += n
        Some(bytes)

    /** Exactly `n` bytes decoded as UTF-8 straight out of the buffer, or `None` if the peer closed
      * first.
      */
    def readString(n: Int): Option[String] =
      if !require(n) then None
      else
        val text = new String(buf, start, n, StandardCharsets.UTF_8)
        start += n
        Some(text)

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
