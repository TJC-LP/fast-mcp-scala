package com.tjclp.fastmcp.core.skills

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import com.tjclp.fastmcp.core.wire.{SkillResources, Skills}
import com.tjclp.fastmcp.skills.SkillTestFixtures.*

/** Authoring validation and the immutable publication snapshot: manifests, digests (checked against
  * `MessageDigest`), directory index, defensive copies, nested skills, limits at the exact edges.
  */
class McpSkillSnapshotTest extends AnyFunSuite with Matchers:

  test("the manifest lists SKILL.md and every file once, with independent digests and exact sizes") {
    val snap = SkillSnapshot.build(refunds)
    val entries = snap.entry.resources.staticEntries
    entries.map(_.uri) shouldBe List(
      "skill://acme/billing/refunds/SKILL.md",
      "skill://acme/billing/refunds/assets/logo.bin",
      "skill://acme/billing/refunds/references/policy.md",
      "skill://acme/billing/refunds/templates/email.md",
      "skill://acme/billing/refunds/templates/regional/eu.md"
    )
    entries.map(_.uri).distinct.size shouldBe entries.size
    snap.entry.uri shouldBe "skill://acme/billing/refunds/SKILL.md"
    val md = entries.head
    md.digest shouldBe jdkDigest(utf8(refundsMarkdown))
    md.size shouldBe utf8(refundsMarkdown).length.toLong
    val policy = snap.file("skill://acme/billing/refunds/references/policy.md").get
    policy.digest shouldBe jdkDigest(utf8("# Policy\r\n\r\nRefund within 30 days.\r\n"))
    policy.isText shouldBe true
    policy.mimeType shouldBe "text/markdown"
    policy.body shouldBe "# Policy\r\n\r\nRefund within 30 days.\r\n" // CRLF preserved
    val eu = snap.file("skill://acme/billing/refunds/templates/regional/eu.md").get
    eu.size shouldBe utf8("Sehr geehrte/r {{customer}} — €\n").length.toLong // multibyte counted in bytes
    val logo = snap.file("skill://acme/billing/refunds/assets/logo.bin").get
    logo.isText shouldBe false
    logo.mimeType shouldBe "application/octet-stream"
    logo.digest shouldBe jdkDigest(Array[Byte](0x89.toByte, 0x50, 0x4e, 0x47, 0x00, 0xff.toByte))
    logo.size shouldBe 6L
    snap.entry.frontmatter.get("license").map(_.toString) shouldBe Some("\"Apache-2.0\"")
  }

  test("the stable page's worked example (pdf-processing) reproduces the published sizes and digests exactly") {
    // specification/stable/skills.mdx §Example Message Flow (blob 824c66ba, 2026-09-16+): the page now
    // prints the exact bytes of every file, so the manifest it shows is a fixed oracle for an SDK.
    val skillMd =
      "---\nname: pdf-processing\ndescription: Extract, fill, and assemble PDF documents\n---\n\n" +
        "# PDF processing\n\nChoose the matching template from `templates/`.\n"
    val invoice = "# Invoice\n\nCustomer:\nAmount:\n"
    val purchaseOrder = "# Purchase order\n\nSupplier:\nItems:\n"
    val creditNote = "# Credit note\n\nInvoice:\nCredit amount:\n"
    val listed = McpSkill.fromMarkdown(
      "pdf-processing",
      skillMd,
      Map("templates/invoice.md" -> SkillFile.text(invoice), "templates/purchase-order.md" -> SkillFile.text(purchaseOrder))
    )
    val snap = SkillSnapshot.build(listed)
    snap.entry.uri shouldBe "skill://pdf-processing/SKILL.md"
    snap.entry.frontmatter.toJson shouldBe parseJson(
      """{"name":"pdf-processing","description":"Extract, fill, and assemble PDF documents"}"""
    )
    snap.entry.resources.staticEntries.map(r => (r.uri, r.size, r.digest)) shouldBe List(
      ("skill://pdf-processing/SKILL.md", 151L, "sha256:99b737495721155ece826d57521e2d66141ebdc1344a400487481ea2642ab19e"),
      ("skill://pdf-processing/templates/invoice.md", 29L, "sha256:61f4ea6d2c75fde1b4977219e7e3107d491c3c26aefb6686e84d6281c088d9ee"),
      ("skill://pdf-processing/templates/purchase-order.md", 35L, "sha256:f2ff774b1737ff3dec81c47946f9976f18a1a9f69dda0a81f22eabd95173c158")
    )
    snap.file("skill://pdf-processing/SKILL.md").get.body shouldBe skillMd // the exact `text` the page shows

    // The server then adds credit-note.md: the directory read sees it, the held entry does not.
    val refreshed = SkillSnapshot.build(
      McpSkill.fromMarkdown(
        "pdf-processing",
        skillMd,
        Map(
          "templates/invoice.md" -> SkillFile.text(invoice),
          "templates/purchase-order.md" -> SkillFile.text(purchaseOrder),
          "templates/credit-note.md" -> SkillFile.text(creditNote)
        )
      )
    )
    refreshed.entry.resources.staticEntries.find(_.uri.endsWith("/credit-note.md")).map(r => (r.size, r.digest)) shouldBe
      Some((39L, "sha256:766bffa8d4908ce897e1727ca22e4bf0fa796620b35218ce2132a124545626f1"))
    refreshed.directory("skill://pdf-processing/templates").get.children.map(c => (c.uri, c.name, c.mimeType)).toSet shouldBe Set(
      ("skill://pdf-processing/templates/invoice.md", "invoice.md", Some("text/markdown")),
      ("skill://pdf-processing/templates/purchase-order.md", "purchase-order.md", Some("text/markdown")),
      ("skill://pdf-processing/templates/credit-note.md", "credit-note.md", Some("text/markdown"))
    )
    val held = HeldEntry("docs-server", snap.entry)
    SkillVerifier.verifyFile(held, "skill://pdf-processing/templates/invoice.md", utf8(invoice)).isVerified shouldBe true
    SkillVerifier.verifyFile(held, "skill://pdf-processing/templates/credit-note.md", utf8(creditNote)) shouldBe
      VerificationOutcome.Failed(VerificationFailure.UnlistedFile("skill://pdf-processing/templates/credit-note.md"))
    held.sameContentSet(refreshed.entry) shouldBe false // the prior approval is revoked
    SkillVerifier.verifySkillMd(HeldEntry("docs-server", refreshed.entry), utf8(skillMd)).isVerified shouldBe true
  }

  test("the directory index covers the root, every ancestor and explicit empty directories, direct children only") {
    val snap = SkillSnapshot.build(refunds)
    snap.directories.keySet shouldBe Set(
      "skill://acme/billing/refunds",
      "skill://acme/billing/refunds/assets",
      "skill://acme/billing/refunds/references",
      "skill://acme/billing/refunds/templates",
      "skill://acme/billing/refunds/templates/regional",
      "skill://acme/billing/refunds/templates/drafts"
    )
    val root = snap.directory("skill://acme/billing/refunds").get
    root.children.map(_.name) shouldBe Vector("assets", "references", "refunds", "templates")
    root.children.find(_.name == "refunds").get.uri shouldBe "skill://acme/billing/refunds/SKILL.md"
    root.children.find(_.name == "refunds").get.description shouldBe Some("Process customer refund requests per company policy.")
    root.children.find(_.name == "templates").get.mimeType shouldBe Some(Skills.DirectoryMimeType)
    val templates = snap.directory("skill://acme/billing/refunds/templates").get
    templates.children.map(c => (c.name, c.mimeType.get)) shouldBe
      Vector(("drafts", "inode/directory"), ("email.md", "text/markdown"), ("regional", "inode/directory"))
    templates.children.find(_.name == "email.md").get.size shouldBe Some(19L)
    snap.directory("skill://acme/billing/refunds/templates/drafts").get.children shouldBe Vector.empty
    snap.owns("skill://acme/billing/refunds/templates/drafts") shouldBe true
    snap.owns("skill://acme/billing") shouldBe false
  }

  test("an authority-only root skill works (skill://git-workflow)") {
    val snap = SkillSnapshot.build(gitWorkflow)
    snap.entry.uri shouldBe "skill://git-workflow/SKILL.md"
    snap.rootUri shouldBe "skill://git-workflow"
    snap.directory("skill://git-workflow").get.children.map(_.uri) shouldBe Vector("skill://git-workflow/SKILL.md")
  }

  test("bytes are defensively copied: mutating the caller's array never changes what is published") {
    val data = Array[Byte](1, 2, 3)
    val skill = McpSkill.fromMarkdown("acme/copy", "---\nname: copy\ndescription: d\n---\n", Map("a.bin" -> SkillFile.binary(data)))
    data(0) = 99
    val snap = SkillSnapshot.build(skill)
    snap.file("skill://acme/copy/a.bin").get.bytes.toList shouldBe List[Byte](1, 2, 3)
    val served = snap.file("skill://acme/copy/a.bin").get.bytes
    served(1) = 42
    snap.file("skill://acme/copy/a.bin").get.bytes.toList shouldBe List[Byte](1, 2, 3)
    val md = skill.skillMd
    md(0) = 0
    skill.skillMd.head shouldBe '-'.toByte
  }

  test("the final path segment must equal frontmatter.name; names are validated on both sides") {
    McpSkill.parse("acme/wrong", refundsMarkdown).left.map(_.getClass.getSimpleName) shouldBe Left("NameMismatch")
    McpSkill.parse("acme/Refunds", refundsMarkdown).isLeft shouldBe true
    McpSkill.parse("refunds", refundsMarkdown).isRight shouldBe true
    McpSkill.parse("ACME/refunds", refundsMarkdown).isLeft shouldBe true // authority must be lowercase
    McpSkill.parse("acme/billing/refunds", refundsMarkdown, scheme = "github").map(_.uri.render) shouldBe
      Right("github://acme/billing/refunds/SKILL.md")
    McpSkill.parse("acme/refunds", refundsMarkdown, scheme = "Skill").isLeft shouldBe true
  }

  test("file paths are validated: dot segments, separators, encoded escapes, collisions, a second top-level SKILL.md") {
    def withFile(path: String) =
      McpSkill.parse("acme/refunds", refundsMarkdown, Map(path -> SkillFile.text("x")))
    for bad <- List("../x.md", "a/../x.md", "./x.md", "/abs.md", "a\\b.md", "a//b.md", "a/", "x%2F.md", "x y.md", "SKILL.md", "")
    do withClue(bad)(withFile(bad).isLeft shouldBe true)
    // Unicode is fine; a nested SKILL.md is an ordinary supporting file.
    withFile("références/guide.md").isRight shouldBe true
    withFile("sub/SKILL.md").isRight shouldBe true
    McpSkill
      .parse("acme/refunds", refundsMarkdown, Map("a" -> SkillFile.text("x"), "a/b.md" -> SkillFile.text("y")))
      .left
      .map(_.message) match
      case Left(m) => m should include("both a file and a directory")
      case Right(_) => fail("expected a file/directory collision")
    McpSkill.parse("acme/refunds", refundsMarkdown, Map("a.md" -> SkillFile.text("x")), Set("a.md")).isLeft shouldBe true
    McpSkill.parse("acme/refunds", refundsMarkdown, Map("a.md" -> SkillFile.text("x")), Set("a.md/sub")).isLeft shouldBe true
    McpSkill.parse("acme/refunds", refundsMarkdown, Map.empty, Set("../up")).isLeft shouldBe true
    McpSkill.parse("acme/refunds", refundsMarkdown, Map.empty, Set("ok/dir/")).map(_.emptyDirectories) shouldBe Right(Set("ok/dir"))
  }

  test("text files must be strict UTF-8; an empty supporting file is legitimate") {
    SkillFile.textBytes(Array(0xc0.toByte, 0x80.toByte)).isLeft shouldBe true
    SkillFile.textBytes("héllo".getBytes("UTF-8")).map(_.size) shouldBe Right(6L)
    val skill = McpSkill.fromMarkdown("acme/empty", "---\nname: empty\ndescription: d\n---\n", Map("empty.txt" -> SkillFile.text("")))
    val f = SkillSnapshot.build(skill).file("skill://acme/empty/empty.txt").get
    f.size shouldBe 0L
    f.digest shouldBe "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    f.body shouldBe ""
  }

  test("limits at the exact boundaries: 512 entries and 16 MiB pass, one more fails, with checked arithmetic") {
    val md = "---\nname: big\ndescription: d\n---\n"
    val files511 = (1 to 511).map(i => s"f$i.txt" -> SkillFile.text("")).toMap
    McpSkill.fromMarkdown("acme/big", md, files511).checkLimits().isRight shouldBe true
    McpSkill.fromMarkdown("acme/big", md, files511 + ("f512.txt" -> SkillFile.text(""))).checkLimits().left.map(_.message) match
      case Left(m) => m should include("resources per skill: 513 > 512")
      case Right(_) => fail("expected the 513th resource to be refused")
    val mdBytes = utf8(md).length
    val exactly = Skills.MaxTotalBytesPerSkill - mdBytes
    val bigFile = SkillFile.binary(new Array[Byte](exactly.toInt))
    McpSkill.fromMarkdown("acme/big", md, Map("blob.bin" -> bigFile)).checkLimits().isRight shouldBe true
    McpSkill.fromMarkdown("acme/big", md, Map("blob.bin" -> bigFile, "one.txt" -> SkillFile.text("x"))).checkLimits().left.map(_.message) match
      case Left(m) => m should include("total bytes per skill: 16777217 > 16777216")
      case Right(_) => fail("expected the extra byte to be refused")
    // Configurable limits.
    McpSkill.fromMarkdown("acme/big", md, Map("a.txt" -> SkillFile.text("abc"))).checkLimits(maxTotalBytes = mdBytes + 2L).isLeft shouldBe true
    McpSkill.fromMarkdown("acme/big", md, Map("a.txt" -> SkillFile.text("abc"))).checkLimits(maxResources = 1).isLeft shouldBe true
  }

  test("nestedSkill derives the nested entry from the parent's files and keeps shared membership") {
    val nested = refunds.nestedSkill("templates").left.map(_.message)
    nested should matchPattern { case Left(m: String) if m.contains("no SKILL.md") => }
    val parent = McpSkill.fromMarkdown(
      "acme/parent",
      "---\nname: parent\ndescription: d\n---\n",
      Map(
        "child/SKILL.md" -> SkillFile.text("---\nname: child\ndescription: nested\n---\n"),
        "child/ref.md" -> SkillFile.text("ref"),
        "other.md" -> SkillFile.text("other")
      ),
      Set("child/empty")
    )
    val child = parent.nestedSkill("child").fold(e => fail(e.message), identity)
    child.uri.render shouldBe "skill://acme/parent/child/SKILL.md"
    child.files.keySet shouldBe Set("ref.md")
    child.emptyDirectories shouldBe Set("empty")
    val ps = SkillSnapshot.build(parent)
    val cs = SkillSnapshot.build(child)
    // The same file appears in both manifests with identical digest and size.
    val shared = "skill://acme/parent/child/ref.md"
    ps.file(shared).get.digest shouldBe cs.file(shared).get.digest
    ps.entry.resources.staticEntries.map(_.uri) should contain(shared)
    cs.entry.resources.staticEntries.map(_.uri) should contain(shared)
    ps.entry.resources.staticEntries.map(_.uri) should contain("skill://acme/parent/child/SKILL.md")
    // A nested SKILL.md is an ordinary supporting file of the parent (markdown mime, file name).
    ps.file("skill://acme/parent/child/SKILL.md").get.name shouldBe "SKILL.md"
    cs.file("skill://acme/parent/child/SKILL.md").get.name shouldBe "child"
    parent.nestedSkill("nope").isLeft shouldBe true
    SkillSnapshot.build(parent.unlisted).listed shouldBe false
    parent.resources shouldBe parent.resources
  }

  extension (s: McpSkill) private def resources: SkillResources = SkillSnapshot.build(s).entry.resources
