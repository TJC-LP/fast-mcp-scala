package com.tjclp.fastmcp.conformance

import java.nio.charset.StandardCharsets

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import com.tjclp.fastmcp.core.skills.Sha256

/** An independent MCP client — the official TypeScript SDK, over a real stdio pipe — against the
  * JVM `SkillsServer` example. Exercises the Skills extension the way a host would: read the
  * declaration from the initialize capabilities, enumerate with `skills/list`, resolve an unlisted
  * skill with `skills/get`, fetch files with `resources/read` and verify size + digest against the
  * entry (with the portable SHA-256 on the Bun runtime), and browse with
  * `resources/directory/read`. Mirrors the checks of the upstream conformance scenarios
  * `sep-2640-skills-enumeration` / `-manifest` / `-directory` (modelcontextprotocol/conformance
  * commit 7169291) for a 2025-11-25 session.
  */
@SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
class SkillsConformanceTest extends AsyncFlatSpec with Matchers with BeforeAndAfterAll:

  override implicit val executionContext: ExecutionContext = ExecutionContext.global

  private val ServerClass = "com.tjclp.fastmcp.examples.SkillsServer"
  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  private var client: McpTestClient = scala.compiletime.uninitialized

  override def afterAll(): Unit =
    if client != null then client.close()
    super.afterAll()

  private def ensureClient(): Future[McpTestClient] =
    if client != null then Future.successful(client)
    else McpTestClient.connectStdio(ServerClass).map { c => client = c; c }

  private def arr(v: js.Dynamic): Seq[js.Dynamic] = v.asInstanceOf[js.Array[js.Dynamic]].toSeq
  private def str(v: js.Dynamic): String = v.asInstanceOf[String]
  private def digestOf(text: String): String =
    "sha256:" + Sha256.hex(Sha256.digest(text.getBytes(StandardCharsets.UTF_8)))

  "initialize" should "declare io.modelcontextprotocol/skills inline with directoryRead: true, plus resources" in {
    ensureClient().map { c =>
      val caps = c.rawServerCapabilities.getOrElse(fail("no capabilities"))
      val skills = caps.extensions.selectDynamic("io.modelcontextprotocol/skills")
      js.isUndefined(skills) shouldBe false
      skills.directoryRead.asInstanceOf[Boolean] shouldBe true
      js.isUndefined(caps.resources) shouldBe false
    }
  }

  "skills/list" should "return complete entries whose final path segment equals frontmatter.name" in {
    ensureClient().flatMap(_.rawRequest("skills/list", js.Dynamic.literal())).map { result =>
      val entries = arr(result.skills)
      entries.map(e => str(e.uri)) shouldBe Seq(
        "skill://acme/reconcile-positions/SKILL.md",
        "skill://acme/reconcile-positions/daily-tieout/SKILL.md",
        "skill://reports/daily/SKILL.md"
      )
      // 2025-11-25 session: no cache attributes on the wire.
      js.isUndefined(result.ttlMs) shouldBe true
      entries.foreach { e =>
        val uri = str(e.uri)
        val name = uri.stripSuffix("/SKILL.md").split('/').last
        str(e.frontmatter.name) shouldBe name
        name should fullyMatch regex "[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?"
      }
      val reconcile = entries.head
      val resources = arr(reconcile.resources)
      resources.map(r => str(r.uri)) should contain("skill://acme/reconcile-positions/SKILL.md")
      resources.map(r => str(r.uri)).distinct.size shouldBe resources.size
      resources.foreach { r =>
        str(r.digest) should fullyMatch regex "sha256:[0-9a-f]{64}"
        r.size.asInstanceOf[Double] should be >= 0.0
        str(r.uri) should startWith("skill://acme/reconcile-positions/")
      }
      str(reconcile.frontmatter.license) shouldBe "MIT"
      str(entries(2).resources) shouldBe "dynamic"
    }
  }

  "skills/get" should "resolve an unlisted skill and answer -32602 for an unknown one" in {
    ensureClient().flatMap { c =>
      for
        hidden <- c.rawRequest("skills/get", js.Dynamic.literal(uri = "skill://acme/internal-runbook/SKILL.md"))
        missing <- c.rawRequest("skills/get", js.Dynamic.literal(uri = "skill://acme/nope/SKILL.md")).map(_ => None).recover {
          case e: Throwable => Some(e.getMessage)
        }
      yield
        str(hidden.skill.frontmatter.name) shouldBe "internal-runbook"
        js.isUndefined(hidden.nextCursor) shouldBe true
        missing.getOrElse("") should include("-32602")
    }
  }

  "resources/read" should "serve SKILL.md and a text reference whose size and digest match the entry" in {
    ensureClient().flatMap { c =>
      for
        entry <- c.rawRequest("skills/get", js.Dynamic.literal(uri = "skill://acme/reconcile-positions/SKILL.md"))
        md <- c.readResource("skill://acme/reconcile-positions/SKILL.md")
        rules <- c.readResource("skill://acme/reconcile-positions/references/rules.md")
      yield
        val manifest = arr(entry.skill.resources).map(r => str(r.uri) -> (str(r.digest), r.size.asInstanceOf[Double].toLong)).toMap
        val mdText = md.contents.headOption.flatMap(_.text.toOption).getOrElse(fail("no text"))
        mdText should startWith("---\nname: reconcile-positions\n")
        md.contents.head.mimeType.toOption shouldBe Some("text/markdown")
        manifest("skill://acme/reconcile-positions/SKILL.md") shouldBe
          (digestOf(mdText), mdText.getBytes(StandardCharsets.UTF_8).length.toLong)
        val rulesText = rules.contents.headOption.flatMap(_.text.toOption).getOrElse(fail("no text"))
        manifest("skill://acme/reconcile-positions/references/rules.md") shouldBe
          (digestOf(rulesText), rulesText.getBytes(StandardCharsets.UTF_8).length.toLong)
    }
  }

  "resources/directory/read" should "list direct children with directory markers and reject a file URI" in {
    ensureClient().flatMap { c =>
      for
        root <- c.rawRequest("resources/directory/read", js.Dynamic.literal(uri = "skill://acme/reconcile-positions"))
        drafts <- c.rawRequest("resources/directory/read", js.Dynamic.literal(uri = "skill://acme/reconcile-positions/templates/drafts"))
        dynamic <- c.rawRequest("resources/directory/read", js.Dynamic.literal(uri = "skill://reports/daily"))
        notDir <- c.rawRequest("resources/directory/read", js.Dynamic.literal(uri = "skill://acme/reconcile-positions/SKILL.md")).map(_ => None).recover {
          case e: Throwable => Some(e.getMessage)
        }
      yield
        val children = arr(root.resources)
        children.map(r => str(r.name)) shouldBe Seq("assets", "daily-tieout", "reconcile-positions", "references", "templates")
        children.filter(r => str(r.mimeType) == "inode/directory").map(r => str(r.name)) shouldBe Seq("assets", "daily-tieout", "references", "templates")
        arr(drafts.resources) shouldBe empty
        arr(dynamic.resources).map(r => str(r.name)) shouldBe Seq("daily", "data")
        notDir.getOrElse("") should include("-32602")
    }
  }
