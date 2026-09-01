package com.tjclp.fastmcp.surface

import org.scalatest.funsuite.AnyFunSuite
import zio.*
import zio.json.*

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.codec.DefaultDecodeContext
import com.tjclp.fastmcp.macros.MapToFunctionMacro

class RootImportSurfaceTest extends AnyFunSuite {

  object ExampleTools:
    @Tool(name = Some("hello"))
    def hello(@Param("Person to greet") name: String): String =
      s"Hello, $name!"

  case class HelloArgs(name: String)
  case class HelloResult(message: String)
  case class TaggedId(value: String)

  given JsonEncoder[HelloResult] = DeriveJsonEncoder.gen[HelloResult]

  given McpDecoder[TaggedId] with
    def decode(name: String, rawValue: Any, context: McpDecodeContext): TaggedId =
      rawValue match
        case s: String if s.startsWith("{") =>
          TaggedId(context.parseJsonObject(name, s)("value").toString)
        case s: String =>
          TaggedId(s)
        case map: Map[String @unchecked, Any @unchecked] =>
          TaggedId(map("value").toString)
        case other =>
          TaggedId(context.convertValue[String](name, other))

  test("root import exposes the public JVM API surface") {
    val server = McpServer("RootImportServer")
    val _ = server.scanAnnotations[ExampleTools.type]

    val toolDef = server.toolManager.getToolDefinition("hello")
    assert(toolDef.isDefined)

    val schema = ToolInputSchema.derived[HelloArgs]
    val typed = ToolDefinition("typed-tool", None, schema)
    assert(typed.inputSchema.toJsonString.contains("object"))

    val typedTool = McpTool[HelloArgs, HelloResult](
      name = "typed-hello",
      description = Some("Typed greeting")
    ) { args =>
      HelloResult(s"Hello, ${args.name}!")
    }

    val typedPrompt = McpPrompt[HelloArgs](
      name = "typed-prompt",
      arguments = List(PromptArgument("name", Some("The person to greet"), required = true))
    ) { args =>
      List(Message(Role.User, TextContent(s"Prompt for ${args.name}")))
    }

    val staticResource =
      McpStaticResource("static://hello", description = Some("Greeting resource"))("hello")

    val templateResource = McpTemplateResource[HelloArgs](
      uriPattern = "users://{name}",
      arguments = List(ResourceArgument("name", Some("The user name"), required = true))
    ) { args =>
      s"user:${args.name}"
    }

    assert(typedTool.definition.name == "typed-hello")
    assert(typedPrompt.definition.name == "typed-prompt")
    assert(staticResource.definition.uri == "static://hello")
    assert(templateResource.definition.isTemplate)

    val ctx = McpContext.empty
    assert(ctx.getClientInfo.isEmpty)

    val promptMessage: Message = Message(Role.User, TextContent("hi"))
    assert(promptMessage.role == Role.User)

    val tagged = summon[McpDecoder[TaggedId]].decode(
      "tagged",
      """{"value":"abc"}""",
      DefaultDecodeContext.default
    )
    assert(tagged == TaggedId("abc"))
  }

  // Regression for #64: at ROOT-IMPORT call sites `export McpDecoders.given` flattens the
  // low-priority Mirror fallback to the same precedence as the JsonDecoder-based given, and
  // Option[T] (a sum type with a Mirror) used to resolve to a derived sum decoder that expects
  // `{"Some": ...}` — rejecting bare values AND `null`. Must resolve via zio-json's Option decoder.
  test("root import decodes Option params from bare values, null, and absence (#64)") {
    @SuppressWarnings(Array("org.wartremover.warts.Null"))
    val jsonNull: Any = null

    val decoder = summon[McpDecoder[Option[String]]]
    assert(decoder.decode("p", "main", DefaultDecodeContext.default) == Some("main"))
    assert(decoder.decode("p", jsonNull, DefaultDecodeContext.default) == None)

    def search(query: String, drive: Option[String], limit: Option[Int]): String =
      s"$query/${drive.getOrElse("all")}/${limit.getOrElse(10)}"

    val fn = MapToFunctionMacro.callByMap(search).asInstanceOf[Map[String, Any] => Any]
    assert(fn(Map("query" -> "q", "drive" -> "main", "limit" -> 3)) == "q/main/3")
    assert(fn(Map("query" -> "q", "drive" -> jsonNull, "limit" -> jsonNull)) == "q/all/10")
    assert(fn(Map("query" -> "q")) == "q/all/10")
  }
}
