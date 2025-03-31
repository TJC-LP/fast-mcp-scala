package fastmcp.examples

import fastmcp.core.*
import fastmcp.server.*
import fastmcp.server.manager.*
import zio.*
import zio.json.*
import io.modelcontextprotocol.server.McpServerFeatures

/**
 * A demonstration of enhanced tool capabilities in FastMCP-Scala
 */
object TypedToolExample extends ZIOAppDefault:
  /**
   * Input parameters for the calculator tool with JSON codec
   */
  case class CalculatorInput(
    operation: String = "add",
    numbers: List[Double] = List(0.0, 0.0)
  )
  
  /**
   * Output type for the calculator tool with JSON codec
   */
  case class CalculatorOutput(
    result: Double,
    operation: String,
    inputs: List[Double]
  )
  
  // Define JSON encoders and decoders for input and output types
  given JsonEncoder[CalculatorInput] = DeriveJsonEncoder.gen[CalculatorInput]
  given JsonDecoder[CalculatorInput] = DeriveJsonDecoder.gen[CalculatorInput]
  given JsonEncoder[CalculatorOutput] = DeriveJsonEncoder.gen[CalculatorOutput]
  given JsonDecoder[CalculatorOutput] = DeriveJsonDecoder.gen[CalculatorOutput]
  
  // Add ZIO Schema instances for schema generation
  // These will only be used if we register with caseClassToolWithSchema
  import zio.schema.*
  given Schema[CalculatorInput] = DeriveSchema.gen[CalculatorInput]
  given Schema[CalculatorOutput] = DeriveSchema.gen[CalculatorOutput]
  
  // Implementation of a typed tool handler for the calculator
  object CalculatorTool extends TypedToolHandler[CalculatorInput, CalculatorOutput]:
    override def handle(input: CalculatorInput, context: Option[McpContext]): ZIO[Any, Throwable, CalculatorOutput] =
      // Log that we received input (to stderr)
      ZIO.attempt {
        java.lang.System.err.println(s"[Calculator] Processing operation: ${input.operation} with numbers: ${input.numbers}")
      } *>
      // Calculate result based on operation
      ZIO.attempt {
        val result = input.operation match
          case "add" => input.numbers.sum
          case "multiply" => input.numbers.product
          case "subtract" => input.numbers.reduceOption(_ - _).getOrElse(0.0)
          case "divide" => input.numbers.reduceOption(_ / _).getOrElse(0.0)
          case unknownOp => throw new IllegalArgumentException(s"Unknown operation: $unknownOp")
        
        CalculatorOutput(result, input.operation, input.numbers)
      }
  
  /**
   * Main run method
   */
  override def run =
    // Create a new FastMCPScala server
    val server = FastMCPScala("TypedToolExample", "0.1.0")
    
    for
      // Register the typed calculator tool
      _ <- server.typedTool(
        name = "calculator",
        handler = CalculatorTool,
        description = Some("A strongly-typed calculator tool with schema validation")
      )
      
      // Register a case class backed tool with auto-generated schema
      _ <- server.caseClassTool[CalculatorInput, CalculatorOutput](
        name = "calculator2",
        handler = (input, ctx) => CalculatorTool.handle(input, ctx),
        description = Some("Another calculator with basic schema")
      )
      
      // Register a case class backed tool with ZIO Schema-generated schema
      _ <- server.caseClassToolWithSchema[CalculatorInput, CalculatorOutput](
        name = "calculator-schema",
        handler = (input, ctx) => CalculatorTool.handle(input, ctx),
        description = Some("Calculator with advanced ZIO Schema-generated schema")
      )
      
      // Register a traditional non-typed tool for comparison
      _ <- server.tool(
        name = "calculator-legacy",
        description = Some("Traditional calculator using Map[String, Any]"),
        handler = args => {
          val operation = args.getOrElse("operation", "add").toString
          val numbers = args.getOrElse("numbers", List(0.0, 0.0)).asInstanceOf[List[Double]]
          
          val result = operation match {
            case "add" => numbers.sum
            case "multiply" => numbers.product
            case "subtract" => numbers.reduceOption(_ - _).getOrElse(0.0)
            case "divide" => numbers.reduceOption(_ / _).getOrElse(0.0)
            case _ => throw new IllegalArgumentException(s"Unknown operation: $operation")
          }
          
          ZIO.succeed(CalculatorOutput(result, operation, numbers))
        }
      )
      
      // Add a demo help resource
      _ <- server.resource(
        uri = "/calculator/help",
        name = Some("Calculator Help"),
        description = Some("Help documentation for the typed calculator tool"),
        handler = () => ZIO.succeed(
          """
          |# Typed Calculator Tool
          |
          |## Input
          |```json
          |{
          |  "operation": "add|subtract|multiply|divide",
          |  "numbers": [1.0, 2.0, 3.0, ...]
          |}
          |```
          |
          |## Output
          |```json
          |{
          |  "result": 6.0,
          |  "operation": "add",
          |  "inputs": [1.0, 2.0, 3.0]
          |}
          |```
          """.stripMargin
        )
      )
      
      // Run the server with stdio transport
      _ <- server.runStdio()
    yield ()