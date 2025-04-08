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
      val typedHandler = new TypedToolHandler[Input, Output] {
        override def handle(input: Input, context: Option[McpContext]): ZIO[Any, Throwable, Output] =
          handler(input, context)
      }
      val inputSchema = SchemaGenerator.schemaFor[Input]
      val definition = ToolDefinition(name, description, Left(inputSchema))
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
      val typedHandler = new TypedToolHandler[Input, Output] {
        override def handle(input: Input, context: Option[McpContext]): ZIO[Any, Throwable, Output] =
          handler(input, context)
      }
      val inputSchema = SchemaGenerator.schemaForTapir[Input]
      val definition = ToolDefinition(name, description, Left(inputSchema))
      toolManager.addTypedTool(name, typedHandler, definition, options).as(this)
    }.flatten

  /**
   * Register a case class backed tool with a directly provided JSON Schema string
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
      val typedHandler = new TypedToolHandler[Input, Output] {
        override def handle(input: Input, context: Option[McpContext]): ZIO[Any, Throwable, Output] =
          handler(input, context)
      }
      JSystem.err.println(s"[FastMCPScala] Using provided JSON Schema for $name: $schemaString")
      // We'll store the schema as a string to let the Java SDK parse it
      val definition = ToolDefinition(name, description, Right(schemaString))
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
        new McpSchema.ServerCapabilities.ResourceCapabilities(true, false)
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
    toolManager.listDefinitions().foreach { toolDef =>
      serverBuilder.tool(
        ToolDefinition.toJava(toolDef),
        javaToolHandler(toolDef.name)
      )
    }

    // Register resources
    resourceManager.listDefinitions().foreach { resDef =>
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
      serverBuilder.resources(resourceSpec)
    }

    // Register prompts
    promptManager.listDefinitions().foreach { promptDef =>
      val promptSpec = new McpServerFeatures.SyncPromptSpecification(
        PromptDefinition.toJava(promptDef),
        javaPromptHandler(promptDef.name)
      )
      serverBuilder.prompts(promptSpec)
    }

    // Build the server
    underlyingJavaServer = Some(serverBuilder.build())
    JSystem.err.println(s"MCP Server '$name' configured.")

  /**
   * Convert Scala ZIO handler to Java BiFunction for SyncServer
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

      val contentList = result match
        case s: String =>
          val textContent = new McpSchema.TextContent(null, null, s)
          List(textContent).asJava
        case c: Content =>
          List(c.toJava).asJava
        case lst: List[?] if lst.nonEmpty && lst.head.isInstanceOf[Content] =>
          lst.asInstanceOf[List[Content]].map(_.toJava).asJava
        case other =>
          val textContent = new McpSchema.TextContent(null, null, other.toString)
          List(textContent).asJava

      new McpSchema.CallToolResult(contentList, false)
    }

  /**
   * Read a resource by URI
   */
  def readResource(uri: String): ZIO[Any, Throwable, McpSchema.ReadResourceResult] =
    for
      content <- resourceManager.readResource(uri, None)
      javaContent = content match
        case s: String =>
          new McpSchema.TextResourceContents(uri, "text/plain", s)
        case bytes: Array[Byte] =>
          val base64Data = java.util.Base64.getEncoder.encodeToString(bytes)
          new McpSchema.BlobResourceContents(uri, "application/octet-stream", base64Data)
    yield new McpSchema.ReadResourceResult(List(javaContent).asJava)

  /**
   * Convert Scala ZIO handler to Java BiFunction for prompt handling
   */
  def javaPromptHandler(promptName: String)
    : java.util.function.BiFunction[McpSyncServerExchange, McpSchema.GetPromptRequest, McpSchema.GetPromptResult] =
    (exchange, request) => {
      val scalaArgs = request.arguments().asScala.toMap.asInstanceOf[Map[String, Any]]
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
   * Fully implement registerMacroTool by reflecting on methodName, creating a handler that:
   * 1) Locates method in T
   * 2) Checks for required parameters
   * 3) Converts args to the correct param types
   * 4) Invokes method
   * 5) Registers the resulting tool with the provided JSON schema
   */
  def registerMacroTool[T](
    toolName: String,
    description: Option[String],
    methodName: String,
    schemaJson: String,
    paramNames: List[String],
    paramTypes: List[String],
    required: List[Boolean]
  )(using classTag: ClassTag[T]): Unit =
    JSystem.err.println(s"[FastMCPScala] Registering macro-generated tool '$toolName' from method '$methodName'")
    JSystem.err.println(s"[FastMCPScala] Schema: $schemaJson")

    // Attempt to load the runtime class
    val runtimeClass = classTag.runtimeClass

    // Attempt to get an instance of T (assume it's either a Scala object or has a no-arg constructor)
    val instance: Any =
      if runtimeClass.getName.endsWith("$") then
        // Scala object
        runtimeClass.getField("MODULE$").get(null)
      else
        // Attempt new instance via no-arg constructor
        runtimeClass.getDeclaredConstructor().newInstance()

    // Let's fix the method lookup by trying different combinations
    JSystem.err.println(s"[FastMCPScala] Looking up method $methodName with param types: ${paramTypes.mkString(", ")}")
    
    // Try different combinations to find the method
    val method = try {
      // First try: primitive types
      val primitiveClasses = paramTypes.map {
        case t if t.contains("Int") => classOf[Int]
        case t if t.contains("Long") => classOf[Long]
        case t if t.contains("Double") => classOf[Double]
        case t if t.contains("Float") => classOf[Float]
        case t if t.contains("Boolean") => classOf[Boolean]
        case t if t.contains("String") => classOf[String]
        // Fallback: treat it as an Object param
        case _ => classOf[Object]
      }.toArray
      
      JSystem.err.println(s"[FastMCPScala] Trying primitive classes: ${primitiveClasses.map(_.getName).mkString(", ")}")
      runtimeClass.getMethod(methodName, primitiveClasses*)
    } catch {
      case e1: NoSuchMethodException => try {
        // Second try: boxed Java types
        val boxedClasses = paramTypes.map {
          case t if t.contains("Int") => classOf[java.lang.Integer]
          case t if t.contains("Long") => classOf[java.lang.Long]
          case t if t.contains("Double") => classOf[java.lang.Double]
          case t if t.contains("Float") => classOf[java.lang.Float]
          case t if t.contains("Boolean") => classOf[java.lang.Boolean]
          case t if t.contains("String") => classOf[java.lang.String]
          // Fallback
          case _ => classOf[Object]
        }.toArray
        
        JSystem.err.println(s"[FastMCPScala] Trying boxed classes: ${boxedClasses.map(_.getName).mkString(", ")}")
        runtimeClass.getMethod(methodName, boxedClasses*)
      } catch {
        case e2: NoSuchMethodException =>
          // Last resort: all strings
          JSystem.err.println(s"[FastMCPScala] Trying with all String parameters")
          val stringClasses = Array.fill(paramTypes.length)(classOf[String])
          try {
            runtimeClass.getMethod(methodName, stringClasses*)
          } catch {
            case e3: NoSuchMethodException =>
              // Final attempt: use getDeclaredMethod which also returns non-public methods
              JSystem.err.println(s"[FastMCPScala] Trying with getDeclaredMethod")
              runtimeClass.getDeclaredMethod(methodName, stringClasses*)
          }
      }
    }

    // Helper to convert argument from Any to the needed param type
    def convertParam(value: Any, tpe: String): Any =
      // Use pattern matching for full Scala type names
      if tpe.contains("Int") then
        value match
          case n: Number => n.intValue()
          case s: String => s.toInt
          case other     => other.toString.toInt
      else if tpe.contains("Long") then
        value match
          case n: Number => n.longValue()
          case s: String => s.toLong
          case other     => other.toString.toLong
      else if tpe.contains("Double") then
        value match
          case n: Number => n.doubleValue()
          case s: String => s.toDouble
          case other     => other.toString.toDouble
      else if tpe.contains("Float") then
        value match
          case n: Number => n.floatValue()
          case s: String => s.toFloat
          case other     => other.toString.toFloat
      else if tpe.contains("Boolean") then
        value match
          case b: Boolean => b
          case s: String  => s.toBoolean
          case n: Number  => n.intValue() != 0
          case other      => other.toString.toBoolean
      else if tpe.contains("String") then
        value.toString
      else
        // fallback
        value

    // We don't need to parse the schema string, we can use it directly in a ToolDefinition

    // Build the tool handler with reflection
    val reflectiveHandler: ToolHandler = args =>
      // Validate required arguments using boundary for early return
      boundary {
        val missingParams = paramNames.zip(required).collect {
          case (pName, true) if !args.contains(pName) => pName
        }
        if missingParams.nonEmpty then
          break(ZIO.fail(new ToolArgumentError(
            s"Missing required parameters for tool '$toolName': ${missingParams.mkString(", ")}"
          )))
      }

      // Convert arguments
      val paramValues = paramNames.zip(paramTypes).zipWithIndex.map {
        case ((pName, pType), idx) =>
          val rawVal = args.getOrElse(pName, null)
          if rawVal == null && required(idx) then
            throw new ToolArgumentError(s"Parameter '$pName' is required but was not provided.")
          if rawVal == null then null
          else convertParam(rawVal, pType)
      }

      ZIO.attempt {
        method.invoke(instance, paramValues*)
      }.mapError { e =>
        new ToolExecutionError(s"Error invoking method '$methodName' on '${runtimeClass.getName}': ${e.getMessage}", e)
      }

    // Create a tool definition with the raw schema string
    val toolDefinition = ToolDefinition(
      name = toolName, 
      description = description,
      inputSchema = Right(schemaJson) // Using the raw schema string
    )

    // Register the tool directly with the toolManager
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run(
        ZIO.attempt {
          toolManager.addTool(
            name = toolName,
            handler = reflectiveHandler,
            definition = toolDefinition,
            options = ToolRegistrationOptions()
          )
        }.as(())
      ).getOrThrowFiberFailure()
    }

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