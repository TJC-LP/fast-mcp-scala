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

  test("a reply stream halted before its first pull frees the GET slot, at once or by the grace") {
    val handler = makeHandler(grace = 300.millis)
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

    // A backend that runs the stream already halted instead of calling `abandon`: complete `dead`
    // up front and run the stream through interruptWhen. Whether the stream's own finalizer runs
    // is a race — the halt can win before the stream has registered it — so the slot comes back
    // at once or, at the latest, when the unstarted-reply grace passes. Either way this session
    // must not answer 409 to every later GET for ever.
    runUnsafe(
      Promise.make[Nothing, Unit].flatMap { dead =>
        dead.succeed(()) *> framesOf(first).interruptWhen(dead).runDrain
      }
    )

    val second = runUnsafe(
      (handler.get(request("GET", getHeaders)) <* ZIO.sleep(20.millis))
        .repeatUntil(_.status == 200)
        .timeout(5.seconds)
    )
    second.map(_.status) shouldBe Some(200)
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

  /** A clock whose first `sleep` returns at once and whose later ones are real. That is what a
    * timer sees after the process was frozen for longer than its delay (a stop-the-world pause, a
    * suspended VM, a paused container): the deadline it was waiting for has already passed when it
    * runs again, while everything it schedules afterwards takes its normal time.
    */
  private final class FrozenOnceClock extends Clock:
    private val first = new java.util.concurrent.atomic.AtomicBoolean(true)
    private val live = Clock.ClockLive
    def currentTime(unit: => java.util.concurrent.TimeUnit)(implicit trace: Trace): UIO[Long] =
      live.currentTime(unit)
    def currentTime(unit: => java.time.temporal.ChronoUnit)(implicit
        trace: Trace,
        d: DummyImplicit
    ): UIO[Long] = live.currentTime(unit)
    def currentDateTime(implicit trace: Trace): UIO[java.time.OffsetDateTime] =
      live.currentDateTime
    def instant(implicit trace: Trace): UIO[java.time.Instant] = live.instant
    def javaClock(implicit trace: Trace): UIO[java.time.Clock] = live.javaClock
    def localDateTime(implicit trace: Trace): UIO[java.time.LocalDateTime] = live.localDateTime
    def nanoTime(implicit trace: Trace): UIO[Long] = live.nanoTime
    def scheduler(implicit trace: Trace): UIO[Scheduler] = live.scheduler
    def sleep(duration: => Duration)(implicit trace: Trace): UIO[Unit] =
      if first.compareAndSet(true, false) then ZIO.unit else live.sleep(duration)

  test("a process frozen past the grace does not make a reply that is then streamed come back empty") {
    // Antithesis found this by pausing a node for nine seconds just after a reply was built (run
    // 138b4b66187ffc9deaf28072fff5b5d9-66-21): the reply's watchdog saw its whole grace gone when
    // the process ran again, released the reply, and the backend then streamed an empty 200.
    val handler = makeHandler(grace = 2.seconds)
    val sid = openSession(handler)
    val getHeaders = Map("accept" -> "text/event-stream", "mcp-session-id" -> sid)

    val reply = runUnsafe(ZIO.withClock(new FrozenOnceClock)(handler.get(request("GET", getHeaders))))
    reply.status shouldBe 200
    // The backend starts the stream a moment after the freeze ends, as it would after a real one.
    val outcome = runUnsafe(
      ZIO.sleep(100.millis) *> framesOf(reply).runCollect.timeout(400.millis)
    )
    // A live GET stream stays open; a reply that was given up on ends at once, empty.
    outcome shouldBe None
    runUnsafe(storedSession(handler, sid).hasActiveGet) shouldBe false // the timeout ended the run
    runUnsafe(handler.get(request("GET", getHeaders))).status shouldBe 200
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

  // ---- the legacy session store: what a session retains, when it is stored, who can fill it ----

  /** A handler for the store tests. Its unstarted-reply grace is far longer than any test: these
    * tests hold a GET slot through a reply whose stream nothing runs (`openGet`), and the slot must
    * stay held for as long as the test looks at it.
    */
  private def storeHandler(
      settings: McpServerSettings = McpServerSettings()
  ): StreamableHttpHandler[Any] =
    val server = McpServer.typed[Any]("StoreT", "0.1.0", settings)
    runUnsafe(
      server.buildRouter.flatMap { router =>
        StreamableHttpHandler.make(
          router,
          server.settings,
          summon[TransportBackend].randomId(),
          unstartedReplyGrace = 1.hour
        )
      }
    )

  private val pingFrame = """{"jsonrpc":"2.0","id":7,"method":"ping"}"""

  private def initFrameWith(capabilities: String, clientInfo: String): String =
    s"""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":$capabilities,"clientInfo":$clientInfo}}"""

  private val plainClientInfo = """{"name":"t","version":"1.0"}"""

  /** One POST, optionally on a session and from a given peer address. */
  private def post(
      handler: StreamableHttpHandler[Any],
      body: String,
      sid: Option[String] = None,
      peer: Option[String] = None
  ): HttpReply =
    val headers = sid.fold(postHeaders)(id => postHeaders + ("mcp-session-id" -> id))
    val lookup: String => Option[String] = name => headers.get(name.toLowerCase)
    runUnsafe(handler.post(HttpRequest("POST", "/mcp", lookup, lookup, ZIO.succeed(body), peer)))

  private def sessionIdOf(reply: HttpReply): Option[String] =
    reply.headers.collectFirst {
      case (name, value) if name.equalsIgnoreCase("mcp-session-id") => value
    }

  /** The whole reply body as text: the JSON body, or the `data:` payloads of an SSE reply. */
  private def textOf(reply: HttpReply): String =
    reply match
      case HttpReply.Json(_, body, _) => body
      case HttpReply.Empty(_, _) => ""
      case sse => runUnsafe(framesOf(sse).runCollect).map(_.data).mkString("\n")

  private def initialize(handler: StreamableHttpHandler[Any], peer: Option[String] = None): String =
    val reply = post(handler, initFrame, peer = peer)
    withClue(s"initialize from $peer: ${reply.status} ") { reply.status shouldBe 200 }
    val _ = textOf(reply)
    sessionIdOf(reply).getOrElse(fail(s"initialize from $peer minted no session"))

  /** True while the session is stored (an unknown session id answers 404). */
  private def alive(handler: StreamableHttpHandler[Any], sid: String): Boolean =
    post(handler, pingFrame, Some(sid)).status != 404

  /** Open the session's GET stream and never drain it: the session holds a live GET from here on
    * (`storeHandler` never gives an unstarted reply's slot back within a test's lifetime).
    */
  private def openGet(handler: StreamableHttpHandler[Any], sid: String): Unit =
    val reply = runUnsafe(
      handler.get(request("GET", Map("accept" -> "text/event-stream", "mcp-session-id" -> sid)))
    )
    reply.status shouldBe 200

  test("an initialize within the retention budget is accepted with icons and open capability members") {
    val handler = storeHandler()
    val capabilities =
      """{"roots":{"listChanged":true},"sampling":{},"elicitation":{"form":{},"url":{}},"experimental":{"x":{"a":[1,2,3]}},"extensions":{"io.modelcontextprotocol/ui":{"mimeTypes":["text/html;profile=mcp-app"]}}}"""
    val clientInfo =
      """{"name":"client","version":"1.2.3","title":"A client","websiteUrl":"https://example.com","icons":[{"src":"https://example.com/icon.png","mimeType":"image/png","sizes":["48x48"]}]}"""
    val reply = post(handler, initFrameWith(capabilities, clientInfo))
    reply.status shouldBe 200
    textOf(reply) should include("\"serverInfo\"")
    sessionIdOf(reply) should not be empty
  }

  test("an initialize whose capabilities hold too many values is rejected with -32602 and mints no session") {
    val handler = storeHandler()
    val manyValues = List.fill(300)("0").mkString("[", ",", "]")
    val reply = post(handler, initFrameWith(s"""{"experimental":{"x":$manyValues}}""", plainClientInfo))
    val text = textOf(reply)
    text should include("-32602")
    text should not include "serverInfo"
    sessionIdOf(reply) shouldBe None
  }

  test("an initialize whose clientInfo holds too much text is rejected with -32602 and mints no session") {
    val handler = storeHandler()
    val longName = "n" * (32 * 1024)
    val reply = post(handler, initFrameWith("{}", s"""{"name":"$longName","version":"1.0"}"""))
    val text = textOf(reply)
    text should include("-32602")
    text should not include "serverInfo"
    sessionIdOf(reply) shouldBe None
  }

  test("a body-cap-sized initialize holding one very long capability array is rejected and mints no session") {
    val handler = storeHandler()
    val cap = McpServerSettings().maxRequestBodyBytes
    val head =
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","clientInfo":{"name":"t","version":"1.0"},"capabilities":{"experimental":{"x":[0"""
    val tail = "]}}}}"
    val body = head + ",0" * ((cap - head.length - tail.length) / 2) + tail
    body.length should be > cap - 4
    body.length should be <= cap
    val reply = post(handler, body)
    val text = textOf(reply)
    // -32602 from the retention budget; a frame-level bound on array length, where configured
    // below this size, answers -32700 first. Either way the request is refused outright.
    text should (include("-32602") or include("-32700"))
    text should not include "serverInfo"
    sessionIdOf(reply) shouldBe None
  }

  test("a failed initialize leaves the store unchanged: no session id, and no stored session is evicted for it") {
    // One slot, taken by an idle session without a GET stream — the first eviction candidate.
    val handler = storeHandler(McpServerSettings(maxSessions = Some(1)))
    val stored = initialize(handler)

    val undecodable =
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{}}}"""
    val overBudget =
      initFrameWith(s"""{"experimental":{"x":${List.fill(300)("0").mkString("[", ",", "]")}}}""", plainClientInfo)
    for body <- List(undecodable, overBudget) do
      val reply = post(handler, body)
      textOf(reply) should include("-32602")
      sessionIdOf(reply) shouldBe None
      withClue("the stored session must survive a failed initialize: ") {
        alive(handler, stored) shouldBe true
      }

    // A successful initialize is what takes the slot.
    val next = initialize(handler)
    alive(handler, next) shouldBe true
    alive(handler, stored) shouldBe false
  }

  test("one peer holding every slot with live, freshly touched GET streams cannot lock another peer out") {
    val cap = 4
    // No idle timeout: nothing ever becomes evictable through idleness.
    val handler =
      storeHandler(McpServerSettings(maxSessions = Some(cap), sessionIdleTimeout = None))
    val hog = Some("203.0.113.7")
    val other = Some("198.51.100.20")

    val hogged = List.fill(cap)(initialize(handler, hog))
    hogged.foreach(openGet(handler, _))
    def touchAll(): Unit = hogged.foreach(sid => post(handler, pingFrame, Some(sid), hog))
    touchAll()
    hogged.count(alive(handler, _)) shouldBe cap

    val admitted = post(handler, initFrame, peer = other)
    withClue(s"initialize from another peer at the cap: ${textOf(admitted)} ") {
      admitted.status shouldBe 200
    }
    val newcomer = sessionIdOf(admitted).getOrElse(fail("the other peer was given no session"))
    alive(handler, newcomer) shouldBe true
    // The store never exceeds the cap: exactly one of the hog's sessions made room.
    hogged.count(alive(handler, _)) shouldBe cap - 1

    // The hog keeps touching and keeps initializing: it only ever displaces its own sessions.
    val more = (1 to 3).toList.map { _ =>
      touchAll()
      initialize(handler, hog)
    }
    alive(handler, newcomer) shouldBe true
    (hogged ++ more).count(alive(handler, _)) shouldBe cap - 1

    // And a third peer still gets in, again at the hog's expense.
    val third = initialize(handler, Some("192.0.2.33"))
    alive(handler, third) shouldBe true
    alive(handler, newcomer) shouldBe true
    (hogged ++ more).count(alive(handler, _)) shouldBe cap - 2
  }

  test("a peer that already holds the most sessions replaces its own at the cap, not an idle session of another peer") {
    val handler = storeHandler(McpServerSettings(maxSessions = Some(3)))
    val small = initialize(handler, Some("198.51.100.20")) // the longest-idle session, no GET
    Thread.sleep(5)
    val big = List.fill(2)(initialize(handler, Some("203.0.113.7")))
    Thread.sleep(5)

    val next = initialize(handler, Some("203.0.113.7"))
    alive(handler, small) shouldBe true
    alive(handler, next) shouldBe true
    big.count(alive(handler, _)) shouldBe 1
  }

  test("without a peer address the cap keeps its rules: live GET holders are exempt and the newcomer gets 503") {
    val handler =
      storeHandler(McpServerSettings(maxSessions = Some(2), sessionIdleTimeout = None))
    val held = List.fill(2)(initialize(handler))
    held.foreach(openGet(handler, _))

    val refused = post(handler, initFrame)
    refused.status shouldBe 503
    sessionIdOf(refused) shouldBe None
    held.count(alive(handler, _)) shouldBe 2
  }

  test("concurrent initializes from many peers at a hogged cap are all admitted and never grow the store past it") {
    val cap = 4
    val handler =
      storeHandler(McpServerSettings(maxSessions = Some(cap), sessionIdleTimeout = None))
    val hog = Some("203.0.113.7")
    val hogged = List.fill(cap)(initialize(handler, hog))
    hogged.foreach(openGet(handler, _))

    // Three other peers arrive at once, twice each. Snapshot, decision and insert are one atomic
    // step, so each is admitted against the store as the one before it left it.
    val peers = List("198.51.100.1", "198.51.100.2", "198.51.100.3")
    val replies = runUnsafe(
      ZIO.foreachPar(peers ++ peers) { peer =>
        val lookup: String => Option[String] = name => postHeaders.get(name.toLowerCase)
        handler.post(HttpRequest("POST", "/mcp", lookup, lookup, ZIO.succeed(initFrame), Some(peer)))
      }
    )
    replies.map(_.status).distinct shouldBe List(200)
    val minted = replies.flatMap(sessionIdOf)
    minted.distinct.size shouldBe 6
    (hogged ++ minted).count(alive(handler, _)) shouldBe cap
  }
