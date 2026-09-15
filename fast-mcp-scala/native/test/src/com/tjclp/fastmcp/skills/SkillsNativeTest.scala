package com.tjclp.fastmcp
package skills

import java.nio.charset.StandardCharsets

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*

import com.tjclp.fastmcp.core.skills.{McpSkill, Sha256, SkillFile, SkillFrontmatterReader, SkillSnapshot}
import com.tjclp.fastmcp.server.{McpServer, McpServerSettings, SkillSettings}
import com.tjclp.fastmcp.server.router.Session
import com.tjclp.fastmcp.server.transport.{NativeTransportBackend, StdioLoop}
import com.tjclp.fastmcp.server.transport.NativeTransportBackend.given

/** Scala Native canary for the Skills extension: the portable SHA-256 against the NIST vectors on
  * THIS runtime (no `MessageDigest` here), the scala-yaml frontmatter path, snapshot digests, and a
  * full stdio transcript (legacy handshake then list → get → read → directory) through the shared
  * [[StdioLoop]] on the Native fiber runtime.
  */
class SkillsNativeTest extends AnyFunSuite with Matchers:

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private def field(json: Json, path: String*): Json =
    path.foldLeft(json) { (j, key) =>
      j match
        case Json.Obj(fields) => fields.find(_._1 == key).map(_._2).getOrElse(Json.Null)
        case Json.Arr(items) => key.toIntOption.filter(_ < items.length).map(items(_)).getOrElse(Json.Null)
        case _ => Json.Null
    }

  private val markdown =
    "﻿---\r\nname: reconcile\r\ndescription: Reconcile positions — tie-out.\r\nmetadata:\r\n  version: \"1.0\"\r\n---\r\n\r\n# Body\r\n"

  test("portable SHA-256 matches the NIST vectors and the backend's digest on Scala Native") {
    Sha256.hex(Sha256.digest("abc".getBytes(StandardCharsets.US_ASCII))) shouldBe
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    Sha256.hex(Sha256.digest(Array.empty[Byte])) shouldBe
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    Sha256.hex(Sha256.digest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".getBytes(StandardCharsets.US_ASCII))) shouldBe
      "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
    Sha256.hex(NativeTransportBackend.sha256("abc".getBytes(StandardCharsets.US_ASCII))) shouldBe
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
  }

  test("frontmatter with BOM, CRLF and Unicode parses on Scala Native; duplicate keys and tags are rejected") {
    val parsed = SkillFrontmatterReader.parse(markdown.getBytes(StandardCharsets.UTF_8)).fold(fail(_), identity)
    parsed.frontmatter.name shouldBe "reconcile"
    parsed.frontmatter.description shouldBe "Reconcile positions — tie-out."
    parsed.frontmatter.get("metadata") shouldBe Some(Json.Obj("version" -> Json.Str("1.0")))
    SkillFrontmatterReader.parseText("---\nname: x\ndescription: d\nname: y\n---\n").isLeft shouldBe true
    SkillFrontmatterReader.parseText("---\nname: x\ndescription: !!str d\n---\n").isLeft shouldBe true
    SkillFrontmatterReader.parseText("---\nname: x\ndescription: d\na: &x 1\nb: *x\n---\n").isLeft shouldBe true
  }

  test("a snapshot's digests cover the exact bytes, including the BOM") {
    val skill = McpSkill.fromMarkdown("acme/reconcile", markdown, Map("ref.txt" -> SkillFile.text("abc")))
    val snap = SkillSnapshot.build(skill)
    snap.file("skill://acme/reconcile/ref.txt").get.digest shouldBe
      "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    val md = snap.file("skill://acme/reconcile/SKILL.md").get
    md.size shouldBe markdown.getBytes(StandardCharsets.UTF_8).length.toLong
    md.bytes.take(3).toList shouldBe List(0xef.toByte, 0xbb.toByte, 0xbf.toByte)
  }

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""

  test("stdio transcript over StdioLoop on Scala Native: initialize → skills/list → skills/get → resources/read → directory") {
    val skill = McpSkill.fromMarkdown(
      "acme/reconcile",
      markdown,
      Map("references/rules.md" -> SkillFile.text("rules\n"), "assets/x.bin" -> SkillFile.binary(Array[Byte](1, 2, 3))),
      Set("templates/empty")
    )
    def replyTo(outQ: Queue[String], id: Int): UIO[Json] =
      outQ.take.repeatUntil(_.contains(s""""id":$id,""")).map(_.fromJson[Json].fold(e => throw new RuntimeException(e), identity))
    val program =
      for
        server <- ZIO.succeed(McpServer("NativeSkills", "0.1.0", McpServerSettings(skills = SkillSettings())))
        _ <- server.skill(skill)
        router <- server.buildRouter
        session <- Session.make("native-skills")
        inQ <- Queue.unbounded[String]
        outQ <- Queue.unbounded[String]
        loop <- StdioLoop.run(router, session, ZStream.fromQueue(inQ), s => outQ.offer(s).unit).fork
        _ <- inQ.offer(initFrame)
        init <- replyTo(outQ, 1)
        _ <- inQ.offer("""{"jsonrpc":"2.0","id":2,"method":"skills/list","params":{}}""")
        list <- replyTo(outQ, 2)
        _ <- inQ.offer("""{"jsonrpc":"2.0","id":3,"method":"skills/get","params":{"uri":"skill://acme/reconcile/SKILL.md"}}""")
        got <- replyTo(outQ, 3)
        _ <- inQ.offer("""{"jsonrpc":"2.0","id":4,"method":"resources/read","params":{"uri":"skill://acme/reconcile/assets/x.bin"}}""")
        read <- replyTo(outQ, 4)
        _ <- inQ.offer("""{"jsonrpc":"2.0","id":5,"method":"resources/directory/read","params":{"uri":"skill://acme/reconcile/templates"}}""")
        dir <- replyTo(outQ, 5)
        _ <- inQ.offer("""{"jsonrpc":"2.0","id":6,"method":"skills/get","params":{"uri":"skill://acme/nope/SKILL.md"}}""")
        miss <- replyTo(outQ, 6)
        _ <- inQ.shutdown
        _ <- loop.interrupt
      yield (init, list, got, read, dir, miss)
    val (init, list, got, read, dir, miss) =
      runUnsafe(program.timeoutFail(new RuntimeException("native stdio transcript timed out"))(60.seconds))
    field(init, "result", "capabilities", "extensions", "io.modelcontextprotocol/skills", "directoryRead") shouldBe Json.Bool(true)
    field(list, "result", "skills", "0", "uri") shouldBe Json.Str("skill://acme/reconcile/SKILL.md")
    field(got, "result", "skill", "frontmatter", "name") shouldBe Json.Str("reconcile")
    field(got, "result", "skill", "resources", "0", "digest").toString should startWith("\"sha256:")
    field(read, "result", "contents", "0", "blob") shouldBe Json.Str("AQID") // base64 of 01 02 03
    field(dir, "result", "resources", "0", "name") shouldBe Json.Str("empty")
    field(dir, "result", "resources", "0", "mimeType") shouldBe Json.Str("inode/directory")
    field(miss, "error", "code") shouldBe Json.Num(-32602)
    field(miss, "id") shouldBe Json.Num(6)
  }
