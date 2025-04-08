package fastmcp.examples

import fastmcp.core.*
import fastmcp.server.*
import fastmcp.server.manager.*
import fastmcp.macros.{MacroAnnotationProcessor, InferSchema}
import fastmcp.macros.MacroAnnotationProcessor.{given, *}
import fastmcp.server.McpToolRegistration.* // Import scanAnnotations extension method
import zio.*
import zio.json.*
import java.lang.{System => JSystem}

/**
 * Example server using annotation-based tool definitions with macro processing
 * 
 * This example demonstrates how to use @Tool annotations to define MCP tools
 * with automatic schema generation through Scala 3 macros.
 */
object AnnotatedServer extends ZIOAppDefault:
  
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
    @InferSchema
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
  override def run: ZIO[Any, Throwable, Unit] =
    for
      // Create MCP server
      server <- ZIO.succeed(FastMCPScala(
        name = "MacroAnnotatedServer",
        version = "0.1.0"
      ))
      
      // Process tools using our new macro processor
      _ <- ZIO.attempt {
        // This is a simplified implementation for now
        JSystem.err.println("[AnnotatedServer] NOTE: This is using a simplified macro implementation that simply demonstrates the concept.")
        JSystem.err.println("[AnnotatedServer] Future versions will provide full tool extraction from annotations.")
        
        // Use the inline scanAnnotations extension method for CalculatorTools
        JSystem.err.println("[AnnotatedServer] Scanning CalculatorTools for annotations...")
        server.scanAnnotations[CalculatorTools.type]
        
        // Use the inline scanAnnotations extension method for StringTools
        JSystem.err.println("[AnnotatedServer] Scanning StringTools for annotations...")
        server.scanAnnotations[StringTools.type]
      }
      
      // For demonstration purposes, also register a tool manually
      _ <- server.tool(
        name = "add-manual",
        description = Some("Manual version of the add tool"),
        handler = args => {
          val a = args.getOrElse("a", 0).asInstanceOf[Int]
          val b = args.getOrElse("b", 0).asInstanceOf[Int]
          ZIO.succeed(a + b)
        }
      )
      
      // Run the server with stdio transport
      _ <- server.runStdio()
    yield ()