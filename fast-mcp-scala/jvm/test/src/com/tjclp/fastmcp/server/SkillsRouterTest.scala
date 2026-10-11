package com.tjclp.fastmcp.server

import java.util.Base64

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.ast.Json

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.core.skills.SkillSnapshot
import com.tjclp.fastmcp.examples.DailyReportSkills
import com.tjclp.fastmcp.skills.SkillTestFixtures.*

/** The Skills extension through the real router in both protocol eras: capabilities, the three
  * methods, result envelopes, error codes with preserved ids, ordinary resource routing of skill
  * files, coexistence with tools/prompts/resources/tasks, and the disabled / empty-catalog states.
  */
class SkillsRouterTest extends AnyFunSuite with Matchers:

  object Ordinary:
    @Tool(name = Some("add"), description = Some("Add"))
    def add(@Param("a") a: Int, @Param("b") b: Int): Int = a + b

    @Prompt(name = Some("hello"), description = Some("Hello"))
    def hello(): List[Message] = List(Message(Role.User, TextContent("hi")))

  private def server(settings: McpServerSettings = McpServerSettings(), skills: List[McpSkill] = List(refunds, gitWorkflow, simpleSkill("acme/hidden").unlisted)) =
    val s = McpServer("SkillsRouter", "0.1.0", settings)
    val _ = s.scanAnnotations[Ordinary.type]
    runUnsafe(s.resource(McpStaticResource("static://welcome", name = Some("welcome"))("hello")))
    runUnsafe(s.skills(skills))
    runUnsafe(s.skillProvider(DailyReportSkills))
    s

  private val ExtKey = "io.modelcontextprotocol/skills"

  test("legacy initialize declares the extension (inline settings) and resources; modern discover does too") {
    val h = harness(server())
    val caps = h.initLegacy() / "result" / "capabilities"
    caps / "extensions" / ExtKey shouldBe Json.Obj("directoryRead" -> Json.Bool(true))
    (caps / "resources").has("subscribe") shouldBe false
    caps.has("resources") shouldBe true
    caps.has("tools") shouldBe true
    caps.has("prompts") shouldBe true
    val disc = h.send(modern(2, "server/discover")) / "result"
    disc / "capabilities" / "extensions" / ExtKey shouldBe Json.Obj("directoryRead" -> Json.Bool(true))
    (disc / "capabilities").has("resources") shouldBe true
    (disc / "resultType").str shouldBe "complete"
  }

  test("skills/list: legacy strips cache attributes, modern carries resultType/ttlMs/cacheScope; entries are complete manifests") {
    val h = harness(server())
    h.initLegacy()
    val legacyRes = h.send(legacy(2, "skills/list")) / "result"
    legacyRes.has("ttlMs") shouldBe false
    legacyRes.has("cacheScope") shouldBe false
    legacyRes.has("resultType") shouldBe false
    val listed = (legacyRes / "skills").arr
    listed.map(e => (e / "uri").str) shouldBe List(
      "skill://acme/billing/refunds/SKILL.md",
      "skill://git-workflow/SKILL.md",
      "skill://reports/daily/SKILL.md"
    ) // sorted; the unlisted skill is absent; the dynamic provider is present
    val refundsEntry = listed.head
    (refundsEntry / "frontmatter" / "name").str shouldBe "refunds"
    (refundsEntry / "frontmatter" / "license").str shouldBe "Apache-2.0"
    (refundsEntry / "frontmatter" / "metadata" / "version").str shouldBe "2.1"
    val resources = (refundsEntry / "resources").arr
    resources.map(e => (e / "uri").str) should contain("skill://acme/billing/refunds/SKILL.md")
    resources.foreach { r => (r / "digest").str should fullyMatch regex "sha256:[0-9a-f]{64}" }
    (resources.find(r => (r / "uri").str.endsWith("/SKILL.md")).get / "size").num shouldBe utf8(refundsMarkdown).length.toLong
    (listed(2) / "resources").str shouldBe "dynamic"
    // Modern.
    val modernRes = h.send(modern(3, "skills/list")) / "result"
    (modernRes / "resultType").str shouldBe "complete"
    (modernRes / "ttlMs").num shouldBe 0L
    (modernRes / "cacheScope").str shouldBe "private"
    (modernRes / "skills").arr.size shouldBe 3
    (modernRes / "_meta" / "io.modelcontextprotocol/serverInfo" / "name").str shouldBe "SkillsRouter"
  }

  test("skills/list with a null / absent params object and an explicit empty cursor object") {
    val h = harness(server())
    h.initLegacy()
    (h.send("""{"jsonrpc":"2.0","id":9,"method":"skills/list"}""") / "result" / "skills").arr.size shouldBe 3
    (h.send(legacy(10, "skills/list", "{}")) / "result" / "skills").arr.size shouldBe 3
    val bad = h.send(legacy(11, "skills/list", """{"cursor":"nope"}"""))
    (bad / "error" / "code").num shouldBe -32602L
    (bad / "id").num shouldBe 11L
  }

  test("skills/get: listed, unlisted and dynamic skills resolve; unknown / malformed / missing URIs are -32602 with the id preserved") {
    val h = harness(server())
    h.initLegacy()
    val got = h.send(legacy(2, "skills/get", """{"uri":"skill://acme/hidden/SKILL.md"}""")) / "result"
    (got / "skill" / "uri").str shouldBe "skill://acme/hidden/SKILL.md"
    got.has("nextCursor") shouldBe false
    got.has("ttlMs") shouldBe false // legacy
    val modernGot = h.send(modern(3, "skills/get", """"uri":"skill://reports/daily/SKILL.md"""")) / "result"
    (modernGot / "resultType").str shouldBe "complete"
    (modernGot / "ttlMs").num shouldBe 0L
    (modernGot / "cacheScope").str shouldBe "private"
    (modernGot / "skill" / "resources").str shouldBe "dynamic"
    for (id, uri) <- List(
        (4, "skill://acme/billing/chargebacks/SKILL.md"),
        (5, "skill://acme/billing/refunds"), // the root is not the SKILL.md
        (6, "skill://acme/billing/refunds/references/policy.md"), // a file, not a skill
        (7, "skill://acme/billing/refunds/%2E%2E/SKILL.md"),
        (8, "skill://ACME/billing/refunds/SKILL.md"),
        (9, "skill://acme/billing/refunds/SKILL.md/"),
        (10, "skill://acme/billing/refunds-other/SKILL.md"),
        (11, "http://acme/billing/refunds/SKILL.md")
      )
    do
      val resp = h.send(legacy(id, "skills/get", s"""{"uri":"$uri"}"""))
      withClue(uri) {
        (resp / "error" / "code").num shouldBe -32602L
        (resp / "id").num shouldBe id.toLong
      }
    (h.send(legacy(12, "skills/get", "{}")) / "error" / "code").num shouldBe -32602L
    (h.send(legacy(13, "skills/get", s"""{"uri":"${"x" * 9000}"}""")) / "error" / "message").str should include("maxUriChars")
  }

  test("skill files are ordinary resources: listed (listed skills only), read as exact text or base64 blob, never served as directories") {
    val h = harness(server())
    h.initLegacy()
    val uris = (h.send(legacy(2, "resources/list")) / "result" / "resources").strings("uri")
    uris should contain("static://welcome")
    uris should contain("skill://acme/billing/refunds/SKILL.md")
    uris should contain("skill://acme/billing/refunds/assets/logo.bin")
    uris should not contain "skill://acme/hidden/SKILL.md"
    uris should not contain "skill://acme/billing/refunds" // directories are not listed
    val mdRes = (h.send(legacy(2, "resources/list")) / "result" / "resources").arr.find(r => (r / "uri").str == "skill://acme/billing/refunds/SKILL.md").get
    (mdRes / "name").str shouldBe "refunds"
    (mdRes / "description").str shouldBe "Process customer refund requests per company policy."
    (mdRes / "mimeType").str shouldBe "text/markdown"

    val text = h.send(legacy(3, "resources/read", """{"uri":"skill://acme/billing/refunds/references/policy.md"}""")) / "result" / "contents" / 0
    (text / "text").str shouldBe "# Policy\r\n\r\nRefund within 30 days.\r\n"
    (text / "mimeType").str shouldBe "text/markdown"
    val blob = h.send(legacy(4, "resources/read", """{"uri":"skill://acme/billing/refunds/assets/logo.bin"}""")) / "result" / "contents" / 0
    (blob / "mimeType").str shouldBe "application/octet-stream"
    val decoded = Base64.getDecoder.decode((blob / "blob").str)
    decoded.toList shouldBe List[Byte](0x89.toByte, 0x50, 0x4e, 0x47, 0x00, 0xff.toByte)
    // The advertised digest covers the decoded bytes — independently recomputed.
    val entry = h.send(legacy(5, "skills/get", """{"uri":"skill://acme/billing/refunds/SKILL.md"}""")) / "result" / "skill"
    val logoDigest = (entry / "resources").arr.find(r => (r / "uri").str.endsWith("logo.bin")).get / "digest"
    logoDigest.str shouldBe jdkDigest(decoded)
    // Unlisted skill files still read; a directory URI is not a resource.
    (h.send(legacy(6, "resources/read", """{"uri":"skill://acme/hidden/SKILL.md"}""")) / "result" / "contents" / 0 / "text").str should include("name: hidden")
    (h.send(legacy(7, "resources/read", """{"uri":"skill://acme/billing/refunds/templates"}""")) / "error" / "code").num shouldBe -32002L // legacy code for a miss
    (h.send(modern(8, "resources/read", """"uri":"skill://acme/billing/refunds/nope.md"""")) / "error" / "code").num shouldBe -32602L
    // Dynamic provider files read through the same route with their own mime type.
    val dyn = h.send(modern(9, "resources/read", """"uri":"skill://reports/daily/data/snapshot.txt"""")) / "result" / "contents" / 0
    (dyn / "text").str should startWith("snapshot@")
    (dyn / "mimeType").str shouldBe "text/plain"
  }

  test("resources/directory/read: roots, nested, empty, direct children only, dynamic; files and unknown URIs are -32602") {
    val h = harness(server())
    h.initLegacy()
    val root = h.send(legacy(2, "resources/directory/read", """{"uri":"skill://acme/billing/refunds"}""")) / "result"
    root.has("nextCursor") shouldBe false
    root.has("ttlMs") shouldBe false
    (root / "resources").arr.map(r => ((r / "name").str, (r / "mimeType").str)) shouldBe List(
      ("assets", "inode/directory"),
      ("references", "inode/directory"),
      ("refunds", "text/markdown"),
      ("templates", "inode/directory")
    )
    (root / "resources").strings("uri") should not contain "skill://acme/billing/refunds/templates/email.md" // not recursive
    val templates = h.send(modern(3, "resources/directory/read", """"uri":"skill://acme/billing/refunds/templates"""")) / "result"
    (templates / "resultType").str shouldBe "complete"
    (templates / "resources").strings("uri") shouldBe List(
      "skill://acme/billing/refunds/templates/drafts",
      "skill://acme/billing/refunds/templates/email.md",
      "skill://acme/billing/refunds/templates/regional"
    )
    (h.send(legacy(4, "resources/directory/read", """{"uri":"skill://acme/billing/refunds/templates/drafts"}""")) / "result" / "resources").arr shouldBe Nil
    (h.send(legacy(5, "resources/directory/read", """{"uri":"skill://reports/daily/data"}""")) / "result" / "resources").strings("name") shouldBe List("snapshot.txt")
    for (id, uri) <- List(
        (6, "skill://acme/billing/refunds/SKILL.md"),
        (7, "skill://acme/billing/refunds/templates/"),
        (8, "skill://acme/billing"),
        (9, "skill://nowhere"),
        (10, "static://welcome")
      )
    do
      val resp = h.send(legacy(id, "resources/directory/read", s"""{"uri":"$uri"}"""))
      withClue(uri) {
        (resp / "error" / "code").num shouldBe -32602L
        (resp / "id").num shouldBe id.toLong
      }
  }

  test("disabled: no extension declared, skills methods answer -32601 in both eras, ordinary surface intact") {
    val s = McpServer("NoSkills")
    val _ = s.scanAnnotations[Ordinary.type]
    val h = harness(s)
    val caps = h.initLegacy() / "result" / "capabilities"
    caps.has("extensions") shouldBe false
    caps.has("resources") shouldBe false
    (h.send(legacy(2, "skills/list")) / "error" / "code").num shouldBe -32601L
    (h.send(modern(3, "skills/get", """"uri":"skill://x/SKILL.md"""")) / "error" / "code").num shouldBe -32601L
    (h.send(modern(4, "resources/directory/read", """"uri":"skill://x"""")) / "error" / "code").num shouldBe -32601L
    (h.send(legacy(5, "tools/call", """{"name":"add","arguments":{"a":1,"b":2}}""")) / "result" / "content" / 0 / "text").str shouldBe "3"
  }

  test("enabled with an empty catalog: extension + resources declared, skills/list is empty, gets are -32602") {
    val s = McpServer("EmptySkills", "0.1.0", McpServerSettings(skills = SkillSettings(enabled = true)))
    val h = harness(s)
    val caps = h.initLegacy() / "result" / "capabilities"
    caps / "extensions" / ExtKey shouldBe Json.Obj("directoryRead" -> Json.Bool(true))
    caps.has("resources") shouldBe true
    (h.send(legacy(2, "skills/list")) / "result" / "skills").arr shouldBe Nil
    (h.send(legacy(3, "skills/get", """{"uri":"skill://x/SKILL.md"}""")) / "error" / "code").num shouldBe -32602L
    (h.send(legacy(4, "resources/list")) / "result" / "resources").arr shouldBe Nil
    (h.send(legacy(5, "resources/directory/read", """{"uri":"skill://x"}""")) / "error" / "code").num shouldBe -32602L
  }

  test("directoryRead off (by setting or by a provider that cannot honour it) yields `{}` and -32601") {
    val bySetting = harness(server(McpServerSettings(skills = SkillSettings(directoryRead = false))))
    (bySetting.initLegacy() / "result" / "capabilities" / "extensions" / ExtKey) shouldBe Json.Obj()
    (bySetting.send(legacy(2, "resources/directory/read", """{"uri":"skill://acme/billing/refunds"}""")) / "error" / "code").num shouldBe -32601L
    (bySetting.send(legacy(3, "skills/list")) / "result" / "skills").arr.size shouldBe 3

    val s = McpServer("NoDirProvider")
    runUnsafe(s.skill(gitWorkflow))
    runUnsafe(s.skillProvider(new SkillProvider[Any] {
      override def namespaces = List(SkillUri.unsafeParse("skill://opaque"))
      override def supportsDirectoryRead = false
      override def list(context: McpContext) = ZIO.succeed(Nil)
      override def get(uri: String, context: McpContext) = ZIO.none
      override def read(uri: String, context: McpContext) = ZIO.none
      override def readDirectory(uri: String, context: McpContext) = ZIO.none
    }))
    val byProvider = harness(s)
    (byProvider.initLegacy() / "result" / "capabilities" / "extensions" / ExtKey) shouldBe Json.Obj()
  }

  test("other extensions are preserved: Tasks and Skills coexist in modern capabilities; legacy keeps top-level tasks") {
    val h = harness(server(McpServerSettings(tasks = TaskSettings(enabled = true))))
    val legacyCaps = h.initLegacy() / "result" / "capabilities"
    legacyCaps.has("tasks") shouldBe true
    (legacyCaps / "extensions").has(ExtKey) shouldBe true
    val modernCaps = h.send(modern(2, "server/discover")) / "result" / "capabilities"
    modernCaps.has("tasks") shouldBe false
    (modernCaps / "extensions").has("io.modelcontextprotocol/tasks") shouldBe true
    (modernCaps / "extensions").has(ExtKey) shouldBe true
    (h.send(legacy(3, "prompts/get", """{"name":"hello"}""")) / "result" / "messages").arr.size shouldBe 1
  }

  test("a static resource or template colliding with a published skill URI is refused, and vice versa") {
    val s = McpServer("Collide")
    runUnsafe(s.skill(refunds))
    val staticErr = runUnsafe(s.resource(McpStaticResource("skill://acme/billing/refunds/references/policy.md", name = Some("x"))("shadow")).either)
    staticErr.isLeft shouldBe true
    val templateErr = runUnsafe(s.resourceTemplate("skill://acme/billing/refunds/{file}", (_: Map[String, String]) => ZIO.succeed("shadow": String | Array[Byte]), arguments = Some(List(ResourceArgument("file", None)))).either)
    templateErr.isLeft shouldBe true
    val s2 = McpServer("Collide2")
    runUnsafe(s2.resource(McpStaticResource("skill://acme/billing/refunds/SKILL.md", name = Some("x"))("shadow")))
    val skillErr = runUnsafe(s2.skill(refunds).either)
    skillErr.left.map(_.getMessage) match
      case Left(m) => m should include("already registered as a static resource")
      case Right(_) => fail("skill must not silently replace the static resource")
    val s3 = McpServer("Collide3")
    runUnsafe(s3.resourceTemplate("skill://acme/{team}/refunds/SKILL.md", (_: Map[String, String]) => ZIO.succeed("t": String | Array[Byte]), arguments = Some(List(ResourceArgument("team", None)))))
    runUnsafe(s3.skill(refunds).either).left.map(_.getMessage) match
      case Left(m) => m should include("matched by the resource template")
      case Right(_) => fail("skill must not be shadowed by a template")
  }

  test("the advertised digests are what an independent verifier computes from the served bytes") {
    val h = harness(server())
    h.initLegacy()
    val entry = parseJson((h.send(legacy(2, "skills/get", """{"uri":"skill://acme/billing/refunds/SKILL.md"}""")) / "result" / "skill").toString)
    val skill = zio.json.JsonDecoder[Skill].fromJsonAST(entry).fold(fail(_), identity)
    val held = HeldEntry("test-host-label", skill)
    skill.resources.staticEntries.foreach { r =>
      val contents = h.send(legacy(3, "resources/read", s"""{"uri":"${r.uri}"}""")) / "result" / "contents" / 0
      val decoded = zio.json.JsonDecoder[ResourceContents].fromJsonAST(contents).fold(fail(_), identity)
      SkillVerifier.verifyContents(held, decoded, bytes => java.security.MessageDigest.getInstance("SHA-256").digest(bytes)).isVerified shouldBe true
    }
    SkillSnapshot.build(refunds, java.security.MessageDigest.getInstance("SHA-256").digest(_)).entry shouldBe skill
  }
