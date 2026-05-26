package com.tjclp.fastmcp
package server.manager

import java.lang.System as JSystem
import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters.*

import zio.*

import core.*
import server.*

/** Context-aware tool handler.
  *
  * Parameterized on the ZIO environment `R`. A handler that declares `ZIO[Client, Throwable, ...]`
  * may be stored on a manager whose `R = Client` (or a subtype), so the user-supplied environment
  * (passed via `server.runHttp().provide(...)`) reaches the handler at execution time.
  */
type ContextualToolHandler[R] = (Map[String, Any], Option[McpContext]) => ZIO[R, Throwable, Any]

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
  * Responsible for registering, storing, and executing tools.
  *
  * @tparam R
  *   the ZIO environment all stored handlers may require; supplied by the server at `runHttp[R]()`
  *   / `runStdio[R]()` entry.
  */
class ToolManager[R] extends Manager[ToolDefinition]:
  // Thread-safe storage for registered tools - public for direct access in examples
  val tools = new ConcurrentHashMap[String, (ToolDefinition, ContextualToolHandler[R])]()

  private def checkToolConflict(
      name: String,
      options: ToolRegistrationOptions
  ): Unit = // Check if tool already exists
    // Check if tool already exists
    if tools.containsKey(name) && !options.allowOverrides then
      if options.warnOnDuplicates then
        JSystem.err.println(
          s"[ToolManager] Warning: Tool '$name' already exists and will be overwritten"
        )
      else throw new ToolRegistrationError(s"Tool '$name' already exists")

  /** Register a context-aware tool handler directly
    *
    * @param name
    *   Tool name
    * @param handler
    *   Context-aware function to execute when the tool is called
    * @param definition
    *   Tool definition including description and schema
    */
  def addTool(
      name: String,
      handler: ContextualToolHandler[R],
      definition: ToolDefinition,
      options: ToolRegistrationOptions = ToolRegistrationOptions()
  ): ZIO[Any, Throwable, Unit] =
    ZIO
      .attempt {
        checkToolConflict(name, options)
        tools.put(name, (definition, handler))
        ()
      }

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
  ): ZIO[R, Throwable, Any] =
    for
      // Get the handler and definition
      handlerOpt <- ZIO.succeed(getToolHandler(name))

      // Validate that the tool exists
      handler <- ZIO
        .fromOption(handlerOpt)
        .orElseFail(new ToolNotFoundError(s"Tool '$name' not found"))

      // Get the tool definition to validate arguments against schema (future enhancement)
      _ <- ZIO
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
  def getToolHandler(name: String): Option[ContextualToolHandler[R]] =
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
@SuppressWarnings(Array("org.wartremover.warts.Null"))
class ToolError(message: String, cause: Option[Throwable] = None)
    extends RuntimeException(message, cause.orNull)

class ToolNotFoundError(message: String) extends ToolError(message)

class ToolRegistrationError(message: String, cause: Option[Throwable] = None)
    extends ToolError(message, cause)

class ToolExecutionError(message: String, cause: Option[Throwable] = None)
    extends ToolError(message, cause)

class ToolArgumentError(message: String) extends ToolError(message)
