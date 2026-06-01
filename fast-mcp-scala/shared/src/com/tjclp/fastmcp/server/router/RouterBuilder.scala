package com.tjclp.fastmcp.server.router

import com.tjclp.fastmcp.core.Tasks
import com.tjclp.fastmcp.core.wire.Implementation
import com.tjclp.fastmcp.server.{CompletionHandler, McpServerSettings}
import com.tjclp.fastmcp.server.manager.{PromptManager, ResourceManager, TaskManager, ToolManager}

/** Assembles an [[McpRouter]] from the populated managers + settings.
  *
  * Honest-capabilities principle: a method's built-in handler is registered ONLY when its backing
  * content exists (tools registered ⇒ `tools/list`+`tools/call`; prompts ⇒ the `prompts` group;
  * etc.). Since [[McpRouter.deriveCapabilities]] reads the registered method set, capabilities can
  * never over-advertise — issue #56 cannot recur.
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
      taskManager: Option[TaskManager[R]] = None,
      completionHandler: Option[CompletionHandler[R]] = None,
      validator: SchemaValidator = SchemaValidator.permissive,
      extraMiddlewares: List[Middleware[R]] = Nil,
      hooks: ServerHooks[R] = ServerHooks.noop[R]
  ): McpRouter[R] =
    val tasksOn = settings.tasks.enabled && taskManager.isDefined

    val resourceDefs = resourceManager.listDefinitions()
    val hasTools = toolManager.listDefinitions().nonEmpty
    val hasStatic = resourceDefs.exists(!_.isTemplate)
    val hasTemplates = resourceDefs.exists(_.isTemplate)
    val hasResources = hasStatic || hasTemplates
    val hasPrompts = promptManager.listDefinitions().nonEmpty
    val hasCompletion = completionHandler.isDefined
    val exposeTemplates = hasTemplates && settings.exposeTemplatesEndpoint
    val loggingEnabled = settings.loggingEnabled
    // Subscribe only matters when resources exist; advertised + wired only when opted in.
    val resourcesSubscribe = settings.resourcesSubscribe && hasResources

    // Which request methods this server will answer — drives capability derivation.
    val methods: Set[String] = Set(Methods.Ping, Methods.Initialize) ++
      Option.when(hasTools)(Set(Methods.ToolsList, Methods.ToolsCall)).getOrElse(Set.empty) ++
      Option
        .when(hasResources)(Set(Methods.ResourcesList, Methods.ResourcesRead))
        .getOrElse(Set.empty) ++
      Option.when(exposeTemplates)(Set(Methods.ResourcesTemplatesList)).getOrElse(Set.empty) ++
      Option.when(hasPrompts)(Set(Methods.PromptsList, Methods.PromptsGet)).getOrElse(Set.empty) ++
      Option.when(hasCompletion)(Set(Methods.CompletionComplete)).getOrElse(Set.empty) ++
      Option.when(loggingEnabled)(Set(Methods.LoggingSetLevel)).getOrElse(Set.empty) ++
      Option
        .when(resourcesSubscribe)(Set(Methods.ResourcesSubscribe, Methods.ResourcesUnsubscribe))
        .getOrElse(Set.empty)

    val listChanged = false // dynamic list-change notifications not implemented yet

    val capabilities =
      McpRouter.deriveCapabilities(methods, tasksOn, resourcesSubscribe, listChanged)

    val builtins = new Builtins[R](
      serverInfo = serverInfo,
      instructions = instructions,
      capabilities = capabilities,
      toolManager = toolManager,
      promptManager = promptManager,
      resourceManager = resourceManager,
      tasksEnabled = tasksOn,
      exposeTemplates = exposeTemplates,
      completionHandler = completionHandler
    )

    val taskHandlers = taskManager.map(tm => new TaskHandlers[R](tm))

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
           Map(
             Methods.ResourcesList -> builtins.resourcesList,
             Methods.ResourcesRead -> builtins.resourcesRead
           )
         else Map.empty) ++
        (if resourcesSubscribe then
           Map(
             Methods.ResourcesSubscribe -> builtins.resourcesSubscribe,
             Methods.ResourcesUnsubscribe -> builtins.resourcesUnsubscribe
           )
         else Map.empty) ++
        (if exposeTemplates then
           Map(Methods.ResourcesTemplatesList -> builtins.resourcesTemplatesList)
         else Map.empty) ++
        (if hasPrompts then
           Map(
             Methods.PromptsList -> builtins.promptsList,
             Methods.PromptsGet -> builtins.promptsGet
           )
         else Map.empty) ++
        (if loggingEnabled then Map(Methods.LoggingSetLevel -> builtins.loggingSetLevel)
         else Map.empty) ++
        (if hasCompletion then Map(Methods.CompletionComplete -> builtins.complete)
         else Map.empty) ++
        (taskHandlers match
          case Some(th) if tasksOn =>
            Map(
              Tasks.MethodTasksGet -> th.get,
              Tasks.MethodTasksList -> th.list,
              Tasks.MethodTasksCancel -> th.cancel,
              Tasks.MethodTasksResult -> th.result
            )
          case _ => Map.empty
        )

    val notificationHandlers: Map[String, NotificationHandler[R]] =
      Map(com.tjclp.fastmcp.core.wire.NotificationMethods.Initialized -> builtins.initialized)

    // Chain order (head = outermost): validation runs first, then task augmentation, then the
    // handler. ValidationMiddleware with the permissive default is a pass-through.
    val middlewares: List[Middleware[R]] =
      (new ValidationMiddleware[R](validator, toolManager) :: Nil) ++
        taskManager.filter(_ => tasksOn).map(tm => new TaskMiddleware[R](tm, toolManager)).toList ++
        extraMiddlewares

    new McpRouter[R](
      requestHandlers = requestHandlers,
      notificationHandlers = notificationHandlers,
      middlewares = middlewares,
      hooks = hooks,
      tasksEnabled = tasksOn,
      resourcesSubscribe = resourcesSubscribe,
      listChanged = listChanged
    )
