package com.tjclp.fastmcp
package server

import java.util.Base64

import scala.scalajs.js
import scala.scalajs.js.JSON
import scala.scalajs.js.JavaScriptException

import zio.*

import com.tjclp.fastmcp.codec.JsMcpDecodeContext
import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.facades.runtime as runtime
import com.tjclp.fastmcp.facades.server as tsdk
import com.tjclp.fastmcp.interop.ZioJsPromise
import com.tjclp.fastmcp.server.manager.*

/** Scala.js implementation of [[McpServer]]. Wraps the TS MCP SDK's low-level `Server` (from
  * `@modelcontextprotocol/sdk/server/index.js`), keeping the Scala-side tool/prompt/resource
  * manager layer as the canonical registry.
  *
  * Typed-contract registration flows through the shared trait's `tool`/`prompt`/`resource`
  * overloads, which pipe arguments through a given `McpDecoder[In]` and encode results via
  * `McpEncoder[Out]`. See [[com.tjclp.fastmcp.codec.JsMcpDecoders]] for the default givens.
  */
final class JsMcpServer(
    val name: String,
    val version: String,
    val settings: FastMcpServerSettings = FastMcpServerSettings()
) extends McpServerPlatform:

  override protected val decodeContext: McpDecodeContext = JsMcpDecodeContext.default

  val toolManager = new ToolManager()
  val resourceManager = new ResourceManager()
  val promptManager = new PromptManager()

  private val validator = new tsdk.AjvJsonSchemaValidator()

  private val validatorCache = scala.collection.mutable.Map.empty[String, js.Function1[
    js.Any,
    tsdk.JsonSchemaValidatorResult
  ]]

  // --- Tool registration -----------------------------------------------------------------------

  override def tool(
      definition: ToolDefinition,
      handler: ContextualToolHandler,
      options: ToolRegistrationOptions
  ): ZIO[Any, Throwable, McpServerPlatform] =
    toolManager.addTool(definition.name, handler, definition, options).as(this)

  // --- Resource registration -------------------------------------------------------------------

  override def resource(
      definition: ResourceDefinition,
      handler: ResourceHandler
  ): ZIO[Any, Throwable, McpServerPlatform] =
    resourceManager.addStaticResource(definition.uri, handler, definition).as(this)

  override def resourceTemplate(
      definition: ResourceDefinition,
      handler: ResourceTemplateHandler
  ): ZIO[Any, Throwable, McpServerPlatform] =
    resourceManager.addTemplateResource(definition.uri, handler, definition).as(this)

  // --- Prompt registration ---------------------------------------------------------------------

  override def prompt(
      definition: PromptDefinition,
      handler: PromptHandler
  ): ZIO[Any, Throwable, McpServerPlatform] =
    promptManager.addPrompt(definition.name, handler, definition).as(this)

  // --- Lifecycle -------------------------------------------------------------------------------

  /** Build a TS-SDK `Server`, register all MCP method handlers, and attach the given transport.
    * Returns the underlying TS `Server` for callers that want to close it (tests, custom runtimes).
    * Does **not** block — use [[runStdio]] or [[runHttp]] for the long-running case.
    */
  def connect(transport: tsdk.Transport): ZIO[Any, Throwable, tsdk.Server] =
    for
      tsServer <- ZIO.attempt(buildTsServer())
      _ <- ZioJsPromise.fromJsPromise(tsServer.connect(transport))
    yield tsServer

  /** Connect to `StdioServerTransport` and suspend until the Node/Bun runtime exits (stdin EOF,
    * SIGINT, etc.).
    */
  override def runStdio(): ZIO[Any, Throwable, Unit] =
    val transport = new tsdk.StdioServerTransport()
    ZIO.acquireReleaseWith(connect(transport))(tsServer =>
      ZioJsPromise.fromJsPromise(tsServer.close()).ignore
    )(_ => ZIO.never)

  /** Bun-first Streamable HTTP transport.
    *
    *   - `settings.stateless = true` — fresh TS Server + transport per POST (JSON-response mode, no
    *     SSE); GET/DELETE → 405.
    *   - `settings.stateless = false` (default) — one TS Server + transport per initialized
    *     session, keyed by `mcp-session-id`, reused across GET/POST/DELETE until the session closes
    *     or the client sends DELETE.
    *
    * Honors `host`, `port`, `httpEndpoint`, and `disallowDelete`. `keepAliveInterval` is a
    * documented JS no-op — the transport handles stream keep-alive internally on Bun.
    */
  override def runHttp(): ZIO[Any, Throwable, Unit] =
    ZIO.acquireReleaseWith(
      ZIO.attempt {
        if settings.stateless then startStatelessHttp()
        else startStatefulHttp()
      }
    )(bunServer => shutdownHttp(bunServer).ignore)(_ => ZIO.never)

  // Session table for stateful HTTP. Bun is single-threaded, so a js.Dictionary is sufficient —
  // no concurrent access concerns.
  private val sessions =
    js.Dictionary.empty[(tsdk.Server, tsdk.WebStandardStreamableHttpServerTransport)]

  private[fastmcp] def startStatelessHttp(): runtime.BunServer =
    runtime.Bun.serve(
      runtime.BunServeOptions(
        port = settings.port,
        hostname = settings.host,
        fetch = js.Any.fromFunction1((req: js.Dynamic) => handleStateless(req))
      )
    )

  private[fastmcp] def startStatefulHttp(): runtime.BunServer =
    runtime.Bun.serve(
      runtime.BunServeOptions(
        port = settings.port,
        hostname = settings.host,
        fetch = js.Any.fromFunction1((req: js.Dynamic) => handleStateful(req))
      )
    )

  private def requestMethod(req: js.Dynamic): String = req.method.asInstanceOf[String]

  private def requestPath(req: js.Dynamic): String =
    // `new URL(req.url).pathname` is the Web-Standard way to pull the path from a Request.
    val url = js.Dynamic.newInstance(js.Dynamic.global.URL)(req.url)
    url.pathname.asInstanceOf[String]

  private def sessionIdHeader(req: js.Dynamic): Option[String] =
    val header = req.headers.get("mcp-session-id")
    Option(header.asInstanceOf[String | Null]).flatMap {
      case null => None
      case s: String if s.nonEmpty => Some(s)
      case _ => None
    }

  private def webResponse(
      status: Int,
      body: String = "",
      contentType: String = "text/plain"
  ): js.Dynamic =
    new runtime.WebResponse(
      body,
      runtime.WebResponseInit(status, Map("content-type" -> contentType))
    ).asInstanceOf[js.Dynamic]

  private def handleStateless(req: js.Dynamic): js.Promise[js.Dynamic] =
    if requestPath(req) != settings.httpEndpoint then
      return js.Promise.resolve[js.Dynamic](webResponse(404, "Not Found"))
    if requestMethod(req) != "POST" then
      return js.Promise.resolve[js.Dynamic](webResponse(405, "Stateless mode only accepts POST"))

    val transport =
      new tsdk.WebStandardStreamableHttpServerTransport(tsdk.WebStreamableHttpOptions.stateless)

    ZioJsPromise.zioToPromise {
      ZIO.acquireReleaseWith(
        for
          tsServer <- ZIO.attempt(buildTsServer())
          _ <- ZioJsPromise.fromJsPromise(tsServer.connect(transport))
        yield tsServer
      )(tsServer => ZioJsPromise.fromJsPromise(tsServer.close()).ignore)(_ =>
        ZioJsPromise.fromJsPromise(transport.handleRequest(req))
      )
    }

  private def handleStateful(req: js.Dynamic): js.Promise[js.Dynamic] =
    if requestPath(req) != settings.httpEndpoint then
      return js.Promise.resolve[js.Dynamic](webResponse(404, "Not Found"))

    val method = requestMethod(req)
    if method == "DELETE" && settings.disallowDelete then
      return js.Promise.resolve[js.Dynamic](webResponse(405, "DELETE disabled"))

    sessionIdHeader(req) match
      case Some(sid) =>
        sessions.get(sid) match
          case Some((_, transport)) =>
            transport.handleRequest(req)
          case None =>
            js.Promise.resolve[js.Dynamic](webResponse(404, s"Unknown session: $sid"))

      case None if method == "POST" =>
        // Fresh session — the transport will call back on `onsessioninitialized` with the new id.
        lazy val transport: tsdk.WebStandardStreamableHttpServerTransport =
          new tsdk.WebStandardStreamableHttpServerTransport(
            js.Dynamic
              .literal(
                sessionIdGenerator = js.Any.fromFunction0(() => runtime.WebCrypto.randomUUID()),
                onsessioninitialized = js.Any.fromFunction1((newSid: String) => {
                  sessions(newSid) = (tsServer, transport)
                  ()
                }),
                onsessionclosed = js.Any.fromFunction1((sid: String) => {
                  sessions -= sid
                  ()
                })
              )
              .asInstanceOf[tsdk.WebStreamableHttpOptions]
          )
        lazy val tsServer: tsdk.Server = buildTsServer()

        ZioJsPromise.zioToPromise(
          for
            _ <- ZioJsPromise.fromJsPromise(tsServer.connect(transport))
            resp <- ZioJsPromise.fromJsPromise(transport.handleRequest(req))
          yield resp
        )

      case None =>
        js.Promise.resolve[js.Dynamic](webResponse(400, "Missing mcp-session-id header"))

  // --- TS SDK wiring ---------------------------------------------------------------------------

  private def buildTsServer(): tsdk.Server =
    val capabilities = tsdk.ServerCapabilities(
      tools = !toolManager.listDefinitions().isEmpty,
      resources = !resourceManager.listDefinitions().isEmpty,
      prompts = !promptManager.listDefinitions().isEmpty
    )
    val tsServer = new tsdk.Server(
      tsdk.Implementation(name, version),
      tsdk.ServerOptions(capabilities = capabilities)
    )
    registerHandlers(tsServer)
    tsServer

  private def registerHandlers(tsServer: tsdk.Server): Unit =
    tsServer.setRequestHandler(
      tsdk.ListToolsRequestSchema,
      (_, _) => ZioJsPromise.zioToPromise(ZIO.attempt(handleListTools()))
    )
    tsServer.setRequestHandler(
      tsdk.CallToolRequestSchema,
      (req, extra) => ZioJsPromise.zioToPromise(handleCallTool(tsServer, req, extra))
    )
    tsServer.setRequestHandler(
      tsdk.ListResourcesRequestSchema,
      (_, _) => ZioJsPromise.zioToPromise(ZIO.attempt(handleListResources()))
    )
    tsServer.setRequestHandler(
      tsdk.ListResourceTemplatesRequestSchema,
      (_, _) => ZioJsPromise.zioToPromise(ZIO.attempt(handleListResourceTemplates()))
    )
    tsServer.setRequestHandler(
      tsdk.ReadResourceRequestSchema,
      (req, extra) => ZioJsPromise.zioToPromise(handleReadResource(tsServer, req, extra))
    )
    tsServer.setRequestHandler(
      tsdk.ListPromptsRequestSchema,
      (_, _) => ZioJsPromise.zioToPromise(ZIO.attempt(handleListPrompts()))
    )
    tsServer.setRequestHandler(
      tsdk.GetPromptRequestSchema,
      (req, extra) => ZioJsPromise.zioToPromise(handleGetPrompt(tsServer, req, extra))
    )

  // --- Handlers --------------------------------------------------------------------------------

  private def handleListTools(): js.Any =
    val tools = toolManager
      .listDefinitions()
      .map(JsMcpServer.toolDefinitionToJs)
    js.Dynamic.literal(tools = js.Array[js.Any](tools*))

  private def handleCallTool(
      tsServer: tsdk.Server,
      req: js.Dynamic,
      extra: tsdk.RequestHandlerExtra
  ): ZIO[Any, Throwable, js.Any] =
    val params = req.params
    val toolName = params.name.asInstanceOf[String]
    val argsJs = params.arguments.asInstanceOf[js.UndefOr[js.Dictionary[js.Any]]]
    val argsMap: Map[String, Any] = argsJs.toOption.fold(Map.empty[String, Any])(_.toMap)

    toolManager.getToolDefinition(toolName) match
      case None =>
        ZIO.succeed(JsMcpServer.callToolError(s"Tool '$toolName' not found"))
      case Some(defn) =>
        validateAgainstSchema(defn.inputSchema.toJsonString, argsJs) match
          case Some(errorMsg) =>
            ZIO.succeed(JsMcpServer.callToolError(errorMsg))
          case None =>
            val ctx = Some(JsMcpContext(tsServer, extra): McpContext)
            toolManager
              .callTool(toolName, argsMap, ctx)
              .map(result => JsMcpServer.callToolSuccess(toolName, result))
              .catchAll(err =>
                ZIO.succeed(
                  JsMcpServer.callToolError(
                    Option(err.getMessage).getOrElse(err.getClass.getSimpleName)
                  )
                )
              )

  private def handleListResources(): js.Any =
    val defs = resourceManager.listDefinitions().filterNot(_.isTemplate)
    js.Dynamic.literal(
      resources = js.Array[js.Any](defs.map(JsMcpServer.resourceDefinitionToJs)*)
    )

  private def handleListResourceTemplates(): js.Any =
    val defs = resourceManager.listDefinitions().filter(_.isTemplate)
    js.Dynamic.literal(
      resourceTemplates = js.Array[js.Any](defs.map(JsMcpServer.resourceTemplateToJs)*)
    )

  private def handleReadResource(
      tsServer: tsdk.Server,
      req: js.Dynamic,
      extra: tsdk.RequestHandlerExtra
  ): ZIO[Any, Throwable, js.Any] =
    val uri = req.params.uri.asInstanceOf[String]
    val ctx = Some(JsMcpContext(tsServer, extra): McpContext)
    val mimeType = resourceMimeType(uri)
    resourceManager
      .readResource(uri, ctx)
      .map(body => JsMcpServer.readResourceResult(uri, mimeType, body))
      .catchAll(err =>
        ZIO.fail(
          JavaScriptException(
            new tsdk.McpError(
              tsdk.ErrorCode.InternalError,
              Option(err.getMessage).getOrElse(err.getClass.getSimpleName)
            )
          )
        )
      )

  private def handleListPrompts(): js.Any =
    val defs = promptManager.listDefinitions()
    js.Dynamic.literal(
      prompts = js.Array[js.Any](defs.map(JsMcpServer.promptDefinitionToJs)*)
    )

  private def handleGetPrompt(
      tsServer: tsdk.Server,
      req: js.Dynamic,
      extra: tsdk.RequestHandlerExtra
  ): ZIO[Any, Throwable, js.Any] =
    val params = req.params
    val promptName = params.name.asInstanceOf[String]
    val argsJs = params.arguments.asInstanceOf[js.UndefOr[js.Dictionary[js.Any]]]
    val argsMap: Map[String, Any] = argsJs.toOption.fold(Map.empty[String, Any])(_.toMap)
    val ctx = Some(JsMcpContext(tsServer, extra): McpContext)

    promptManager
      .getPrompt(promptName, argsMap, ctx)
      .map { messages =>
        val jsMessages = messages.map(JsMcpServer.messageToJs)
        js.Dynamic.literal(messages = js.Array[js.Any](jsMessages*))
      }
      .catchAll(err =>
        ZIO.fail(
          JavaScriptException(
            new tsdk.McpError(
              tsdk.ErrorCode.InternalError,
              Option(err.getMessage).getOrElse(err.getClass.getSimpleName)
            )
          )
        )
      )

  private def validateAgainstSchema(
      schemaJson: String,
      input: js.UndefOr[js.Dictionary[js.Any]]
  ): Option[String] =
    val validate = validatorCache.getOrElseUpdate(
      schemaJson,
      validator.getValidator(JSON.parse(schemaJson))
    )
    val result = validate(input.getOrElse(js.Dictionary.empty[js.Any]))
    if result.valid then None else Some(result.errorMessage.getOrElse("Input validation failed"))

  private def resourceMimeType(uri: String): Option[String] =
    resourceManager
      .getResourceDefinition(uri)
      .flatMap(_.mimeType)
      .orElse(resourceManager.findMatchingTemplate(uri).flatMap { case (_, definition, _, _) =>
        definition.mimeType
      })

  private def shutdownHttp(bunServer: runtime.BunServer): ZIO[Any, Throwable, Unit] =
    for
      activeServers <- ZIO.succeed(sessions.values.map(_._1).toList)
      _ <- ZIO.foreachDiscard(activeServers)(tsServer =>
        ZioJsPromise.fromJsPromise(tsServer.close()).ignore
      )
      _ <- ZIO.succeed(sessions.clear())
      _ <- ZIO.attempt(bunServer.stop())
    yield ()

end JsMcpServer

object JsMcpServer:

  private val base64Enc = Base64.getEncoder

  private[server] def toolDefinitionToJs(defn: ToolDefinition): js.Any =
    val raw = js.Dictionary.empty[js.Any]
    raw("name") = defn.name
    defn.description.foreach(d => raw("description") = d)
    raw("inputSchema") = JSON.parse(defn.inputSchema.toJsonString)
    defn.annotations.foreach(a => raw("annotations") = toolAnnotationsToJs(a))
    raw.asInstanceOf[js.Any]

  private[server] def toolAnnotationsToJs(ann: ToolAnnotations): js.Any =
    val raw = js.Dictionary.empty[js.Any]
    ann.title.foreach(v => raw("title") = v)
    ann.readOnlyHint.foreach(v => raw("readOnlyHint") = v)
    ann.destructiveHint.foreach(v => raw("destructiveHint") = v)
    ann.idempotentHint.foreach(v => raw("idempotentHint") = v)
    ann.openWorldHint.foreach(v => raw("openWorldHint") = v)
    ann.returnDirect.foreach(v => raw("returnDirect") = v)
    raw.asInstanceOf[js.Any]

  private[server] def resourceDefinitionToJs(defn: ResourceDefinition): js.Any =
    val raw = js.Dictionary.empty[js.Any]
    raw("uri") = defn.uri
    // MCP spec requires `name` — default to the URI if the user didn't supply one.
    raw("name") = defn.name.getOrElse(defn.uri)
    defn.description.foreach(d => raw("description") = d)
    defn.mimeType.foreach(m => raw("mimeType") = m)
    raw.asInstanceOf[js.Any]

  private[server] def resourceTemplateToJs(defn: ResourceDefinition): js.Any =
    val raw = js.Dictionary.empty[js.Any]
    raw("uriTemplate") = defn.uri
    raw("name") = defn.name.getOrElse(defn.uri)
    defn.description.foreach(d => raw("description") = d)
    defn.mimeType.foreach(m => raw("mimeType") = m)
    raw.asInstanceOf[js.Any]

  private[server] def promptDefinitionToJs(defn: PromptDefinition): js.Any =
    val raw = js.Dictionary.empty[js.Any]
    raw("name") = defn.name
    defn.description.foreach(d => raw("description") = d)
    defn.arguments.foreach { args =>
      raw("arguments") = js.Array[js.Any](args.map(promptArgumentToJs)*)
    }
    raw.asInstanceOf[js.Any]

  private[server] def promptArgumentToJs(arg: PromptArgument): js.Any =
    val raw = js.Dictionary.empty[js.Any]
    raw("name") = arg.name
    arg.description.foreach(d => raw("description") = d)
    raw("required") = arg.required
    raw.asInstanceOf[js.Any]

  private[server] def contentToJs(content: Content): js.Any =
    content match
      case t: TextContent =>
        val raw = js.Dictionary[js.Any]("type" -> "text", "text" -> t.text)
        raw.asInstanceOf[js.Any]
      case i: ImageContent =>
        val raw =
          js.Dictionary[js.Any]("type" -> "image", "data" -> i.data, "mimeType" -> i.mimeType)
        raw.asInstanceOf[js.Any]
      case er: EmbeddedResource =>
        // TS SDK expects `{ type: "resource", resource: {...} }`. We pass the embedded content
        // through as best we can; complex cases (mixed text/blob) are handled by callers that
        // build their own `List[Content]`.
        val inner = js.Dictionary.empty[js.Any]
        inner("uri") = er.resource.uri
        inner("mimeType") = er.resource.mimeType
        er.resource.text.foreach(t => inner("text") = t)
        er.resource.blob.foreach(b => inner("blob") = b)
        val raw = js.Dictionary[js.Any]("type" -> "resource", "resource" -> inner)
        raw.asInstanceOf[js.Any]

  private[server] def messageToJs(msg: Message): js.Any =
    val role = msg.role match
      case Role.User => "user"
      case Role.Assistant => "assistant"
    js.Dictionary[js.Any]("role" -> role, "content" -> contentToJs(msg.content))
      .asInstanceOf[js.Any]

  /** Mirror the JVM [[FastMcpServer]]'s `transformToolResult`: normalize an arbitrary handler
    * return value into the `{ content: [...] }` wire shape.
    */
  private[server] def callToolSuccess(toolName: String, result: Any): js.Any =
    val content: List[js.Any] = result match
      case s: String =>
        List(js.Dictionary[js.Any]("type" -> "text", "text" -> s).asInstanceOf[js.Any])
      case bytes: Array[Byte] =>
        List(
          js.Dictionary[js.Any](
            "type" -> "image",
            "data" -> base64Enc.encodeToString(bytes),
            "mimeType" -> "application/octet-stream"
          ).asInstanceOf[js.Any]
        )
      case c: Content => List(contentToJs(c))
      case lst: List[?] if lst.nonEmpty && lst.head.isInstanceOf[Content] =>
        lst.asInstanceOf[List[Content]].map(contentToJs)
      case null => Nil
      case other =>
        List(
          js.Dictionary[js.Any](
            "type" -> "text",
            "text" -> other.toString
          ).asInstanceOf[js.Any]
        )
    js.Dynamic.literal(content = js.Array[js.Any](content*), isError = false)

  private[server] def callToolError(message: String): js.Any =
    js.Dynamic.literal(
      content = js.Array[js.Any](
        js.Dictionary[js.Any]("type" -> "text", "text" -> message).asInstanceOf[js.Any]
      ),
      isError = true
    )

  private[server] def readResourceResult(
      uri: String,
      mimeType: Option[String],
      body: String | Array[Byte]
  ): js.Any =
    val inner = js.Dictionary.empty[js.Any]
    inner("uri") = uri
    val finalMimeType = mimeType.getOrElse(body match
      case s: String
          if (s.startsWith("{") && s.endsWith("}")) || (s.startsWith("[") && s.endsWith("]")) =>
        "application/json"
      case _: String => "text/plain"
      case _: Array[Byte] => "application/octet-stream"
    )
    inner("mimeType") = finalMimeType
    body match
      case s: String => inner("text") = s
      case bytes: Array[Byte] => inner("blob") = base64Enc.encodeToString(bytes)
    js.Dynamic.literal(contents = js.Array[js.Any](inner.asInstanceOf[js.Any]))
