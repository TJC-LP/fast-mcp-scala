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

// Qualified import to resolve ambiguity
import fastmcp.server.manager.{ResourceDefinition => ManagerResourceDefinition}

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
  val toolManager = new ToolManager()
  val resourceManager = new ResourceManager()
  val promptManager = new PromptManager()
  // Placeholder for the Java MCP Server instance
  private var underlyingJavaServer: Option[McpSyncServer] = None

  // --- Private Java Handler Converters ---

  /**
   * Creates a Java BiFunction handler for a specific static resource URI.
   */
  private def javaStaticResourceReadHandler(registeredUri: String): java.util.function.BiFunction[McpSyncServerExchange, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult] =
    (exchange, request) => {
      val handlerOpt: Option[ResourceHandler] = resourceManager.getResourceHandler(registeredUri)
      handlerOpt match {
        case Some(handler) =>
          Unsafe.unsafe { implicit unsafe =>
            Runtime.default.unsafe.run {
              handler() // Static handler takes no params
                .flatMap { content =>
                  val mimeTypeOpt = resourceManager.getResourceDefinition(registeredUri).flatMap(_.mimeType)
                  createReadResourceResult(registeredUri, content, mimeTypeOpt)
                }
                .catchAll(e => ZIO.fail(new RuntimeException(s"Error executing static resource handler for $registeredUri", e)))
            }.getOrThrowFiberFailure()
          }
        case None =>
          throw new RuntimeException(s"Static resource handler not found for URI: $registeredUri")
      }
    }

  /**
   * Creates a generic Java BiFunction handler for templated resources.
   */
  private lazy val genericTemplateReadHandler: java.util.function.BiFunction[McpSyncServerExchange, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult] =
    (exchange, request) => {
      val actualUri = request.uri()
      val context = McpContext(Some(exchange))
      JSystem.err.println(s"[FastMCPScala] Generic template handler invoked for actual URI: '$actualUri'")
      try {
        Unsafe.unsafe { implicit unsafe =>
          Runtime.default.unsafe.run {
            resourceManager.readResource(actualUri, Some(context))
              .flatMap { content =>
                val mimeTypeOpt = resourceManager.findMatchingTemplate(actualUri).flatMap(_._2.mimeType)
                createReadResourceResult(actualUri, content, mimeTypeOpt)
              }
              .catchAll {
                case e: ResourceNotFoundError =>
                  JSystem.err.println(s"[FastMCPScala] Resource not found via ResourceManager for URI '$actualUri': ${e.getMessage}")
                  ZIO.fail(new RuntimeException(s"Resource not found: '$actualUri'", e))
                case e: Throwable =>
                  JSystem.err.println(s"[FastMCPScala] Error reading resource '$actualUri' via ResourceManager: ${e.getMessage}")
                  ZIO.fail(new RuntimeException(s"Error accessing resource '$actualUri'", e))
              }
          }.getOrThrowFiberFailure()
        }
      } catch {
        case e: Throwable =>
          JSystem.err.println(s"[FastMCPScala] Synchronous error in generic template handler for URI '$actualUri': ${e.getMessage}")
          throw new RuntimeException(s"Error processing resource '$actualUri'", e)
      }
    }


  /**
   * Convert Scala ZIO handler to Java BiFunction for prompt handling
   */
  private def javaPromptHandler(promptName: String)
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

  /**
   * Convert Scala ZIO handler to Java BiFunction for SyncServer Tool handling.
   * IMPORTANT: This method assumes the ToolHandler returns a type that can be
   * directly converted to McpSchema.Content (String, Array[Byte], Content, List[Content])
   * or a String representation. It no longer attempts generic JSON serialization
   * for arbitrary Products or Maps due to lack of implicit encoders.
   * ToolHandlers returning complex types should serialize them to JSON String *within* the handler.
   */
  private def javaToolHandler(toolName: String)
  : java.util.function.BiFunction[McpSyncServerExchange, java.util.Map[String, Object], McpSchema.CallToolResult] =
    (exchange, args) => {
      val scalaArgs = args.asScala.toMap.asInstanceOf[Map[String, Any]]
      val context = McpContext(Some(exchange))

      // Execute the user-provided ToolHandler
      val resultEffect: ZIO[Any, Throwable, Any] = toolManager.callTool(toolName, scalaArgs, Some(context))
      val result: Any = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(resultEffect).getOrThrowFiberFailure()
      }

      // Convert the result to Java McpSchema.Content list
      val contentList: java.util.List[McpSchema.Content] = result match {
        case s: String =>
          // If the string looks like JSON, create TextContent.
          // Tool handlers returning case classes should ideally call .toJson themselves.
          List(new McpSchema.TextContent(null, null, s)).asJava
        case bytes: Array[Byte] =>
          val base64Data = java.util.Base64.getEncoder.encodeToString(bytes)
          // Use ImageContent for now, might need a more generic BlobContent if available/needed
          List(new McpSchema.ImageContent(null, null, base64Data, "application/octet-stream")).asJava
        case c: Content => // Handle fastmcp.core.Content directly
          List(c.toJava).asJava
        case lst: List[?] if lst.nonEmpty && lst.head.isInstanceOf[Content] =>
          // Handle List of fastmcp.core.Content
          lst.asInstanceOf[List[Content]].map(_.toJava).asJava
        // Removed generic Product and Map handling - ToolHandler must return supported type or String
        // case p: Product => ... removed ...
        // case m: Map[?, ?] => ... removed ...
        case null => // Handle null result gracefully
          JSystem.err.println(s"[FastMCPScala] Warning: Tool handler for '$toolName' returned null.")
          List.empty[McpSchema.Content].asJava
        case other =>
          // Fallback: Convert any other type to its String representation.
          // Complex objects should be serialized to JSON string within the handler.
          JSystem.err.println(s"[FastMCPScala] Warning: Tool handler for '$toolName' returned type ${other.getClass.getName}, using toString representation.")
          List(new McpSchema.TextContent(null, null, other.toString)).asJava
      }

      // Construct the final result for the MCP protocol
      new McpSchema.CallToolResult(contentList, false) // Assuming streaming is false for sync handler
    }

  // --- Public Registration Methods ---

  /**
   * Register a tool with the server
   */
  def tool(
            name: String,
            handler: ToolHandler,
            description: Option[String] = None,
            inputSchema: Either[McpSchema.JsonSchema, String] = Left(new McpSchema.JsonSchema("object", null, null, true, null, null)),
            options: ToolRegistrationOptions = ToolRegistrationOptions()
          ): ZIO[Any, Throwable, FastMCPScala] =
    val definition = ToolDefinition(
      name = name,
      description = description,
      inputSchema = inputSchema
    )
    toolManager.addTool(name, handler, definition, options).as(this)

  /**
   * Register a **static** resource with the server.
   */
  def resource(
                uri: String,
                handler: ResourceHandler, // () => ZIO[Any, Throwable, String | Array[Byte]]
                name: Option[String] = None,
                description: Option[String] = None,
                mimeType: Option[String] = Some("text/plain") // Default mimeType here
              ): ZIO[Any, Throwable, FastMCPScala] = {
    val definition = ManagerResourceDefinition(
      uri = uri, name = name, description = description, mimeType = mimeType,
      isTemplate = false, arguments = None
    )
    resourceManager.addResource(uri, handler, definition).as(this)
  }

  /**
   * Register a **templated** resource with the server.
   */
  def resourceTemplate(
                        uriPattern: String,
                        handler: ResourceTemplateHandler, // Map[String, String] => ZIO[Any, Throwable, String | Array[Byte]]
                        name: Option[String] = None,
                        description: Option[String] = None,
                        mimeType: Option[String] = Some("text/plain"), // Default mimeType here
                        arguments: Option[List[ResourceArgument]] = None // Arguments might be passed by macro
                      ): ZIO[Any, Throwable, FastMCPScala] = {
    val definition = ManagerResourceDefinition(
      uri = uriPattern, name = name, description = description, mimeType = mimeType,
      isTemplate = true, arguments = arguments
    )
    resourceManager.addResourceTemplate(uriPattern, handler, definition).as(this)
  }


  /**
   * Register a prompt with the server
   */
  def prompt(
              name: String,
              handler: PromptHandler,
              description: Option[String] = None,
              arguments: Option[List[PromptArgument]] = None
            ): ZIO[Any, Throwable, FastMCPScala] =
    val definition = PromptDefinition(name, description, arguments)
    promptManager.addPrompt(name, handler, definition).as(this)

  // --- Public Listing Methods ---

  def listTools(): ZIO[Any, Throwable, McpSchema.ListToolsResult] =
    ZIO.succeed {
      val tools = toolManager.listDefinitions().map(ToolDefinition.toJava).asJava
      new McpSchema.ListToolsResult(tools, null)
    }

  def listResources(): ZIO[Any, Throwable, McpSchema.ListResourcesResult] =
    ZIO.succeed {
      val javaResources = resourceManager.listDefinitions().map { resourceDef =>
        ManagerResourceDefinition.toJava(resourceDef) match {
          case res: McpSchema.Resource => res
          case template: McpSchema.ResourceTemplate =>
            new McpSchema.Resource(
              template.uriTemplate(), template.name(), template.description(),
              template.mimeType(), template.annotations()
            )
          case other =>
            JSystem.err.println(s"[FastMCPScala] Warning: Unexpected type during resource list conversion: ${other.getClass.getName}")
            new McpSchema.Resource(resourceDef.uri, resourceDef.name.orNull, resourceDef.description.orNull, resourceDef.mimeType.orNull, null)
        }
      }.asJava
      new McpSchema.ListResourcesResult(javaResources, null)
    }

  def listPrompts(): ZIO[Any, Throwable, McpSchema.ListPromptsResult] =
    ZIO.succeed {
      val prompts = promptManager.listDefinitions().map(PromptDefinition.toJava).asJava
      new McpSchema.ListPromptsResult(prompts, null)
    }

  // --- Server Lifecycle Methods ---

  /**
   * Run the server with the specified transport
   */
  def run(transport: String = "stdio"): ZIO[Any, Throwable, Unit] =
    transport.toLowerCase match
      case "stdio" => runStdio()
      case _       => ZIO.fail(new IllegalArgumentException(s"Unsupported transport: $transport"))

  /**
   * Run the server with stdio transport
   */
  def runStdio(): ZIO[Any, Throwable, Unit] =
    ZIO.attemptBlocking {
      val stdioTransportProvider = new io.modelcontextprotocol.server.transport.StdioServerTransportProvider()
      this.setupServer(stdioTransportProvider)
      JSystem.err.println(s"FastMCPScala server '${this.name}' running with stdio transport.")
      Thread.sleep(Long.MaxValue)
    }.unit

  /**
   * Set up the Java MCP Server with the given transport provider
   */
  def setupServer(transportProvider: McpServerTransportProvider): Unit =
    val serverBuilder = McpServer.sync(transportProvider)
      .serverInfo(name, version)

    // --- Capabilities Setup ---
    val experimental = new java.util.HashMap[String, Object]()
    val toolCapabilities = if (toolManager.listDefinitions().nonEmpty) new McpSchema.ServerCapabilities.ToolCapabilities(true) else null
    val resourceCapabilities = if (resourceManager.listDefinitions().nonEmpty) new McpSchema.ServerCapabilities.ResourceCapabilities(true, true) else null
    val promptCapabilities = if (promptManager.listDefinitions().nonEmpty) new McpSchema.ServerCapabilities.PromptCapabilities(true) else null
    val loggingCapabilities = new McpSchema.ServerCapabilities.LoggingCapabilities()

    val capabilities = new McpSchema.ServerCapabilities(
      experimental, loggingCapabilities, promptCapabilities, resourceCapabilities, toolCapabilities
    )
    serverBuilder.capabilities(capabilities)

    // --- Tool Registration ---
    val tools = toolManager.listDefinitions()
    JSystem.err.println(s"[FastMCPScala] Registering ${tools.size} tools with the MCP server:")
    tools.foreach { toolDef =>
      JSystem.err.println(s"[FastMCPScala] - Registering Tool: ${toolDef.name}")
      serverBuilder.tool(ToolDefinition.toJava(toolDef), javaToolHandler(toolDef.name))
    }

    // --- Resource and Template Registration with Java Server ---
    JSystem.err.println(s"[FastMCPScala] Processing ${resourceManager.listDefinitions().size} resource definitions for Java server registration...")
    val resourceSpecs = new java.util.ArrayList[McpServerFeatures.SyncResourceSpecification]()
    val templateDefs = new java.util.ArrayList[McpSchema.ResourceTemplate]()

    resourceManager.listDefinitions().foreach { resDef =>
      JSystem.err.println(s"[FastMCPScala] - Processing definition for URI: ${resDef.uri}, isTemplate: ${resDef.isTemplate}")

      if (resDef.isTemplate) {
        // 1. Add Template Definition for discovery via .resourceTemplates()
        ManagerResourceDefinition.toJava(resDef) match {
          case template: McpSchema.ResourceTemplate =>
            templateDefs.add(template)
            JSystem.err.println(s"[FastMCPScala]   - Added ResourceTemplate definition for discovery: ${resDef.uri}")
          case _ => JSystem.err.println(s"[FastMCPScala]   - Warning: ResourceDefinition marked as template but did not convert to ResourceTemplate: ${resDef.uri}")
        }
        JSystem.err.println(s"[FastMCPScala]   - Added Generic Handler spec keyed by template URI: ${resDef.uri}")

      } else {
        // --- Static Resource ---
        ManagerResourceDefinition.toJava(resDef) match {
          case resource: McpSchema.Resource =>
            val resourceSpec = new McpServerFeatures.SyncResourceSpecification(resource, javaStaticResourceReadHandler(resDef.uri))
            resourceSpecs.add(resourceSpec)
            JSystem.err.println(s"[FastMCPScala]   - Added SyncResourceSpecification for static resource: ${resDef.uri}")
          case _ => JSystem.err.println(s"[FastMCPScala]   - Warning: ResourceDefinition marked as static but did not convert to Resource: ${resDef.uri}")
        }
      }
    }

    if (!resourceSpecs.isEmpty) {
      serverBuilder.resources(resourceSpecs)
      JSystem.err.println(s"[FastMCPScala] Registered ${resourceSpecs.size()} resource handler specifications with Java server via .resources()")
    }
    if (!templateDefs.isEmpty) {
      serverBuilder.resourceTemplates(templateDefs)
      JSystem.err.println(s"[FastMCPScala] Registered ${templateDefs.size()} resource template definitions with Java server via .resourceTemplates()")
    }

    // --- Prompt Registration ---
    JSystem.err.println(s"[FastMCPScala] Registering ${promptManager.listDefinitions().size} prompts...")
    promptManager.listDefinitions().foreach { promptDef =>
      JSystem.err.println(s"[FastMCPScala] - Registering Prompt: ${promptDef.name}")
      val javaPrompt = PromptDefinition.toJava(promptDef)
      val promptSpec = new McpServerFeatures.SyncPromptSpecification(javaPrompt, javaPromptHandler(promptDef.name))
      serverBuilder.prompts(promptSpec)
    }

    // --- Build Server ---
    underlyingJavaServer = Some(serverBuilder.build())
    JSystem.err.println(s"MCP Server '$name' configured.")

  // --- Private Helper Methods ---

  /**
   * Helper to convert Scala result types into McpSchema.ReadResourceResult.
   */
  private def createReadResourceResult(uri: String, content: String | Array[Byte], mimeTypeOpt: Option[String]): ZIO[Any, Throwable, McpSchema.ReadResourceResult] = ZIO.attempt {
    val finalMimeType = mimeTypeOpt.getOrElse(content match {
      case s: String if (s.startsWith("{") && s.endsWith("}")) || (s.startsWith("[") && s.endsWith("]")) => "application/json"
      case _: String => "text/plain"
      case _: Array[Byte] => "application/octet-stream"
    })

    val javaContent: McpSchema.ResourceContents = content match {
      case s: String => new McpSchema.TextResourceContents(uri, finalMimeType, s)
      case bytes: Array[Byte] =>
        val base64Data = java.util.Base64.getEncoder.encodeToString(bytes)
        new McpSchema.BlobResourceContents(uri, finalMimeType, base64Data)
    }
    new McpSchema.ReadResourceResult(List(javaContent).asJava)
  }

end FastMCPScala

/**
 * Companion object for FastMCPScala
 */
object FastMCPScala:
  /**
   * Create a new FastMCPScala instance and run it with stdio transport
   */
  def stdio(
             name: String = "FastMCPScala",
             version: String = "0.1.0",
             settings: FastMCPScalaSettings = FastMCPScalaSettings()
           ): ZIO[Any, Throwable, Unit] =
    ZIO.succeed(apply(name, version, settings)).flatMap(_.runStdio())

  /**
   * Create a new FastMCPScala instance with the given settings
   */
  def apply(
             name: String = "FastMCPScala",
             version: String = "0.1.0",
             settings: FastMCPScalaSettings = FastMCPScalaSettings()
           ): FastMCPScala =
    new FastMCPScala(name, version, settings) //comment

end FastMCPScala
