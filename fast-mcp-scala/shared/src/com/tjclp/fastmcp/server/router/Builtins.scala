package com.tjclp.fastmcp.server.router

import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.{Protocol, SetLevelRequestParams}
import com.tjclp.fastmcp.core.wire.*
import com.tjclp.fastmcp.jsonrpc.McpError
import com.tjclp.fastmcp.server.{CompletionHandler, McpContext}
import com.tjclp.fastmcp.server.manager.{
  PromptManager,
  ResourceManager,
  ToolManager,
  ToolNotFoundError
}

/** The built-in MCP request/notification handlers, parameterized on the server environment `R`.
  *
  * Each handler decodes its `params` (failing with `-32602` on bad shape), drives the relevant
  * manager, maps the result to the wire type ([[WireMapping]]), and returns the result as JSON.
  * Manager failures surface as [[McpError]] via `McpError.fromThrowable`.
  *
  * Built-ins are registered with the router only when their backing capability is present (e.g.
  * `completion/complete` only if a completion provider is wired) — which is exactly what makes
  * [[McpRouter.deriveCapabilities]] honest and fixes issue #56.
  */
final class Builtins[R](
    serverInfo: Implementation,
    instructions: Option[String],
    capabilities: ServerCapabilities,
    toolManager: ToolManager[R],
    promptManager: PromptManager[R],
    resourceManager: ResourceManager[R],
    tasksEnabled: Boolean,
    exposeTemplates: Boolean,
    completionHandler: Option[CompletionHandler[R]] = None
):

  /** Helper: decode `params` into `A`, failing with InvalidParams. */
  private def decodeParams[A: JsonDecoder](params: Json, ctx: String): IO[McpError, A] =
    ZIO.fromEither(params.as[A]).mapError(err => McpError.invalidParams(s"$ctx: $err"))

  /** Helper: render any encodable result to JSON. */
  private def ok[A: JsonEncoder](value: A): UIO[Json] =
    ZIO.succeed(value.toJsonAST.getOrElse(Json.Obj()))

  // ---- lifecycle ----

  val ping: RequestHandler[R] = (_, _) => ok(EmptyResult())

  val initialize: RequestHandler[R] = (session, params) =>
    for
      req <- decodeParams[InitializeRequestParams](params, "initialize")
      negotiated = negotiateVersion(req.protocolVersion)
      _ <- session.setProtocolVersion(negotiated)
      _ <- session.setClientInfo(req.clientInfo, req.capabilities)
      result = InitializeResult(
        protocolVersion = negotiated,
        capabilities = capabilities,
        serverInfo = serverInfo,
        instructions = instructions
      )
      json <- ok(result)
    yield json

  // markInitialized is a UIO (cannot fail); it widens to ZIO[R, McpError, Unit] directly.
  val initialized: NotificationHandler[R] = (session, _) => session.markInitialized

  /** Pick the protocol version to respond with: echo the client's if we support it, else our
    * latest. (The client disconnects if it can't accept our choice.)
    */
  private def negotiateVersion(clientVersion: String): String =
    if Protocol.SupportedProtocolVersions.contains(clientVersion) then clientVersion
    else Protocol.LatestProtocolVersion

  // ---- tools ----

  val toolsList: RequestHandler[R] = (_, _) =>
    val tools = toolManager.listDefinitions().map(WireMapping.toolToWire(_, tasksEnabled))
    ok(ListToolsResult(tools = tools))

  val toolsCall: RequestHandler[R] = (session, params) =>
    for
      req <- decodeParams[CallToolRequestParams](params, "tools/call")
      args = req.arguments match
        case Some(Json.Obj(fields)) => fields.toMap
        case _ => Map.empty[String, Any]
      ctx <- contextFor(session)
      outcome <- toolManager.callTool(req.name, args, Some(ctx)).either
      json <- outcome match
        case Right(result) => ok(WireMapping.toolResultToWire(result))
        // Unknown tool is bad input (protocol error); a handler that threw is a tool-level failure
        // surfaced as an error result (isError = true), per the MCP spec.
        case Left(_: ToolNotFoundError) =>
          ZIO.fail(McpError.invalidParams(s"Unknown tool: ${req.name}"))
        case Left(err) => ok(WireMapping.toolErrorToWire(err))
    yield json

  // ---- resources ----

  val resourcesList: RequestHandler[R] = (_, _) =>
    val resources = resourceManager
      .listDefinitions()
      .filterNot(_.isTemplate)
      .map(WireMapping.resourceToWire)
    ok(ListResourcesResult(resources = resources))

  val resourcesTemplatesList: RequestHandler[R] = (_, _) =>
    val templates = resourceManager
      .listDefinitions()
      .filter(_.isTemplate)
      .map(WireMapping.templateToWire)
    ok(ListResourceTemplatesResult(resourceTemplates = templates))

  val resourcesRead: RequestHandler[R] = (session, params) =>
    for
      req <- decodeParams[ReadResourceRequestParams](params, "resources/read")
      ctx <- contextFor(session)
      body <- resourceManager.readResource(req.uri, Some(ctx)).mapError(McpError.fromThrowable)
      mime = resourceManager.getResourceDefinition(req.uri).flatMap(_.mimeType)
      contents = WireMapping.resourceContentsToWire(req.uri, mime, body)
      json <- ok(ReadResourceResult(contents = List(contents)))
    yield json

  // ---- prompts ----

  val promptsList: RequestHandler[R] = (_, _) =>
    val prompts = promptManager.listDefinitions().map(WireMapping.promptToWire)
    ok(ListPromptsResult(prompts = prompts))

  val promptsGet: RequestHandler[R] = (session, params) =>
    for
      req <- decodeParams[GetPromptRequestParams](params, "prompts/get")
      args = req.arguments.getOrElse(Map.empty).view.mapValues(v => v: Any).toMap
      ctx <- contextFor(session)
      messages <- promptManager
        .getPrompt(req.name, args, Some(ctx))
        .mapError(McpError.fromThrowable)
      result = GetPromptResult(
        messages = WireMapping.promptMessagesToWire(messages),
        description = promptManager.getPromptDefinition(req.name).flatMap(_.description)
      )
      json <- ok(result)
    yield json

  // ---- logging ----

  val loggingSetLevel: RequestHandler[R] = (session, params) =>
    for
      req <- decodeParams[SetLevelRequestParams](params, "logging/setLevel")
      _ <- session.setLogLevel(req.level)
      json <- ok(EmptyResult())
    yield json

  // ---- completion ----

  /** `completion/complete` — argument autocompletion. Registered only when a completion provider is
    * wired (so the `completions` capability stays honest); the `None` branch is therefore
    * defensive.
    */
  val complete: RequestHandler[R] = (session, params) =>
    completionHandler match
      case None =>
        ZIO.fail(McpError.methodNotFound(Methods.CompletionComplete))
      case Some(handler) =>
        for
          req <- decodeParams[CompleteRequestParams](params, "completion/complete")
          ctx <- contextFor(session)
          completion <- handler(req, Some(ctx)).mapError(McpError.fromThrowable)
          json <- ok(CompleteResult(completion = completion))
        yield json

  /** Build an [[McpContext]] for the current request, bound to its session (so handlers can push
    * log/progress notifications) and snapshotting the client info/capabilities captured at
    * `initialize` (so handlers can read them synchronously).
    */
  private def contextFor(session: Session): UIO[McpContext] =
    for
      info <- session.clientInfo
      caps <- session.clientCapabilities
    yield McpContext.withSession(session, info, caps)
