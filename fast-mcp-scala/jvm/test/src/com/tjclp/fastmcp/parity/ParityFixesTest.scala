package com.tjclp.fastmcp
package parity

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.core.wire.*
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.router.{Session, WireMapping}
import com.tjclp.fastmcp.server.transport.MessageLoop
import com.tjclp.fastmcp.server.transport.JvmTransportBackend.given

/** Regression coverage for the Phase-1 parity fixes (vs the TS SDK audit):
  *   - `Icon.sizes` is a list + `Icon.theme` field
  *   - `PromptArgument.title`
  *   - `Tool.outputSchema` flows from `ToolDefinition` to the wire
  *   - `completion/complete` handler + honest `completions` capability
  */
class ParityFixesTest extends AnyFunSuite with Matchers:

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  test("Icon round-trips sizes as a JSON array and carries the theme field") {
    val icon = Icon(
      src = "https://example.com/icon.png",
      mimeType = Some("image/png"),
      sizes = Some(List("48x48", "96x96")),
      theme = Some("dark")
    )
    val json = icon.toJson
    json should include(""""sizes":["48x48","96x96"]""")
    json should include(""""theme":"dark"""")
    json.fromJson[Icon] shouldBe Right(icon)
  }

  test("PromptArgument round-trips the title field") {
    val arg = PromptArgument(name = "topic", description = Some("d"), required = true, title = Some("Topic"))
    arg.toJson should include(""""title":"Topic"""")
    arg.toJson.fromJson[PromptArgument] shouldBe Right(arg)
  }

  test("ToolDefinition.outputSchema flows to the wire Tool") {
    val out = ToolOutputSchema.unsafeFromJsonString(
      """{"type":"object","properties":{"sum":{"type":"integer"}}}"""
    )
    val d = ToolDefinition("add", Some("Add"), ToolInputSchema.default, outputSchema = Some(out))
    val wire = WireMapping.toolToWire(d, tasksEnabled = false)
    wire.outputSchema shouldBe Some(out)
    wire.toJson should include(""""outputSchema":{""")
  }

  test("completion/complete dispatches to a registered provider; capability advertised honestly") {
    val server = McpServer.typed[Any]("CompletionServer", "0.1.0")
    runUnsafe(
      server
        .completion[Any]((req, _) =>
          ZIO.succeed(
            Completion(values = List("scala", "scalajs", "kotlin").filter(_.startsWith(req.argument.value)))
          )
        )
        .unit
    )
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("parity-test"))

    val initFrame =
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""
    val initReply =
      runUnsafe(MessageLoop.handleFrame(router, session, initFrame)).getOrElse(fail("no init reply"))
    initReply should include("\"completions\"") // honest capability: advertised because a provider is wired

    val completeFrame =
      """{"jsonrpc":"2.0","id":2,"method":"completion/complete","params":{"ref":{"type":"ref/prompt","name":"p"},"argument":{"name":"lang","value":"sca"}}}"""
    val completeReply =
      runUnsafe(MessageLoop.handleFrame(router, session, completeFrame)).getOrElse(fail("no reply"))
    completeReply should include(""""values":["scala","scalajs"]""")
  }

  test("completions capability is NOT advertised when no provider is registered (#56 honesty)") {
    val server = McpServer.typed[Any]("NoCompletionServer", "0.1.0")
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("parity-test-2"))
    val initFrame =
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""
    val initReply =
      runUnsafe(MessageLoop.handleFrame(router, session, initFrame)).getOrElse(fail("no init reply"))
    initReply should not include "completions"
  }
