package com.tjclp.fastmcp
package macros

import java.util.concurrent.atomic.AtomicReference

import org.scalatest.funsuite.AnyFunSuite
import zio.*

import JsonTestSupport.*
import core.toJsonString
import core.wire.Implementation
import server.*
import server.transport.JvmTransportBackend.given
import RegistrationMacro.*

/** Verifies the `ctx: McpContext` parameter is excluded from the generated schema and that the
  * runtime context is threaded into handler calls.
  *
  * Natively there is no Java SDK exchange to mock — an [[McpContext]] just snapshots the client
  * info/capabilities captured at `initialize`, so these tests build one directly.
  */
class ContextPropagationTest extends AnyFunSuite:

  test("Context parameter is excluded from schema") {
    // Define a function with a context parameter
    def testFn(name: String, age: Int, ctx: McpContext): String =
      s"Name: $name, Age: $age, Context: ${ctx.getClientInfo.map(_.name).getOrElse("Unknown")}"

    // Generate schema for the function
    val schema = JsonSchemaMacro.schemaForFunctionArgs(testFn, exclude = List("ctx"))

    // Verify the schema only contains 'name' and 'age' properties
    val properties = schema.hcursor.downField("properties").keys.getOrElse(Iterable.empty)
    assert(
      properties.toSet == Set("name", "age"),
      "Schema should only contain non-context parameters"
    )

    // Verify required fields don't include ctx
    val required = schema.hcursor.downField("required").as[List[String]].getOrElse(Nil)
    assert(!required.contains("ctx"), "Required fields should not contain ctx parameter")
  }

  test("Context is automatically injected into function calls") {
    // Capture the context that gets passed to the handler
    val capturedContext = new AtomicReference[Option[McpContext]](None)

    val server = new McpServer[Any]("ContextTestServer")

    // Native context carrying the client identity captured at `initialize`
    val mockContext = new McpContext(None, Some(Implementation("TestClient", "1.0.0")), None)

    // Register a tool that captures the context
    val toolEffect = server.tool(
      name = "context-test",
      handler = (args: Map[String, Any], ctxOpt: Option[McpContext]) => {
        capturedContext.set(ctxOpt)
        ZIO.succeed(s"Received context: ${ctxOpt.isDefined}")
      }
    )

    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(toolEffect).getOrThrowFiberFailure()
    }

    // Call the tool with the context
    val callEffect =
      server.toolManager.callTool("context-test", Map("dummy" -> "value"), Some(mockContext))

    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(callEffect).getOrThrowFiberFailure()
    }

    val passed = capturedContext.get()
    assert(passed.isDefined, "Context should be passed to the handler")
    assert(
      passed.get.getClientInfo.map(_.name) == Some("TestClient"),
      "Context should contain the expected client info"
    )
  }

  test("@Tool annotations properly handle context parameters") {
    val server = new McpServer[Any]("AnnotationContextTest")

    server.scanAnnotations[ContextAwareTool.type]
    val toolDef = server.toolManager.getToolDefinition("context-aware-tool")
    assert(toolDef.isDefined, "Tool should be registered")

    // Verify the schema doesn't include ctx
    val schemaStr = toolDef.get.inputSchema.toJsonString
    assert(!schemaStr.contains("\"ctx\""), "Schema should not contain ctx parameter")

    // Call the function with a context carrying client info
    val mockContext =
      new McpContext(None, Some(Implementation("AnnotationTestClient", "1.0.0")), None)

    val result = server.toolManager.callTool(
      "context-aware-tool",
      Map("message" -> "Hello!"),
      Some(mockContext)
    )

    val output = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(result).getOrThrowFiberFailure()
    }

    assert(
      output.toString.contains("AnnotationTestClient"),
      "Function output should contain client name from context"
    )
  }
end ContextPropagationTest
