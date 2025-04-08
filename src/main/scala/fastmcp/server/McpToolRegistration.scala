package fastmcp.server

import fastmcp.core.*
import fastmcp.macros.*
import fastmcp.server.manager.*
import zio.*
import zio.json.*

/**
 * Extension methods for FastMCPScala to simplify tool registration.
 */
object McpToolRegistration:
  /**
   * Extension method to register an McpTool with the server.
   */
  extension (server: FastMCPScala)
    /** 
     * Extension method to scan class T for @Tool annotations.
     * Provides a convenient way to process annotations on a class with tools.
     *
     * @tparam T The type to scan for annotations
     */
    inline def scanAnnotations[T]: Unit = ToolMacros.processAnnotations[T](server)
    
    /**
     * Enhanced version that uses JsonSchemaMacro and MapToFunctionMacro 
     * for automatic schema generation and handler creation.
     *
     * @tparam T The type to scan for annotations
     */
    inline def scanToolsWithMacros[T]: Unit = EnhancedToolMacros.processAnnotations[T](server)
    /**
     * Register an McpTool implementation with a provided JSON Schema string.
     * This is the most direct way to register a tool with a custom schema.
     * 
     * @param tool The McpTool implementation to register
     * @param schemaString JSON Schema string for the tool's input
     * @param name Optional tool name (defaults to tool class name)
     * @param description Optional tool description
     * @param options Tool registration options
     * @return ZIO effect that completes with the server instance
     */
    def registerToolWithSchema[I: {JsonEncoder, JsonDecoder}, O: {JsonEncoder, JsonDecoder}](
      tool: McpTool[I, O],
      schemaString: String,
      name: Option[String] = None,
      description: Option[String] = None,
      options: ToolRegistrationOptions = ToolRegistrationOptions()
    ): ZIO[Any, Throwable, FastMCPScala] =
      // Determine the tool name
      val toolName = name.getOrElse(tool.getClass.getSimpleName.stripSuffix("$"))
      
      ZIO.attempt {
        // Create a typed handler adapter
        val typedHandler = new TypedToolHandler[I, O] {
          override def handle(input: I, context: Option[McpContext]): ZIO[Any, Throwable, O] =
            tool.handle(input, context)
            
          override def convertInput(args: Map[String, Any]): ZIO[Any, Throwable, I] =
            tool.convertInput(args)
        }
        
        // Create the tool definition with string schema
        val definition = ToolDefinition(
          name = toolName,
          description = description,
          inputSchema = Right(schemaString)
        )
        
        // Register the tool
        server.toolManager.addTypedTool(toolName, typedHandler, definition, options).as(server)
      }.flatten
      
    /**
     * Find @Tool annotation on a class and extract metadata.
     * 
     * @param tool The tool instance to analyze
     * @return Extracted metadata or default values
     */
    private def extractToolMetadata(tool: Any): (String, Option[String]) =
      // For now, use a simple implementation - in the future, this would use reflection
      // to extract @Tool annotation data
      val name = tool.getClass.getSimpleName.stripSuffix("$")
      val description = None
      (name, description)