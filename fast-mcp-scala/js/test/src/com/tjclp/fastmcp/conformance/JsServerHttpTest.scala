package com.tjclp.fastmcp
package conformance

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.scalajs.js

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.server.TaskSettings
import com.tjclp.fastmcp.server.router.Session
import com.tjclp.fastmcp.server.transport.{
  BunHttpHandle,
  JsTransportBackend,
  startStatefulHttp,
  startStatelessHttp
}

/** Exercises the shared `McpServer` over `JsTransportBackend`'s Bun HTTP listener (stateless +
  * stateful) using the global `fetch` API. No TS SDK client involvement — just a raw HTTP wire
  * check.
  */
class JsServerHttpTest extends AsyncFlatSpec with Matchers with BeforeAndAfterAll:

  override implicit val executionContext: ExecutionContext = ExecutionContext.global

  case class PingArgs(msg: String)
  given JsonDecoder[PingArgs] = DeriveJsonDecoder.gen[PingArgs]
  case class PingResult(echo: String)
  given JsonEncoder[PingResult] = DeriveJsonEncoder.gen[PingResult]

  private val pingSchema = ToolInputSchema.unsafeFromJsonString(
    """{"type":"object","properties":{"msg":{"type":"string"}},"required":["msg"]}"""
  )

  private val pingTool = McpTool.withSchema[PingArgs, PingResult, Any](
    name = "ping",
    description = Some("echo"),
    inputSchema = pingSchema
  )(args => PingResult(args.msg))

  private val needsRootsTool = McpTool
    .withSchema[PingArgs, String](
      name = "needs-roots",
      inputSchema = pingSchema,
      description = Some("Requires the roots capability")
    )
    .contextual((_, context) => context.get.listRoots().as("roots received"))

  private val port = 38917

  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  private var bunServer: BunHttpHandle = scala.compiletime.uninitialized

  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  private var fiber: Fiber.Runtime[Throwable, Unit] = scala.compiletime.uninitialized

  private def runZio[A](effect: ZIO[Any, Throwable, A]): Future[A] =
    val promise = Promise[A]()
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.runToFuture(effect).onComplete {
        case scala.util.Success(a) => val _ = promise.trySuccess(a)
        case scala.util.Failure(e) => val _ = promise.tryFailure(e)
      }
    }
    promise.future

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

  override def afterAll(): Unit =
    if bunServer != null then bunServer.stop()
    super.afterAll()

  private def delay(ms: Int): Future[Unit] =
    val promise = Promise[Unit]()
    val _ = js.timers.setTimeout(ms.toDouble)(promise.success(()))
    promise.future

  private def text(resp: js.Dynamic): Future[String] =
    fromJsPromise(resp.text().asInstanceOf[js.Promise[String]])

  private def status(resp: js.Dynamic): Int = resp.status.asInstanceOf[Int]

  private def header(resp: js.Dynamic, name: String): Option[String] =
    Option(resp.headers.get(name).asInstanceOf[String])

  private def fetchAt(port: Int, init: js.Dynamic, path: String = "/mcp"): Future[js.Dynamic] =
    fromJsPromise(
      js.Dynamic.global
        .fetch(s"http://127.0.0.1:$port$path", init)
        .asInstanceOf[js.Promise[js.Dynamic]]
    )

  private val legacyInitBody =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""
  private val legacyListBody = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""

  private def jsonHeaders(extra: (String, String)*): js.Dictionary[String] =
    val headers = js.Dictionary[String](
      "content-type" -> "application/json",
      "accept" -> "application/json, text/event-stream"
    )
    extra.foreach { case (k, v) => headers(k) = v }
    headers

  private def statefulServer(name: String, port: Int, tweak: McpServerSettings => McpServerSettings)
      : BunHttpHandle =
    com.tjclp.fastmcp.server
      .McpServer(
        name,
        "0.1.0",
        tweak(
          McpServerSettings(host = "127.0.0.1", port = port, httpEndpoint = "/mcp", stateless = false)
        )
      )
      .startStatefulHttp()

  private def setupStatelessServer(): Future[Unit] =
    val server = com.tjclp.fastmcp.server.McpServer(
      "JsHttpStatelessServer",
      "0.1.0",
      McpServerSettings(
        host = "127.0.0.1",
        port = port,
        httpEndpoint = "/mcp",
        stateless = true
      )
    )
    runZio(server.tool(pingTool) *> server.tool(needsRootsTool).unit).map { _ =>
      // Start the Bun listener directly — `runHttp()` would be a ZIO.never that we'd have to
      // fork & interrupt; tests prefer the direct handle so afterAll can `stop()` cleanly.
      bunServer = server.startStatelessHttp()
      ()
    }

  private def httpFetch(path: String, init: js.Dynamic): Future[js.Dynamic] =
    val url = s"http://127.0.0.1:$port$path"
    fromJsPromise(
      js.Dynamic.global
        .fetch(url, init)
        .asInstanceOf[js.Promise[js.Dynamic]]
    )

  private def serverReady: Future[Unit] =
    if bunServer != null then Future.successful(())
    else setupStatelessServer()

  "runHttp (stateless)" should "accept an initialize POST and return JSON carrying the server name" in {
    serverReady.flatMap { _ =>
      val init = js.Dynamic.literal(
        method = "POST",
        headers = js.Dictionary(
          "content-type" -> "application/json",
          "accept" -> "application/json, text/event-stream"
        ),
        body =
          """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"http-test","version":"0.1.0"}}}"""
      )
      for
        resp <- httpFetch("/mcp", init)
        body <- fromJsPromise(resp.text().asInstanceOf[js.Promise[String]])
      yield body should include("JsHttpStatelessServer")
    }
  }

  it should "reject a bogus mcp-protocol-version header with -32022, not -32602" in {
    serverReady.flatMap { _ =>
      val init = js.Dynamic.literal(
        method = "POST",
        headers = js.Dictionary(
          "content-type" -> "application/json",
          "accept" -> "application/json, text/event-stream",
          "mcp-protocol-version" -> "2099-01-01",
          "mcp-method" -> "tools/list"
        ),
        body = """{"jsonrpc":"2.0","id":7,"method":"tools/list"}"""
      )
      for
        resp <- httpFetch("/mcp", init)
        body <- fromJsPromise(resp.text().asInstanceOf[js.Promise[String]])
      yield
        resp.status.asInstanceOf[Int] shouldBe 400
        body should include(""""code":-32022""")
        body should include("2026-07-28")
        body should not include "-32602"
    }
  }

  it should "serve a session-free 2026 discovery request with the required response metadata" in {
    serverReady.flatMap { _ =>
      val init = js.Dynamic.literal(
        method = "POST",
        headers = js.Dictionary(
          "content-type" -> "application/json",
          "accept" -> "application/json, text/event-stream",
          "mcp-protocol-version" -> "2026-07-28",
          "mcp-method" -> "server/discover",
          "mcp-session-id" -> "ignored-legacy-session"
        ),
        body =
          """{"jsonrpc":"2.0","id":10,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{},"io.modelcontextprotocol/clientInfo":{"name":"http-test","version":"1.0"}}}}"""
      )
      for
        resp <- httpFetch("/mcp", init)
        body <- fromJsPromise(resp.text().asInstanceOf[js.Promise[String]])
      yield
        resp.status.asInstanceOf[Int] shouldBe 200
        Option(resp.headers.get("mcp-session-id").asInstanceOf[String]) shouldBe None
        resp.headers.get("x-accel-buffering").asInstanceOf[String] shouldBe "no"
        body should include("\"resultType\":\"complete\"")
        body should include("JsHttpStatelessServer")
    }
  }

  it should "reject a modern notification whose Mcp-Method header does not match" in {
    serverReady.flatMap { _ =>
      val init = js.Dynamic.literal(
        method = "POST",
        headers = js.Dictionary(
          "content-type" -> "application/json",
          "accept" -> "application/json, text/event-stream",
          "mcp-protocol-version" -> "2026-07-28",
          "mcp-method" -> "notifications/wrong"
        ),
        body =
          """{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":10}}"""
      )
      for
        resp <- httpFetch("/mcp", init)
        body <- fromJsPromise(resp.text().asInstanceOf[js.Promise[String]])
      yield
        resp.status.asInstanceOf[Int] shouldBe 400
        body should include("-32020")
    }
  }

  it should "return HTTP 400 for a missing required client capability" in {
    serverReady.flatMap { _ =>
      val init = js.Dynamic.literal(
        method = "POST",
        headers = js.Dictionary(
          "content-type" -> "application/json",
          "accept" -> "application/json, text/event-stream",
          "mcp-protocol-version" -> "2026-07-28",
          "mcp-method" -> "tools/call",
          "mcp-name" -> "needs-roots"
        ),
        body =
          """{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"needs-roots","arguments":{"msg":"x"},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}"""
      )
      for
        resp <- httpFetch("/mcp", init)
        body <- fromJsPromise(resp.text().asInstanceOf[js.Promise[String]])
      yield
        resp.status.asInstanceOf[Int] shouldBe 400
        body should include("-32021")
    }
  }

  it should "reject GET with 405" in {
    serverReady.flatMap { _ =>
      httpFetch("/mcp", js.Dynamic.literal(method = "GET"))
        .map(resp => resp.status.asInstanceOf[Int] shouldBe 405)
    }
  }

  it should "reject POST with a non-JSON Accept (406)" in {
    serverReady.flatMap { _ =>
      httpFetch(
        "/mcp",
        js.Dynamic.literal(
          method = "POST",
          headers = js.Dictionary("content-type" -> "application/json", "accept" -> "text/plain"),
          body = "{}"
        )
      ).map(resp => resp.status.asInstanceOf[Int] shouldBe 406)
    }
  }

  // ---- F5 §2: Content-Type gate, before body read or minting ----

  it should "reject a text/plain legacy POST with 415 and no session header (stateless)" in {
    serverReady.flatMap { _ =>
      // A string body with no explicit content-type: Bun's fetch sends text/plain;charset=UTF-8 —
      // exactly the CORS-simple request a cross-site page can fire without a preflight.
      val init = js.Dynamic.literal(
        method = "POST",
        headers = js.Dictionary("accept" -> "application/json, text/event-stream"),
        body = legacyInitBody
      )
      for
        resp <- httpFetch("/mcp", init)
        body <- text(resp)
      yield
        status(resp) shouldBe 415
        header(resp, "mcp-session-id") shouldBe None
        body should include("-32000")
        body should include("application/json")
    }
  }

  it should "reject a Content-Type-less Blob legacy tools/call with 415 (stateless)" in {
    serverReady.flatMap { _ =>
      val callBody =
        """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ping","arguments":{"msg":"csrf"}}}"""
      val blob = js.Dynamic.newInstance(js.Dynamic.global.Blob)(js.Array(callBody))
      val init = js.Dynamic.literal(method = "POST", body = blob)
      for
        resp <- httpFetch("/mcp", init)
        body <- text(resp)
      yield
        status(resp) shouldBe 415
        body should not include "csrf"
    }
  }

  it should "accept application/json with a charset parameter" in {
    serverReady.flatMap { _ =>
      httpFetch(
        "/mcp",
        js.Dynamic.literal(
          method = "POST",
          headers = js.Dictionary(
            "content-type" -> "application/json; charset=utf-8",
            "accept" -> "application/json, text/event-stream"
          ),
          body = legacyListBody
        )
      ).map(resp => status(resp) shouldBe 200)
    }
  }

  // ---- F10: header sentinel + error boundary ----

  it should "answer `Mcp-Name: =?base64?=` with a small JSON -32020, never a trace or a debug page" in {
    serverReady.flatMap { _ =>
      val init = js.Dynamic.literal(
        method = "POST",
        headers = jsonHeaders(
          "mcp-protocol-version" -> "2026-07-28",
          "mcp-method" -> "tools/call",
          "mcp-name" -> "=?base64?="
        ),
        body =
          """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"x","arguments":{},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}"""
      )
      for
        resp <- httpFetch("/mcp", init)
        body <- text(resp)
      yield
        status(resp) shouldBe 400
        header(resp, "content-type").getOrElse("") should include("application/json")
        body should include("-32020")
        body should include("Malformed Base64 header sentinel")
        body should not include "Exception"
        body should not include "StringIndexOutOfBounds"
        body should not include "main.js"
        body should not include "    at "
        body should not include "<html"
        body.length should be < 512
    }
  }

  it should "answer `Mcp-Name: =?base64??=` (empty payload) with -32020 as well" in {
    serverReady.flatMap { _ =>
      val init = js.Dynamic.literal(
        method = "POST",
        headers = jsonHeaders(
          "mcp-protocol-version" -> "2026-07-28",
          "mcp-method" -> "tools/call",
          "mcp-name" -> "=?base64??="
        ),
        body =
          """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"x","arguments":{},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}"""
      )
      for
        resp <- httpFetch("/mcp", init)
        body <- text(resp)
      yield
        status(resp) shouldBe 400
        body should include("-32020")
        body should not include "Exception"
    }
  }

  "Bun.serve options" should "run with development=false, an error callback and the body cap (NODE_ENV-independent)" in {
    val settings = McpServerSettings(
      host = "127.0.0.1",
      port = 1, // never bound: serveOptions does not call Bun.serve
      stateless = true,
      maxRequestBodyBytes = 4096
    )
    val server = com.tjclp.fastmcp.server.McpServer("JsHttpOptionsServer", "0.1.0", settings)
    runZio(server.tool(pingTool) *> server.buildRouter).flatMap { router =>
      val opts =
        JsTransportBackend.serveOptions(router, Runtime.default, settings, js.Dictionary.empty[Session])
      opts.development.getOrElse(true) shouldBe false
      opts.maxRequestBodySize.getOrElse(0) shouldBe 4096
      opts.port.getOrElse(0) shouldBe 1
      opts.error.isDefined shouldBe true
      // Bun calls fetch(request, server): the closure must take both.
      opts.fetch.asInstanceOf[js.Dynamic].length.asInstanceOf[Int] shouldBe 2

      val errorResponse = opts.error.map(_(js.Dynamic.literal(message = "kaboom"))).getOrElse(fail())
      // Fake Web Requests drive handleFetch below Bun's own network layer, so the FIRST-PARTY
      // gates (postGate 413 on Content-Length, post-read bodyTooLarge 413) are what answers.
      def fakeRequest(headers: Map[String, String], body: String): js.Dynamic =
        js.Dynamic.literal(
          url = "http://127.0.0.1:1/mcp",
          method = "POST",
          headers = js.Dynamic.newInstance(js.Dynamic.global.Headers)(js.Dictionary(headers.toSeq*)),
          text = js.Any.fromFunction0(() => js.Promise.resolve[String](body))
        )
      val json = Map("content-type" -> "application/json", "accept" -> "application/json")
      def call(req: js.Dynamic): Future[(Int, String)] =
        fromJsPromise(opts.fetch(req, js.undefined.asInstanceOf[js.Dynamic]))
          .flatMap(resp => text(resp).map(body => (status(resp), body)))
      for
        errorBody <- text(errorResponse)
        // First-party boundary: a request object that makes handleFetch throw synchronously
        // (`new URL("not a url")`) must resolve — not reject — to a JSON-RPC 500.
        boundaryResponse <- fromJsPromise(
          opts.fetch(
            js.Dynamic.literal(url = "not a url", method = "POST"),
            js.undefined.asInstanceOf[js.Dynamic]
          )
        )
        boundaryBody <- text(boundaryResponse)
        declared <- call(fakeRequest(json + ("content-length" -> "9999"), legacyListBody))
        oversized <- call(fakeRequest(json, "x" * 5000))
        fine <- call(fakeRequest(json, legacyListBody))
        // A body read that fails is a controlled 400, never a rejected promise.
        brokenRead <- call(
          js.Dynamic.literal(
            url = "http://127.0.0.1:1/mcp",
            method = "POST",
            headers = js.Dynamic.newInstance(js.Dynamic.global.Headers)(js.Dictionary(json.toSeq*)),
            text = js.Any.fromFunction0(() => js.Promise.reject(js.Error("socket reset")).asInstanceOf[js.Promise[String]])
          )
        )
      yield
        declared._1 shouldBe 413
        declared._2 should include("-32000")
        declared._2 should include("4096 bytes")
        oversized._1 shouldBe 413
        oversized._2 should include("-32000")
        oversized._2 should not include "-32700"
        fine._1 shouldBe 200
        fine._2 should include("\"tools\"")
        brokenRead._1 shouldBe 400
        brokenRead._2 should include("-32000")
        brokenRead._2 should not include "socket reset"
        status(errorResponse) shouldBe 500
        errorBody should include("-32000")
        errorBody should include("Internal server error")
        errorBody should not include "kaboom"
        status(boundaryResponse) shouldBe 500
        header(boundaryResponse, "content-type").getOrElse("") should include("application/json")
        boundaryBody should include("-32000")
        boundaryBody should include("Internal server error")
        boundaryBody should not include "Exception"
        boundaryBody should not include "    at "
        boundaryBody should not include "not a url"
    }
  }

  "a dying tool on Bun" should "be answered in-band as JSON (router boundary), never a raw 500 or HTML" in {
    val boomPort = 38927
    val boomTool = McpTool
      .withSchema[PingArgs, String](
        name = "boom",
        inputSchema = pingSchema,
        description = Some("Dies with a defect")
      )
      .contextual((_, _) => ZIO.die(new IllegalStateException("boom: secret handler state")))
    val server = com.tjclp.fastmcp.server.McpServer(
      "JsHttpBoomServer",
      "0.1.0",
      McpServerSettings(host = "127.0.0.1", port = boomPort, httpEndpoint = "/mcp", stateless = true)
    )
    runZio(server.tool(boomTool).unit).flatMap { _ =>
      val handle = server.startStatelessHttp()
      val checked = for
        resp <- fetchAt(
          boomPort,
          js.Dynamic.literal(
            method = "POST",
            headers = jsonHeaders(),
            body =
              """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"boom","arguments":{"msg":"x"}}}"""
          )
        )
        body <- text(resp)
      yield
        status(resp) shouldBe 200
        header(resp, "content-type").getOrElse("") should include("application/json")
        body should include("\"jsonrpc\"")
        body should not include "<html"
        body should not include "    at "
      checked.andThen { case _ => handle.stop() }
    }
  }

  // ---- F1 §2: body cap ----

  "maxRequestBodyBytes on Bun" should "answer 413 for a declared oversize body and never 200/500 for a streamed one" in {
    val capPort = 38926
    val handle = statefulServer(
      "JsHttpBodyCapServer",
      capPort,
      _.copy(stateless = true, maxRequestBodyBytes = 64)
    )
    val big = "a" * 200
    val declared = fetchAt(
      capPort,
      js.Dynamic.literal(method = "POST", headers = jsonHeaders(), body = big)
    ).flatMap(resp => text(resp).map(body => (status(resp), body)))

    val encoder = js.Dynamic.newInstance(js.Dynamic.global.TextEncoder)()
    val source = js.Dynamic.literal(
      start = js.Any.fromFunction1((controller: js.Dynamic) =>
        val _ = controller.enqueue(encoder.encode(big))
        val _ = controller.close()
      )
    )
    val stream = js.Dynamic.newInstance(js.Dynamic.global.ReadableStream)(source)
    val streamed: Future[Option[Int]] = fetchAt(
      capPort,
      js.Dynamic.literal(method = "POST", headers = jsonHeaders(), body = stream, duplex = "half")
    ).map(resp => Some(status(resp))).recover { case _: Throwable => None }

    val small = fetchAt(
      capPort,
      js.Dynamic.literal(method = "POST", headers = jsonHeaders(), body = legacyListBody)
    ).map(status)

    val checked = for
      (declaredStatus, declaredBody) <- declared
      streamedStatus <- streamed
      smallStatus <- small
    yield
      // Bun enforces `maxRequestBodySize` itself first (an empty 413 for a declared oversize body,
      // an errored body read for a streamed one — which `guarded` maps to a JSON 413); the
      // first-party gates behind it are proven on fake requests in the "Bun.serve options" test.
      // Never 200 (parsed) and never 500 (escaped as a defect).
      declaredStatus shouldBe 413
      if declaredBody.nonEmpty then declaredBody should include("-32000")
      streamedStatus.foreach(st => st shouldBe 413)
      smallStatus shouldBe 200
    checked.andThen { case _ => handle.stop() }
  }

  it should "return 404 for unknown paths" in {
    serverReady.flatMap { _ =>
      httpFetch("/other", js.Dynamic.literal(method = "POST", body = "{}"))
        .map(resp => resp.status.asInstanceOf[Int] shouldBe 404)
    }
  }

  it should "reject malformed JSON with 400 and a -32700 body" in {
    serverReady.flatMap { _ =>
      httpFetch(
        "/mcp",
        js.Dynamic.literal(
          method = "POST",
          headers = js.Dictionary(
            "content-type" -> "application/json",
            "accept" -> "application/json, text/event-stream"
          ),
          body = "not json"
        )
      ).flatMap { resp =>
        fromJsPromise(resp.text().asInstanceOf[js.Promise[String]]).map { body =>
          resp.status.asInstanceOf[Int] shouldBe 400
          body should include("-32700")
        }
      }
    }
  }

  "HostGuard on Bun" should "match Origin as a full origin: cross-port/scheme/null are 403, same authority 200" in {
    val originPort = 38924
    val handle = statefulServer(
      "JsHttpOriginServer",
      originPort,
      _.copy(stateless = true, allowedHosts = Some(Set("127.0.0.1", "localhost")))
    )
    def withOrigin(origin: String): Future[(String, Int, Option[String])] =
      fetchAt(
        originPort,
        js.Dynamic.literal(
          method = "POST",
          headers = jsonHeaders("origin" -> origin),
          body = legacyListBody
        )
      ).map(resp => (origin, status(resp), header(resp, "mcp-session-id")))

    val refused = List(
      "http://localhost:3000",
      "https://localhost",
      "http://127.0.0.1:1",
      "null",
      "http://localhost:99999",
      "http://evil.example.com",
      s"http://localhost:$originPort" // listed hostname, right port, but not this request's Host
    )
    val checked = for
      refusedResults <- Future.sequence(refused.map(withOrigin))
      same <- withOrigin(s"http://127.0.0.1:$originPort")
      sameUpper <- withOrigin(s"HTTP://127.0.0.1:$originPort")
      none <- fetchAt(
        originPort,
        js.Dynamic.literal(method = "POST", headers = jsonHeaders(), body = legacyListBody)
      ).map(status)
    yield
      refusedResults.foreach { case (origin, st, sid) =>
        withClue(s"Origin: $origin") {
          st shouldBe 403
          sid shouldBe None
        }
      }
      same._2 shouldBe 200
      sameUpper._2 shouldBe 200
      none shouldBe 200
    checked.andThen { case _ => handle.stop() }
  }

  it should "admit an explicitly listed allowedOrigins entry" in {
    val listedPort = 38930
    val handle = statefulServer(
      "JsHttpAllowedOriginsServer",
      listedPort,
      _.copy(
        stateless = true,
        allowedHosts = Some(Set("127.0.0.1", "localhost")),
        allowedOrigins = Some(Set("http://localhost:3000"))
      )
    )
    def withOrigin(origin: String): Future[Int] =
      fetchAt(
        listedPort,
        js.Dynamic.literal(
          method = "POST",
          headers = jsonHeaders("origin" -> origin),
          body = legacyListBody
        )
      ).map(status)
    val checked = for
      listed <- withOrigin("http://localhost:3000")
      other <- withOrigin("http://localhost:3001")
    yield
      listed shouldBe 200
      other shouldBe 403
    checked.andThen { case _ => handle.stop() }
  }

  it should "refuse to start on an unparseable allowedOrigins entry" in {
    val server = com.tjclp.fastmcp.server.McpServer(
      "JsHttpBadOriginsServer",
      "0.1.0",
      McpServerSettings(
        host = "127.0.0.1",
        port = 38931,
        stateless = true,
        allowedOrigins = Some(Set("app.example.com"))
      )
    )
    val thrown = intercept[IllegalArgumentException](server.startStatelessHttp())
    thrown.getMessage should include("allowedOrigins")
    Future.successful(succeed)
  }

  it should "reject a foreign Origin with 403 when allowedHosts is set" in {
    val guardPort = 38920
    val guardServer = com.tjclp.fastmcp.server.McpServer(
      "JsHttpGuardServer",
      "0.1.0",
      McpServerSettings(
        host = "127.0.0.1",
        port = guardPort,
        httpEndpoint = "/mcp",
        stateless = true,
        allowedHosts = Some(Set("127.0.0.1", "localhost"))
      )
    )
    val guardBunServer = guardServer.startStatelessHttp()
    val init = js.Dynamic.literal(
      method = "POST",
      headers = js.Dictionary(
        "content-type" -> "application/json",
        "accept" -> "application/json, text/event-stream",
        "origin" -> "http://evil.example.com"
      ),
      body = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""
    )
    val done = fromJsPromise(
      js.Dynamic.global
        .fetch(s"http://127.0.0.1:$guardPort/mcp", init)
        .asInstanceOf[js.Promise[js.Dynamic]]
    ).map(resp => resp.status.asInstanceOf[Int] shouldBe 403)
    done.andThen { case _ => guardBunServer.stop() }
  }

  "streamable session lifecycle on Bun" should "mint, reuse, DELETE, then 404" in {
    val lifePort = 38921
    val lifeServer = com.tjclp.fastmcp.server.McpServer(
      "JsHttpLifecycleServer",
      "0.1.0",
      McpServerSettings(
        host = "127.0.0.1",
        port = lifePort,
        httpEndpoint = "/mcp",
        stateless = false
      )
    )
    val lifeBunServer = lifeServer.startStatefulHttp()

    def send(
        method: String,
        body: Option[String],
        sid: Option[String],
        protocolVersion: Option[String] = None
    ): Future[js.Dynamic] =
      val headers = js.Dictionary[String](
        "content-type" -> "application/json",
        "accept" -> "application/json, text/event-stream"
      )
      sid.foreach(s => headers("mcp-session-id") = s)
      protocolVersion.foreach(version => headers("mcp-protocol-version") = version)
      val init = body match
        case Some(b) => js.Dynamic.literal(method = method, headers = headers, body = b)
        case None => js.Dynamic.literal(method = method, headers = headers)
      fromJsPromise(
        js.Dynamic.global
          .fetch(s"http://127.0.0.1:$lifePort/mcp", init)
          .asInstanceOf[js.Promise[js.Dynamic]]
      )

    val initBody =
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""
    val listBody = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""

    val done = for
      initResp <- send("POST", Some(initBody), None)
      sid = initResp.headers.get("mcp-session-id").asInstanceOf[String]
      list <- send("POST", Some(listBody), Some(sid))
      modernDelete <- send("DELETE", None, Some(sid), Some("2026-07-28"))
      stillLive <- send("POST", Some(listBody), Some(sid))
      del <- send("DELETE", None, Some(sid))
      after <- send("POST", Some(listBody), Some(sid))
    yield
      initResp.status.asInstanceOf[Int] shouldBe 200
      list.status.asInstanceOf[Int] shouldBe 200
      modernDelete.status.asInstanceOf[Int] shouldBe 405
      stillLive.status.asInstanceOf[Int] shouldBe 200
      del.status.asInstanceOf[Int] shouldBe 200
      after.status.asInstanceOf[Int] shouldBe 404

    done.andThen { case _ => lifeBunServer.stop() }
  }

  "runHttp (streamable minting)" should "400 a headerless non-initialize POST without minting" in {
    val mintPort = 38919
    val mintServer = com.tjclp.fastmcp.server.McpServer(
      "JsHttpMintServer",
      "0.1.0",
      McpServerSettings(
        host = "127.0.0.1",
        port = mintPort,
        httpEndpoint = "/mcp",
        stateless = false
      )
    )
    val mintBunServer = mintServer.startStatefulHttp()
    val init = js.Dynamic.literal(
      method = "POST",
      headers = js.Dictionary(
        "content-type" -> "application/json",
        "accept" -> "application/json, text/event-stream"
      ),
      body = """{"jsonrpc":"2.0","id":9,"method":"tools/list"}"""
    )
    val done = fromJsPromise(
      js.Dynamic.global
        .fetch(s"http://127.0.0.1:$mintPort/mcp", init)
        .asInstanceOf[js.Promise[js.Dynamic]]
    ).map { resp =>
      resp.status.asInstanceOf[Int] shouldBe 400
      Option(resp.headers.get("mcp-session-id").asInstanceOf[String]) shouldBe None
    }
    done.andThen { case _ => mintBunServer.stop() }
  }

  "runHttp (streamable gates)" should "415 a text/plain or Blob initialize and mint nothing" in {
    val gatePort = 38925
    val handle = statefulServer("JsHttpGateServer", gatePort, identity)
    val textPlain = fetchAt(
      gatePort,
      js.Dynamic.literal(
        method = "POST",
        headers = js.Dictionary("accept" -> "application/json, text/event-stream"),
        body = legacyInitBody
      )
    )
    val blob = js.Dynamic.newInstance(js.Dynamic.global.Blob)(js.Array(legacyInitBody))
    val blobPost = fetchAt(gatePort, js.Dynamic.literal(method = "POST", body = blob))
    val checked = for
      tp <- textPlain
      tpBody <- text(tp)
      bl <- blobPost
      guessed <- fetchAt(
        gatePort,
        js.Dynamic.literal(
          method = "POST",
          headers = jsonHeaders("mcp-session-id" -> "guessed-session-id"),
          body = legacyListBody
        )
      )
    yield
      status(tp) shouldBe 415
      header(tp, "mcp-session-id") shouldBe None
      tpBody should include("-32000")
      status(bl) shouldBe 415
      header(bl, "mcp-session-id") shouldBe None
      status(guessed) shouldBe 404 // nothing was minted by the refused POSTs
    checked.andThen { case _ => handle.stop() }
  }

  // ---- F12: sweeper on every entry + bounded store ----

  // Regression net: the sweeper used `repeat(Schedule.spaced(..))`, whose driver reads
  // `Clock.currentDateTime` → scala-java-time `ZoneId.systemDefault()` → `ZoneRulesException` on
  // Scala.js without tzdb — the fiber died silently after its first tick and never evicted.
  "evictIdleSessions on Bun" should "drop an idle session from the dictionary when forked via unsafe.fork" in {
    val settings = McpServerSettings(sessionIdleTimeout = Some(java.time.Duration.ofMillis(100)))
    runZio(Session.make("idle-unit")).flatMap { session =>
      val store = js.Dictionary[Session]("idle-unit" -> session)
      val sweeper = Unsafe.unsafe(implicit u =>
        Runtime.default.unsafe.fork(JsTransportBackend.evictIdleSessions(store, settings))
      )
      def poll(remaining: Int): Future[Boolean] =
        if store.isEmpty then Future.successful(true)
        else if remaining <= 0 then Future.successful(false)
        else delay(200).flatMap(_ => poll(remaining - 1))
      poll(remaining = 25).map { evicted =>
        val _ = Unsafe.unsafe(implicit u => Runtime.default.unsafe.fork(sweeper.interrupt))
        evicted shouldBe true
      }
    }
  }

  "startStatefulHttp" should "run the idle-session sweeper: a session idle past sessionIdleTimeout 404s" in {
    val idlePort = 38928
    val handle = statefulServer(
      "JsHttpIdleServer",
      idlePort,
      _.copy(sessionIdleTimeout = Some(java.time.Duration.ofMillis(200)))
    )
    def listWith(sid: String): Future[Int] =
      fetchAt(
        idlePort,
        js.Dynamic.literal(
          method = "POST",
          headers = jsonHeaders("mcp-session-id" -> sid),
          body = legacyListBody
        )
      ).map(status)
    // The sweep interval floors at 1 s; each poll `touch`es the session, so wait out the first
    // sweep untouched, then allow a few more sweeps before giving up (5 s deadline).
    def pollUntil404(sid: String, remaining: Int): Future[Int] =
      listWith(sid).flatMap { st =>
        if st == 404 || remaining <= 0 then Future.successful(st)
        else delay(1100).flatMap(_ => pollUntil404(sid, remaining - 1))
      }
    val checked = for
      initResp <- fetchAt(
        idlePort,
        js.Dynamic.literal(method = "POST", headers = jsonHeaders(), body = legacyInitBody)
      )
      _ <- text(initResp)
      sid = initResp.headers.get("mcp-session-id").asInstanceOf[String]
      live <- listWith(sid)
      _ <- delay(1600)
      afterIdle <- pollUntil404(sid, remaining = 3)
    yield
      status(initResp) shouldBe 200
      live shouldBe 200
      afterIdle shouldBe 404
    checked.andThen { case _ => handle.stop() }
  }

  "maxSessions on Bun" should "evict the longest-idle session at the cap instead of growing the store" in {
    val capPort = 38929
    val handle = statefulServer("JsHttpSessionCapServer", capPort, _.copy(maxSessions = Some(2)))
    def init(): Future[String] =
      fetchAt(
        capPort,
        js.Dynamic.literal(method = "POST", headers = jsonHeaders(), body = legacyInitBody)
      ).flatMap { resp =>
        text(resp).map { _ =>
          status(resp) shouldBe 200
          resp.headers.get("mcp-session-id").asInstanceOf[String]
        }
      }
    def listWith(sid: String): Future[Int] =
      fetchAt(
        capPort,
        js.Dynamic.literal(
          method = "POST",
          headers = jsonHeaders("mcp-session-id" -> sid),
          body = legacyListBody
        )
      ).map(status)
    val checked = for
      sid1 <- init()
      _ <- delay(10)
      sid2 <- init()
      _ <- delay(10)
      touched2 <- listWith(sid2) // sid1 is now unambiguously the longest-idle session
      sid3 <- init() // at the cap: evicts sid1, admits sid3
      after1 <- listWith(sid1)
      after2 <- listWith(sid2)
      after3 <- listWith(sid3)
      sid4 <- init() // still capped at 2: evicts the next-oldest idle (sid2)
      after2b <- listWith(sid2)
      after4 <- listWith(sid4)
    yield
      touched2 shouldBe 200
      after1 shouldBe 404
      after2 shouldBe 200
      after3 shouldBe 200
      after2b shouldBe 404
      after4 shouldBe 200
      Option(sid3) should not be None
    checked.andThen { case _ => handle.stop() }
  }

  "runHttp (stateful tasks)" should "handle tasks/list without params" in {
    val taskPort = 38918
    val taskServer = com.tjclp.fastmcp.server.McpServer(
      "JsHttpTasksServer",
      "0.1.0",
      McpServerSettings(
        host = "127.0.0.1",
        port = taskPort,
        httpEndpoint = "/mcp",
        stateless = false,
        tasks = TaskSettings(enabled = true)
      )
    )
    val taskBunServer = taskServer.startStatefulHttp()

    def fetchTasks(body: String, sessionId: Option[String] = None): Future[js.Dynamic] =
      val headers = js.Dictionary[String](
        "content-type" -> "application/json",
        "accept" -> "application/json, text/event-stream"
      )
      sessionId.foreach(sid => headers("mcp-session-id") = sid)
      val init = js.Dynamic.literal(
        method = "POST",
        headers = headers,
        body = body
      )
      val url = s"http://127.0.0.1:$taskPort/mcp"
      fromJsPromise(
        js.Dynamic.global
          .fetch(url, init)
          .asInstanceOf[js.Promise[js.Dynamic]]
      )

    val done = for
      initResp <- fetchTasks(
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"http-test","version":"0.1.0"}}}"""
      )
      sid = initResp.headers.get("mcp-session-id").asInstanceOf[String]
      listResp <- fetchTasks(
        """{"jsonrpc":"2.0","id":2,"method":"tasks/list"}""",
        Some(sid)
      )
      body <- fromJsPromise(listResp.text().asInstanceOf[js.Promise[String]])
    yield
      listResp.status.asInstanceOf[Int] shouldBe 200
      body should include(""""tasks":[]""")

    done.andThen { case _ => taskBunServer.stop() }
  }

  "runHttp (stateless keepalive)" should "emit pings on a quiet modern POST SSE stream" in {
    val kaPort = 38923
    val server = com.tjclp.fastmcp.server.McpServer(
      "JsHttpKeepaliveServer",
      "0.1.0",
      McpServerSettings(
        host = "127.0.0.1",
        port = kaPort,
        httpEndpoint = "/mcp",
        stateless = true,
        keepAliveInterval = Some(java.time.Duration.ofMillis(50))
      )
    )
    val slowProgressTool = McpTool
      .withSchema[PingArgs, String](
        name = "slow-progress",
        inputSchema = pingSchema,
        description = Some("Reports progress, then sleeps")
      )
      .contextual { (_, ctx) =>
        // The early notification opens the SSE stream; the sleep leaves it quiet for pings.
        ZIO.foreachDiscard(ctx.get.progressToken)(t => ctx.get.sendProgress(t, 0.5)) *>
          ZIO.sleep(30.seconds).as("done")
      }

    def readUntilPing(reader: js.Dynamic, acc: String, remaining: Int): Future[String] =
      if acc.contains("ping") || remaining <= 0 then Future.successful(acc)
      else
        fromJsPromise(reader.read().asInstanceOf[js.Promise[js.Dynamic]]).flatMap { chunk =>
          if chunk.done.asInstanceOf[Boolean] then Future.successful(acc)
          else
            val piece = js.Dynamic
              .newInstance(js.Dynamic.global.TextDecoder)()
              .decode(chunk.value)
              .asInstanceOf[String]
            readUntilPing(reader, acc + piece, remaining - 1)
        }

    runZio(server.tool(slowProgressTool).unit).flatMap { _ =>
      val bun = server.startStatelessHttp()
      val checked = for
        resp <- fromJsPromise(
          js.Dynamic.global
            .fetch(
              s"http://127.0.0.1:$kaPort/mcp",
              js.Dynamic.literal(
                method = "POST",
                headers = js.Dictionary(
                  "content-type" -> "application/json",
                  "accept" -> "application/json, text/event-stream",
                  "mcp-protocol-version" -> "2026-07-28",
                  "mcp-method" -> "tools/call",
                  "mcp-name" -> "slow-progress"
                ),
                body =
                  """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"slow-progress","arguments":{"msg":"hi"},"_meta":{"progressToken":"kp","io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}"""
              )
            )
            .asInstanceOf[js.Promise[js.Dynamic]]
        )
        reader = resp.body.getReader()
        seen <- readUntilPing(reader, "", remaining = 40)
      yield
        val _ = reader.cancel()
        seen should include("ping")
      checked.andThen { case _ => bun.stop() }
    }
  }

  "runHttp (stateless tasks)" should "refuse legacy task augmentation on the shared stateless session" in {
    val statelessTaskPort = 38922
    val server = com.tjclp.fastmcp.server.McpServer(
      "JsHttpStatelessTasksServer",
      "0.1.0",
      McpServerSettings(
        host = "127.0.0.1",
        port = statelessTaskPort,
        httpEndpoint = "/mcp",
        stateless = true,
        tasks = TaskSettings(enabled = true)
      )
    )
    val taskTool = McpTool
      .withSchema[PingArgs, PingResult, Any](
        name = "taskable",
        description = Some("echo, task-capable"),
        inputSchema = pingSchema
      )(args => PingResult(args.msg))
      .withTaskSupport(TaskSupport.Optional)

    runZio(server.tool(taskTool).unit).flatMap { _ =>
      val bun = server.startStatelessHttp()
      val checked = for
        resp <- fromJsPromise(
          js.Dynamic.global
            .fetch(
              s"http://127.0.0.1:$statelessTaskPort/mcp",
              js.Dynamic.literal(
                method = "POST",
                headers = js.Dictionary(
                  "content-type" -> "application/json",
                  "accept" -> "application/json, text/event-stream"
                ),
                body =
                  """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"taskable","arguments":{"msg":"hi"},"task":{"ttl":60000}}}"""
              )
            )
            .asInstanceOf[js.Promise[js.Dynamic]]
        )
        body <- fromJsPromise(resp.text().asInstanceOf[js.Promise[String]])
      yield
        body should include(""""code":-32601""")
        body should include("stateless")
      checked.andThen { case _ => bun.stop() }
    }
  }
