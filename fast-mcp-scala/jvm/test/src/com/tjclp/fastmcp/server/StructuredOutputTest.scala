package com.tjclp.fastmcp.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import sttp.tapir.generic.auto.*
import zio.*
import zio.json.*

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.core.wire.Implementation
import com.tjclp.fastmcp.jsonrpc.McpError
import com.tjclp.fastmcp.server.router.{McpRouter, RouterBuilder, ServerHooks, Session}
import com.tjclp.fastmcp.server.transport.MessageLoop

/** End-to-end coverage for the structured-output path (review finding F5b): a typed tool that
  * declares an `outputSchema` must advertise it on `tools/list` AND return conforming
  * `structuredContent` on every call (spec MUST — strict TS-SDK clients validate and fail
  * otherwise). Also pins the ServerHooks tool-call seam, previously declared but never invoked.
  */
class StructuredOutputTest extends AnyFunSuite with Matchers:

  case class AddArgs(a: Int, b: Int)
  case class AddResult(sum: Int)

  given JsonEncoder[AddResult] = DeriveJsonEncoder.gen[AddResult]

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""

  private def frame(router: McpRouter[Any], session: Session, s: String): String =
    runUnsafe(MessageLoop.handleFrame(router, session, s)).getOrElse(fail("no reply"))

  test("withOutputSchema advertises the schema and emits structuredContent + text fallback") {
    val server = McpServer("StructServer")
    runUnsafe(
      server.tool(
        McpTool[AddArgs, AddResult](name = "add", description = Some("Add")) { args =>
          AddResult(args.a + args.b)
        }.withOutputSchema
      )
    )
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("struct"))
    frame(router, session, initFrame)

    val list = frame(router, session, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
    list should include(""""outputSchema"""")
    list should include(""""sum"""")

    val call = frame(
      router,
      session,
      """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"add","arguments":{"a":40,"b":2}}}"""
    )
    call should include(""""structuredContent":{"sum":42}""")
    call should include(""""type":"text"""") // text fallback rides along
  }

  test("without withOutputSchema neither outputSchema nor structuredContent is emitted") {
    val server = McpServer("PlainStructServer")
    runUnsafe(
      server.tool(
        McpTool[AddArgs, AddResult](name = "add") { args => AddResult(args.a + args.b) }
      )
    )
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("plain-struct"))
    frame(router, session, initFrame)

    frame(router, session, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""") should
      not include "outputSchema"
    frame(
      router,
      session,
      """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"add","arguments":{"a":1,"b":2}}}"""
    ) should not include "structuredContent"
  }

  test("ServerHooks before/afterToolCall and onError fire around tools/call") {
    val server = McpServer("HookServer")
    runUnsafe(
      server.tool(McpTool[AddArgs, Int](name = "add")(args => args.a + args.b))
    )
    val (beforeRef, afterRef, errorRef) =
      runUnsafe(Ref.make(0) <*> Ref.make(0) <*> Ref.make(0))
    val hooks = new ServerHooks[Any]:
      override def beforeToolCall(name: String, args: zio.json.ast.Json, session: Session) =
        beforeRef.update(_ + 1)
      override def afterToolCall(name: String, result: zio.json.ast.Json, session: Session) =
        afterRef.update(_ + 1)
      override def onError(method: String, error: McpError, session: Session) =
        errorRef.update(_ + 1)

    val router = RouterBuilder.build[Any](
      serverInfo = Implementation(name = "HookServer", version = "0.1.0"),
      instructions = None,
      toolManager = server.toolManager,
      promptManager = server.promptManager,
      resourceManager = server.resourceManager,
      settings = McpServerSettings(),
      hooks = hooks
    )
    val session = runUnsafe(Session.make("hooks"))
    frame(router, session, initFrame)
    frame(
      router,
      session,
      """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"add","arguments":{"a":1,"b":2}}}"""
    )
    // An unknown tool is rejected by ValidationMiddleware BEFORE the handler, so neither
    // before nor after fires — only onError (the hooks wrap handler execution, not validation).
    val ghostReply = frame(
      router,
      session,
      """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ghost","arguments":{}}}"""
    )
    ghostReply should include("Unknown tool")
    withClue(s"ghost reply: $ghostReply — (before, after, error): ") {
      runUnsafe(beforeRef.get <*> afterRef.get <*> errorRef.get) shouldBe ((1, 1, 1))
    }
  }
