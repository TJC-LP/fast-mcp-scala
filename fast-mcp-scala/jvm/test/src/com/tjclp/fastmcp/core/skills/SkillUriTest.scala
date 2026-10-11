package com.tjclp.fastmcp.core.skills

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** The one URI policy every skill handler goes through: canonical literal matching, no
  * percent-decoding, segment-aware containment, filesystem-style relative resolution.
  */
class SkillUriTest extends AnyFunSuite with Matchers:

  private def parse(s: String): SkillUri = SkillUri.parse(s).fold(e => fail(s"$s: ${e.message}"), identity)

  test("canonical URIs parse and render unchanged; single-level, namespaced and other schemes") {
    for uri <- List(
        "skill://git-workflow/SKILL.md",
        "skill://acme/billing/refunds/SKILL.md",
        "github://owner/repo/skills/lint/SKILL.md",
        "skill://acme/refunds/references/FORMS.md",
        "skill://acme" // authority-only root
      )
    do parse(uri).render shouldBe uri
    parse("skill://acme/billing/refunds/SKILL.md").segments shouldBe Vector("acme", "billing", "refunds", "SKILL.md")
  }

  test("raw Unicode is accepted in path segments (IRI-style) but not in the authority") {
    parse("skill://acme/docs/références/guide.md").lastSegment shouldBe "guide.md"
    parse("skill://acme/名前/SKILL.md").segments(1) shouldBe "名前"
    SkillUri.parse("skill://ácme/x").isLeft shouldBe true
    SkillUri.parse("skill://ACME/x").isLeft shouldBe true // lowercase authority only: no case aliases
  }

  test("percent-encoding is never decoded: encoded separators, dot segments and double encodings are rejected") {
    for bad <- List(
        "skill://a/demo/x%2Fy",
        "skill://a/demo/%2E%2E/SKILL.md",
        "skill://a/demo/%252F",
        "skill://a/demo/ref%C3%A9rences.md",
        "skill://a/demo/%zz"
      )
    do withClue(bad)(SkillUri.parse(bad).isLeft shouldBe true)
  }

  test("dot segments, empty segments, trailing slashes, backslashes, delimiters and controls are rejected") {
    for bad <- List(
        "skill://a/./SKILL.md",
        "skill://a/../SKILL.md",
        "skill://a//SKILL.md",
        "skill://a/demo/",
        "skill://a/demo\\x",
        "skill://a/demo?x=1",
        "skill://a/demo#frag",
        "skill://a/demo/x y",
        "skill://a/demo/x\u0000y",
        "skill://a/demo/x\ty",
        "skill://a/demo/ x",
        "skill://",
        "skill:///a",
        "SKILL://a/b",
        "1skill://a/b",
        "skill:/a/b",
        "a/b",
        ""
      )
    do withClue(bad)(SkillUri.parse(bad).isLeft shouldBe true)
  }

  test("containment is segment-aware: a sibling with a longer prefix is not inside") {
    val root = parse("skill://a/demo")
    parse("skill://a/demo/x").isWithin(root) shouldBe true
    parse("skill://a/demo/sub/x").isWithin(root) shouldBe true
    parse("skill://a/demo-other/x").isWithin(root) shouldBe false
    parse("skill://a/demo").isWithin(root) shouldBe false // equal, not within
    parse("other://a/demo/x").isWithin(root) shouldBe false // scheme matters
    parse("skill://a/demo/x").relativeTo(root) shouldBe Some(Vector("x"))
    parse("skill://a/demo-other/x").relativeTo(root) shouldBe None
  }

  test("relative references resolve against the skill root like filesystem paths") {
    val root = parse("skill://acme/billing/refunds")
    SkillUri.resolveRelative(root, root, "references/GUIDE.md").map(_.render) shouldBe
      Right("skill://acme/billing/refunds/references/GUIDE.md")
    SkillUri.resolveRelative(root, root, "./references/./GUIDE.md").map(_.render) shouldBe
      Right("skill://acme/billing/refunds/references/GUIDE.md")
    SkillUri.resolveRelative(root, root, "a/../b.md").map(_.render) shouldBe
      Right("skill://acme/billing/refunds/b.md")
    // From a nested directory, `..` climbs within the skill.
    val nested = parse("skill://acme/billing/refunds/templates/regional")
    SkillUri.resolveRelative(root, nested, "../invoice.md").map(_.render) shouldBe
      Right("skill://acme/billing/refunds/templates/invoice.md")
  }

  test("relative references cannot escape the root, be absolute, name a directory, or use backslashes") {
    val root = parse("skill://acme/billing/refunds")
    for bad <- List("../other/SKILL.md", "a/../../x", "/etc/passwd", "refs/", "refs\\x.md", "", "skill://acme/x", "refs/%2e%2e/x")
    do withClue(bad)(SkillUri.resolveRelative(root, root, bad).isLeft shouldBe true)
  }

  test("authority-only roots resolve too and `..` is refused at the root") {
    val root = parse("skill://example")
    SkillUri.resolveRelative(root, root, "SKILL.md").map(_.render) shouldBe Right("skill://example/SKILL.md")
    SkillUri.resolveRelative(root, root, "../x").isLeft shouldBe true
    SkillUri.skillRootOf(parse("skill://example/SKILL.md")).map(_.render) shouldBe Some("skill://example")
    SkillUri.skillRootOf(parse("skill://example/README.md")) shouldBe None
    SkillUri.skillRootOf(parse("skill://example")) shouldBe None
  }

  test("skill names follow the Agent Skills rules") {
    SkillNames.isValidName("pdf-processing") shouldBe true
    SkillNames.isValidName("a") shouldBe true
    SkillNames.isValidName("a" * 64) shouldBe true
    SkillNames.isValidName("a" * 65) shouldBe false
    SkillNames.isValidName("PDF-Processing") shouldBe false
    SkillNames.isValidName("-pdf") shouldBe false
    SkillNames.isValidName("pdf-") shouldBe false
    SkillNames.isValidName("pdf--processing") shouldBe false
    SkillNames.isValidName("pdf_processing") shouldBe false
    SkillNames.isValidName("") shouldBe false
    SkillNames.validateDescription("x" * 1024).isRight shouldBe true
    SkillNames.validateDescription("x" * 1025).isLeft shouldBe true
    SkillNames.validateDescription("").isLeft shouldBe true
  }
