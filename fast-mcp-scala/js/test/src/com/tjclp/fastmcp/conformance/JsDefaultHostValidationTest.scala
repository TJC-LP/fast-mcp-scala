package com.tjclp.fastmcp
package conformance

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.scalajs.js

import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import com.tjclp.fastmcp.examples.HttpServerJs
import com.tjclp.fastmcp.server.transport.{BunHttpHandle, startStatelessHttp}

/** Host / Origin validation on `Bun.serve` with NO allow-list configured: a server on a loopback
  * bind address is guarded by default (JVM twin: `DefaultHostValidationTest`; the verdicts are the
  * shared `HttpRequestGuards`). Requests go over the wire with the global `fetch` — Bun sends a
  * `Host` header given in `headers` as is, which is how a non-loopback `Host` reaches a listener
  * on `127.0.0.1` here.
  */
class JsDefaultHostValidationTest extends AsyncFlatSpec with Matchers:

  override implicit val executionContext: ExecutionContext = ExecutionContext.global

  private val listBody = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""

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

  /** POST `tools/list` (legacy stateless mode answers 200 with no handshake); the status only. */
  private def post(port: Int, via: String, extra: (String, String)*): Future[Int] =
    val headers = js.Dictionary[String](
      "content-type" -> "application/json",
      "accept" -> "application/json, text/event-stream"
    )
    extra.foreach { case (k, v) => headers(k) = v }
    fromJsPromise(
      js.Dynamic.global
        .fetch(
          s"http://$via:$port/mcp",
          js.Dynamic.literal(method = "POST", headers = headers, body = listBody)
        )
        .asInstanceOf[js.Promise[js.Dynamic]]
    ).map(_.status.asInstanceOf[Int])

  private def start(name: String, settings: McpServerSettings): BunHttpHandle =
    com.tjclp.fastmcp.server.McpServer(name, "0.1.0", settings).startStatelessHttp()

  "A Bun server with default Host/Origin settings on a loopback bind" should
    "refuse a non-loopback Host and a foreign or cross-port Origin with 403" in {
      val port = 28987
      // Nothing but the port and the reply shape: host, allowedHosts and allowedOrigins are defaults.
      val handle = start("JsDefaultGuardServer", McpServerSettings(port = port, stateless = true))
      val checked = for
        foreignHost <- post(port, "127.0.0.1", "host" -> "evil.example.com")
        foreignHostPort <- post(port, "127.0.0.1", "host" -> s"evil.example.com:$port")
        foreignOrigin <- post(port, "127.0.0.1", "origin" -> "http://evil.example.com")
        foreignOriginPort <- post(port, "127.0.0.1", "origin" -> s"http://evil.example.com:$port")
        crossPort <- post(port, "127.0.0.1", "origin" -> "http://localhost:3000")
        nullOrigin <- post(port, "127.0.0.1", "origin" -> "null")
        viaIp <- post(port, "127.0.0.1")
        viaName <- post(port, "localhost")
        sameOrigin <- post(port, "127.0.0.1", "origin" -> s"http://127.0.0.1:$port")
        forwardedPort <- post(port, "127.0.0.1", "host" -> "localhost:9000")
      yield
        foreignHost shouldBe 403
        foreignHostPort shouldBe 403
        foreignOrigin shouldBe 403
        foreignOriginPort shouldBe 403
        crossPort shouldBe 403
        nullOrigin shouldBe 403
        viaIp shouldBe 200
        viaName shouldBe 200
        sameOrigin shouldBe 200
        forwardedPort shouldBe 200 // the port is not compared: a forwarded port is still loopback
      checked.andThen { case _ => handle.stop() }
    }

  it should "serve any Host and Origin once dnsRebindingProtection = false opts out" in {
    val port = 28988
    val handle = start(
      "JsOptOutGuardServer",
      McpServerSettings(port = port, stateless = true, dnsRebindingProtection = false)
    )
    val checked = for
      foreignHost <- post(port, "127.0.0.1", "host" -> "mcp.example.com")
      foreignOrigin <- post(port, "127.0.0.1", "origin" -> "https://app.example.com")
    yield
      foreignHost shouldBe 200
      foreignOrigin shouldBe 200
    checked.andThen { case _ => handle.stop() }
  }

  it should "refuse to start when the opt-out is combined with an allow-list" in {
    val server = com.tjclp.fastmcp.server.McpServer(
      "JsContradictoryGuardServer",
      "0.1.0",
      McpServerSettings(
        port = 28989,
        stateless = true,
        allowedHosts = Some(Set("localhost")),
        dnsRebindingProtection = false
      )
    )
    val thrown = intercept[IllegalArgumentException](server.startStatelessHttp())
    thrown.getMessage should include("dnsRebindingProtection")
    Future.successful(succeed)
  }

  "The HttpServerJs example" should "answer 403 to a non-loopback Host and a foreign Origin" in {
    val port = 28990
    // The example's own settings, moved off its port and onto the handshake-free reply shape.
    val settings = HttpServerJs.settings.copy(port = port, stateless = true)
    settings.host shouldBe "127.0.0.1"
    val handle = start("JsExampleGuardServer", settings)
    val checked = for
      foreignHost <- post(port, "127.0.0.1", "host" -> "evil.example.com")
      foreignOrigin <- post(port, "127.0.0.1", "origin" -> "http://evil.example.com")
      local <- post(port, "127.0.0.1")
    yield
      foreignHost shouldBe 403
      foreignOrigin shouldBe 403
      local shouldBe 200
    checked.andThen { case _ => handle.stop() }
  }
