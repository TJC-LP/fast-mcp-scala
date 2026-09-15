package com.tjclp.fastmcp.server.skills

import java.util.concurrent.atomic.AtomicInteger

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.Chunk
import zio.json.ast.Json

import com.tjclp.fastmcp.core.Cursor
import com.tjclp.fastmcp.core.skills.*
import com.tjclp.fastmcp.core.wire.{Resource, Skill, SkillFrontmatter, SkillResources, Skills}
import com.tjclp.fastmcp.jsonrpc.McpError
import com.tjclp.fastmcp.server.{McpContext, SkillSettings}
import com.tjclp.fastmcp.skills.SkillTestFixtures.*

/** Publication semantics of the catalog: atomic publish/replace/remove, unlisted lookup, conflicts,
  * nested overlap, providers, pagination cursors, directory reads, concurrency, and that metadata
  * discovery never reads supporting files.
  */
class SkillRegistryTest extends AnyFunSuite with Matchers:

  private val ctx = McpContext.empty

  private def registry(settings: SkillSettings = SkillSettings(), external: String => Option[String] = _ => None) =
    SkillRegistry.make[Any](settings, Sha256.digest, external)

  private def publish(r: SkillRegistry[Any], skills: McpSkill*): Unit = runAny(r.publish(skills.toList))

  private def publishErr(r: SkillRegistry[Any], skills: McpSkill*): SkillError =
    runAny(r.publish(skills.toList).either).fold(identity, _ => fail("expected a publication failure"))

  private def fm(name: String, description: String): SkillFrontmatter =
    SkillFrontmatter.fromFields(Chunk("name" -> Json.Str(name), "description" -> Json.Str(description))).fold(fail(_), identity)

  /** A dynamic provider that counts every content read. */
  class CountingProvider(ns: String) extends SkillProvider[Any]:
    val reads = new AtomicInteger(0)
    val root = SkillUri.unsafeParse(ns)
    val skillMd = s"$ns/SKILL.md"
    val entry = Skill(skillMd, fm(root.lastSegment, "Dynamic content. Use in tests."), SkillResources.Dynamic)
    override def namespaces: List[SkillUri] = List(root)
    override def list(context: McpContext) = ZIO.succeed(List(entry))
    override def get(uri: String, context: McpContext) = ZIO.succeed(Option.when(uri == skillMd)(entry))
    override def read(uri: String, context: McpContext) =
      ZIO.succeed(reads.incrementAndGet()) *> ZIO.succeed(
        Option.when(uri == skillMd)(SkillFileContent(s"---\nname: ${root.lastSegment}\ndescription: Dynamic content. Use in tests.\n---\n"))
      )
    override def readDirectory(uri: String, context: McpContext) =
      ZIO.succeed(Option.when(uri == ns)(List(Resource(skillMd, root.lastSegment, mimeType = Some("text/markdown")))))

  test("publish → list/get/read/directory; empty registry lists nothing and gets nothing") {
    val r = registry()
    r.isEmpty shouldBe true
    runUnsafe(r.listAll(ctx)) shouldBe Nil
    runUnsafe(r.get("skill://acme/billing/refunds/SKILL.md", ctx)) shouldBe None
    publish(r, refunds, gitWorkflow)
    r.nonEmpty shouldBe true
    runUnsafe(r.listAll(ctx)).map(_.uri) shouldBe List("skill://acme/billing/refunds/SKILL.md", "skill://git-workflow/SKILL.md")
    runUnsafe(r.get("skill://acme/billing/refunds/SKILL.md", ctx)).map(_.frontmatter.name) shouldBe Some("refunds")
    runUnsafe(r.readFile("skill://acme/billing/refunds/templates/email.md", ctx)).map(_.body) shouldBe Some("Dear {{customer}},\n")
    runUnsafe(r.readFile("skill://acme/billing/refunds", ctx)) shouldBe None // a directory is not a file
    runUnsafe(r.readDirectoryAll("skill://acme/billing/refunds/templates", ctx)).map(_.map(_.name)) shouldBe
      Some(List("drafts", "email.md", "regional"))
    runUnsafe(r.readDirectoryAll("skill://acme/billing/refunds/templates/email.md", ctx)) shouldBe None
    runUnsafe(r.readDirectoryAll("skill://acme/billing", ctx)) shouldBe None // above the skill root: not served
    // ResourceSource view: files of listed skills only, never directories.
    r.listDefinitions().map(_.uri) should contain("skill://acme/billing/refunds/assets/logo.bin")
    r.listDefinitions().map(_.uri) should not contain "skill://acme/billing/refunds"
    r.definition("skill://acme/billing/refunds/SKILL.md").flatMap(_.name) shouldBe Some("refunds")
    r.owns("skill://acme/billing/refunds/templates/drafts") shouldBe true
  }

  test("unlisted skills resolve by URI but never appear in listings") {
    val r = registry()
    publish(r, refunds.unlisted)
    runUnsafe(r.listAll(ctx)) shouldBe Nil
    r.listDefinitions() shouldBe Nil
    runUnsafe(r.get("skill://acme/billing/refunds/SKILL.md", ctx)).isDefined shouldBe true
    runUnsafe(r.readFile("skill://acme/billing/refunds/references/policy.md", ctx)).isDefined shouldBe true
    runUnsafe(r.readDirectoryAll("skill://acme/billing/refunds", ctx)).isDefined shouldBe true
  }

  test("replace is atomic by root and bumps the generation; remove withdraws every URI") {
    val r = registry()
    publish(r, refunds)
    val g1 = r.generation
    val v2 = McpSkill.fromMarkdown("acme/billing/refunds", refundsMarkdown, Map("only.md" -> SkillFile.text("v2")))
    publish(r, v2)
    r.generation shouldBe g1 + 1
    runUnsafe(r.readFile("skill://acme/billing/refunds/references/policy.md", ctx)) shouldBe None
    runUnsafe(r.readFile("skill://acme/billing/refunds/only.md", ctx)).map(_.body) shouldBe Some("v2")
    runUnsafe(r.get("skill://acme/billing/refunds/SKILL.md", ctx)).map(_.resources.staticEntries.size) shouldBe Some(2)
    runUnsafe(r.remove(List("skill://acme/billing/refunds")))
    runUnsafe(r.get("skill://acme/billing/refunds/SKILL.md", ctx)) shouldBe None
    runUnsafe(r.readFile("skill://acme/billing/refunds/only.md", ctx)) shouldBe None
    r.isEmpty shouldBe true
  }

  test("a failing batch publishes nothing (atomic rollback), and limits are enforced from settings") {
    val r = registry(SkillSettings(maxResourcesPerSkill = 2))
    val ok = simpleSkill("acme/ok")
    val tooMany = McpSkill.fromMarkdown("acme/big", "---\nname: big\ndescription: d\n---\n", Map("a" -> SkillFile.text(""), "b" -> SkillFile.text("")))
    publishErr(r, ok, tooMany) should matchPattern { case SkillError.LimitExceeded(_, _, 3L, 2L) => }
    r.isEmpty shouldBe true
    publishErr(r, ok, ok) should matchPattern { case SkillError.ConflictingResource(_, _, _) => }
    r.isEmpty shouldBe true
    publish(r, ok)
  }

  test("duplicate names at different paths are legitimate; the same URI with different bytes is a conflict") {
    val r = registry()
    publish(r, simpleSkill("acme/billing/refunds"), simpleSkill("acme/support/refunds"))
    runUnsafe(r.listAll(ctx)).map(_.frontmatter.name) shouldBe List("refunds", "refunds")
    // Two distinct roots cannot publish the same file URI unless one nests in the other, and then
    // the content must be identical.
    val parent = McpSkill.fromMarkdown("acme/parent", "---\nname: parent\ndescription: d\n---\n",
      Map("child/SKILL.md" -> SkillFile.text("---\nname: child\ndescription: c\n---\n"), "child/ref.md" -> SkillFile.text("same")))
    val childOk = parent.nestedSkill("child").fold(e => fail(e.message), identity)
    publish(r, parent, childOk) // legitimate shared membership
    runUnsafe(r.get("skill://acme/parent/child/SKILL.md", ctx)).map(_.resources.staticEntries.size) shouldBe Some(2)
    val childDiff = McpSkill.fromMarkdown("acme/parent/child", "---\nname: child\ndescription: c\n---\n", Map("ref.md" -> SkillFile.text("DIFFERENT")))
    publishErr(r, childDiff) should matchPattern { case SkillError.ConflictingResource(_, "skill://acme/parent/child/ref.md", _) => }
    // A nested skill with a file the parent does not list contradicts the parent's completeness.
    val childExtra = McpSkill.fromMarkdown("acme/parent/child", "---\nname: child\ndescription: c\n---\n",
      Map("ref.md" -> SkillFile.text("same"), "extra.md" -> SkillFile.text("x")))
    publishErr(r, childExtra).message should include("missing from the enclosing skill")
    // Same URI as a file in one skill and a directory in another → conflict.
    val fileAtDir = McpSkill.fromMarkdown("acme/parent2", "---\nname: parent2\ndescription: d\n---\n", Map("x" -> SkillFile.text("f")))
    val dirAtFile = McpSkill.fromMarkdown("acme/parent2/x", "---\nname: x\ndescription: d\n---\n")
    publishErr(r, fileAtDir, dirAtFile) should matchPattern { case SkillError.ConflictingResource(_, _, _) => }
    // Different frontmatter for the nested SKILL.md is different content too.
    val childOtherFm = McpSkill.fromMarkdown("acme/parent/child", "---\nname: child\ndescription: different\n---\n", Map("ref.md" -> SkillFile.text("same")))
    publishErr(r, childOtherFm) should matchPattern { case SkillError.ConflictingResource(_, "skill://acme/parent/child/SKILL.md", _) => }
  }

  test("conflicts with existing resource registrations are detected before publication") {
    val r = registry(external = uri => Option.when(uri == "skill://acme/billing/refunds/references/policy.md")("already registered as a static resource"))
    publishErr(r, refunds) should matchPattern {
      case SkillError.ConflictingResource(_, "skill://acme/billing/refunds/references/policy.md", "already registered as a static resource") =>
    }
    r.isEmpty shouldBe true
  }

  test("providers: namespaces must not overlap skills or each other; entries are validated and routed") {
    val r = registry()
    publish(r, refunds)
    val overlapping = new CountingProvider("skill://acme/billing")
    runAny(r.addProvider(overlapping).either).isLeft shouldBe true
    val inside = new CountingProvider("skill://acme/billing/refunds/templates")
    runAny(r.addProvider(inside).either).isLeft shouldBe true
    val ok = new CountingProvider("skill://reports/daily")
    runAny(r.addProvider(ok))
    runAny(r.addProvider(new CountingProvider("skill://reports")).either).isLeft shouldBe true // covers ok
    runAny(r.addProvider(new CountingProvider("skill://reports/daily/sub")).either).isLeft shouldBe true
    // Publishing a skill into a provider's namespace is refused too.
    publishErr(r, simpleSkill("reports/daily/extra")) should matchPattern { case SkillError.ConflictingResource(_, _, _) => }
    runUnsafe(r.listAll(ctx)).map(_.uri) shouldBe List("skill://acme/billing/refunds/SKILL.md", "skill://reports/daily/SKILL.md")
    runUnsafe(r.get("skill://reports/daily/SKILL.md", ctx)).map(_.resources) shouldBe Some(SkillResources.Dynamic)
    runUnsafe(r.get("skill://reports/daily/other/SKILL.md", ctx)) shouldBe None
    runUnsafe(r.readDirectoryAll("skill://reports/daily", ctx)).map(_.map(_.uri)) shouldBe Some(List("skill://reports/daily/SKILL.md"))
    ok.reads.get() shouldBe 0 // list, get and directory reads never touched content
    runUnsafe(r.readFile("skill://reports/daily/SKILL.md", ctx)).isDefined shouldBe true
    ok.reads.get() shouldBe 1
    // A provider returning an entry outside its namespace or malformed is an internal error, never republished.
    val liar = new SkillProvider[Any]:
      override def namespaces = List(SkillUri.unsafeParse("skill://liar"))
      override def list(context: McpContext) = ZIO.succeed(List(Skill("skill://elsewhere/x/SKILL.md", fm("x", "d"), SkillResources.Dynamic)))
      override def get(uri: String, context: McpContext) = ZIO.none
      override def read(uri: String, context: McpContext) = ZIO.none
      override def readDirectory(uri: String, context: McpContext) = ZIO.none
    runAny(r.addProvider(liar))
    runUnsafe(r.listAll(ctx).either).left.map(_.code) shouldBe Left(-32603)
    // A provider without directory support switches the advertisement off for the whole server.
    r.directoryReadSupported shouldBe true
    val noDir = new CountingProvider("skill://nodir/x") { override def supportsDirectoryRead = false }
    runAny(r.addProvider(noDir))
    r.directoryReadSupported shouldBe false
    // A provider must declare a namespace.
    val nameless = new CountingProvider("skill://nameless/x") { override def namespaces = Nil }
    runAny(r.addProvider(nameless).either).isLeft shouldBe true
  }

  test("pagination: deterministic pages, atomic entries, empty terminal page, rejected bad/stale/foreign cursors") {
    val r = registry(SkillSettings(listPageSize = 2, directoryPageSize = 2))
    val skills = (1 to 5).map(i => simpleSkill(s"acme/s$i"))
    publish(r, skills*)
    def page(c: Option[Cursor]) = runUnsafe(r.listPage(c, ctx))
    val (p1, c1) = page(None)
    val (p2, c2) = page(c1)
    val (p3, c3) = page(c2)
    p1.size shouldBe 2; p2.size shouldBe 2; p3.size shouldBe 1
    c3 shouldBe None
    (p1 ++ p2 ++ p3).map(_.uri) shouldBe skills.map(_.uri.render).sorted
    (p1 ++ p2 ++ p3).map(_.uri).distinct.size shouldBe 5
    p1.forall(_.resources.staticEntries.nonEmpty) shouldBe true // entries are whole, never split
    // Cursor validation.
    runUnsafe(r.listPage(Some(Cursor("garbage")), ctx).either).left.map(_.code) shouldBe Left(-32602)
    runUnsafe(r.listPage(Some(Cursor("fms1.!!!")), ctx).either).left.map(_.code) shouldBe Left(-32602)
    // A directory cursor is foreign to skills/list.
    val (_, dc) = runUnsafe(r.readDirectoryPage("skill://acme/s1", None, ctx)).get
    dc shouldBe None // a 1-child directory has no next page
    val big = McpSkill.fromMarkdown("acme/dir", "---\nname: dir\ndescription: d\n---\n", (1 to 5).map(i => s"f$i.md" -> SkillFile.text("")).toMap)
    publish(r, big)
    val Some((d1, Some(dcur))) = runUnsafe(r.readDirectoryPage("skill://acme/dir", None, ctx)): @unchecked
    d1.size shouldBe 2
    runUnsafe(r.listPage(Some(dcur), ctx).either).left.map(_.message) match
      case Left(m) => m should include("issued by resources/directory/read")
      case Right(_) => fail("a directory cursor must not page skills/list")
    // The same directory cursor on another directory is cross-scope.
    runUnsafe(r.readDirectoryPage("skill://acme/s1", Some(dcur), ctx).either).left.map(_.code) shouldBe Left(-32602)
    // Directory pages concatenate to all six children without duplicates or omissions.
    def dirAll(c: Option[Cursor], acc: List[Resource]): List[Resource] =
      runUnsafe(r.readDirectoryPage("skill://acme/dir", c, ctx)).get match
        case (items, None) => acc ++ items
        case (items, next) => dirAll(next, acc ++ items)
    dirAll(None, Nil).map(_.name) shouldBe List("dir", "f1.md", "f2.md", "f3.md", "f4.md", "f5.md")
    // A publish makes the old list cursor stale.
    val (_, cursorBefore) = page(None)
    publish(r, simpleSkill("acme/s6"))
    runUnsafe(r.listPage(cursorBefore, ctx).either).left.map(_.message) match
      case Left(m) => m should include("stale")
      case Right(_) => fail("cursor from the previous generation must be rejected")
  }

  test("a different caller seeing a different catalog cannot reuse a cursor (authorization isolation by content)") {
    val r = registry(SkillSettings(listPageSize = 1))
    publish(r, simpleSkill("acme/a"), simpleSkill("acme/b"), simpleSkill("acme/c"))
    val scoped = new SkillProvider[Any]:
      override def namespaces = List(SkillUri.unsafeParse("skill://tenant"))
      private val e = Skill("skill://tenant/private/SKILL.md", fm("private", "Tenant-only. Use in tests."), SkillResources.Dynamic)
      override def list(context: McpContext) = ZIO.succeed(if context.requestMetadata("tenant").isDefined then List(e) else Nil)
      override def get(uri: String, context: McpContext) = ZIO.succeed(Option.when(uri == e.uri && context.requestMetadata("tenant").isDefined)(e))
      override def read(uri: String, context: McpContext) = ZIO.none
      override def readDirectory(uri: String, context: McpContext) = ZIO.none
    runAny(r.addProvider(scoped))
    val tenant = McpContext.withSession(runUnsafe(com.tjclp.fastmcp.server.router.Session.make("t")), requestMeta = Some(Map("tenant" -> Json.Str("x"))))
    val (_, tenantCursor) = runUnsafe(r.listPage(None, tenant))
    runUnsafe(r.listPage(tenantCursor, ctx).either).left.map(_.code) shouldBe Left(-32602)
    runUnsafe(r.listPage(tenantCursor, tenant)).isInstanceOf[(List[Skill], Option[Cursor])] shouldBe true
    runUnsafe(r.get("skill://tenant/private/SKILL.md", ctx)) shouldBe None
    runUnsafe(r.get("skill://tenant/private/SKILL.md", tenant)).isDefined shouldBe true
  }

  test("concurrent republishing never exposes a half-published generation") {
    val r = registry()
    val roots = (1 to 4).map(i => s"acme/c$i")
    def gen(tag: String) = roots.map(p => McpSkill.fromMarkdown(p, s"---\nname: ${p.split('/').last}\ndescription: gen $tag\n---\n", Map("data.txt" -> SkillFile.text(tag)))).toList
    publish(r, gen("A")*)
    val program =
      for
        stop <- Ref.make(false)
        writer <- (r.publish(gen("B")) *> r.publish(gen("A"))).repeatUntilZIO(_ => stop.get).fork
        readers <- ZIO.foreachPar(1 to 8) { _ =>
          r.listAll(ctx).map { entries =>
            val descs = entries.map(_.frontmatter.description).distinct
            descs.size == 1 && entries.size == 4
          }.repeatN(200).map(_ => ())
        }.fork
        _ <- readers.join
        _ <- stop.set(true)
        _ <- writer.join
        finalList <- r.listAll(ctx)
      yield finalList.map(_.frontmatter.description).distinct.size
    runAny(program) shouldBe 1
  }

  test("registry-level provider failures surface as -32603, McpErrors pass through") {
    val r = registry()
    val failing = new SkillProvider[Any]:
      override def namespaces = List(SkillUri.unsafeParse("skill://fail"))
      override def list(context: McpContext) = ZIO.fail(new RuntimeException("backend down"))
      override def get(uri: String, context: McpContext) = ZIO.fail(McpError.invalidParams("nope"))
      override def read(uri: String, context: McpContext) = ZIO.none
      override def readDirectory(uri: String, context: McpContext) = ZIO.none
    runAny(r.addProvider(failing))
    val listErr = runUnsafe(r.listAll(ctx).either).left.getOrElse(fail("expected failure"))
    listErr.code shouldBe -32603
    listErr.message should include("backend down")
    runUnsafe(r.get("skill://fail/x/SKILL.md", ctx).either).left.map(_.code) shouldBe Left(-32602)
  }
