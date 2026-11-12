package com.tjclp.fastmcp
package server.transport

import java.util.concurrent.atomic.AtomicReference

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.http.{Header, Path, Request, Status, URL}

import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.transport.JvmTransportBackend.given
import com.tjclp.fastmcp.server.transport.http.{HttpReply, HttpRequest, StreamableHttpHandler}

/** What a request holds — its dispatch fiber and queue, a session's GET slot, a session evicted at
  * the cap — is given back whether or not the reply is ever streamed, and whenever the handler is
  * interrupted. `StreamableHttpHandlerTest` pins the same contract through the neutral model on
  * every platform; the tests here need the JVM (root-fiber counts, a logger that interrupts, the
  * zio-http request scope).
  */
class RequestCleanupTest extends AnyFunSuite with Matchers:

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""

  private def listenFrame(id: Int): String =
    s"""{"jsonrpc":"2.0","id":$id,"method":"subscriptions/listen","params":{"notifications":{"toolsListChanged":true},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}"""

  private val postHeaders =
    Map("accept" -> "application/json, text/event-stream", "content-type" -> "application/json")

  private val listenHeaders =
    postHeaders ++ Map(
      "mcp-protocol-version" -> "2026-07-28",
      "mcp-method" -> "subscriptions/listen"
    )

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private def makeHandler(
      settings: McpServerSettings = McpServerSettings(),
      grace: Duration = StreamableHttpHandler.UnstartedReplyGrace
  ): StreamableHttpHandler[Any] =
    val server = McpServer.typed[Any]("CleanupT", "0.1.0", settings)
    runUnsafe(
      server.buildRouter.flatMap { router =>
        StreamableHttpHandler.make(router, settings, summon[TransportBackend].randomId(), grace)
      }
    )

  private def request(method: String, headers: Map[String, String], body: String = ""): HttpRequest =
    val lookup: String => Option[String] = name => headers.get(name.toLowerCase)
    HttpRequest(method, "/mcp", lookup, lookup, ZIO.succeed(body))

  private def openSession(handler: StreamableHttpHandler[Any]): String =
    val init = runUnsafe(handler.post(request("POST", postHeaders, initFrame)))
    init.status shouldBe 200
    init match
      case HttpReply.Sse(_, frames, _) => runUnsafe(frames.runDrain)
      case _ => ()
    init.headers
      .collectFirst { case (name, value) if name.equalsIgnoreCase("mcp-session-id") => value }
      .getOrElse(fail("initialize did not mint a session"))

  private def rootFibers: Int = runUnsafe(Fiber.roots.map(_.size))

  /** Poll rather than sleep-then-assert: cleanup settles at its own pace. */
  private def awaitRootsSettle(before: Int, tolerance: Int = 5): Int =
    runUnsafe(
      (ZIO.sleep(25.millis) *> Fiber.roots.map(_.size))
        .repeatUntil(_ - before <= tolerance)
        .timeout(10.seconds)
        .flatMap {
          case Some(settled) => ZIO.succeed(settled)
          case None => Fiber.roots.map(_.size)
        }
    )

  test("subscriptions/listen: no fiber survives a handler interrupted along the way or a reply that is never streamed") {
    val handler = makeHandler(grace = 200.millis)
    // Warm the path up so the count below is not skewed by one-off runtime fibers.
    runUnsafe(handler.post(request("POST", listenHeaders, listenFrame(0))).flatMap {
      case HttpReply.Sse(_, frames, _) => frames.take(1).runDrain
      case other => ZIO.fail(new RuntimeException(s"expected a stream, got $other"))
    })
    val before = awaitRootsSettle(rootFibers, tolerance = 0)

    // Each request is a listen that acknowledges and then never ends. The handler is interrupted
    // after a different delay each time — before it runs, while it waits for the first frame,
    // between taking that frame and returning — and every third one is left to return its reply,
    // which is then dropped without its stream ever being run.
    val rounds = 150
    val completed = runUnsafe(
      ZIO.foreach((1 to rounds).toList) { i =>
        for
          fiber <- handler.post(request("POST", listenHeaders, listenFrame(i))).fork
          _ <-
            if i % 3 == 0 then fiber.await.unit
            else if i % 2 == 0 then ZIO.yieldNow.repeatN(i % 23)
            else ZIO.sleep(((i % 20) * 25).micros)
          exit <- fiber.interrupt
        yield exit.isSuccess
      }
    )
    withClue("the sweep must cover both a returned-and-dropped reply and an interrupted handler: ") {
      completed.count(identity) should be >= rounds / 3
      completed.count(!_) should be > 0
    }

    val after = awaitRootsSettle(before)
    withClue(s"root fibers before=$before after=$after (${completed.count(identity)} replies dropped unstreamed): ") {
      (after - before) should be <= 5
    }
  }

  test("an at-cap initialize interrupted after the store update still terminates the session it evicted") {
    val handler = makeHandler(McpServerSettings(maxSessions = Some(1)))
    val evictedId = openSession(handler)
    val evicted =
      runUnsafe(handler.session(evictedId)).getOrElse(fail("the first session is not stored"))

    // The eviction is logged after the store update and before the victim is terminated. A logger
    // that interrupts the handler fiber from inside that log call lands the interrupt exactly
    // there, on the fiber's own thread, before its next step.
    val target = new AtomicReference[Option[Fiber.Runtime[?, ?]]](None)
    def onEvictionLog(react: () => Unit): ZLogger[String, Unit] = new ZLogger[String, Unit]:
      def apply(
          trace: Trace,
          fiberId: FiberId,
          logLevel: LogLevel,
          message: () => String,
          cause: Cause[Any],
          context: FiberRefs,
          spans: List[LogSpan],
          annotations: Map[String, String]
      ): Unit =
        if message().contains("evicted session") then react()
    val interruptOnEviction = onEvictionLog { () =>
      target.get.foreach { fiber =>
        Unsafe.unsafe(implicit u => fiber.unsafe.interrupt(Cause.interrupt(FiberId.None)))
      }
    }

    val exit = runUnsafe(
      for
        go <- Promise.make[Nothing, Unit]
        fiber <- ZIO
          .scoped(
            ZIO.withLoggerScoped(interruptOnEviction) *>
              go.await *> handler.post(request("POST", postHeaders, initFrame))
          )
          .fork
        _ <- ZIO.succeed(target.set(Some(fiber)))
        _ <- go.succeed(())
        exit <- fiber.await
      yield exit
    )

    withClue("the second initialize must have been interrupted at the eviction: ") {
      exit.isInterrupted shouldBe true
    }
    // The store update had happened: the evicted session is no longer reachable by id, so nothing
    // else (DELETE, the idle sweeper) could ever terminate it.
    runUnsafe(handler.session(evictedId)) shouldBe None
    runUnsafe(evicted.isTerminated) shouldBe true

    // The interrupted initialize's own session went with it: its client never learned the id, so
    // it must not be left holding the store's one slot. The next initialize finds room and evicts
    // nobody.
    val evictions = new java.util.concurrent.atomic.AtomicInteger(0)
    val next = runUnsafe(
      ZIO.scoped(
        ZIO.withLoggerScoped(onEvictionLog(() => { val _ = evictions.incrementAndGet() })) *>
          handler.post(request("POST", postHeaders, initFrame))
      )
    )
    next.status shouldBe 200
    withClue("the interrupted initialize left its session in the store: ") {
      evictions.get shouldBe 0
    }
  }

  test("zio-http: a GET whose request ends by interruption before its stream starts frees the slot at once") {
    // The production grace: the slot must come back through the request's own scope, not by
    // waiting the grace out.
    val settings = McpServerSettings()
    val server = McpServer.typed[Any]("CleanupRoutes", "0.1.0", settings)
    val routes = runUnsafe(
      server.buildRouter.flatMap(r => JvmHttpBackend.httpRoutes(r, settings, ZEnvironment.empty))
    )
    val init = runUnsafe(
      ZIO.scoped(
        routes.runZIO(
          Request
            .post(URL(Path.root / "mcp"), zio.http.Body.fromString(initFrame))
            .addHeader(Header.Custom("content-type", "application/json"))
            .addHeader(Header.Custom("accept", "application/json, text/event-stream"))
        )
      )
    )
    val _ = runUnsafe(init.body.asString)
    val sid = init.rawHeader("mcp-session-id").getOrElse(fail("no session id"))
    val get = Request
      .get(URL(Path.root / "mcp"))
      .addHeader(Header.Custom("accept", "text/event-stream"))
      .addHeader(Header.Custom("mcp-session-id", sid))

    // What zio-http does when the connection closes after the handler returned and before the
    // body stream is run: the request's scope is closed with an interruption.
    val first = runUnsafe(
      Scope.make.flatMap { scope =>
        routes
          .runZIO(get)
          .provideEnvironment(ZEnvironment[Scope](scope))
          .flatMap(response => scope.close(Exit.interrupt(FiberId.None)).as(response.status))
      }
    )
    first shouldBe Status.Ok

    val second = runUnsafe(ZIO.scoped(routes.runZIO(get).map(_.status)))
    second shouldBe Status.Ok
  }
