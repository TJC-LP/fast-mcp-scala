package com.tjclp.fastmcp.server.transport

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.http.*

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.skills.SkillTestFixtures.*

/** The Skills extension over the JVM HTTP transport, both eras: a 2026-07-28 stateless POST
  * transcript (discover → list → get → read → directory) and the legacy streamable session flow.
  */
class SkillsHttpTransportTest extends AnyFunSuite with Matchers:

  private def routes(stateless: Boolean): Routes[Any, Response] =
    val server = McpServer.typed[Any]("HttpSkills", "0.1.0", McpServerSettings(stateless = stateless))
    runUnsafe(server.skills(List(refunds, gitWorkflow)))
    runUnsafe(server.buildRouter.flatMap(r => JvmHttpBackend.httpRoutes(r, server.settings, ZEnvironment.empty)))

  private def run(routes: Routes[Any, Response], req: Request): Response = runUnsafe(ZIO.scoped(routes.runZIO(req)))
  private def bodyOf(resp: Response): String = runUnsafe(resp.body.asString)

  private def modernPost(routes: Routes[Any, Response], body: String, method: String, name: Option[String] = None): Response =
    val base = Request
      .post(URL(Path.root / "mcp"), Body.fromString(body))
      .addHeader(Header.Custom("content-type", "application/json"))
      .addHeader(Header.Custom("accept", "application/json, text/event-stream"))
      .addHeader(Header.Custom("mcp-protocol-version", "2026-07-28"))
      .addHeader(Header.Custom("mcp-method", method))
    run(routes, name.fold(base)(n => base.addHeader(Header.Custom("mcp-name", n))))

  private def legacyPost(routes: Routes[Any, Response], body: String, sid: Option[String]): Response =
    val base = Request
      .post(URL(Path.root / "mcp"), Body.fromString(body))
      .addHeader(Header.Custom("content-type", "application/json"))
      .addHeader(Header.Custom("accept", "application/json, text/event-stream"))
    run(routes, sid.fold(base)(s => base.addHeader(Header.Custom("mcp-session-id", s))))

  /** Extract the JSON payload from a JSON or SSE body. */
  private def payload(resp: Response): zio.json.ast.Json =
    val body = bodyOf(resp)
    val data = body.linesIterator.find(_.startsWith("data:")).map(_.stripPrefix("data:").trim).getOrElse(body)
    parseJson(data)

  test("modern stateless HTTP: full skills transcript, every result complete with cache attributes") {
    val r = routes(stateless = true)
    val disc = modernPost(r, modern(1, "server/discover"), "server/discover")
    disc.status shouldBe Status.Ok
    (payload(disc) / "result" / "capabilities" / "extensions" / "io.modelcontextprotocol/skills" / "directoryRead") shouldBe zio.json.ast.Json.Bool(true)

    val list = payload(modernPost(r, modern(2, "skills/list"), "skills/list")) / "result"
    (list / "resultType").str shouldBe "complete"
    (list / "ttlMs").num shouldBe 0L
    (list / "skills").strings("uri") shouldBe List("skill://acme/billing/refunds/SKILL.md", "skill://git-workflow/SKILL.md")

    val got = payload(modernPost(r, modern(3, "skills/get", """"uri":"skill://acme/billing/refunds/SKILL.md""""), "skills/get")) / "result"
    (got / "skill" / "frontmatter" / "name").str shouldBe "refunds"
    (got / "cacheScope").str shouldBe "private"

    // resources/read needs the Mcp-Name header carrying the uri (base protocol rule).
    val read = payload(modernPost(r, modern(4, "resources/read", """"uri":"skill://acme/billing/refunds/references/policy.md""""), "resources/read", Some("skill://acme/billing/refunds/references/policy.md")))
    (read / "result" / "contents" / 0 / "text").str shouldBe "# Policy\r\n\r\nRefund within 30 days.\r\n"

    val dir = payload(modernPost(r, modern(5, "resources/directory/read", """"uri":"skill://acme/billing/refunds""""), "resources/directory/read")) / "result"
    (dir / "resultType").str shouldBe "complete"
    (dir / "resources").strings("name") shouldBe List("assets", "references", "refunds", "templates")

    val unknown = modernPost(r, modern(6, "skills/get", """"uri":"skill://nope/SKILL.md""""), "skills/get")
    (payload(unknown) / "error" / "code").num shouldBe -32602L
    (payload(unknown) / "id").num shouldBe 6L

    // Mcp-Method must match the body; a mismatch is a transport-level 400.
    modernPost(r, modern(7, "skills/list"), "skills/get").status shouldBe Status.BadRequest
  }

  test("legacy streamable HTTP: initialize mints a session that serves the skills methods without cache attributes") {
    val r = routes(stateless = false)
    val init = legacyPost(r, legacyInitFrame, None)
    init.status shouldBe Status.Ok
    val sid = init.rawHeader("mcp-session-id").getOrElse(fail("no session id"))
    (payload(init) / "result" / "capabilities" / "extensions").has("io.modelcontextprotocol/skills") shouldBe true
    val list = payload(legacyPost(r, legacy(2, "skills/list"), Some(sid))) / "result"
    list.has("ttlMs") shouldBe false
    list.has("resultType") shouldBe false
    (list / "skills").arr.size shouldBe 2
    val dir = payload(legacyPost(r, legacy(3, "resources/directory/read", """{"uri":"skill://git-workflow"}"""), Some(sid))) / "result"
    (dir / "resources").strings("uri") shouldBe List("skill://git-workflow/SKILL.md")
    val miss = payload(legacyPost(r, legacy(4, "resources/read", """{"uri":"skill://git-workflow/nope.md"}"""), Some(sid)))
    (miss / "error" / "code").num shouldBe -32002L
  }
