package com.tjclp.fastmcp
package server.transport.http

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.*

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.core.ProgressToken
import com.tjclp.fastmcp.jsonrpc.JsonRpcMessage
import com.tjclp.fastmcp.server.transport.TransportBackend

/** Contracts of the shared streamable-HTTP handler that the socket backend leans on, run on both
  * the JVM and Scala Native (`jvm-native/test`). Drives the handler directly through the neutral
  * model — no port, no zio-http.
  */
class StreamableHttpHandlerTest extends AnyFunSuite with Matchers:

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""

  /** `grace` is how long a streamed reply may sit unstarted; short here so the tests that leave one
    * unstarted do not have to wait out the production value.
    */
  private def makeHandler(
      grace: Duration = StreamableHttpHandler.UnstartedReplyGrace,
      register: McpServer[Any] => ZIO[Any, Throwable, Any] = _ => ZIO.unit
  ): StreamableHttpHandler[Any] =
    val server = McpServer.typed[Any]("HandlerT", "0.1.0", McpServerSettings())
    val _ = runUnsafe(register(server))
    runUnsafe(
      server.buildRouter.flatMap { router =>
        StreamableHttpHandler.make(
          router,
          server.settings,
          summon[TransportBackend].randomId(),
          grace
        )
      }
    )

  private val postHeaders =
    Map("accept" -> "application/json, text/event-stream", "content-type" -> "application/json")

  /** Initialize a legacy session, read the reply to its end, and return the session id. */
  private def openSession(handler: StreamableHttpHandler[Any]): String =
    val init = runUnsafe(handler.post(request("POST", postHeaders, initFrame)))
    init.status shouldBe 200
    runUnsafe(framesOf(init).runDrain)
    init.headers
      .collectFirst { case (name, value) if name.equalsIgnoreCase("mcp-session-id") => value }
      .getOrElse(fail("initialize did not mint a session"))

  private def storedSession(handler: StreamableHttpHandler[Any], sid: String) =
    runUnsafe(handler.session(sid)).getOrElse(fail(s"session $sid is not stored"))

  /** Poll until `check` holds; fails after ten seconds. */
  private def eventually(what: String)(check: UIO[Boolean]): Unit =
    runUnsafe(
      (ZIO.sleep(20.millis) *> check)
        .repeatUntil(identity)
        .timeoutFail(new RuntimeException(s"timed out waiting until $what"))(10.seconds)
        .unit
    )

  case class HoldArgs(note: Option[String] = None)
  given JsonDecoder[HoldArgs] = DeriveJsonDecoder.gen[HoldArgs]

  /** A tool that reports when it started and when it was interrupted. With `progressFirst` it
    * emits a progress notification before it parks, so its reply is a stream; `finishAfter` makes
    * it return instead of parking for good.
    */
  private final class Held(progressFirst: Boolean, finishAfter: Option[Duration] = None):
    val started: Promise[Nothing, Unit] = runUnsafe(Promise.make[Nothing, Unit])
    val stopped: Promise[Nothing, Unit] = runUnsafe(Promise.make[Nothing, Unit])

    val tool: McpTool[HoldArgs, String] = McpTool
      .withSchema[HoldArgs, String](
        name = "hold",
        inputSchema =
          ToolInputSchema.unsafeFromJsonString("""{"type":"object","properties":{}}"""),
        description = Some("Parks until interrupted")
      )
      .contextual { (_, ctx) =>
        val progress =
          if progressFirst then ctx.get.sendProgress(ProgressToken.StringToken("t"), 0.5)
          else ZIO.unit
        val rest = finishAfter.fold[UIO[String]](ZIO.never)(ZIO.sleep(_).as("held done"))
        (progress *> started.succeed(()) *> rest).onInterrupt(stopped.succeed(()))
      }

  private val holdFrame =
    """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"hold","arguments":{}}}"""

  private def request(method: String, headers: Map[String, String], body: String = ""): HttpRequest =
    val lookup: String => Option[String] = name => headers.get(name.toLowerCase)
    HttpRequest(method, "/mcp", lookup, lookup, ZIO.succeed(body))

  private def framesOf(reply: HttpReply) =
    reply match
      case HttpReply.Sse(_, frames, _) => frames
      case HttpReply.SseComplete(_, frame) => zio.stream.ZStream.succeed(frame)
      case other => fail(s"expected an SSE reply, got $other")

  test("a reply stream halted before its first pull still runs the handler's finalizers") {
    val handler = makeHandler()
    val init = runUnsafe(
      handler.post(
        request(
          "POST",
          Map(
            "accept" -> "application/json, text/event-stream",
            "content-type" -> "application/json"
          ),
          initFrame
        )
      )
    )
    init.status shouldBe 200
    val sid = init.headers
      .collectFirst { case (name, value) if name.equalsIgnoreCase("mcp-session-id") => value }
      .getOrElse(fail("initialize did not mint a session"))
    runUnsafe(framesOf(init).runDrain)

    val getHeaders = Map("accept" -> "text/event-stream", "mcp-session-id" -> sid)
    val first = runUnsafe(handler.get(request("GET", getHeaders)))
    first.status shouldBe 200

    // What the socket layer does when the head write fails: complete `dead` up front and run the
    // stream through interruptWhen so it halts immediately. Its `ensuring(session.releaseGet)`
    // must still fire — otherwise this session answers 409 to every later GET, forever.
    runUnsafe(
      Promise.make[Nothing, Unit].flatMap { dead =>
        dead.succeed(()) *> framesOf(first).interruptWhen(dead).runDrain
      }
    )

    val second = runUnsafe(handler.get(request("GET", getHeaders)))
    second.status shouldBe 200
  }

  test("a POST whose header names show a repeated routing header is a header mismatch (400, -32020)") {
    val handler = makeHandler()
    val discover =
      """{"jsonrpc":"2.0","id":7,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}"""
    val headers = Map(
      "accept" -> "application/json, text/event-stream",
      "content-type" -> "application/json",
      "mcp-protocol-version" -> "2026-07-28",
      "mcp-method" -> "server/discover"
    )
    def post(body: String, lookup: Map[String, String], names: List[String]): HttpReply =
      runUnsafe(
        handler.post(
          HttpRequest(
            "POST",
            "/mcp",
            name => lookup.get(name.toLowerCase),
            name => lookup.get(name.toLowerCase),
            ZIO.succeed(body),
            None,
            () => names.iterator
          )
        )
      )
    def mismatch(reply: HttpReply): Unit =
      reply match
        case HttpReply.Json(status, body, _) =>
          status shouldBe 400
          body should include(""""code":-32020""")
          body should include(""""id":7""")
        case other => fail(s"expected a JSON error reply, got $other")

    // The lookups see one value (whichever the backend picks); the names show the repeat.
    post(discover, headers, headers.keys.toList).status shouldBe 200
    mismatch(post(discover, headers, "Mcp-Method" :: headers.keys.toList))
    mismatch(post(discover, headers, headers.keys.toList :+ "MCP-Protocol-Version"))
    // A request built without names (no wire behind it) is judged on its lookups alone.
    runUnsafe(handler.post(request("POST", headers, discover))).status shouldBe 200

    // A repeated version header is refused on a legacy-looking request too: its first value must
    // not choose the path while another one says otherwise.
    val legacy = headers - "mcp-method" + ("mcp-protocol-version" -> "2025-11-25")
    post(initFrame, legacy, legacy.keys.toList).status shouldBe 200
    post(initFrame, legacy, "mcp-protocol-version" :: legacy.keys.toList) match
      case HttpReply.Json(status, body, _) =>
        status shouldBe 400
        body should include(""""code":-32020""")
      case other => fail(s"expected a JSON error reply, got $other")
    // The other routing headers mean nothing to a legacy request and are left alone there.
    post(initFrame, legacy, "mcp-name" :: "mcp-name" :: legacy.keys.toList).status shouldBe 200
  }

  test("a request that never forces its body is answered without the body being read") {
    val handler = makeHandler()
    val touched = new java.util.concurrent.atomic.AtomicBoolean(false)
    val body: IO[Throwable, String] = ZIO.succeed { touched.set(true); initFrame }
    // Accept without text/event-stream: rejected by the guard before the body is needed.
    val reply = runUnsafe(
      handler.post(
        HttpRequest(
          "POST",
          "/mcp",
          Map("accept" -> "text/plain", "content-type" -> "application/json").get,
          Map("accept" -> "text/plain", "content-type" -> "application/json").get,
          body
        )
      )
    )
    reply.status shouldBe 406
    touched.get shouldBe false
  }

  // --- request resources when a reply is never streamed ------------------------------------------

  test("a GET reply that is never streamed gives the session's GET slot back") {
    val handler = makeHandler(grace = 100.millis)
    val sid = openSession(handler)
    val getHeaders = Map("accept" -> "text/event-stream", "mcp-session-id" -> sid)

    // The handler returns the reply; nothing ever runs its stream (the client left as the
    // response head went out, or the request was interrupted on the way to the backend).
    val first = runUnsafe(handler.get(request("GET", getHeaders)))
    first.status shouldBe 200
    eventually("the unstarted GET released its slot") {
      storedSession(handler, sid).hasActiveGet.map(!_)
    }

    // The slot is usable again, and a second reply left unstarted is released the same way.
    runUnsafe(handler.get(request("GET", getHeaders))).status shouldBe 200
    eventually("the second unstarted GET released its slot") {
      storedSession(handler, sid).hasActiveGet.map(!_)
    }

    // A reply that was given up on must not come back to life: run late, its stream ends at once
    // instead of draining the session's messages without holding the slot.
    val late = runUnsafe(
      framesOf(first).runCollect
        .timeoutFail(new RuntimeException("a given-up GET stream still ran"))(5.seconds)
    )
    late shouldBe empty
    runUnsafe(storedSession(handler, sid).hasActiveGet) shouldBe false
  }

  test("a GET stream that was started keeps the slot for as long as it runs") {
    val handler = makeHandler(grace = 500.millis)
    val sid = openSession(handler)
    val getHeaders = Map("accept" -> "text/event-stream", "mcp-session-id" -> sid)
    val first = runUnsafe(handler.get(request("GET", getHeaders)))
    first.status shouldBe 200

    // A daemon: a plain fork would be interrupted as soon as this `runUnsafe` returned.
    val streaming = runUnsafe(framesOf(first).runDrain.forkDaemon)
    // Well past the grace: a stream that is being read is never mistaken for an unstarted one.
    runUnsafe(ZIO.sleep(1500.millis))
    runUnsafe(storedSession(handler, sid).hasActiveGet) shouldBe true
    runUnsafe(handler.get(request("GET", getHeaders))).status shouldBe 409

    // The client goes away: the stream's own finalizer frees the slot.
    runUnsafe(streaming.interrupt.unit)
    runUnsafe(storedSession(handler, sid).hasActiveGet) shouldBe false
    runUnsafe(handler.get(request("GET", getHeaders))).status shouldBe 200
  }

  test("a backend that abandons a streamed reply frees its resources at once; a started stream is left alone") {
    // The production grace: nothing here may depend on waiting it out.
    val handler = makeHandler()
    val sid = openSession(handler)
    val getHeaders = Map("accept" -> "text/event-stream", "mcp-session-id" -> sid)
    def abandonOf(reply: HttpReply): UIO[Unit] =
      reply match
        case sse: HttpReply.Sse => sse.abandon
        case other => fail(s"expected a streamed reply, got $other")

    val dropped = runUnsafe(handler.get(request("GET", getHeaders)))
    runUnsafe(storedSession(handler, sid).hasActiveGet) shouldBe true
    runUnsafe(abandonOf(dropped))
    runUnsafe(storedSession(handler, sid).hasActiveGet) shouldBe false
    runUnsafe(abandonOf(dropped)) // idempotent

    // A reply whose stream is running owns its resources itself: abandoning it changes nothing.
    val live = runUnsafe(handler.get(request("GET", getHeaders)))
    live.status shouldBe 200
    val delivered = runUnsafe(Promise.make[Nothing, Unit])
    val streaming =
      runUnsafe(framesOf(live).tap(_ => delivered.succeed(())).runDrain.forkDaemon)
    // A message that came out of the stream proves the stream is running.
    runUnsafe(
      storedSession(handler, sid).outbound
        .offer(JsonRpcMessage.Notification("notifications/tools/list_changed", None)) *>
        delivered.await.timeoutFail(new RuntimeException("the GET stream never ran"))(10.seconds)
    )
    runUnsafe(abandonOf(live))
    runUnsafe(storedSession(handler, sid).hasActiveGet) shouldBe true
    runUnsafe(handler.get(request("GET", getHeaders))).status shouldBe 409
    runUnsafe(streaming.interrupt.unit)
    runUnsafe(storedSession(handler, sid).hasActiveGet) shouldBe false
  }

  test("a POST reply that is never streamed stops the request it belongs to") {
    val held = new Held(progressFirst = true)
    val handler = makeHandler(grace = 100.millis, register = _.tool(held.tool))
    val sid = openSession(handler)

    // The progress notification is the reply's first frame, so the reply is a stream.
    val reply = runUnsafe(
      handler.post(request("POST", postHeaders + ("mcp-session-id" -> sid), holdFrame))
    )
    reply shouldBe a[HttpReply.Sse]
    runUnsafe(held.started.await.timeoutFail(new RuntimeException("tool never started"))(10.seconds))

    // Nothing runs the stream. The dispatch must not go on for ever with nobody to answer.
    runUnsafe(
      held.stopped.await
        .timeoutFail(new RuntimeException("the dispatch outlived its unstreamed reply"))(10.seconds)
    )
  }

  test("a streamed POST reply that is being read is not cut short by the unstarted-reply grace") {
    val held = new Held(progressFirst = true, finishAfter = Some(1500.millis))
    val handler = makeHandler(grace = 500.millis, register = _.tool(held.tool))
    val sid = openSession(handler)
    val reply = runUnsafe(
      handler.post(request("POST", postHeaders + ("mcp-session-id" -> sid), holdFrame))
    )
    // Read at once; the call outlasts the grace several times over and must still be answered.
    val frames = runUnsafe(framesOf(reply).runCollect).map(_.data).mkString("\n")
    frames should include("notifications/progress")
    frames should include("held done")
  }

  test("a POST interrupted while it waits for its first frame stops the request") {
    val held = new Held(progressFirst = false)
    val handler = makeHandler(register = _.tool(held.tool))
    val sid = openSession(handler)
    val waiting = runUnsafe(
      handler
        .post(request("POST", postHeaders + ("mcp-session-id" -> sid), holdFrame))
        .forkDaemon
    )
    runUnsafe(held.started.await.timeoutFail(new RuntimeException("tool never started"))(10.seconds))
    // The client disconnects: the backend interrupts the handler.
    runUnsafe(waiting.interrupt.unit)
    runUnsafe(
      held.stopped.await
        .timeoutFail(new RuntimeException("the dispatch outlived its interrupted request"))(
          10.seconds
        )
    )
  }
