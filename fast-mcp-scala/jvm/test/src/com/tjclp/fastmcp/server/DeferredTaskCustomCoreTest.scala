package com.tjclp.fastmcp.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.server.manager.*
import com.tjclp.fastmcp.server.transport.HttpTransportBackend

/** A core that is not an `McpServer` cannot receive `McpTool.withDeferredTask` (the flag travels
  * through a library-private method), so registering such a tool on it fails instead of silently
  * creating the task before the handler runs. Review of PR #144.
  */
class DeferredTaskCustomCoreTest extends AnyFunSuite with Matchers:

  private case class NoArgs()

  /** What a custom `McpServerCoreFactory` typically builds: a core that forwards to a server. */
  final private class Wrapping(inner: McpServer[Any]) extends McpServerCore[Any]:
    protected def decodeContext: McpDecodeContext = Wrapping.context

    def tool[R1 >: Any](
        definition: ToolDefinition,
        handler: ContextualToolHandler[R1],
        options: ToolRegistrationOptions
    ): ZIO[Any, Throwable, McpServerCore[Any]] = inner.tool(definition, handler, options).as(this)

    def resource[R1 >: Any](
        definition: ResourceDefinition,
        handler: ResourceHandler[R1]
    ): ZIO[Any, Throwable, McpServerCore[Any]] = inner.resource(definition, handler).as(this)

    def resourceTemplate[R1 >: Any](
        definition: ResourceDefinition,
        handler: ResourceTemplateHandler[R1]
    ): ZIO[Any, Throwable, McpServerCore[Any]] =
      inner.resourceTemplate(definition, handler).as(this)

    def prompt[R1 >: Any](
        definition: PromptDefinition,
        handler: PromptHandler[R1]
    ): ZIO[Any, Throwable, McpServerCore[Any]] = inner.prompt(definition, handler).as(this)

    def promptContextual[R1 >: Any](
        definition: PromptDefinition,
        handler: ContextualPromptHandler[R1]
    ): ZIO[Any, Throwable, McpServerCore[Any]] =
      inner.promptContextual(definition, handler).as(this)

    def runStdio(): ZIO[Any, Throwable, Unit] = inner.runStdio()
    def runHttp()(using HttpTransportBackend): ZIO[Any, Throwable, Unit] = inner.runHttp()

  private object Wrapping:
    val context: McpDecodeContext = com.tjclp.fastmcp.codec.DefaultDecodeContext.default

  private def run[A](effect: ZIO[Any, Throwable, A]): Either[Throwable, A] =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect.either).getOrThrowFiberFailure()
    }

  private def tool(name: String) =
    McpTool[NoArgs, String](name = name)(_ => ZIO.succeed("ok"))
      .withTaskSupport(TaskSupport.Optional)

  test("a deferred tool fails to register on a core that is not an McpServer") {
    val server = McpServer("wrapped", settings = McpServerSettings(tasks = TaskSettings(enabled = true)))
    val core = new Wrapping(server)
    val refused = run(core.tool(tool("deferred").withDeferredTask))
    refused.isLeft shouldBe true
    val message = refused.left.toOption.map(_.getMessage).getOrElse("")
    message should include("deferred")
    message should include("withDeferredTask")
    server.toolInputValidation.keySet should not contain "deferred"
  }

  test("a tool that is not deferred still registers there, and a deferred one on the server") {
    val server = McpServer("wrapped", settings = McpServerSettings(tasks = TaskSettings(enabled = true)))
    run(new Wrapping(server).tool(tool("plain"))).isRight shouldBe true
    run(server.tool(tool("deferred").withDeferredTask)).isRight shouldBe true
  }
