package com.tjclp.fastmcp
package server.transport

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.http.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.RegistrationMacro.*
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.router.Session
import com.tjclp.fastmcp.server.transport.JvmTransportBackend.given

/** Integration tests for the native JVM HTTP transport.
  *
  * Requests are driven straight through the zio-http `Routes` via `runZIO` — no TCP port is opened,
  * so the suite is deterministic. Covers the streamable session lifecycle (mint → reuse → delete →
  * 404), the SSE channel, unknown-session handling, and the stateless single-shot path.
  */
class JvmHttpTransportTest extends AnyFunSuite with Matchers:

  object TestServer:
    @Tool(name = Some("add"), description = Some("Add two numbers"))
    def add(@Param("a") a: Int, @Param("b") b: Int): Int = a + b

    @Tool(name = Some("slow"), description = Some("Sleeps forever (cancellation target)"))
    def slow(): ZIO[Any, Throwable, String] = ZIO.sleep(30.seconds).as("done")

    @Tool(name = Some("needs-roots"), description = Some("Requires the roots capability"))
    def needsRoots(ctx: McpContext): ZIO[Any, Throwable, String] =
      ctx.listRoots().as("roots received")

    @Tool(name = Some("slow-progress"), description = Some("Reports progress, then sleeps"))
    def slowProgress(ctx: McpContext): ZIO[Any, Throwable, String] =
      // The early notification opens the SSE stream (modern POSTs hold the response until the
      // first queued message); the long sleep then leaves the stream quiet for keepalives.
      ZIO.foreachDiscard(ctx.progressToken)(t => ctx.sendProgress(t, 0.5)) *>
        ZIO.sleep(30.seconds).as("done")

  private val SessionIdHeader = "mcp-session-id"

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""
  private val callFrame =
    """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"add","arguments":{"a":40,"b":2}}}"""
  private val listFrame =
    """{"jsonrpc":"2.0","id":3,"method":"tools/list"}"""
  private val modernDiscoverFrame =
    """{"jsonrpc":"2.0","id":10,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{},"io.modelcontextprotocol/clientInfo":{"name":"http-test","version":"1.0"}}}}"""
  private val modernCallFrame =
    """{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"add","arguments":{"a":40,"b":2},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}"""

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  /** Build the HTTP routes for a one-tool server (fresh session store per call). */
  private def buildRoutes(
      stateless: Boolean,
      keepAlive: Option[java.time.Duration] = None,
      allowedHosts: Option[Set[String]] = None
  ): Routes[Any, Response] =
    val server = McpServer.typed[Any](
      "T",
      "0.1.0",
      McpServerSettings(
        stateless = stateless,
        keepAliveInterval = keepAlive,
        allowedHosts = allowedHosts
      )
    )
    val _ = server.scanAnnotations[TestServer.type]
    runUnsafe(
      server.buildRouter.flatMap(r =>
        JvmTransportBackend.httpRoutes(r, server.settings, ZEnvironment.empty)
      )
    )

  /** Run one request through the routes in-memory. `runZIO` needs a `Scope`; discharge it per
    * request — the responses here carry in-memory bodies (JSON / status-only / SSE headers).
    */
  private def run(routes: Routes[Any, Response], req: Request): Response =
    runUnsafe(ZIO.scoped(routes.runZIO(req)))

  private def post(routes: Routes[Any, Response], body: String, sid: Option[String]): Response =
    val base = Request.post(URL(Path.root / "mcp"), Body.fromString(body))
    val req = sid.fold(base)(s => base.addHeader(Header.Custom(SessionIdHeader, s)))
    run(routes, req)

  private def bodyOf(resp: Response): String = runUnsafe(resp.body.asString)

  private def modernPost(
      routes: Routes[Any, Response],
      body: String,
      method: String,
      name: Option[String] = None,
      sessionId: Option[String] = None
  ): Response =
    val base = Request
      .post(URL(Path.root / "mcp"), Body.fromString(body))
      .addHeader(Header.Custom("content-type", "application/json"))
      .addHeader(Header.Custom("accept", "application/json, text/event-stream"))
      .addHeader(Header.Custom("mcp-protocol-version", "2026-07-28"))
      .addHeader(Header.Custom("mcp-method", method))
    val named = name.fold(base)(value => base.addHeader(Header.Custom("mcp-name", value)))
    val request = sessionId.fold(named)(value =>
      named.addHeader(Header.Custom(SessionIdHeader, value))
    )
    run(routes, request)

  /** Initialize and return the minted session id, draining the SSE body first — a compliant
    * client awaits the initialize response; grabbing only the header races the pre-init gate.
    */
  private def initSid(routes: Routes[Any, Response]): String =
    val resp = post(routes, initFrame, None)
    val _ = bodyOf(resp)
    resp.rawHeader(SessionIdHeader).getOrElse(fail("no session id"))

  test("streamable: initialize mints a session id, reused for tools/call, then deleted") {
    val routes = buildRoutes(stateless = false)

    val initResp = post(routes, initFrame, None)
    initResp.status shouldBe Status.Ok
    val initBody = bodyOf(initResp)
    initBody should include("\"serverInfo\"")
    initBody should not include "logging" // #56: capabilities derived; no logging hook → no logging

    val sid = initResp
      .rawHeader(SessionIdHeader)
      .getOrElse(fail("initialize did not return an mcp-session-id header"))

    // Reuse the durable session for a tool call.
    bodyOf(post(routes, callFrame, Some(sid))) should include("42")

    // Terminate it.
    val delResp = run(
      routes,
      Request.delete(URL(Path.root / "mcp")).addHeader(Header.Custom(SessionIdHeader, sid))
    )
    delResp.status shouldBe Status.Ok

    // The id is now unknown.
    post(routes, listFrame, Some(sid)).status shouldBe Status.NotFound
  }

  test("streamable: GET opens an SSE channel for a known session") {
    val routes = buildRoutes(stateless = false)
    val sid = initSid(routes)

    // Don't read the SSE body (it blocks on the outbound queue) — assert status + content-type.
    val getResp =
      run(routes, Request.get(URL(Path.root / "mcp")).addHeader(Header.Custom(SessionIdHeader, sid)))
    getResp.status shouldBe Status.Ok
    getResp.rawHeader("content-type").getOrElse("") should include("text/event-stream")
  }

  test("streamable: modern GET and DELETE ignore a legacy session id") {
    val routes = buildRoutes(stateless = false)
    val sid = initSid(routes)
    val modernHeaders = List(
      Header.Custom(SessionIdHeader, sid),
      Header.Custom("mcp-protocol-version", "2026-07-28")
    )

    val getResp = run(
      routes,
      modernHeaders.foldLeft(Request.get(URL(Path.root / "mcp")))((req, header) =>
        req.addHeader(header)
      )
    )
    getResp.status shouldBe Status.MethodNotAllowed

    val deleteResp = run(
      routes,
      modernHeaders.foldLeft(Request.delete(URL(Path.root / "mcp")))((req, header) =>
        req.addHeader(header)
      )
    )
    deleteResp.status shouldBe Status.MethodNotAllowed
    bodyOf(post(routes, listFrame, Some(sid))) should include("tools")
  }

  test("streamable: unknown session id is rejected with 404") {
    val routes = buildRoutes(stateless = false)
    post(routes, listFrame, Some("does-not-exist")).status shouldBe Status.NotFound
  }

  test("streamable: headerless non-initialize POST is 400 and mints no session") {
    val routes = buildRoutes(stateless = false)
    val resp = post(routes, listFrame, None)
    resp.status shouldBe Status.BadRequest
    resp.rawHeader(SessionIdHeader) shouldBe None
    bodyOf(resp) should include(SessionIdHeader)
  }

  test("streamable: malformed JSON POST is 400 with a -32700 body and mints no session") {
    val routes = buildRoutes(stateless = false)
    val resp = post(routes, """{"jsonrpc":"2.0", broken""", None)
    resp.status shouldBe Status.BadRequest
    resp.rawHeader(SessionIdHeader) shouldBe None
    bodyOf(resp) should include("-32700")
  }

  test("stateless: malformed JSON POST is 400 with a -32700 body") {
    val routes = buildRoutes(stateless = true)
    val resp = post(routes, "not json", None)
    resp.status shouldBe Status.BadRequest
    bodyOf(resp) should include("-32700")
  }

  test("streamable: a second concurrent GET on the same session is rejected with 409") {
    val routes = buildRoutes(stateless = false)
    val sid = initSid(routes)
    val get = Request.get(URL(Path.root / "mcp")).addHeader(Header.Custom(SessionIdHeader, sid))
    run(routes, get).status shouldBe Status.Ok
    run(routes, get).status shouldBe Status.Conflict
  }

  test("streamable: notifications/cancelled ends the request's SSE stream without a reply") {
    val routes = buildRoutes(stateless = false)
    val sid = initSid(routes)
    val slowFrame =
      """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"slow","arguments":{}}}"""
    val cancelFrame =
      """{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":7}}"""

    val (cancelStatus, body) = runUnsafe(
      for
        // Drain the slow call's SSE body in a fiber — it only completes when the stream closes.
        bodyFiber <- ZIO
          .scoped(
            routes.runZIO(
              Request
                .post(URL(Path.root / "mcp"), Body.fromString(slowFrame))
                .addHeader(Header.Custom(SessionIdHeader, sid))
            )
          )
          .flatMap(_.body.asString)
          .fork
        _ <- ZIO.sleep(300.millis) // let the dispatch start
        cancelResp <- ZIO.scoped(
          routes.runZIO(
            Request
              .post(URL(Path.root / "mcp"), Body.fromString(cancelFrame))
              .addHeader(Header.Custom(SessionIdHeader, sid))
          )
        )
        body <- bodyFiber.join
          .timeoutFail(new RuntimeException("cancelled stream never closed"))(10.seconds)
      yield (cancelResp.status, body)
    )
    cancelStatus shouldBe Status.Accepted
    body should not include "done"
    body should not include "$fastmcp/internal/close"
  }

  test("cancelInflight ignores the initialize request (spec: MUST NOT be cancelled)") {
    runUnsafe(
      for
        session <- Session.make("no-cancel-init")
        fiber <- ZIO.never.fork
        _ <- session.trackInflight(
          com.tjclp.fastmcp.jsonrpc.RequestId.NumId(1),
          "initialize",
          fiber
        )
        _ <- session.cancelInflight(com.tjclp.fastmcp.jsonrpc.RequestId.NumId(1))
        poll <- fiber.poll
      yield poll shouldBe None // still running — the cancel was ignored
    )
  }

  test("streamable POST requires Accept to allow text/event-stream too (406 otherwise)") {
    val routes = buildRoutes(stateless = false)
    val req = Request
      .post(URL(Path.root / "mcp"), Body.fromString(initFrame))
      .addHeader(Header.Custom("accept", "application/json"))
    run(routes, req).status shouldBe Status.NotAcceptable

    // Stateless answers plain JSON, so json-only Accept stays fine there.
    val statelessRoutes = buildRoutes(stateless = true)
    run(statelessRoutes, req).status shouldBe Status.Ok
  }

  test("keepalive pings flow on a quiet GET SSE stream when configured") {
    val routes =
      buildRoutes(stateless = false, keepAlive = Some(java.time.Duration.ofMillis(100)))
    val sid = initSid(routes)
    val getResp = run(
      routes,
      Request.get(URL(Path.root / "mcp")).addHeader(Header.Custom(SessionIdHeader, sid))
    )
    val bytes = runUnsafe(
      getResp.body.asStream
        .take(64)
        .runCollect
        .timeoutFail(new RuntimeException("no keepalive emitted"))(10.seconds)
    )
    new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8) should include("ping")
  }

  test("stateless modern POST SSE emits keepalive pings when configured") {
    val routes =
      buildRoutes(stateless = true, keepAlive = Some(java.time.Duration.ofMillis(100)))
    val frame =
      """{"jsonrpc":"2.0","id":30,"method":"tools/call","params":{"name":"slow-progress","arguments":{},"_meta":{"progressToken":"kp","io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}"""
    val resp = modernPost(routes, frame, "tools/call", Some("slow-progress"))
    resp.status shouldBe Status.Ok
    val seen = runUnsafe(
      resp.body.asStream
        .via(zio.stream.ZPipeline.utf8Decode)
        .scan("")(_ + _)
        .takeUntil(_.contains("ping"))
        .runLast
        .timeoutFail(new RuntimeException("no keepalive on the modern POST SSE"))(10.seconds)
    )
    seen.getOrElse("") should include("ping")
  }

  test("requests before initialize are rejected with -32600; ping is exempt") {
    val server = McpServer.typed[Any]("Gate", "0.1.0", McpServerSettings())
    val _ = server.scanAnnotations[TestServer.type]
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("gate"))

    val early = runUnsafe(MessageLoop.handleFrame(router, session, listFrame))
      .getOrElse(fail("no reply"))
    early should include("-32600")
    early should include("initialize")

    val ping = runUnsafe(
      MessageLoop.handleFrame(router, session, """{"jsonrpc":"2.0","id":9,"method":"ping"}""")
    ).getOrElse(fail("no ping reply"))
    ping should include("\"result\"")

    runUnsafe(MessageLoop.handleFrame(router, session, initFrame))
    val after = runUnsafe(MessageLoop.handleFrame(router, session, listFrame))
      .getOrElse(fail("no reply after init"))
    after should include("\"tools\"")
  }

  test("legacy initialize with an unknown version negotiates to the latest legacy revision") {
    val server = McpServer.typed[Any]("Neg", "0.1.0", McpServerSettings())
    val _ = server.scanAnnotations[TestServer.type]
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("neg"))
    val unknownVersionInit =
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"1999-01-01","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""
    val reply = runUnsafe(MessageLoop.handleFrame(router, session, unknownVersionInit))
      .getOrElse(fail("no reply"))
    reply should include(s""""protocolVersion":"${Protocol.LegacyProtocolVersions.head}"""")
  }

  test("HostGuard rejects foreign Host/Origin with 403 at the route level") {
    val routes =
      buildRoutes(stateless = false, allowedHosts = Some(Set("localhost", "127.0.0.1")))
    val evilPost = Request
      .post(URL(Path.root / "mcp"), Body.fromString(initFrame))
      .addHeader(Header.Custom("host", "evil.example.com"))
    run(routes, evilPost).status shouldBe Status.Forbidden

    val evilGet = Request
      .get(URL(Path.root / "mcp"))
      .addHeader(Header.Custom("host", "evil.example.com"))
      .addHeader(Header.Custom(SessionIdHeader, "whatever"))
    run(routes, evilGet).status shouldBe Status.Forbidden

    // Allowed host proceeds to normal processing (mints a session on initialize).
    val okPost = Request
      .post(URL(Path.root / "mcp"), Body.fromString(initFrame))
      .addHeader(Header.Custom("host", "127.0.0.1:8000"))
    run(routes, okPost).status shouldBe Status.Ok
  }

  test("idle streamable sessions are swept; live-GET sessions are exempt") {
    val settings = McpServerSettings(sessionIdleTimeout = Some(java.time.Duration.ofMillis(200)))
    runUnsafe(
      for
        idle <- Session.make("idle")
        live <- Session.make("live")
        _ <- live.tryAcquireGet
        store <- Ref.make(Map("idle" -> idle, "live" -> live))
        sweeper <- JvmTransportBackend.evictIdleSessions(store, settings).fork
        _ <- (ZIO.sleep(50.millis) *> store.get)
          .map(m => !m.contains("idle") && m.contains("live"))
          .repeatUntil(identity)
          .timeoutFail(new RuntimeException("sweeper did not evict the idle session"))(10.seconds)
        shutdown <- idle.outbound.isShutdown
        _ <- sweeper.interrupt
      yield shutdown shouldBe true
    )
  }

  test("stateless: a single POST initialize returns capabilities without logging") {
    val routes = buildRoutes(stateless = true)
    val resp = post(routes, initFrame, None)
    resp.status shouldBe Status.Ok
    val body = bodyOf(resp)
    body should include("\"capabilities\"")
    body should not include "logging"
  }

  test("stateless: GET is method-not-allowed (no SSE channel)") {
    val routes = buildRoutes(stateless = true)
    run(routes, Request.get(URL(Path.root / "mcp"))).status shouldBe Status.MethodNotAllowed
  }

  test("POST with an Accept that excludes application/json is rejected (406)") {
    val routes = buildRoutes(stateless = true)
    val req = Request
      .post(URL(Path.root / "mcp"), Body.fromString(initFrame))
      .addHeader(Header.Custom("accept", "text/plain"))
    run(routes, req).status shouldBe Status.NotAcceptable
  }

  test("modern POST is stateless, typed, and ignores legacy session headers") {
    val routes = buildRoutes(stateless = false)
    val response = modernPost(
      routes,
      modernDiscoverFrame,
      "server/discover",
      sessionId = Some("legacy-session-must-be-ignored")
    )
    response.status shouldBe Status.Ok
    response.rawHeader(SessionIdHeader) shouldBe None
    response.rawHeader("x-accel-buffering") shouldBe Some("no")
    val body = bodyOf(response)
    body should include(""""resultType":"complete"""")
    body should include(""""supportedVersions":["2026-07-28"""")
  }

  test("modern POST requires matching Mcp-Method and Mcp-Name headers") {
    val routes = buildRoutes(stateless = false)
    val missingMethod = Request
      .post(URL(Path.root / "mcp"), Body.fromString(modernDiscoverFrame))
      .addHeader(Header.Custom("content-type", "application/json"))
      .addHeader(Header.Custom("accept", "application/json, text/event-stream"))
      .addHeader(Header.Custom("mcp-protocol-version", "2026-07-28"))
    val missingResponse = run(routes, missingMethod)
    missingResponse.status shouldBe Status.BadRequest
    bodyOf(missingResponse) should include("-32020")

    val nameMismatch = modernPost(routes, modernCallFrame, "tools/call", Some("subtract"))
    nameMismatch.status shouldBe Status.BadRequest
    bodyOf(nameMismatch) should include("-32020")

    val valid = modernPost(routes, modernCallFrame, "tools/call", Some("add"))
    valid.status shouldBe Status.Ok
    bodyOf(valid) should include("42")
  }

  test("streamable endpoint returns 405 for a modern-style GET without a legacy session") {
    val routes = buildRoutes(stateless = false)
    run(routes, Request.get(URL(Path.root / "mcp"))).status shouldBe Status.MethodNotAllowed
  }

  test("modern capability failures use HTTP 400 with -32021") {
    val routes = buildRoutes(stateless = false)
    val frame =
      """{"jsonrpc":"2.0","id":12,"method":"tools/call","params":{"name":"needs-roots","arguments":{},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}"""
    val response = modernPost(routes, frame, "tools/call", Some("needs-roots"))
    response.status shouldBe Status.BadRequest
    bodyOf(response) should include("-32021")
  }

  test("POST with an unsupported mcp-protocol-version is rejected (400)") {
    val routes = buildRoutes(stateless = true)
    val frame =
      """{"jsonrpc":"2.0","id":1,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2099-01-01","io.modelcontextprotocol/clientCapabilities":{}}}}"""
    val req = Request
      .post(URL(Path.root / "mcp"), Body.fromString(frame))
      .addHeader(Header.Custom("content-type", "application/json"))
      .addHeader(Header.Custom("accept", "application/json, text/event-stream"))
      .addHeader(Header.Custom("mcp-protocol-version", "2099-01-01"))
      .addHeader(Header.Custom("mcp-method", "server/discover"))
    val response = run(routes, req)
    response.status shouldBe Status.BadRequest
    bodyOf(response) should include("-32022")
  }
