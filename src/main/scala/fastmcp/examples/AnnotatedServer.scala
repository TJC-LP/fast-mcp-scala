package fastmcp.examples

import fastmcp.core.*
import fastmcp.server.*
import fastmcp.server.manager.*
import fastmcp.macros.*
import zio.*
import zio.json.*
import java.lang.{System => JSystem}

/**
 * Example server using annotation-based tool definitions
 * 
 * This example demonstrates how to use annotations to define MCP tools, resources,
 * and prompts with automatic schema generation.
 */
object AnnotatedServer extends ZIOAppDefault:
  /**
   * Tool with primitive parameters
   * 
   * This tool adds two numbers together.
   */
  @Tool(
    name = Some("addNumbers"),
    description = Some("Simple calculator that adds two numbers"),
    examples = List("addNumbers(1, 2) = 3", "addNumbers(-5, 10) = 5"),
    version = Some("1.0")
  )
  def add(
    @Param(description = "First number to add") a: Int,
    @Param(description = "Second number to add") b: Int
  ): Int = a + b
  
  /**
   * Tool with complex parameter types
   * 
   * This tool performs calculations on a list of numbers.
   */
  @Tool(
    name = Some("calculator"),
    description = Some("Perform a calculation on a list of numbers")
  )
  def calculator(
    @Param(description = "The operation to perform (add, multiply, min, max, avg)") 
    operation: String,
    
    @Param(description = "List of numbers to operate on") 
    numbers: List[Double]
  ): CalculatorResult =
    val result = operation match
      case "add" => numbers.sum
      case "multiply" => numbers.product
      case "min" => numbers.min
      case "max" => numbers.max
      case "avg" => if numbers.isEmpty then 0 else numbers.sum / numbers.size
      case _ => throw new IllegalArgumentException(s"Unknown operation: $operation")
    
    CalculatorResult(operation, numbers, result)
  
  /**
   * Resource example - return current system status
   */
  @Resource(
    uri = "status://system",
    name = Some("System Status"),
    description = Some("Current system status information"),
    mimeType = Some("text/plain")
  )
  def getSystemStatus(): String =
    val runtime = java.lang.Runtime.getRuntime
    val freeMemory = runtime.freeMemory() / (1024 * 1024)
    val totalMemory = runtime.totalMemory() / (1024 * 1024)
    val maxMemory = runtime.maxMemory() / (1024 * 1024)
    
    s"""System Status:
       |Runtime: Java ${java.lang.System.getProperty("java.version")}
       |Available processors: ${runtime.availableProcessors}
       |Free memory: $freeMemory MB
       |Total memory: $totalMemory MB
       |Max memory: $maxMemory MB
       |""".stripMargin
  
  /**
   * Prompt example - generates a greeting prompt
   */
  @Prompt(
    name = Some("greeting"),
    description = Some("Generate a friendly greeting")
  )
  def generateGreeting(
    @PromptParam(description = "Name of the person to greet") 
    name: String,
    
    @PromptParam(description = "Optional style (formal, casual, or friendly)", required = false)
    style: Option[String] = None
  ): List[Message] =
    val greeting = style match
      case Some("formal") => s"Good day, $name. How may I be of assistance?"
      case Some("casual") => s"Hey $name! What's up?"
      case Some("friendly") => s"Hi there, $name! How are you doing today?"
      case _ => s"Hello, $name! How can I help you today?"
    
    List(
      Message(Role.User, TextContent(greeting))
    )
  
  /**
   * Output type for calculator tool
   */
  case class CalculatorResult(
    operation: String,
    numbers: List[Double],
    result: Double
  )
  
  // JSON codecs for our custom types
  given JsonEncoder[CalculatorResult] = DeriveJsonEncoder.gen[CalculatorResult]
  given JsonDecoder[CalculatorResult] = DeriveJsonDecoder.gen[CalculatorResult]
  
  /**
   * Main entry point
   */
  override def run =
    val server = FastMCPScala("AnnotatedExampleServer", "1.0.0")
    
    for
      // Process all tools, resources, and prompts in this object
      _ <- server.processAnnotations[AnnotatedServer.type]
      
      // Run the server with stdio transport
      _ <- server.runStdio()
    yield ()