package com.tjclp.fastmcp.skills

import java.nio.charset.StandardCharsets

import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.server.router.Session
import com.tjclp.fastmcp.server.transport.MessageLoop

/** Shared fixtures for the Skills extension test suites: sample skills, JSON-RPC frame builders for
  * both protocol eras, an independent (`MessageDigest`) digest, and JSON navigation helpers.
  */
object SkillTestFixtures:

  def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  /** Run an effect with any error type (typed `SkillError` / `McpError` channels). */
  def runAny[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  /** Independent expected value: the platform's own SHA-256, never the portable implementation. */
  def jdkSha256Hex(bytes: Array[Byte]): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  def jdkDigest(bytes: Array[Byte]): String = "sha256:" + jdkSha256Hex(bytes)

  def utf8(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)

  val refundsMarkdown: String =
    """---
      |name: refunds
      |description: Process customer refund requests per company policy.
      |license: Apache-2.0
      |metadata:
      |  team: billing
      |  version: "2.1"
      |---
      |
      |# Refunds
      |
      |Read `references/policy.md`, then fill `templates/email.md`.
      |""".stripMargin

  /** A realistic multi-file skill: text reference, binary asset, nested directory, empty directory. */
  val refunds: McpSkill = McpSkill.fromMarkdown(
    skillPath = "acme/billing/refunds",
    markdown = refundsMarkdown,
    files = Map(
      "references/policy.md" -> SkillFile.text("# Policy\r\n\r\nRefund within 30 days.\r\n"),
      "templates/email.md" -> SkillFile.text("Dear {{customer}},\n"),
      "templates/regional/eu.md" -> SkillFile.text("Sehr geehrte/r {{customer}} — €\n"),
      "assets/logo.bin" -> SkillFile.binary(Array[Byte](0x89.toByte, 0x50, 0x4e, 0x47, 0x00, 0xff.toByte))
    ),
    emptyDirectories = Set("templates/drafts")
  )

  val gitWorkflowMarkdown: String =
    """---
      |name: git-workflow
      |description: Follow this team's Git conventions for branching and commits.
      |---
      |Use conventional commits.
      |""".stripMargin

  /** A single-segment (authority-only root) skill. */
  val gitWorkflow: McpSkill = McpSkill.fromMarkdown("git-workflow", gitWorkflowMarkdown)

  def simpleSkill(path: String, description: String = "A test skill. Use it in tests.", body: String = "Body.\n"): McpSkill =
    val name = path.substring(path.lastIndexOf('/') + 1)
    McpSkill.fromMarkdown(path, s"---\nname: $name\ndescription: $description\n---\n$body")

  // ---- JSON-RPC frames ----

  val legacyInitFrame: String =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""

  def legacy(id: Int, method: String, params: String = "{}"): String =
    s"""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}"""

  val modernMeta: String =
    """"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{},"io.modelcontextprotocol/clientInfo":{"name":"t","version":"1.0"}}"""

  def modern(id: Int, method: String, paramFields: String = ""): String =
    val sep = if paramFields.isEmpty then "" else ","
    s"""{"jsonrpc":"2.0","id":$id,"method":"$method","params":{$paramFields$sep$modernMeta}}"""

  // ---- JSON helpers ----

  def parseJson(text: String): Json = text.fromJson[Json].fold(e => throw new RuntimeException(s"$e in $text"), identity)

  extension (j: Json)
    def /(key: String): Json = j match
      case Json.Obj(fields) => fields.find(_._1 == key).map(_._2).getOrElse(Json.Null)
      case _ => Json.Null
    def at(i: Int): Json = j match
      case Json.Arr(items) if i < items.length => items(i)
      case _ => Json.Null
    /** Array index (so `x / "contents" / 0 / "text"` chains without precedence surprises). */
    def /(i: Int): Json = at(i)
    def str: String = j match
      case Json.Str(s) => s
      case other => throw new RuntimeException(s"not a string: $other")
    def num: Long = j match
      case Json.Num(n) => n.longValueExact
      case other => throw new RuntimeException(s"not a number: $other")
    def arr: List[Json] = j match
      case Json.Arr(items) => items.toList
      case other => throw new RuntimeException(s"not an array: $other")
    /** `key` of every object in an array. */
    def strings(key: String): List[String] = arr.map(e => (e / key).str)
    def has(key: String): Boolean = j match
      case Json.Obj(fields) => fields.exists(_._1 == key)
      case _ => false

  /** A server with a built router and a fresh session, ready to dispatch frames. */
  final class Harness(val server: McpServer[Any], val router: com.tjclp.fastmcp.server.router.McpRouter[Any]):
    val session: Session = runUnsafe(Session.make(s"skills-test"))

    def send(frame: String): Json =
      parseJson(runUnsafe(MessageLoop.handleFrame(router, session, frame)).getOrElse(throw new RuntimeException(s"no reply to $frame")))

    def sendOpt(frame: String): Option[Json] =
      runUnsafe(MessageLoop.handleFrame(router, session, frame)).map(parseJson)

    def initLegacy(): Json = send(legacyInitFrame)

  def harness(server: McpServer[Any]): Harness =
    new Harness(server, runUnsafe(server.buildRouter))
