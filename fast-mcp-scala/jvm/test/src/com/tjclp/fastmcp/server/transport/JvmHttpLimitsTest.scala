package com.tjclp.fastmcp
package server.transport

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.http.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.RegistrationMacro.*
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.transport.JvmTransportBackend.given

/** The input limits over the JVM HTTP transport (both the streamable and stateless adapters and
  * the 2026-07-28 modern path), with LOWERED `LimitSettings` and bodies of a few KB — independent
  * of the transport body cap. A rejected body is HTTP 400 / -32700 and never mints a session.
  */
class JvmHttpLimitsTest extends AnyFunSuite with Matchers:

  object TestServer:
    @Tool(name = Some("add"), description = Some("Add two numbers"))
    def add(@Param("a") a: Int, @Param("b") b: Int): Int = a + b

    @Tool(name = Some("noop"), description = Some("No parameters"))
    def noop(): String = "ok"

  private val SessionIdHeader = "mcp-session-id"

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""
  private val pingFrame = """{"jsonrpc":"2.0","id":2,"method":"ping"}"""

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private val limits = LimitSettings(maxObjectFields = 64, maxDepth = 16)

  private def buildRoutes(stateless: Boolean): Routes[Any, Response] =
    val server = McpServer.typed[Any](
      "L",
      "0.1.0",
      McpServerSettings(stateless = stateless, limits = limits)
    )
    val _ = server.scanAnnotations[TestServer.type]
    runUnsafe(
      server.buildRouter.flatMap(r =>
        JvmHttpBackend.httpRoutes(r, server.settings, ZEnvironment.empty)
      )
    )

  private def run(routes: Routes[Any, Response], req: Request): Response =
    runUnsafe(ZIO.scoped(routes.runZIO(req)))

  private def bodyOf(resp: Response): String = runUnsafe(resp.body.asString)

  /** Legacy POST — always carries the JSON content type and the dual Accept header. */
  private def post(routes: Routes[Any, Response], body: String, sid: Option[String] = None): Response =
    val base = Request
      .post(URL(Path.root / "mcp"), Body.fromString(body))
      .addHeader(Header.Custom("content-type", "application/json"))
      .addHeader(Header.Custom("accept", "application/json, text/event-stream"))
    run(routes, sid.fold(base)(s => base.addHeader(Header.Custom(SessionIdHeader, s))))

  private def modernPost(
      routes: Routes[Any, Response],
      body: String,
      method: String,
      name: Option[String] = None
  ): Response =
    val base = Request
      .post(URL(Path.root / "mcp"), Body.fromString(body))
      .addHeader(Header.Custom("content-type", "application/json"))
      .addHeader(Header.Custom("accept", "application/json, text/event-stream"))
      .addHeader(Header.Custom("mcp-protocol-version", "2026-07-28"))
      .addHeader(Header.Custom("mcp-method", method))
    run(routes, name.fold(base)(value => base.addHeader(Header.Custom("mcp-name", value))))

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

  private def collidingMembers(keys: IndexedSeq[String]): String =
    keys.map(k => s""""$k":0""").mkString(",")

  /** An initialize whose params object carries 243 colliding keys (~5 KB). */
  private val collidingInit =
    s"""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"},${collidingMembers(
        collidingKeys(5)
      )}}}"""

  test("streamable: a colliding-key initialize is 400/-32700 and mints no session; a clean one does") {
    val routes = buildRoutes(stateless = false)
    val _ = bodyOf(post(routes, pingFrame)) // warm the routes; timing bounds live in JsonLimitsTest
    val bad = post(routes, collidingInit)
    bad.status shouldBe Status.BadRequest
    val body = bodyOf(bad)
    body should include("-32700")
    body should include("maxObjectFields")
    bad.rawHeader(SessionIdHeader) shouldBe None

    val good = post(routes, initFrame)
    good.status shouldBe Status.Ok
    val _ = bodyOf(good)
    good.rawHeader(SessionIdHeader) shouldBe defined
  }

  test("stateless: the same colliding body is 400/-32700") {
    val routes = buildRoutes(stateless = true)
    val resp = post(routes, collidingInit)
    resp.status shouldBe Status.BadRequest
    val body = bodyOf(resp)
    body should include("-32700")
    body should include("maxObjectFields")
  }

  test("tools/call nested 20 deep is 400/-32700 (maxDepth); the server then answers a ping") {
    val routes = buildRoutes(stateless = true)
    val deep = "[" * 20 + "1" + "]" * 20
    val frame =
      s"""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"noop","arguments":{"x":$deep}}}"""
    frame.length should be < 200
    val resp = post(routes, frame)
    resp.status shouldBe Status.BadRequest
    val body = bodyOf(resp)
    body should include("-32700")
    body should include("maxDepth")

    val ping = post(routes, pingFrame)
    ping.status shouldBe Status.Ok
    bodyOf(ping) should include(""""result":{}""")
  }

  test("modern path: a 20-deep _meta is 400/-32700 — the frame check runs before ModernHttpValidation") {
    val routes = buildRoutes(stateless = false)
    val deep = "[" * 20 + "1" + "]" * 20
    val frame =
      s"""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"noop","arguments":{},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{},"deep":$deep}}}"""
    val resp = modernPost(routes, frame, "tools/call", Some("noop"))
    resp.status shouldBe Status.BadRequest
    val body = bodyOf(resp)
    body should include("-32700")
    body should include("maxDepth")
    body should not include "-32020"
    body should not include "-32022"
    resp.rawHeader(SessionIdHeader) shouldBe None
  }

  test("modern path: a 64-key colliding _meta inside the limits gets a normal reply quickly") {
    val routes = buildRoutes(stateless = false)
    // 2 required members + 62 colliding keys = exactly maxObjectFields (64).
    val extra = collidingMembers(collidingKeys(4).take(62))
    val frame =
      s"""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"add","arguments":{"a":40,"b":2},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{},$extra}}}"""
    val start = java.lang.System.nanoTime()
    val resp = modernPost(routes, frame, "tools/call", Some("add"))
    val body = bodyOf(resp)
    val ms = (java.lang.System.nanoTime() - start) / 1_000_000L
    resp.status shouldBe Status.Ok
    body should include("42")
    ms should be < 500L
  }
