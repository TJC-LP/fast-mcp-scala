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
import zio.json.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.server.LimitSettings
import com.tjclp.fastmcp.server.transport.{startStatefulHttp, startStatelessHttp}

/** The inbound input limits over the Bun HTTP listener (TJC-2295 / F1 on the single-threaded
  * runtime the finding targets). Servers run LOWERED `LimitSettings` with bodies of a few KB, so
  * the assertions are independent of the transport body cap. Bun is single-threaded, so the
  * wall-clock round trip of a rejected frame is the meaningful property — measured with
  * `performance.now()`.
  */
class JsServerLimitsTest extends AsyncFlatSpec with Matchers:

  override implicit val executionContext: ExecutionContext = ExecutionContext.global

  case class BatchArgs(items: List[Map[String, Int]])
  given JsonDecoder[BatchArgs] = DeriveJsonDecoder.gen[BatchArgs]

  private val batchSchema = ToolInputSchema.unsafeFromJsonString(
    """{"type":"object","properties":{"items":{"type":"array","items":{"type":"object","additionalProperties":{"type":"integer"}}}},"required":["items"]}"""
  )

  private val batchTool = McpTool.withSchema[BatchArgs, Int, Any](
    name = "batch",
    description = Some("counts objects"),
    inputSchema = batchSchema
  )(args => args.items.size)

  private val limits = LimitSettings(maxObjectFields = 64, maxDepth = 16)

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

  private def now(): Double = js.Dynamic.global.performance.now().asInstanceOf[Double]

  private def post(port: Int, body: String): Future[js.Dynamic] =
    val init = js.Dynamic.literal(
      method = "POST",
      headers = js.Dictionary(
        "content-type" -> "application/json",
        "accept" -> "application/json, text/event-stream"
      ),
      body = body
    )
    fromJsPromise(
      js.Dynamic.global
        .fetch(s"http://127.0.0.1:$port/mcp", init)
        .asInstanceOf[js.Promise[js.Dynamic]]
    )

  private def textOf(resp: js.Dynamic): Future[String] =
    fromJsPromise(resp.text().asInstanceOf[js.Promise[String]])

  private def collidingKeys(blocks: Int): IndexedSeq[String] =
    val parts = Array("Aa", "BB", "C#")
    (0 until math.pow(3, blocks).toInt).map { k =>
      val sb = new StringBuilder
      var rest = k
      var b = 0
      while b < blocks do
        sb.append(parts(rest % 3))
        rest /= 3
        b += 1
      sb.result()
    }

  private def collidingObject(keys: IndexedSeq[String]): String =
    keys.map(k => s""""$k":0""").mkString("{", ",", "}")

  private val initBody =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""
  private val pingBody = """{"jsonrpc":"2.0","id":2,"method":"ping"}"""

  private val collidingBody =
    s"""{"jsonrpc":"2.0","id":3,"method":"ping","params":${collidingObject(collidingKeys(5))}}"""

  private val deepBody =
    s"""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"batch","arguments":{"items":${"[" * 20}1${"]" * 20}}}}"""

  private def withServer(port: Int, stateless: Boolean)(
      body: Int => Future[Assertion]
  ): Future[Assertion] =
    val server = com.tjclp.fastmcp.server.McpServer(
      s"JsLimits$port",
      "0.1.0",
      McpServerSettings(
        host = "127.0.0.1",
        port = port,
        httpEndpoint = "/mcp",
        stateless = stateless,
        limits = limits
      )
    )
    runZio(server.tool(batchTool).unit).flatMap { _ =>
      val handle = if stateless then server.startStatelessHttp() else server.startStatefulHttp()
      body(port).andThen { case _ => handle.stop() }
    }

  "Bun HTTP limits (stateless)" should "reject a 243-colliding-key body with 400/-32700 in < 200 ms, then serve normally" in {
    withServer(38934, stateless = true) { port =>
      for
        _ <- post(port, pingBody) // warm-up
        start = now()
        bad <- post(port, collidingBody)
        badText <- textOf(bad)
        elapsed = now() - start
        deep <- post(port, deepBody)
        deepText <- textOf(deep)
        init <- post(port, initBody)
        initText <- textOf(init)
        ping <- post(port, pingBody)
        pingText <- textOf(ping)
      yield
        bad.status.asInstanceOf[Int] shouldBe 400
        badText should include("-32700")
        badText should include("maxObjectFields")
        elapsed should be < 200.0
        deep.status.asInstanceOf[Int] shouldBe 400
        deepText should include("-32700")
        deepText should include("maxDepth")
        deepText should not include "RangeError"
        init.status.asInstanceOf[Int] shouldBe 200
        initText should include("serverInfo")
        ping.status.asInstanceOf[Int] shouldBe 200
        pingText should include(""""result":{}""")
    }
  }

  it should "answer a within-limits worst case (20 × 64 colliding keys) normally in < 500 ms" in {
    withServer(38933, stateless = true) { port =>
      val obj = collidingObject(collidingKeys(4).take(64))
      val body =
        s"""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"batch","arguments":{"items":[${List
            .fill(20)(obj)
            .mkString(",")}]}}}"""
      for
        _ <- post(port, pingBody)
        start = now()
        resp <- post(port, body)
        text <- textOf(resp)
        elapsed = now() - start
      yield
        resp.status.asInstanceOf[Int] shouldBe 200
        text should include("\"20\"")
        elapsed should be < 500.0
    }
  }

  "Bun HTTP limits (stateful)" should "reject a limit-violating POST with 400 and no mcp-session-id" in {
    withServer(38932, stateless = false) { port =>
      for
        bad <- post(port, collidingBody)
        badText <- textOf(bad)
        deep <- post(port, deepBody)
        init <- post(port, initBody)
      yield
        bad.status.asInstanceOf[Int] shouldBe 400
        badText should include("maxObjectFields")
        Option(bad.headers.get("mcp-session-id").asInstanceOf[String]) shouldBe None
        deep.status.asInstanceOf[Int] shouldBe 400
        Option(deep.headers.get("mcp-session-id").asInstanceOf[String]) shouldBe None
        init.status.asInstanceOf[Int] shouldBe 200
        Option(init.headers.get("mcp-session-id").asInstanceOf[String]) shouldBe defined
    }
  }
