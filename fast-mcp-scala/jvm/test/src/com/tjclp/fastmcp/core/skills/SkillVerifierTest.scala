package com.tjclp.fastmcp.core.skills

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.Chunk
import zio.json.ast.Json

import com.tjclp.fastmcp.core.wire.*
import com.tjclp.fastmcp.skills.SkillTestFixtures.*

/** Host-side verification helpers: entry validation, byte/size/digest checks, frontmatter
  * comparison, `resources/read` payload decoding, identity and content-bound approval keys. Expected
  * digests come from `MessageDigest`, not from the code under test.
  */
class SkillVerifierTest extends AnyFunSuite with Matchers:

  private val snap = SkillSnapshot.build(refunds, bytes => java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
  private val held = HeldEntry("docs-server", snap.entry)

  private def fm(pairs: (String, Json)*): SkillFrontmatter =
    SkillFrontmatter.fromFields(Chunk.fromIterable(pairs)).fold(fail(_), identity)

  test("identity is host label + URI; the URI alone is never enough") {
    held.identity shouldBe SkillIdentity("docs-server", "skill://acme/billing/refunds/SKILL.md")
    an[IllegalArgumentException] should be thrownBy SkillIdentity("", "skill://x/SKILL.md")
    HeldEntry("other-server", snap.entry).identity should not be held.identity
  }

  test("a valid static entry passes validateEntry; every structural rule is enforced") {
    SkillVerifier.validateEntry(snap.entry) shouldBe Right(())
    def invalid(entry: Skill): String =
      SkillVerifier.validateEntry(entry).fold(_.message, _ => fail("expected invalid"))
    val base = snap.entry
    val md = base.resources.staticEntries.head
    invalid(base.copy(resources = SkillResources.Static(Nil))) should include("empty")
    invalid(base.copy(resources = SkillResources.Static(base.resources.staticEntries.tail))) should include("own SKILL.md")
    invalid(base.copy(resources = SkillResources.Static(md :: base.resources.staticEntries))) should include("more than once")
    invalid(base.copy(resources = SkillResources.Static(md :: SkillResource("skill://acme/billing/refunds-other/x", md.digest, 1) :: Nil))) should include("outside")
    invalid(base.copy(resources = SkillResources.Static(md :: SkillResource("skill://acme/billing/refunds/%2e%2e/x", md.digest, 1) :: Nil))) should include("outside")
    invalid(base.copy(resources = SkillResources.Static(md :: SkillResource("skill://acme/billing/refunds/x", "sha256:zz", 1) :: Nil))) should include("digest")
    invalid(base.copy(resources = SkillResources.Static(md :: SkillResource("skill://acme/billing/refunds/x", md.digest, -1) :: Nil))) should include("negative")
    invalid(base.copy(resources = SkillResources.Static(md :: SkillResource("skill://acme/billing/refunds/x", md.digest, Long.MaxValue) :: Nil))) should include("overflows")
    invalid(base.copy(resources = SkillResources.Static(md :: SkillResource("skill://acme/billing/refunds/x", md.digest, 16L * 1024 * 1024) :: Nil))) should include("limit")
    invalid(base.copy(uri = "skill://acme/billing/refunds/README.md")) should include("SKILL.md")
    invalid(base.copy(uri = "skill://acme/billing/Refunds/SKILL.md")) should include("naming")
    invalid(base.copy(uri = "skill://acme/billing/other/SKILL.md")) should include("frontmatter.name")
    invalid(base.copy(uri = "not a uri")) should include("uri")
    // Limits are advisory for hosts: they can be switched off, the structural rules cannot.
    val many = SkillResources.Static(md :: (1 to 600).map(i => SkillResource(s"skill://acme/billing/refunds/f$i", md.digest, 1L)).toList)
    SkillVerifier.validateEntry(base.copy(resources = many)).isLeft shouldBe true
    SkillVerifier.validateEntry(base.copy(resources = many), enforceLimits = false) shouldBe Right(())
    // Dynamic entries need only a valid URI/name.
    SkillVerifier.validateEntry(base.copy(resources = SkillResources.Dynamic)) shouldBe Right(())
  }

  test("verifyFile: exact bytes verify; size mismatch, digest mismatch and unlisted files fail") {
    val uri = "skill://acme/billing/refunds/references/policy.md"
    val bytes = utf8("# Policy\r\n\r\nRefund within 30 days.\r\n")
    SkillVerifier.verifyFile(held, uri, bytes) shouldBe VerificationOutcome.Verified(uri, bytes.length.toLong, jdkDigest(bytes))
    SkillVerifier.verifyFile(held, uri, bytes.dropRight(1)) should matchPattern {
      case VerificationOutcome.Failed(VerificationFailure.SizeMismatch(`uri`, _, _)) =>
    }
    val flipped = bytes.clone(); flipped(0) = '!'.toByte
    SkillVerifier.verifyFile(held, uri, flipped) should matchPattern {
      case VerificationOutcome.Failed(VerificationFailure.DigestMismatch(`uri`, _, _)) =>
    }
    SkillVerifier.verifyFile(held, "skill://acme/billing/refunds/references/new.md", bytes) shouldBe
      VerificationOutcome.Failed(VerificationFailure.UnlistedFile("skill://acme/billing/refunds/references/new.md"))
    SkillVerifier.verifyFile(held, "skill://other/x", bytes).isVerified shouldBe false
    // The hasher is pluggable: a wrong hasher makes a correct file fail (nothing is self-consistent).
    SkillVerifier.verifyFile(held, uri, bytes, _ => new Array[Byte](32)).isVerified shouldBe false
  }

  test("verifySkillMd: digest-verified AND frontmatter-compared; a changed frontmatter fails even with a matching entry") {
    val md = utf8(refundsMarkdown)
    SkillVerifier.verifySkillMd(held, md).isVerified shouldBe true
    // An entry whose frontmatter claims a different license than the file carries.
    val tampered = held.copy(entry = held.entry.copy(frontmatter = fm(
      "name" -> Json.Str("refunds"),
      "description" -> Json.Str("Process customer refund requests per company policy."),
      "license" -> Json.Str("GPL-3.0"),
      "metadata" -> Json.Obj("team" -> Json.Str("billing"), "version" -> Json.Str("2.1"))
    )))
    SkillVerifier.verifySkillMd(tampered, md) should matchPattern {
      case VerificationOutcome.Failed(VerificationFailure.FrontmatterMismatch(_, diffs)) if diffs.exists(_.startsWith("license")) =>
    }
    // A file with the entry's digest but no parseable frontmatter cannot happen without a digest
    // mismatch — so unparseable content surfaces as a digest failure first.
    SkillVerifier.verifySkillMd(held, utf8("garbage")).isVerified shouldBe false
  }

  test("dynamic entries are Unverifiable for listed-in-skill files, frontmatter still checked on SKILL.md") {
    val dynamicEntry = Skill(
      "skill://reports/daily/SKILL.md",
      fm("name" -> Json.Str("daily"), "description" -> Json.Str("Assemble today's report.")),
      SkillResources.Dynamic
    )
    val dyn = HeldEntry("docs-server", dynamicEntry)
    dyn.approvalKey shouldBe None
    SkillVerifier.verifyFile(dyn, "skill://reports/daily/data/snapshot.txt", utf8("x")) shouldBe
      VerificationOutcome.Unverifiable("skill://reports/daily/data/snapshot.txt")
    SkillVerifier.verifyFile(dyn, "skill://reports/daily-other/x", utf8("x")) shouldBe
      VerificationOutcome.Failed(VerificationFailure.NotWithinSkill("skill://reports/daily-other/x"))
    SkillVerifier.verifySkillMd(dyn, utf8("---\nname: daily\ndescription: Assemble today's report.\n---\nbody\n")) shouldBe
      VerificationOutcome.Unverifiable("skill://reports/daily/SKILL.md")
    SkillVerifier.verifySkillMd(dyn, utf8("---\nname: daily\ndescription: Something else.\n---\n")) should matchPattern {
      case VerificationOutcome.Failed(VerificationFailure.FrontmatterMismatch(_, _)) =>
    }
    SkillVerifier.verifySkillMd(dyn, utf8("no frontmatter")) should matchPattern {
      case VerificationOutcome.Failed(VerificationFailure.InvalidSkillMd(_, _)) =>
    }
  }

  test("verifyContents decodes text as UTF-8 and blobs as base64 before checking") {
    val policyUri = "skill://acme/billing/refunds/references/policy.md"
    SkillVerifier.verifyContents(held, TextResourceContents(policyUri, "# Policy\r\n\r\nRefund within 30 days.\r\n")).isVerified shouldBe true
    SkillVerifier.verifyContents(held, TextResourceContents(policyUri, "# Policy\n\nRefund within 30 days.\n")).isVerified shouldBe false // LF≠CRLF
    val logoUri = "skill://acme/billing/refunds/assets/logo.bin"
    val logo = Array[Byte](0x89.toByte, 0x50, 0x4e, 0x47, 0x00, 0xff.toByte)
    SkillVerifier.verifyContents(held, BlobResourceContents(logoUri, java.util.Base64.getEncoder.encodeToString(logo))).isVerified shouldBe true
    SkillVerifier.verifyContents(held, BlobResourceContents(logoUri, "not base64!!")) should matchPattern {
      case VerificationOutcome.Failed(VerificationFailure.UndecodableContents(`logoUri`, _)) =>
    }
    // Hashing the base64 TEXT instead of the decoded bytes must not verify.
    SkillVerifier.verifyContents(held, TextResourceContents(logoUri, java.util.Base64.getEncoder.encodeToString(logo))).isVerified shouldBe false
    SkillVerifier.verifyContents(held, TextResourceContents(held.entry.uri, refundsMarkdown)).isVerified shouldBe true
  }

  test("content-bound approval keys: the (uri, digest) set; any change revokes") {
    val key = held.approvalKey.get
    key.size shouldBe 5
    held.sameContentSet(snap.entry) shouldBe true
    val rotated = SkillSnapshot.build(
      McpSkill.fromMarkdown("acme/billing/refunds", refundsMarkdown, refunds.files.toMap + ("references/policy.md" -> SkillFile.text("changed")), refunds.emptyDirectories)
    )
    held.sameContentSet(rotated.entry) shouldBe false
    val added = SkillSnapshot.build(
      McpSkill.fromMarkdown("acme/billing/refunds", refundsMarkdown, refunds.files.toMap + ("new.md" -> SkillFile.text("n")), refunds.emptyDirectories)
    )
    held.sameContentSet(added.entry) shouldBe false
    held.sameContentSet(snap.entry.copy(resources = SkillResources.Dynamic)) shouldBe false
  }
