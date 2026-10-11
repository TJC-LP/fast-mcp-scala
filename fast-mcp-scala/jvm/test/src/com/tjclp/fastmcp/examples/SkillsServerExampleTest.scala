package com.tjclp.fastmcp.examples

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.core.skills.Sha256
import com.tjclp.fastmcp.server.router.Session
import com.tjclp.fastmcp.server.transport.MessageLoop
import com.tjclp.fastmcp.skills.SkillTestFixtures.*

/** The shipped [[SkillsServer]] example builds, declares the extension, and serves its in-memory,
  * nested, unlisted and dynamic skills alongside its tool.
  */
class SkillsServerExampleTest extends AnyFunSuite with Matchers:

  test("SkillsServer publishes its skills and tool through McpServerApp") {
    val core = runUnsafe(SkillsServer.buildCore)
    val server = core.asInstanceOf[McpServer[Any]]
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("example"))
    def send(frame: String) = parseJson(runUnsafe(MessageLoop.handleFrame(router, session, frame)).getOrElse(fail("no reply")))

    (send(legacyInitFrame) / "result" / "capabilities" / "extensions").has("io.modelcontextprotocol/skills") shouldBe true
    val listed = (send(legacy(2, "skills/list")) / "result" / "skills").strings("uri")
    listed shouldBe List(
      "skill://acme/reconcile-positions/SKILL.md",
      "skill://acme/reconcile-positions/daily-tieout/SKILL.md",
      "skill://reports/daily/SKILL.md"
    )
    (send(legacy(3, "skills/get", """{"uri":"skill://acme/internal-runbook/SKILL.md"}""")) / "result" / "skill" / "frontmatter" / "name").str shouldBe "internal-runbook"
    (send(legacy(4, "tools/call", """{"name":"add","arguments":{"a":2,"b":3}}""")) / "result" / "content" / 0 / "text").str shouldBe "5"
    (send(legacy(5, "resources/directory/read", """{"uri":"skill://acme/reconcile-positions/templates"}""")) / "result" / "resources").strings("name") shouldBe
      List("drafts", "positions.csv")
    // The nested skill's manifest is a subset of the parent's, with identical digests.
    val parent = (send(legacy(6, "skills/get", """{"uri":"skill://acme/reconcile-positions/SKILL.md"}""")) / "result" / "skill" / "resources").arr
    val child = (send(legacy(7, "skills/get", """{"uri":"skill://acme/reconcile-positions/daily-tieout/SKILL.md"}""")) / "result" / "skill" / "resources").arr
    child.toSet.subsetOf(parent.toSet) shouldBe true
    // The dynamic skill's served SKILL.md frontmatter matches its entry field by field.
    val dynEntry = zio.json.JsonDecoder[Skill].fromJsonAST(send(legacy(8, "skills/get", """{"uri":"skill://reports/daily/SKILL.md"}""")) / "result" / "skill").fold(fail(_), identity)
    val dynMd = (send(legacy(9, "resources/read", """{"uri":"skill://reports/daily/SKILL.md"}""")) / "result" / "contents" / 0 / "text").str
    SkillVerifier.verifySkillMd(HeldEntry("example-host", dynEntry), dynMd.getBytes("UTF-8"), Sha256.digest) shouldBe
      VerificationOutcome.Unverifiable("skill://reports/daily/SKILL.md")
  }
