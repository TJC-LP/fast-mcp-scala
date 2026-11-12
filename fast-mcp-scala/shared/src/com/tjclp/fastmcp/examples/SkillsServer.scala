package com.tjclp.fastmcp
package examples

import zio.*

import com.tjclp.fastmcp.*
import com.tjclp.fastmcp.core.wire.Resource as WireResource

/** Skills extension (`io.modelcontextprotocol/skills`) example, cross-platform (JVM, Scala.js on
  * Bun, Scala Native over stdio).
  *
  * Shows the three ways a server publishes skills:
  *   - an **in-memory skill** built with [[McpSkill.fromMarkdown]]: a `SKILL.md`, a text reference,
  *     a binary asset, a nested subdirectory, an explicitly empty directory, and a nested skill
  *     whose own catalog entry is derived from the parent (`nestedSkill`);
  *   - an **unlisted** skill that `skills/get` and `resources/read` still resolve;
  *   - a **dynamic provider** ([[DailyReportSkills]]) whose `SKILL.md` is generated per request and
  *     therefore advertises `resources: "dynamic"`.
  *
  * The `@Tool` alongside proves ordinary tools coexist with skills. Nothing here executes a skill's
  * content: the server serves bytes and digests, the host decides what to do with them.
  *
  * Run with `./mill fast-mcp-scala.jvm.runMain com.tjclp.fastmcp.examples.SkillsServer`.
  */
object SkillsServer extends McpServerApp[Stdio, SkillsServer.type]:

  override def name: String = "SkillsExampleServer"

  @Tool(name = Some("add"), description = Some("Add two numbers"))
  def add(@Param("First number") a: Int, @Param("Second number") b: Int): Int = a + b

  val reconcileMarkdown: String =
    """---
      |name: reconcile-positions
      |description: Reconcile broker positions against the ledger and flag breaks. Use when a user asks to reconcile, tie out, or explain position differences.
      |license: MIT
      |metadata:
      |  author: acme-ops
      |  version: "1.2"
      |---
      |
      |# Reconcile positions
      |
      |1. Read `references/rules.md` for the tolerance rules.
      |2. Load the example fixture in `assets/example.bin` when you need sample input.
      |3. Draft findings into `templates/drafts/` (kept empty on purpose).
      |4. For the daily variant, see the nested `daily-tieout/SKILL.md`.
      |""".stripMargin

  val dailyMarkdown: String =
    """---
      |name: daily-tieout
      |description: The end-of-day tie-out variant of reconcile-positions. Use only for the daily close.
      |---
      |
      |# Daily tie-out
      |
      |Run the parent procedure with a zero tolerance.
      |""".stripMargin

  /** The flagship skill. `templates/drafts` is published as an empty directory. */
  val reconcile: McpSkill = McpSkill.fromMarkdown(
    skillPath = "acme/reconcile-positions",
    markdown = reconcileMarkdown,
    files = Map(
      "references/rules.md" -> SkillFile.text("# Rules\n\nTolerance: 0.01 per position.\n"),
      "assets/example.bin" -> SkillFile.binary(Array[Byte](0x00, 0x01, 0x02, 0x7f, -1)),
      "templates/positions.csv" -> SkillFile.text("account,symbol,qty\n"),
      "daily-tieout/SKILL.md" -> SkillFile.text(dailyMarkdown)
    ),
    emptyDirectories = Set("templates/drafts")
  )

  /** The nested skill as its own catalog entry — same bytes, its own manifest. */
  val dailyTieout: McpSkill =
    reconcile.nestedSkill("daily-tieout").fold(e => throw new SkillError.Exception(e), identity)

  /** Retrievable by URI only: absent from `skills/list` and `resources/list`. */
  val internalRunbook: McpSkill = McpSkill
    .fromMarkdown(
      skillPath = "acme/internal-runbook",
      markdown = """---
          |name: internal-runbook
          |description: Internal escalation runbook. Referenced from server instructions, not listed.
          |---
          |
          |Escalate to the on-call desk.
          |""".stripMargin
    )
    .unlisted

  override def skills: List[McpSkill] = List(reconcile, dailyTieout, internalRunbook)

  override def skillProviders: List[SkillProvider[Any]] = List(DailyReportSkills)

/** A dynamic [[SkillProvider]]: `skill://reports/daily/SKILL.md` is generated on every read, so the
  * entry carries `resources: "dynamic"` and directory reads reflect the live view. The frontmatter
  * is the same object the served `SKILL.md` starts with — hosts compare the two.
  */
object DailyReportSkills extends SkillProvider[Any]:

  private val root = SkillUri.unsafeParse("skill://reports/daily")
  private val skillMd = s"${root.render}/SKILL.md"
  private val snapshotTxt = s"${root.render}/data/snapshot.txt"
  private val dataDir = s"${root.render}/data"

  private val frontmatter = SkillFrontmatter
    .fromFields(
      zio.Chunk(
        "name" -> zio.json.ast.Json.Str("daily"),
        "description" -> zio.json.ast.Json.Str(
          "Assemble today's operational report from live data. Content changes every day."
        )
      )
    )
    .fold(e => throw new IllegalStateException(e), identity)

  private def markdown(now: Long): String =
    s"""---
       |name: daily
       |description: Assemble today's operational report from live data. Content changes every day.
       |---
       |
       |# Daily report (generated at epoch millisecond $now)
       |
       |Read `data/snapshot.txt` for the live snapshot.
       |""".stripMargin

  override def namespaces: List[SkillUri] = List(root)

  private val entry =
    Skill(uri = skillMd, frontmatter = frontmatter, resources = SkillResources.Dynamic)

  override def list(context: McpContext): ZIO[Any, Throwable, List[Skill]] =
    ZIO.succeed(List(entry))

  override def get(uri: String, context: McpContext): ZIO[Any, Throwable, Option[Skill]] =
    ZIO.succeed(Option.when(uri == skillMd)(entry))

  override def read(
      uri: String,
      context: McpContext
  ): ZIO[Any, Throwable, Option[SkillFileContent]] =
    Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS).map { now =>
      if uri == skillMd then Some(SkillFileContent(markdown(now), Some("text/markdown")))
      else if uri == snapshotTxt then Some(SkillFileContent(s"snapshot@$now\n", Some("text/plain")))
      else None
    }

  override def readDirectory(
      uri: String,
      context: McpContext
  ): ZIO[Any, Throwable, Option[List[WireResource]]] =
    ZIO.succeed {
      if uri == root.render then
        Some(
          List(
            WireResource(uri = skillMd, name = "daily", mimeType = Some("text/markdown")),
            WireResource(uri = dataDir, name = "data", mimeType = Some(Skills.DirectoryMimeType))
          )
        )
      else if uri == dataDir then
        Some(
          List(
            WireResource(uri = snapshotTxt, name = "snapshot.txt", mimeType = Some("text/plain"))
          )
        )
      else None
    }
