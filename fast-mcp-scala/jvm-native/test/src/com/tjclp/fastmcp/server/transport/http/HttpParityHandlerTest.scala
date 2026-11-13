package com.tjclp.fastmcp
package server.transport.http

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.*

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.server.transport.TransportBackend

/** The refusal shapes every HTTP backend shares (GH #114), pinned on the shared handler through the
  * neutral model, on the JVM and on Scala Native: endpoint path matching, the method gate and its
  * order behind the Host/Origin guard, the `mcp-session-id` rules of GET and DELETE, `Accept`
  * parsing, and `data.supported` of `-32022`. The zio-http and socket backends render exactly these
  * replies; the Bun backend is held to the same table by `JsServerHttpTest`.
  */
class HttpParityHandlerTest extends AnyFunSuite with Matchers:

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""

  private val listFrame = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""

  // `server/discover`: a 2026-07-28 method every server has, whatever is registered.
  private val modernDiscoverFrame =
    """{"jsonrpc":"2.0","id":7,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{},"io.modelcontextprotocol/clientInfo":{"name":"t","version":"1.0"}}}}"""

  private def makeHandler(
      settings: McpServerSettings = McpServerSettings(),
      register: McpServer[Any] => ZIO[Any, Throwable, Any] = _ => ZIO.unit
  ) =
    val server = McpServer.typed[Any]("ParityT", "0.1.0", settings)
    val _ = runUnsafe(register(server))
    runUnsafe(
      server.buildRouter.flatMap { router =>
        StreamableHttpHandler.make(router, server.settings, summon[TransportBackend].randomId())
      }
    )

  private val postHeaders =
    Map("accept" -> "application/json, text/event-stream", "content-type" -> "application/json")

  private def request(
      method: String,
      headers: Map[String, String] = Map.empty,
      body: String = "",
      path: String = "/mcp"
  ): HttpRequest =
    val lookup: String => Option[String] = name => headers.get(name.toLowerCase)
    HttpRequest(method, path, lookup, lookup, ZIO.succeed(body))

  private def handle(handler: StreamableHttpHandler[Any], req: HttpRequest): HttpReply =
    val reply = runUnsafe(handler.handle(req))
    // `admit` is the same verdict taken on the head alone: whenever it answers, it answers the
    // same thing `handle` does (the socket backend relies on it to skip the body).
    handler.admit(req).foreach { early =>
      early.status shouldBe reply.status
      early.headers shouldBe reply.headers
    }
    reply

  private def bodyOf(reply: HttpReply): String =
    reply match
      case HttpReply.Json(_, body, _) => body
      case HttpReply.Empty(_, _) => ""
      case HttpReply.SseComplete(_, frame) => frame.encode
      case sse: HttpReply.Sse =>
        runUnsafe(sse.frames.runCollect.map(_.map(_.encode).mkString))

  private def headerOf(reply: HttpReply, name: String): Option[String] =
    reply.headers.collectFirst { case (n, v) if n.equalsIgnoreCase(name) => v }

  private def openSession(handler: StreamableHttpHandler[Any]): String =
    val init = handle(handler, request("POST", postHeaders, initFrame))
    init.status shouldBe 200
    val _ = bodyOf(init)
    headerOf(init, "mcp-session-id").getOrElse(fail("initialize did not mint a session"))

  // --- D3.16 / D4.4: endpoint path -------------------------------------------------------------

  test("the endpoint is an exact path: a trailing slash, another case or a longer path is 404") {
    val handler = makeHandler()
    handle(handler, request("POST", postHeaders, initFrame)).status shouldBe 200
    for path <- List("/mcp/", "/MCP", "/mcp/extra", "/api/mcp", "/xmcpx", "/", "//mcp") do
      for method <- List("POST", "GET", "DELETE", "PUT", "OPTIONS") do
        withClue(s"$method $path: ") {
          val reply = handle(handler, request(method, postHeaders, initFrame, path))
          reply shouldBe HttpReply.Empty(404)
        }
  }

  test("404 off the endpoint comes before the Host/Origin guard") {
    val handler = makeHandler()
    val evil = postHeaders + ("host" -> "evil.example.com")
    handle(handler, request("POST", evil, initFrame, "/other")) shouldBe HttpReply.Empty(404)
    handle(handler, request("PUT", evil, "", "/other")) shouldBe HttpReply.Empty(404)
    handle(handler, request("POST", evil, initFrame)).status shouldBe 403
  }

  test("a configured endpoint is matched the same way, with or without its leading slash") {
    for endpoint <- List("/api/v1/mcp", "api/v1/mcp") do
      val handler = makeHandler(McpServerSettings(httpEndpoint = endpoint))
      handler.endpoint shouldBe "/api/v1/mcp"
      handle(handler, request("POST", postHeaders, initFrame, "/api/v1/mcp")).status shouldBe 200
      handle(handler, request("POST", postHeaders, initFrame, "/mcp")) shouldBe HttpReply.Empty(404)
      handle(handler, request("POST", postHeaders, initFrame, "/api/v1/mcp/")) shouldBe
        HttpReply.Empty(404)
  }

  // --- C3.7: methods and gate order ------------------------------------------------------------

  test("PUT, PATCH, OPTIONS and TRACE on the endpoint are 405 with Allow and a JSON-RPC body") {
    val handler = makeHandler()
    for method <- List("PUT", "PATCH", "OPTIONS", "TRACE") do
      withClue(s"$method: ") {
        val reply = handle(handler, request(method))
        reply.status shouldBe 405
        headerOf(reply, "allow") shouldBe Some("POST, GET, DELETE")
        val body = bodyOf(reply)
        body should include(""""code":-32000""")
        body should include(s"Method $method is not allowed on the MCP endpoint")
        body should include("allowed: POST, GET, DELETE")
      }
  }

  test("HEAD on the endpoint is 405 with Allow and no body") {
    val handler = makeHandler()
    handle(handler, request("HEAD")) shouldBe
      HttpReply.Empty(405, List("allow" -> "POST, GET, DELETE"))
    // A refused Host is a bodiless 403 for HEAD too.
    handle(handler, request("HEAD", Map("host" -> "evil.example.com"))) shouldBe
      HttpReply.Empty(403)
  }

  test("every method meets the Host/Origin guard before it is judged: 403, not 405") {
    val evil = Map("host" -> "evil.example.com")
    for
      settings <- List(McpServerSettings(), McpServerSettings(stateless = true))
      method <- List("GET", "DELETE", "PUT", "PATCH", "OPTIONS")
    do
      withClue(s"$method stateless=${settings.stateless}: ") {
        val reply = handle(makeHandler(settings), request(method, evil))
        reply.status shouldBe 403
        bodyOf(reply) should include("Host/Origin not allowed")
      }
  }

  test("the stateless adapter serves POST only: GET and DELETE are 405 with Allow: POST") {
    val handler = makeHandler(McpServerSettings(stateless = true))
    val get = handle(handler, request("GET", Map("accept" -> "text/event-stream")))
    get.status shouldBe 405
    headerOf(get, "allow") shouldBe Some("POST")
    bodyOf(get) should include("GET is not supported: the stateless transport")
    val delete = handle(handler, request("DELETE", Map("mcp-session-id" -> "whatever")))
    delete.status shouldBe 405
    headerOf(delete, "allow") shouldBe Some("POST")
    bodyOf(delete) should include("DELETE is not supported: the stateless transport")
    // The entry points a routing backend calls directly give the same answers.
    runUnsafe(handler.get(request("GET"))).status shouldBe 405
    runUnsafe(handler.delete(request("DELETE"))).status shouldBe 405
    runUnsafe(handler.get(request("GET", Map("host" -> "evil.example.com")))).status shouldBe 403
  }

  test("disallowDelete: DELETE is 405 and Allow no longer lists it") {
    val handler = makeHandler(McpServerSettings(disallowDelete = true))
    val sid = openSession(handler)
    val delete = handle(handler, request("DELETE", Map("mcp-session-id" -> sid)))
    delete.status shouldBe 405
    headerOf(delete, "allow") shouldBe Some("POST, GET")
    bodyOf(delete) should include("DELETE is disabled on this server")
    runUnsafe(handler.session(sid)) shouldBe defined
  }

  test("a GET or DELETE that declares 2026-07-28 is 405 with Allow: POST") {
    val handler = makeHandler()
    val sid = openSession(handler)
    for method <- List("GET", "DELETE") do
      withClue(s"$method: ") {
        val reply = handle(
          handler,
          request(
            method,
            Map(
              "mcp-protocol-version" -> "2026-07-28",
              "mcp-session-id" -> sid,
              "accept" -> "text/event-stream"
            )
          )
        )
        reply.status shouldBe 405
        headerOf(reply, "allow") shouldBe Some("POST")
        bodyOf(reply) should include(s"$method is not used by MCP 2026-07-28")
      }
    runUnsafe(handler.session(sid)) shouldBe defined
  }

  // --- D3.14 / C3.6 / D4.3: mcp-session-id -----------------------------------------------------

  test("GET and DELETE without an mcp-session-id are 400, not 405") {
    val handler = makeHandler()
    for (method, headers) <- List(
        "GET" -> Map("accept" -> "text/event-stream"),
        "DELETE" -> Map.empty[String, String]
      )
    do
      withClue(s"$method: ") {
        val reply = handle(handler, request(method, headers))
        reply.status shouldBe 400
        bodyOf(reply) should include(""""code":-32000""")
        bodyOf(reply) should include("mcp-session-id header is required")
      }
  }

  test("an empty or blank mcp-session-id counts as absent: 400 on GET, DELETE and POST, never 404") {
    val handler = makeHandler()
    for empty <- List("", "   ") do
      for (method, headers, body) <- List(
          ("GET", Map("accept" -> "text/event-stream"), ""),
          ("DELETE", Map.empty[String, String], ""),
          ("POST", postHeaders, listFrame)
        )
      do
        withClue(s"$method '$empty': ") {
          val reply = handle(handler, request(method, headers + ("mcp-session-id" -> empty), body))
          reply.status shouldBe 400
          bodyOf(reply) should include("mcp-session-id header is required")
          bodyOf(reply) should not include "Session not found"
        }
  }

  test("an initialize with an empty mcp-session-id opens a session like a header-less one") {
    val handler = makeHandler()
    val init = handle(handler, request("POST", postHeaders + ("mcp-session-id" -> ""), initFrame))
    init.status shouldBe 200
    headerOf(init, "mcp-session-id") shouldBe defined
  }

  test("an unknown mcp-session-id is still 404 (-32001) on GET, DELETE and POST") {
    val handler = makeHandler()
    for (method, headers, body) <- List(
        ("GET", Map("accept" -> "text/event-stream"), ""),
        ("DELETE", Map.empty[String, String], ""),
        ("POST", postHeaders, listFrame)
      )
    do
      withClue(s"$method: ") {
        val reply = handle(handler, request(method, headers + ("mcp-session-id" -> "nope"), body))
        reply.status shouldBe 404
        bodyOf(reply) should include(""""code":-32001""")
        bodyOf(reply) should include("Session not found: nope")
      }
  }

  test("an mcp-session-id sent twice reaches the handler joined and names no session (404)") {
    val handler = makeHandler()
    val sid = openSession(handler)
    // What every backend's gate lookup yields for two field lines carrying the same id.
    val joined = s"$sid, $sid"
    val lookup: String => Option[String] = {
      case "mcp-session-id" => Some(joined)
      case _ => None
    }
    val first: String => Option[String] = {
      case "mcp-session-id" => Some(sid)
      case _ => None
    }
    val reply =
      runUnsafe(handler.handle(HttpRequest("DELETE", "/mcp", first, lookup, ZIO.succeed(""))))
    reply.status shouldBe 404
    runUnsafe(handler.session(sid)) shouldBe defined
  }

  test("GET checks run in order: version 400, then Accept 406, then the missing session 400") {
    val handler = makeHandler()
    val badVersion = handle(
      handler,
      request("GET", Map("mcp-protocol-version" -> "1999-01-01", "accept" -> "text/html"))
    )
    badVersion.status shouldBe 400
    bodyOf(badVersion) should include("Unsupported mcp-protocol-version header")
    val badAccept = handle(handler, request("GET", Map("accept" -> "text/html")))
    badAccept.status shouldBe 406
    bodyOf(badAccept) should include("Accept must allow text/event-stream")
  }

  // --- D3.19 / D3.20: Accept -------------------------------------------------------------------

  test("Accept is parsed, not searched: q=0 refuses on the legacy POST and GET gates") {
    val handler = makeHandler()
    val sid = openSession(handler)
    def post(accept: String) =
      handle(handler, request("POST", postHeaders + ("accept" -> accept), initFrame))
    post("*/*;q=0").status shouldBe 406
    bodyOf(post("*/*;q=0")) should include("Accept must allow application/json")
    post("application/json;q=0, text/event-stream").status shouldBe 406
    val noSse = post("application/json, text/event-stream;q=0")
    noSse.status shouldBe 406
    bodyOf(noSse) should include("Accept must allow text/event-stream")
    bodyOf(noSse) should include(""""code":-32000""")
    post("application/json-patch+json, text/event-stream").status shouldBe 406
    post("application/*, text/*").status shouldBe 200
    post("application/json;q=0.5, text/event-stream;q=0.1").status shouldBe 200

    def get(accept: String) =
      handle(handler, request("GET", Map("accept" -> accept, "mcp-session-id" -> sid)))
    get("*/*;q=0").status shouldBe 406
    get("text/event-stream;q=0, */*").status shouldBe 406
    get("text/event-stream-ish").status shouldBe 406
  }

  test("a 2026-07-28 POST has one 406: -32020 and one message, whatever is wrong with Accept") {
    for stateless <- List(false, true) do
      val handler = makeHandler(McpServerSettings(stateless = stateless))
      val modern = Map(
        "content-type" -> "application/json",
        "mcp-protocol-version" -> "2026-07-28",
        "mcp-method" -> "server/discover"
      )
      val ok = handle(
        handler,
        request("POST", modern + ("accept" -> "application/json, text/event-stream"), modernDiscoverFrame)
      )
      ok.status shouldBe 200
      val _ = bodyOf(ok)
      val refusals =
        for accept <- List(
            None,
            Some("application/json"),
            Some("text/event-stream"),
            Some("text/html"),
            Some("*/*;q=0"),
            Some("application/json, text/event-stream;q=0")
          )
        yield
          val reply = handle(handler, request("POST", modern ++ accept.map("accept" -> _), modernDiscoverFrame))
          withClue(s"stateless=$stateless accept=$accept: ") {
            reply.status shouldBe 406
            bodyOf(reply) should include(""""code":-32020""")
            bodyOf(reply) should include(
              "Accept must include application/json and text/event-stream"
            )
          }
          bodyOf(reply)
      refusals.distinct.size shouldBe 1
  }

  test("a request that is 2026-07-28 only by its body gets the same 406 code and message") {
    // No version header: the header gate cannot tell, so the lenient rule admits an absent Accept
    // and the body validation refuses it — with the code and message of the header gate.
    val handler = makeHandler(McpServerSettings(stateless = true))
    val reply = handle(
      handler,
      request("POST", Map("content-type" -> "application/json"), modernDiscoverFrame)
    )
    reply.status shouldBe 406
    bodyOf(reply) should include(""""code":-32020""")
    bodyOf(reply) should include("Accept must include application/json and text/event-stream")
  }

  // --- D3.15: -32022 data.supported ------------------------------------------------------------

  private val everyVersion =
    """"supported":["2026-07-28","2025-11-25","2025-06-18","2025-03-26","2024-11-05","2024-10-07"]"""

  test("-32022 for an unknown version lists every supported version, on requests and notifications") {
    val handler = makeHandler()
    val headers = postHeaders ++ Map("mcp-protocol-version" -> "2099-01-01", "mcp-method" -> "tools/list")
    val request2099 = handle(handler, request("POST", headers, listFrame))
    request2099.status shouldBe 400
    bodyOf(request2099) should include(""""code":-32022""")
    bodyOf(request2099) should include(everyVersion)
    bodyOf(request2099) should include(""""requested":"2099-01-01"""")
    val notification = handle(
      handler,
      request(
        "POST",
        headers + ("mcp-method" -> "notifications/initialized"),
        """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
      )
    )
    notification.status shouldBe 400
    bodyOf(notification) should include(""""code":-32022""")
    bodyOf(notification) should include(everyVersion)
  }

  test("-32022 for a legacy version in the 2026-07-28 request shape names 2026-07-28 only") {
    val handler = makeHandler()
    val legacyInModernShape = modernDiscoverFrame.replace(
      """"io.modelcontextprotocol/protocolVersion":"2026-07-28"""",
      """"io.modelcontextprotocol/protocolVersion":"2025-11-25""""
    )
    val reply = handle(
      handler,
      request(
        "POST",
        postHeaders ++ Map("mcp-protocol-version" -> "2025-11-25", "mcp-method" -> "server/discover"),
        legacyInModernShape
      )
    )
    reply.status shouldBe 400
    bodyOf(reply) should include(""""code":-32022""")
    bodyOf(reply) should include(""""supported":["2026-07-28"]""")
    bodyOf(reply) should include(""""requested":"2025-11-25"""")
  }

  // --- D6.19: a request that ends without a reply is logged ------------------------------------

  case class HoldArgs(note: Option[String] = None)
  given JsonDecoder[HoldArgs] = DeriveJsonDecoder.gen[HoldArgs]

  /** A tool that parks until it is interrupted, and says when it started. */
  private final class Held:
    val started: Promise[Nothing, Unit] = runUnsafe(Promise.make[Nothing, Unit])

    val tool: McpTool[HoldArgs, String] = McpTool
      .withSchema[HoldArgs, String](
        name = "hold",
        inputSchema =
          ToolInputSchema.unsafeFromJsonString("""{"type":"object","properties":{}}"""),
        description = Some("Parks until interrupted")
      )
      .contextual((_, _) => started.succeed(()) *> ZIO.never)

  private val holdFrame =
    """{"jsonrpc":"2.0","id":"client-chosen-id-7","method":"tools/call","params":{"name":"hold","arguments":{}}}"""

  /** Everything logged at WARN or above while `effect` and the fibers it forks run. */
  private final class Captured:
    private val seen =
      new java.util.concurrent.atomic.AtomicReference[List[(LogLevel, String)]](Nil)

    val logger: ZLogger[String, Unit] = new ZLogger[String, Unit]:
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
        val entry = (logLevel, message())
        val _ = seen.updateAndGet(entry :: _)

    def warnings: List[String] =
      seen.get().collect { case (level, message) if level >= LogLevel.Warning => message }

  /** Start a `hold` call on a fresh session and wait until its handler runs. Returns the session
    * id and the fiber that reads the call's reply to its end.
    */
  private def startHeldCall(
      handler: StreamableHttpHandler[Any],
      held: Held,
      captured: Captured
  ): (String, Fiber.Runtime[Throwable, String]) =
    val sid = openSession(handler)
    val call = handler
      .post(request("POST", postHeaders + ("mcp-session-id" -> sid), holdFrame))
      .flatMap {
        case sse: HttpReply.Sse => sse.frames.runCollect.map(_.map(_.encode).mkString)
        case HttpReply.SseComplete(_, frame) => ZIO.succeed(frame.encode)
        case other => ZIO.succeed(other.toString)
      }
      .provideLayer(Runtime.addLogger(captured.logger))
    val fiber = runUnsafe(call.forkDaemon)
    runUnsafe(held.started.await.timeoutFail(new RuntimeException("hold never started"))(10.seconds))
    (sid, fiber)

  test("a request whose session is deleted mid-flight gets no reply, and the server says so at WARN") {
    val held = new Held
    val captured = new Captured
    val handler = makeHandler(register = _.tool(held.tool))
    val (sid, call) = startHeldCall(handler, held, captured)
    handle(handler, request("DELETE", Map("mcp-session-id" -> sid))).status shouldBe 200
    val body = runUnsafe(
      call.join.timeoutFail(new RuntimeException("the reply stream never ended"))(10.seconds)
    )
    // The stream ended without the call's result or an error for it.
    body should not include "client-chosen-id-7"
    val warnings = captured.warnings
    warnings should have size 1
    warnings.head should include("Request 'tools/call' ended without a reply")
    warnings.head should include(s"session $sid was terminated")
    // The request id is a client-chosen string: it is not written to the log.
    warnings.head should not include "client-chosen-id-7"
  }

  test("a request the client cancels gets no reply and no WARN") {
    val held = new Held
    val captured = new Captured
    val handler = makeHandler(register = _.tool(held.tool))
    val (sid, call) = startHeldCall(handler, held, captured)
    val cancel = handle(
      handler,
      request(
        "POST",
        postHeaders + ("mcp-session-id" -> sid),
        """{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":"client-chosen-id-7"}}"""
      )
    )
    cancel.status shouldBe 202
    val body = runUnsafe(
      call.join.timeoutFail(new RuntimeException("the reply stream never ended"))(10.seconds)
    )
    body should not include "client-chosen-id-7"
    captured.warnings shouldBe Nil
    runUnsafe(handler.session(sid)) shouldBe defined
  }
