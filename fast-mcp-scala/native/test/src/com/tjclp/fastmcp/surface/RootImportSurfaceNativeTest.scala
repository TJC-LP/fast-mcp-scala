package com.tjclp.fastmcp.surface

import org.scalatest.funsuite.AnyFunSuite
import zio.*
import zio.json.*

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.core.StructuredToolResult

class RootImportSurfaceNativeTest extends AnyFunSuite:

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
  enum Mood:
    case happy, sad
  case class MoodArgs(mood: Mood)

  given JsonEncoder[HelloResult] = DeriveJsonEncoder.gen[HelloResult]

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }

  test("root import exposes the public Scala Native annotation and derived-schema surface") {
    val server = McpServer("RootImportNativeServer")
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

  test("typed contracts derive singleton enum schemas and decoders on Scala Native (#78)") {
    val server = McpServer("RootImportNativeEnumServer")
    val tool = McpTool[MoodArgs, String](name = "describe-mood") { args =>
      s"mood:${args.mood}"
    }

    assert(tool.definition.inputSchema.toJsonString.contains("\"enum\":[\"happy\",\"sad\"]"))
    runUnsafe(server.tool(tool))
    val result = runUnsafe(
      server.toolManager.callTool("describe-mood", Map("mood" -> "happy"), None)
    )
    assert(result == StructuredToolResult(List(TextContent("mood:happy")), None))
  }
