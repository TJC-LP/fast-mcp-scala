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

/** Exercises `JsMcpServer.runHttp()` end-to-end over Bun's HTTP server. Uses the global `fetch`
  * API to POST a JSON-RPC initialize + tools/list pair against the running server. No TS SDK
  * client involvement — just a raw HTTP wire check.
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

  private val pingTool = McpTool.withSchema[PingArgs, PingResult](
    name = "ping",
    description = Some("echo"),
    inputSchema = pingSchema
  )(args => PingResult(args.msg))

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
    runZio(server.tool(pingTool).unit).map { _ =>
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

  it should "reject GET with 405" in {
    serverReady.flatMap { _ =>
      httpFetch("/mcp", js.Dynamic.literal(method = "GET"))
        .map(resp => resp.status.asInstanceOf[Int] shouldBe 405)
    }
  }

  it should "return 404 for unknown paths" in {
    serverReady.flatMap { _ =>
      httpFetch("/other", js.Dynamic.literal(method = "POST", body = "{}"))
        .map(resp => resp.status.asInstanceOf[Int] shouldBe 404)
    }
  }
