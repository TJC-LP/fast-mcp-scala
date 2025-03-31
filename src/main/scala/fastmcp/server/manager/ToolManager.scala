package fastmcp.server.manager

import fastmcp.core.*
import fastmcp.server.McpContext
import zio.*
import zio.json.*
import io.circe.{Json, Encoder}
import io.circe.syntax.*

import java.lang.System as JSystem
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/**
 * Extension methods for JSON conversion
 */
// Extension to convert Map[String, Any] to JSON string
extension (map: Map[String, Any]) 
  def toJson: String = {
    // Convert Map[String, Any] to a format that can be serialized
    val jsonMap = map.map { case (k, v) => 
      k -> (v match {
        case null => Json.Null
        case b: Boolean => Json.fromBoolean(b)
        case n: Int => Json.fromInt(n)
        case n: Long => Json.fromLong(n)
        case n: Double => Json.fromDoubleOrString(n)
        case s: String => Json.fromString(s)
        case a: Seq[?] => Json.fromValues(a.map(anyToJson))
        case m: Map[?, ?] => 
          val stringMap = m.asInstanceOf[Map[String, Any]]
          Json.fromFields(stringMap.map { case (sk, sv) => sk -> anyToJson(sv) })
        case other => Json.fromString(other.toString)
      })
    }
    
    Json.fromFields(jsonMap).noSpaces
  }

// Helper function to convert Any to Json
private def anyToJson(value: Any): Json = {
  value match {
    case null => Json.Null
    case b: Boolean => Json.fromBoolean(b)
    case n: Int => Json.fromInt(n)
    case n: Long => Json.fromLong(n)
    case n: Double => Json.fromDoubleOrString(n)
    case s: String => Json.fromString(s)
    case a: Seq[?] => Json.fromValues(a.map(anyToJson))
    case m: Map[?, ?] => 
      val stringMap = m.asInstanceOf[Map[String, Any]]
      Json.fromFields(stringMap.map { case (k, v) => k -> anyToJson(v) })
    case other => Json.fromString(other.toString)
  }
}

/**
 * Tool handler function types
 */
// Basic handler type for backward compatibility
type ToolHandler = Map[String, Any] => ZIO[Any, Throwable, Any]

// Context-aware handler type
type ContextualToolHandler = (Map[String, Any], Option[McpContext]) => ZIO[Any, Throwable, Any]

/**
 * Type-safe tool handler trait
 */
trait TypedToolHandler[Input, Output]:
  def handle(input: Input, context: Option[McpContext]): ZIO[Any, Throwable, Output]
  
  /**
   * Optional method to convert from Map[String, Any] to Input.
   * If not overridden, the default implementation will try basic conversion methods.
   */
  def convertInput(args: Map[String, Any]): ZIO[Any, Throwable, Input] = 
    ZIO.attemptBlocking {
      JSystem.err.println(s"[TypedToolHandler] Default convertInput called with args: $args")
      // Simple approach: try casting first
      try {
        args.asInstanceOf[Input]
      } catch {
        case e: ClassCastException =>
          // If you get this error, consider overriding convertInput in your TypedToolHandler implementation
          throw new ToolArgumentError(s"Cannot convert Map to Input type: ${e.getMessage}. Consider overriding convertInput method.")
      }
    }

/**
 * Options for tool registration
 */
case class ToolRegistrationOptions(
                                    validateInputSchema: Boolean = true,
                                    validateOutputSchema: Boolean = false,
                                    allowOverrides: Boolean = false,
                                    warnOnDuplicates: Boolean = true
                                  )

/**
 * Manager for MCP tools
 *
 * Responsible for registering, storing, and executing tools
 */
class ToolManager extends Manager[ToolDefinition]:
  // Thread-safe storage for registered tools - public for direct access in examples
  val tools = new ConcurrentHashMap[String, (ToolDefinition, ContextualToolHandler)]()

  /**
   * Register a tool with the manager
   *
   * @param name       Tool name
   * @param handler    Function to execute when the tool is called
   * @param definition Tool definition including description and schema
   * @return ZIO effect that completes with Unit on success or fails with ToolRegistrationError
   */
  def addTool(
               name: String,
               handler: ToolHandler,
               definition: ToolDefinition,
               options: ToolRegistrationOptions = ToolRegistrationOptions()
             ): ZIO[Any, Throwable, Unit] =
    ZIO.attempt {
      // Check if tool already exists
      if tools.containsKey(name) && !options.allowOverrides then
        if options.warnOnDuplicates then
          JSystem.err.println(s"[ToolManager] Warning: Tool '$name' already exists and will be overwritten")
        else
          throw new ToolRegistrationError(s"Tool '$name' already exists")

      // Convert basic handler to contextual handler
      val contextualHandler: ContextualToolHandler = (args, ctx) => handler(args)

      tools.put(name, (definition, contextualHandler))
      ()
    }.mapError(e => new ToolRegistrationError(s"Failed to register tool '$name'", e))

  /**
   * Register a contextual tool handler
   */
  def addContextualTool(
                         name: String,
                         handler: ContextualToolHandler,
                         definition: ToolDefinition,
                         options: ToolRegistrationOptions = ToolRegistrationOptions()
                       ): ZIO[Any, Throwable, Unit] =
    ZIO.attempt {
      // Check if tool already exists
      if tools.containsKey(name) && !options.allowOverrides then
        if options.warnOnDuplicates then
          JSystem.err.println(s"[ToolManager] Warning: Tool '$name' already exists and will be overwritten")
        else
          throw new ToolRegistrationError(s"Tool '$name' already exists")

      tools.put(name, (definition, handler))
      ()
    }.mapError(e => new ToolRegistrationError(s"Failed to register tool '$name'", e))

  /**
   * Register a typed tool handler with JSON schema generation
   */
  def addTypedTool[Input: {JsonEncoder, JsonDecoder}, Output: {JsonEncoder, JsonDecoder}](
                                                                                           name: String,
                                                                                           typedHandler: TypedToolHandler[Input, Output],
                                                                                           definition: ToolDefinition,
                                                                                           options: ToolRegistrationOptions = ToolRegistrationOptions()
                                                                                         ): ZIO[Any, Throwable, Unit] =
    ZIO.attempt {
      // Check if tool already exists
      if tools.containsKey(name) && !options.allowOverrides then
        if options.warnOnDuplicates then
          JSystem.err.println(s"[ToolManager] Warning: Tool '$name' already exists and will be overwritten")
        else
          throw new ToolRegistrationError(s"Tool '$name' already exists")

      // Create a contextual handler that:
      // 1. Attempts to cast args to Input type (simple approach for now)
      // 2. Calls the typed handler
      // 3. Returns the output (will be serialized by FastMCPScala)
      val contextualHandler: ContextualToolHandler = (args, ctx) =>
        // Use the TypedToolHandler's convertInput method to transform the Map to Input
        for {
          // Convert Map to Input type using handler's conversion method
          _ <- ZIO.succeed(JSystem.err.println(s"[ToolManager] Converting arguments for tool '$name': $args"))
          input <- typedHandler.convertInput(args)
            .tapError(e => ZIO.succeed(JSystem.err.println(s"[ToolManager] Error converting input: ${e.getMessage}")))
            .mapError(e => new ToolArgumentError(s"Cannot convert arguments to expected type: ${e.getMessage}"))
            
          // Call the typed handler with the converted input
          result <- typedHandler.handle(input, ctx)
        } yield result

      tools.put(name, (definition, contextualHandler))
      ()
    }.mapError(e => new ToolRegistrationError(s"Failed to register typed tool '$name'", e))

  /**
   * List all registered tool definitions
   */
  override def listDefinitions(): List[ToolDefinition] =
    tools.values().asScala.map(_._1).toList

  /**
   * Call a tool by name with the provided arguments
   *
   * @param name      Tool name
   * @param arguments Arguments to pass to the tool
   * @param context   Optional context for the tool execution
   * @return ZIO effect that completes with the tool's result or fails with ToolError
   */
  def callTool(name: String, arguments: Map[String, Any], context: Option[McpContext]): ZIO[Any, Throwable, Any] =
    for
      // Get the handler and definition
      handlerOpt <- ZIO.succeed(getToolHandler(name))

      // Validate that the tool exists
      handler <- ZIO.fromOption(handlerOpt)
        .orElseFail(new ToolNotFoundError(s"Tool '$name' not found"))

      // Get the tool definition to validate arguments against schema (future enhancement)
      definition <- ZIO.fromOption(getToolDefinition(name))
        .orElseFail(new ToolExecutionError(s"Tool definition for '$name' not found but handler exists"))

      // TODO: Validate arguments against the schema
      // For future implementation

      // Call the handler
      result <- handler(arguments, context)
        .mapError(e => new ToolExecutionError(s"Error executing tool '$name'", e))

    yield result

  /**
   * Get a tool handler by name
   *
   * @param name Tool name
   * @return Option containing the handler if found
   */
  def getToolHandler(name: String): Option[ContextualToolHandler] =
    Option(tools.get(name)).map(_._2)

  /**
   * Get a tool definition by name
   *
   * @param name Tool name
   * @return Option containing the definition if found
   */
  def getToolDefinition(name: String): Option[ToolDefinition] =
    Option(tools.get(name)).map(_._1)

/**
 * Custom exceptions for tool operations
 */
class ToolError(message: String, cause: Throwable = null) extends RuntimeException(message, cause)

class ToolNotFoundError(message: String) extends ToolError(message)

class ToolRegistrationError(message: String, cause: Throwable = null) extends ToolError(message, cause)

class ToolExecutionError(message: String, cause: Throwable = null) extends ToolError(message, cause)

class ToolArgumentError(message: String) extends ToolError(message)