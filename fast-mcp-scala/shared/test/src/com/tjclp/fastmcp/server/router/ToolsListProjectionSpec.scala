package com.tjclp.fastmcp.server.router

import scala.concurrent.{ExecutionContext, Future}

import org.scalatest.funsuite.AsyncFunSuite
import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.core.{Protocol, ToolExecution}
import com.tjclp.fastmcp.core.wire.{Icon, Tool, ToolOutputSchema}
import com.tjclp.fastmcp.server.transport.MessageLoop

case class ProjectionArgs(q: String)
case class ProjectionResult(answer: String)

/** `tools/list` writes each tool with the fields of the revision the client speaks (GH #120,
  * D3.17): a 2024-11-05 session used to be sent `annotations` (2025-03-26), `title` and
  * `outputSchema` (2025-06-18) and `execution` (2025-11-25). The table is the published schemas'
  * (`schema/<revision>/schema.json`, definition `Tool`). Compiled into the JVM, Scala.js and Scala
  * Native test modules.
  */
class ToolsListProjectionSpec extends AsyncFunSuite:

  override implicit val executionContext: ExecutionContext = ExecutionContext.global

  private def run[A](effect: ZIO[Any, Throwable, A]): Future[A] =
    Unsafe.unsafe(implicit unsafe => Runtime.default.unsafe.runToFuture(effect))

  private val lookup = McpTool[ProjectionArgs, ProjectionResult](
    name = "lookup",
    description = Some("Look something up"),
    annotations = Some(ToolAnnotations(title = Some("Look up"), readOnlyHint = Some(true)))
  )(args => ProjectionResult(args.q)).withOutputSchema.withTaskSupport(TaskSupport.Optional)

  private def initFrame(version: String): String =
    s"""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$version","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""

  private val listFrame = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""

  private val modernListFrame =
    """{"jsonrpc":"2.0","id":3,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}"""

  private def router: ZIO[Any, Throwable, McpRouter[Any]] =
    val server = McpServer(
      "Projection",
      "0.1.0",
      McpServerSettings(tasks = TaskSettings(enabled = true))
    )
    server.tool(lookup) *> server.buildRouter

  /** The member names of the one tool in a `tools/list` reply. */
  private def toolFields(reply: Option[String]): Set[String] =
    val tool = for
      text <- reply
      json <- text.fromJson[Json].toOption
      result <- json.asObject.flatMap(_.get("result")).flatMap(_.asObject)
      tools <- result.get("tools").flatMap(_.asArray)
      first <- tools.headOption.flatMap(_.asObject)
    yield first.keys.toSet
    tool.getOrElse(throw new AssertionError(s"not a tools/list reply with one tool: $reply"))

  /** `tools/list` on a legacy session that negotiated `version`. */
  private def legacyFields(version: String): ZIO[Any, Throwable, (String, Set[String])] =
    for
      r <- router
      session <- Session.make(s"projection-$version")
      init <- MessageLoop.handleFrame(r, session, initFrame(version))
      negotiated <- session.protocolVersion
      list <- MessageLoop.handleFrame(r, session, listFrame)
      _ <- ZIO.unless(init.exists(_.contains(s""""protocolVersion":"$version"""")))(
        ZIO.fail(new AssertionError(s"initialize did not negotiate $version: $init"))
      )
    yield (negotiated, toolFields(list))

  private val base = Set("name", "description", "inputSchema")

  private val expected = List(
    "2024-10-07" -> base,
    "2024-11-05" -> base,
    "2025-03-26" -> (base + "annotations"),
    "2025-06-18" -> (base ++ Set("annotations", "title", "outputSchema")),
    "2025-11-25" -> (base ++ Set("annotations", "title", "outputSchema", "execution"))
  )

  for (version, fields) <- expected do
    test(s"a $version session gets the Tool fields of $version: ${fields.toList.sorted.mkString(", ")}") {
      run(legacyFields(version)).map { (negotiated, seen) =>
        assert(negotiated == version)
        assert(seen == fields)
      }
    }

  test("the table covers every legacy revision the server negotiates") {
    assert(expected.map(_._1).toSet == Protocol.LegacyProtocolVersions.toSet)
  }

  test("a 2026-07-28 request gets that revision's fields: no execution") {
    run(
      for
        r <- router
        session <- Session.make("projection-modern")
        list <- MessageLoop.handleFrame(r, session, modernListFrame)
      yield toolFields(list)
    ).map(seen => assert(seen == base ++ Set("annotations", "title", "outputSchema")))
  }

  test("a session that negotiated no version (the legacy stateless adapter) is not projected") {
    run(
      for
        r <- router
        session <- Session.make("projection-none")
        _ <- session.markInitialized
        version <- session.protocolVersion
        list <- MessageLoop.handleFrame(r, session, listFrame)
      yield (version, toolFields(list))
    ).map { (version, seen) =>
      assert(version == "")
      assert(seen == base ++ Set("annotations", "title", "outputSchema", "execution"))
    }
  }

  test("the 2025-03-26 entry keeps the display title inside annotations") {
    run(
      for
        r <- router
        session <- Session.make("projection-title")
        _ <- MessageLoop.handleFrame(r, session, initFrame("2025-03-26"))
        list <- MessageLoop.handleFrame(r, session, listFrame)
      yield list.getOrElse("")
    ).map { reply =>
      assert(reply.contains(""""annotations":{"title":"Look up","readOnlyHint":true}"""), reply)
      assert(!reply.contains(""""title":"Look up","description""""), reply)
    }
  }

  test("toolForVersion: every optional field, revision by revision") {
    val full = Tool(
      name = "t",
      inputSchema = ToolInputSchema.default,
      title = Some("T"),
      description = Some("d"),
      outputSchema = Some(ToolOutputSchema.unsafeFromJsonString("""{"type":"object"}""")),
      annotations = Some(ToolAnnotations(readOnlyHint = Some(true))),
      execution = Some(ToolExecution(Some(TaskSupport.Optional))),
      icons = Some(List(Icon("https://example.com/i.png"))),
      _meta = Some(Map("k" -> Json.Str("v")))
    )
    def present(version: String): Set[String] =
      val t = WireMapping.toolForVersion(full, version)
      assert(t.name == "t" && t.description.contains("d") && t.inputSchema == full.inputSchema)
      Set(
        "annotations" -> t.annotations.isDefined,
        "title" -> t.title.isDefined,
        "outputSchema" -> t.outputSchema.isDefined,
        "_meta" -> t._meta.isDefined,
        "icons" -> t.icons.isDefined,
        "execution" -> t.execution.isDefined
      ).collect { case (name, true) => name }

    assert(present("2024-10-07") == Set.empty)
    assert(present("2024-11-05") == Set.empty)
    assert(present("2025-03-26") == Set("annotations"))
    assert(present("2025-06-18") == Set("annotations", "title", "outputSchema", "_meta"))
    assert(
      present("2025-11-25") ==
        Set("annotations", "title", "outputSchema", "_meta", "icons", "execution")
    )
    assert(present("2026-07-28") == Set("annotations", "title", "outputSchema", "_meta", "icons"))
    assert(WireMapping.toolForVersion(full, "") == full)
  }
