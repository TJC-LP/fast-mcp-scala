package com.tjclp.fastmcp.server

import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.spec.McpSchema
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*

import com.tjclp.fastmcp.server.manager.ToolRegistrationOptions

class FastMcpServerTest extends AnyFunSuite with Matchers {
  test("Server instantiation with default settings") {
    val server = new FastMcpServer()

    assert(server.name == "FastMCPScala")
  }

  test("Server instantiation with custom settings") {
    val settings = McpServerSettings(
      port = 8080,
      host = "localhost"
    )
    val server = new FastMcpServer(name = "TestServer", settings = settings)

    assert(server.name == "TestServer")
  }

  test("legacy Either schema overload should work for FastMcpServer and McpServerCore") {
    val concrete = new FastMcpServer(name = "CompatServer")
    val schema = Left(
      McpJsonDefaults
        .getMapper()
        .readValue("""{"type":"object","additionalProperties":true}""", classOf[McpSchema.JsonSchema])
    )

    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(
          concrete.tool(
            name = "legacy-concrete",
            handler = (_, _) => ZIO.succeed("ok"),
            description = None,
            inputSchema = schema,
            options = ToolRegistrationOptions(),
            annotations = None
          )
        )
        .getOrThrowFiberFailure()
    }

    concrete.toolManager.getToolDefinition("legacy-concrete") should not be empty

    val asApi: McpServerCore = concrete
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(
          asApi.tool(
            name = "legacy-api",
            handler = (_, _) => ZIO.succeed("ok"),
            description = None,
            inputSchema = schema,
            options = ToolRegistrationOptions(),
            annotations = None
          )
        )
        .getOrThrowFiberFailure()
    }

    concrete.toolManager.getToolDefinition("legacy-api") should not be empty
  }
}
