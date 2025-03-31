package fastmcp.server.manager

import fastmcp.core.*
import fastmcp.server.McpContext
import zio.*

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * Function type for tool handlers
 * Takes arbitrary input and returns any result wrapped in ZIO
 */
type ToolHandler = Map[String, Any] => ZIO[Any, Throwable, Any]

/**
 * Manager for MCP tools
 * 
 * Responsible for registering, storing, and executing tools
 */
class ToolManager extends Manager[ToolDefinition]:
  // Thread-safe storage for registered tools
  private val tools = new ConcurrentHashMap[String, (ToolDefinition, ToolHandler)]()
  
  /**
   * Register a tool with the manager
   * 
   * @param name Tool name
   * @param handler Function to execute when the tool is called
   * @param definition Tool definition including description and schema
   * @return ZIO effect that completes with Unit on success or fails with ToolRegistrationError
   */
  def addTool(name: String, handler: ToolHandler, definition: ToolDefinition): ZIO[Any, Throwable, Unit] =
    ZIO.attempt {
      tools.put(name, (definition, handler))
      ()
    }.mapError(e => new ToolRegistrationError(s"Failed to register tool '$name'", e))
  
  /**
   * Get a tool handler by name
   * 
   * @param name Tool name
   * @return Option containing the handler if found
   */
  def getToolHandler(name: String): Option[ToolHandler] = 
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
   * List all registered tool definitions
   */
  override def listDefinitions(): List[ToolDefinition] = 
    tools.values().asScala.map(_._1).toList
  
  /**
   * Call a tool by name with the provided arguments
   * 
   * @param name Tool name
   * @param arguments Arguments to pass to the tool
   * @param context Optional context for the tool execution
   * @return ZIO effect that completes with the tool's result or fails with ToolError
   */
  def callTool(name: String, arguments: Map[String, Any], context: Option[McpContext]): ZIO[Any, Throwable, Any] =
    getToolHandler(name) match
      case Some(handler) => 
        handler(arguments)
          .mapError(e => new ToolExecutionError(s"Error executing tool '$name'", e))
      case None => 
        ZIO.fail(new ToolNotFoundError(s"Tool '$name' not found"))

/**
 * Custom exceptions for tool operations
 */
class ToolError(message: String, cause: Throwable = null) extends RuntimeException(message, cause)
class ToolNotFoundError(message: String) extends ToolError(message)
class ToolRegistrationError(message: String, cause: Throwable = null) extends ToolError(message, cause)
class ToolExecutionError(message: String, cause: Throwable = null) extends ToolError(message, cause)
class ToolArgumentError(message: String) extends ToolError(message)