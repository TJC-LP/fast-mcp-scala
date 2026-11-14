package com.tjclp.fastmcp
package server.transport.http

import java.util.concurrent.atomic.AtomicInteger

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.server.transport.{HttpRequestGuards, TransportBackend}

/** `McpServerSettings.httpRoutes` (GH #120) on the shared handler, on the JVM and on Scala Native:
  * what a route answers, which gates a request to it meets and in which order, and that the MCP
  * endpoint is exactly what it was. The zio-http and socket backends render these replies; the Bun
  * backend is held to the same table by `JsServerHttpTest`.
  */
class HttpRouteHandlerTest extends AnyFunSuite with Matchers:

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""

  private val postHeaders =
    Map("accept" -> "application/json, text/event-stream", "content-type" -> "application/json")

  private val health = HttpRoute.get("/health")(_ => ZIO.succeed(HttpRouteResponse.text("ok")))

  private def makeHandler(settings: McpServerSettings) =
    val server = McpServer.typed[Any]("RouteT", "0.1.0", settings)
    runUnsafe(
      server.buildRouter.flatMap { router =>
        StreamableHttpHandler.make(router, server.settings, summon[TransportBackend].randomId())
      }
    )

  private def request(
      method: String,
      path: String,
      headers: Map[String, String] = Map.empty,
      body: String = ""
  ): HttpRequest =
    val lookup: String => Option[String] = name => headers.get(name.toLowerCase)
    HttpRequest(method, path, lookup, lookup, ZIO.succeed(body))

  /** `handle`, checked against `admit`: whenever `admit` answers on the head alone it answers what
    * `handle` does (the socket backend relies on that to skip the body).
    */
  private def handle(handler: StreamableHttpHandler[Any], req: HttpRequest): HttpReply =
    val early = handler.admit(req)
    val reply = runUnsafe(handler.handle(req))
    early.foreach(_ shouldBe reply)
    reply

  private def headerOf(reply: HttpReply, name: String): Option[String] =
    reply.headers.collectFirst { case (n, v) if n.equalsIgnoreCase(name) => v }

  test("without routes every path but the endpoint is an empty 404, as before") {
    val handler = makeHandler(McpServerSettings())
    McpServerSettings().httpRoutes shouldBe Nil
    for method <- List("GET", "POST", "HEAD", "OPTIONS") do
      handle(handler, request(method, "/health")) shouldBe HttpReply.Empty(404)
  }

  test("a GET on a route's path runs its handler; the reply carries its status, body and type") {
    val handler = makeHandler(McpServerSettings(httpRoutes = List(health)))
    handle(handler, request("GET", "/health")) shouldBe
      HttpReply.Body(200, "ok", "text/plain; charset=utf-8", Nil)
    // Admitted on its head: the socket backend then calls `handle` without reading a body.
    handler.admit(request("GET", "/health")) shouldBe None
  }

  test("a route's path is exact; other paths stay 404") {
    val handler = makeHandler(McpServerSettings(httpRoutes = List(health)))
    for path <- List("/health/", "/HEALTH", "/health/x", "/api/health", "/", "/healthz") do
      withClue(s"$path: ") {
        handle(handler, request("GET", path)) shouldBe HttpReply.Empty(404)
      }
  }

  test("several routes: each path gets its own handler, status and headers") {
    val ready = HttpRoute.get("/ready") { _ =>
      ZIO.succeed(
        HttpRouteResponse
          .json("""{"ready":false}""", status = 503)
          .withHeader("Retry-After", "5")
          .withHeader("Cache-Control", "no-store")
      )
    }
    val handler = makeHandler(McpServerSettings(httpRoutes = List(health, ready)))
    handle(handler, request("GET", "/health")).status shouldBe 200
    handle(handler, request("GET", "/ready")) shouldBe HttpReply.Body(
      503,
      """{"ready":false}""",
      "application/json",
      List("Retry-After" -> "5", "Cache-Control" -> "no-store")
    )
  }

  test("the handler sees the path and the request headers, by any case of the name") {
    val echo = HttpRoute.get("/whoami") { req =>
      ZIO.succeed(
        HttpRouteResponse.text(
          s"${req.path}|${req.header("X-Probe").getOrElse("-")}|${req.header("x-absent")}|" +
            s"${req.header("not a name")}"
        )
      )
    }
    val handler = makeHandler(McpServerSettings(httpRoutes = List(echo)))
    val reply = handle(handler, request("GET", "/whoami", Map("x-probe" -> "kubelet")))
    reply shouldBe
      HttpReply.Body(200, "/whoami|kubelet|None|None", "text/plain; charset=utf-8", Nil)
  }

  test("the Host/Origin guard refuses a request to a route before its handler runs") {
    val calls = new AtomicInteger(0)
    val counted = HttpRoute.get("/health") { _ =>
      ZIO.succeed { val _ = calls.incrementAndGet(); HttpRouteResponse.text("ok") }
    }
    // The default loopback bind: the guard is on without any setting.
    val handler = makeHandler(McpServerSettings(httpRoutes = List(counted)))
    for
      headers <- List(
        Map("host" -> "evil.example.com"),
        Map("host" -> "localhost:8000", "origin" -> "http://evil.example.com"),
        Map("host" -> "localhost:8000", "origin" -> "null")
      )
    do
      withClue(s"$headers: ") {
        val reply = handle(handler, request("GET", "/health", headers))
        reply.status shouldBe 403
        reply match
          case HttpReply.Json(_, body, _) =>
            body should include(HttpRequestGuards.HostRefusedMessage)
          case other => fail(s"expected the guard's JSON-RPC refusal, got $other")
      }
    calls.get shouldBe 0
    handle(handler, request("GET", "/health", Map("host" -> "localhost:8000"))).status shouldBe 200
    handle(handler, request("GET", "/health", Map("host" -> "127.0.0.1"))).status shouldBe 200
    calls.get shouldBe 2
  }

  test("an explicit allowedHosts list governs routes as it governs the endpoint") {
    val settings = McpServerSettings(
      host = "0.0.0.0",
      allowedHosts = Some(Set("mcp.example.com")),
      httpRoutes = List(health)
    )
    val handler = makeHandler(settings)
    handle(handler, request("GET", "/health", Map("host" -> "mcp.example.com"))).status shouldBe 200
    handle(handler, request("GET", "/health", Map("host" -> "10.0.0.7:8000"))).status shouldBe 403
    // The opt-out turns the guard off for routes and endpoint alike.
    val open = makeHandler(
      McpServerSettings(host = "0.0.0.0", dnsRebindingProtection = false, httpRoutes = List(health))
    )
    handle(open, request("GET", "/health", Map("host" -> "10.0.0.7:8000"))).status shouldBe 200
  }

  test("any method but GET on a route's path is 405 with Allow: GET, after the Host guard") {
    val calls = new AtomicInteger(0)
    val counted = HttpRoute.get("/health") { _ =>
      ZIO.succeed { val _ = calls.incrementAndGet(); HttpRouteResponse.text("ok") }
    }
    val handler = makeHandler(McpServerSettings(httpRoutes = List(counted)))
    for method <- List("POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE") do
      withClue(s"$method: ") {
        val reply = handle(handler, request(method, "/health", postHeaders, initFrame))
        reply.status shouldBe 405
        headerOf(reply, "allow") shouldBe Some("GET")
        reply match
          case HttpReply.Json(_, body, _) =>
            body should include(s"Method $method is not allowed on this route")
          case other => fail(s"expected a JSON-RPC refusal, got $other")
        handle(handler, request(method, "/health", Map("host" -> "evil.example"))).status shouldBe
          403
      }
    // HEAD: the status and headers only.
    handle(handler, request("HEAD", "/health")) shouldBe
      HttpReply.Empty(405, List("allow" -> "GET"))
    calls.get shouldBe 0
  }

  test("a handler that dies is a fixed 500: nothing of the cause reaches the client") {
    val broken = HttpRoute.get("/broken")(_ => ZIO.die(new RuntimeException("secret-db-password")))
    val throwing = HttpRoute.get("/throwing")(_ => throw new IllegalStateException("secret-path"))
    // A reply that cannot be written (a line break in a header) is refused where it is built.
    val injected = HttpRoute.get("/injected") { _ =>
      ZIO.succeed(HttpRouteResponse.text("x").withHeader("X-A", "1\r\nSet-Cookie: a=b"))
    }
    val handler =
      makeHandler(McpServerSettings(httpRoutes = List(broken, throwing, injected, health)))
    for path <- List("/broken", "/throwing", "/injected") do
      withClue(s"$path: ") {
        handle(handler, request("GET", path)) match
          case HttpReply.Json(500, body, _) =>
            body should include(HttpRequestGuards.InternalErrorMessage)
            body should not include "secret"
            body should not include "Set-Cookie"
          case other => fail(s"expected the fixed 500, got $other")
      }
    // The listener is unharmed.
    handle(handler, request("GET", "/health")).status shouldBe 200
  }

  test("the MCP endpoint is untouched by routes: its gates, its methods and its replies") {
    val plain = makeHandler(McpServerSettings())
    val routed = makeHandler(McpServerSettings(httpRoutes = List(health)))
    val probes = List(
      request("POST", "/mcp", postHeaders - "content-type", initFrame), // 415
      request("POST", "/mcp", postHeaders + ("host" -> "evil.example"), initFrame), // 403
      request("POST", "/mcp", postHeaders + ("accept" -> "text/html"), initFrame), // 406
      request("POST", "/mcp", postHeaders, "{not json"), // 400
      request("GET", "/mcp"), // 400: no session
      request("PUT", "/mcp"), // 405
      request("OPTIONS", "/mcp"), // 405
      request("DELETE", "/mcp") // 400: no session
    )
    for probe <- probes do
      withClue(s"${probe.method}: ") {
        handle(routed, probe) shouldBe handle(plain, probe)
      }
    handle(routed, request("POST", "/mcp", postHeaders, initFrame)).status shouldBe 200
  }

  test("startup refuses a route that is the endpoint, listed twice, or unreachable") {
    def refusal(settings: McpServerSettings): String =
      HttpRequestGuards.validateSettings(settings).left.getOrElse(fail("settings were accepted"))
    def route(path: String) = HttpRoute.get(path)(_ => ZIO.succeed(HttpRouteResponse.text("x")))

    refusal(McpServerSettings(httpRoutes = List(route("/mcp")))) should include("is the MCP endpoint")
    refusal(McpServerSettings(httpEndpoint = "api", httpRoutes = List(route("/api")))) should
      include("is the MCP endpoint")
    refusal(McpServerSettings(httpRoutes = List(route("health")))) should include(
      "must start with '/'"
    )
    refusal(McpServerSettings(httpRoutes = List(route("/a"), route("/b"), route("/a")))) should
      include("'/a' is listed twice")
    for bad <- List("/health?x=1", "/he alth", "/a/../b", "/h%65alth", "/café", "/a#b") do
      withClue(s"$bad: ") {
        refusal(McpServerSettings(httpRoutes = List(route(bad)))) should
          include("can never match a request path")
      }
    // The zio-http backend, which tolerates an encodable endpoint, holds routes to the strict rule.
    HttpRequestGuards
      .validateSettings(McpServerSettings(httpRoutes = List(route("/café"))), decodedPaths = true)
      .isLeft shouldBe true
    HttpRequestGuards.validateSettings(
      McpServerSettings(httpRoutes = List(route("/health"), route("/ready")))
    ) shouldBe Right(())
    // And the transport does not start with them.
    val failed = runUnsafe(
      StreamableHttpHandler
        .validateSettings(McpServerSettings(httpRoutes = List(route("/mcp"))))
        .either
    )
    failed.left.map(_.getMessage).left.getOrElse("") should include("is the MCP endpoint")
  }

  test("a reply is checked when it is built: status, content type and headers") {
    for status <- List(99, 100, 199, 204, 205, 304, 600, -1) do
      withClue(s"$status: ") {
        an[IllegalArgumentException] should be thrownBy HttpRouteResponse.text("x", status)
      }
    for status <- List(200, 201, 301, 404, 500, 503, 599) do
      HttpRouteResponse.text("x", status).status shouldBe status
    an[IllegalArgumentException] should be thrownBy HttpRouteResponse(200, "x", "text/plain\r\nX: y")
    an[IllegalArgumentException] should be thrownBy HttpRouteResponse(200, "x", "")
    val ok = HttpRouteResponse.text("x")
    for name <- List("Content-Length", "content-type", "Transfer-Encoding", "CONNECTION") do
      withClue(s"$name: ") {
        an[IllegalArgumentException] should be thrownBy ok.withHeader(name, "1")
      }
    for (name, value) <- List("X A" -> "1", "" -> "1", "X-A:" -> "1", "X-A" -> "a\nb", "X-A" -> "a\u0000", "X-A" -> "café")
    do
      withClue(s"$name=$value: ") {
        an[IllegalArgumentException] should be thrownBy ok.withHeader(name, value)
      }
    // A header set twice is sent once, with the later value.
    ok.withHeader("X-A", "1").withHeader("x-a", "2").headers shouldBe List("x-a" -> "2")
    HttpRouteResponse.json("{}").contentType shouldBe "application/json"
  }
