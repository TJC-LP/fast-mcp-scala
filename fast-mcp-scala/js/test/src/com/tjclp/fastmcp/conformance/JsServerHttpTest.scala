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
import com.tjclp.fastmcp.facades.runtime.BunServer
import com.tjclp.fastmcp.server.TaskSettings
import com.tjclp.fastmcp.server.transport.{startStatefulHttp, startStatelessHttp}

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
  private var bunServer: BunServer = scala.compiletime.uninitialized

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
          headers = js.Dictionary("accept" -> "text/plain"),
          body = "{}"
        )
      ).map(resp => resp.status.asInstanceOf[Int] shouldBe 406)
    }
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

  "HostGuard on Bun" should "reject a foreign Origin with 403 when allowedHosts is set" in {
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
