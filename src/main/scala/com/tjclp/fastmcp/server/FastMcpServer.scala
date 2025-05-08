package com.tjclp.fastmcp
package server

import io.modelcontextprotocol.server.McpAsyncServer
import io.modelcontextprotocol.server.McpAsyncServerExchange
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpServerTransportProvider
import reactor.core.publisher.Mono
import zio.*

import java.lang.System as JSystem
import scala.concurrent.ExecutionContext.Implicits.global
import scala.jdk.CollectionConverters.*
import scala.util.Failure
import scala.util.Success

import core.*
import server.manager.* // Needed for runToFuture onComplete

/** Main server class for FastMCP-Scala
  *
  * This class provides a high-level API for creating MCP servers using Scala and ZIO
  */
class FastMcpServer(
    val name: String = "FastMCPScala",
    version: String = "0.1.0",
    settings: FastMcpServerSettings = FastMcpServerSettings()
):
  val dependencies: List[String] = settings.dependencies
  // Initialize managers
  val toolManager = new ToolManager()
  val resourceManager = new ResourceManager()
  val promptManager = new PromptManager()

  // --- Private ZIO to Mono Conversion Helper ---
  // Placeholder for the Java MCP Server instance - Changed to McpAsyncServer
  private var underlyingJavaServer: Option[McpAsyncServer] = None

  // --- Private Java Handler Converters ---

  /** Register a tool with the server
    */
  def tool(
      name: String,
      handler: ContextualToolHandler,
      description: Option[String] = None,
      inputSchema: Either[McpSchema.JsonSchema, String] = Left(
        new McpSchema.JsonSchema("object", null, null, true)
      ),
      options: ToolRegistrationOptions = ToolRegistrationOptions()
  ): ZIO[Any, Throwable, FastMcpServer] =
    val definition = ToolDefinition(
      name = name,
      description = description,
      inputSchema = inputSchema
    )
    toolManager.addTool(name, handler, definition, options).as(this)

  /** Register a **static** resource with the server.
    */
  def resource(
      uri: String,
      handler: ResourceHandler, // () => ZIO[Any, Throwable, String | Array[Byte]]
      name: Option[String] = None,
      description: Option[String] = None,
      mimeType: Option[String] = Some("text/plain") // Default mimeType here
  ): ZIO[Any, Throwable, FastMcpServer] = {
    val definition = ResourceDefinition(
      uri = uri,
      name = name,
      description = description,
      mimeType = mimeType,
      isTemplate = false,
      arguments = None
    )
    resourceManager.addResource(uri, handler, definition).as(this)
  }

  /** Register a **templated** resource with the server.
    */
  def resourceTemplate(
      uriPattern: String,
      handler: ResourceTemplateHandler, // Map[String, String] => ZIO[Any, Throwable, String | Array[Byte]]
      name: Option[String] = None,
      description: Option[String] = None,
      mimeType: Option[String] = Some("text/plain"), // Default mimeType here
      arguments: Option[List[ResourceArgument]] = None // Arguments might be passed by macro
  ): ZIO[Any, Throwable, FastMcpServer] = {
    val definition = ResourceDefinition(
      uri = uriPattern,
      name = name,
      description = description,
      mimeType = mimeType,
      isTemplate = true,
      arguments = arguments
    )
    resourceManager.addResourceTemplate(uriPattern, handler, definition).as(this)
  }

  /** Register a prompt with the server
    */
  def prompt(
      name: String,
      handler: PromptHandler,
      description: Option[String] = None,
      arguments: Option[List[PromptArgument]] = None
  ): ZIO[Any, Throwable, FastMcpServer] =
    val definition = PromptDefinition(name, description, arguments)
    promptManager.addPrompt(name, handler, definition).as(this)

  // --- Public Registration Methods ---

  def listTools(): ZIO[Any, Throwable, McpSchema.ListToolsResult] =
    ZIO.succeed {
      val tools = toolManager.listDefinitions().map(ToolDefinition.toJava).asJava
      new McpSchema.ListToolsResult(tools, null)
    }

  def listResources(): ZIO[Any, Throwable, McpSchema.ListResourcesResult] =
    ZIO.succeed {
      val javaResources = resourceManager
        .listDefinitions()
        .map { resourceDef =>
          ResourceDefinition.toJava(resourceDef) match {
            case res: McpSchema.Resource => res
            case template: McpSchema.ResourceTemplate =>
              new McpSchema.Resource(
                template.uriTemplate(),
                template.name(),
                template.description(),
                template.mimeType(),
                template.annotations()
              )
          }
        }
        .asJava
      new McpSchema.ListResourcesResult(javaResources, null)
    }

  def listPrompts(): ZIO[Any, Throwable, McpSchema.ListPromptsResult] =
    ZIO.succeed {
      val prompts = promptManager.listDefinitions().map(PromptDefinition.toJava).asJava
      new McpSchema.ListPromptsResult(prompts, null)
    }

  /** Run the server with the specified transport
    */
  def run(transport: String = "stdio"): ZIO[Any, Throwable, Unit] =
    transport.toLowerCase match
      case "stdio" => runStdio()
      case _ => ZIO.fail(new IllegalArgumentException(s"Unsupported transport: $transport"))

  // --- Public Listing Methods ---

  /** Run the server with stdio transport
    */
  def runStdio(): ZIO[Any, Throwable, Unit] =
    ZIO.scoped { // ⬅ drops the `Scope` requirement
      ZIO.acquireRelease(
        for {
          provider <- ZIO.attempt(new StdioServerTransportProvider())
          _ <- ZIO.attempt(setupServer(provider))
          _ <- ZIO.attempt(
            JSystem.err.println(
              s"[FastMCPScala] '$name' running on stdio – press Ctrl-C to stop."
            )
          )
        } yield ()
      )(_ => ZIO.attempt(underlyingJavaServer.foreach(_.close())).orDie) *> ZIO.never.as(
        ()
      ) // ⬅ turn `Nothing` into `Unit`
    }

  /** Set up the Java MCP Server with the given transport provider
    */
  def setupServer(transportProvider: McpServerTransportProvider): Unit =
    // Use McpServer.async builder
    val serverBuilder = McpServer
      .async(transportProvider)
      .serverInfo(name, version)

    // --- Capabilities Setup ---
    val experimental = new java.util.HashMap[String, Object]()
    val toolCapabilities =
      if (toolManager.listDefinitions().nonEmpty)
        new McpSchema.ServerCapabilities.ToolCapabilities(true)
      else null
    val resourceCapabilities =
      if (resourceManager.listDefinitions().nonEmpty)
        new McpSchema.ServerCapabilities.ResourceCapabilities(true, true)
      else null
    val promptCapabilities =
      if (promptManager.listDefinitions().nonEmpty)
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

    // --- Tool Registration ---
    val tools = toolManager.listDefinitions()
    JSystem.err.println(s"[FastMCPScala] Registering ${tools.size} tools with the MCP server:")
    tools.foreach { toolDef =>
      JSystem.err.println(s"[FastMCPScala] - Registering Tool: ${toolDef.name}")
      serverBuilder.tool(ToolDefinition.toJava(toolDef), javaToolHandler(toolDef.name))
    }

    // --- Resource and Template Registration with Java Server ---
    JSystem.err.println(
      s"[FastMCPScala] Processing ${resourceManager.listDefinitions().size} resource definitions for Java server registration..."
    )
    val resourceSpecs = new java.util.ArrayList[McpServerFeatures.AsyncResourceSpecification]()
    val templateDefs = new java.util.ArrayList[McpSchema.ResourceTemplate]()

    resourceManager.listDefinitions().foreach { resDef =>
      JSystem.err.println(
        s"[FastMCPScala] - Processing definition for URI: ${resDef.uri}, isTemplate: ${resDef.isTemplate}"
      )

      if (resDef.isTemplate) {
        // 1. Add Template Definition for discovery via .resourceTemplates()
        ResourceDefinition.toJava(resDef) match {
          case template: McpSchema.ResourceTemplate =>
            templateDefs.add(template)
            JSystem.err.println(
              s"[FastMCPScala]   - Added ResourceTemplate definition for discovery: ${resDef.uri}"
            )
          case _ =>
            JSystem.err.println(
              s"[FastMCPScala]   - Warning: ResourceDefinition marked as template but did not convert to ResourceTemplate: ${resDef.uri}"
            )
        }
        JSystem.err.println(
          s"[FastMCPScala]   - Added Generic Handler spec keyed by template URI: ${resDef.uri}"
        )

      } else {
        // --- Static Resource ---
        ResourceDefinition.toJava(resDef) match {
          case resource: McpSchema.Resource =>
            val resourceSpec = new McpServerFeatures.AsyncResourceSpecification(
              resource,
              javaStaticResourceReadHandler(resDef.uri)
            )
            resourceSpecs.add(resourceSpec)
            JSystem.err.println(
              s"[FastMCPScala]   - Added AsyncResourceSpecification for static resource: ${resDef.uri}"
            )
          case _ =>
            JSystem.err.println(
              s"[FastMCPScala]   - Warning: ResourceDefinition marked as static but did not convert to Resource: ${resDef.uri}"
            )
        }
      }
    }

    if (!resourceSpecs.isEmpty) {
      serverBuilder.resources(resourceSpecs)
      JSystem.err.println(
        s"[FastMCPScala] Registered ${resourceSpecs.size()} resource handler specifications with Java server via .resources()"
      )
    }
    if (!templateDefs.isEmpty) {
      serverBuilder.resourceTemplates(templateDefs)
      JSystem.err.println(
        s"[FastMCPScala] Registered ${templateDefs.size()} resource template definitions with Java server via .resourceTemplates()"
      )
    }

    // --- Prompt Registration ---
    JSystem.err.println(
      s"[FastMCPScala] Registering ${promptManager.listDefinitions().size} prompts..."
    )
    promptManager.listDefinitions().foreach { promptDef =>
      JSystem.err.println(s"[FastMCPScala] - Registering Prompt: ${promptDef.name}")
      val javaPrompt = PromptDefinition.toJava(promptDef)
      val promptSpec = new McpServerFeatures.AsyncPromptSpecification(
        javaPrompt,
        javaPromptHandler(promptDef.name)
      )
      serverBuilder.prompts(
        promptSpec
      ) // Note: .prompts() might take a list or varargs depending on the Java library version
    }

    // --- Build Server ---
    // Build the McpAsyncServer
    underlyingJavaServer = Some(serverBuilder.build())
    JSystem.err.println(s"[FastMCPScala] MCP Server '$name' configured.")

  /** Creates a Java BiFunction handler for a specific static resource URI. Returns a Mono that
    * completes with the result.
    */
  private def javaStaticResourceReadHandler(
      registeredUri: String
  ): java.util.function.BiFunction[McpAsyncServerExchange, McpSchema.ReadResourceRequest, Mono[
    McpSchema.ReadResourceResult
  ]] =
    (exchange, request) => {
      val handlerOpt: Option[ResourceHandler] = resourceManager.getResourceHandler(registeredUri)
      handlerOpt match {
        case Some(handler) =>
          // Execute the ZIO effect and convert to Mono
          val contentEffect: ZIO[Any, Throwable, String | Array[Byte]] = handler()
          val finalEffect: ZIO[Any, Throwable, McpSchema.ReadResourceResult] = contentEffect
            .flatMap { content =>
              val mimeTypeOpt =
                resourceManager.getResourceDefinition(registeredUri).flatMap(_.mimeType)
              createReadResourceResult(registeredUri, content, mimeTypeOpt)
            }
            .catchAll(e =>
              ZIO.fail(
                new RuntimeException(
                  s"Error executing static resource handler for $registeredUri",
                  e
                )
              )
            )

          // Convert the final ZIO effect to Mono using the helper
          zioToMono(finalEffect)

        case None =>
          Mono.error(
            new RuntimeException(s"Static resource handler not found for URI: $registeredUri")
          )
      }
    }

  // --- Server Lifecycle Methods ---

  /** Helper to convert Scala result types into McpSchema.ReadResourceResult.
    */
  private def createReadResourceResult(
      uri: String,
      content: String | Array[Byte],
      mimeTypeOpt: Option[String]
  ): ZIO[Any, Throwable, McpSchema.ReadResourceResult] = ZIO.attempt {
    val finalMimeType = mimeTypeOpt.getOrElse(content match {
      case s: String
          if (s.startsWith("{") && s.endsWith("}")) || (s.startsWith("[") && s.endsWith("]")) =>
        "application/json"
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

  /** Converts a ZIO effect to a Reactor Mono. Executes the ZIO effect asynchronously and bridges
    * the result/error to the MonoSink.
    */
  // Visible within server package so tests can exercise directly without reflection.
  private[server] def zioToMono[A](effect: ZIO[Any, Throwable, A]): Mono[A] = {
    Mono.create { sink =>
      Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.runToFuture(effect).onComplete {
          case Success(value) => sink.success(value)
          case Failure(error) => sink.error(error)
        }
      }
    }
  }

  /** Converts a ZIO effect with potential Throwable error to a Reactor Mono with MCP error
    * handling. Errors are mapped to McpSchema.CallToolResult with isError flag set.
    */
  private[server] def zioToMonoWithErrorHandling[A](
      effect: ZIO[Any, Throwable, A],
      resultTransform: A => McpSchema.CallToolResult
  ): Mono[McpSchema.CallToolResult] = {
    Mono.create { sink =>
      Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe
          .runToFuture(
            effect.fold(
              // Error case - map to CallToolResult with isError=true
              error => ErrorMapper.toCallToolResult(error),
              // Success case - transform the result
              success => resultTransform(success)
            )
          )
          .onComplete {
            case Success(result) => sink.success(result)
            case Failure(error) =>
              // This should generally not happen since we handled errors in the fold,
              // but just in case there's an error during the error mapping itself
              JSystem.err.println(
                s"[FastMCPScala] Unexpected error in zioToMonoWithErrorHandling: ${error.getMessage}"
              )
              sink.error(error)
          }
      }
    }
  }

  /** Convert Scala ZIO handler to Java BiFunction for prompt handling. Returns a Mono that
    * completes with the result.
    */
  private def javaPromptHandler(
      promptName: String
  ): java.util.function.BiFunction[McpAsyncServerExchange, McpSchema.GetPromptRequest, Mono[
    McpSchema.GetPromptResult
  ]] =
    (exchange, request) => {
      val scalaArgs = Option(request.arguments())
        .map(_.asScala.toMap.asInstanceOf[Map[String, Any]])
        .getOrElse(Map.empty)
      val context = McpContext(Some(exchange))

      val messagesEffect: ZIO[Any, Throwable, List[Message]] =
        promptManager.getPrompt(promptName, scalaArgs, Some(context))

      val finalEffect: ZIO[Any, Throwable, McpSchema.GetPromptResult] = messagesEffect.map {
        messages =>
          val javaMessages = messages.map(Message.toJava).asJava
          val description = promptManager
            .getPromptDefinition(promptName)
            .flatMap(_.description)
            .getOrElse(s"Prompt: $promptName")

          new McpSchema.GetPromptResult(description, javaMessages)
      }

      // Convert the final ZIO effect to Mono using the helper
      zioToMono(finalEffect)
    }

  // --- Private Helper Methods ---

  /** Convert Scala ZIO handler to Java BiFunction for SyncServer Tool handling. IMPORTANT: This
    * method assumes the ToolHandler returns a type that can be directly converted to
    * McpSchema.Content (String, Array[Byte], Content, List[Content]) or a String representation. It
    * no longer attempts generic JSON serialization for arbitrary Products or Maps due to lack of
    * implicit encoders. ToolHandlers returning complex types should serialize them to JSON String
    * *within* the handler. Returns a Mono that completes with the result.
    */
  private def javaToolHandler(
      toolName: String
  ): java.util.function.BiFunction[McpAsyncServerExchange, java.util.Map[String, Object], Mono[
    McpSchema.CallToolResult
  ]] =
    (exchange, args) => {
      val scalaArgs = args.asScala.toMap.asInstanceOf[Map[String, Any]]
      val context = McpContext(Some(exchange))

      // Execute the user-provided ToolHandler
      val resultEffect: ZIO[Any, Throwable, Any] =
        toolManager.callTool(toolName, scalaArgs, Some(context))

      // Create a transform function to convert the result to McpSchema.CallToolResult
      val transformResult: Any => McpSchema.CallToolResult = { result =>
        // Convert the result to Java McpSchema.Content list
        val contentList: java.util.List[McpSchema.Content] = result match {
          case s: String =>
            List(new McpSchema.TextContent(null, null, s)).asJava
          case bytes: Array[Byte] =>
            val base64Data = java.util.Base64.getEncoder.encodeToString(bytes)
            List(
              new McpSchema.ImageContent(null, null, base64Data, "application/octet-stream")
            ).asJava
          case c: Content =>
            List(c.toJava).asJava
          case lst: List[?] if lst.nonEmpty && lst.head.isInstanceOf[Content] =>
            lst.asInstanceOf[List[Content]].map(_.toJava).asJava
          case null =>
            JSystem.err.println(
              s"[FastMCPScala] Warning: Tool handler for '$toolName' returned null."
            )
            List.empty[McpSchema.Content].asJava
          case other =>
            JSystem.err.println(
              s"[FastMCPScala] Warning: Tool handler for '$toolName' returned type ${other.getClass.getName}, using toString representation."
            )
            List(new McpSchema.TextContent(null, null, other.toString)).asJava
        }
        // Construct the final result for the MCP protocol
        new McpSchema.CallToolResult(contentList, false) // false for isError
      }

      // Convert the ZIO effect to Mono using the helper that handles errors
      zioToMonoWithErrorHandling(resultEffect, transformResult)
    }

end FastMcpServer

/** Companion object for FastMCPScala
  */
object FastMcpServer:

  /** Create a new FastMCPScala instance and run it with stdio transport
    */
  def stdio(
      name: String = "FastMCPScala",
      version: String = "0.1.0",
      settings: FastMcpServerSettings = FastMcpServerSettings()
  ): ZIO[Any, Throwable, Unit] =
    ZIO.succeed(apply(name, version, settings)).flatMap(_.runStdio())

  /** Create a new FastMCPScala instance with the given settings
    */
  def apply(
      name: String = "FastMCPScala",
      version: String = "0.1.0",
      settings: FastMcpServerSettings = FastMcpServerSettings()
  ): FastMcpServer =
    new FastMcpServer(name, version, settings) // comment

end FastMcpServer
