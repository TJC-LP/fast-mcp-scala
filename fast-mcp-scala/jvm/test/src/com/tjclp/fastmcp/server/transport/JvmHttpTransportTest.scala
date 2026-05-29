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

/** Integration tests for the native JVM HTTP transport.
  *
  * Requests are driven straight through the zio-http `Routes` via `runZIO` — no TCP port is opened,
  * so the suite is deterministic. Covers the streamable session lifecycle (mint → reuse → delete →
  * 404), the SSE channel, unknown-session handling, and the stateless single-shot path.
  */
class JvmHttpTransportTest extends AnyFunSuite with Matchers:

  object TestServer:
    @Tool(name = Some("add"), description = Some("Add two numbers"))
    def add(@Param("a") a: Int, @Param("b") b: Int): Int = a + b

  private val SessionIdHeader = "mcp-session-id"

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""
  private val callFrame =
    """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"add","arguments":{"a":40,"b":2}}}"""
  private val listFrame =
    """{"jsonrpc":"2.0","id":3,"method":"tools/list"}"""

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  /** Build the HTTP routes for a one-tool server (fresh session store per call). */
  private def buildRoutes(stateless: Boolean): Routes[Any, Response] =
    val server = McpServer.typed[Any]("T", "0.1.0", McpServerSettings(stateless = stateless))
    val _ = server.scanAnnotations[TestServer.type]
    runUnsafe(
      server.buildRouter.flatMap(r =>
        JvmTransportBackend.httpRoutes(r, server.settings, ZEnvironment.empty)
      )
    )

  /** Run one request through the routes in-memory. `runZIO` needs a `Scope`; discharge it per
    * request — the responses here carry in-memory bodies (JSON / status-only / SSE headers).
    */
  private def run(routes: Routes[Any, Response], req: Request): Response =
    runUnsafe(ZIO.scoped(routes.runZIO(req)))

  private def post(routes: Routes[Any, Response], body: String, sid: Option[String]): Response =
    val base = Request.post(URL(Path.root / "mcp"), Body.fromString(body))
    val req = sid.fold(base)(s => base.addHeader(Header.Custom(SessionIdHeader, s)))
    run(routes, req)

  private def bodyOf(resp: Response): String = runUnsafe(resp.body.asString)

  test("streamable: initialize mints a session id, reused for tools/call, then deleted") {
    val routes = buildRoutes(stateless = false)

    val initResp = post(routes, initFrame, None)
    initResp.status shouldBe Status.Ok
    val initBody = bodyOf(initResp)
    initBody should include("\"serverInfo\"")
    initBody should not include "logging" // #56: capabilities derived; no logging hook → no logging

    val sid = initResp
      .rawHeader(SessionIdHeader)
      .getOrElse(fail("initialize did not return an mcp-session-id header"))

    // Reuse the durable session for a tool call.
    bodyOf(post(routes, callFrame, Some(sid))) should include("42")

    // Terminate it.
    val delResp = run(
      routes,
      Request.delete(URL(Path.root / "mcp")).addHeader(Header.Custom(SessionIdHeader, sid))
    )
    delResp.status shouldBe Status.Ok

    // The id is now unknown.
    post(routes, listFrame, Some(sid)).status shouldBe Status.NotFound
  }

  test("streamable: GET opens an SSE channel for a known session") {
    val routes = buildRoutes(stateless = false)
    val sid = post(routes, initFrame, None)
      .rawHeader(SessionIdHeader)
      .getOrElse(fail("no session id"))

    // Don't read the SSE body (it blocks on the outbound queue) — assert status + content-type.
    val getResp =
      run(routes, Request.get(URL(Path.root / "mcp")).addHeader(Header.Custom(SessionIdHeader, sid)))
    getResp.status shouldBe Status.Ok
    getResp.rawHeader("content-type").getOrElse("") should include("text/event-stream")
  }

  test("streamable: unknown session id is rejected with 404") {
    val routes = buildRoutes(stateless = false)
    post(routes, listFrame, Some("does-not-exist")).status shouldBe Status.NotFound
  }

  test("stateless: a single POST initialize returns capabilities without logging") {
    val routes = buildRoutes(stateless = true)
    val resp = post(routes, initFrame, None)
    resp.status shouldBe Status.Ok
    val body = bodyOf(resp)
    body should include("\"capabilities\"")
    body should not include "logging"
  }

  test("stateless: GET is method-not-allowed (no SSE channel)") {
    val routes = buildRoutes(stateless = true)
    run(routes, Request.get(URL(Path.root / "mcp"))).status shouldBe Status.MethodNotAllowed
  }

  test("POST with an Accept that excludes application/json is rejected (406)") {
    val routes = buildRoutes(stateless = true)
    val req = Request
      .post(URL(Path.root / "mcp"), Body.fromString(initFrame))
      .addHeader(Header.Custom("accept", "text/plain"))
    run(routes, req).status shouldBe Status.NotAcceptable
  }

  test("POST with an unsupported mcp-protocol-version is rejected (400)") {
    val routes = buildRoutes(stateless = true)
    val req = Request
      .post(URL(Path.root / "mcp"), Body.fromString(initFrame))
      .addHeader(Header.Custom("mcp-protocol-version", "1999-01-01"))
    run(routes, req).status shouldBe Status.BadRequest
  }
