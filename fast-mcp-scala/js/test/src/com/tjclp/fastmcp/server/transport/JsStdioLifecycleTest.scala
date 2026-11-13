package com.tjclp.fastmcp.server.transport

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js

import org.scalatest.Assertion
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.*

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.core.TaskSupport
import com.tjclp.fastmcp.facades.node.NodeReadableStream
import com.tjclp.fastmcp.interop.ZioJsPromise
import com.tjclp.fastmcp.jsonrpc.McpError
import com.tjclp.fastmcp.server.TaskSettings
import com.tjclp.fastmcp.server.router.{McpRouter, Session}

/** The stdio lifecycle contract on Bun (GH #113), driven through the production stdin callbacks
  * (`JsTransportBackend.wireStdin`): what `end` owes the frames already read, the final frame
  * without a newline, `initialize` ordering. The JVM / Scala Native twin is
  * `StdioLifecycleContractTest`.
  *
  * Orderings are fixed by promises: a handler says when it has started, and `end` is delivered at a
  * chosen moment. The only clocks are the drain timeout under test and the hard timeout that turns
  * a hang into a failure.
  */
class JsStdioLifecycleTest extends AsyncFlatSpec with Matchers:
  override implicit val executionContext: ExecutionContext = ExecutionContext.global

  case class NoArgs(unused: Boolean = false)

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{"roots":{}},"clientInfo":{"name":"t","version":"1.0"}}}"""
  private val initializedFrame = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
  private val handshake = initFrame + "\n" + initializedFrame + "\n"

  private def call(id: Int, tool: String, extra: String = ""): String =
    s"""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$tool","arguments":{}$extra}}"""

  private def listTools(id: Int): String = s"""{"jsonrpc":"2.0","id":$id,"method":"tools/list"}"""

  /** Long enough that a test relying on it would hit the hard timeout instead of passing. */
  private val NoDrainLimit: Duration = 1.hour

  private def tool(name: String)(body: Task[String]): McpTool[NoArgs, String] =
    McpTool[NoArgs, String](name = name)(_ => body)

  private def echo: McpTool[NoArgs, String] = tool("echo")(ZIO.succeed("ok"))

  private def routerWith(
      settings: McpServerSettings,
      tools: McpTool[NoArgs, String]*
  ): Task[McpRouter[Any]] =
    val server = McpServer.typed[Any]("JsStdioLifecycle", "0.1.0", settings)
    ZIO.foreachDiscard(tools)(server.tool(_)) *> server.buildRouter

  /** The stdin callbacks wired to an in-memory stdout. */
  private final class Wired(
      callbacks: js.Dictionary[js.Function1[js.Any, Unit]],
      val written: Ref[Chunk[String]],
      val finished: Promise[Throwable, Unit],
      val interruptInFlight: UIO[Unit],
      val session: Session
  ):
    /** The next message a handler pushed outside a reply (`serveStdio`'s writer is not wired). */
    def pushed: UIO[String] = session.outbound.take.map(MessageLoop.encodeOutbound)

    def data(text: String): UIO[Unit] = ZIO.succeed(callbacks("data")(text))
    def end: UIO[Unit] = ZIO.succeed(callbacks("end")(js.undefined))

    /** Deliver `end` and wait for the server to be done with stdin; what it wrote, in order. */
    def endAndWait: Task[Chunk[String]] = end *> finished.await *> written.get

  private def wire(router: McpRouter[Any], drainTimeout: Duration = NoDrainLimit): UIO[Wired] =
    for
      session <- Session.makeDrained("stdio-lifecycle", Session.DefaultOutboundCapacity)
      written <- Ref.make(Chunk.empty[String])
      finished <- Promise.make[Throwable, Unit]
      wired <- ZIO.succeed {
        val callbacks = js.Dictionary.empty[js.Function1[js.Any, Unit]]
        val stdin = js.Dynamic
          .literal(
            setEncoding = js.Any.fromFunction1((_: String) => ()),
            on = js.Any.fromFunction2((event: String, callback: js.Function1[js.Any, Unit]) =>
              callbacks(event) = callback
              ()
            )
          )
          .asInstanceOf[NodeReadableStream]
        val interruptInFlight = JsTransportBackend.wireStdin(
          router,
          session,
          Runtime.default,
          // What `serveStdio` resumes with when stdin ends.
          settle =>
            val _ = Unsafe.unsafe(implicit unsafe =>
              Runtime.default.unsafe.fork(settle.intoPromise(finished))
            )
          ,
          stdin,
          line => written.update(_ :+ line),
          drainTimeout
        )
        new Wired(callbacks, written, finished, interruptInFlight, session)
      }
    yield wired

  private def replyTo(lines: Chunk[String], id: Int): Option[String] =
    lines.find(_.contains(s""""id":$id,"""))

  private def run[A](effect: Task[A])(check: A => Assertion): Future[Assertion] =
    ZioJsPromise
      .zioToPromise(
        effect.timeoutFail(new RuntimeException("stdio lifecycle test hung"))(60.seconds)
      )
      .toFuture
      .map(check)

  // --- 1. `end` drains ---------------------------------------------------------------------------

  "Bun stdio at end of stdin" should "answer every frame already read before it is done" in {
    val calls = (2 to 51).toList
    run(
      for
        router <- routerWith(McpServerSettings(), tool("echo")(ZIO.yieldNow.as("ok")))
        wired <- wire(router)
        _ <- wired.data(handshake + calls.map(call(_, "echo") + "\n").mkString)
        written <- wired.endAndWait
      yield written
    ) { written =>
      (1 :: calls).filter(replyTo(written, _).isEmpty) shouldBe empty
      written.size shouldBe calls.size + 1
    }
  }

  it should "wait for a handler that is still running, then write its reply" in {
    run(
      for
        started <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        router <- routerWith(
          McpServerSettings(),
          tool("hold")(started.succeed(()) *> release.await.as("held"))
        )
        wired <- wire(router)
        _ <- wired.data(handshake + call(2, "hold") + "\n")
        _ <- started.await
        _ <- wired.end
        // `end` has been delivered and the handler is parked: stdin must not be done with.
        pending <- wired.finished.isDone
        _ <- release.succeed(())
        _ <- wired.finished.await
        written <- wired.written.get
      yield (pending, written)
    ) { (doneWhileHeld, written) =>
      doneWhileHeld shouldBe false
      replyTo(written, 2).getOrElse(fail(s"no reply to the held call in: $written")) should include(
        "held"
      )
    }
  }

  it should "interrupt a handler that never finishes after the drain timeout" in {
    run(
      for
        started <- Promise.make[Nothing, Unit]
        interrupted <- Promise.make[Nothing, Unit]
        router <- routerWith(
          McpServerSettings(),
          tool("hang")(started.succeed(()) *> ZIO.never.onInterrupt(interrupted.succeed(())))
        )
        wired <- wire(router, drainTimeout = 100.millis)
        _ <- wired.data(handshake + call(2, "hang") + "\n")
        // `end` only once the handler runs: a fiber interrupted before it starts would never
        // install the `onInterrupt` this test waits for.
        _ <- started.await
        written <- wired.endAndWait
        _ <- interrupted.await
      yield written
    ) { written =>
      replyTo(written, 1) should not be empty
      replyTo(written, 2) shouldBe empty
    }
  }

  it should "not wait for a background task body" in {
    run(
      for
        bodyStarted <- Promise.make[Nothing, Unit]
        router <- routerWith(
          McpServerSettings(tasks = TaskSettings(enabled = true)),
          tool("forever")(bodyStarted.succeed(()) *> ZIO.never)
            .withTaskSupport(TaskSupport.Optional)
        )
        wired <- wire(router)
        _ <- wired.data(handshake + call(2, "forever", ""","task":{"ttl":60000}""") + "\n")
        // The task body runs and the drain has no limit: `end` completes only if the body is not
        // counted as a reply still owed.
        _ <- bodyStarted.await
        written <- wired.endAndWait
      yield written
    ) { written =>
      replyTo(written, 2).getOrElse(fail(s"no task handle in: $written")) should include("taskId")
    }
  }

  it should "fail a handler waiting for the client's answer at once, not at the timeout" in {
    run(
      for
        router <- {
          val server = McpServer.typed[Any]("JsStdioLifecycle", "0.1.0", McpServerSettings())
          server.tool(
            McpTool[NoArgs, String](name = "roots").contextual { (_, ctx) =>
              ctx match
                case Some(c) => c.listRoots().map(_.roots.size.toString)
                case None => ZIO.fail(McpError.internalError("no context"))
            }
          ) *> server.buildRouter
        }
        wired <- wire(router)
        // Whether the handler asks for the roots before `end` or after it, nobody can answer: a
        // request already waiting is failed by `end`, a later one fails when it is made. With no
        // drain limit, the test passes only if the handler does not wait for its own timeout.
        _ <- wired.data(handshake + call(2, "roots") + "\n")
        written <- wired.endAndWait
      yield written
    ) { written =>
      val reply = replyTo(written, 2).getOrElse(fail(s"no reply to the roots call in: $written"))
      reply should include("the client closed its input stream")
    }
  }

  it should "deliver the client's answer written just before stdin ends to the handler" in {
    run(
      for
        router <- {
          val server = McpServer.typed[Any]("JsStdioLifecycle", "0.1.0", McpServerSettings())
          server.tool(
            McpTool[NoArgs, String](name = "roots").contextual { (_, ctx) =>
              ctx match
                case Some(c) => c.listRoots().map(_.roots.size.toString)
                case None => ZIO.fail(McpError.internalError("no context"))
            }
          ) *> server.buildRouter
        }
        wired <- wire(router)
        _ <- wired.data(handshake + call(2, "roots") + "\n")
        request <- wired.pushed.repeatUntil(_.contains("roots/list"))
        id = request.split("\"id\":")(1).takeWhile(c => c != ',' && c != '}')
        // The answer and `end` arrive back to back, with nothing run in between.
        _ <- ZIO.succeed {
          val runtime = Runtime.default
          Unsafe.unsafe { implicit unsafe =>
            runtime.unsafe.run(
              wired.data(
                s"""{"jsonrpc":"2.0","id":$id,"result":{"roots":[{"uri":"file:///a"}]}}""" + "\n"
              ) *> wired.end
            )
          }
        }
        _ <- wired.finished.await
        written <- wired.written.get
      yield written
    ) { written =>
      val reply = replyTo(written, 2).getOrElse(fail(s"no reply to the roots call in: $written"))
      reply should not include "the client closed its input stream"
      reply should not include "isError\":true"
    }
  }

  it should "not let an open subscriptions/listen hold the drain" in {
    val listen =
      """{"jsonrpc":"2.0","id":7,"method":"subscriptions/listen","params":{"notifications":{"toolsListChanged":true},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}"""
    run(
      for
        router <- routerWith(McpServerSettings(), echo)
        wired <- wire(router)
        _ <- wired.data(listen + "\n")
        _ <- wired.pushed.repeatUntil(_.contains("subscriptions/acknowledged"))
        // No drain limit: a listen handler that held the drain would never finish.
        written <- wired.endAndWait
      yield written
    ) { written =>
      replyTo(written, 7) shouldBe None
    }
  }

  // --- 2. the final frame without a newline ------------------------------------------------------

  "Bun stdio" should "dispatch and answer a final frame without a newline" in {
    run(
      for
        router <- routerWith(McpServerSettings(), echo)
        wired <- wire(router)
        // Chunk boundaries inside frames, and no `\n` after the last one.
        _ <- ZIO.foreachDiscard((handshake + listTools(2)).grouped(23).toList)(wired.data)
        written <- wired.endAndWait
      yield written
    ) { written =>
      replyTo(written, 2).getOrElse(fail(s"no reply to the unterminated frame in: $written")) should
        include(""""tools":""")
    }
  }

  it should "answer -32700 for an over-long final line without a newline" in {
    val limits = LimitSettings(maxFrameChars = 256)
    run(
      for
        router <- routerWith(McpServerSettings(limits = limits), echo)
        wired <- wire(router)
        _ <- wired.data(handshake)
        _ <- wired.data(listTools(2) + " " * 400)
        written <- wired.endAndWait
      yield written
    ) { written =>
      written.count(_.contains("-32700")) shouldBe 1
      written.filter(_.contains("-32700")).head should include("maxFrameChars")
    }
  }

  it should "ignore a blank final line" in {
    run(
      for
        router <- routerWith(McpServerSettings(), echo)
        wired <- wire(router)
        _ <- wired.data(handshake + "  \t")
        written <- wired.endAndWait
      yield written
    )(_.size shouldBe 1)
  }

  // --- 4. initialize ordering --------------------------------------------------------------------

  it should "never answer a pipelined handshake 'not initialized'" in {
    val requests = (2 to 6).toList
    val burst = handshake + requests.map(listTools(_) + "\n").mkString
    run(
      for
        router <- routerWith(McpServerSettings(), echo)
        runs <- ZIO.foreach((1 to 40).toList) { n =>
          for
            wired <- wire(router)
            // One chunk, and the same bytes cut at arbitrary places.
            _ <-
              if n % 2 == 0 then wired.data(burst)
              else ZIO.foreachDiscard(burst.grouped(7 + n).toList)(wired.data)
            written <- wired.endAndWait
          yield written
        }
      yield runs
    ) { runs =>
      runs.foreach { written =>
        written.filter(_.contains("-32600")) shouldBe empty
        // `initialize` is finished, reply included, before any later frame runs.
        written.headOption.getOrElse(fail("nothing was written")) should include(""""id":1,""")
        requests.filterNot(replyTo(written, _).exists(_.contains(""""tools":"""))) shouldBe empty
      }
      succeed
    }
  }

  it should "serve a 2026-07-28 request without a handshake and refuse a modern 'initialize'" in {
    val meta =
      """"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}"""
    val modernList = s"""{"jsonrpc":"2.0","id":7,"method":"tools/list","params":{$meta}}"""
    val modernInit = s"""{"jsonrpc":"2.0","id":8,"method":"initialize","params":{$meta}}"""
    run(
      for
        router <- routerWith(McpServerSettings(), echo)
        wired <- wire(router)
        _ <- wired.data(List(modernList, modernInit, modernList).map(_ + "\n").mkString)
        written <- wired.endAndWait
      yield written
    ) { written =>
      written.filter(_.contains("-32600")) shouldBe empty
      written.count(_.contains(""""tools":""")) shouldBe 2
      replyTo(written, 8).getOrElse(fail(s"no reply to the modern initialize in: $written")) should
        include("-32601")
    }
  }

  // --- 5. wording --------------------------------------------------------------------------------

  it should "name the handshake and the 2026-07-28 _meta route in the 'not initialized' error" in {
    run(
      for
        router <- routerWith(McpServerSettings(), echo)
        wired <- wire(router)
        _ <- wired.data(listTools(3) + "\n")
        written <- wired.endAndWait
      yield written
    ) { written =>
      val reply = replyTo(written, 3).getOrElse(fail(s"no reply in: $written"))
      reply should include("-32600")
      reply should include("send initialize before 'tools/list'")
      reply should include("""_meta[\"io.modelcontextprotocol/protocolVersion\"]""")
    }
  }

  // --- interruption ------------------------------------------------------------------------------

  it should "interrupt the handlers in flight when the server is interrupted" in {
    run(
      for
        started <- Promise.make[Nothing, Unit]
        interrupted <- Promise.make[Nothing, Unit]
        router <- routerWith(
          McpServerSettings(),
          tool("hang")(started.succeed(()) *> ZIO.never.onInterrupt(interrupted.succeed(())))
        )
        wired <- wire(router)
        _ <- wired.data(handshake + call(2, "hang") + "\n")
        _ <- started.await
        // What `serveStdio` runs when its fiber is interrupted while stdin is still open.
        _ <- wired.interruptInFlight
        _ <- interrupted.await
      yield ()
    )(_ => succeed)
  }
