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

    @Tool(name = Some("boom"), description = Some("Dies with a defect (error-boundary target)"))
    def boom(): ZIO[Any, Throwable, String] =
      ZIO.succeed(throw new IllegalStateException("boom: secret handler state"))

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
      allowedHosts: Option[Set[String]] = None,
      allowedOrigins: Option[Set[String]] = None,
      maxRequestBodyBytes: Int = 1024 * 1024,
      maxSessions: Option[Int] = Some(1000)
  ): Routes[Any, Response] =
    val server = McpServer.typed[Any](
      "T",
      "0.1.0",
      McpServerSettings(
        stateless = stateless,
        keepAliveInterval = keepAlive,
        allowedHosts = allowedHosts,
        allowedOrigins = allowedOrigins,
        maxRequestBodyBytes = maxRequestBodyBytes,
        maxSessions = maxSessions
      )
    )
    val _ = server.scanAnnotations[TestServer.type]
    runUnsafe(
      server.buildRouter.flatMap(r =>
        JvmHttpBackend.httpRoutes(r, server.settings, ZEnvironment.empty)
      )
    )

  /** Run one request through the routes in-memory. `runZIO` needs a `Scope`; discharge it per
    * request — the responses here carry in-memory bodies (JSON / status-only / SSE headers).
    */
  private def run(routes: Routes[Any, Response], req: Request): Response =
    runUnsafe(ZIO.scoped(routes.runZIO(req)))

  /** A legacy POST as a compliant client sends it: JSON media type and an Accept covering both the
    * JSON and SSE reply shapes (every POST needs `Content-Type: application/json` — 415 otherwise).
    */
  private def post(routes: Routes[Any, Response], body: String, sid: Option[String]): Response =
    val base = Request
      .post(URL(Path.root / "mcp"), Body.fromString(body))
      .addHeader(Header.Custom("content-type", "application/json"))
      .addHeader(Header.Custom("accept", "application/json, text/event-stream"))
    val req = sid.fold(base)(s => base.addHeader(Header.Custom(SessionIdHeader, s)))
    run(routes, req)

  /** A raw POST with exactly the given headers — for the transport-gate tests. */
  private def rawPost(
      routes: Routes[Any, Response],
      body: String,
      headers: (String, String)*
  ): Response =
    val req = headers.foldLeft(Request.post(URL(Path.root / "mcp"), Body.fromString(body))) {
      case (r, (k, v)) => r.addHeader(Header.Custom(k, v))
    }
    run(routes, req)

  private val JsonHeaders = List(
    "content-type" -> "application/json",
    "accept" -> "application/json, text/event-stream"
  )

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
                .addHeader(Header.Custom("content-type", "application/json"))
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
              .addHeader(Header.Custom("content-type", "application/json"))
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
      .addHeader(Header.Custom("content-type", "application/json"))
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

  test("POST with a bogus mcp-protocol-version header and a legacy body answers -32022") {
    val routes = buildRoutes(stateless = true)
    // No _meta in the body: only the unknown header routes this to the modern path, so the
    // version must be judged before _meta decoding or the client gets a misleading -32602.
    val req = Request
      .post(URL(Path.root / "mcp"), Body.fromString(listFrame))
      .addHeader(Header.Custom("content-type", "application/json"))
      .addHeader(Header.Custom("accept", "application/json, text/event-stream"))
      .addHeader(Header.Custom("mcp-protocol-version", "2099-01-01"))
      .addHeader(Header.Custom("mcp-method", "tools/list"))
    val resp = run(routes, req)
    resp.status shouldBe Status.BadRequest
    val body = bodyOf(resp)
    body should include(""""code":-32022""")
    body should include(""""supported":["2026-07-28"]""")
    body should include(""""requested":"2099-01-01"""")
    body should not include "-32602"
    body should not include "_meta"
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
      .addHeader(Header.Custom("content-type", "application/json"))
      .addHeader(Header.Custom("host", "evil.example.com"))
    run(routes, evilPost).status shouldBe Status.Forbidden

    val evilGet = Request
      .get(URL(Path.root / "mcp"))
      .addHeader(Header.Custom("host", "evil.example.com"))
      .addHeader(Header.Custom(SessionIdHeader, "whatever"))
    run(routes, evilGet).status shouldBe Status.Forbidden

    val evilDelete = Request
      .delete(URL(Path.root / "mcp"))
      .addHeader(Header.Custom("host", "evil.example.com"))
      .addHeader(Header.Custom(SessionIdHeader, "whatever"))
    run(routes, evilDelete).status shouldBe Status.Forbidden

    // Allowed host proceeds to normal processing (mints a session on initialize).
    val okPost = Request
      .post(URL(Path.root / "mcp"), Body.fromString(initFrame))
      .addHeader(Header.Custom("content-type", "application/json"))
      .addHeader(Header.Custom("host", "127.0.0.1:8000"))
    run(routes, okPost).status shouldBe Status.Ok
  }

  // ---- F5 §1: Origin is a full origin, matched against the request's Host authority ----

  test("Origin route table: cross-port / cross-scheme / null / foreign origins are 403, same authority 200") {
    val routes =
      buildRoutes(stateless = true, allowedHosts = Some(Set("localhost", "127.0.0.1")))
    def withOrigin(origin: String): Response =
      rawPost(
        routes,
        listFrame,
        ("host" -> "localhost:8000") :: ("origin" -> origin) :: JsonHeaders*
      )
    for refused <- List(
        "http://localhost:3000",
        "https://localhost",
        "http://127.0.0.1:1",
        "null",
        "",
        "http://evil.example.com",
        "http://localhost:99999",
        "http://localhost:",
        "http://localhost:abc",
        "http://localhost:0",
        "http://localhost:8000/x",
        "http://user@localhost:8000",
        "ftp://localhost:8000",
        "http://127.0.0.1:8000" // listed hostname, same port, but not the request's authority
      )
    do
      withClue(s"Origin: $refused") {
        val resp = withOrigin(refused)
        resp.status shouldBe Status.Forbidden
        bodyOf(resp) should include("-32000")
        resp.rawHeader(SessionIdHeader) shouldBe None
      }
    withOrigin("http://localhost:8000").status shouldBe Status.Ok
    withOrigin("HTTP://LocalHost:8000").status shouldBe Status.Ok
    withOrigin("https://localhost:8000").status shouldBe Status.Ok // scheme not compared (documented)
    // No Origin at all: non-browser client, still fine.
    rawPost(routes, listFrame, ("host" -> "localhost:8000") :: JsonHeaders*).status shouldBe Status.Ok
  }

  test("allowedOrigins admits an explicitly listed origin that is not the request's authority") {
    val routes = buildRoutes(
      stateless = true,
      allowedHosts = Some(Set("localhost", "127.0.0.1")),
      allowedOrigins = Some(Set("http://localhost:3000", "https://app.example.com"))
    )
    def withOrigin(origin: String): Response =
      rawPost(
        routes,
        listFrame,
        ("host" -> "localhost:8000") :: ("origin" -> origin) :: JsonHeaders*
      )
    withOrigin("http://localhost:3000").status shouldBe Status.Ok
    withOrigin("https://app.example.com:443").status shouldBe Status.Ok
    withOrigin("http://localhost:3001").status shouldBe Status.Forbidden
    withOrigin("http://app.example.com").status shouldBe Status.Forbidden
  }

  test("serveHttp refuses to start on an unparseable allowedOrigins entry") {
    val server = McpServer.typed[Any](
      "BadOrigins",
      "0.1.0",
      McpServerSettings(allowedOrigins = Some(Set("app.example.com")))
    )
    val outcome = runUnsafe(
      server.buildRouter.flatMap(r => JvmHttpBackend.serveHttp(r, server.settings)).either
    )
    outcome.isLeft shouldBe true
    outcome.swap.map(_.getMessage).getOrElse("") should include("allowedOrigins")
  }

  // ---- F5 §2: every POST needs Content-Type: application/json, before body read or minting ----

  test("415 for a legacy POST with text/plain or no Content-Type, on stateless and streamable, no session minted") {
    for stateless <- List(true, false) do
      val routes = buildRoutes(stateless = stateless)
      withClue(s"stateless=$stateless text/plain") {
        val resp = rawPost(
          routes,
          initFrame,
          "content-type" -> "text/plain",
          "accept" -> "application/json, text/event-stream"
        )
        resp.status.code shouldBe 415
        bodyOf(resp) should include("-32000")
        bodyOf(resp) should include("application/json")
        resp.rawHeader(SessionIdHeader) shouldBe None
      }
      withClue(s"stateless=$stateless absent") {
        val resp = rawPost(routes, initFrame, "accept" -> "application/json, text/event-stream")
        resp.status.code shouldBe 415
        resp.rawHeader(SessionIdHeader) shouldBe None
      }
      withClue(s"stateless=$stateless tools/call text/plain") {
        val resp = rawPost(routes, callFrame, "content-type" -> "text/plain")
        resp.status.code shouldBe 415
        bodyOf(resp) should not include "42"
      }
    // A CORS-simple no-Content-Type initialize on the streamable route did not mint anything: a
    // guessed follow-up session id is unknown.
    val streamable = buildRoutes(stateless = false)
    val _ = rawPost(streamable, initFrame)
    post(streamable, listFrame, Some("guessed-session-id")).status shouldBe Status.NotFound
  }

  test("application/json with a charset parameter is accepted; JSON-ish other types are not") {
    val routes = buildRoutes(stateless = true)
    rawPost(routes, listFrame, "content-type" -> "application/json; charset=utf-8").status shouldBe
      Status.Ok
    rawPost(routes, listFrame, "content-type" -> "application/json-patch+json").status.code shouldBe
      415
    rawPost(routes, listFrame, "content-type" -> "application/json; charset=utf-16").status.code shouldBe
      415
  }

  // ---- F1 §2: operator-configurable body cap, 413 before parsing ----

  test("413 when the declared Content-Length exceeds maxRequestBodyBytes (before the body is read)") {
    val routes = buildRoutes(stateless = false, maxRequestBodyBytes = 64)
    val resp = rawPost(routes, initFrame, ("content-length" -> "200") :: JsonHeaders*)
    resp.status.code shouldBe 413
    val body = bodyOf(resp)
    body should include("-32000")
    body should include("64 bytes")
    resp.rawHeader(SessionIdHeader) shouldBe None
  }

  test("413 for an oversized NON-JSON body: the cap fires before parseFrame (not -32700)") {
    for stateless <- List(true, false) do
      val routes = buildRoutes(stateless = stateless, maxRequestBodyBytes = 64)
      val resp = rawPost(routes, "x" * 200, JsonHeaders*)
      withClue(s"stateless=$stateless") {
        resp.status.code shouldBe 413
        val body = bodyOf(resp)
        body should include("-32000")
        body should not include "-32700"
        resp.rawHeader(SessionIdHeader) shouldBe None
      }
    // At or under the cap the request proceeds normally.
    val ok = buildRoutes(stateless = true, maxRequestBodyBytes = listFrame.length)
    post(ok, listFrame, None).status shouldBe Status.Ok
  }

  // ---- F12: bounded legacy session store ----

  test("maxSessions: at the cap the longest-idle session without a live GET is evicted, not the newcomer") {
    val routes = buildRoutes(stateless = false, maxSessions = Some(2))
    val sid1 = initSid(routes)
    Thread.sleep(5)
    val sid2 = initSid(routes)
    Thread.sleep(5)
    // Touch sid2 so sid1 is unambiguously the longest-idle session.
    post(routes, listFrame, Some(sid2)).status shouldBe Status.Ok

    val third = post(routes, initFrame, None)
    third.status shouldBe Status.Ok
    val sid3 = third.rawHeader(SessionIdHeader).getOrElse(fail("third initialize minted nothing"))
    val _ = bodyOf(third)

    post(routes, listFrame, Some(sid1)).status shouldBe Status.NotFound // evicted
    post(routes, listFrame, Some(sid2)).status shouldBe Status.Ok
    post(routes, listFrame, Some(sid3)).status shouldBe Status.Ok

    // DELETE frees a slot; the next initialize is admitted without evicting.
    run(
      routes,
      Request.delete(URL(Path.root / "mcp")).addHeader(Header.Custom(SessionIdHeader, sid3))
    ).status shouldBe Status.Ok
    val fourth = initSid(routes)
    post(routes, listFrame, Some(sid2)).status shouldBe Status.Ok
    post(routes, listFrame, Some(fourth)).status shouldBe Status.Ok
  }

  test("maxSessions: 503 only when every stored session holds a live GET stream") {
    val routes = buildRoutes(stateless = false, maxSessions = Some(2))
    val sid1 = initSid(routes)
    val sid2 = initSid(routes)
    for sid <- List(sid1, sid2) do
      // Open (and never drain) the GET SSE channel: the session now has a live GET.
      run(routes, Request.get(URL(Path.root / "mcp")).addHeader(Header.Custom(SessionIdHeader, sid)))
        .status shouldBe Status.Ok

    val refused = post(routes, initFrame, None)
    refused.status.code shouldBe 503
    val body = bodyOf(refused)
    body should include("-32000")
    body should include("Session limit reached")
    refused.rawHeader(SessionIdHeader) shouldBe None
    // Neither stored session was touched.
    post(routes, listFrame, Some(sid1)).status shouldBe Status.Ok
    post(routes, listFrame, Some(sid2)).status shouldBe Status.Ok
  }

  test("maxSessions: concurrent header-less initializes at the cap all admit while idle sessions exist") {
    val routes = buildRoutes(stateless = false, maxSessions = Some(2))
    val idle = List(initSid(routes), initSid(routes))
    val initReq = Request
      .post(URL(Path.root / "mcp"), Body.fromString(initFrame))
      .addHeader(Header.Custom("content-type", "application/json"))
      .addHeader(Header.Custom("accept", "application/json, text/event-stream"))
    // 16 initializes race for the two slots: admission + eviction are one serialised modifyZIO, so
    // nobody sees a stale snapshot and nobody is refused while an idle (GET-less) session exists.
    val responses = runUnsafe(
      ZIO.foreachPar((1 to 16).toList)(_ => ZIO.scoped(routes.runZIO(initReq)))
    )
    responses.map(_.status.code).distinct shouldBe List(200)
    val minted = responses.map(r => r.rawHeader(SessionIdHeader).getOrElse(fail("no session id")))
    minted.distinct.size shouldBe 16
    // The cap held: exactly two sessions survive (the idle pair was evicted first).
    val alive = (idle ++ minted).count(sid => post(routes, listFrame, Some(sid)).status == Status.Ok)
    alive shouldBe 2
    idle.foreach(sid => post(routes, listFrame, Some(sid)).status shouldBe Status.NotFound)
  }

  test("maxSessions = None disables the cap") {
    val routes = buildRoutes(stateless = false, maxSessions = None)
    val sids = (1 to 5).map(_ => initSid(routes))
    sids.foreach(sid => post(routes, listFrame, Some(sid)).status shouldBe Status.Ok)
  }

  // ---- F10 §1: first-party error boundary ----

  test("guarded: a defect becomes a JSON-RPC 500 with a fixed message; interrupts pass through") {
    val dead = JvmHttpBackend.guarded[Any](ZIO.die(new IllegalStateException("boom: secret state")))
    val resp = runUnsafe(dead)
    resp.status shouldBe Status.InternalServerError
    resp.rawHeader("content-type").getOrElse("") should include("application/json")
    val body = bodyOf(resp)
    body should include("-32000")
    body should include("Internal server error")
    body should not include "boom"
    body should not include "IllegalStateException"
    body should not include "secret"

    // A client disconnect interrupts the handler fiber: zio-http keeps its own 408 path, so the
    // boundary must not swallow interrupts into a 500.
    val interrupted = runUnsafe(JvmHttpBackend.guarded[Any](ZIO.interrupt).exit)
    interrupted.isInterrupted shouldBe true

    val fine = runUnsafe(JvmHttpBackend.guarded[Any](ZIO.succeed(Response.status(Status.Accepted))))
    fine.status shouldBe Status.Accepted
  }

  test("a tool that dies is answered in-band by the router (JSON -32603), never a raw 500 or HTML") {
    // The router's dispatch awaits the handler fiber's Exit and maps defects to -32603, so the
    // transport boundary above is defence in depth behind it. Pin the contract at the route level.
    val routes = buildRoutes(stateless = true)
    val frame =
      """{"jsonrpc":"2.0","id":13,"method":"tools/call","params":{"name":"boom","arguments":{}}}"""
    val resp = post(routes, frame, None)
    resp.status shouldBe Status.Ok
    resp.rawHeader("content-type").getOrElse("") should include("application/json")
    val body = bodyOf(resp)
    body should include("\"jsonrpc\"")
    body should not include "<html"
    body should not include "\tat "
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
      .addHeader(Header.Custom("content-type", "application/json"))
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
