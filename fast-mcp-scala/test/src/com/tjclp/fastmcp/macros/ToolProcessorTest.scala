package com.tjclp.fastmcp
package macros

import org.scalatest.funsuite.AnyFunSuite
import sttp.tapir.Schema
import zio.*
import zio.json.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.RegistrationMacro.scanAnnotations
import com.tjclp.fastmcp.server.*

/** Integration test for Tool annotation and processor This tests the full workflow of tool
  * annotation processing
  */
class ToolProcessorTest extends AnyFunSuite {
  // Create a server for testing
  val testServer = ToolProcessorTest.server

  // Test that tool annotations are processed correctly
  test("should process tool annotations and register tools") {
    // First check that no tools are registered
    val initialTools = testServer.toolManager.listDefinitions()
    assert(initialTools.isEmpty)

    // Process tool annotations
    testServer.scanAnnotations[ToolProcessorTest.type]

    // Check tools were registered
    val tools = testServer.toolManager.listDefinitions()
    assert(tools.size == 2)

    // Find calculator tool
    val calculatorTool = tools.find(_.name == "calculator")
    assert(calculatorTool.isDefined)
    assert(calculatorTool.get.description.contains("Performs basic arithmetic operations"))

    // Find uppercase tool
    val uppercaseTool = tools.find(_.name == "uppercase")
    assert(uppercaseTool.isDefined)
  }

  // Test tool execution through the handler
  test("should execute tools via generated handlers") {
    // Process annotations first to ensure tools are available
    testServer.scanAnnotations[ToolProcessorTest.type]

    // Execute calculator tool
    val calcResult = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(
          testServer.toolManager.callTool(
            "calculator",
            Map("a" -> 10.0, "b" -> 5.0, "op" -> "MULTIPLY"),
            None
          )
        )
        .getOrThrowFiberFailure()
    }

    // Verify result
    assert(calcResult.isInstanceOf[ToolProcessorTest.CalculationResult])
    val calculation = calcResult.asInstanceOf[ToolProcessorTest.CalculationResult]
    assert(calculation.result == 50.0)
    assert(calculation.operation == "MULTIPLY")

    // Execute uppercase tool
    val textResult = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(
          testServer.toolManager.callTool("uppercase", Map("text" -> "hello world"), None)
        )
        .getOrThrowFiberFailure()
    }

    // Verify result
    assert(textResult.isInstanceOf[String])
    assert(textResult.asInstanceOf[String] == "HELLO WORLD")
  }

  // Test with enum parameter passed as string
  test("should handle enum parameters passed as strings") {
    // Process annotations first to ensure tools are available
    testServer.scanAnnotations[ToolProcessorTest.type]

    // Pass operation as a string
    val result = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(
          testServer.toolManager.callTool(
            "calculator",
            Map("a" -> 20.0, "b" -> 4.0, "op" -> "DIVIDE"),
            None
          )
        )
        .getOrThrowFiberFailure()
    }

    val calculation = result.asInstanceOf[ToolProcessorTest.CalculationResult]
    assert(calculation.result == 5.0)
    assert(calculation.operation == "DIVIDE")
  }

  // Test with enum parameter passed as string value
  test("should handle enum parameters passed as ordinal string values") {
    // Process annotations first to ensure tools are available
    testServer.scanAnnotations[ToolProcessorTest.type]

    // Pass operation as a string representation (SUBTRACT has ordinal 1)
    val result = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(
          testServer.toolManager.callTool(
            "calculator",
            Map("a" -> 15.0, "b" -> 7.0, "op" -> "SUBTRACT"),
            None
          )
        )
        .getOrThrowFiberFailure()
    }

    val calculation = result.asInstanceOf[ToolProcessorTest.CalculationResult]
    assert(calculation.result == 8.0)
    assert(calculation.operation == "SUBTRACT")
  }

  // Test @Param annotation with all fields (description, examples, required, schema)
  test("@Param annotation with all fields generates correct schema") {
    // Create a separate server for this test to avoid interference
    val paramTestServer = new FastMcpServer("ParamTestServer", "0.1.0")
    paramTestServer.scanAnnotations[ParamMetadataTestTools.type]

    // Get the tool definition
    val toolDef = paramTestServer.toolManager.getToolDefinition("param-metadata-test")
    assert(toolDef.isDefined, "Tool 'param-metadata-test' should be registered")

    // Parse the schema JSON
    val schemaStr = toolDef.get.inputSchema match {
      case Right(s) => s
      case Left(js) => js.toString
    }
    val schemaJson = io.circe.parser.parse(schemaStr).getOrElse(io.circe.Json.Null)

    // Check username has description and examples
    val usernameDesc = schemaJson.hcursor.downField("properties").downField("username").downField("description").as[String]
    assert(usernameDesc == Right("The username for login"), s"Expected username description, got: $usernameDesc")

    val usernameExamples = schemaJson.hcursor.downField("properties").downField("username").downField("examples").as[List[String]]
    assert(usernameExamples == Right(List("john_doe")), s"Expected username examples, got: $usernameExamples")

    // Check age has description and examples
    val ageDesc = schemaJson.hcursor.downField("properties").downField("age").downField("description").as[String]
    assert(ageDesc == Right("User's age in years"), s"Expected age description, got: $ageDesc")

    val ageExamples = schemaJson.hcursor.downField("properties").downField("age").downField("examples").as[List[String]]
    assert(ageExamples == Right(List("25")), s"Expected age examples, got: $ageExamples")

    // Check required array - username should be required, age should not be
    val required = schemaJson.hcursor.downField("required").as[List[String]].getOrElse(Nil)
    assert(required.contains("username"), s"Required should contain 'username', got: $required")
    assert(!required.contains("age"), s"Required should not contain 'age' (marked as required=false), got: $required")
  }

  // Test @Param annotation with custom schema override
  test("@Param annotation with schema override replaces property definition") {
    val schemaTestServer = new FastMcpServer("SchemaTestServer", "0.1.0")
    schemaTestServer.scanAnnotations[CustomSchemaTestTools.type]

    val toolDef = schemaTestServer.toolManager.getToolDefinition("custom-schema-test")
    assert(toolDef.isDefined, "Tool 'custom-schema-test' should be registered")

    val schemaStr = toolDef.get.inputSchema match {
      case Right(s) => s
      case Left(js) => js.toString
    }
    val schemaJson = io.circe.parser.parse(schemaStr).getOrElse(io.circe.Json.Null)

    // Check that status uses the custom enum schema
    val statusEnum = schemaJson.hcursor.downField("properties").downField("status").downField("enum").as[List[String]]
    assert(statusEnum == Right(List("pending", "active", "completed", "cancelled")),
      s"Expected custom enum schema, got: $statusEnum")

    // Check that the custom description is preserved
    val statusDesc = schemaJson.hcursor.downField("properties").downField("status").downField("description").as[String]
    assert(statusDesc == Right("Current status of the task"), s"Expected custom description, got: $statusDesc")
  }
}

/** Companion object for ToolProcessorTest containing the tools to be processed by annotations
  */
object ToolProcessorTest {
  // Create a test server for tool registration
  val server = new FastMcpServer("TestServer", "0.1.0")

  // Schema for the enum
  given Schema[Operation] = Schema.derivedEnumeration.defaultStringBased

  /** Simple calculator tool for testing
    */
  @Tool(
    name = Some("calculator"),
    description = Some("Performs basic arithmetic operations")
  )
  def calculate(
      @ToolParam("First number") a: Double,
      @ToolParam("Second number") b: Double,
      @ToolParam("Operation to perform", required = false) op: String = "ADD"
  ): CalculationResult = {
    // Parse string to enum
    val operation = op.toUpperCase match {
      case "ADD" => Operation.ADD
      case "SUBTRACT" => Operation.SUBTRACT
      case "MULTIPLY" => Operation.MULTIPLY
      case "DIVIDE" => Operation.DIVIDE
      case _ => throw new IllegalArgumentException(s"Unknown operation: $op")
    }

    val result = operation match {
      case Operation.ADD => a + b
      case Operation.SUBTRACT => a - b
      case Operation.MULTIPLY => a * b
      case Operation.DIVIDE =>
        if (b == 0) throw new IllegalArgumentException("Cannot divide by zero")
        else a / b
    }

    CalculationResult(operation, List(a, b), result)
  }

  /** Simple text transformation tool for testing
    */
  @Tool()
  def uppercase(@ToolParam("Text to transform") text: String): String = text.toUpperCase

  // Sample enum for testing
  enum Operation:
    case ADD, SUBTRACT, MULTIPLY, DIVIDE

  // Sample case class for testing
  case class CalculationResult(operation: String, numbers: List[Double], result: Double)

  object CalculationResult {
    given JsonEncoder[CalculationResult] = DeriveJsonEncoder.gen[CalculationResult]

    // Factory method to simplify testing
    def apply(op: Operation, numbers: List[Double], result: Double): CalculationResult =
      new CalculationResult(op.toString, numbers, result)
  }
}

/** Test object for @Param annotation with all fields */
object ParamMetadataTestTools {
  @Tool(name = Some("param-metadata-test"))
  def testTool(
      @Param(
        description = "The username for login",
        examples = List("john_doe"),
        required = true
      )
      username: String,
      @Param(
        description = "User's age in years",
        examples = List("25"),
        required = false
      )
      age: Option[Int]
  ): String = s"Hello $username, you are ${age.getOrElse("unknown")} years old"
}

/** Test object for @Param annotation with custom schema override */
object CustomSchemaTestTools {
  @Tool(name = Some("custom-schema-test"))
  def processTask(
      @Param(description = "Task name")
      name: String,
      @Param(
        description = "Current status of the task",
        schema = Some("""{"type": "string", "enum": ["pending", "active", "completed", "cancelled"], "description": "Current status of the task"}""")
      )
      status: String
  ): String = s"Task '$name' has status: $status"
}
