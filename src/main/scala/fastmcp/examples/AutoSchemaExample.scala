package fastmcp.examples

import fastmcp.core.*
import fastmcp.server.*
import fastmcp.server.manager.*
import fastmcp.core.McpToolRegistry.* // Import extension methods
import zio.*
import zio.json.*
import scala.reflect.ClassTag

import java.lang.System as JSystem

/**
 * Example demonstrating the registration of tools, potentially with custom schemas.
 * Note: Automatic schema generation via `registerTool` is now basic.
 * Use annotations (@Tool) for detailed, automatic schema generation.
 */
object AutoSchemaExample extends ZIOAppDefault:
  // JSON codec derivation for our types
  given JsonEncoder[CalculatorParams] = DeriveJsonEncoder.gen[CalculatorParams]
  given JsonDecoder[CalculatorParams] = DeriveJsonDecoder.gen[CalculatorParams]
  given JsonEncoder[CalculatorResult] = DeriveJsonEncoder.gen[CalculatorResult]
  given JsonDecoder[CalculatorResult] = DeriveJsonDecoder.gen[CalculatorResult]

  given JsonEncoder[WeatherParams] = DeriveJsonEncoder.gen[WeatherParams]
  given JsonDecoder[WeatherParams] = DeriveJsonDecoder.gen[WeatherParams]
  given JsonEncoder[WeatherResult] = DeriveJsonEncoder.gen[WeatherResult]
  given JsonDecoder[WeatherResult] = DeriveJsonDecoder.gen[WeatherResult]

  /**
   * Main entry point
   */
  override def run =
    val server = FastMCPScala("AutoSchemaExample", "1.0.0")

    // Define a custom schema string for the weather tool
    val weatherSchemaString = """{
      "type": "object",
      "properties": {
        "location": { "type": "string", "description": "Location to get weather for (city or coordinates)" },
        "units": { "type": "string", "description": "Temperature units", "enum": ["celsius", "fahrenheit"], "default": "celsius" },
        "includeForecast": { "type": "boolean", "description": "Whether to include extended forecast", "default": false }
      },
      "required": ["location"]
    }"""

    for
      // Register the calculator tool - schema will be basic. Use @Tool for better schemas.
      _ <- server.registerTool[CalculatorParams, CalculatorResult](
        tool = CalculatorTool,
        description = Some("Perform arithmetic operations with two numbers")
      )

      // Register the weather tool with a custom schema string
      _ <- server.registerToolWithCustomSchema[WeatherParams, WeatherResult](
        tool = WeatherTool,
        schemaString = weatherSchemaString, // Pass the schema string directly
        description = Some("Get weather information for a location")
      )

      // Log startup information
      _ <- ZIO.attempt {
        JSystem.err.println("Auto Schema Example initialized.")
        JSystem.err.println("NOTE: Use @Tool annotations for detailed automatic schema generation.")
        JSystem.err.println("Available tools:")
        JSystem.err.println("- calculator: Perform arithmetic operations (basic schema)")
        JSystem.err.println("- weather: Get weather information (custom schema)")
      }

      // Run the server with stdio transport
      _ <- server.runStdio()
    yield ()

  /**
   * Calculator tool implementation
   */
  object CalculatorTool extends McpTool[CalculatorParams, CalculatorResult]:
    override def handle(params: CalculatorParams, context: Option[McpContext]): ZIO[Any, Throwable, CalculatorResult] =
      ZIO.attempt {
        val result = params.operation match {
          case "add" => params.operand1 + params.operand2
          case "subtract" => params.operand1 - params.operand2
          case "multiply" => params.operand1 * params.operand2
          case "divide" => 
            if (params.operand2 == 0) throw new ArithmeticException("Division by zero")
            params.operand1 / params.operand2
          case unknown => throw new IllegalArgumentException(s"Unknown operation: $unknown")
        }

        CalculatorResult(
          params.operand1,
          params.operand2,
          params.operation,
          result
        )
      }

    // Custom implementation of the conversion from Map to CalculatorParams
    override def convertInput(args: Map[String, Any]): ZIO[Any, Throwable, CalculatorParams] =
      ZIO.attempt {
        JSystem.err.println(s"[CalculatorTool] Converting map to CalculatorParams: $args")

        // Extract and convert operands
        val op1 = args.getOrElse("operand1", 0.0) match {
          case n: Number => n.doubleValue()
          case s: String => s.toDouble
          case other => 
            JSystem.err.println(s"[CalculatorTool] Unexpected operand1 type: ${other.getClass.getName}")
            0.0
        }

        val op2 = args.getOrElse("operand2", 0.0) match {
          case n: Number => n.doubleValue()
          case s: String => s.toDouble
          case other => 
            JSystem.err.println(s"[CalculatorTool] Unexpected operand2 type: ${other.getClass.getName}")
            0.0
        }

        // Extract operation
        val op = args.getOrElse("operation", "add").toString

        JSystem.err.println(s"[CalculatorTool] Converted to: op1=$op1, op2=$op2, op=$op")
        CalculatorParams(op1, op2, op)
      }

  /**
   * Calculator parameters
   */
  case class CalculatorParams(
    operand1: Double,
    operand2: Double,
    operation: String = "add"
  )

  /**
   * Calculator result
   */
  case class CalculatorResult(
    operand1: Double,
    operand2: Double,
    operation: String,
    result: Double
  )

  /**
   * Weather tool implementation
   */
  object WeatherTool extends McpTool[WeatherParams, WeatherResult]:
    override def handle(params: WeatherParams, context: Option[McpContext]): ZIO[Any, Throwable, WeatherResult] =
      ZIO.attempt {
        // In a real implementation, this would call a weather API
        // For this example, we'll just return mock data
        val temperature = 72.5
        val conditions = "Sunny"
        val humidity = 45.0

        WeatherResult(
          location = params.location,
          temperature = temperature,
          conditions = conditions,
          humidity = humidity,
          units = params.units,
          timestamp = java.lang.System.currentTimeMillis()
        )
      }

    // Custom implementation of the conversion from Map to WeatherParams
    override def convertInput(args: Map[String, Any]): ZIO[Any, Throwable, WeatherParams] =
      ZIO.attempt {
        JSystem.err.println(s"[WeatherTool] Converting map to WeatherParams: $args")

        // Extract location
        val location = args.getOrElse("location", "").toString

        // Extract units
        val units = args.getOrElse("units", "celsius").toString

        // Extract includeForecast
        val includeForecast = args.getOrElse("includeForecast", false) match {
          case b: Boolean => b
          case s: String => s.toLowerCase == "true"
          case n: Number => n.intValue() != 0
          case _ => false
        }

        JSystem.err.println(s"[WeatherTool] Converted to: location=$location, units=$units, includeForecast=$includeForecast")
        WeatherParams(location, units, includeForecast)
      }

  /**
   * Weather parameters
   */
  case class WeatherParams(
    location: String,
    units: String = "celsius",
    includeForecast: Boolean = false
  )

  /**
   * Weather result
   */
  case class WeatherResult(
    location: String,
    temperature: Double,
    conditions: String,
    humidity: Double,
    units: String,
    timestamp: Long
  )
end AutoSchemaExample // Ensure object definition is closed