package com.tjclp.fastmcp
package macros

import java.util.concurrent.atomic.AtomicReference

import io.modelcontextprotocol.json.TypeRef
import io.modelcontextprotocol.server.McpAsyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.scalatest.funsuite.AnyFunSuite
import zio.*

import server.*
import RegistrationMacro.*

class ContextPropagationTest extends AnyFunSuite:

  test("Context parameter is excluded from schema") {
    // Define a function with a context parameter
    def testFn(name: String, age: Int, ctx: McpContext): String =
      s"Name: $name, Age: $age, Context: ${ctx.getClientInfo.map(_.name()).getOrElse("Unknown")}"

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
    // Create an atomic reference to capture the context that gets passed to the function
    val capturedContext = new AtomicReference[Option[McpContext]](None)

    // Create a simple server for testing
    val server = new FastMcpServer("ContextTestServer")

    // Create a mock context with client info
    val mockClient = new McpSchema.Implementation("TestClient", "1.0.0")
    val mockContext = McpContext(
      javaExchange = Some(new MockServerExchange(mockClient))
    )

    // Register a tool that captures the context
    val toolEffect = server.tool(
      name = "context-test",
      handler = (args: Map[String, Any], ctxOpt: Option[McpContext]) => {
        capturedContext.set(ctxOpt)
        ZIO.succeed(s"Received context: ${ctxOpt.isDefined}")
      }
    )

    // Register the tool
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(toolEffect).getOrThrowFiberFailure()
    }

    // Call the tool with a context
    val callEffect =
      server.toolManager.callTool("context-test", Map("dummy" -> "value"), Some(mockContext))

    // Run the effect
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(callEffect).getOrThrowFiberFailure()
    }

    // Verify the context was passed to the handler
    val passed = capturedContext.get()
    assert(passed.isDefined, "Context should be passed to the handler")
    assert(
      passed.get.getClientInfo.map(_.name()) == Some("TestClient"),
      "Context should contain the expected client info"
    )
  }

  test("@Tool annotations properly handle context parameters") {
    // Set up a test server
    val server = new FastMcpServer("AnnotationContextTest")

    // Get the tool definition
    server.scanAnnotations[ContextAwareTool.type]
    val toolDef = server.toolManager.getToolDefinition("context-aware-tool")
    assert(toolDef.isDefined, "Tool should be registered")

    // Create a schema parser to check the schema structure
    val schemaStr = toolDef.get.inputSchema match {
      case Right(s) => s
      case Left(js) => js.toString
    }

    // Verify the schema doesn't include ctx
    assert(!schemaStr.contains("\"ctx\""), "Schema should not contain ctx parameter")

    // Now test calling the function
    val mockClient = new McpSchema.Implementation("AnnotationTestClient", "1.0.0")
    val mockContext = McpContext(
      javaExchange = Some(new MockServerExchange(mockClient))
    )

    // Call the tool
    val result = server.toolManager.callTool(
      "context-aware-tool",
      Map("message" -> "Hello!"),
      Some(mockContext)
    )

    // Verify the result
    val output = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(result).getOrThrowFiberFailure()
    }

    assert(
      output.toString.contains("AnnotationTestClient"),
      "Function output should contain client name from context"
    )
  }
end ContextPropagationTest

// A simple mock for McpAsyncServerExchange for testing
class NoopLoggableSession extends io.modelcontextprotocol.spec.McpLoggableSession:
  override def setMinLoggingLevel(level: McpSchema.LoggingLevel): Unit = ()
  override def isNotificationForLevelAllowed(level: McpSchema.LoggingLevel): Boolean = false

  override def sendRequest[T](
      method: String,
      params: Object,
      typeRef: TypeRef[T]
  ): reactor.core.publisher.Mono[T] = reactor.core.publisher.Mono.empty()

  override def sendNotification(method: String): reactor.core.publisher.Mono[Void] =
    reactor.core.publisher.Mono.empty()

  override def sendNotification(method: String, obj: Object): reactor.core.publisher.Mono[Void] =
    reactor.core.publisher.Mono.empty()

  override def closeGracefully(): reactor.core.publisher.Mono[Void] =
    reactor.core.publisher.Mono.empty()
  override def close(): Unit = ()

class MockServerExchange(clientInfo: McpSchema.Implementation)
    extends McpAsyncServerExchange(
      new NoopLoggableSession(),
      new McpSchema.ClientCapabilities(
        null, // experimental
        new McpSchema.ClientCapabilities.RootCapabilities(true), // roots with listChanged=true
        new McpSchema.ClientCapabilities.Sampling(), // sampling
        new McpSchema.ClientCapabilities.Elicitation() // elicitation
      ),
      clientInfo
    ):
  override def getClientInfo(): McpSchema.Implementation = clientInfo

  override def getClientCapabilities(): McpSchema.ClientCapabilities =
    new McpSchema.ClientCapabilities(
      null, // experimental
      new McpSchema.ClientCapabilities.RootCapabilities(true), // roots with listChanged=true
      new McpSchema.ClientCapabilities.Sampling(), // sampling
      new McpSchema.ClientCapabilities.Elicitation() // elicitation
    )
