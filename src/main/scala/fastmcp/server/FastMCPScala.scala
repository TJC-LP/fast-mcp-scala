package fastmcp.server

import fastmcp.core.*
import fastmcp.server.manager.*
import fastmcp.macros.{JsonSchemaMacro, MapToFunctionMacro}
import io.modelcontextprotocol.server.{McpServer, McpServerFeatures, McpSyncServer, McpSyncServerExchange}
import io.modelcontextprotocol.spec.{McpSchema, McpServerTransportProvider}
import zio.*
import zio.json.*
// Explicitly import Java's System to avoid conflicts with zio.System
import java.lang.{System => JSystem}
// Tapir imports for schema generation
import sttp.tapir.{Schema => TapirSchema}
import sttp.tapir.SchemaType
import sttp.tapir.generic.auto.*

import scala.jdk.CollectionConverters.*
import scala.reflect.ClassTag
import scala.util.{Try, boundary} 
import scala.util.boundary.break

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
  val toolManager = new ToolManager() // public for direct usage in macros
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
      inputSchema: Either[McpSchema.JsonSchema, String] = Left(new McpSchema.JsonSchema("object", null, null, true)),
      options: ToolRegistrationOptions = ToolRegistrationOptions()
  ): ZIO[Any, Throwable, FastMCPScala] =
    val definition = ToolDefinition(
        name = name,
        description = description,
        inputSchema = inputSchema
        // TODO: Add other fields like tags, examples etc. from options or definition if needed
    )
    toolManager.addTool(name, handler, definition, options).as(this)
    
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
      new McpSchema.ListToolsResult(tools, null)
    }

  def listResources(): ZIO[Any, Throwable, McpSchema.ListResourcesResult] =
    ZIO.succeed {
      val resources = resourceManager.listDefinitions().map(ResourceDefinition.toJava).asJava
      new McpSchema.ListResourcesResult(resources, null)
    }

  def listPrompts(): ZIO[Any, Throwable, McpSchema.ListPromptsResult] =
    ZIO.succeed {
      val prompts = promptManager.listDefinitions().map(PromptDefinition.toJava).asJava
      new McpSchema.ListPromptsResult(prompts, null)
    }

  /**
   * Run the server with the specified transport
   *
   * @param transport Transport type ("stdio" or "sse")
   * @return ZIO effect that completes when the server stops or fails with Exception
   */
  def run(transport: String = "stdio"): ZIO[Any, Throwable, Unit] =
    transport.toLowerCase match
      case "stdio" => runStdio()
      // case "sse" => runSse() // SSE requires more setup
      case _       => ZIO.fail(new IllegalArgumentException(s"Unsupported transport: $transport"))

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

      // Log to stderr
      JSystem.err.println(s"FastMCPScala server '${this.name}' running with stdio transport.")

      // Keep the main fiber alive
      Thread.sleep(Long.MaxValue)
    }.unit

  /**
   * Set up the Java MCP Server with the given transport provider
   *
   * @param transportProvider Provider for server transport
   */
  def setupServer(transportProvider: McpServerTransportProvider): Unit =
    val serverBuilder = McpServer.sync(transportProvider)
      .serverInfo(name, version)

    // Set up capabilities based on what's registered
    val experimental = new java.util.HashMap[String, Object]()

    val toolCapabilities =
      if toolManager.listDefinitions().nonEmpty then
        new McpSchema.ServerCapabilities.ToolCapabilities(true)
      else null

    val resourceCapabilities =
      if resourceManager.listDefinitions().nonEmpty then
        // TODO: Differentiate between static and template support if needed by spec
        new McpSchema.ServerCapabilities.ResourceCapabilities(true, true) // Assuming both static and template support
      else null

    val promptCapabilities =
      if promptManager.listDefinitions().nonEmpty then
        new McpSchema.ServerCapabilities.PromptCapabilities(true)
      else null

    val loggingCapabilities = new McpSchema.ServerCapabilities.LoggingCapabilities()

    val capabilities = new McpSchema.ServerCapabilities(
      experimental,
      loggingCapabilities,
      promptCapabilities,
      resourceCapabilities,
      toolCapabilities
    )

    serverBuilder.capabilities(capabilities)

    // Register tool handlers
    val tools = toolManager.listDefinitions()
    JSystem.err.println(s"[FastMCPScala] Registering ${tools.size} tools with the MCP server:")
    tools.foreach { toolDef =>
      // Log the tool name and description being passed to the Java SDK
      JSystem.err.println(s"[FastMCPScala] - Registering Tool: ${toolDef.name}, Description: ${toolDef.description.getOrElse("None")}")
      serverBuilder.tool(
        ToolDefinition.toJava(toolDef), // Converts Scala ToolDefinition to Java McpSchema.Tool
        javaToolHandler(toolDef.name)   // Provides the execution handler
      )
    }

    // Register resources (Static and Templates)
    JSystem.err.println(s"[FastMCPScala] Registering ${resourceManager.listDefinitions().size} resources/templates...")
    // Iterate through ALL resource definitions and register each one
    resourceManager.listDefinitions().foreach { resDef =>
      JSystem.err.println(s"[FastMCPScala] - Registering Resource/Template: ${resDef.uri}")

      // Create the specification for the current resource definition
      val resourceSpec = new McpServerFeatures.SyncResourceSpecification(
        ResourceDefinition.toJava(resDef), // Convert Scala def to Java def
        (exchange, request) => { // Define the handler for this specific resource
          val uri = request.uri()
          // Synchronously run the ZIO effect from readResource(uri)
          // readResource internally calls the correct handler from ResourceManager
          // This block executes the ZIO effect and returns the result or throws
          try {
            val result: McpSchema.ReadResourceResult = Unsafe.unsafe { implicit unsafe =>
              Runtime.default.unsafe.run(readResource(uri)).getOrThrowFiberFailure()
            }
            result // Return the successful result
          } catch {
            case e: Throwable =>
              // Log the error and rethrow or convert to an MCP error if possible
              JSystem.err.println(s"[FastMCPScala] Error reading resource '$uri': ${e.getMessage}")
              // Rethrowing will likely cause the Java SDK to return a generic internal error
              // Ideally, we'd map this to McpSchema.ErrorObject, but the BiFunction signature doesn't allow it directly.
              throw new RuntimeException(s"Error accessing resource '$uri'", e)
          }
        }
      )
      // Register this specific resource specification with the Java server builder
      serverBuilder.resources(resourceSpec)
    }

    // Register prompts
    JSystem.err.println(s"[FastMCPScala] Registering ${promptManager.listDefinitions().size} prompts...")
    promptManager.listDefinitions().foreach { promptDef =>
      JSystem.err.println(s"[FastMCPScala] - Registering Prompt: ${promptDef.name}")
      val promptSpec = new McpServerFeatures.SyncPromptSpecification(
        PromptDefinition.toJava(promptDef),
        javaPromptHandler(promptDef.name) // Use existing handler logic
      )
      serverBuilder.prompts(promptSpec)
    }

    // Build the server
    underlyingJavaServer = Some(serverBuilder.build())
    JSystem.err.println(s"MCP Server '$name' configured.")

  /**
   * Convert Scala ZIO handler to Java BiFunction for SyncServer Tool handling
   */
  private def javaToolHandler(toolName: String)
    : java.util.function.BiFunction[McpSyncServerExchange, java.util.Map[String, Object], McpSchema.CallToolResult] =
    (exchange, args) => {
      val scalaArgs = args.asScala.toMap.asInstanceOf[Map[String, Any]]
      val context = McpContext(Some(exchange))

      val resultEffect: ZIO[Any, Throwable, Any] = toolManager.callTool(toolName, scalaArgs, Some(context))
      val result = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(resultEffect).getOrThrowFiberFailure()
      }
      // Convert result to McpSchema.Content list
      val contentList = result match
        case s: String =>
          List(new McpSchema.TextContent(null, null, s)).asJava
        case bytes: Array[Byte] => // Handle byte array results if needed
           val base64Data = java.util.Base64.getEncoder.encodeToString(bytes)
           // Assuming default image or binary content type
           List(new McpSchema.ImageContent(null, null, base64Data, "application/octet-stream")).asJava
        case c: Content =>
          List(c.toJava).asJava
        case lst: List[?] if lst.nonEmpty && lst.head.isInstanceOf[Content] =>
          lst.asInstanceOf[List[Content]].map(_.toJava).asJava
        case other => // Default to string representation
          List(new McpSchema.TextContent(null, null, other.toString)).asJava

      new McpSchema.CallToolResult(contentList, false)
    }

  /**
   * Read a resource by URI
   *
   * @param uri Resource URI
   * @return ZIO effect that completes with the resource content as ReadResourceResult or fails with ResourceError
   */
  def readResource(uri: String): ZIO[Any, Throwable, McpSchema.ReadResourceResult] =
    for {
      // Attempt to find the resource definition for accurate mime type
      definitionOpt <- ZIO.succeed {
        resourceManager.getResourceDefinition(uri)                         // First try static resources
          .orElse(resourceManager.findMatchingTemplate(uri).map(_._2))     // Then try templates
      }
      
      // Get the content from the resource handler
      content <- resourceManager.readResource(uri, None)
      
      // Determine the correct mime type
      finalMimeType = definitionOpt.flatMap(_.mimeType).getOrElse {
        content match {
          case _: String => "text/plain"                  // Default for strings
          case _: Array[Byte] => "application/octet-stream" // Default for binary
          case _ => "application/json"                    // Default for other types (assume JSON)
        }
      }
      
      // Create the appropriate resource contents with the correct mime type
      javaContent = content match {
        case s: String =>
          new McpSchema.TextResourceContents(uri, finalMimeType, s)
        case bytes: Array[Byte] =>
          val base64Data = java.util.Base64.getEncoder.encodeToString(bytes)
          new McpSchema.BlobResourceContents(uri, finalMimeType, base64Data)
        case other =>
          // For non-String, non-byte[] types, use toString and treat as text
          new McpSchema.TextResourceContents(uri, finalMimeType, other.toString)
      }
    } yield new McpSchema.ReadResourceResult(List(javaContent).asJava)

  /**
   * Convert Scala ZIO handler to Java BiFunction for prompt handling
   */
  def javaPromptHandler(promptName: String)
    : java.util.function.BiFunction[McpSyncServerExchange, McpSchema.GetPromptRequest, McpSchema.GetPromptResult] =
    (exchange, request) => {
      val scalaArgs = Option(request.arguments()).map(_.asScala.toMap.asInstanceOf[Map[String, Any]]).getOrElse(Map.empty)
      val context = McpContext(Some(exchange))

      val messagesEffect = promptManager.getPrompt(promptName, scalaArgs, Some(context))
      val messages = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(messagesEffect).getOrThrowFiberFailure()
      }

      val javaMessages = messages.map(Message.toJava).asJava
      val description = promptManager
        .getPromptDefinition(promptName)
        .flatMap(_.description)
        .getOrElse(s"Prompt: $promptName")

      new McpSchema.GetPromptResult(description, javaMessages)
    }

end FastMCPScala // Ensure class definition is closed

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