package com.tjclp.fastmcp.surface

import org.scalatest.funsuite.AnyFunSuite

import com.tjclp.fastmcp.{given, *}

class RootImportSurfaceTest extends AnyFunSuite {

  object ExampleTools:
    @Tool(name = Some("hello"))
    def hello(@Param("Person to greet") name: String): String =
      s"Hello, $name!"

  case class TaggedId(value: String)

  given JacksonConverter[TaggedId] with
    def convert(name: String, rawValue: Any, context: JacksonConversionContext): TaggedId =
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
    val server = FastMcpServer("RootImportServer")
    val _ = server.scanAnnotations[ExampleTools.type]

    val toolDef = server.toolManager.getToolDefinition("hello")
    assert(toolDef.isDefined)

    val schema = ToolInputSchema.unsafeFromJsonString("""{"type":"object"}""")
    val typed = ToolDefinition("typed-tool", None, schema)
    assert(typed.inputSchema.toJsonString.contains("object"))

    val ctx = McpContext()
    assert(ctx.getClientInfo.isEmpty)

    val promptMessage: Message = Message(Role.User, TextContent("hi"))
    assert(promptMessage.role == Role.User)

    val tagged = summon[JacksonConverter[TaggedId]].convert(
      "tagged",
      """{"value":"abc"}""",
      JacksonConversionContext.default
    )
    assert(tagged == TaggedId("abc"))
  }
}
