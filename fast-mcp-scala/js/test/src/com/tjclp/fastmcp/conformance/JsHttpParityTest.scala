package com.tjclp.fastmcp
package conformance

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.scalajs.js

import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import com.tjclp.fastmcp.server.transport.{BunHttpHandle, startStatefulHttp, startStatelessHttp}

/** The refusal shapes of the Bun HTTP backend that GH #114 brought in line with the JVM backends,
  * over a real `Bun.serve` listener and the global `fetch`: endpoint path matching, the method gate
  * behind the Host/Origin guard, the `mcp-session-id` rules, `Accept` parsing and `data.supported`
  * of `-32022`. The JVM twin is `HttpParityHandlerTest` (the shared handler, JVM and Scala Native);
  * the two suites assert the same statuses, codes and messages. The one deliberate difference is
  * GET: Bun has no standalone server→client stream, so GET is never an allowed method here.
  *
  * Ports 28970–28979: no other Scala.js suite uses them.
  */
class JsHttpParityTest extends AsyncFlatSpec with Matchers:

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
  private val listBody = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""
  private val modernDiscoverBody =
    """{"jsonrpc":"2.0","id":7,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{},"io.modelcontextprotocol/clientInfo":{"name":"t","version":"1.0"}}}}"""

  private val everyVersion =
    """"supported":["2026-07-28","2025-11-25","2025-06-18","2025-03-26","2024-11-05","2024-10-07"]"""

  /** A reply reduced to what the assertions read. */
  private final case class Reply(status: Int, body: String, headers: js.Dynamic):
    def header(name: String): Option[String] = Option(headers.get(name).asInstanceOf[String])

  private def send(
      port: Int,
      method: String,
      headers: Map[String, String] = Map.empty,
      body: Option[String] = None,
      path: String = "/mcp"
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

  private val postHeaders =
    Map("content-type" -> "application/json", "accept" -> "application/json, text/event-stream")

  private def start(port: Int, tweak: McpServerSettings => McpServerSettings = identity)
      : BunHttpHandle =
    val settings = tweak(McpServerSettings(host = "127.0.0.1", port = port))
    val server = com.tjclp.fastmcp.server.McpServer("JsParity", "0.1.0", settings)
    if settings.stateless then server.startStatelessHttp() else server.startStatefulHttp()

  /** Run `checks` against a fresh listener and stop it afterwards, whatever the outcome. */
  private def withServer[A](port: Int, tweak: McpServerSettings => McpServerSettings = identity)(
      checks: => Future[A]
  ): Future[A] =
    val handle = start(port, tweak)
    checks.andThen { case _ => handle.stop() }

  private def all(checks: Seq[Future[org.scalatest.Assertion]]): Future[org.scalatest.Assertion] =
    Future.sequence(checks).map(_ => succeed)

  "the endpoint path (Bun)" should "be exact: a query string is allowed; a trailing slash, another case or a longer path is 404" in {
    val port = 28970
    withServer(port) {
      val served = List("/mcp", "/mcp?x=1", "/mcp?").map { path =>
        send(port, "POST", postHeaders, Some(initBody), path)
          .map(reply => withClue(s"$path: ")(reply.status shouldBe 200))
      }
      // (`//mcp` is not in the list: Bun's `fetch` collapses it to `/mcp` before sending. The
      // server compares the path as received, where `//mcp` is not the endpoint.)
      val notFound =
        List("/mcp/", "/mcp/?x=1", "/MCP", "/mcp/extra", "/api/mcp", "/xmcpx", "/%6dcp")
          .flatMap { path =>
            List("POST", "GET", "DELETE", "PUT").map { method =>
              val body = if method == "POST" then Some(initBody) else None
              send(port, method, postHeaders, body, path).map { reply =>
                withClue(s"$method $path: ") {
                  reply.status shouldBe 404
                  reply.body shouldBe ""
                }
              }
            }
          }
      all(served ++ notFound)
    }
  }

  it should "be matched with or without the leading slash in the setting" in {
    val port = 28971
    withServer(port, _.copy(httpEndpoint = "api/v1/mcp")) {
      for
        ok <- send(port, "POST", postHeaders, Some(initBody), "/api/v1/mcp")
        slash <- send(port, "POST", postHeaders, Some(initBody), "/api/v1/mcp/")
        root <- send(port, "POST", postHeaders, Some(initBody), "/mcp")
      yield
        ok.status shouldBe 200
        slash.status shouldBe 404
        root.status shouldBe 404
    }
  }

  "methods other than POST (Bun)" should "be 405 with Allow and a message that names the method" in {
    val port = 28972
    withServer(port) {
      val refused = List("PUT", "PATCH", "OPTIONS").map { method =>
        send(port, method).map { reply =>
          withClue(s"$method: ") {
            reply.status shouldBe 405
            reply.header("allow") shouldBe Some("POST, DELETE")
            reply.body should include(""""code":-32000""")
            reply.body should include(s"Method $method is not allowed on the MCP endpoint")
            reply.body should include("allowed: POST, DELETE")
          }
        }
      }
      val head = send(port, "HEAD").map { reply =>
        reply.status shouldBe 405
        reply.header("allow") shouldBe Some("POST, DELETE")
        reply.body shouldBe ""
      }
      // GET: Bun has no standalone stream, with or without a session id.
      val gets = List(Map.empty[String, String], Map("mcp-session-id" -> "whatever")).map { extra =>
        send(port, "GET", Map("accept" -> "text/event-stream") ++ extra).map { reply =>
          reply.status shouldBe 405
          reply.header("allow") shouldBe Some("POST, DELETE")
          reply.body should include("no standalone server-to-client stream")
        }
      }
      all(refused ++ gets :+ head)
    }
  }

  it should "meet the Host/Origin guard before the method is judged: 403, not 405" in {
    val port = 28973
    val guard = (s: McpServerSettings) => s.copy(allowedHosts = Some(Set("127.0.0.1")))
    val foreign = Map("origin" -> "http://evil.example.com")
    def checks(port: Int) =
      all(List("GET", "DELETE", "PUT", "PATCH", "OPTIONS").map { method =>
        send(port, method, foreign).map { reply =>
          withClue(s"$method: ") {
            reply.status shouldBe 403
            reply.body should include("Host/Origin not allowed")
          }
        }
      })
    for
      _ <- withServer(port, guard)(checks(port))
      // The stateless adapter too: a refused Origin is 403 there, an allowed GET or DELETE 405.
      stateless <- withServer(port + 1, s => guard(s).copy(stateless = true)) {
        for
          _ <- checks(port + 1)
          get <- send(port + 1, "GET")
          delete <- send(port + 1, "DELETE", Map("mcp-session-id" -> "whatever"))
        yield
          get.status shouldBe 405
          get.header("allow") shouldBe Some("POST")
          get.body should include("GET is not supported: the stateless transport")
          delete.status shouldBe 405
          delete.header("allow") shouldBe Some("POST")
          delete.body should include("DELETE is not supported: the stateless transport")
      }
    yield stateless
  }

  "mcp-session-id (Bun)" should "be required on DELETE (400), unknown ids be 404, and an empty one count as absent" in {
    val port = 28975
    withServer(port) {
      for
        init <- send(port, "POST", postHeaders, Some(initBody))
        sid = init.header("mcp-session-id").getOrElse(fail("initialize minted no session"))
        bare <- send(port, "DELETE")
        empty <- send(port, "DELETE", Map("mcp-session-id" -> ""))
        unknown <- send(port, "DELETE", Map("mcp-session-id" -> "nope"))
        emptyPost <- send(port, "POST", postHeaders + ("mcp-session-id" -> ""), Some(listBody))
        modern <- send(
          port,
          "DELETE",
          Map("mcp-session-id" -> sid, "mcp-protocol-version" -> "2026-07-28")
        )
        stillLive <- send(port, "POST", postHeaders + ("mcp-session-id" -> sid), Some(listBody))
      yield
        for reply <- List(bare, empty) do
          reply.status shouldBe 400
          reply.body should include(""""code":-32000""")
          reply.body should include("mcp-session-id header is required")
        unknown.status shouldBe 404
        unknown.body should include(""""code":-32001""")
        emptyPost.status shouldBe 400
        emptyPost.body should include("mcp-session-id header is required")
        modern.status shouldBe 405
        modern.header("allow") shouldBe Some("POST")
        modern.body should include("DELETE is not used by MCP 2026-07-28")
        stillLive.status shouldBe 200
    }
  }

  it should "leave DELETE out of Allow and answer it 405 with disallowDelete" in {
    val port = 28976
    withServer(port, _.copy(disallowDelete = true)) {
      for
        delete <- send(port, "DELETE", Map("mcp-session-id" -> "whatever"))
        put <- send(port, "PUT")
      yield
        delete.status shouldBe 405
        delete.header("allow") shouldBe Some("POST")
        delete.body should include("DELETE is disabled on this server")
        put.header("allow") shouldBe Some("POST")
    }
  }

  "Accept (Bun)" should "be parsed, not searched: q=0 refuses, and a 2026-07-28 POST has one 406 shape" in {
    val port = 28977
    withServer(port) {
      def legacy(accept: String) =
        send(port, "POST", postHeaders + ("accept" -> accept), Some(initBody))
      val modernHeaders = Map(
        "content-type" -> "application/json",
        "mcp-protocol-version" -> "2026-07-28",
        "mcp-method" -> "server/discover"
      )
      def modern(accept: String) =
        send(port, "POST", modernHeaders + ("accept" -> accept), Some(modernDiscoverBody))
      for
        anyZero <- legacy("*/*;q=0")
        jsonZero <- legacy("application/json;q=0, text/event-stream")
        sseZero <- legacy("application/json, text/event-stream;q=0")
        lookalike <- legacy("application/json-patch+json, text/event-stream")
        wildcards <- legacy("application/*, text/*")
        modernOk <- modern("application/json, text/event-stream")
        refusals <- Future.sequence(
          List("application/json", "text/event-stream", "text/html", "*/*;q=0").map(modern)
        )
      yield
        anyZero.status shouldBe 406
        anyZero.body should include("Accept must allow application/json")
        jsonZero.status shouldBe 406
        sseZero.status shouldBe 406
        sseZero.body should include("Accept must allow text/event-stream")
        sseZero.body should include(""""code":-32000""")
        lookalike.status shouldBe 406
        wildcards.status shouldBe 200
        modernOk.status shouldBe 200
        for reply <- refusals do
          reply.status shouldBe 406
          reply.body should include(""""code":-32020""")
          reply.body should include("Accept must include application/json and text/event-stream")
        refusals.map(_.body).distinct.size shouldBe 1
    }
  }

  "-32022 (Bun)" should "list every supported version for an unknown one, and 2026-07-28 alone for a legacy version in the modern shape" in {
    val port = 28978
    withServer(port) {
      val unknownHeaders =
        postHeaders ++ Map("mcp-protocol-version" -> "2099-01-01", "mcp-method" -> "tools/list")
      for
        unknown <- send(port, "POST", unknownHeaders, Some(listBody))
        notification <- send(
          port,
          "POST",
          unknownHeaders + ("mcp-method" -> "notifications/initialized"),
          Some("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        )
        legacyShape <- send(
          port,
          "POST",
          postHeaders ++ Map(
            "mcp-protocol-version" -> "2025-11-25",
            "mcp-method" -> "server/discover"
          ),
          Some(modernDiscoverBody.replace(""":"2026-07-28"""", """:"2025-11-25""""))
        )
      yield
        for reply <- List(unknown, notification) do
          reply.status shouldBe 400
          reply.body should include(""""code":-32022""")
          reply.body should include(everyVersion)
        unknown.body should include(""""requested":"2099-01-01"""")
        legacyShape.status shouldBe 400
        legacyShape.body should include(""""code":-32022""")
        legacyShape.body should include(""""supported":["2026-07-28"]""")
    }
  }

  "startup validation (Bun)" should "refuse an httpEndpoint no request path can equal" in {
    for bad <- List("/mcp?x=1", "/mcp#frag", "/m%63p", "/my mcp", "/a/../mcp") do
      val thrown = intercept[IllegalArgumentException] {
        start(28979, _.copy(httpEndpoint = bad)).stop()
      }
      withClue(s"'$bad': ")(thrown.getMessage should include("httpEndpoint"))
    Future.successful(succeed)
  }
