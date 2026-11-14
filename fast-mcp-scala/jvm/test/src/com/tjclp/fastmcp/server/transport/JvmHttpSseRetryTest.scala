package com.tjclp.fastmcp
package server.transport

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.http.*

import com.tjclp.fastmcp.{*, given}

/** `McpServerSettings.sseRetryInterval` as the zio-http backend writes it. What the setting means,
  * reply by reply, is `SseRetryHandlerTest` (the shared handler, JVM and Scala Native) and the
  * socket backend's wire is `SocketHttpBackendTest`; this pins the one thing that is zio-http's
  * own: a streamed reply is encoded by zio-http's `ServerSentEvent`, which cannot write an event
  * that is only a `retry` line, so the adapter writes that one event itself. The bytes must be the
  * socket backend's, and every other event must stay as zio-http encodes it.
  */
class JvmHttpSseRetryTest extends AnyFunSuite with Matchers:
  import JvmHttpSseRetryTest.*

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private def buildRoutes(settings: McpServerSettings): Routes[Any, Response] =
    val server = McpServer.typed[Any]("SseRetryJvm", "0.1.0", settings)
    val _ = server.scanAnnotations[Tools.type]
    runUnsafe(
      server.buildRouter.flatMap(r => JvmHttpBackend.httpRoutes(r, server.settings, ZEnvironment.empty))
    )

  private def run(routes: Routes[Any, Response], req: Request): Response =
    runUnsafe(ZIO.scoped(routes.runZIO(req)))

  private def post(
      routes: Routes[Any, Response],
      body: String,
      headers: (String, String)*
  ): Response =
    val base = Request
      .post(URL(Path.root / "mcp"), Body.fromString(body))
      .addHeader(Header.Custom("content-type", "application/json"))
      .addHeader(Header.Custom("accept", "application/json, text/event-stream"))
    run(routes, headers.foldLeft(base) { case (r, (k, v)) => r.addHeader(Header.Custom(k, v)) })

  private def bodyOf(resp: Response): String =
    runUnsafe(
      resp.body.asString.timeoutFail(new RuntimeException("the body never ended"))(60.seconds)
    )

  private def initialize(routes: Routes[Any, Response]): (String, String) =
    val resp = post(routes, InitFrame)
    val body = bodyOf(resp)
    val sid = resp.rawHeader("mcp-session-id").getOrElse(fail("no session id"))
    post(routes, """{"jsonrpc":"2.0","method":"notifications/initialized"}""", "mcp-session-id" -> sid)
      .status shouldBe Status.Accepted
    (body, sid)

  private val retrying = McpServerSettings(sseRetryInterval = Some(java.time.Duration.ofSeconds(3)))

  test("legacy replies begin with the retry line: fixed-length, streamed, and the GET stream") {
    val routes = buildRoutes(retrying)
    val (init, sid) = initialize(routes)
    init should startWith("retry: 3000\nevent: message\ndata: ")

    val quick = bodyOf(post(routes, call(2, "echo"), "mcp-session-id" -> sid))
    quick should startWith("retry: 3000\nevent: message\ndata: ")
    quick should include("echo-done")

    val streamed = bodyOf(post(routes, call(3, "progress-then-done"), "mcp-session-id" -> sid))
    streamed should startWith("retry: 3000\n\nevent: message\ndata: ")
    streamed should include("notifications/progress")
    streamed should include("progress-done")
    streamed.split("retry: ", -1).length shouldBe 2

    // The GET stream stays open after its opening event: read exactly that many bytes, then let go.
    val opening = "retry: 3000\n\n"
    getStreamHead(routes, sid, opening.length) shouldBe opening
  }

  /** The first `count` bytes of a session's GET stream, as text. */
  private def getStreamHead(routes: Routes[Any, Response], sid: String, count: Int): String =
    val get = run(
      routes,
      Request.get(URL(Path.root / "mcp")).addHeader(Header.Custom("mcp-session-id", sid))
    )
    get.status shouldBe Status.Ok
    val bytes = runUnsafe(
      get.body.asStream
        .take(count.toLong)
        .runCollect
        .timeoutFail(new RuntimeException(s"the GET stream sent fewer than $count bytes"))(60.seconds)
    )
    new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)

  test("everything after the opening event is byte for byte what the server sends without the option") {
    val plain = buildRoutes(McpServerSettings())
    val (_, plainSid) = initialize(plain)
    val routes = buildRoutes(retrying)
    val (_, sid) = initialize(routes)
    // A streamed reply: the same call on both servers.
    val expected = bodyOf(post(plain, call(7, "progress-then-done"), "mcp-session-id" -> plainSid))
    val streamed = bodyOf(post(routes, call(7, "progress-then-done"), "mcp-session-id" -> sid))
    streamed shouldBe "retry: 3000\n\n" + expected

    // The heartbeat: the first bytes of a quiet GET stream on both servers. Several rounds: the
    // order of the opening event and the first ping must not depend on a race.
    val beat = Some(java.time.Duration.ofMillis(50))
    val plainBeating = buildRoutes(McpServerSettings(keepAliveInterval = beat))
    val beating = buildRoutes(retrying.copy(keepAliveInterval = beat))
    val opening = "retry: 3000\n\n"
    val ping = getStreamHead(plainBeating, initialize(plainBeating)._2, count = 12)
    ping shouldBe "event: ping\n"
    // The first ping of a stream is due the moment it starts; the opening event still precedes it.
    (1 to 20).foreach { _ =>
      getStreamHead(beating, initialize(beating)._2, opening.length + ping.length) shouldBe
        opening + ping
    }
  }

  test("a 2026-07-28 reply carries no retry field; nor does any reply by default") {
    val modernCall =
      """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"echo","arguments":{},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}"""
    val modern = bodyOf(
      post(
        buildRoutes(retrying),
        modernCall,
        "mcp-protocol-version" -> "2026-07-28",
        "mcp-method" -> "tools/call",
        "mcp-name" -> "echo"
      )
    )
    modern should startWith("event: message\ndata: ")
    modern should not include "retry"

    val routes = buildRoutes(McpServerSettings())
    val (init, sid) = initialize(routes)
    init should startWith("event: message\ndata: ")
    val streamed = bodyOf(post(routes, call(5, "progress-then-done"), "mcp-session-id" -> sid))
    streamed should startWith("event: message\ndata: ")
    streamed should not include "retry"
  }

object JvmHttpSseRetryTest:

  private val InitFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""

  private def call(id: Int, tool: String): String =
    s"""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$tool","arguments":{},"_meta":{"progressToken":"p$id"}}}"""

  object Tools:

    @Tool(name = Some("echo"), description = Some("Answers at once"))
    def echo(): String = "echo-done"

    @Tool(name = Some("progress-then-done"), description = Some("Reports progress, then answers"))
    def progressThenDone(ctx: McpContext): ZIO[Any, Throwable, String] =
      ZIO.foreachDiscard(ctx.progressToken)(t => ctx.sendProgress(t, 0.5)).as("progress-done")
