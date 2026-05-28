package com.tjclp.fastmcp.server.router

import com.tjclp.fastmcp.core.wire.Implementation
import com.tjclp.fastmcp.server.McpServerSettings
import com.tjclp.fastmcp.server.manager.{PromptManager, ResourceManager, ToolManager}

/** Assembles an [[McpRouter]] from the populated managers + settings.
  *
  * Honest-capabilities principle: a method's built-in handler is registered ONLY when its backing
  * content exists (tools registered ⇒ `tools/list`+`tools/call`; prompts ⇒ `prompts/*`; etc.).
  * Since [[McpRouter.deriveCapabilities]] reads the registered method set, capabilities can never
  * over-advertise — issue #56 cannot recur.
  *
  * Called by the server orchestrator at `runStdio()` / `runHttp()`, after registration is done.
  */
object RouterBuilder:

  def build[R](
      serverInfo: Implementation,
      instructions: Option[String],
      toolManager: ToolManager[R],
      promptManager: PromptManager[R],
      resourceManager: ResourceManager[R],
      settings: McpServerSettings,
      middlewares: List[Middleware[R]] = Nil,
      hooks: ServerHooks[R] = ServerHooks.noop[R],
      loggingEnabled: Boolean = false
  ): McpRouter[R] =
    val tasksEnabled = settings.tasks.enabled

    val resourceDefs = resourceManager.listDefinitions()
    val hasTools = toolManager.listDefinitions().nonEmpty
    val hasStatic = resourceDefs.exists(!_.isTemplate)
    val hasTemplates = resourceDefs.exists(_.isTemplate)
    val hasResources = hasStatic || hasTemplates
    val hasPrompts = promptManager.listDefinitions().nonEmpty
    val exposeTemplates = hasTemplates && settings.exposeTemplatesEndpoint

    // Which request methods this server will answer — drives capability derivation.
    val methods: Set[String] = Set(Methods.Ping, Methods.Initialize) ++
      Option.when(hasTools)(Set(Methods.ToolsList, Methods.ToolsCall)).getOrElse(Set.empty) ++
      Option.when(hasResources)(Set(Methods.ResourcesList, Methods.ResourcesRead)).getOrElse(Set.empty) ++
      Option.when(exposeTemplates)(Set(Methods.ResourcesTemplatesList)).getOrElse(Set.empty) ++
      Option.when(hasPrompts)(Set(Methods.PromptsList, Methods.PromptsGet)).getOrElse(Set.empty) ++
      Option.when(loggingEnabled)(Set(Methods.LoggingSetLevel)).getOrElse(Set.empty)

    val resourcesSubscribe = false // subscribe handlers not implemented yet
    val listChanged = false // dynamic list-change notifications not implemented yet

    val capabilities = McpRouter.deriveCapabilities(methods, tasksEnabled, resourcesSubscribe, listChanged)

    val builtins = new Builtins[R](
      serverInfo = serverInfo,
      instructions = instructions,
      capabilities = capabilities,
      toolManager = toolManager,
      promptManager = promptManager,
      resourceManager = resourceManager,
      tasksEnabled = tasksEnabled,
      exposeTemplates = exposeTemplates
    )

    // Map each registered method to its built-in handler.
    val requestHandlers: Map[String, RequestHandler[R]] =
      Map(
        Methods.Ping -> builtins.ping,
        Methods.Initialize -> builtins.initialize
      ) ++
        (if hasTools then
           Map(Methods.ToolsList -> builtins.toolsList, Methods.ToolsCall -> builtins.toolsCall)
         else Map.empty) ++
        (if hasResources then
           Map(Methods.ResourcesList -> builtins.resourcesList, Methods.ResourcesRead -> builtins.resourcesRead)
         else Map.empty) ++
        (if exposeTemplates then
           Map(Methods.ResourcesTemplatesList -> builtins.resourcesTemplatesList)
         else Map.empty) ++
        (if hasPrompts then
           Map(Methods.PromptsList -> builtins.promptsList, Methods.PromptsGet -> builtins.promptsGet)
         else Map.empty) ++
        (if loggingEnabled then Map(Methods.LoggingSetLevel -> builtins.loggingSetLevel)
         else Map.empty)

    val notificationHandlers: Map[String, NotificationHandler[R]] =
      Map(com.tjclp.fastmcp.core.wire.NotificationMethods.Initialized -> builtins.initialized)

    new McpRouter[R](
      requestHandlers = requestHandlers,
      notificationHandlers = notificationHandlers,
      middlewares = middlewares,
      hooks = hooks,
      tasksEnabled = tasksEnabled,
      resourcesSubscribe = resourcesSubscribe,
      listChanged = listChanged
    )
