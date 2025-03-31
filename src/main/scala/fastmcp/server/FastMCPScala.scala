package fastmcp.server

import fastmcp.core.*
import fastmcp.server.manager.*
import io.modelcontextprotocol.server.{McpServer, McpServerFeatures, McpSyncServer, McpSyncServerExchange}
import io.modelcontextprotocol.spec.{McpSchema, McpServerTransportProvider}
import zio.*
import zio.json.*
// Explicitly import Java's System to avoid conflicts with zio.System
import java.lang.{System => JSystem}
// Tapir imports for schema generation
import sttp.tapir.{Schema => TapirSchema}
import sttp.tapir.SchemaType
import sttp.tapir.generic.auto._

import scala.jdk.CollectionConverters.*

/**
 * Settings for the FastMCPScala server
 */
case class FastMCPScalaSettings(
                                 debug: Boolean = false,
                                 logLevel: String = "INFO",
                                 host: String = "0.0.0.0",
                                 port: Int = 8000,
                                 warnOnDuplicateResources: Boolean = true,
                                 warnOnDuplicateTools: Boolean = true,
                                 warnOnDuplicatePrompts: Boolean = true,
                                 dependencies: List[String] = List.empty
                               )

/**
 * Main server class for FastMCP-Scala
 *
 * This class provides a high-level API for creating MCP servers using Scala and ZIO
 */
class FastMCPScala(
                    val name: String = "FastMCPScala",
                    version: String = "0.1.0",
                    settings: FastMCPScalaSettings = FastMCPScalaSettings()
                  ):
  val dependencies: List[String] = settings.dependencies
  // Initialize managers
  val toolManager = new ToolManager() // Changed to public for direct access
  private val resourceManager = new ResourceManager()
  private val promptManager = new PromptManager()
  // Placeholder for the Java MCP Server instance
  private var underlyingJavaServer: Option[McpSyncServer] = None

  /**
   * Register a tool with the server
   *
   * @param name        Tool name
   * @param handler     Function to execute when the tool is called
   * @param description Optional tool description
   * @param inputSchema JSON schema for the tool's input parameters
   * @return ZIO effect that completes with this FastMCPScala instance or fails with ToolRegistrationError
   */
  def tool(
            name: String,
            handler: ToolHandler,
            description: Option[String] = None,
            inputSchema: McpSchema.JsonSchema = new McpSchema.JsonSchema("object", null, null, true),
            options: ToolRegistrationOptions = ToolRegistrationOptions()
          ): ZIO[Any, Throwable, FastMCPScala] =
    val definition = ToolDefinition(name, description, Left(inputSchema))
    toolManager.addTool(name, handler, definition, options).as(this)
    
  /**
   * Register a context-aware tool with the server
   * 
   * @param name        Tool name
   * @param handler     Function to execute when the tool is called (with context)
   * @param description Optional tool description
   * @param inputSchema JSON schema for the tool's input parameters
   * @return ZIO effect that completes with this FastMCPScala instance or fails with ToolRegistrationError
   */
  def contextualTool(
            name: String,
            handler: ContextualToolHandler,
            description: Option[String] = None,
            inputSchema: McpSchema.JsonSchema = new McpSchema.JsonSchema("object", null, null, true),
            options: ToolRegistrationOptions = ToolRegistrationOptions()
          ): ZIO[Any, Throwable, FastMCPScala] =
    val definition = ToolDefinition(name, description, Left(inputSchema))
    toolManager.addContextualTool(name, handler, definition, options).as(this)
    
  /**
   * Register a fully type-safe tool with the server
   * 
   * @param name        Tool name
   * @param handler     TypedToolHandler implementation
   * @param description Optional tool description
   * @param inputSchema JSON schema for the tool's input parameters
   * @return ZIO effect that completes with this FastMCPScala instance or fails with ToolRegistrationError
   */
  def typedTool[Input: {JsonEncoder, JsonDecoder}, Output: {JsonEncoder, JsonDecoder}](
            name: String,
            handler: TypedToolHandler[Input, Output],
            description: Option[String] = None,
            inputSchema: McpSchema.JsonSchema = new McpSchema.JsonSchema("object", null, null, true),
            options: ToolRegistrationOptions = ToolRegistrationOptions()
          ): ZIO[Any, Throwable, FastMCPScala] =
    val definition = ToolDefinition(name, description, Left(inputSchema))
    toolManager.addTypedTool(name, handler, definition, options).as(this)
    
  /**
   * Register a case class backed tool with simplified schema
   * 
   * @param name        Tool name
   * @param handler     Function that takes a case class as input and returns output
   * @param description Optional tool description
   * @return ZIO effect that completes with this FastMCPScala instance or fails with ToolRegistrationError
   */
  def caseClassTool[Input: {JsonEncoder, JsonDecoder}, Output: {JsonEncoder, JsonDecoder}](
            name: String,
            handler: (Input, Option[McpContext]) => ZIO[Any, Throwable, Output],
            description: Option[String] = None,
            options: ToolRegistrationOptions = ToolRegistrationOptions()
          ): ZIO[Any, Throwable, FastMCPScala] =
    // Create a typed handler
    val typedHandler = new TypedToolHandler[Input, Output] {
      override def handle(input: Input, context: Option[McpContext]): ZIO[Any, Throwable, Output] =
        handler(input, context)
    }
    
    // Create a basic schema for now
    val inputSchema = new McpSchema.JsonSchema("object", null, null, true)
    
    // Create the tool definition
    val definition = ToolDefinition(name, description, Left(inputSchema))
    
    // Register the tool
    toolManager.addTypedTool(name, typedHandler, definition, options).as(this)
    
  /**
   * Register a case class backed tool with schema generated from ZIO Schema
   * 
   * @param name        Tool name
   * @param handler     Function that takes a case class as input and returns output
   * @param description Optional tool description
   * @return ZIO effect that completes with this FastMCPScala instance or fails with ToolRegistrationError
   */
  def caseClassToolWithSchema[Input: {JsonEncoder, JsonDecoder, zio.schema.Schema}, Output: {JsonEncoder, JsonDecoder}](
            name: String,
            handler: (Input, Option[McpContext]) => ZIO[Any, Throwable, Output],
            description: Option[String] = None,
            options: ToolRegistrationOptions = ToolRegistrationOptions()
          ): ZIO[Any, Throwable, FastMCPScala] =
    ZIO.attempt {
      // Create a typed handler
      val typedHandler = new TypedToolHandler[Input, Output] {
        override def handle(input: Input, context: Option[McpContext]): ZIO[Any, Throwable, Output] =
          handler(input, context)
      }
      
      // Generate schema using ZIO Schema
      val inputSchema = SchemaGenerator.schemaFor[Input]
      
      // Create the tool definition
      val definition = ToolDefinition(name, description, Left(inputSchema))
      
      // Register the tool
      toolManager.addTypedTool(name, typedHandler, definition, options).as(this)
    }.flatten
    
  /**
   * Register a case class backed tool with schema generated from Tapir Schema
   * 
   * @param name        Tool name
   * @param handler     Function that takes a case class as input and returns output
   * @param description Optional tool description
   * @return ZIO effect that completes with this FastMCPScala instance or fails with ToolRegistrationError
   */
  def caseClassToolWithTapirSchema[Input: {JsonEncoder, JsonDecoder, TapirSchema}, Output: {JsonEncoder, JsonDecoder}](
            name: String,
            handler: (Input, Option[McpContext]) => ZIO[Any, Throwable, Output],
            description: Option[String] = None,
            options: ToolRegistrationOptions = ToolRegistrationOptions()
          ): ZIO[Any, Throwable, FastMCPScala] =
    ZIO.attempt {
      // Create a typed handler
      val typedHandler = new TypedToolHandler[Input, Output] {
        override def handle(input: Input, context: Option[McpContext]): ZIO[Any, Throwable, Output] =
          handler(input, context)
      }
      
      // Generate schema using Tapir Schema
      val inputSchema = SchemaGenerator.schemaForTapir[Input]
      
      // Create the tool definition with schema object
      val definition = ToolDefinition(name, description, Left(inputSchema))
      
      // Register the tool
      toolManager.addTypedTool(name, typedHandler, definition, options).as(this)
    }.flatten

  /**
   * Register a case class backed tool with a directly provided JSON Schema string
   * This avoids potential compile-time issues with schema generation
   * 
   * @param name        Tool name
   * @param handler     Function that takes a case class as input and returns output
   * @param schemaString JSON Schema string to use directly
   * @param description Optional tool description
   * @return ZIO effect that completes with this FastMCPScala instance or fails with ToolRegistrationError
   */
  def caseClassToolWithDirectSchema[Input: {JsonEncoder, JsonDecoder}, Output: {JsonEncoder, JsonDecoder}](
            name: String,
            handler: (Input, Option[McpContext]) => ZIO[Any, Throwable, Output],
            schemaString: String,
            description: Option[String] = None,
            options: ToolRegistrationOptions = ToolRegistrationOptions()
          ): ZIO[Any, Throwable, FastMCPScala] =
    ZIO.attempt {
      // Create a typed handler
      val typedHandler = new TypedToolHandler[Input, Output] {
        override def handle(input: Input, context: Option[McpContext]): ZIO[Any, Throwable, Output] =
          handler(input, context)
      }
      
      JSystem.err.println(s"[FastMCPScala] Using provided JSON Schema for $name: $schemaString")
      
      // Create the tool definition with the provided string schema
      val definition = ToolDefinition(name, description, Right(schemaString))
      
      // Register the tool
      toolManager.addTypedTool(name, typedHandler, definition, options).as(this)
    }.flatten

  /**
   * Register a static resource with the server
   *
   * @param uri         Resource URI
   * @param handler     Function to execute when the resource is accessed
   * @param name        Optional resource name
   * @param description Optional resource description
   * @param mimeType    Optional MIME type (defaults to "text/plain")
   * @return ZIO effect that completes with this FastMCPScala instance or fails with ResourceRegistrationError
   */
  def resource(
                uri: String,
                handler: ResourceHandler,
                name: Option[String] = None,
                description: Option[String] = None,
                mimeType: Option[String] = Some("text/plain")
              ): ZIO[Any, Throwable, FastMCPScala] =
    val definition = ResourceDefinition(uri, name, description, mimeType)
    resourceManager.addResource(uri, handler, definition).as(this)

  /**
   * Register a templated resource with the server
   *
   * @param uriPattern  Resource URI pattern with placeholders like {param}
   * @param handler     Function to execute when the resource is accessed
   * @param name        Optional resource name
   * @param description Optional resource description
   * @param mimeType    Optional MIME type (defaults to "text/plain")
   * @return ZIO effect that completes with this FastMCPScala instance or fails with ResourceRegistrationError
   */
  def resourceTemplate(
                        uriPattern: String,
                        handler: ResourceTemplateHandler,
                        name: Option[String] = None,
                        description: Option[String] = None,
                        mimeType: Option[String] = Some("text/plain")
                      ): ZIO[Any, Throwable, FastMCPScala] =
    val definition = ResourceDefinition(uriPattern, name, description, mimeType)
    resourceManager.addResourceTemplate(uriPattern, handler, definition).as(this)

  /**
   * Register a prompt with the server
   *
   * @param name        Prompt name
   * @param handler     Function to execute when the prompt is rendered
   * @param description Optional prompt description
   * @param arguments   Optional list of prompt arguments
   * @return ZIO effect that completes with this FastMCPScala instance or fails with PromptRegistrationError
   */
  def prompt(
              name: String,
              handler: PromptHandler,
              description: Option[String] = None,
              arguments: Option[List[PromptArgument]] = None
            ): ZIO[Any, Throwable, FastMCPScala] =
    val definition = PromptDefinition(name, description, arguments)
    promptManager.addPrompt(name, handler, definition).as(this)

  def listTools(): ZIO[Any, Throwable, McpSchema.ListToolsResult] =
    ZIO.succeed {
      val tools = toolManager.listDefinitions().map(ToolDefinition.toJava).asJava
      new McpSchema.ListToolsResult(tools, null) // Assuming no pagination for now
    }

  def listResources(): ZIO[Any, Throwable, McpSchema.ListResourcesResult] =
    ZIO.succeed {
      val resources = resourceManager.listDefinitions().map(ResourceDefinition.toJava).asJava
      new McpSchema.ListResourcesResult(resources, null) // Assuming no pagination
    }

  // --- MCP Protocol Handlers (Implementation using Managers) ---

  def listPrompts(): ZIO[Any, Throwable, McpSchema.ListPromptsResult] =
    ZIO.succeed {
      val prompts = promptManager.listDefinitions().map(PromptDefinition.toJava).asJava
      new McpSchema.ListPromptsResult(prompts, null) // Assuming no pagination
    }

  /**
   * Run the server with the specified transport
   *
   * @param transport Transport type ("stdio" or "sse")
   * @return ZIO effect that completes when the server stops or fails with Exception
   */
  def run(transport: String = "stdio"): ZIO[Any, Throwable, Unit] =
    transport.toLowerCase match {
      case "stdio" => runStdio()
      // case "sse" => runSse() // SSE requires more setup (e.g., web server)
      case _ => ZIO.fail(new IllegalArgumentException(s"Unsupported transport: $transport"))
    }

  /**
   * Run the server with stdio transport
   *
   * @return ZIO effect that completes when the server stops or fails with Exception
   */
  def runStdio(): ZIO[Any, Throwable, Unit] =
    ZIO.attemptBlocking {
      // Create a stdio transport provider
      val stdioTransportProvider = new io.modelcontextprotocol.server.transport.StdioServerTransportProvider()
      this.setupServer(stdioTransportProvider)

      // Log to stderr - stdout is used for MCP communication
      JSystem.err.println(s"FastMCPScala server '${this.name}' running with stdio transport.")

      // Keep the main fiber alive as the Java SDK handles the processing
      Thread.sleep(Long.MaxValue)
    }.unit

  /**
   * Set up the Java MCP Server with the given transport provider
   *
   * @param transportProvider Provider for server transport
   */
  def setupServer(transportProvider: McpServerTransportProvider): Unit = {
    val serverBuilder = McpServer.sync(transportProvider)
      .serverInfo(name, version)

    // Set up capabilities based on what's registered
    // Create a map for experimental capabilities
    val experimental = new java.util.HashMap[String, Object]()

    // Create capabilities
    val toolCapabilities = if (toolManager.listDefinitions().nonEmpty)
      new McpSchema.ServerCapabilities.ToolCapabilities(true)
    else null

    val resourceCapabilities = if (resourceManager.listDefinitions().nonEmpty)
      new McpSchema.ServerCapabilities.ResourceCapabilities(true, false)
    else null

    val promptCapabilities = if (promptManager.listDefinitions().nonEmpty)
      new McpSchema.ServerCapabilities.PromptCapabilities(true)
    else null

    val loggingCapabilities = new McpSchema.ServerCapabilities.LoggingCapabilities()

    // Create server capabilities
    val capabilities = new McpSchema.ServerCapabilities(
      experimental,
      loggingCapabilities,
      promptCapabilities,
      resourceCapabilities,
      toolCapabilities
    )

    serverBuilder.capabilities(capabilities)

    // Register handlers for tools
    toolManager.listDefinitions().foreach { toolDef =>
      serverBuilder.tool(
        ToolDefinition.toJava(toolDef),
        javaToolHandler(toolDef.name)
      )
    }

    // Register resources
    resourceManager.listDefinitions().foreach { resDef =>
      // Register the resource definition (metadata only)
      val resourceSpec = new McpServerFeatures.SyncResourceSpecification(
        ResourceDefinition.toJava(resDef),
        (exchange, request) => {
          val uri = request.uri()
          val result = Unsafe.unsafe { implicit unsafe =>
            Runtime.default.unsafe.run(readResource(uri)).getOrThrowFiberFailure()
          }
          result
        }
      )

      // Use the resources method to register the resource specification
      serverBuilder.resources(resourceSpec)
    }

    // Register prompts
    promptManager.listDefinitions().foreach { promptDef =>
      // Create a prompt specification with its handler
      val promptSpec = new McpServerFeatures.SyncPromptSpecification(
        PromptDefinition.toJava(promptDef),
        javaPromptHandler(promptDef.name)
      )

      // Use the prompts method to register the prompt specification
      serverBuilder.prompts(promptSpec)
    }

    // Build the server
    underlyingJavaServer = Some(serverBuilder.build())
    // Use stderr for logging since stdout is used for MCP communication
    JSystem.err.println(s"MCP Server '$name' configured.")
  }

  /**
   * Convert Scala ZIO handler to Java BiFunction for SyncServer
   */
  private def javaToolHandler(toolName: String): java.util.function.BiFunction[McpSyncServerExchange, java.util.Map[String, Object], McpSchema.CallToolResult] =
    (exchange, args) => {
      val scalaArgs = args.asScala.toMap.asInstanceOf[Map[String, Any]]
      val context = McpContext(Some(exchange))

      // Run the ZIO effect and block for the result
      val resultEffect: ZIO[Any, Throwable, Any] = toolManager.callTool(toolName, scalaArgs, Some(context))
      val result = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(resultEffect).getOrThrowFiberFailure()
      }

      // Convert result to Java CallToolResult
      val contentList = result match {
        case s: String =>
          val textContent = new McpSchema.TextContent(null, null, s)
          List(textContent).asJava
        case content: Content =>
          List(content.toJava).asJava
        case contentList: List[_] if contentList.nonEmpty && contentList.head.isInstanceOf[Content] =>
          contentList.asInstanceOf[List[Content]].map(_.toJava).asJava
        case other =>
          val textContent = new McpSchema.TextContent(null, null, other.toString)
          List(textContent).asJava // Fallback
      }
      new McpSchema.CallToolResult(contentList, false) // Assuming no error for now
    }

  // --- Server Running Logic ---

  def readResource(uri: String): ZIO[Any, Throwable, McpSchema.ReadResourceResult] =
    for {
      // Assuming ResourceManager.readResource returns String | Array[Byte]
      content <- this.resourceManager.readResource(uri, None) // Context might be needed later
      javaContent = content match {
        case s: String => new McpSchema.TextResourceContents(uri, "text/plain", s) // Assuming text
        case bytes: Array[Byte] =>
          val base64Data = java.util.Base64.getEncoder.encodeToString(bytes)
          new McpSchema.BlobResourceContents(uri, "application/octet-stream", base64Data) // Assuming binary
      }
    } yield new McpSchema.ReadResourceResult(List(javaContent).asJava)

  /**
   * Convert Scala ZIO handler to Java BiFunction for prompt handling
   */
  def javaPromptHandler(promptName: String): java.util.function.BiFunction[McpSyncServerExchange, McpSchema.GetPromptRequest, McpSchema.GetPromptResult] =
    (exchange, request) => {
      val scalaArgs = request.arguments().asScala.toMap.asInstanceOf[Map[String, Any]]
      val context = McpContext(Some(exchange))

      val messagesEffect = this.promptManager.getPrompt(promptName, scalaArgs, Some(context))
      val messages = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(messagesEffect).getOrThrowFiberFailure()
      }

      val javaMessages = messages.map(Message.toJava).asJava
      // Get description from the prompt definition
      val description = this.promptManager.getPromptDefinition(promptName)
        .flatMap(_.description)
        .getOrElse(s"Prompt: $promptName")

      new McpSchema.GetPromptResult(description, javaMessages)
    }

  /**
   * Run the server with SSE transport (Not implemented yet)
   *
   * @return ZIO effect that completes when the server stops or fails with Exception
   */
  // def runSse(): ZIO[Any, Throwable, Unit] = ???

  /**
   * Register a tool generated from a macro-annotated method (simplified implementation)
   * This method is called by the ToolMacros.processAnnotations macro
   *
   * @param toolName     Name of the tool
   * @param description  Description of the tool
   * @param methodName   Name of the method to be called
   * @param schemaJson   JSON Schema for the tool's input
   * @param paramNames   Names of the parameters
   * @param paramTypes   Types of the parameters as strings
   * @param required     Boolean flags indicating if each parameter is required
   * @tparam T           The type containing the annotated method
   * @return Unit        No explicit return value
   */
  def registerMacroTool[T](
    toolName: String,
    description: Option[String],
    methodName: String,
    schemaJson: String,
    paramNames: List[String],
    paramTypes: List[String],
    required: List[Boolean]
  )(using classTag: scala.reflect.ClassTag[T]): Unit =
    JSystem.err.println(s"[FastMCPScala] Registering macro-generated tool '$toolName' from method '$methodName'")
    JSystem.err.println(s"[FastMCPScala] Schema: $schemaJson")
    
    // For a simplified version, we just register a basic handler
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(
        tool(
          name = toolName,
          handler = args => ZIO.succeed(s"Macro tool $toolName called with args: $args"),
          description = description,
          options = ToolRegistrationOptions()
        )
      ).getOrThrowFiberFailure()
    }

end FastMCPScala

/**
 * Companion object for FastMCPScala
 */
object FastMCPScala:
  /**
   * Create a new FastMCPScala instance and run it with stdio transport
   *
   * @param name     Server name
   * @param version  Server version
   * @param settings Server settings
   * @return ZIO effect that completes when the server stops or fails with Exception
   */
  def stdio(
             name: String = "FastMCPScala",
             version: String = "0.1.0",
             settings: FastMCPScalaSettings = FastMCPScalaSettings()
           ): ZIO[Any, Throwable, Unit] =
    ZIO.succeed(apply(name, version, settings)).flatMap(_.runStdio())

  /**
   * Create a new FastMCPScala instance with the given settings
   *
   * @param name     Server name
   * @param version  Server version
   * @param settings Server settings
   * @return A new FastMCPScala instance
   */
  def apply(
             name: String = "FastMCPScala",
             version: String = "0.1.0",
             settings: FastMCPScalaSettings = FastMCPScalaSettings()
           ): FastMCPScala =
    new FastMCPScala(name, version, settings)