package com.tjclp.fastmcp.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.ast.Json

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.codec.ArgumentDecodeFailure
import com.tjclp.fastmcp.server.router.Session
import com.tjclp.fastmcp.server.transport.MessageLoop

final case class JobId(value: String)

object JobId:

  // The pattern is advertised and not enforced by the input validator, so a bad id reaches the
  // decoder: the one kind of argument error that validation does not report first.
  given McpInputCodec[JobId] = McpInputCodec.string("""{"type":"string","pattern":"^job-[0-9]+$"}""") {
    raw => Either.cond(raw.startsWith("job-"), JobId(raw), s"Invalid job id '$raw'")
  }

final case class ReportArgs(id: JobId, verbose: Boolean = false)
final case class JobWindow(job: JobId, days: Int)
final case class WindowArgs(window: JobWindow)

/** A typed contract decodes the whole `arguments` object as one value, and used to report a decode
  * failure under the name of the tool: `Failed to decode parameter 'report' from JSON:
  * .id(Invalid job id 'nope')`. It now names the argument, as the annotation path does (GH #115,
  * D6.23).
  */
class ContractDecodeFailureTest extends AnyFunSuite with Matchers:

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  /** The message a failed call carries, causes included. */
  private def failureOf(effect: ZIO[Any, Throwable, Any]): String =
    def chain(t: Throwable): List[String] =
      Option(t).fold(Nil)(e => Option(e.getMessage).toList ++ chain(e.getCause))
    runUnsafe(effect.flip.orElseFail(new AssertionError("the call did not fail")).map(chain))
      .mkString(" <- ")

  private val server =
    val s = McpServer("DecodeFailureServer")
    runUnsafe(
      s.tool(McpTool[ReportArgs, String](name = "report")(_.id.value)) *>
        s.tool(McpTool[WindowArgs, String](name = "window")(_.window.job.value)) *>
        s.prompt(McpPrompt[ReportArgs](name = "report-prompt")(args => List(Message(Role.User, TextContent(args.id.value)))))
    )
    s

  test("a failing field is named, with its value: the message the annotation path gives") {
    val failure = failureOf(server.toolManager.callTool("report", Map("id" -> "nope"), None))
    // The format the annotation path uses for a parameter of that name: both go through
    // `DefaultDecodeContext.decodeRaw`, the annotation path once per parameter.
    failure should include(
      """Failed to decode parameter 'id' from JSON: (Invalid job id 'nope'). Value: "nope""""
    )
    failure should not include "'report'"
  }

  test("on the wire: an isError tool result whose text names the argument") {
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("decode"))
    def send(frame: String): String =
      runUnsafe(MessageLoop.handleFrame(router, session, frame)).getOrElse(fail("no reply"))
    val _ = send(
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""
    )
    val reply = send(
      """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"report","arguments":{"id":"nope"}}}"""
    )
    reply should include(""""isError":true""")
    reply should include("Failed to decode parameter 'id' from JSON: (Invalid job id 'nope')")
    reply should not include "parameter 'report'"
  }

  test("a failure deeper in an argument names the argument and keeps the rest of the path") {
    val failure = failureOf(
      server.toolManager.callTool("window", Map("window" -> Map("job" -> "nope", "days" -> 3)), None)
    )
    failure should include("Failed to decode parameter 'window' from JSON: .job(Invalid job id 'nope')")
    failure should include("""Value: {"job":"nope","days":3}""")
    failure should not include "'window' from JSON: .window"
  }

  test("prompts name the argument too") {
    val failure =
      failureOf(server.promptManager.getPrompt("report-prompt", Map("id" -> "nope"), None))
    failure should include("Failed to decode parameter 'id' from JSON: (Invalid job id 'nope')")
    failure should not include "parameter 'report-prompt'"
  }

  test("a valid call is unaffected") {
    runUnsafe(server.toolManager.callTool("report", Map("id" -> "job-7"), None)) match
      case core.StructuredToolResult(List(TextContent(text, _, _)), _) => text shouldBe "job-7"
      case other => fail(s"unexpected result: $other")
  }

  test("forField: only a path that starts at a member of the value is renamed") {
    val value = Json.Obj("id" -> Json.Str("nope"), "ids" -> Json.Arr(Json.Num(1), Json.Str("x")))
    def renamed(reason: String): Option[String] =
      new ArgumentDecodeFailure("report", reason, value).forField.map(_.getMessage)

    renamed(".id(bad)") shouldBe Some("""Failed to decode parameter 'id' from JSON: (bad). Value: "nope"""")
    renamed(".ids[1](not a number)") shouldBe
      Some("""Failed to decode parameter 'ids' from JSON: [1](not a number). Value: [1,"x"]""")
    // A member the client did not send has no value to show.
    renamed(".missing(missing)") shouldBe Some("Failed to decode parameter 'missing' from JSON: (missing)")
    // About the arguments as a whole: no member to name, so the failure keeps its message.
    renamed("(expected '{' got '\"')") shouldBe None
    renamed(".(odd)") shouldBe None
    new ArgumentDecodeFailure("report", "(whole)", value).getMessage should
      startWith("Failed to decode parameter 'report' from JSON: (whole). Value: {")
  }
