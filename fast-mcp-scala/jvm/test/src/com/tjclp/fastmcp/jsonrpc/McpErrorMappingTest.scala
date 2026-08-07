package com.tjclp.fastmcp.jsonrpc

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.core.ErrorCodes
import com.tjclp.fastmcp.server.manager.{
  PromptArgumentError,
  PromptNotFoundError,
  ResourceNotFoundError,
  TaskConcurrencyLimitExceeded,
  TaskNotFoundError
}
import com.tjclp.fastmcp.server.router.Session
import com.tjclp.fastmcp.server.transport.MessageLoop
import com.tjclp.fastmcp.server.{McpServer, McpServerSettings, TaskSettings}

/** Pins the JSON-RPC codes produced at the dispatch boundary for every domain error
  * ([[McpErrorCarrier]]), both at the `fromThrowable` unit level and through the router. Regression
  * coverage for the review findings: unknown resource was -32603 before moving through the former
  * -32002 mapping to the 2026-required -32602 code (while retaining `data.uri`), unknown prompt was
  * -32603, `tasks/result` unknown id was -32002, and the task concurrency cap was -32603 (0.4.0
  * returned -32602 for both task cases).
  */
class McpErrorMappingTest extends AnyFunSuite with Matchers:

  private case class GreetArgs(name: String) derives zio.json.JsonDecoder

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""

  // ---- fromThrowable unit mappings ----

  test("ResourceNotFoundError maps to -32602 with data.uri") {
    val err = McpError.fromThrowable(new ResourceNotFoundError("res://missing"))
    err.code shouldBe ErrorCodes.ResourceNotFound
    err.message should include("res://missing")
    err.data.map(_.toString).getOrElse("") should include("res://missing")
  }

  test("TaskNotFoundError maps to -32602") {
    val err = McpError.fromThrowable(new TaskNotFoundError("t-1"))
    err.code shouldBe ErrorCodes.InvalidParams
    err.message shouldBe "Unknown task: t-1"
  }

  test("TaskConcurrencyLimitExceeded maps to -32602") {
    val err = McpError.fromThrowable(TaskConcurrencyLimitExceeded(Some("s"), 2))
    err.code shouldBe ErrorCodes.InvalidParams
    err.message should include("concurrency limit")
  }

  test("PromptNotFoundError and PromptArgumentError map to -32602") {
    McpError
      .fromThrowable(new PromptNotFoundError("Prompt 'x' not found"))
      .code shouldBe ErrorCodes.InvalidParams
    McpError
      .fromThrowable(new PromptArgumentError("Missing required arguments for prompt 'x': a"))
      .code shouldBe ErrorCodes.InvalidParams
  }

  test("NoSuchElementException (missing request data) maps to -32602, not -32002") {
    val err = McpError.fromThrowable(new NoSuchElementException("Missing required argument: a"))
    err.code shouldBe ErrorCodes.InvalidParams
  }

  test("fallback classifications are unchanged") {
    McpError.fromThrowable(McpError.methodNotFound("x")).code shouldBe ErrorCodes.MethodNotFound
    McpError
      .fromThrowable(new IllegalArgumentException("bad"))
      .code shouldBe ErrorCodes.InvalidParams
    McpError
      .fromThrowable(new java.util.concurrent.TimeoutException("slow"))
      .code shouldBe ErrorCodes.InternalError
    McpError.fromThrowable(new RuntimeException("boom")).code shouldBe ErrorCodes.InternalError
  }

  // ---- router-level: the codes actually reach the wire ----

  test("resources/read with unknown URI answers -32602 and data.uri") {
    val server = McpServer("ErrServer")
    runUnsafe(server.resource(McpStaticResource("test://x", name = Some("x"))("body")))
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("err"))
    runUnsafe(MessageLoop.handleFrame(router, session, initFrame))

    val reply = runUnsafe(
      MessageLoop.handleFrame(
        router,
        session,
        """{"jsonrpc":"2.0","id":2,"method":"resources/read","params":{"uri":"test://nope"}}"""
      )
    ).getOrElse(fail("no reply"))
    reply should include(s""""code":${ErrorCodes.ResourceNotFound}""")
    reply should include(""""uri":"test://nope"""")
  }

  test("prompts/get with unknown name or missing required argument answers -32602") {
    // prompts/get is only wired when a prompt is registered (honest capabilities), so mount one.
    val server = McpServer("ErrServer2")
    runUnsafe(
      server.prompt(
        McpPrompt[GreetArgs](
          name = "greet",
          arguments = List(PromptArgument("name", Some("who"), required = true))
        )(args => List(Message(Role.User, TextContent(s"Hi ${args.name}"))))
      )
    )
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("err2"))
    runUnsafe(MessageLoop.handleFrame(router, session, initFrame))

    val unknown = runUnsafe(
      MessageLoop.handleFrame(
        router,
        session,
        """{"jsonrpc":"2.0","id":3,"method":"prompts/get","params":{"name":"ghost"}}"""
      )
    ).getOrElse(fail("no reply"))
    unknown should include(s""""code":${ErrorCodes.InvalidParams}""")
    unknown should include("not found")

    val missingArg = runUnsafe(
      MessageLoop.handleFrame(
        router,
        session,
        """{"jsonrpc":"2.0","id":4,"method":"prompts/get","params":{"name":"greet"}}"""
      )
    ).getOrElse(fail("no reply"))
    missingArg should include(s""""code":${ErrorCodes.InvalidParams}""")
    missingArg should include("Missing required arguments")
  }

  test("legacy tools/call: a handler-raised McpError stays in-band as isError:true") {
    val server = McpServer("ErrServer4")
    runUnsafe(
      server.tool(
        McpTool.withSchema[GreetArgs, String](
          name = "domain-error",
          inputSchema =
            ToolInputSchema.unsafeFromJsonString("""{"type":"object","properties":{}}"""),
          description = Some("Raises an McpError as a domain failure")
        )(_ => throw McpError.invalidParams("domain failure"))
      )
    )
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("err4"))
    runUnsafe(MessageLoop.handleFrame(router, session, initFrame))

    val reply = runUnsafe(
      MessageLoop.handleFrame(
        router,
        session,
        """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"domain-error","arguments":{"name":"x"}}}"""
      )
    ).getOrElse(fail("no reply"))
    reply should include(""""isError":true""")
    reply should include("domain failure")
    reply should not include """"error":{"""
  }

  test("legacy tools/call: a capability-gate failure inside the handler is in-band, not -32600") {
    val server = McpServer("ErrServer5")
    runUnsafe(
      server.tool(
        McpTool
          .withSchema[GreetArgs, String](
            name = "needs-roots",
            inputSchema =
              ToolInputSchema.unsafeFromJsonString("""{"type":"object","properties":{}}"""),
            description = Some("Requires the roots capability")
          )
          .contextual((_, ctx) => ctx.get.listRoots().as("roots received"))
      )
    )
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("err5"))
    // initFrame declares no capabilities, so the handler's requireCapability("roots") fails.
    runUnsafe(MessageLoop.handleFrame(router, session, initFrame))

    val reply = runUnsafe(
      MessageLoop.handleFrame(
        router,
        session,
        """{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"needs-roots","arguments":{"name":"x"}}}"""
      )
    ).getOrElse(fail("no reply"))
    reply should include(""""isError":true""")
    reply should include("roots")
    reply should not include """"error":{"""
  }

  test("tasks/result with unknown id answers -32602") {
    val server =
      McpServer("ErrServer3", "0.1.0", McpServerSettings(tasks = TaskSettings(enabled = true)))
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("err3"))
    runUnsafe(MessageLoop.handleFrame(router, session, initFrame))

    val reply = runUnsafe(
      MessageLoop.handleFrame(
        router,
        session,
        """{"jsonrpc":"2.0","id":4,"method":"tasks/result","params":{"taskId":"nope"}}"""
      )
    ).getOrElse(fail("no reply"))
    reply should include(s""""code":${ErrorCodes.InvalidParams}""")
    reply should include("Unknown task")
  }
