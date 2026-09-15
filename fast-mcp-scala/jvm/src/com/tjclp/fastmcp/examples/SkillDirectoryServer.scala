package com.tjclp.fastmcp
package examples

import java.nio.file.Paths

import zio.*

import com.tjclp.fastmcp.*
import com.tjclp.fastmcp.server.skills.SkillDirectoryLoader

/** JVM-only Skills example: publish an on-disk skill directory over stdio.
  *
  * The directory is the one named by `FAST_MCP_SKILL_DIR` (or the first program argument). It is
  * loaded once at startup through [[SkillDirectoryLoader]] — regular files only, symlinks refused,
  * bounded reads — into a validated [[McpSkill]], and then published through exactly the same
  * shared pipeline an in-memory skill uses. The directory's name must equal the `name` in its
  * `SKILL.md` frontmatter (Agent Skills rule); pass `Options(skillPath = Some("acme/<name>"))` to
  * mount it under a namespace.
  *
  * {{{
  *   FAST_MCP_SKILL_DIR=./my-skill \
  *     ./mill fast-mcp-scala.jvm.runMain com.tjclp.fastmcp.examples.SkillDirectoryServer
  * }}}
  *
  * Nothing in the directory is executed or installed — the server serves bytes and digests.
  */
object SkillDirectoryServer extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] = TransportRunner.stdio.bootstrap

  override def run: ZIO[ZIOAppArgs, Throwable, Unit] =
    for
      args <- getArgs
      dir <- ZIO
        .fromOption(args.headOption.orElse(sys.env.get("FAST_MCP_SKILL_DIR")))
        .orElseFail(
          new IllegalArgumentException(
            "pass the skill directory as the first argument or set FAST_MCP_SKILL_DIR"
          )
        )
      skill <- SkillDirectoryLoader.load(Paths.get(dir))
      server = McpServer("SkillDirectoryServer", "0.1.0")
      _ <- server.skill(skill)
      _ <- ZIO.logInfo(s"serving ${skill.uri.render} (${skill.resourceCount} files) over stdio")
      _ <- server.runStdio()
    yield ()
