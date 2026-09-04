package com.tjclp.fastmcp
package server

import zio.*

import com.tjclp.fastmcp.codec.DefaultDecodeContext
import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.core.wire.{CompleteRequestParams, Completion, Implementation}
import com.tjclp.fastmcp.server.manager.*
import com.tjclp.fastmcp.server.router.{McpRouter, RouterBuilder}
import com.tjclp.fastmcp.server.transport.{HttpTransportBackend, TransportBackend}

/** A completion provider for `completion/complete` (argument autocompletion): given the request
  * (ref + argument + optional context), return candidate values. Registered via
  * [[McpServer.completion]]; when one is registered the server advertises the `completions`
  * capability and routes `completion/complete` to it.
  */
type CompletionHandler[R] =
  (CompleteRequestParams, Option[McpContext]) => ZIO[R, Throwable, Completion]

/** The single, platform-neutral MCP server.
  *
  * Replaces the old `FastMcpServer` (JVM) and `JsMcpServer` (JS) with ONE class in `shared/`.
  * Registration (`tool` / `resource` / `prompt` / annotation scanning) lives entirely here against
  * the platform-neutral managers; the ONLY platform-specific piece is the [[TransportBackend]]
  * given, which drives stdin/stdout or the HTTP server. Identical behavior on JVM and Scala.js.
  *
  * Prefer the [[McpServer$]] factory (`McpServer("name")` / `McpServer.typed[R]("name")`) over
  * constructing this directly.
  *
  * @tparam R
  *   the ZIO environment all handlers may require; provided via `runHttp().provide(...)`.
  */
final class McpServer[R](
    val name: String = "FastMCPScala",
    val version: String = "0.1.0",
    val settings: McpServerSettings = McpServerSettings()
)(using backend: TransportBackend)
    extends McpServerCore[R]:

  val dependencies: List[String] = settings.dependencies

  // Embedded-JSON bounds (objects/arrays inside string arguments) follow the server's own limits.
  protected val decodeContext: McpDecodeContext =
    new DefaultDecodeContext(settings.limits.maxDepth, settings.limits.maxObjectFields)

  val toolManager: ToolManager[R] = new ToolManager[R]()
  val resourceManager: ResourceManager[R] = new ResourceManager[R]()
  val promptManager: PromptManager[R] = new PromptManager[R]()

  // Optional argument-completion provider; when set, `completion/complete` is wired and the
  // `completions` capability is advertised. (Single handler, so a plain atomic holder, not a manager.)
  private val completionRef =
    new java.util.concurrent.atomic.AtomicReference[Option[CompletionHandler[R]]](None)

  // --- Registration (the trait's overloads delegate to these four abstracts) ---

  override def tool[R1 >: R](
      definition: ToolDefinition,
      handler: ContextualToolHandler[R1],
      options: ToolRegistrationOptions
  ): ZIO[Any, Throwable, McpServerCore[R]] =
    toolManager
      .addTool(definition.name, handler.asInstanceOf[ContextualToolHandler[R]], definition, options)
      .as(this)

  override def resource[R1 >: R](
      definition: ResourceDefinition,
      handler: ResourceHandler[R1]
  ): ZIO[Any, Throwable, McpServerCore[R]] =
    resourceManager
      .addStaticResource(definition.uri, handler.asInstanceOf[ResourceHandler[R]], definition)
      .as(this)

  override def resourceTemplate[R1 >: R](
      definition: ResourceDefinition,
      handler: ResourceTemplateHandler[R1]
  ): ZIO[Any, Throwable, McpServerCore[R]] =
    resourceManager
      .addTemplateResource(
        definition.uri,
        handler.asInstanceOf[ResourceTemplateHandler[R]],
        definition
      )
      .as(this)

  override def prompt[R1 >: R](
      definition: PromptDefinition,
      handler: PromptHandler[R1]
  ): ZIO[Any, Throwable, McpServerCore[R]] =
    promptManager
      .addPrompt(definition.name, handler.asInstanceOf[PromptHandler[R]], definition)
      .as(this)

  override def promptContextual[R1 >: R](
      definition: PromptDefinition,
      handler: ContextualPromptHandler[R1]
  ): ZIO[Any, Throwable, McpServerCore[R]] =
    promptManager
      .addContextualPrompt(
        definition.name,
        handler.asInstanceOf[ContextualPromptHandler[R]],
        definition
      )
      .as(this)

  /** Register the `completion/complete` argument-completion provider. Advertises the `completions`
    * capability and routes completion requests to `handler`.
    */
  def completion[R1 >: R](handler: CompletionHandler[R1]): ZIO[Any, Throwable, McpServerCore[R]] =
    ZIO.succeed {
      completionRef.set(Some(handler.asInstanceOf[CompletionHandler[R]]))
      this
    }

  // --- Lifecycle ---

  /** Build the immutable router from the (now-populated) managers + settings. Allocates a
    * [[TaskManager]] only when tasks are enabled.
    */
  private[fastmcp] def buildRouter: IO[Throwable, McpRouter[R]] =
    buildRouterWithTasks.map(_._1)

  /** [[buildRouter]] that also hands back the [[TaskManager]] (when tasks are enabled) so the serve
    * loops (and the JS `startStatefulHttp()` / `startStatelessHttp()` handles) can stop its sweeper
    * and interrupt running tasks on shutdown.
    */
  private[fastmcp] def buildRouterWithTasks: IO[Throwable, (McpRouter[R], Option[TaskManager[R]])] =
    val taskMgr: UIO[Option[TaskManager[R]]] =
      if settings.tasks.enabled then
        TaskManager.make[R](settings.tasks, backend.randomId()).map(Some(_))
      else ZIO.none
    taskMgr.map { tm =>
      val router = RouterBuilder.build[R](
        serverInfo = Implementation(name = name, version = version),
        instructions = None,
        toolManager = toolManager,
        promptManager = promptManager,
        resourceManager = resourceManager,
        settings = settings,
        taskManager = tm,
        completionHandler = completionRef.get()
      )
      (router, tm)
    }

  override def runStdio(): ZIO[R, Throwable, Unit] =
    buildRouterWithTasks.flatMap { (router, tm) =>
      backend.serveStdio(router, settings).ensuring(tm.fold(ZIO.unit)(_.shutdown))
    }

  override def runHttp()(using http: HttpTransportBackend): ZIO[R, Throwable, Unit] =
    buildRouterWithTasks.flatMap { (router, tm) =>
      http.serveHttp(router, settings).ensuring(tm.fold(ZIO.unit)(_.shutdown))
    }

/** The public `McpServer` factory — one definition for both platforms (replaces the per-platform
  * `McpServerBuilders`). Each platform need only provide a `given TransportBackend` in scope.
  *
  * {{{
  *   val server = McpServer("MyServer", "0.1.0")            // McpServer[Any]
  *   val typed  = McpServer.typed[Client]("MyServer")       // McpServer[Client]
  *   typed.runHttp().provide(Client.default)
  * }}}
  */
object McpServer:

  /** Lets the shared `McpServerApp` sugar build a server without naming the concrete type. */
  given (using TransportBackend): McpServerCoreFactory with

    def build(name: String, version: String, settings: McpServerSettings): McpServerCore[Any] =
      new McpServer[Any](name, version, settings)

  def apply(
      name: String = "FastMCPScala",
      version: String = "0.1.0",
      settings: McpServerSettings = McpServerSettings()
  )(using TransportBackend): McpServer[Any] =
    new McpServer[Any](name, version, settings)

  /** Layer-aware factory: `McpServer.typed[Client]("name")` → `McpServer[Client]`; complete
    * `runHttp()` / `runStdio()` with `.provide(...)`.
    */
  def typed[R](
      name: String = "FastMCPScala",
      version: String = "0.1.0",
      settings: McpServerSettings = McpServerSettings()
  )(using TransportBackend): McpServer[R] =
    new McpServer[R](name, version, settings)

  /** Create + run on HTTP in one step (streamable by default; `settings.stateless = true` for
    * stateless).
    */
  def http(
      name: String = "FastMCPScala",
      version: String = "0.1.0",
      settings: McpServerSettings = McpServerSettings()
  )(using TransportBackend, HttpTransportBackend): ZIO[Any, Throwable, Unit] =
    ZIO.succeed(apply(name, version, settings)).flatMap(_.runHttp())

  /** Create + run on stdio in one step. */
  def stdio(
      name: String = "FastMCPScala",
      version: String = "0.1.0",
      settings: McpServerSettings = McpServerSettings()
  )(using TransportBackend): ZIO[Any, Throwable, Unit] =
    ZIO.succeed(apply(name, version, settings)).flatMap(_.runStdio())
