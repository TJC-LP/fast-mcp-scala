package com.tjclp.fastmcp
package server

import java.io.FilterInputStream
import java.lang.System as JSystem

import scala.concurrent.ExecutionContext.Implicits.global
import scala.jdk.CollectionConverters.*
import scala.util.Failure
import scala.util.Success

import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.server.McpAsyncServer
import io.modelcontextprotocol.server.McpAsyncServerExchange
import io.modelcontextprotocol.server.McpServer as JavaMcpServer
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.server.McpStatelessAsyncServer
import io.modelcontextprotocol.server.McpStatelessServerFeatures
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpServerTransportProvider
import io.modelcontextprotocol.spec.McpStatelessServerTransport
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider
import reactor.core.publisher.Mono
import zio.*
import zio.http.Server as ZHttpServer

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.core.JvmToolInputSchemaSupport.*
import com.tjclp.fastmcp.core.TypeConversions.*
import com.tjclp.fastmcp.macros.JacksonConversionContext
import com.tjclp.fastmcp.server.manager.ContextualToolHandler
import com.tjclp.fastmcp.server.manager.PromptHandler
import com.tjclp.fastmcp.server.manager.PromptManager
import com.tjclp.fastmcp.server.manager.ResourceHandler
import com.tjclp.fastmcp.server.manager.ResourceManager
import com.tjclp.fastmcp.server.manager.ResourceTemplateHandler
import com.tjclp.fastmcp.server.manager.ToolManager
import com.tjclp.fastmcp.server.manager.ToolRegistrationOptions
import com.tjclp.fastmcp.server.manager.ResourceConversions.*
import com.tjclp.fastmcp.server.transport.ZioHttpStatelessTransport
import com.tjclp.fastmcp.server.transport.ZioHttpStreamableTransportProvider

/** Main server class for FastMCP-Scala
  *
  * This class provides a high-level API for creating MCP servers using Scala and ZIO
  */
@SuppressWarnings(Array("org.wartremover.warts.Null"))
class FastMcpServer(
    val name: String = "FastMCPScala",
    version: String = "0.1.0",
    settings: FastMcpServerSettings = FastMcpServerSettings()
) extends com.tjclp.fastmcp.server.McpServer:
  val dependencies: List[String] = settings.dependencies
  protected val decodeContext: McpDecodeContext = JacksonConversionContext.default
  // Initialize managers
  val toolManager = new ToolManager()
  val resourceManager = new ResourceManager()
  val promptManager = new PromptManager()

  // Placeholder for the Java MCP Server instances
  private var underlyingJavaServer: Option[McpAsyncServer] = None
  private var underlyingStatelessServer: Option[McpStatelessAsyncServer] = None

  // --- Private Java Handler Converters ---

  /** Register a tool with the server
    */
  override def tool(
      definition: ToolDefinition,
      handler: ContextualToolHandler,
      options: ToolRegistrationOptions
  ): ZIO[Any, Throwable, FastMcpServer] =
    toolManager.addTool(definition.name, handler, definition, options).as(this)

  override def tool(
      definition: ToolDefinition,
      handler: ContextualToolHandler
  ): ZIO[Any, Throwable, FastMcpServer] =
    tool(definition, handler, ToolRegistrationOptions())

  override def tool(
      name: String,
      handler: ContextualToolHandler,
      description: Option[String] = None,
      inputSchema: ToolInputSchema = ToolInputSchema.default,
      options: ToolRegistrationOptions = ToolRegistrationOptions(),
      annotations: Option[ToolAnnotations] = None
  ): ZIO[Any, Throwable, FastMcpServer] =
    tool(
      definition = ToolDefinition(
        name = name,
        description = description,
        inputSchema = inputSchema,
        annotations = annotations
      ),
      handler = handler,
      options = options
    )

  def tool(
      name: String,
      handler: ContextualToolHandler,
      description: Option[String],
      inputSchema: Either[McpSchema.JsonSchema, String],
      options: ToolRegistrationOptions,
      annotations: Option[ToolAnnotations]
  ): ZIO[Any, Throwable, FastMcpServer] =
    tool(
      name = name,
      handler = handler,
      description = description,
      inputSchema = fromEither(inputSchema),
      options = options,
      annotations = annotations
    )

  /** Register a **static** resource with the server.
    */
  override def resource(
      definition: ResourceDefinition,
      handler: ResourceHandler
  ): ZIO[Any, Throwable, FastMcpServer] =
    resourceManager.addStaticResource(definition.uri, handler, definition).as(this)

  override def resource(
      uri: String,
      handler: ResourceHandler, // () => ZIO[Any, Throwable, String | Array[Byte]]
      name: Option[String] = None,
      description: Option[String] = None,
      mimeType: Option[String] = Some("text/plain") // Default mimeType here
  ): ZIO[Any, Throwable, FastMcpServer] = {
    resource(
      definition = ResourceDefinition(
        uri = uri,
        name = name,
        description = description,
        mimeType = mimeType,
        isTemplate = false,
        arguments = None
      ),
      handler = handler
    )
  }

  /** Register a **templated** resource with the server.
    */
  override def resourceTemplate(
      definition: ResourceDefinition,
      handler: ResourceTemplateHandler
  ): ZIO[Any, Throwable, FastMcpServer] =
    resourceManager.addTemplateResource(definition.uri, handler, definition).as(this)

  override def resourceTemplate(
      uriPattern: String,
      handler: ResourceTemplateHandler, // Map[String, String] => ZIO[Any, Throwable, String | Array[Byte]]
      name: Option[String] = None,
      description: Option[String] = None,
      mimeType: Option[String] = Some("text/plain"), // Default mimeType here
      arguments: Option[List[ResourceArgument]] = None // Arguments might be passed by macro
  ): ZIO[Any, Throwable, FastMcpServer] = {
    resourceTemplate(
      definition = ResourceDefinition(
        uri = uriPattern,
        name = name,
        description = description,
        mimeType = mimeType,
        isTemplate = true,
        arguments = arguments
      ),
      handler = handler
    )
  }

  /** Register a prompt with the server
    */
  override def prompt(
      definition: PromptDefinition,
      handler: PromptHandler
  ): ZIO[Any, Throwable, FastMcpServer] =
    promptManager.addPrompt(definition.name, handler, definition).as(this)

  override def prompt(
      name: String,
      handler: PromptHandler,
      description: Option[String] = None,
      arguments: Option[List[PromptArgument]] = None
  ): ZIO[Any, Throwable, FastMcpServer] =
    prompt(
      definition = PromptDefinition(name, description, arguments),
      handler = handler
    )

  // --- Public Registration Methods ---

  def listTools(): ZIO[Any, Throwable, McpSchema.ListToolsResult] =
    ZIO.succeed {
      val tools = toolManager.listDefinitions().map(_.toJava).asJava
      new McpSchema.ListToolsResult(tools, null)
    }

  def listResources(): ZIO[Any, Throwable, McpSchema.ListResourcesResult] =
    ZIO.succeed {
      val javaResources = resourceManager
        .listDefinitions()
        .filter(!_.isTemplate) // Only include static resources
        .map(_.toJava)
        .collect { case res: McpSchema.Resource => res }
        .asJava
      new McpSchema.ListResourcesResult(javaResources, null)
    }

  def listPrompts(): ZIO[Any, Throwable, McpSchema.ListPromptsResult] =
    ZIO.succeed {
      val prompts = promptManager.listDefinitions().map(_.toJava).asJava
      new McpSchema.ListPromptsResult(prompts, null)
    }

  def listResourceTemplates(): ZIO[Any, Throwable, McpSchema.ListResourceTemplatesResult] =
    ZIO.succeed {
      val templates = resourceManager
        .listTemplateDefinitions()
        .map(_.toJava)
        .collect { case template: McpSchema.ResourceTemplate => template }
        .asJava
      new McpSchema.ListResourceTemplatesResult(templates, null)
    }

  /** Run the server with the specified transport
    */
  def run(transport: String = "stdio"): ZIO[Any, Throwable, Unit] =
    transport.toLowerCase match
      case "stdio" => runStdio()
      case "http" => runHttp()
      case _ => ZIO.fail(new IllegalArgumentException(s"Unsupported transport: $transport"))

  // --- Public Listing Methods ---

  /** Run the server with stdio transport. Completes when stdin reaches EOF or the fiber is
    * interrupted, allowing clean shutdown when an MCP client closes the subprocess stdin.
    */
  def runStdio(): ZIO[Any, Throwable, Unit] =
    ZIO.scoped {
      for {
        stdinClosed <- Promise.make[Nothing, Unit]
        wrappedIn = new FilterInputStream(JSystem.in) {
          override def read(): Int = {
            val b = super.read()
            if (b == -1) signalEof(stdinClosed)
            b
          }
          override def read(buf: Array[Byte], off: Int, len: Int): Int = {
            val n = super.read(buf, off, len)
            if (n == -1) signalEof(stdinClosed)
            n
          }
          private def signalEof(p: Promise[Nothing, Unit]): Unit =
            Unsafe.unsafe { implicit unsafe =>
              val _ = Runtime.default.unsafe.run(p.succeed(())).getOrThrowFiberFailure()
            }
        }
        _ <- ZIO.acquireRelease(
          for {
            jsonMapper <- ZIO.attempt(McpJsonDefaults.getMapper())
            provider <- ZIO.attempt(
              new StdioServerTransportProvider(jsonMapper, wrappedIn, JSystem.out)
            )
            _ <- ZIO.attempt(setupServer(provider))
            _ <- ZIO.attempt(
              JSystem.err.println(
                s"[FastMCPScala] '$name' running on stdio – will exit on stdin EOF or Ctrl-C."
              )
            )
          } yield ()
        )(_ => ZIO.attempt(underlyingJavaServer.foreach(_.close())).orDie)
        _ <- stdinClosed.await
      } yield ()
    }

  /** Run the server with HTTP transport via zio-http.
    *
    * When `settings.stateless` is true, uses stateless transport (no sessions, no SSE). When false
    * (default), uses streamable transport with session management and SSE streaming.
    */
  def runHttp(): ZIO[Any, Throwable, Unit] =
    if settings.stateless then runStatelessHttp()
    else runStreamableHttp()

  /** Run the server with stateless HTTP transport via zio-http.
    *
    * Each HTTP POST to the configured endpoint is independently dispatched to the Java SDK's
    * McpStatelessServerHandler. No session state is maintained between requests.
    */
  private def runStatelessHttp(): ZIO[Any, Throwable, Unit] =
    ZIO.scoped {
      for {
        jsonMapper <- ZIO.attempt(McpJsonDefaults.getMapper())
        transport = new ZioHttpStatelessTransport(jsonMapper, settings.httpEndpoint)
        _ <- ZIO.attempt(setupStatelessServer(transport))
        _ <- ZIO.attempt(
          JSystem.err.println(
            s"[FastMCPScala] '$name' running stateless HTTP on http://${settings.host}:${settings.port}${settings.httpEndpoint}"
          )
        )
        _ <- ZIO.acquireRelease(ZIO.unit)(_ =>
          ZIO.attempt(underlyingStatelessServer.foreach(_.close())).orDie
        )
        _ <- ZHttpServer
          .serve(transport.routes)
          .provideLayer(
            ZHttpServer.defaultWith(config => config.binding(settings.host, settings.port))
          )
      } yield ()
    }

  /** Run the server with Streamable HTTP transport via zio-http.
    *
    * Provides full MCP Streamable HTTP support: session management via `mcp-session-id` header, SSE
    * streaming for server-to-client messages, and POST/GET/DELETE endpoint handling.
    */
  private def runStreamableHttp(): ZIO[Any, Throwable, Unit] =
    ZIO.scoped {
      for {
        jsonMapper <- ZIO.attempt(McpJsonDefaults.getMapper())
        provider = new ZioHttpStreamableTransportProvider(
          jsonMapper,
          settings.httpEndpoint,
          settings.disallowDelete,
          settings.keepAliveInterval
        )
        _ <- ZIO.attempt(setupStreamableServer(provider))
        _ <- ZIO.attempt(
          JSystem.err.println(
            s"[FastMCPScala] '$name' running streamable HTTP on http://${settings.host}:${settings.port}${settings.httpEndpoint}"
          )
        )
        _ <- ZIO.acquireRelease(ZIO.unit)(_ =>
          ZIO.attempt(underlyingJavaServer.foreach(_.close())).orDie
        )
        _ <- ZHttpServer
          .serve(provider.routes)
          .provideLayer(
            ZHttpServer.defaultWith(config => config.binding(settings.host, settings.port))
          )
      } yield ()
    }

  /** Set up the Java MCP Server with the given transport provider
    */
  def setupServer(transportProvider: McpServerTransportProvider): Unit =
    val serverBuilder: JavaMcpServer.AsyncSpecification[?] = JavaMcpServer
      .async(transportProvider)
    configureServerBuilder(serverBuilder)
    underlyingJavaServer = Some(serverBuilder.build())
    JSystem.err.println(s"[FastMCPScala] MCP Server '$name' configured.")

  /** Set up a streamable MCP server for Streamable HTTP transport.
    *
    * Uses the SDK's streamable builder which supports session management, SSE streaming, and
    * bidirectional communication via [[McpStreamableServerTransportProvider]].
    */
  private[server] def setupStreamableServer(
      transportProvider: McpStreamableServerTransportProvider
  ): Unit =
    val serverBuilder: JavaMcpServer.AsyncSpecification[?] = JavaMcpServer
      .async(transportProvider)
    configureServerBuilder(serverBuilder)
    underlyingJavaServer = Some(serverBuilder.build())
    JSystem.err.println(s"[FastMCPScala] Streamable MCP Server '$name' configured.")

  /** Shared builder configuration for tool/resource/prompt registration.
    *
    * Used by both [[setupServer]] (stdio) and [[setupStreamableServer]] (streamable HTTP). Both use
    * [[McpAsyncServerExchange]]-based handlers.
    */
  private def configureServerBuilder(
      serverBuilder: JavaMcpServer.AsyncSpecification[?]
  ): Unit =
    serverBuilder.serverInfo(name, version)

    // --- Capabilities Setup ---
    serverBuilder.capabilities(buildCapabilities())

    // --- Tool Registration ---
    val tools = toolManager.listDefinitions()
    JSystem.err.println(s"[FastMCPScala] Registering ${tools.size} tools with the MCP server:")
    tools.foreach { toolDef =>
      JSystem.err.println(s"[FastMCPScala] - Registering Tool: ${toolDef.name}")
      serverBuilder.toolCall(toolDef.toJava, javaToolCallHandler(toolDef.name))
    }

    // --- Resource and Template Registration with Java Server ---
    // Register static resources and templates separately
    val defs = resourceManager.listDefinitions()
    // Treat any URI containing '{' or '}' as a template regardless of flags
    val staticResources =
      defs.filter(d => !d.isTemplate && !d.uri.exists(ch => ch == '{' || ch == '}'))
    val templateResources =
      defs.filter(d => d.isTemplate || d.uri.exists(ch => ch == '{' || ch == '}'))

    JSystem.err.println(
      s"[FastMCPScala] Processing ${staticResources.size} static resources and ${templateResources.size} resource templates..."
    )

    // Register static resources
    if (staticResources.nonEmpty) {
      val resourceSpecs = new java.util.ArrayList[McpServerFeatures.AsyncResourceSpecification]()

      staticResources.foreach { resDef =>
        JSystem.err.println(
          s"[FastMCPScala] - Processing static resource: ${resDef.uri}"
        )

        resDef.toJava match {
          case resource: McpSchema.Resource =>
            val spec = new McpServerFeatures.AsyncResourceSpecification(
              resource,
              javaStaticResourceReadHandler(resDef.uri)
            )
            resourceSpecs.add(spec)
          case _ =>
            JSystem.err.println(
              s"[FastMCPScala]   - Warning: ResourceDefinition marked as static but did not convert to Resource: ${resDef.uri}"
            )
        }
      }

      serverBuilder.resources(resourceSpecs)
      JSystem.err.println(
        s"[FastMCPScala] Registered ${resourceSpecs.size()} static resources with Java server"
      )
    }

    // Register resource templates
    if (templateResources.nonEmpty) {
      JSystem.err.println(
        s"[FastMCPScala] Registering ${templateResources.size} resource templates..."
      )
      val javaTemplateSpecs = templateResources
        .map { resDef =>
          JSystem.err.println(s"[FastMCPScala] - Processing resource template: ${resDef.uri}")
          val template = resDef.toJava match {
            case t: McpSchema.ResourceTemplate => t
            case _ => null
          }
          if (template != null) {
            new McpServerFeatures.AsyncResourceTemplateSpecification(
              template,
              javaTemplateResourceReadHandler(resDef.uri)
            )
          } else null
        }
        .filter(_ != null)
        .asJava
      serverBuilder.resourceTemplates(javaTemplateSpecs)
      JSystem.err.println(
        s"[FastMCPScala] Registered ${javaTemplateSpecs.size()} resource templates"
      )
    }

    // --- Prompt Registration ---
    JSystem.err.println(
      s"[FastMCPScala] Registering ${promptManager.listDefinitions().size} prompts..."
    )
    promptManager.listDefinitions().foreach { promptDef =>
      JSystem.err.println(s"[FastMCPScala] - Registering Prompt: ${promptDef.name}")
      val javaPrompt = promptDef.toJava
      val promptSpec = new McpServerFeatures.AsyncPromptSpecification(
        javaPrompt,
        javaPromptHandler(promptDef.name)
      )
      serverBuilder.prompts(promptSpec)
    }

  /** Set up a stateless MCP server for HTTP transport.
    *
    * Uses the SDK's stateless builder which takes [[McpTransportContext]] instead of
    * [[McpAsyncServerExchange]] in handler BiFunction signatures.
    */
  private[server] def setupStatelessServer(
      transport: McpStatelessServerTransport
  ): Unit =
    val serverBuilder = JavaMcpServer
      .async(transport)
      .serverInfo(name, version)

    // --- Capabilities Setup ---
    serverBuilder.capabilities(buildCapabilities())

    // --- Tool Registration ---
    val tools = toolManager.listDefinitions()
    JSystem.err.println(
      s"[FastMCPScala] Registering ${tools.size} tools with stateless MCP server:"
    )
    tools.foreach { toolDef =>
      JSystem.err.println(s"[FastMCPScala] - Registering Tool: ${toolDef.name}")
      serverBuilder.toolCall(
        toolDef.toJava,
        statelessToolCallHandler(toolDef.name)
      )
    }

    // --- Resource and Template Registration ---
    val defs = resourceManager.listDefinitions()
    val staticResources =
      defs.filter(d => !d.isTemplate && !d.uri.exists(ch => ch == '{' || ch == '}'))
    val templateResources =
      defs.filter(d => d.isTemplate || d.uri.exists(ch => ch == '{' || ch == '}'))

    JSystem.err.println(
      s"[FastMCPScala] Processing ${staticResources.size} static resources and ${templateResources.size} resource templates..."
    )

    // Register static resources
    if (staticResources.nonEmpty) {
      val resourceSpecs =
        new java.util.ArrayList[McpStatelessServerFeatures.AsyncResourceSpecification]()

      staticResources.foreach { resDef =>
        JSystem.err.println(s"[FastMCPScala] - Processing static resource: ${resDef.uri}")
        resDef.toJava match {
          case resource: McpSchema.Resource =>
            val spec = new McpStatelessServerFeatures.AsyncResourceSpecification(
              resource,
              statelessStaticResourceReadHandler(resDef.uri)
            )
            resourceSpecs.add(spec)
          case _ =>
            JSystem.err.println(
              s"[FastMCPScala]   - Warning: ResourceDefinition marked as static but did not convert to Resource: ${resDef.uri}"
            )
        }
      }

      serverBuilder.resources(resourceSpecs)
      JSystem.err.println(
        s"[FastMCPScala] Registered ${resourceSpecs.size()} static resources with stateless server"
      )
    }

    // Register resource templates
    if (templateResources.nonEmpty) {
      JSystem.err.println(
        s"[FastMCPScala] Registering ${templateResources.size} resource templates..."
      )
      val javaTemplateSpecs = templateResources
        .map { resDef =>
          JSystem.err.println(s"[FastMCPScala] - Processing resource template: ${resDef.uri}")
          val template = resDef.toJava match {
            case t: McpSchema.ResourceTemplate => t
            case _ => null
          }
          if (template != null) {
            new McpStatelessServerFeatures.AsyncResourceTemplateSpecification(
              template,
              statelessTemplateResourceReadHandler(resDef.uri)
            )
          } else null
        }
        .filter(_ != null)
        .asJava
      serverBuilder.resourceTemplates(javaTemplateSpecs)
      JSystem.err.println(
        s"[FastMCPScala] Registered ${javaTemplateSpecs.size()} resource templates"
      )
    }

    // --- Prompt Registration ---
    JSystem.err.println(
      s"[FastMCPScala] Registering ${promptManager.listDefinitions().size} prompts..."
    )
    promptManager.listDefinitions().foreach { promptDef =>
      JSystem.err.println(s"[FastMCPScala] - Registering Prompt: ${promptDef.name}")
      val javaPrompt = promptDef.toJava
      val promptSpec = new McpStatelessServerFeatures.AsyncPromptSpecification(
        javaPrompt,
        statelessPromptHandler(promptDef.name)
      )
      serverBuilder.prompts(promptSpec)
    }

    // --- Build Server ---
    underlyingStatelessServer = Some(serverBuilder.build())
    JSystem.err.println(s"[FastMCPScala] Stateless MCP Server '$name' configured.")

  /** Build server capabilities from registered tools/resources/prompts. Shared by both stateful and
    * stateless setup methods.
    */
  private def buildCapabilities(): McpSchema.ServerCapabilities =
    val toolCapabilities =
      if (toolManager.listDefinitions().nonEmpty)
        new McpSchema.ServerCapabilities.ToolCapabilities(true)
      else null
    val resourceCapabilities =
      if (resourceManager.listDefinitions().nonEmpty)
        new McpSchema.ServerCapabilities.ResourceCapabilities(
          /* list */ true,
          /* templates endpoint */ settings.exposeTemplatesEndpoint
        )
      else null
    val promptCapabilities =
      if (promptManager.listDefinitions().nonEmpty)
        new McpSchema.ServerCapabilities.PromptCapabilities(true)
      else null
    val loggingCapabilities = new McpSchema.ServerCapabilities.LoggingCapabilities()

    new McpSchema.ServerCapabilities(
      null,
      null, // experimental
      loggingCapabilities,
      promptCapabilities,
      resourceCapabilities,
      toolCapabilities
    )

  /** Creates a Java BiFunction handler for a specific static resource URI. Returns a Mono that
    * completes with the result.
    */
  private def javaStaticResourceReadHandler(
      registeredUri: String
  ): java.util.function.BiFunction[McpAsyncServerExchange, McpSchema.ReadResourceRequest, Mono[
    McpSchema.ReadResourceResult
  ]] =
    (_, _) => {
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

  // Note: Template read handlers are not bound via resources() in 0.11.x.
  // When SDK exposes a template+handler API, bind handlers there.

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

  /** Creates a Java BiFunction handler for template resources. The Java server will call this
    * handler when a URI matches the template pattern.
    */
  private def javaTemplateResourceReadHandler(
      templatePattern: String
  ): java.util.function.BiFunction[McpAsyncServerExchange, McpSchema.ReadResourceRequest, Mono[
    McpSchema.ReadResourceResult
  ]] =
    (_, request) => {
      val requestedUri = request.uri()
      if requestedUri != null && (requestedUri.contains("{") || requestedUri.contains("}")) then
        // Prevent reading unresolved template URIs (e.g. the literal pattern from listResources)
        val paramRegex = """\{([^{}]+)\}""".r
        val params = paramRegex.findAllMatchIn(templatePattern).map(_.group(1)).toList
        val msg =
          if params.nonEmpty then
            s"Template URI not resolved; provide values for: ${params.mkString(", ")}"
          else "Template URI not resolved; provide parameter values"
        Mono.error(new IllegalArgumentException(msg))
      else {
        resourceManager.getTemplateHandler(templatePattern) match
          case Some(handler) => {
            val templateManager =
              new io.modelcontextprotocol.util.DefaultMcpUriTemplateManagerFactory()
                .create(templatePattern)
            val params =
              templateManager.extractVariableValues(requestedUri).asScala.toMap

            val contentEffect = handler(params)
            val finalEffect: ZIO[Any, Throwable, McpSchema.ReadResourceResult] = contentEffect
              .flatMap { content =>
                val mimeTypeOpt = resourceManager
                  .listDefinitions()
                  .find(d =>
                    (d.isTemplate || d.uri
                      .exists(ch => ch == '{' || ch == '}')) && d.uri == templatePattern
                  )
                  .flatMap(_.mimeType)
                createReadResourceResult(requestedUri, content, mimeTypeOpt)
              }
              .catchAll(e =>
                ZIO.fail(
                  new RuntimeException(
                    s"Error executing template resource handler for $requestedUri (pattern: $templatePattern)",
                    e
                  )
                )
              )

            zioToMono(finalEffect)
          }
          case None =>
            Mono.error(
              new RuntimeException(
                s"Template resource handler not found for pattern: $templatePattern"
              )
            )
      }
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
      val context = JvmMcpContext(Some(exchange))

      val messagesEffect: ZIO[Any, Throwable, List[Message]] =
        promptManager.getPrompt(promptName, scalaArgs, Some(context))

      val finalEffect: ZIO[Any, Throwable, McpSchema.GetPromptResult] = messagesEffect.map {
        messages =>
          val javaMessages = messages.map(_.toJava).asJava
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
  private def javaToolCallHandler(
      toolName: String
  ): java.util.function.BiFunction[McpAsyncServerExchange, McpSchema.CallToolRequest, Mono[
    McpSchema.CallToolResult
  ]] =
    (exchange, request) => {
      val scalaArgs = Option(request.arguments())
        .map(_.asScala.toMap.asInstanceOf[Map[String, Any]])
        .getOrElse(Map.empty)
      val context = JvmMcpContext(javaExchange = Some(exchange))

      val resultEffect: ZIO[Any, Throwable, Any] =
        toolManager.callTool(toolName, scalaArgs, Some(context))

      zioToMonoWithErrorHandling(resultEffect, transformToolResult(toolName))
    }

  // --- Stateless Handler Converters (McpTransportContext-based) ---

  /** Stateless tool call handler - same logic as javaToolCallHandler but takes McpTransportContext.
    */
  private def statelessToolCallHandler(
      toolName: String
  ): java.util.function.BiFunction[McpTransportContext, McpSchema.CallToolRequest, Mono[
    McpSchema.CallToolResult
  ]] =
    (ctx, request) => {
      val scalaArgs = Option(request.arguments())
        .map(_.asScala.toMap.asInstanceOf[Map[String, Any]])
        .getOrElse(Map.empty)
      val context = JvmMcpContext(transportContext = Some(ctx))

      val resultEffect: ZIO[Any, Throwable, Any] =
        toolManager.callTool(toolName, scalaArgs, Some(context))

      zioToMonoWithErrorHandling(resultEffect, transformToolResult(toolName))
    }

  /** Stateless static resource read handler.
    */
  private def statelessStaticResourceReadHandler(
      registeredUri: String
  ): java.util.function.BiFunction[McpTransportContext, McpSchema.ReadResourceRequest, Mono[
    McpSchema.ReadResourceResult
  ]] =
    (_, _) => {
      resourceManager.getResourceHandler(registeredUri) match {
        case Some(handler) =>
          val finalEffect: ZIO[Any, Throwable, McpSchema.ReadResourceResult] = handler()
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
          zioToMono(finalEffect)

        case None =>
          Mono.error(
            new RuntimeException(s"Static resource handler not found for URI: $registeredUri")
          )
      }
    }

  /** Stateless template resource read handler.
    */
  private def statelessTemplateResourceReadHandler(
      templatePattern: String
  ): java.util.function.BiFunction[McpTransportContext, McpSchema.ReadResourceRequest, Mono[
    McpSchema.ReadResourceResult
  ]] =
    (_, request) => {
      val requestedUri = request.uri()
      if requestedUri != null && (requestedUri.contains("{") || requestedUri.contains("}")) then
        val paramRegex = """\{([^{}]+)\}""".r
        val params = paramRegex.findAllMatchIn(templatePattern).map(_.group(1)).toList
        val msg =
          if params.nonEmpty then
            s"Template URI not resolved; provide values for: ${params.mkString(", ")}"
          else "Template URI not resolved; provide parameter values"
        Mono.error(new IllegalArgumentException(msg))
      else {
        resourceManager.getTemplateHandler(templatePattern) match
          case Some(handler) => {
            val templateManager =
              new io.modelcontextprotocol.util.DefaultMcpUriTemplateManagerFactory()
                .create(templatePattern)
            val params =
              templateManager.extractVariableValues(requestedUri).asScala.toMap

            val contentEffect = handler(params)
            val finalEffect: ZIO[Any, Throwable, McpSchema.ReadResourceResult] = contentEffect
              .flatMap { content =>
                val mimeTypeOpt = resourceManager
                  .listDefinitions()
                  .find(d =>
                    (d.isTemplate || d.uri
                      .exists(ch => ch == '{' || ch == '}')) && d.uri == templatePattern
                  )
                  .flatMap(_.mimeType)
                createReadResourceResult(requestedUri, content, mimeTypeOpt)
              }
              .catchAll(e =>
                ZIO.fail(
                  new RuntimeException(
                    s"Error executing template resource handler for $requestedUri (pattern: $templatePattern)",
                    e
                  )
                )
              )

            zioToMono(finalEffect)
          }
          case None =>
            Mono.error(
              new RuntimeException(
                s"Template resource handler not found for pattern: $templatePattern"
              )
            )
      }
    }

  /** Stateless prompt handler.
    */
  private def statelessPromptHandler(
      promptName: String
  ): java.util.function.BiFunction[McpTransportContext, McpSchema.GetPromptRequest, Mono[
    McpSchema.GetPromptResult
  ]] =
    (ctx, request) => {
      val scalaArgs = Option(request.arguments())
        .map(_.asScala.toMap.asInstanceOf[Map[String, Any]])
        .getOrElse(Map.empty)
      val context = JvmMcpContext(transportContext = Some(ctx))

      val messagesEffect: ZIO[Any, Throwable, List[Message]] =
        promptManager.getPrompt(promptName, scalaArgs, Some(context))

      val finalEffect: ZIO[Any, Throwable, McpSchema.GetPromptResult] = messagesEffect.map {
        messages =>
          val javaMessages = messages.map(_.toJava).asJava
          val description = promptManager
            .getPromptDefinition(promptName)
            .flatMap(_.description)
            .getOrElse(s"Prompt: $promptName")

          new McpSchema.GetPromptResult(description, javaMessages)
      }

      zioToMono(finalEffect)
    }

  /** Shared transform function for converting tool results to McpSchema.CallToolResult.
    */
  private def transformToolResult(toolName: String): Any => McpSchema.CallToolResult = { result =>
    val contentList: java.util.List[McpSchema.Content] = result match {
      case s: String =>
        val ann = new McpSchema.Annotations(null, null)
        List(new McpSchema.TextContent(ann, s)).asJava
      case bytes: Array[Byte] =>
        val base64Data = java.util.Base64.getEncoder.encodeToString(bytes)
        val ann = new McpSchema.Annotations(null, null)
        List(new McpSchema.ImageContent(ann, base64Data, "application/octet-stream")).asJava
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
        val ann = new McpSchema.Annotations(null, null)
        List(new McpSchema.TextContent(ann, other.toString)).asJava
    }
    McpSchema.CallToolResult.builder().content(contentList).isError(false).build()
  }

end FastMcpServer

/** Companion object for FastMCPScala
  */
object FastMcpServer:

  /** Create a new FastMCPScala instance and run it with HTTP transport.
    *
    * Uses streamable transport (sessions + SSE) by default. Set `settings.stateless = true` for
    * stateless transport (no sessions, no SSE).
    */
  def http(
      name: String = "FastMCPScala",
      version: String = "0.1.0",
      settings: FastMcpServerSettings = FastMcpServerSettings()
  ): ZIO[Any, Throwable, Unit] =
    McpServer.http(name, version, settings)

  /** Create a new FastMCPScala instance and run it with stdio transport
    */
  def stdio(
      name: String = "FastMCPScala",
      version: String = "0.1.0",
      settings: FastMcpServerSettings = FastMcpServerSettings()
  ): ZIO[Any, Throwable, Unit] =
    McpServer.stdio(name, version, settings)

  /** Create a new FastMCPScala instance with the given settings
    */
  def apply(
      name: String = "FastMCPScala",
      version: String = "0.1.0",
      settings: FastMcpServerSettings = FastMcpServerSettings()
  ): FastMcpServer =
    McpServer(name, version, settings)

end FastMcpServer
