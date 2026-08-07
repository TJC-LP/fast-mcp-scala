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
  ResourceNotFoundError,
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
    modernCapabilities: ServerCapabilities,
    toolManager: ToolManager[R],
    promptManager: PromptManager[R],
    resourceManager: ResourceManager[R],
    tasksEnabled: Boolean,
    exposeTemplates: Boolean,
    completionHandler: Option[CompletionHandler[R]] = None,
    hooks: ServerHooks[R] = ServerHooks.noop[R]
):

  /** Helper: decode `params` into `A`, failing with InvalidParams. */
  private def decodeParams[A: JsonDecoder](params: Json, ctx: String): IO[McpError, A] =
    ZIO.fromEither(params.as[A]).mapError(err => McpError.invalidParams(s"$ctx: $err"))

  /** Helper: render any encodable result to JSON. */
  private def ok[A: JsonEncoder](value: A): UIO[Json] =
    ZIO.succeed(value.toJsonAST.getOrElse(Json.Obj()))

  val discover: RequestHandler[R] = (_, _) =>
    ok(
      DiscoverResult(
        supportedVersions = Protocol.SupportedProtocolVersions,
        capabilities = modernCapabilities,
        instructions = instructions
      )
    )

  // ---- lifecycle ----

  val ping: RequestHandler[R] = (_, _) => ok(EmptyResult())

  val initialize: RequestHandler[R] = (session, params) =>
    for
      req <- decodeParams[InitializeRequestParams](params, "initialize")
      negotiated = negotiateVersion(req.protocolVersion)
      _ <- session.setProtocolVersion(negotiated)
      _ <- session.setClientInfo(req.clientInfo, req.capabilities)
      // The pre-init gate opens once initialize is answered (not only on notifications/
      // initialized) — clients routinely issue requests right after the response, and the TS SDK
      // server accepts them. Re-initialize is lenient: it just renegotiates.
      _ <- session.markInitialized
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
    if Protocol.LegacyProtocolVersions.contains(clientVersion) then clientVersion
    else Protocol.LegacyProtocolVersions.head

  // ---- tools ----

  val toolsList: RequestHandler[R] = (session, _) =>
    session.currentRequestContext.flatMap { modern =>
      val tools = toolManager
        .listDefinitions()
        .sortBy(_.name)
        .map(WireMapping.toolToWire(_, tasksEnabled && modern.isEmpty))
      ok(ListToolsResult(tools = tools))
    }

  val toolsCall: RequestHandler[R] = (session, params) =>
    for
      req <- decodeParams[CallToolRequestParams](params, "tools/call")
      args <- req.arguments match
        case Some(Json.Obj(fields)) => ZIO.succeed(fields.toMap: Map[String, Any])
        case None => ZIO.succeed(Map.empty[String, Any])
        case Some(_) =>
          ZIO.fail(McpError.invalidParams("tools/call: `arguments` must be a JSON object"))
      ctx <- contextFor(session, req._meta)
      modern <- session.currentRequestContext.map(_.isDefined)
      _ <- hooks.beforeToolCall(req.name, params, session)
      outcome <- toolManager.callTool(req.name, args, Some(ctx)).either
      outputSchema = toolManager.getToolDefinition(req.name).flatMap(_.outputSchema)
      json <- outcome match
        case Right(result) => ok(WireMapping.toolResultToWire(result, outputSchema))
        case Left(err) if McpError.inputRequiredResult(err).isDefined =>
          ZIO.succeed(McpError.inputRequiredResult(err).get)
        // Unknown tool is bad input (protocol error) in both eras. Modern (2026-07-28): a
        // handler-raised McpError is a protocol error by contract — -32021 in particular must
        // escape to the HTTP 400 mapping. Legacy keeps the 0.5.0 in-band contract: every handler
        // failure, McpError included, surfaces as isError:true so the model can self-correct.
        case Left(_: ToolNotFoundError) =>
          ZIO.fail(McpError.invalidParams(s"Unknown tool: ${req.name}"))
        case Left(err: McpError) if modern => ZIO.fail(err)
        case Left(err) => ok(WireMapping.toolErrorToWire(err))
      _ <- hooks.afterToolCall(req.name, json, session)
    yield json

  // ---- resources ----

  val resourcesList: RequestHandler[R] = (_, _) =>
    val resources = resourceManager
      .listDefinitions()
      .filterNot(_.isTemplate)
      .sortBy(_.uri)
      .map(WireMapping.resourceToWire)
    ok(ListResourcesResult(resources = resources))

  /** Answered whenever resources exist — clients probe it unconditionally and treat `-32601` as an
    * error. Actual template listing stays behind `exposeTemplatesEndpoint`: when off, clients
    * derive templates from `{}` URIs and this returns an empty page.
    */
  val resourcesTemplatesList: RequestHandler[R] = (_, _) =>
    val templates =
      if exposeTemplates then
        resourceManager
          .listDefinitions()
          .filter(_.isTemplate)
          .sortBy(_.uri)
          .map(WireMapping.templateToWire)
      else List.empty
    ok(ListResourceTemplatesResult(resourceTemplates = templates))

  val resourcesRead: RequestHandler[R] = (session, params) =>
    for
      req <- decodeParams[ReadResourceRequestParams](params, "resources/read")
      ctx <- contextFor(session)
      modern <- session.currentRequestContext.map(_.isDefined)
      body <- resourceManager
        .readResource(req.uri, Some(ctx))
        .mapError {
          // 2026-07-28 folded resource misses into -32602; legacy sessions keep the reserved
          // -32002 their spec revisions promise. The manager is era-blind, so re-code here.
          case e: ResourceNotFoundError if !modern => McpError.legacyResourceNotFound(e.uri)
          case other => McpError.fromThrowable(other)
        }
      mime = resourceManager.getResourceDefinition(req.uri).flatMap(_.mimeType)
      contents = WireMapping.resourceContentsToWire(req.uri, mime, body)
      json <- ok(ReadResourceResult(contents = List(contents)))
    yield json

  /** `resources/subscribe` — record the client's interest in a URI (returns an empty result).
    * Registered only when `settings.resourcesSubscribe` is on, so the `resources.subscribe`
    * capability stays honest. The core does not yet push `notifications/resources/updated`.
    */
  val resourcesSubscribe: RequestHandler[R] = (session, params) =>
    for
      req <- decodeParams[SubscribeRequestParams](params, "resources/subscribe")
      _ <- session.subscribe(req.uri)
      json <- ok(EmptyResult())
    yield json

  /** `resources/unsubscribe` — drop a previously recorded subscription. */
  val resourcesUnsubscribe: RequestHandler[R] = (session, params) =>
    for
      req <- decodeParams[UnsubscribeRequestParams](params, "resources/unsubscribe")
      _ <- session.unsubscribe(req.uri)
      json <- ok(EmptyResult())
    yield json

  // ---- prompts ----

  val promptsList: RequestHandler[R] = (_, _) =>
    val prompts = promptManager.listDefinitions().sortBy(_.name).map(WireMapping.promptToWire)
    ok(ListPromptsResult(prompts = prompts))

  // ---- subscriptions ----

  val subscriptionsListen: RequestHandler[R] = (session, params) =>
    for
      req <- decodeParams[SubscriptionsListenRequestParams](params, "subscriptions/listen")
      requestId <- session.currentRequestId.someOrFail(
        McpError.internalError("subscriptions/listen is missing its request id")
      )
      subscriptionId = requestId.toJsonAST.getOrElse(Json.Null)
      requested = req.notifications
      supportsResourceSubscriptions = modernCapabilities.resources
        .flatMap(_.subscribe)
        .contains(true)
      agreed = SubscriptionFilter(
        toolsListChanged = requested.toolsListChanged.filter(_ => false),
        promptsListChanged = requested.promptsListChanged.filter(_ => false),
        resourcesListChanged = requested.resourcesListChanged.filter(_ => false),
        resourceSubscriptions = requested.resourceSubscriptions
          .filter(_ => supportsResourceSubscriptions)
          .map(
            _.filter(uri =>
              resourceManager.getResourceDefinition(uri).isDefined ||
                resourceManager.findMatchingTemplate(uri).isDefined
            )
          )
          .filter(_.nonEmpty)
      )
      notificationParams = SubscriptionsAcknowledgedNotificationParams(
        notifications = agreed,
        _meta = Some(Map("io.modelcontextprotocol/subscriptionId" -> subscriptionId))
      )
      _ <- session.send(
        com.tjclp.fastmcp.jsonrpc.JsonRpcMessage.Notification(
          NotificationMethods.SubscriptionsAcknowledged,
          notificationParams.toJsonAST.toOption
        )
      )
      result <- ZIO.never
    yield result

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
  private def contextFor(
      session: Session,
      requestMeta: Option[Map[String, Json]] = None
  ): UIO[McpContext] =
    for
      modern <- session.currentRequestContext
      legacyInfo <- session.clientInfo
      legacyCaps <- session.clientCapabilities
    yield McpContext.withSession(
      session,
      modern.flatMap(_.clientInfo).orElse(legacyInfo),
      modern.map(_.clientCapabilities).orElse(legacyCaps),
      requestMeta.orElse(modern.map(_.meta)),
      modern.flatMap(_.requestState)
    )
