package fastmcp.core

import fastmcp.server.*
import fastmcp.server.manager.*
import zio.*
import zio.json.*
import scala.reflect.ClassTag
import java.lang.{System => JSystem}

/**
 * Provides simplified registration of MCP tools with automatic schema generation.
 */
object McpToolRegistry:
  /**
   * Extension methods for FastMCPScala.
   */
  extension (server: FastMCPScala)
    /**
     * Register an McpTool with automatically derived schema.
     * 
     * @param tool The McpTool implementation to register
     * @param name Optional tool name (defaults to tool class name)
     * @param description Optional tool description
     * @param options Tool registration options
     * @return ZIO effect that completes with the server instance
     */
    def registerTool[I: JsonEncoder: JsonDecoder: ClassTag, O: JsonEncoder: JsonDecoder](
      tool: McpTool[I, O],
      name: Option[String] = None,
      description: Option[String] = None,
      options: ToolRegistrationOptions = ToolRegistrationOptions()
    ): ZIO[Any, Throwable, FastMCPScala] =
      // Generate schema string using AutoSchema
      val schemaString = AutoSchema.schemaFor[I]
      
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
        
        // Log the schema for debugging
        JSystem.err.println(s"[McpToolRegistry] Registering tool '$toolName' with schema:\n$schemaString")
        
        // Register the tool
        server.toolManager.addTypedTool(toolName, typedHandler, definition, options).as(server)
      }.flatten
      
    /**
     * Register an McpTool with custom schema.
     * This allows manual customization of the schema instead of automatic generation.
     * 
     * @param tool The McpTool implementation to register
     * @param schemaBuilder A builder function that constructs the schema
     * @param name Optional tool name (defaults to tool class name)
     * @param description Optional tool description
     * @param options Tool registration options
     * @return ZIO effect that completes with the server instance
     */
    def registerToolWithCustomSchema[I: JsonEncoder: JsonDecoder: ClassTag, O: JsonEncoder: JsonDecoder](
      tool: McpTool[I, O],
      schemaBuilder: AutoSchema.SchemaBuilder[I] => AutoSchema.SchemaBuilder[I],
      name: Option[String] = None,
      description: Option[String] = None,
      options: ToolRegistrationOptions = ToolRegistrationOptions()
    ): ZIO[Any, Throwable, FastMCPScala] =
      // Start with a new builder and apply the customizations
      val builder = schemaBuilder(AutoSchema.builder[I])
      val schemaString = builder.build()
      
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
        
        // Log the schema for debugging
        JSystem.err.println(s"[McpToolRegistry] Registering tool '$toolName' with custom schema:\n$schemaString")
        
        // Register the tool
        server.toolManager.addTypedTool(toolName, typedHandler, definition, options).as(server)
      }.flatten