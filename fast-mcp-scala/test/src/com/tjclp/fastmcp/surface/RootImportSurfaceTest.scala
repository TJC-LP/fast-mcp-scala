package com.tjclp.fastmcp.surface

import org.scalatest.funsuite.AnyFunSuite
import zio.*

import com.tjclp.fastmcp.{given, *}

class RootImportSurfaceTest extends AnyFunSuite {

  object ExampleTools:
    @Tool(name = Some("hello"))
    def hello(@Param("Person to greet") name: String): String =
      s"Hello, $name!"

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
  }
}
