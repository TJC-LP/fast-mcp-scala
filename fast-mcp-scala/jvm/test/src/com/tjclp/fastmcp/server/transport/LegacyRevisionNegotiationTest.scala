package com.tjclp.fastmcp
package server.transport

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.http.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.RegistrationMacro.*
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.router.{McpRouter, Session}
import com.tjclp.fastmcp.server.transport.JvmTransportBackend.given

/** DRAFT (C3 step 10, TJC-2328) — behavioural coverage of legacy `initialize` negotiation for
  * EVERY revision in [[Protocol.LegacyProtocolVersions]] over the streamable HTTP routes (in-memory
  * `routes.runZIO`, no port) and over the stdio-shaped `MessageLoop.handleFrame` path.
  *
  * The coverage map (S1.8) found zero test files mentioning 2024-10-07, 2024-11-05 or 2025-03-26:
  * only the head revision (2025-11-25) and the unknown -> head fallback were asserted. D3 rows
  * 45-46 observed the verbatim echo at runtime; this suite pins it.
  *
  * Cross-check against the TS SDK 1.29.0 pinned by `js/bun.lock` (on disk at
  * `~/.bun/install/cache/@modelcontextprotocol/sdk@1.29.0@@@1/dist/esm/types.js:2-4`):
  * {{{
  * LATEST_PROTOCOL_VERSION = '2025-11-25'
  * DEFAULT_NEGOTIATED_PROTOCOL_VERSION = '2025-03-26'
  * SUPPORTED_PROTOCOL_VERSIONS = [LATEST, '2025-06-18', '2025-03-26', '2024-11-05', '2024-10-07']
  * }}}
  *
  * JSON-RPC batch arrays (a 2025-03-26 client may still send them) must be refused the same way on
  * both transports: `-32700` with a null id (batching was dropped from the spec at 2025-06-18 and
  * `parseFrame` has no array case, MessageLoop.scala:27).
  */
class LegacyRevisionNegotiationTest extends AnyFunSuite with Matchers:

  object NegServer:
    @Tool(name = Some("add"), description = Some("Add two numbers"))
    def add(@Param("a") a: Int, @Param("b") b: Int): Int = a + b

  private val SessionIdHeader = "mcp-session-id"

  private def initFrame(version: String): String =
    s"""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$version","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""

  private val listFrame = """{"jsonrpc":"2.0","id":3,"method":"tools/list"}"""

  private val batchFrame =
    """[{"jsonrpc":"2.0","id":4,"method":"ping"},{"jsonrpc":"2.0","id":5,"method":"tools/list"}]"""

  /** The five revisions as the TS SDK 1.29.0 lists them (newest first). */
  private val TsSdkSupported =
    List("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05", "2024-10-07")

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private def buildRouter(): McpRouter[Any] =
    val server = McpServer.typed[Any]("Neg", "0.1.0", McpServerSettings())
    val _ = server.scanAnnotations[NegServer.type]
    runUnsafe(server.buildRouter)

  private def buildRoutes(): Routes[Any, Response] =
    val server = McpServer.typed[Any]("Neg", "0.1.0", McpServerSettings())
    val _ = server.scanAnnotations[NegServer.type]
    runUnsafe(
      server.buildRouter.flatMap(r =>
        JvmHttpBackend.httpRoutes(r, server.settings, ZEnvironment.empty)
      )
    )

  private def run(routes: Routes[Any, Response], req: Request): Response =
    runUnsafe(ZIO.scoped(routes.runZIO(req)))

  private def post(routes: Routes[Any, Response], body: String, sid: Option[String]): Response =
    val base = Request
      .post(URL(Path.root / "mcp"), Body.fromString(body))
      .addHeader(Header.Custom("content-type", "application/json"))
      .addHeader(Header.Custom("accept", "application/json, text/event-stream"))
    val req = sid.fold(base)(s => base.addHeader(Header.Custom(SessionIdHeader, s)))
    run(routes, req)

  private def bodyOf(resp: Response): String = runUnsafe(resp.body.asString)

  private def get(routes: Routes[Any, Response], sid: String, version: Option[String]): Response =
    val base = Request.get(URL(Path.root / "mcp")).addHeader(Header.Custom(SessionIdHeader, sid))
    run(routes, version.fold(base)(v => base.addHeader(Header.Custom("mcp-protocol-version", v))))

  /** Initialize at `version`; return the minted session id and the SSE body of the reply. */
  private def initAt(routes: Routes[Any, Response], version: String): (String, String) =
    val resp = post(routes, initFrame(version), None)
    withClue(s"initialize at $version: status ${resp.status}") {
      resp.status shouldBe Status.Ok
    }
    val body = bodyOf(resp)
    val sid = resp.rawHeader(SessionIdHeader).getOrElse(fail(s"no session id at $version"))
    (sid, body)

  test("the legacy revision set matches the TS SDK 1.29.0 constants pinned by js/bun.lock") {
    Protocol.LegacyProtocolVersions shouldBe TsSdkSupported
    Protocol.DefaultNegotiatedProtocolVersion shouldBe "2025-03-26"
    Protocol.SupportedProtocolVersions shouldBe ("2026-07-28" :: TsSdkSupported)
  }

  test("streamable HTTP: initialize echoes each of the five legacy revisions verbatim and the session serves tools") {
    val routes = buildRoutes()
    for version <- Protocol.LegacyProtocolVersions do
      val (sid, body) = initAt(routes, version)
      withClue(s"[$version] initialize body: $body ") {
        body should include(s""""protocolVersion":"$version"""")
        body should not include "2026-07-28"
        // Capability shape is the same object for every legacy revision (no per-version projection,
        // D3.17): tools are advertised on all of them.
        body should include(""""tools"""")
        body should include(""""serverInfo"""")
      }
      val list = post(routes, listFrame, Some(sid))
      withClue(s"[$version] tools/list status ${list.status}") {
        list.status shouldBe Status.Ok
        bodyOf(list) should include(""""name":"add"""")
      }
  }

  test("stdio path: MessageLoop.handleFrame echoes each legacy revision verbatim too") {
    val router = buildRouter()
    for version <- Protocol.LegacyProtocolVersions do
      val session = runUnsafe(Session.make(s"stdio-$version"))
      val reply = runUnsafe(MessageLoop.handleFrame(router, session, initFrame(version)))
        .getOrElse(fail(s"no initialize reply at $version"))
      reply should include(s""""protocolVersion":"$version"""")
      runUnsafe(session.protocolVersion) shouldBe version
  }

  test("an unknown legacy revision negotiates to the newest legacy revision, never to 2026-07-28 (spec lifecycle MUST)") {
    val routes = buildRoutes()
    for unknown <- List("2023-01-01", "1999-01-01", "2026-01-01", "latest") do
      val (_, body) = initAt(routes, unknown)
      withClue(s"[$unknown] $body ") {
        body should include(s""""protocolVersion":"${Protocol.LegacyProtocolVersions.head}"""")
        body should not include "2026-07-28"
        body should not include "-32022"
      }
  }

  test("a JSON-RPC batch array from a 2025-03-26 client is refused identically on stdio and HTTP: -32700 with a null id, session intact") {
    // HTTP
    val routes = buildRoutes()
    val (sid, _) = initAt(routes, "2025-03-26")
    val http = post(routes, batchFrame, Some(sid))
    http.status shouldBe Status.BadRequest
    val httpBody = bodyOf(http)
    httpBody should include(""""code":-32700""")
    httpBody should include(""""id":null""")
    // The refused frame did not disturb the session.
    post(routes, listFrame, Some(sid)).status shouldBe Status.Ok

    // stdio-shaped
    val router = buildRouter()
    val session = runUnsafe(Session.make("stdio-batch"))
    val _ = runUnsafe(MessageLoop.handleFrame(router, session, initFrame("2025-03-26")))
    val stdio = runUnsafe(MessageLoop.handleFrame(router, session, batchFrame))
      .getOrElse(fail("stdio produced no reply for the batch array"))
    stdio should include(""""code":-32700""")
    stdio should include(""""id":null""")
    val afterBatch = runUnsafe(MessageLoop.handleFrame(router, session, listFrame))
      .getOrElse(fail("no tools/list reply after the batch"))
    afterBatch should include(""""name":"add"""")

    // Same JSON-RPC error object on both transports (the HTTP body is the router's frame).
    httpBody shouldBe stdio
  }

  test("GET push channel: a header-less GET assumes 2025-03-26; every legacy header passes; an unknown header is 400") {
    val routes = buildRoutes()
    // One fresh session per GET (a second GET on the same session is 409 by design).
    val (noHeaderSid, _) = initAt(routes, "2025-03-26")
    val noHeader = get(routes, noHeaderSid, None)
    noHeader.status shouldBe Status.Ok
    noHeader.rawHeader("content-type").getOrElse("") should include("text/event-stream")

    for version <- Protocol.LegacyProtocolVersions do
      val (sid, _) = initAt(routes, version)
      withClue(s"GET with mcp-protocol-version: $version") {
        get(routes, sid, Some(version)).status shouldBe Status.Ok
      }

    val (badSid, _) = initAt(routes, "2025-11-25")
    val bad = get(routes, badSid, Some("1999-01-01"))
    bad.status shouldBe Status.BadRequest
    bodyOf(bad) should include("Unsupported mcp-protocol-version header")
  }
