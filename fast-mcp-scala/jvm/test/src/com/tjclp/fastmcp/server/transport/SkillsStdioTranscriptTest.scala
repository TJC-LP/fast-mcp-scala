package com.tjclp.fastmcp.server.transport

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.stream.*

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.server.router.Session
import com.tjclp.fastmcp.skills.SkillTestFixtures.*

/** Real stdio lifecycle over the shared [[StdioLoop]] (the JVM and Scala Native transport): a
  * legacy 2025-11-25 handshake then list → get → read → directory, a 2026-07-28 stateless
  * transcript, malformed frames that must not end the session, and cancellation of an in-flight
  * `skills/list` against a slow provider.
  */
class SkillsStdioTranscriptTest extends AnyFunSuite with Matchers:

  private def replyTo(outQ: Queue[String], id: Int): UIO[String] =
    outQ.take.repeatUntil(_.contains(s""""id":$id,"""))

  private def loop(server: McpServer[Any])(script: (Queue[String], Queue[String], Session) => ZIO[Any, Throwable, Unit]): Unit =
    val program =
      for
        router <- server.buildRouter
        session <- Session.make("stdio-skills")
        inQ <- Queue.unbounded[String]
        outQ <- Queue.unbounded[String]
        fiber <- StdioLoop.run(router, session, ZStream.fromQueue(inQ), s => outQ.offer(s).unit).fork
        _ <- script(inQ, outQ, session)
        _ <- inQ.shutdown
        _ <- fiber.interrupt
      yield ()
    runUnsafe(program.timeoutFail(new RuntimeException("stdio transcript timed out"))(30.seconds))

  test("legacy transcript: initialize → skills/list → skills/get → resources/read → resources/directory/read") {
    val server = McpServer("StdioSkills")
    runUnsafe(server.skills(List(refunds)))
    loop(server) { (inQ, outQ, _) =>
      for
        _ <- inQ.offer(legacyInitFrame)
        init <- replyTo(outQ, 1)
        _ = (parseJson(init) / "result" / "capabilities" / "extensions" / "io.modelcontextprotocol/skills" / "directoryRead") shouldBe zio.json.ast.Json.Bool(true)
        _ <- inQ.offer("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        _ <- inQ.offer(legacy(2, "skills/list"))
        list <- replyTo(outQ, 2)
        entry = parseJson(list) / "result" / "skills" / 0
        _ = (entry / "uri").str shouldBe "skill://acme/billing/refunds/SKILL.md"
        _ <- inQ.offer(legacy(3, "skills/get", """{"uri":"skill://acme/billing/refunds/SKILL.md"}"""))
        got <- replyTo(outQ, 3)
        _ = (parseJson(got) / "result" / "skill") shouldBe entry
        _ <- inQ.offer(legacy(4, "resources/read", """{"uri":"skill://acme/billing/refunds/SKILL.md"}"""))
        read <- replyTo(outQ, 4)
        _ = (parseJson(read) / "result" / "contents" / 0 / "text").str shouldBe refundsMarkdown
        _ <- inQ.offer(legacy(5, "resources/directory/read", """{"uri":"skill://acme/billing/refunds/templates"}"""))
        dir <- replyTo(outQ, 5)
        _ = (parseJson(dir) / "result" / "resources").strings("name") shouldBe List("drafts", "email.md", "regional")
        // One frame per line, every reply is a single line.
        _ = List(init, list, got, read, dir).foreach(_ should not include "\n")
      yield ()
    }
  }

  test("modern (2026-07-28) transcript over stdio: discover → list → get → read → directory, every result complete") {
    val server = McpServer("StdioSkillsModern")
    runUnsafe(server.skills(List(gitWorkflow)))
    loop(server) { (inQ, outQ, _) =>
      for
        _ <- inQ.offer(modern(1, "server/discover"))
        disc <- replyTo(outQ, 1)
        _ = (parseJson(disc) / "result" / "capabilities" / "extensions").has("io.modelcontextprotocol/skills") shouldBe true
        _ <- inQ.offer(modern(2, "skills/list"))
        list <- replyTo(outQ, 2)
        _ = (parseJson(list) / "result" / "resultType").str shouldBe "complete"
        _ = (parseJson(list) / "result" / "cacheScope").str shouldBe "private"
        _ <- inQ.offer(modern(3, "skills/get", """"uri":"skill://git-workflow/SKILL.md""""))
        got <- replyTo(outQ, 3)
        _ = (parseJson(got) / "result" / "skill" / "frontmatter" / "name").str shouldBe "git-workflow"
        _ <- inQ.offer(modern(4, "resources/read", """"uri":"skill://git-workflow/SKILL.md""""))
        read <- replyTo(outQ, 4)
        _ = (parseJson(read) / "result" / "contents" / 0 / "text").str shouldBe gitWorkflowMarkdown
        _ <- inQ.offer(modern(5, "resources/directory/read", """"uri":"skill://git-workflow""""))
        dir <- replyTo(outQ, 5)
        _ = (parseJson(dir) / "result" / "resources").strings("uri") shouldBe List("skill://git-workflow/SKILL.md")
      yield ()
    }
  }

  test("malformed skills requests never terminate the stdio session") {
    val server = McpServer("StdioSkillsMalformed")
    runUnsafe(server.skills(List(gitWorkflow)))
    loop(server) { (inQ, outQ, _) =>
      for
        _ <- inQ.offer(legacyInitFrame)
        _ <- replyTo(outQ, 1)
        _ <- inQ.offer("""{"jsonrpc":"2.0","id":2,"method":"skills/get","params":{"uri":42}}""")
        e2 <- replyTo(outQ, 2)
        _ = (parseJson(e2) / "error" / "code").num shouldBe -32602L
        _ <- inQ.offer("""{"jsonrpc":"2.0","id":3,"method":"skills/get","params":"skill://x"}""")
        e3 <- replyTo(outQ, 3)
        _ = (parseJson(e3) / "error" / "code").num shouldBe -32602L
        _ <- inQ.offer("""{"jsonrpc":"2.0","id":4,"method":"resources/directory/read","params":{"uri":"skill://git-workflow","cursor":{"x":1}}}""")
        e4 <- replyTo(outQ, 4)
        _ = (parseJson(e4) / "error" / "code").num shouldBe -32602L
        _ <- inQ.offer("""{"jsonrpc":"2.0","id":5,"method":"skills/list","params":{"cursor":"fms1.AAAA"}}""")
        e5 <- replyTo(outQ, 5)
        _ = (parseJson(e5) / "error" / "code").num shouldBe -32602L
        _ <- inQ.offer("""{not json""")
        parse <- outQ.take
        _ = (parseJson(parse) / "error" / "code").num shouldBe -32700L
        _ <- inQ.offer(legacy(6, "skills/list"))
        ok <- replyTo(outQ, 6)
        _ = (parseJson(ok) / "result" / "skills").arr.size shouldBe 1
      yield ()
    }
  }

  test("notifications/cancelled interrupts an in-flight skills/list against a slow provider and clears the registry") {
    val server = McpServer("StdioSkillsCancel")
    runUnsafe(server.skills(List(gitWorkflow)))
    val slow = new SkillProvider[Any]:
      override def namespaces = List(SkillUri.unsafeParse("skill://slow"))
      override def list(context: McpContext) = ZIO.never
      override def get(uri: String, context: McpContext) = ZIO.none
      override def read(uri: String, context: McpContext) = ZIO.none
      override def readDirectory(uri: String, context: McpContext) = ZIO.none
    runUnsafe(server.skillProvider(slow))
    loop(server) { (inQ, outQ, session) =>
      for
        _ <- inQ.offer(legacyInitFrame)
        _ <- replyTo(outQ, 1)
        _ <- inQ.offer(legacy(2, "skills/list"))
        _ <- session.inflightIds.repeatUntil(_.nonEmpty)
        _ <- inQ.offer("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":2}}""")
        _ <- session.inflightIds.repeatUntil(_.isEmpty)
        _ <- inQ.offer(legacy(3, "ping"))
        pong <- replyTo(outQ, 3)
        _ = (parseJson(pong) / "id").num shouldBe 3L
        leaked <- outQ.poll
        _ = leaked shouldBe None // no reply to the cancelled request
      yield ()
    }
  }
