package com.tjclp.fastmcp
package macros

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.MacpRegistrationMacro.scanAnnotations
import com.tjclp.fastmcp.server.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*

/** Simplified test for ToolProcessor to improve coverage */
class ToolProcessorExtendedTest extends AnyFunSuite with Matchers {
  // Create a server for testing
  val testServer = ToolProcessorExtendedTest.server

  test("should handle context-aware tools") {
    // Register the tools
    testServer.scanAnnotations[ToolProcessorExtendedTest.type]

    // Check that the context-aware tool was registered
    val tools = testServer.toolManager.listDefinitions()
    val contextTool = tools.find(_.name == "context-aware-tool")
    contextTool.isDefined should be(true)

    // Execute the tool with context
    val result = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(
          testServer.toolManager.callTool(
            "context-aware-tool",
            Map("message" -> "Hello"),
            Some(McpContext())
          )
        )
        .getOrThrowFiberFailure()
    }

    // Verify the result is a string
    result.isInstanceOf[String] should be(true)
  }
}

/** Companion object with tool definitions for testing */
object ToolProcessorExtendedTest {
  // Create a test server for tool registration
  val server = new FastMcpServer("TestServer", "0.1.0")

  /** A tool that uses the McpContext parameter */
  @Tool(name = Some("context-aware-tool"), description = Some("A tool that uses context"))
  def contextAwareTool(
      @ToolParam("Message to echo") message: String,
      ctx: McpContext
  ): String = {
    s"$message (This is a context-aware tool)"
  }
}
