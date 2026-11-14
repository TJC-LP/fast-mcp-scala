package com.tjclp.fastmcp
package server.transport.http

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.core.Protocol
import com.tjclp.fastmcp.server.transport.{ModernHttpValidation, TransportBackend}

/** The legacy stateless adapter (`stateless = true`) has no `initialize`, so the revision a reply
  * is written for comes from the request's `mcp-protocol-version` header (review of #157): the
  * `tools/list` field projection applies there too. Also pins what a `required` task tool answers
  * on that adapter. On the shared handler, on the JVM and on Scala Native; the Bun twin is
  * `JsStatelessRevisionTest`.
  */
class LegacyStatelessRevisionTest extends AnyFunSuite with Matchers:

  case class LookupArgs(q: String)
  case class LookupResult(answer: String)

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private val lookup = McpTool[LookupArgs, LookupResult](
    name = "lookup",
    description = Some("Look something up"),
    annotations = Some(ToolAnnotations(title = Some("Look up"), readOnlyHint = Some(true)))
  )(args => LookupResult(args.q)).withOutputSchema.withTaskSupport(TaskSupport.Required)

  private def makeHandler(stateless: Boolean) =
    val server = McpServer.typed[Any](
      "StatelessRev",
      "0.1.0",
      McpServerSettings(stateless = stateless, tasks = TaskSettings(enabled = true))
    )
    runUnsafe(
      server.tool(lookup) *> server.buildRouter.flatMap { router =>
        StreamableHttpHandler.make(router, server.settings, summon[TransportBackend].randomId())
      }
    )

  private val listFrame = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""
  private val callFrame =
    """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"lookup","arguments":{"q":"x"}}}"""

  private def post(
      handler: StreamableHttpHandler[Any],
      body: String,
      version: Option[String]
  ): String =
    val headers = Map(
      "accept" -> "application/json, text/event-stream",
      "content-type" -> "application/json"
    ) ++ version.map("mcp-protocol-version" -> _)
    val lookupHeader: String => Option[String] = name => headers.get(name.toLowerCase)
    val req = HttpRequest("POST", "/mcp", lookupHeader, lookupHeader, ZIO.succeed(body))
    runUnsafe(handler.handle(req)) match
      case HttpReply.Json(200, reply, _) => reply
      case other => fail(s"expected a 200 JSON reply, got $other")

  private def toolFields(reply: String): Set[String] =
    val fields = for
      json <- reply.fromJson[Json].toOption
      result <- json.asObject.flatMap(_.get("result")).flatMap(_.asObject)
      tools <- result.get("tools").flatMap(_.asArray)
      first <- tools.headOption.flatMap(_.asObject)
    yield first.keys.toSet
    fields.getOrElse(fail(s"not a tools/list reply with one tool: $reply"))

  private val base = Set("name", "description", "inputSchema")

  test("stateless tools/list is written for the revision the mcp-protocol-version header names") {
    val handler = makeHandler(stateless = true)
    val expected = List(
      Some("2024-10-07") -> base,
      Some("2024-11-05") -> base,
      Some("2025-03-26") -> (base + "annotations"),
      Some("2025-06-18") -> (base ++ Set("annotations", "title", "outputSchema")),
      // No `execution`: this adapter has no task surface, so it declares none (#156).
      Some("2025-11-25") -> (base ++ Set("annotations", "title", "outputSchema")),
      // No header: the revision the specification assumes for a request without one.
      None -> (base + "annotations")
    )
    for (version, fields) <- expected do
      withClue(s"$version: ") {
        toolFields(post(handler, listFrame, version)) shouldBe fields
      }
  }

  test("legacyRequestVersion: a legacy header value, else the default negotiated revision") {
    def of(value: Option[String]): String =
      ModernHttpValidation.legacyRequestVersion(name =>
        if name == "mcp-protocol-version" then value else None
      )
    of(None) shouldBe "2025-03-26"
    for version <- Protocol.LegacyProtocolVersions do of(Some(version)) shouldBe version
    // Never reached with these (such a request is on the 2026-07-28 path); still a legacy answer.
    of(Some("2026-07-28")) shouldBe "2025-03-26"
    of(Some("bogus")) shouldBe "2025-03-26"
  }

  test("a required-task tool called on the stateless adapter is refused with what does work") {
    val reply = post(makeHandler(stateless = true), callFrame, Some("2025-11-25"))
    reply should include(""""code":-32601""")
    reply should include("Tool 'lookup' requires task augmentation, which is not available on")
    reply should include("legacy stateless transport")
    reply should include("MCP 2026-07-28 task requests")
  }
