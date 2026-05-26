package com.tjclp.fastmcp.surface

import org.scalatest.funsuite.AnyFunSuite
import sttp.tapir.generic.auto.*
import zio.*
import zio.json.*

import com.tjclp.fastmcp.{given, *}

class RootImportSurfaceJsTest extends AnyFunSuite:

  object ExampleTools:
    @Tool(name = Some("hello"))
    def hello(@Param("Person to greet") name: String): String =
      s"Hello, $name!"

    @Prompt(name = Some("hello_prompt"))
    def helloPrompt(@Param("Person to greet") name: String): String =
      s"Prompt for $name"

    @Resource(uri = "static://hello", description = Some("Greeting resource"))
    def helloResource(): String =
      "hello"

  case class HelloArgs(
      @Param(description = "Person to greet")
      name: String
  )
  case class HelloResult(message: String)

  given JsonEncoder[HelloResult] = DeriveJsonEncoder.gen[HelloResult]

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }

  test("root import exposes the public JS annotation and derived-schema surface") {
    val server = McpServer("RootImportJsServer")
    val _ = server.scanAnnotations[ExampleTools.type]

    val toolDef = server.toolManager.getToolDefinition("hello")
    assert(toolDef.isDefined)
    assert(toolDef.get.inputSchema.toJsonString.contains("name"))

    val schema = ToolInputSchema.derived[HelloArgs]
    assert(schema.toJsonString.contains("name"))
    assert(schema.toJsonString.contains("Person to greet"))

    val typedTool = McpTool[HelloArgs, HelloResult](
      name = "typed-hello",
      description = Some("Typed greeting")
    ) { args =>
      HelloResult(s"Hello, ${args.name}!")
    }
    assert(typedTool.definition.inputSchema.toJsonString.contains("name"))

    val toolResult = runUnsafe(server.toolManager.callTool("hello", Map("name" -> "Ada"), None))
    assert(toolResult == "Hello, Ada!")

    val promptResult =
      runUnsafe(server.promptManager.getPrompt("hello_prompt", Map("name" -> "Ada"), None))
    assert(promptResult.headOption.exists(_.content.asInstanceOf[TextContent].text.contains("Ada")))

    val resourceResult = runUnsafe(server.resourceManager.readResource("static://hello", None))
    assert(resourceResult == "hello")
  }
