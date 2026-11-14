package com.tjclp.fastmcp
package conformance

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.scalajs.js

import org.scalatest.Assertion
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.*

import com.tjclp.fastmcp.server.transport.{BunHttpHandle, startStatefulHttp, startStatelessHttp}

/** `McpServerSettings.httpRoutes` (GH #120) on the Bun backend, over a real `Bun.serve` listener
  * and the global `fetch`. The JVM twin is `HttpRouteHandlerTest` (the shared handler, JVM and
  * Scala Native); both assert the same statuses, headers and bodies.
  *
  * Ports 28946–28951: no other Scala.js suite uses them.
  */
class JsHttpRoutesTest extends AsyncFlatSpec with Matchers:

  override implicit val executionContext: ExecutionContext = ExecutionContext.global

  private def fromJsPromise[A](p: js.Promise[A]): Future[A] =
    val promise = Promise[A]()
    val _ = p.`then`[Unit](
      (value: A) => { val _ = promise.trySuccess(value); () },
      (err: scala.Any) =>
        val t = err match
          case th: Throwable => th
          case other => new RuntimeException(String.valueOf(other))
        val _ = promise.tryFailure(t)
        ()
    )
    promise.future

  private val initBody =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""

  private val postHeaders =
    Map("content-type" -> "application/json", "accept" -> "application/json, text/event-stream")

  private final case class Reply(status: Int, body: String, headers: js.Dynamic):
    def header(name: String): Option[String] = Option(headers.get(name).asInstanceOf[String])

  private def send(
      port: Int,
      method: String,
      path: String,
      headers: Map[String, String] = Map.empty,
      body: Option[String] = None
  ): Future[Reply] =
    val init = js.Dynamic.literal(method = method, headers = js.Dictionary[String](headers.toSeq*))
    body.foreach(b => init.body = b)
    for
      resp <- fromJsPromise(
        js.Dynamic.global
          .fetch(s"http://127.0.0.1:$port$path", init)
          .asInstanceOf[js.Promise[js.Dynamic]]
      )
      text <- fromJsPromise(resp.text().asInstanceOf[js.Promise[String]])
    yield Reply(resp.status.asInstanceOf[Int], text, resp.headers)

  private var calls = 0

  private val routes = List(
    HttpRoute.get("/health") { _ =>
      ZIO.succeed { calls += 1; HttpRouteResponse.text("ok") }
    },
    HttpRoute.get("/ready") { req =>
      ZIO.succeed(
        HttpRouteResponse
          .json(s"""{"probe":"${req.header("X-Probe").getOrElse("")}"}""", status = 503)
          .withHeader("Retry-After", "5")
      )
    },
    HttpRoute.get("/broken")(_ => ZIO.die(new RuntimeException("secret-cause"))),
    HttpRoute.get("/injected") { _ =>
      ZIO.succeed(HttpRouteResponse.text("x").withHeader("X-A", "1\r\nSet-Cookie: a=b"))
    }
  )

  private def start(port: Int, tweak: McpServerSettings => McpServerSettings): BunHttpHandle =
    val settings = tweak(McpServerSettings(host = "127.0.0.1", port = port))
    val server = com.tjclp.fastmcp.server.McpServer("JsRoutes", "0.1.0", settings)
    if settings.stateless then server.startStatelessHttp() else server.startStatefulHttp()

  private def withServer[A](port: Int, tweak: McpServerSettings => McpServerSettings)(
      checks: => Future[A]
  ): Future[A] =
    val handle = start(port, tweak)
    checks.andThen { case _ => handle.stop() }

  private def all(checks: Seq[Future[Assertion]]): Future[Assertion] =
    Future.sequence(checks).map(_ => succeed)

  "httpRoutes (Bun)" should "answer a GET with the route's status, content type, headers and body" in {
    val port = 28946
    withServer(port, _.copy(httpRoutes = routes)) {
      for
        health <- send(port, "GET", "/health")
        ready <- send(port, "GET", "/ready?verbose=1", Map("x-probe" -> "kubelet"))
        // The MCP endpoint next to them answers as before.
        init <- send(port, "POST", "/mcp", postHeaders, Some(initBody))
        mcpGet <- send(port, "GET", "/mcp")
      yield
        health.status shouldBe 200
        health.header("content-type") shouldBe Some("text/plain; charset=utf-8")
        health.body shouldBe "ok"
        ready.status shouldBe 503
        ready.header("content-type") shouldBe Some("application/json")
        ready.header("retry-after") shouldBe Some("5")
        ready.body shouldBe """{"probe":"kubelet"}"""
        init.status shouldBe 200
        init.header("mcp-session-id").isDefined shouldBe true
        // Bun has no standalone GET stream: a GET on the endpoint is its 405, never a route.
        mcpGet.status shouldBe 405
        mcpGet.header("allow") shouldBe Some("POST, DELETE")
    }
  }

  it should "keep every other path an empty 404, and be absent by default" in {
    val port = 28947
    withServer(port, _.copy(httpRoutes = routes)) {
      all(List("/health/", "/HEALTH", "/health/x", "/other", "/").map { path =>
        send(port, "GET", path).map { reply =>
          withClue(s"$path: ") {
            reply.status shouldBe 404
            reply.body shouldBe ""
          }
        }
      })
    }.flatMap { _ =>
      val bare = 28948
      withServer(bare, identity) {
        send(bare, "GET", "/health").map { reply =>
          reply.status shouldBe 404
          reply.body shouldBe ""
        }
      }
    }
  }

  it should "run the Host/Origin guard before the handler, then refuse every method but GET with 405" in {
    val port = 28949
    withServer(port, _.copy(httpRoutes = routes)) {
      calls = 0
      val foreign = Map("origin" -> "http://evil.example.com")
      for
        refused <- send(port, "GET", "/health", foreign)
        nullOrigin <- send(port, "GET", "/health", Map("origin" -> "null"))
        afterRefusals = calls
        methods <- Future.sequence(List("POST", "PUT", "DELETE", "OPTIONS").map { method =>
          for
            guarded <- send(port, method, "/health", foreign)
            reply <- send(port, method, "/health")
          yield withClue(s"$method: ") {
            guarded.status shouldBe 403
            reply.status shouldBe 405
            reply.header("allow") shouldBe Some("GET")
            reply.body should include(s"Method $method is not allowed on this route")
          }
        })
        head <- send(port, "HEAD", "/health")
        afterMethods = calls
        ok <- send(port, "GET", "/health", Map("origin" -> s"http://127.0.0.1:$port"))
      yield
        refused.status shouldBe 403
        refused.body should include("Host/Origin not allowed")
        nullOrigin.status shouldBe 403
        afterRefusals shouldBe 0
        methods.size shouldBe 4
        head.status shouldBe 405
        head.header("allow") shouldBe Some("GET")
        head.body shouldBe ""
        afterMethods shouldBe 0
        ok.status shouldBe 200
        calls shouldBe 1
    }
  }

  it should "refuse a Host that allowedHosts does not list, for routes as for the endpoint" in {
    val port = 28950
    withServer(port, _.copy(httpRoutes = routes, allowedHosts = Some(Set("mcp.example.com")))) {
      for
        route <- send(port, "GET", "/health")
        endpoint <- send(port, "POST", "/mcp", postHeaders, Some(initBody))
      yield
        route.status shouldBe 403
        endpoint.status shouldBe 403
    }
  }

  it should "answer a handler that dies, or builds an unsendable reply, with a fixed 500" in {
    val port = 28951
    withServer(port, _.copy(httpRoutes = routes, stateless = true)) {
      for
        broken <- send(port, "GET", "/broken")
        injected <- send(port, "GET", "/injected")
        health <- send(port, "GET", "/health")
      yield
        for reply <- List(broken, injected) do
          reply.status shouldBe 500
          reply.body should include("Internal server error")
          reply.body should not include "secret"
          reply.header("set-cookie") shouldBe None
        health.status shouldBe 200
    }
  }

  "startup (Bun)" should "refuse a route on the MCP endpoint or one listed twice" in {
    def route(path: String) = HttpRoute.get(path)(_ => ZIO.succeed(HttpRouteResponse.text("x")))
    val onEndpoint =
      intercept[IllegalArgumentException](start(28946, _.copy(httpRoutes = List(route("/mcp")))))
    onEndpoint.getMessage should include("is the MCP endpoint")
    val twice = intercept[IllegalArgumentException](
      start(28946, _.copy(httpRoutes = List(route("/a"), route("/a"))))
    )
    twice.getMessage should include("listed twice")
  }
