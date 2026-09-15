package com.tjclp.fastmcp
package skills

import java.nio.charset.StandardCharsets

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.skills.{McpSkill, Sha256, SkillFile, SkillFrontmatterReader, SkillSnapshot}
import com.tjclp.fastmcp.facades.node.NodeReadableStream
import com.tjclp.fastmcp.server.{McpServer, McpServerSettings}
import com.tjclp.fastmcp.server.router.Session
import com.tjclp.fastmcp.server.transport.{BunHttpHandle, JsTransportBackend, startStatelessHttp}
import com.tjclp.fastmcp.server.transport.JsTransportBackend.given

/** Scala.js/Bun canary for the Skills extension: the portable SHA-256 against NIST vectors on THIS
  * runtime (Bun has no `MessageDigest`), the scala-yaml frontmatter path, the production Bun stdin
  * callbacks driving a skills transcript, and a 2026-07-28 transcript over the in-process
  * `Bun.serve` HTTP listener.
  */
class SkillsJsTest extends AsyncFlatSpec with Matchers with BeforeAndAfterAll:

  override implicit val executionContext: ExecutionContext = ExecutionContext.global

  private def runZio[A](effect: ZIO[Any, Throwable, A]): Future[A] =
    val promise = Promise[A]()
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.runToFuture(effect).onComplete {
        case scala.util.Success(a) => val _ = promise.trySuccess(a)
        case scala.util.Failure(e) => val _ = promise.tryFailure(e)
      }
    }
    promise.future

  private def fromJsPromise[A](p: js.Promise[A]): Future[A] =
    val promise = Promise[A]()
    val _ = p.`then`[Unit](
      (value: A) => { val _ = promise.trySuccess(value); () },
      (err: scala.Any) => { val _ = promise.tryFailure(new RuntimeException(String.valueOf(err))); () }
    )
    promise.future

  private def field(json: Json, path: String*): Json =
    path.foldLeft(json) { (j, key) =>
      j match
        case Json.Obj(fields) => fields.find(_._1 == key).map(_._2).getOrElse(Json.Null)
        case Json.Arr(items) => key.toIntOption.filter(_ < items.length).map(items(_)).getOrElse(Json.Null)
        case _ => Json.Null
    }

  private def parse(text: String): Json = text.fromJson[Json].fold(e => throw new RuntimeException(s"$e: $text"), identity)

  private val markdown =
    "---\nname: reconcile\ndescription: Reconcile positions — tie-out.\nmetadata:\n  version: \"1.0\"\n---\n\n# Body\n"

  private val skill = McpSkill.fromMarkdown(
    "acme/reconcile",
    markdown,
    Map("references/rules.md" -> SkillFile.text("rules\n"), "assets/x.bin" -> SkillFile.binary(Array[Byte](1, 2, 3))),
    Set("templates/empty")
  )

  "the portable SHA-256" should "match the NIST vectors and the JS backend digest" in {
    Sha256.hex(Sha256.digest("abc".getBytes(StandardCharsets.US_ASCII))) shouldBe
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    Sha256.hex(Sha256.digest(Array.empty[Byte])) shouldBe
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    Sha256.hex(Sha256.digest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".getBytes(StandardCharsets.US_ASCII))) shouldBe
      "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
    Sha256.hex(JsTransportBackend.sha256("abc".getBytes(StandardCharsets.US_ASCII))) shouldBe
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
  }

  "the frontmatter reader" should "parse on Bun and reject duplicate keys, tags and aliases" in {
    val parsed = SkillFrontmatterReader.parseText(markdown).fold(fail(_), identity)
    parsed.frontmatter.name shouldBe "reconcile"
    parsed.frontmatter.get("metadata") shouldBe Some(Json.Obj("version" -> Json.Str("1.0")))
    SkillFrontmatterReader.parseText("---\nname: x\ndescription: d\nname: y\n---\n").isLeft shouldBe true
    SkillFrontmatterReader.parseText("---\nname: x\ndescription: !!str d\n---\n").isLeft shouldBe true
    SkillFrontmatterReader.parseText("---\nname: x\ndescription: d\na: &x 1\nb: *x\n---\n").isLeft shouldBe true
    SkillSnapshot.build(skill).file("skill://acme/reconcile/references/rules.md").get.digest shouldBe
      "sha256:" + Sha256.hex(Sha256.digest("rules\n".getBytes(StandardCharsets.UTF_8)))
  }

  "the Bun stdio callbacks" should "serve a legacy skills transcript" in {
    val initFrame =
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""
    val frames = List(
      """{"jsonrpc":"2.0","id":2,"method":"skills/list","params":{}}""",
      """{"jsonrpc":"2.0","id":3,"method":"skills/get","params":{"uri":"skill://acme/reconcile/SKILL.md"}}""",
      """{"jsonrpc":"2.0","id":4,"method":"resources/read","params":{"uri":"skill://acme/reconcile/assets/x.bin"}}""",
      """{"jsonrpc":"2.0","id":5,"method":"resources/directory/read","params":{"uri":"skill://acme/reconcile"}}""",
      """{"jsonrpc":"2.0","id":6,"method":"skills/get","params":{"uri":"skill://acme/nope/SKILL.md"}}"""
    )
    val program =
      for
        server <- ZIO.succeed(McpServer("JsSkillsStdio", "0.1.0", McpServerSettings()))
        _ <- server.skill(skill)
        router <- server.buildRouter
        session <- Session.make("js-skills-stdio")
        output <- Queue.unbounded[String]
        callbacks <- ZIO.succeed(js.Dictionary.empty[js.Function1[js.Any, Unit]])
        _ <- ZIO.succeed {
          val stdin = js.Dynamic
            .literal(
              setEncoding = js.Any.fromFunction1((_: String) => ()),
              on = js.Any.fromFunction2((event: String, callback: js.Function1[js.Any, Unit]) =>
                callbacks(event) = callback
                ()
              )
            )
            .asInstanceOf[NodeReadableStream]
          JsTransportBackend.wireStdin(router, session, Runtime.default, _ => (), stdin, line => output.offer(line).unit)
          // The handshake first: the pre-init gate would otherwise reject concurrently dispatched frames.
          callbacks("data")(initFrame + "\n")
        }
        initReply <- output.take.timeoutFail(new RuntimeException("missing initialize reply"))(10.seconds)
        _ <- ZIO.succeed(frames.foreach(frame => callbacks("data")(frame + "\n")))
        replies <- ZIO
          .foreach(frames)(_ => output.take)
          .timeoutFail(new RuntimeException("missing stdio reply"))(10.seconds)
          .ensuring(session.terminate *> output.shutdown)
      yield (initReply :: replies).map(parse)
    runZio(program).map { replies =>
      val byId = replies.map(r => field(r, "id").toString -> r).toMap
      field(byId("1"), "result", "capabilities", "extensions", "io.modelcontextprotocol/skills", "directoryRead") shouldBe Json.Bool(true)
      field(byId("2"), "result", "skills", "0", "uri") shouldBe Json.Str("skill://acme/reconcile/SKILL.md")
      field(byId("2"), "result").toString should not include "ttlMs"
      field(byId("3"), "result", "skill", "frontmatter", "description") shouldBe Json.Str("Reconcile positions — tie-out.")
      field(byId("4"), "result", "contents", "0", "blob") shouldBe Json.Str("AQID")
      field(byId("5"), "result", "resources").toString should include("inode/directory")
      field(byId("6"), "error", "code") shouldBe Json.Num(-32602)
    }
  }

  private val port = 38933
  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  private var bunServer: BunHttpHandle = scala.compiletime.uninitialized

  override def afterAll(): Unit =
    if bunServer != null then bunServer.stop()
    super.afterAll()

  private val modernMeta =
    """"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}"""

  private def modernPost(id: Int, method: String, paramFields: String = ""): Future[Json] =
    val sep = if paramFields.isEmpty then "" else ","
    val body = s"""{"jsonrpc":"2.0","id":$id,"method":"$method","params":{$paramFields$sep$modernMeta}}"""
    val init = js.Dynamic.literal(
      method = "POST",
      headers = js.Dictionary(
        "content-type" -> "application/json",
        "accept" -> "application/json, text/event-stream",
        "mcp-protocol-version" -> "2026-07-28",
        "mcp-method" -> method
      ),
      body = body
    )
    for
      resp <- fromJsPromise(js.Dynamic.global.fetch(s"http://127.0.0.1:$port/mcp", init).asInstanceOf[js.Promise[js.Dynamic]])
      text <- fromJsPromise(resp.text().asInstanceOf[js.Promise[String]])
    yield
      val data = text.linesIterator.find(_.startsWith("data:")).map(_.stripPrefix("data:").trim).getOrElse(text)
      parse(data)

  "Bun.serve" should "answer a 2026-07-28 skills transcript" in {
    val server = McpServer("JsSkillsHttp", "0.1.0", McpServerSettings(host = "127.0.0.1", port = port, httpEndpoint = "/mcp", stateless = true))
    runZio(server.skill(skill).unit).flatMap { _ =>
      bunServer = server.startStatelessHttp()
      for
        disc <- modernPost(1, "server/discover")
        list <- modernPost(2, "skills/list")
        got <- modernPost(3, "skills/get", """"uri":"skill://acme/reconcile/SKILL.md"""")
        dir <- modernPost(4, "resources/directory/read", """"uri":"skill://acme/reconcile/templates"""")
        miss <- modernPost(5, "resources/directory/read", """"uri":"skill://acme/reconcile/SKILL.md"""")
      yield
        field(disc, "result", "capabilities", "extensions", "io.modelcontextprotocol/skills", "directoryRead") shouldBe Json.Bool(true)
        field(list, "result", "resultType") shouldBe Json.Str("complete")
        field(list, "result", "ttlMs") shouldBe Json.Num(0)
        field(list, "result", "cacheScope") shouldBe Json.Str("private")
        field(list, "result", "skills", "0", "resources", "0", "uri") shouldBe Json.Str("skill://acme/reconcile/SKILL.md")
        field(got, "result", "skill", "frontmatter", "name") shouldBe Json.Str("reconcile")
        field(dir, "result", "resources", "0", "uri") shouldBe Json.Str("skill://acme/reconcile/templates/empty")
        field(miss, "error", "code") shouldBe Json.Num(-32602)
    }
  }
