package fastmcp.macros

import fastmcp.core.*
import fastmcp.server.FastMCPScala
import fastmcp.server.manager.ToolRegistrationOptions
import fastmcp.macros.MCPRegistrationMacro.scanAnnotations
import org.scalatest.funsuite.AnyFunSuite
import zio.*
import zio.json.*
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

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
}

/** Companion object for ToolProcessorTest containing the tools to be processed by annotations
  */
object ToolProcessorTest {
  // Create a test server for tool registration
  val server = new FastMCPScala("TestServer", "0.1.0")

  // Sample enum for testing
  enum Operation:
    case ADD, SUBTRACT, MULTIPLY, DIVIDE

  // Schema for the enum
  given Schema[Operation] = Schema.derivedEnumeration.defaultStringBased

  // Sample case class for testing
  case class CalculationResult(operation: String, numbers: List[Double], result: Double)

  object CalculationResult {
    given JsonEncoder[CalculationResult] = DeriveJsonEncoder.gen[CalculationResult]

    // Factory method to simplify testing
    def apply(op: Operation, numbers: List[Double], result: Double): CalculationResult =
      new CalculationResult(op.toString, numbers, result)
  }

  /** Simple calculator tool for testing
    */
  @Tool(
    name = Some("calculator"),
    description = Some("Performs basic arithmetic operations")
  )
  def calculate(
      @Param("First number") a: Double,
      @Param("Second number") b: Double,
      @Param("Operation to perform", required = false) op: String = "ADD"
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
  def uppercase(@Param("Text to transform") text: String): String = text.toUpperCase
}
