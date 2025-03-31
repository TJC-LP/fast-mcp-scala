package fastmcp.examples

import fastmcp.core.*
import fastmcp.macros.*
import fastmcp.server.*
import zio.*

/**
 * Example server using annotations with macro processing
 *
 * This example uses Scala 3 macros to process annotations at compile time
 */
object AnnotatedServer extends ZIOAppDefault:
  override def run =
    // Create a new FastMCPScala server
    val server = FastMCPScala("AnnotatedCalculator", "0.1.0")

    // Process annotations on CalculatorService using macros
    for
      // Process all annotations in the CalculatorService
      _ <- server.processAnnotations[CalculatorService.type]

      // Run the server
      _ <- server.runStdio()
    yield ()

end AnnotatedServer

/**
 * Service with annotated methods that will be exposed as MCP endpoints
 */
object CalculatorService:
  /**
   * A simple addition tool
   */
  @Tool(name = Some("add"), description = Some("Adds two numbers"))
  def addition(a: Double = 0.0, b: Double = 0.0): Double = a + b

  /**
   * A simple multiplication tool
   */
  @Tool(name = Some("multiply"), description = Some("Multiplies two numbers"))
  def multiplication(a: Double = 1.0, b: Double = 1.0): Double = a * b

  /**
   * A static resource that returns the calculator's help text
   */
  @Resource(
    uri = "/calculator/help",
    name = Some("Calculator Help"),
    description = Some("Help documentation for the calculator"),
    mimeType = Some("text/plain")
  )
  def getHelp(): String =
    """
      |Calculator API
      |-------------
      |
      |Tools:
      |  - add: Add two numbers
      |  - multiply: Multiply two numbers
      |
      |Resources:
      |  - /calculator/help: This help text
      |  - /calculator/version: Version information
      |
      |Prompts:
      |  - math_problem: Generate a math problem
    """.stripMargin

  /**
   * A templated resource that operates on a specific calculator operation
   */
  @Resource(
    uri = "/calculator/operations/{op}/description",
    name = Some("Operation Description"),
    description = Some("Description of a specific calculator operation"),
    mimeType = Some("text/plain")
  )
  def getOperationInfo(op: String): String =
    op match
      case "add" => "Addition: Adds two or more numbers together."
      case "subtract" => "Subtraction: Subtracts the second number from the first."
      case "multiply" => "Multiplication: Multiplies two or more numbers together."
      case "divide" => "Division: Divides the first number by the second."
      case _ => s"Unknown operation: $op"

  /**
   * A prompt that generates a math problem
   */
  @Prompt(
    name = Some("math_problem"),
    description = Some("Generate a math problem of specified difficulty")
  )
  def generateMathProblem(difficulty: String = "medium"): List[Message] =
    val problem = difficulty match
      case "easy" => "What is 7 + 3?"
      case "medium" => "If x + 5 = 12, what is the value of x?"
      case "hard" => "Solve for x: 3x² - 6x + 2 = 0"
      case _ => "What is 2 + 2?"

    List(
      Message(
        role = Role.User,
        content = TextContent(s"Generate a $difficulty math problem")
      ),
      Message(
        role = Role.Assistant,
        content = TextContent(problem)
      )
    )