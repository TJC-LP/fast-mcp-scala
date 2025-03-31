package fastmcp.examples

import fastmcp.core.*
import fastmcp.server.*
import fastmcp.server.manager.*
import fastmcp.server.manager.{ToolManager, ToolRegistrationOptions} // Needed for direct access
import zio.*
import zio.json.*

import java.lang.System as JSystem
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/**
 * Example demonstrating the use of direct schema strings for MCP tools.
 * This approach avoids compile-time schema generation issues by providing
 * manually created JSON Schema strings directly.
 */
object DirectSchemaExample extends ZIOAppDefault:
  // JSON serialization/deserialization for our case classes
  given JsonEncoder[SearchItem] = DeriveJsonEncoder.gen[SearchItem]
  given JsonDecoder[SearchItem] = DeriveJsonDecoder.gen[SearchItem]
  given JsonEncoder[SearchParams] = DeriveJsonEncoder.gen[SearchParams]
  given JsonDecoder[SearchParams] = DeriveJsonDecoder.gen[SearchParams]
  given JsonEncoder[SearchResult] = DeriveJsonEncoder.gen[SearchResult]
  given JsonDecoder[SearchResult] = DeriveJsonDecoder.gen[SearchResult]
  
  given JsonEncoder[CalculatorParams] = DeriveJsonEncoder.gen[CalculatorParams]
  given JsonDecoder[CalculatorParams] = DeriveJsonDecoder.gen[CalculatorParams]
  given JsonEncoder[CalculatorResult] = DeriveJsonEncoder.gen[CalculatorResult]
  given JsonDecoder[CalculatorResult] = DeriveJsonDecoder.gen[CalculatorResult]

  /**
   * Main entry point
   */
  override def run =
    val server = FastMCPScala("DirectSchemaExample", "1.0.0")

    for
      // Register the search tool with a manually created JSON Schema string
      _ <- server.caseClassToolWithDirectSchema[SearchParams, SearchResult](
        name = "search",
        handler = handleSearch,
        schemaString = SearchParamsSchema,
        description = Some("Search for items matching certain criteria")
      )
      
      // Register calculator tool with our specialized TypedToolHandler
      _ <- server.typedTool[CalculatorParams, CalculatorResult](
        name = "calculator", 
        handler = CalculatorTool,
        description = Some("Perform basic arithmetic operations"),
        inputSchema = null, // Use null here since we'll set the string schema
        options = ToolRegistrationOptions()
      )
      
      // Override the definition with our string-based schema
      _ <- ZIO.succeed {
        val javaManager = server.toolManager.asInstanceOf[ToolManager]
        val oldData = javaManager.tools.get("calculator")
        val newDef = ToolDefinition("calculator", Some("Perform basic arithmetic operations"), Right(CalculatorParamsSchema))
        // Replace the definition in the map, keeping the handler
        javaManager.tools.put("calculator", (newDef, oldData._2))
      }

      // Log startup information
      _ <- ZIO.attempt {
        JSystem.err.println("Direct Schema Example initialized with manually created JSON schema")
        JSystem.err.println("Available tools:")
        JSystem.err.println("- search: Search for items matching criteria")
        JSystem.err.println("- calculator: Perform arithmetic operations")
      }

      // Run the server with stdio transport
      _ <- server.runStdio()
    yield ()

  /**
   * Handle search requests
   */
  def handleSearch(params: SearchParams, context: Option[McpContext]): ZIO[Any, Throwable, SearchResult] =
    ZIO.succeed {
      // Simple mock implementation
      val results = List(
        SearchItem("Item 1", "Description 1", 10.0),
        SearchItem("Item 2", "Description 2", 20.0),
        SearchItem("Item 3", "Description 3", 30.0)
      ).filter { item =>
        (params.query.isEmpty || item.name.toLowerCase.contains(params.query.toLowerCase)) &&
        (params.minPrice.forall(min => item.price >= min)) &&
        (params.maxPrice.forall(max => item.price <= max))
      }

      SearchResult(
        items = results,
        totalCount = results.length,
        query = params.query,
        executionTimeMs = 42 // mock value
      )
    }
    
  /**
   * Custom TypedToolHandler implementation with improved argument conversion
   */
  object CalculatorTool extends TypedToolHandler[CalculatorParams, CalculatorResult]:
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
      
    // Override the default conversion method with a more robust implementation
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
   * Handle calculation requests - forward to the typed handler
   */
  def handleCalculation(params: CalculatorParams, context: Option[McpContext]): ZIO[Any, Throwable, CalculatorResult] =
    CalculatorTool.handle(params, context)

  /**
   * Search parameters
   */
  case class SearchParams(
    query: String = "",
    minPrice: Option[Double] = None,
    maxPrice: Option[Double] = None,
    sortBy: String = "name",
    limit: Int = 10
  )

  /**
   * Search result item
   */
  case class SearchItem(
    name: String,
    description: String,
    price: Double
  )

  /**
   * Search results
   */
  case class SearchResult(
    items: List[SearchItem],
    totalCount: Int,
    query: String,
    executionTimeMs: Long
  )
  
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
   * Manually created JSON Schema for SearchParams
   */
  val SearchParamsSchema: String = """{
    "type": "object",
    "properties": {
      "query": {
        "type": "string",
        "description": "Search term to filter results"
      },
      "minPrice": {
        "type": ["number", "null"],
        "description": "Minimum price filter (inclusive)"
      },
      "maxPrice": {
        "type": ["number", "null"],
        "description": "Maximum price filter (inclusive)"
      },
      "sortBy": {
        "type": "string",
        "description": "Field to sort results by",
        "enum": ["name", "price", "relevance"],
        "default": "name"
      },
      "limit": {
        "type": "integer",
        "description": "Maximum number of results to return",
        "default": 10,
        "minimum": 1,
        "maximum": 100
      }
    }
  }"""

  /**
   * Manually created JSON Schema for CalculatorParams
   */
  val CalculatorParamsSchema: String = """{
    "type": "object",
    "required": ["operand1", "operand2"],
    "properties": {
      "operand1": {
        "type": "number",
        "description": "First operand for calculation"
      },
      "operand2": {
        "type": "number",
        "description": "Second operand for calculation"
      },
      "operation": {
        "type": "string",
        "description": "Operation to perform",
        "enum": ["add", "subtract", "multiply", "divide"],
        "default": "add"
      }
    }
  }"""