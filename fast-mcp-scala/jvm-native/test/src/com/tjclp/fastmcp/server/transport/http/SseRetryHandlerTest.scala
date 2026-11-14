package com.tjclp.fastmcp
package server.transport.http

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.server.transport.TransportBackend
import com.tjclp.fastmcp.testkit.ManualClock

/** `McpServerSettings.sseRetryInterval`: the SSE `retry` field on the legacy adapter's
  * `text/event-stream` bodies (the `retry` half of SEP-1699).
  *
  *   - unset (the default), no reply carries the field: the wire is what it was;
  *   - set, every legacy SSE body begins with the line `retry: <ms>` — the first line of the one
  *     event of a fixed-length reply, an event of its own at the start of a stream;
  *   - 2026-07-28 replies and legacy stateless replies never carry it.
  *
  * Drives the shared handler through the neutral model, so it runs on the JVM and inside the Scala
  * Native test binary and covers every backend built on [[StreamableHttpHandler]] (zio-http and
  * the socket server render these frames). The wire itself is pinned per backend:
  * `SocketHttpBackendTest`, `JvmHttpSseRetryTest`, and `JsServerHttpTest` for Bun.
  */
class SseRetryHandlerTest extends AnyFunSuite with Matchers:
  import SseRetryHandlerTest.*

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  /** The clock of the handler the running test built, and of the tools it calls. */
  private def clock: ManualClock = Tools.clock.get()

  private def makeHandler(settings: McpServerSettings): StreamableHttpHandler[Any] =
    val server = McpServer.typed[Any]("SseRetryT", "0.1.0", settings)
    val _ = server.scanAnnotations[Tools.type]
    Tools.clock.set(new ManualClock)
    runUnsafe(
      server.buildRouterOn(clock).flatMap { router =>
        StreamableHttpHandler.make(router, server.settings, summon[TransportBackend].randomId())
      }
    )

  private val base = List(
    "accept" -> "application/json, text/event-stream",
    "content-type" -> "application/json"
  )

  private def request(method: String, headers: List[(String, String)], body: String): HttpRequest =
    val all = base ++ headers
    def first(name: String): Option[String] =
      all.collectFirst { case (n, v) if n.equalsIgnoreCase(name) => v }
    HttpRequest(method, "/mcp", first, first, ZIO.succeed(body))

  private def post(headers: List[(String, String)], body: String): HttpRequest =
    request("POST", headers, body)

  private def legacyCall(id: Int, tool: String): String =
    s"""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$tool","arguments":{},"_meta":{"progressToken":"p$id"}}}"""

  private def modernCall(id: Int, tool: String): String =
    s"""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$tool","arguments":{},"_meta":{"progressToken":"p$id","io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}"""

  private def modernHeaders(tool: String): List[(String, String)] =
    List("mcp-protocol-version" -> "2026-07-28", "mcp-method" -> "tools/call", "mcp-name" -> tool)

  /** The events of `reply` in the order a backend writes them: a stream's opening event first
    * (`HttpReply.Sse.opening`, written ahead of the frames and of any heartbeat), then its frames.
    * A streamed reply can be read once.
    */
  private def events(reply: HttpReply): List[SseFrame] =
    reply match
      case sse: HttpReply.Sse => sse.opening.toList ++ runUnsafe(sse.frames.runCollect).toList
      case HttpReply.SseComplete(_, frame) => List(frame)
      case other => fail(s"expected an SSE reply, got $other")

  /** The body a backend writes for `reply`. */
  private def wire(reply: HttpReply): String = events(reply).map(_.encode).mkString

  private def streamed(reply: HttpReply): HttpReply.Sse =
    reply match
      case sse: HttpReply.Sse => sse
      case other => fail(s"expected a streamed SSE reply, got $other")

  /** `initialize`, returning its reply and the session it minted (already initialized). */
  private def initialize(handler: StreamableHttpHandler[Any]): (HttpReply, String) =
    val init = runUnsafe(handler.post(post(Nil, InitFrame)))
    init.status shouldBe 200
    val sid = init.headers
      .collectFirst { case (name, value) if name.equalsIgnoreCase("mcp-session-id") => value }
      .getOrElse(fail("initialize did not mint a session"))
    runUnsafe(
      handler.post(
        post(
          List("mcp-session-id" -> sid),
          """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
        )
      )
    ).status shouldBe 202
    (init, sid)

  private def session(sid: String): List[(String, String)] = List("mcp-session-id" -> sid)

  private val retrying = McpServerSettings(sseRetryInterval = Some(java.time.Duration.ofMillis(2500)))

  // --- the frame --------------------------------------------------------------------------------

  test("SseFrame: `retry` is the first line of an event, and an event of its own when alone") {
    SseFrame.retry(2500).encode shouldBe "retry: 2500\n\n"
    SseFrame(Some("message"), "{}", Some(0L)).encode shouldBe "retry: 0\nevent: message\ndata: {}\n\n"
    // Without it, the encoding is what it was.
    SseFrame(Some("message"), "{}").encode shouldBe "event: message\ndata: {}\n\n"
    SseFrame.Ping.encode shouldBe "event: ping\n\n"
  }

  // --- the default ------------------------------------------------------------------------------

  test("by default no legacy reply carries a retry field, fixed-length or streamed") {
    val handler = makeHandler(McpServerSettings())
    val (init, sid) = initialize(handler)
    val quick = runUnsafe(handler.post(post(session(sid), legacyCall(2, "echo"))))
    val stream = streamed(
      runUnsafe(handler.post(post(session(sid), legacyCall(3, "progress-then-done"))))
    )
    stream.opening shouldBe None
    for reply <- List(init, quick, stream) do
      val all = events(reply)
      all.foreach(_.retry shouldBe None)
      val body = all.map(_.encode).mkString
      body should startWith("event: message\ndata: ")
      body should not include "retry"
  }

  /** A session's GET stream on which nothing is ever pushed: its opening event, and every frame
    * it yields. The session is deleted before the stream is read, which ends it.
    */
  private def quietGetStream(
      handler: StreamableHttpHandler[Any],
      sid: String
  ): (Option[SseFrame], List[SseFrame]) =
    val sse = streamed(runUnsafe(handler.handle(request("GET", session(sid), ""))))
    runUnsafe(handler.handle(request("DELETE", session(sid), ""))).status shouldBe 200
    (sse.opening, runUnsafe(sse.frames.runCollect).toList)

  test("by default the GET stream opens with nothing") {
    val handler = makeHandler(McpServerSettings())
    val (_, sid) = initialize(handler)
    quietGetStream(handler, sid) shouldBe ((None, Nil))
  }

  // --- opted in: the legacy adapter ---------------------------------------------------------------

  test("initialize: the fixed-length reply's one event begins with the retry line") {
    val handler = makeHandler(retrying)
    val (init, _) = initialize(handler)
    init match
      case HttpReply.SseComplete(_, frame) =>
        frame.retry shouldBe Some(2500L)
        frame.event shouldBe Some("message")
        frame.data should include(""""protocolVersion":"2025-11-25"""")
        frame.encode should startWith("retry: 2500\nevent: message\ndata: {")
      case other => fail(s"expected a complete SSE reply, got $other")
  }

  test("a legacy call that answers at once: one event, beginning with the retry line") {
    val handler = makeHandler(retrying)
    val (_, sid) = initialize(handler)
    runUnsafe(handler.post(post(session(sid), legacyCall(4, "echo")))) match
      case HttpReply.SseComplete(_, frame) =>
        frame.encode should startWith("retry: 2500\nevent: message\ndata: {")
        frame.data should include("echo-done")
        // One event: the only blank line is the one that ends the body.
        frame.encode.indexOf("\n\n") shouldBe frame.encode.length - 2
      case other => fail(s"expected a complete SSE reply, got $other")
  }

  test("a legacy call that streams: the stream opens with an event that is only the retry line") {
    val handler = makeHandler(retrying)
    val (_, sid) = initialize(handler)
    val stream = streamed(
      runUnsafe(handler.post(post(session(sid), legacyCall(5, "progress-then-done"))))
    )
    stream.opening shouldBe Some(SseFrame.retry(2500))
    val all = events(stream)
    // The field is on the opening event and on nothing else.
    all.map(_.retry) shouldBe List(Some(2500L), None, None)
    all(1).data should include("notifications/progress")
    all(2).data should include("progress-done")
    all.map(_.encode).mkString should startWith("retry: 2500\n\nevent: message\ndata: {")
  }

  test("a silent legacy call: the retry event comes before the heartbeat that opened the stream") {
    val handler = makeHandler(
      retrying.copy(keepAliveInterval = Some(java.time.Duration.ofMillis(100)))
    )
    val (_, sid) = initialize(handler)
    val pending = runUnsafe(handler.post(post(session(sid), legacyCall(6, "silent"))).forkDaemon)
    // The tool is silent for 1.5 s; the stream opens at the first heartbeat.
    runUnsafe(clock.awaitSleeping(100.millis) *> clock.awaitSleeping(1500.millis))
    clock.advanceMs(100L)
    val stream = streamed(
      runUnsafe(
        pending.join.timeoutFail(new RuntimeException("the reply never came back"))(60.seconds)
      )
    )
    // The opening event is not a frame of the stream: a backend writes it ahead of the frames and
    // of its own heartbeat, so nothing can come before it.
    stream.opening shouldBe Some(SseFrame.retry(2500))
    val reading = runUnsafe(stream.frames.runCollect.forkDaemon)
    runUnsafe(clock.awaitSleeping(1500.millis))
    clock.advanceMs(1400L)
    val all = runUnsafe(
      reading.join.timeoutFail(new RuntimeException("the stream never ended"))(60.seconds)
    ).toList
    all.headOption shouldBe Some(SseFrame.Ping)
    all.last.data should include("silent-done")
    all.foreach(_.retry shouldBe None)
  }

  test("the GET stream opens with the retry event, before anything is pushed") {
    val handler = makeHandler(retrying)
    val (_, sid) = initialize(handler)
    // Nothing is ever pushed on this session: the announcement does not wait for a message.
    quietGetStream(handler, sid) shouldBe ((Some(SseFrame.retry(2500)), Nil))
  }

  test("zero is sent as `retry: 0`; a sub-millisecond interval is sent in whole milliseconds") {
    val zero = makeHandler(McpServerSettings(sseRetryInterval = Some(java.time.Duration.ZERO)))
    wire(initialize(zero)._1) should startWith("retry: 0\nevent: message\n")
    val nanos = makeHandler(
      McpServerSettings(sseRetryInterval = Some(java.time.Duration.ofNanos(1_999_999L)))
    )
    wire(initialize(nanos)._1) should startWith("retry: 1\nevent: message\n")
  }

  // --- opted in: where it must not appear ---------------------------------------------------------

  test("a 2026-07-28 reply never carries the retry field, fixed-length or streamed") {
    val handler = makeHandler(retrying)
    val quick = runUnsafe(handler.post(post(modernHeaders("echo"), modernCall(7, "echo"))))
    quick match
      case HttpReply.SseComplete(_, frame) => frame.retry shouldBe None
      case other => fail(s"expected a complete SSE reply, got $other")
    wire(quick) should startWith("event: message\ndata: ")
    val stream = streamed(
      runUnsafe(
        handler.post(post(modernHeaders("progress-then-done"), modernCall(8, "progress-then-done")))
      )
    )
    stream.opening shouldBe None
    val all = events(stream)
    all.size shouldBe 2
    all.foreach(_.retry shouldBe None)
    all.map(_.encode).mkString should not include "retry"
  }

  test("legacy stateless mode answers in JSON: there is no SSE body to carry the field") {
    val handler = makeHandler(retrying.copy(stateless = true))
    runUnsafe(handler.post(post(Nil, legacyCall(9, "echo")))) match
      case HttpReply.Json(200, body, _) =>
        body should include("echo-done")
        body should not include "retry"
      case other => fail(s"expected a JSON reply, got $other")
  }

  // --- the setting --------------------------------------------------------------------------------

  test("sseRetryInterval: unset by default, and a negative interval is refused at construction") {
    McpServerSettings().sseRetryInterval shouldBe None
    val refused = intercept[IllegalArgumentException] {
      McpServerSettings(sseRetryInterval = Some(java.time.Duration.ofMillis(-1)))
    }
    refused.getMessage should include("sseRetryInterval must be >= 0")
  }

object SseRetryHandlerTest:

  private val InitFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""

  object Tools:

    /** The clock the tools below pause on: the one of the handler the running test built. */
    val clock = new java.util.concurrent.atomic.AtomicReference(new ManualClock)

    @Tool(name = Some("echo"), description = Some("Answers at once"))
    def echo(): String = "echo-done"

    @Tool(name = Some("progress-then-done"), description = Some("Reports progress, then answers"))
    def progressThenDone(ctx: McpContext): ZIO[Any, Throwable, String] =
      ZIO.foreachDiscard(ctx.progressToken)(t => ctx.sendProgress(t, 0.5)).as("progress-done")

    @Tool(name = Some("silent"), description = Some("Says nothing for 1.5 s, then answers"))
    def silent(): ZIO[Any, Throwable, String] =
      ZIO.suspendSucceed(clock.get().sleep(1500.millis)).as("silent-done")
