package com.tjclp.fastmcp.surface

import scala.compiletime.testing.typeCheckErrors

import org.scalatest.funsuite.AnyFunSuite

import com.tjclp.fastmcp.{given, *}

/** Every `docs/skills.md` snippet type-checks with the root import alone (the TJC-2336 rule): the
  * authoring API, the declarative `McpServerApp` hook, the effectful registration, the settings, a
  * provider, and the host-side verification helpers.
  */
class SkillsRootImportTest extends AnyFunSuite:

  private def assertCompiles(errors: List[scala.compiletime.testing.Error]): org.scalatest.Assertion =
    assert(errors == Nil, s"unexpected errors: ${errors.map(_.message).mkString("\n---\n")}")

  test("docs/skills.md: authoring a skill needs only the root import") {
    assertCompiles(typeCheckErrors("""
      val skill: McpSkill = McpSkill.fromMarkdown(
        skillPath = "acme/reconcile-positions",
        markdown = "---\nname: reconcile-positions\ndescription: Reconcile positions.\n---\n",
        files = Map(
          "references/rules.md" -> SkillFile.text("# Rules\n"),
          "assets/example.bin" -> SkillFile.binary(Array[Byte](1, 2, 3))
        ),
        emptyDirectories = Set("templates/drafts")
      )
      val checked: Either[SkillError, McpSkill] = McpSkill.parse("acme/x", "---\nname: x\ndescription: d\n---\n")
      val unlisted: McpSkill = skill.unlisted
    """))
  }

  test("docs/skills.md: declarative and effectful registration need only the root import") {
    assertCompiles(typeCheckErrors("""
      import zio.*
      object ExampleServer extends McpServerApp[Stdio, ExampleServer.type]:
        override val skills: List[McpSkill] = List(
          McpSkill.fromMarkdown("acme/x", "---\nname: x\ndescription: d\n---\n")
        )
      val server = McpServer("effectful", "0.1.0", McpServerSettings(skills = SkillSettings(enabled = true, directoryRead = true)))
      val program: ZIO[Any, Throwable, Unit] =
        server.skill(McpSkill.fromMarkdown("acme/y", "---\nname: y\ndescription: d\n---\n")) *>
          server.removeSkills(List("skill://acme/y")) *> server.runStdio()
    """))
  }

  test("docs/skills.md: a dynamic provider needs only the root import") {
    assertCompiles(typeCheckErrors("""
      import zio.*
      object Live extends SkillProvider[Any]:
        override def namespaces: List[SkillUri] = List(SkillUri.unsafeParse("skill://reports/daily"))
        override def list(context: McpContext): ZIO[Any, Throwable, List[Skill]] = ZIO.succeed(Nil)
        override def get(uri: String, context: McpContext): ZIO[Any, Throwable, Option[Skill]] = ZIO.none
        override def read(uri: String, context: McpContext): ZIO[Any, Throwable, Option[SkillFileContent]] =
          ZIO.succeed(Some(SkillFileContent("---\nname: daily\ndescription: d\n---\n", Some("text/markdown"))))
        override def readDirectory(uri: String, context: McpContext): ZIO[Any, Throwable, Option[List[com.tjclp.fastmcp.core.wire.Resource]]] = ZIO.none
      val entry = Skill("skill://reports/daily/SKILL.md", SkillFrontmatter.fromFields(zio.Chunk("name" -> zio.json.ast.Json.Str("daily"), "description" -> zio.json.ast.Json.Str("d"))).toOption.get, SkillResources.Dynamic)
    """))
  }

  test("docs/skills.md: host-side verification needs only the root import") {
    assertCompiles(typeCheckErrors("""
      val entry: Skill = SkillSnapshot.build(McpSkill.fromMarkdown("acme/x", "---\nname: x\ndescription: d\n---\n")).entry
      val held = HeldEntry("my-label-for-this-server", entry)
      val identity: SkillIdentity = held.identity
      val outcome: VerificationOutcome = SkillVerifier.verifySkillMd(held, "bytes".getBytes)
      val failure: Option[VerificationFailure] = outcome match
        case VerificationOutcome.Failed(f) => Some(f)
        case _ => None
      val ok: Either[VerificationFailure, Unit] = SkillVerifier.validateEntry(entry)
      val approval: Option[Set[(String, String)]] = held.approvalKey
      val limits: Int = Skills.MaxResourcesPerSkill
    """))
  }
