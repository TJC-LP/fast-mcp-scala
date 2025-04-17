package com.tjclp.fastmcp
package server.manager

import zio.*

import java.lang.System as JSystem
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

import core.*
import server.*

/** Tool handler function types
  */
// Basic handler type for backward compatibility
type ToolHandler = Map[String, Any] => ZIO[Any, Throwable, Any]

// Context-aware handler type
type ContextualToolHandler = (Map[String, Any], Option[McpContext]) => ZIO[Any, Throwable, Any]

/** Options for tool registration
  */
case class ToolRegistrationOptions(
    validateInputSchema: Boolean = true,
    validateOutputSchema: Boolean = false,
    allowOverrides: Boolean = false,
    warnOnDuplicates: Boolean = true
)

/** Manager for MCP tools
  *
  * Responsible for registering, storing, and executing tools
  */
class ToolManager extends Manager[ToolDefinition]:
  // Thread-safe storage for registered tools - public for direct access in examples
  val tools = new ConcurrentHashMap[String, (ToolDefinition, ContextualToolHandler)]()

  /** Register a tool with the manager
    *
    * @param name
    *   Tool name
    * @param handler
    *   Function to execute when the tool is called
    * @param definition
    *   Tool definition including description and schema
    * @return
    *   ZIO effect that completes with Unit on success or fails with ToolRegistrationError
    */
  def addTool(
      name: String,
      handler: ToolHandler,
      definition: ToolDefinition,
      options: ToolRegistrationOptions = ToolRegistrationOptions()
  ): ZIO[Any, Throwable, Unit] =
    ZIO
      .attempt {
        // Check if tool already exists
        if tools.containsKey(name) && !options.allowOverrides then
          if options.warnOnDuplicates then
            JSystem.err.println(
              s"[ToolManager] Warning: Tool '$name' already exists and will be overwritten"
            )
          else throw new ToolRegistrationError(s"Tool '$name' already exists")

        // Convert basic handler to contextual handler
        val contextualHandler: ContextualToolHandler = (args, ctx) => handler(args)

        tools.put(name, (definition, contextualHandler))
        ()
      }
      .mapError(e => new ToolRegistrationError(s"Failed to register tool '$name'", e))

  /** List all registered tool definitions
    */
  override def listDefinitions(): List[ToolDefinition] =
    tools.values().asScala.map(_._1).toList

  /** Call a tool by name with the provided arguments
    *
    * @param name
    *   Tool name
    * @param arguments
    *   Arguments to pass to the tool
    * @param context
    *   Optional context for the tool execution
    * @return
    *   ZIO effect that completes with the tool's result or fails with Throwable (errors will be
    *   converted to CallToolResult with isError=true at handler level)
    */
  def callTool(
      name: String,
      arguments: Map[String, Any],
      context: Option[McpContext]
  ): ZIO[Any, Throwable, Any] =
    for
      // Get the handler and definition
      handlerOpt <- ZIO.succeed(getToolHandler(name))

      // Validate that the tool exists
      handler <- ZIO
        .fromOption(handlerOpt)
        .orElseFail(new ToolNotFoundError(s"Tool '$name' not found"))

      // Get the tool definition to validate arguments against schema (future enhancement)
      definition <- ZIO
        .fromOption(getToolDefinition(name))
        .orElseFail(
          new ToolExecutionError(s"Tool definition for '$name' not found but handler exists")
        )

      // TODO: Validate arguments against the schema
      // For future implementation

      // Call the handler
      result <- handler(arguments, context)
    yield result

  /** Get a tool handler by name
    *
    * @param name
    *   Tool name
    * @return
    *   Option containing the handler if found
    */
  def getToolHandler(name: String): Option[ContextualToolHandler] =
    Option(tools.get(name)).map(_._2)

  /** Get a tool definition by name
    *
    * @param name
    *   Tool name
    * @return
    *   Option containing the definition if found
    */
  def getToolDefinition(name: String): Option[ToolDefinition] =
    Option(tools.get(name)).map(_._1)

/** Custom exceptions for tool operations
  */
class ToolError(message: String, cause: Throwable = null) extends RuntimeException(message, cause)

class ToolNotFoundError(message: String) extends ToolError(message)

class ToolRegistrationError(message: String, cause: Throwable = null)
    extends ToolError(message, cause)

class ToolExecutionError(message: String, cause: Throwable = null) extends ToolError(message, cause)

class ToolArgumentError(message: String) extends ToolError(message)
