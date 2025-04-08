package fastmcp.examples

import fastmcp.core.*
import fastmcp.macros.MacroAnnotationProcessor.given
import fastmcp.macros.{JsonSchemaMacro, MapToFunctionMacro}
import fastmcp.server.*
import fastmcp.server.McpToolRegistration.*
import fastmcp.server.manager.*
import io.modelcontextprotocol.spec.McpSchema
import zio.*
import zio.json.*

import java.lang.System as JSystem

/**
 * Example server using annotation-based tool definitions with macro processing
 *
 * This example demonstrates how to use @Tool annotations to define MCP tools
 * with automatic schema generation through Scala 3 macros.
 */
object AnnotatedServer extends ZIOAppDefault:

  // JSON codecs for our custom types
  given JsonEncoder[CalculatorResult] = DeriveJsonEncoder.gen[CalculatorResult]

  given JsonDecoder[CalculatorResult] = DeriveJsonDecoder.gen[CalculatorResult]

  def add(
           a: Int,
           b: Int
         ): Int = a + b

  /**
   * Main entry point
   */
  override def run: ZIO[Any, Throwable, Unit] =
    for
      // Create MCP server
      server <- ZIO.succeed(FastMCPScala(
        name = "MacroAnnotatedServer",
        version = "0.1.0"
      ))
      
      // Start registering tools from CalculatorTools directly in the for-comprehension
      _ <- ZIO.succeed(JSystem.err.println("[AnnotatedServer] Directly registering tools from CalculatorTools..."))
      
      // Register CalculatorTools.add
      addSchema = Right(JsonSchemaMacro.schemaForFunctionArgs(CalculatorTools.add).spaces2)
      _ <- server.tool(
        name = "add",
        description = Some("Simple calculator that adds two numbers"),
        handler = args => ZIO.succeed(MapToFunctionMacro.callByMap(CalculatorTools.add)(args)),
        inputSchema = addSchema
      )
      
      // Register CalculatorTools.addString
      addStringSchema = Right(JsonSchemaMacro.schemaForFunctionArgs(CalculatorTools.addString).spaces2)
      _ <- server.tool(
        name = "addString",
        description = Some("Adds two numbers provided as strings"),
        handler = args => ZIO.succeed(MapToFunctionMacro.callByMap(CalculatorTools.addString)(args)),
        inputSchema = addStringSchema
      )
      
      // Register CalculatorTools.multiply
      multiplySchema = Right(JsonSchemaMacro.schemaForFunctionArgs(CalculatorTools.multiply).spaces2)
      _ <- server.tool(
        name = "multiply",
        description = Some("Multiplies two numbers together"),
        handler = args => ZIO.succeed(MapToFunctionMacro.callByMap(CalculatorTools.multiply)(args)),
        inputSchema = multiplySchema
      )
      
      // Register CalculatorTools.calculate
      calculateSchema = Right(JsonSchemaMacro.schemaForFunctionArgs(CalculatorTools.calculate).spaces2)
      _ <- server.tool(
        name = "calculator",
        description = Some("Perform a calculation with two numbers and a specified operation"),
        handler = args => ZIO.succeed(MapToFunctionMacro.callByMap(CalculatorTools.calculate)(args)),
        inputSchema = calculateSchema
      )
      
      // Register tools from StringTools
      _ <- ZIO.succeed(JSystem.err.println("[AnnotatedServer] Directly registering tools from StringTools..."))
      
      // Register StringTools.greet
      greetSchema = Right(JsonSchemaMacro.schemaForFunctionArgs(StringTools.greet).spaces2)
      _ <- server.tool(
        name = "greet",
        description = Some("Generates a friendly greeting message"),
        handler = args => ZIO.succeed(MapToFunctionMacro.callByMap(StringTools.greet)(args)),
        inputSchema = greetSchema
      )
      
      // Register StringTools.transformText
      transformSchema = Right(JsonSchemaMacro.schemaForFunctionArgs(StringTools.transformText).spaces2)
      _ <- server.tool(
        name = "transform",
        description = Some("Transforms text using various operations"),
        handler = args => ZIO.succeed(MapToFunctionMacro.callByMap(StringTools.transformText)(args)),
        inputSchema = transformSchema
      )
      
      // For demonstration purposes, also register a tool manually 
      addManualSchema = Right(JsonSchemaMacro.schemaForFunctionArgs(add).spaces2)
      _ <- server.tool(
        name = "add-manual",
        description = Some("Manual version of the add tool"),
        handler = args => ZIO.succeed(MapToFunctionMacro.callByMap(add)(args)),
        inputSchema = addManualSchema
      )

      // Run the server with stdio transport
      _ <- server.runStdio()
    yield ()

  /**
   * Output type for calculator tool
   */
  case class CalculatorResult(
                               operation: String,
                               numbers: List[Double],
                               result: Double
                             )

  /**
   * Calculator tools with various arithmetic operations
   */
  object CalculatorTools:
    /**
     * Simple addition tool
     */
    @Tool(
      name = Some("add"),
      description = Some("Simple calculator that adds two numbers")
    )
    def add(
             @Param("First number to add") a: Int,
             @Param("Second number to add") b: Int
           ): Int = a + b

    /**
     * Alternative addition tool that takes strings and converts them to ints
     */
    @Tool(
      name = Some("addString"),
      description = Some("Adds two numbers provided as strings")
    )
    def addString(
                   @Param("First number to add (as string)") a: String,
                   @Param("Second number to add (as string)") b: String
                 ): Int = a.toInt + b.toInt

    /**
     * Multiplication tool
     */
    @Tool(
      name = Some("multiply"),
      description = Some("Multiplies two numbers together")
    )
    def multiply(
                  @Param("First number to multiply") a: Int,
                  @Param("Second number to multiply") b: Int
                ): Int = a * b

    /**
     * Advanced calculator with operation selection - uses standard schema building
     */
    @Tool(
      name = Some("calculator"),
      description = Some("Perform a calculation with two numbers and a specified operation")
    )
    def calculate(
                   @Param("First number") a: Double,
                   @Param("Second number") b: Double,
                   @Param(
                     "Operation to perform (add, subtract, multiply, divide)",
                     required = false
                   ) operation: String = "add"
                 ): CalculatorResult =
      val result = operation.toLowerCase match
        case "add" | "+" => a + b
        case "subtract" | "-" => a - b
        case "multiply" | "*" => a * b
        case "divide" | "/" =>
          if (b == 0) throw new IllegalArgumentException("Cannot divide by zero")
          else a / b
        case _ => throw new IllegalArgumentException(s"Unknown operation: $operation")

      CalculatorResult(operation, List(a, b), result)

    /**
     * Advanced calculator that uses advanced schema generation with @InferSchema
     * This allows for proper handling of complex types like CalculatorResult
     */
    @Tool(
      name = Some("advancedCalculator"),
      description = Some("Perform a calculation with automatic schema generation")
    )
    // @InferSchema // Annotation removed, JsonSchemaMacro is now default
    def advancedCalculate(
                           a: Double,
                           b: Double,
                           operation: String = "add"
                         ): CalculatorResult =
      val result = operation.toLowerCase match
        case "add" | "+" => a + b
        case "subtract" | "-" => a - b
        case "multiply" | "*" => a * b
        case "divide" | "/" =>
          if (b == 0) throw new IllegalArgumentException("Cannot divide by zero")
          else a / b
        case _ => throw new IllegalArgumentException(s"Unknown operation: $operation")

      CalculatorResult(operation, List(a, b), result)

  /**
   * String manipulation tools
   */
  object StringTools:
    /**
     * Greeting generator
     */
    @Tool(
      name = Some("greet"),
      description = Some("Generates a friendly greeting message")
    )
    def greet(
               @Param("Name of the person to greet") name: String,
               @Param(
                 "Language to use for the greeting (en, es, fr, de)",
                 required = false
               ) language: String = "en"
             ): String =
      val greeting = language.toLowerCase match
        case "en" => "Hello"
        case "es" => "Hola"
        case "fr" => "Bonjour"
        case "de" => "Hallo"
        case _ => "Hello"

      s"$greeting, $name!"

    /**
     * Text transformation tool
     */
    @Tool(
      name = Some("transform"),
      description = Some("Transforms text using various operations")
    )
    def transformText(
                       @Param("Text to transform") text: String,
                       @Param(
                         "Transformation to apply (uppercase, lowercase, capitalize, reverse)",
                         required = false
                       ) transformation: String = "uppercase"
                     ): String =
      transformation.toLowerCase match
        case "uppercase" => text.toUpperCase
        case "lowercase" => text.toLowerCase
        case "capitalize" => text.split(" ").map(_.capitalize).mkString(" ")
        case "reverse" => text.reverse
        case _ => throw new IllegalArgumentException(s"Unknown transformation: $transformation")