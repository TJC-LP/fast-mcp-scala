package com.tjclp.fastmcp
package conformance

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.scalajs.js

import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.server.transport.startStatelessHttp

/** The Bun twin of `LegacyStatelessRevisionTest`: on the legacy stateless adapter the revision of a
  * reply comes from the request's `mcp-protocol-version` header, so `tools/list` is projected, and
  * a `required` task tool's refusal names the transport's limitation. Port 28953.
  */
class JsStatelessRevisionTest extends AsyncFlatSpec with Matchers:

  override implicit val executionContext: ExecutionContext = ExecutionContext.global

  case class LookupArgs(q: String)
  given JsonDecoder[LookupArgs] = DeriveJsonDecoder.gen[LookupArgs]

  private val lookup = McpTool
    .withSchema[LookupArgs, String](
      name = "lookup",
      inputSchema = ToolInputSchema.unsafeFromJsonString(
        """{"type":"object","properties":{"q":{"type":"string"}},"required":["q"]}"""
      ),
      description = Some("Look something up"),
      annotations = Some(ToolAnnotations(title = Some("Look up"), readOnlyHint = Some(true)))
    )(args => args.q)
    .withTaskSupport(TaskSupport.Required)

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

  private def runZio[A](effect: ZIO[Any, Throwable, A]): Future[A] =
    Unsafe.unsafe(implicit unsafe => Runtime.default.unsafe.runToFuture(effect))

  private val port = 28953

  private def post(body: String, version: Option[String]): Future[String] =
    val headers = js.Dictionary[String](
      "content-type" -> "application/json",
      "accept" -> "application/json, text/event-stream"
    )
    version.foreach(v => headers("mcp-protocol-version") = v)
    val init = js.Dynamic.literal(method = "POST", headers = headers, body = body)
    for
      resp <- fromJsPromise(
        js.Dynamic.global
          .fetch(s"http://127.0.0.1:$port/mcp", init)
          .asInstanceOf[js.Promise[js.Dynamic]]
      )
      text <- fromJsPromise(resp.text().asInstanceOf[js.Promise[String]])
    yield
      resp.status.asInstanceOf[Int] shouldBe 200
      text

  private def toolFields(reply: String): Set[String] =
    val fields = for
      json <- reply.fromJson[Json].toOption
      result <- json.asObject.flatMap(_.get("result")).flatMap(_.asObject)
      tools <- result.get("tools").flatMap(_.asArray)
      first <- tools.headOption.flatMap(_.asObject)
    yield first.keys.toSet
    fields.getOrElse(fail(s"not a tools/list reply with one tool: $reply"))

  private val listFrame = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""
  private val callFrame =
    """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"lookup","arguments":{"q":"x"}}}"""

  private val base = Set("name", "description", "inputSchema")

  "the legacy stateless adapter (Bun)" should "project tools/list by the mcp-protocol-version header and name its task limitation" in {
    val server = com.tjclp.fastmcp.server.McpServer(
      "JsStatelessRev",
      "0.1.0",
      McpServerSettings(
        host = "127.0.0.1",
        port = port,
        stateless = true,
        tasks = TaskSettings(enabled = true)
      )
    )
    runZio(server.tool(lookup)).flatMap { _ =>
      val handle = server.startStatelessHttp()
      val checks =
        for
          oldest <- post(listFrame, Some("2024-11-05"))
          march <- post(listFrame, Some("2025-03-26"))
          june <- post(listFrame, Some("2025-06-18"))
          latest <- post(listFrame, Some("2025-11-25"))
          bare <- post(listFrame, None)
          call <- post(callFrame, Some("2025-11-25"))
        yield
          toolFields(oldest) shouldBe base
          toolFields(march) shouldBe base + "annotations"
          toolFields(june) shouldBe base ++ Set("annotations", "title")
          // No `execution`: this adapter declares no task surface.
          toolFields(latest) shouldBe base ++ Set("annotations", "title")
          // No header: the revision the specification assumes for a request without one.
          toolFields(bare) shouldBe base + "annotations"
          call should include(""""code":-32601""")
          call should include("requires task augmentation, which is not available on")
          call should include("legacy stateless transport")
      checks.andThen { case _ => handle.stop() }
    }
  }
