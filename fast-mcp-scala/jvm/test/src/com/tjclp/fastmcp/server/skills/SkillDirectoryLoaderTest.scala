package com.tjclp.fastmcp.server.skills

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import com.tjclp.fastmcp.core.skills.*
import com.tjclp.fastmcp.skills.SkillTestFixtures.*

/** The JVM directory adapter: selection policy, text/binary classification, symlink and special
  * file refusal, limits, and that the manifest describes exactly the tree that was published.
  */
class SkillDirectoryLoaderTest extends AnyFunSuite with Matchers:

  private def write(dir: Path, rel: String, bytes: Array[Byte]): Path =
    val p = dir.resolve(rel)
    Files.createDirectories(p.getParent)
    Files.write(p, bytes)

  private def skillDir(name: String = "refunds"): Path =
    val root = Files.createTempDirectory("skill-loader-test")
    val dir = root.resolve(name)
    Files.createDirectories(dir)
    write(dir, "SKILL.md", utf8(refundsMarkdown.replace("name: refunds", s"name: $name")))
    write(dir, "references/policy.md", utf8("# Policy\r\n"))
    write(dir, "assets/logo.png", Array[Byte](0x89.toByte, 0x50, 0x4e, 0x47))
    write(dir, "scripts/run.sh", utf8("#!/bin/sh\necho hi\n"))
    write(dir, "data/latin1.txt", Array[Byte](0xe9.toByte, 0x0a)) // .txt but not UTF-8 → binary
    write(dir, ".hidden/secret.txt", utf8("nope"))
    write(dir, ".env", utf8("TOKEN=x"))
    Files.createDirectories(dir.resolve("templates/drafts"))
    Files.createDirectories(dir.resolve("node_modules/pkg"))
    write(dir, "node_modules/pkg/index.js", utf8("x"))
    dir

  test("loads regular files, classifies text vs binary, keeps empty directories, applies the exclusion policy") {
    val dir = skillDir()
    val loaded = runUnsafe(SkillDirectoryLoader.loadDetailed(dir))
    val skill = loaded.skill
    skill.uri.render shouldBe "skill://refunds/SKILL.md"
    skill.files.keySet shouldBe Set("assets/logo.png", "data/latin1.txt", "references/policy.md", "scripts/run.sh")
    skill.emptyDirectories shouldBe Set("templates/drafts")
    skill.files("references/policy.md").isText shouldBe true
    skill.files("scripts/run.sh").isText shouldBe true
    skill.files("assets/logo.png").isText shouldBe false
    skill.files("data/latin1.txt").isText shouldBe false // invalid UTF-8 is never served as text
    loaded.skipped.toSet shouldBe Set(".env", ".hidden", "node_modules")
    val snap = SkillSnapshot.build(skill)
    snap.file("skill://refunds/assets/logo.png").get.mimeType shouldBe "image/png"
    snap.file("skill://refunds/references/policy.md").get.digest shouldBe jdkDigest(utf8("# Policy\r\n"))
    snap.entry.resources.staticEntries.map(_.uri) should not contain "skill://refunds/.env"
    snap.directory("skill://refunds/templates/drafts").get.children shouldBe Vector.empty
  }

  test("namespace and explicit skillPath; directory name must match the frontmatter name unless skillPath is given") {
    val dir = skillDir()
    runUnsafe(SkillDirectoryLoader.load(dir, SkillDirectoryLoader.Options(namespace = List("acme", "billing")))).uri.render shouldBe
      "skill://acme/billing/refunds/SKILL.md"
    runUnsafe(SkillDirectoryLoader.load(dir, SkillDirectoryLoader.Options(skillPath = Some("other/refunds"), scheme = "github"))).uri.render shouldBe
      "github://other/refunds/SKILL.md"
    val renamed = skillDir("renamed-dir")
    write(renamed, "SKILL.md", utf8(refundsMarkdown)) // frontmatter says `refunds`, directory says `renamed-dir`
    runUnsafe(SkillDirectoryLoader.load(renamed).either).left.map(_.getMessage) match
      case Left(m) => m should include("does not equal frontmatter.name")
      case Right(_) => fail("directory/name mismatch must be refused")
    runUnsafe(SkillDirectoryLoader.load(renamed, SkillDirectoryLoader.Options(skillPath = Some("acme/refunds")))).name shouldBe "refunds"
  }

  test("symbolic links and special files fail closed by default; skipSpecialFiles skips and reports them") {
    val dir = skillDir()
    val outside = Files.createTempFile("outside", ".txt")
    Files.write(outside, utf8("secret"))
    val link =
      try Some(Files.createSymbolicLink(dir.resolve("references/leak.md"), outside))
      catch case _: Exception => None // filesystem without symlink support
    link.foreach { _ =>
      runUnsafe(SkillDirectoryLoader.load(dir).either).left.map(_.getMessage) match
        case Left(m) => m should include("symbolic links are not published")
        case Right(_) => fail("a symlink must fail the load")
      val skipped = runUnsafe(SkillDirectoryLoader.loadDetailed(dir, SkillDirectoryLoader.Options(skipSpecialFiles = true)))
      skipped.skipped should contain("references/leak.md")
      skipped.skill.files.contains("references/leak.md") shouldBe false
      // A symlinked directory pointing outside the root is refused as well.
      Files.delete(dir.resolve("references/leak.md"))
      val outsideDir = Files.createTempDirectory("outside-dir")
      Files.write(outsideDir.resolve("x.md"), utf8("x"))
      Files.createSymbolicLink(dir.resolve("escape"), outsideDir)
      runUnsafe(SkillDirectoryLoader.load(dir).either).isLeft shouldBe true
    }
  }

  test("limits: file count, per-file bytes and total bytes; a non-directory or SKILL.md-less root fails") {
    val dir = skillDir()
    runUnsafe(SkillDirectoryLoader.load(dir, SkillDirectoryLoader.Options(maxFiles = 3)).either).left.map(_.getMessage) match
      case Left(m) => m should include("files")
      case Right(_) => fail("file limit must apply")
    runUnsafe(SkillDirectoryLoader.load(dir, SkillDirectoryLoader.Options(maxFileBytes = 10)).either).isLeft shouldBe true
    runUnsafe(SkillDirectoryLoader.load(dir, SkillDirectoryLoader.Options(maxTotalBytes = 100)).either).left.map(_.getMessage) match
      case Left(m) => m should include("total bytes")
      case Right(_) => fail("total-bytes limit must apply")
    runUnsafe(SkillDirectoryLoader.load(dir.resolve("SKILL.md")).either).isLeft shouldBe true
    val empty = Files.createTempDirectory("no-skill").resolve("nothing")
    Files.createDirectories(empty)
    runUnsafe(SkillDirectoryLoader.load(empty).either).left.map(_.getMessage) match
      case Left(m) => m should include("no SKILL.md")
      case Right(_) => fail("a directory without SKILL.md is not a skill")
    runUnsafe(SkillDirectoryLoader.load(Path.of("/definitely/not/here")).either).isLeft shouldBe true
  }

  test("the loaded skill publishes through the ordinary server pipeline") {
    import com.tjclp.fastmcp.{given, *}
    val dir = skillDir()
    val skill = runUnsafe(SkillDirectoryLoader.load(dir))
    val server = McpServer("LoaderServer")
    runUnsafe(server.skill(skill))
    val h = harness(server)
    h.initLegacy()
    (h.send(legacy(2, "skills/get", """{"uri":"skill://refunds/SKILL.md"}""")) / "result" / "skill" / "resources").arr.size shouldBe 5
    (h.send(legacy(3, "resources/read", """{"uri":"skill://refunds/scripts/run.sh"}""")) / "result" / "contents" / 0 / "text").str shouldBe "#!/bin/sh\necho hi\n"
    (h.send(legacy(4, "resources/read", """{"uri":"skill://refunds/.env"}""")) / "error" / "code").num shouldBe -32002L
  }

  extension (s: String) private def utf8Bytes: Array[Byte] = s.getBytes(StandardCharsets.UTF_8)
