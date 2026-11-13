package com.tjclp.fastmcp
package server.transport

import zio.*
import zio.stream.*

import com.tjclp.fastmcp.server.McpServerSettings
import com.tjclp.fastmcp.server.router.{McpRouter, Session}

/** The platform-neutral stdio serving loop, shared by every backend whose stdin can be presented as
  * a `ZStream` of NDJSON lines and whose stdout is a blocking `System.out` (JVM and Scala Native
  * today; the JS backend drives Node's callback IO directly and implements the same contract in
  * `JsTransportBackend.wireStdin`).
  *
  * [[MessageLoop]] owns wire↔dispatch; this owns the *lifecycle* around it: one writer owning
  * stdout, the outbound drainer fiber, per-frame forked dispatch, and what happens when stdin ends.
  *
  * The lifecycle contract, identical on every platform:
  *   - Every frame read is answered. When stdin reaches EOF, each frame already read is dispatched
  *     and its reply written before the loop returns. The wait is bounded by `drainTimeout`
  *     (`McpServerSettings.stdioDrainTimeout`): a handler still running then is interrupted. Task
  *     bodies (the Tasks extension) run outside the dispatch that created them and are not waited
  *     for.
  *   - A final line without a newline is a frame ([[BoundedLines]] flushes it at EOF).
  *   - `initialize` is answered before the next frame is read, so a pipelined client never races
  *     its own handshake. Every other frame is dispatched in its own fiber.
  *   - Interruption (SIGTERM, a closed scope) ends the loop at once: in-flight handlers are
  *     interrupted, and nothing is waited for beyond their finalizers.
  */
private[fastmcp] object StdioLoop:

  /** How long the loop waits for handlers it interrupted to run their finalizers, and for the
    * stdout writer to finish what is queued, before it returns anyway. A handler stuck in an
    * uninterruptible region, or a stdout nobody reads, must not hold the process.
    */
  private[fastmcp] val SettleTimeout: Duration = 1.second

  /** Serve stdio end to end: mint the session, serialize stdout behind a single writer, and run
    * [[run]]. The caller supplies only the platform's line stream, which is the sole thing that
    * differs between the JVM and Scala Native backends.
    */
  def serve[R](
      router: McpRouter[R],
      inLines: ZStream[Any, Throwable, String]
  ): ZIO[R, Throwable, Unit] =
    serve(router, inLines, McpServerSettings.DefaultStdioDrainTimeout)

  def serve[R](
      router: McpRouter[R],
      inLines: ZStream[Any, Throwable, String],
      drainTimeout: Duration
  ): ZIO[R, Throwable, Unit] =
    for
      session <- Session.makeDrained("stdio", router.limits.maxOutboundMessages)
      // One writer owns stdout; both replies and server-pushed outbound go through it so lines
      // never interleave.
      outLock <- Semaphore.make(1)
      emit = (line: String) => outLock.withPermit(writeLine(line))
      _ <- run(router, session, inLines, emit, drainTimeout)
    yield ()

  /** [[run]] with the default drain timeout. */
  def run[R](
      router: McpRouter[R],
      session: Session,
      inLines: ZStream[Any, Throwable, String],
      emit: String => Task[Unit]
  ): ZIO[R, Throwable, Unit] =
    run(router, session, inLines, emit, McpServerSettings.DefaultStdioDrainTimeout)

  /** The stdio dispatch loop, separate from [[serve]] so it can be driven over in-memory streams in
    * tests (no real `System.in`/`System.out`).
    *
    * Spawns the outbound drainer (server→client pushes; the channel is bounded and, on the session
    * [[serve]] builds, back-pressured — an emitter waits for this writer rather than lose a
    * message) and consumes each inbound line, dispatching **each frame in its own fiber** so the
    * read loop keeps consuming while a handler is blocked — e.g. a tool awaiting a server→client
    * roots/list or sampling response, whose reply is itself a *later* inbound frame. Sequential
    * dispatch deadlocks such handlers (and a `notifications/ cancelled` arriving mid-request).
    * `emit` serializes writes, so forked replies never interleave. The one frame dispatched in line
    * is `initialize` ([[MessageLoop.isOrderedStdioFrame]]); it cannot wait for a later frame.
    *
    * Each frame is parsed on the reading fiber (that is how `initialize` is recognised) and
    * dispatched from the parsed message.
    */
  def run[R](
      router: McpRouter[R],
      session: Session,
      inLines: ZStream[Any, Throwable, String],
      emit: String => Task[Unit],
      drainTimeout: Duration
  ): ZIO[R, Throwable, Unit] =
    ZIO.uninterruptibleMask { restore =>
      for
        // Writes what handlers and tasks push outside a reply until it meets the close sentinel,
        // which `settle` offers after the last reply: everything queued before it is on the wire.
        drainer <- restore(
          session.outbound.take
            .tap(msg =>
              ZIO.unless(MessageLoop.isCloseSentinel(msg))(emit(MessageLoop.encodeOutbound(msg)))
            )
            .repeatUntil(MessageLoop.isCloseSentinel)
        ).forkDaemon
        inFlight <- Ref.make(InFlight.empty)
        read <- restore(inLines.runForeach(accept(router, session, emit, inFlight, _))).exit
        _ <- settle(restore, session, inFlight, drainer, drainTimeout, read.isInterrupted)
        _ <- read // an `Exit` is its own effect: the read's failure or interruption, re-raised
      yield ()
    }

  /** The dispatch fibers that have not finished. A frame is entered as `None` before its fiber is
    * forked and removed by the fiber itself when it ends, so an entry can never outlive its fiber
    * however the fork and the fiber's last step interleave.
    */
  private final case class InFlight(
      next: Long,
      fibers: Map[Long, Option[Fiber.Runtime[Throwable, Unit]]]
  ):
    def reserve: (Long, InFlight) = (next, InFlight(next + 1, fibers.updated(next, None)))

    def started(id: Long, fiber: Fiber.Runtime[Throwable, Unit]): InFlight =
      if fibers.contains(id) then copy(fibers = fibers.updated(id, Some(fiber))) else this

    def finished(id: Long): InFlight = copy(fibers = fibers - id)

    def running: Iterable[Fiber.Runtime[Throwable, Unit]] = fibers.values.flatten

  private object InFlight:
    val empty: InFlight = InFlight(0L, Map.empty)

  /** Parse one line and run it: `initialize` in line, everything else in a tracked fiber. */
  private def accept[R](
      router: McpRouter[R],
      session: Session,
      emit: String => Task[Unit],
      inFlight: Ref[InFlight],
      line: String
  ): URIO[R, Unit] =
    ZIO.suspendSucceed {
      val parsed = MessageLoop.parseFrame(line, router.limits)
      val answer = MessageLoop.dispatchParsed(router, session, parsed).flatMap {
        case Some(reply) => emit(reply)
        case None => ZIO.unit
      }
      // A failed write is not the reader's failure: a forked dispatch never took the loop down
      // with it either.
      if MessageLoop.isOrderedStdioFrame(parsed) then answer.ignore
      else
        ZIO.uninterruptibleMask { restore =>
          for
            id <- inFlight.modify(_.reserve)
            fiber <- restore(answer).ensuring(inFlight.update(_.finished(id))).forkDaemon
            _ <- inFlight.update(_.started(id, fiber))
          yield ()
        }
    }

  /** What the loop does once it has stopped reading. At EOF (or a failed read) the frames already
    * read are owed their replies: wait for them up to `drainTimeout`, then flush the outbound
    * channel behind them. Interrupted — by a signal, or while waiting — nothing is owed: the
    * in-flight handlers are interrupted. Either way the drainer and the outbound channel are closed
    * before the loop returns. Runs uninterruptibly except where it waits.
    */
  private def settle(
      restore: ZIO.InterruptibilityRestorer,
      session: Session,
      inFlight: Ref[InFlight],
      drainer: Fiber.Runtime[Throwable, Any],
      drainTimeout: Duration,
      interrupted: Boolean
  ): UIO[Unit] =
    val awaitReplies = inFlight.get.flatMap(state => ZIO.foreachDiscard(state.running)(_.await))
    val drained: UIO[Boolean] =
      if interrupted then ZIO.succeed(false)
      else
        // No frame can arrive any more, so a handler waiting for the client's answer to a
        // server→client request is failed now rather than at the end of the wait.
        session.closeInbound *>
          restore(awaitReplies.timeout(drainTimeout)).exit.map(_.foldExit(_ => false, _.isDefined))
    val flushOutbound =
      (session.outbound.offer(MessageLoop.CloseSentinel) *> drainer.await).interruptible
        .timeout(SettleTimeout)
        .exit
    val interruptHandlers = inFlight.get.flatMap { state =>
      ZIO.foreachDiscard(state.running)(_.interruptFork) *>
        ZIO.foreachDiscard(state.running)(_.await).interruptible.timeout(SettleTimeout).exit
    }
    drained.flatMap(done => if done then flushOutbound else interruptHandlers) *>
      // Nothing writes server-pushed messages from here on, so the session stops buffering them
      // and releases any emitter still waiting for the writer.
      drainer.interruptFork *> session.closeOutbound

  /** Blocking line write to `System.out`. Identical on the JVM and Scala Native (SN's javalib
    * provides the same `System.out` surface).
    *
    * Frame and newline go out in ONE `PrintStream` call. `PrintStream` serialises each call on its
    * own lock, so a foreign `System.out` writer — a stray `println` in user code — can land between
    * frames but never inside one. Three separate calls (`print(line)`, `print('\n')`, `flush()`)
    * left a window in which such a line spliced into the reply (`{...}noise` + an empty line) and
    * the client never saw that reply (TJC-2338). The single-writer semaphore in [[serve]] orders
    * OUR writes; this guards against writes we don't control.
    */
  private[fastmcp] def writeLine(line: String): Task[Unit] =
    ZIO.attempt {
      val out = java.lang.System.out
      out.print(line + "\n")
      out.flush()
    }
