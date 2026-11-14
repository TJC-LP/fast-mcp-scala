package com.tjclp.fastmcp
package server.transport

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.stream.*

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.core.TaskSupport
import com.tjclp.fastmcp.jsonrpc.McpError
import com.tjclp.fastmcp.server.TaskSettings
import com.tjclp.fastmcp.server.router.{McpRouter, Session}

/** The stdio lifecycle contract of [[StdioLoop]] (GH #113), on the JVM and inside the Scala Native
  * test binary: what stdin EOF owes the frames already read, the final frame without a newline,
  * `initialize` ordering, and interruption. The Bun twin is `JsStdioLifecycleTest`.
  *
  * Every ordering here is fixed by a promise, never by a sleep: a handler says when it has started,
  * and a stream that must stay open ends on a promise. The only clocks are the drain timeout under
  * test and the hard timeout that turns a hang into a failure.
  */
class StdioLifecycleContractTest extends AnyFunSuite with Matchers:

  case class NoArgs(unused: Boolean = false)

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u =>
      Runtime.default.unsafe
        .run(effect.timeoutFail(new RuntimeException("stdio lifecycle test hung"))(60.seconds))
        .getOrThrowFiberFailure()
    )

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{"roots":{}},"clientInfo":{"name":"t","version":"1.0"}}}"""
  private val initializedFrame = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
  private val handshake = List(initFrame, initializedFrame)

  private def call(id: Int, tool: String, extra: String = ""): String =
    s"""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$tool","arguments":{}$extra}}"""

  private def listTools(id: Int): String = s"""{"jsonrpc":"2.0","id":$id,"method":"tools/list"}"""

  /** Long enough that a test relying on it would hit the hard timeout instead of passing. */
  private val NoDrainLimit: Duration = 1.hour

  private def tool(name: String)(body: Task[String]): McpTool[NoArgs, String] =
    McpTool[NoArgs, String](name = name)(_ => body)

  private def echo: McpTool[NoArgs, String] = tool("echo")(ZIO.succeed("ok"))

  private def serverWith(
      settings: McpServerSettings,
      tools: McpTool[NoArgs, String]*
  ): Task[McpRouter[Any]] =
    val server = McpServer.typed[Any]("StdioLifecycle", "0.1.0", settings)
    ZIO.foreachDiscard(tools)(server.tool(_)) *> server.buildRouter

  /** `frames`, then the stream stays open until `until` completes: EOF at a chosen moment. */
  private def framesThen(frames: List[String], until: UIO[Any]): ZStream[Any, Nothing, String] =
    ZStream.fromIterable(frames) ++ ZStream.fromZIO(until).drain

  private def replyTo(lines: Chunk[String], id: Int): Option[String] =
    lines.find(_.contains(s""""id":$id,"""))

  /** Run the loop over `in` to its end and return what it wrote, in order. */
  private def runLoop(
      router: McpRouter[Any],
      in: ZStream[Any, Throwable, String],
      drainTimeout: Duration = NoDrainLimit
  ): Task[Chunk[String]] =
    for
      session <- Session.makeDrained("stdio-lifecycle", Session.DefaultOutboundCapacity)
      out <- Queue.unbounded[String]
      _ <- StdioLoop.run(router, session, in, line => out.offer(line).unit, drainTimeout)
      written <- out.takeAll
    yield written

  // --- 1. EOF drains ----------------------------------------------------------------------------

  test("EOF: every frame already read is answered before the loop returns") {
    val calls = (2 to 51).toList
    val written = runUnsafe(
      for
        router <- serverWith(McpServerSettings(), tool("echo")(ZIO.yieldNow.as("ok")))
        written <- runLoop(router, ZStream.fromIterable(handshake ++ calls.map(call(_, "echo"))))
      yield written
    )
    (1 :: calls).filter(replyTo(written, _).isEmpty) shouldBe empty
    written.size shouldBe calls.size + 1
    // The writer's close marker is internal and never reaches the wire.
    written.filter(_.contains("$fastmcp")) shouldBe empty
  }

  test("EOF: the loop waits for a handler that is still running, then writes its reply") {
    val (pendingAtEof, written) = runUnsafe(
      for
        started <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        eof <- Promise.make[Nothing, Unit]
        router <- serverWith(
          McpServerSettings(),
          tool("hold")(started.succeed(()) *> release.await.as("held"))
        )
        session <- Session.makeDrained("stdio-lifecycle", Session.DefaultOutboundCapacity)
        out <- Queue.unbounded[String]
        in = ZStream.fromIterable(handshake :+ call(2, "hold")).ensuring(eof.succeed(()))
        loop <- StdioLoop.run(router, session, in, line => out.offer(line).unit, NoDrainLimit).fork
        _ <- started.await *> eof.await
        pending <- loop.poll // stdin is at EOF and the handler is parked: the loop must not be done
        _ <- release.succeed(())
        _ <- loop.join
        written <- out.takeAll
      yield (pending, written)
    )
    pendingAtEof shouldBe None
    replyTo(written, 2).getOrElse(fail(s"no reply to the held call in: $written")) should include(
      "held"
    )
  }

  test("EOF: a handler that never finishes is interrupted after the drain timeout") {
    val written = runUnsafe(
      for
        started <- Promise.make[Nothing, Unit]
        interrupted <- Promise.make[Nothing, Unit]
        router <- serverWith(
          McpServerSettings(),
          tool("hang")((started.succeed(()) *> ZIO.never).onInterrupt(interrupted.succeed(())))
        )
        // EOF only once the handler runs: a fiber interrupted before it starts would never
        // install the `onInterrupt` this test waits for.
        in = framesThen(handshake :+ call(2, "hang"), started.await)
        written <- runLoop(router, in, drainTimeout = 100.millis)
        _ <- interrupted.await
      yield written
    )
    replyTo(written, 1) should not be empty
    replyTo(written, 2) shouldBe empty
  }

  test("EOF: a background task body does not hold the exit") {
    val written = runUnsafe(
      for
        bodyStarted <- Promise.make[Nothing, Unit]
        router <- serverWith(
          McpServerSettings(tasks = TaskSettings(enabled = true)),
          tool("forever")(bodyStarted.succeed(()) *> ZIO.never)
            .withTaskSupport(TaskSupport.Optional)
        )
        // The task body is running when stdin ends, and the drain has no limit: the loop returns
        // only if it does not count the body as a reply it owes.
        in = framesThen(
          handshake :+ call(2, "forever", ""","task":{"ttl":60000}"""),
          bodyStarted.await
        )
        written <- runLoop(router, in)
      yield written
    )
    replyTo(written, 2).getOrElse(fail(s"no task handle in: $written")) should include("taskId")
  }

  test("EOF: a handler waiting for the client's answer is failed at once, not at the timeout") {
    val written = runUnsafe(
      for
        router <- {
          val server = McpServer.typed[Any]("StdioLifecycle", "0.1.0", McpServerSettings())
          server.tool(
            McpTool[NoArgs, String](name = "roots").contextual { (_, ctx) =>
              ctx match
                case Some(c) => c.listRoots().map(_.roots.size.toString)
                case None => ZIO.fail(McpError.internalError("no context"))
            }
          ) *> server.buildRouter
        }
        session <- Session.makeDrained("stdio-lifecycle", Session.DefaultOutboundCapacity)
        seen <- Ref.make(Chunk.empty[String])
        asked <- Promise.make[Nothing, Unit]
        emit = (line: String) =>
          seen.update(_ :+ line) *> ZIO.when(line.contains("roots/list"))(asked.succeed(())).unit
        // stdin ends after the server has asked for the roots and before anyone answers.
        in = framesThen(handshake :+ call(2, "roots"), asked.await)
        _ <- StdioLoop.run(router, session, in, emit, NoDrainLimit)
        written <- seen.get
      yield written
    )
    val reply = replyTo(written, 2).getOrElse(fail(s"no reply to the roots call in: $written"))
    reply should include("the client closed its input stream")
  }

  private def rootsServer: Task[McpRouter[Any]] =
    val server = McpServer.typed[Any]("StdioLifecycle", "0.1.0", McpServerSettings())
    server.tool(
      McpTool[NoArgs, String](name = "roots").contextual { (_, ctx) =>
        ctx match
          case Some(c) => c.listRoots().map(_.roots.size.toString)
          case None => ZIO.fail(McpError.internalError("no context"))
      }
    ) *> server.buildRouter

  test("EOF: the client's answer written just before it closes stdin reaches the handler") {
    val replies = runUnsafe(
      ZIO.foreach((1 to 25).toList) { _ =>
        for
          router <- rootsServer
          session <- Session.makeDrained("stdio-lifecycle", Session.DefaultOutboundCapacity)
          seen <- Ref.make(Chunk.empty[String])
          asked <- Promise.make[Nothing, String]
          emit = (line: String) =>
            seen.update(_ :+ line) *> ZIO.when(line.contains("roots/list"))(asked.succeed(line)).unit
          // The answer is the last frame: stdin ends right behind it.
          answer = ZStream.fromZIO(asked.await.map { request =>
            val id = request.split("\"id\":")(1).takeWhile(c => c != ',' && c != '}')
            s"""{"jsonrpc":"2.0","id":$id,"result":{"roots":[{"uri":"file:///a"},{"uri":"file:///b"}]}}"""
          })
          in = ZStream.fromIterable(handshake :+ call(2, "roots")) ++ answer
          _ <- StdioLoop.run(router, session, in, emit, NoDrainLimit)
          written <- seen.get
        yield replyTo(written, 2).getOrElse(s"no reply in: $written")
      }
    )
    // Every run got the client's answer: the tool reports the two roots it was sent.
    replies.filterNot(_.contains(""""text":"2"""")) shouldBe empty
  }

  test("EOF: an open subscriptions/listen does not hold the drain") {
    val listen =
      """{"jsonrpc":"2.0","id":7,"method":"subscriptions/listen","params":{"notifications":{"toolsListChanged":true},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}"""
    val written = runUnsafe(
      for
        router <- serverWith(McpServerSettings(), echo)
        session <- Session.makeDrained("stdio-lifecycle", Session.DefaultOutboundCapacity)
        seen <- Ref.make(Chunk.empty[String])
        acknowledged <- Promise.make[Nothing, Unit]
        emit = (line: String) =>
          seen.update(_ :+ line) *>
            ZIO.when(line.contains("subscriptions/acknowledged"))(acknowledged.succeed(())).unit
        // No drain limit: a listen handler that held the drain would hit the hard test timeout.
        _ <- StdioLoop.run(router, session, framesThen(List(listen), acknowledged.await), emit, NoDrainLimit)
        written <- seen.get
      yield written
    )
    written.count(_.contains("subscriptions/acknowledged")) shouldBe 1
    replyTo(written, 7) shouldBe None
  }

  test("a server-to-client request issued after stdin closed fails and is not written") {
    val (failure, queued) = runUnsafe(
      for
        session <- Session.makeDrained("stdio-lifecycle", Session.DefaultOutboundCapacity)
        _ <- session.closeInbound
        failure <- session.sendRequest("roots/list", None, 30.seconds).flip.mapError(_ =>
          new RuntimeException("the request was answered")
        )
        queued <- session.outbound.size
      yield (failure, queued)
    )
    failure.message should include("the client closed its input stream")
    queued shouldBe 0
  }

  // --- 2. the final frame without a newline ----------------------------------------------------

  test("a final frame without a newline is dispatched and answered") {
    val written = runUnsafe(
      for
        router <- serverWith(McpServerSettings(), echo)
        // Chunk boundaries inside frames, and no `\n` after the last one.
        bytes = (handshake.mkString("", "\n", "\n") + listTools(2)).grouped(23).toList
        in = ZStream
          .fromIterable(bytes)
          .via(BoundedLines.pipeline(router.limits.maxFrameChars))
          .filter(MessageLoop.shouldDispatchStdioFrame(_, router.limits))
        written <- runLoop(router, in)
      yield written
    )
    replyTo(written, 2).getOrElse(fail(s"no reply to the unterminated frame in: $written")) should
      include(""""tools":""")
  }

  // --- 4. initialize ordering -------------------------------------------------------------------

  test("a pipelined handshake is never answered 'not initialized'") {
    val requests = (2 to 6).toList
    val runs = runUnsafe(
      for
        router <- serverWith(McpServerSettings(), echo)
        runs <- ZIO.foreach(1 to 40)(_ =>
          runLoop(router, ZStream.fromIterable(handshake ++ requests.map(listTools)))
        )
      yield runs
    )
    runs.foreach { written =>
      written.filter(_.contains("-32600")) shouldBe empty
      // `initialize` is finished, reply included, before the next frame is even parsed.
      written.headOption.getOrElse(fail("nothing was written")) should include(""""id":1,""")
      requests.filterNot(replyTo(written, _).exists(_.contains(""""tools":"""))) shouldBe empty
    }
  }

  test("a 2026-07-28 request needs no handshake, and a modern 'initialize' is refused in line") {
    val meta =
      """"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}"""
    val modernList = s"""{"jsonrpc":"2.0","id":7,"method":"tools/list","params":{$meta}}"""
    val modernInit = s"""{"jsonrpc":"2.0","id":8,"method":"initialize","params":{$meta}}"""
    val written = runUnsafe(
      serverWith(McpServerSettings(), echo)
        .flatMap(runLoop(_, ZStream(modernList, modernInit, modernList)))
    )
    written.filter(_.contains("-32600")) shouldBe empty
    written.count(_.contains(""""tools":""")) shouldBe 2
    replyTo(written, 8).getOrElse(fail(s"no reply to the modern initialize in: $written")) should
      include("-32601")
  }

  // --- 5. wording -------------------------------------------------------------------------------

  test("the 'not initialized' error names the handshake and the 2026-07-28 _meta route") {
    val written = runUnsafe(serverWith(McpServerSettings()).flatMap(runLoop(_, ZStream(listTools(3)))))
    val reply = replyTo(written, 3).getOrElse(fail(s"no reply in: $written"))
    reply should include("-32600")
    reply should include("send initialize before 'tools/list'")
    reply should include("""_meta[\"io.modelcontextprotocol/protocolVersion\"]""")
    reply should include("2026-07-28")
  }

  // --- 3. interruption --------------------------------------------------------------------------

  test("interruption ends the loop at once and interrupts the handlers in flight") {
    val exit = runUnsafe(
      for
        started <- Promise.make[Nothing, Unit]
        interrupted <- Promise.make[Nothing, Unit]
        router <- serverWith(
          McpServerSettings(),
          tool("hang")((started.succeed(()) *> ZIO.never).onInterrupt(interrupted.succeed(())))
        )
        session <- Session.makeDrained("stdio-lifecycle", Session.DefaultOutboundCapacity)
        open <- Queue.unbounded[String] // never closed: stdin stays open, as under SIGTERM
        _ <- open.offerAll(handshake :+ call(2, "hang"))
        // No drain limit: an interrupted loop that waited for its handlers would hang here.
        loop <- StdioLoop
          .run(router, session, ZStream.fromQueue(open), _ => ZIO.unit, NoDrainLimit)
          .fork
        _ <- started.await
        exit <- loop.interrupt
        _ <- interrupted.await
      yield exit
    )
    exit.isInterrupted shouldBe true
  }

  test("a negative drain timeout is refused when the settings are built") {
    val error = intercept[IllegalArgumentException] {
      McpServerSettings(stdioDrainTimeout = java.time.Duration.ofSeconds(-1))
    }
    error.getMessage should include("stdioDrainTimeout")
    McpServerSettings().stdioDrainTimeout shouldBe java.time.Duration.ofSeconds(5)
  }
