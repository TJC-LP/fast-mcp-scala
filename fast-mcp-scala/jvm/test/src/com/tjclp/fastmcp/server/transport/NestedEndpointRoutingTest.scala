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

/** DRAFT (C3 step 4, TJC-2328) — a nested `httpEndpoint` (`/api/v1/mcp`) routes on the JVM.
  *
  * The session plan predicted a 404 for nested endpoints; D3 (8094) and D4 (8095) refuted it at
  * runtime on both platforms and the static reader confirmed the mechanism in zio-http 3.4.0:
  * `Method.POST / ep` takes the `String` through `PathCodec.apply` (PathCodec.scala:674-685 via the
  * implicit at :700), which splits on `/` into one literal segment each, so `/api/v1/mcp` matches
  * segment by segment (SegmentSubtree.get, PathCodec.scala:802-829). Two consequences pinned here:
  * `%2F` stays ONE segment (404), and the trailing-slash flag set by `Path.decode` is never read by
  * the route tree, so `/api/v1/mcp/` is tolerated on the JVM (Bun is exact — D3.16 / D4.4, a 1.1.0
  * rule decision; this case documents today's JVM behaviour rather than a contract). Every test
  * before this one used the default `/mcp` (coverage map S1.8, `httpEndpoint` PARTIAL).
  */
class NestedEndpointRoutingTest extends AnyFunSuite with Matchers:

  object NestedServer:
    @Tool(name = Some("add"), description = Some("Add two numbers"))
    def add(@Param("a") a: Int, @Param("b") b: Int): Int = a + b

  private val SessionIdHeader = "mcp-session-id"

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""
  private val listFrame = """{"jsonrpc":"2.0","id":3,"method":"tools/list"}"""

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private def buildRoutes(endpoint: String): Routes[Any, Response] =
    val server = McpServer.typed[Any]("Nested", "0.1.0", McpServerSettings(httpEndpoint = endpoint))
    val _ = server.scanAnnotations[NestedServer.type]
    runUnsafe(
      server.buildRouter.flatMap(r =>
        JvmHttpBackend.httpRoutes(r, server.settings, ZEnvironment.empty)
      )
    )

  private def run(routes: Routes[Any, Response], req: Request): Response =
    runUnsafe(ZIO.scoped(routes.runZIO(req)))

  private def post(
      routes: Routes[Any, Response],
      path: String,
      body: String,
      sid: Option[String]
  ): Response =
    val base = Request
      .post(URL(Path.decode(path)), Body.fromString(body))
      .addHeader(Header.Custom("content-type", "application/json"))
      .addHeader(Header.Custom("accept", "application/json, text/event-stream"))
    run(routes, sid.fold(base)(s => base.addHeader(Header.Custom(SessionIdHeader, s))))

  private def bodyOf(resp: Response): String = runUnsafe(resp.body.asString)

  test("httpEndpoint = /api/v1/mcp: initialize mints a session on the nested path and the session serves tools") {
    val routes = buildRoutes("/api/v1/mcp")
    val init = post(routes, "/api/v1/mcp", initFrame, None)
    init.status shouldBe Status.Ok
    val sid = init.rawHeader(SessionIdHeader).getOrElse(fail("no session id on the nested path"))
    bodyOf(init) should include(""""protocolVersion":"2025-11-25"""")
    val list = post(routes, "/api/v1/mcp", listFrame, Some(sid))
    list.status shouldBe Status.Ok
    bodyOf(list) should include(""""name":"add"""")
  }

  test("httpEndpoint = /api/v1/mcp: the default /mcp, the parent prefix, a longer path and a percent-encoded literal are 404") {
    val routes = buildRoutes("/api/v1/mcp")
    for wrong <- List("/mcp", "/api/v1", "/api/v1/mcp/extra", "/api/mcp", "/prefix/api/v1/mcp", "/api%2Fv1%2Fmcp") do
      withClue(s"POST $wrong") {
        post(routes, wrong, initFrame, None).status shouldBe Status.NotFound
      }
  }

  test("httpEndpoint = /api/v1/mcp: a trailing slash is tolerated on the JVM (zio-http ignores Path's trailing-slash flag; D3.16 / D4.4 rule pending)") {
    val routes = buildRoutes("/api/v1/mcp")
    val init = post(routes, "/api/v1/mcp/", initFrame, None)
    init.status shouldBe Status.Ok
    init.rawHeader(SessionIdHeader) should not be empty
  }

  test("httpEndpoint = /api/v1/mcp: an unrouted method (PUT) is 404 on the JVM — no route, so the host gate never runs (parity.md rows 13-14)") {
    val routes = buildRoutes("/api/v1/mcp")
    val put = Request(method = Method.PUT, url = URL(Path.decode("/api/v1/mcp")), body = Body.fromString("{}"))
    run(routes, put).status shouldBe Status.NotFound
  }

  test("httpEndpoint without a leading slash behaves like the slash form (stripPrefix at JvmHttpBackend.scala:92)") {
    val routes = buildRoutes("api/v1/mcp")
    post(routes, "/api/v1/mcp", initFrame, None).status shouldBe Status.Ok
  }
