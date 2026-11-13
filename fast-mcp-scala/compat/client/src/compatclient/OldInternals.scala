package compatclient

import zio.*

import com.tjclp.fastmcp.*
import com.tjclp.fastmcp.codec.DefaultDecodeContext
import com.tjclp.fastmcp.core.wire.ServerCapabilities
import com.tjclp.fastmcp.server.manager.{PromptManager, ResourceManager, ToolManager}
import com.tjclp.fastmcp.server.manager.ToolRegistrationOptions
import com.tjclp.fastmcp.server.router.{Builtins, McpRouter, RouterBuilder, SchemaValidator}
import com.tjclp.fastmcp.server.router.ServerHooks
import com.tjclp.fastmcp.server.transport.HttpTransportBackend

/** The lower-level public classes, used the way 1.0.1 let a program use them. Compiled against the
  * released 1.0.1 jar; see [[OldSettings]].
  */
object OldInternals:

  /** 1.0.1: `new ToolManager[R]()`, the only constructor. */
  def toolManager(): ToolManager[Any] = new ToolManager[Any]()

  /** 1.0.1: `DefaultDecodeContext(maxDepth, maxObjectFields)`. */
  def decodeContext(): DefaultDecodeContext = new DefaultDecodeContext(8, 16)

  private val info = Implementation(name = "old-router", version = "1.0.1")

  /** 1.0.1: the 11-parameter `RouterBuilder.build`, the last five through its default getters. */
  def routerWithDefaults(settings: McpServerSettings): McpRouter[Any] =
    RouterBuilder.build[Any](
      info,
      None,
      new ToolManager[Any](),
      new PromptManager[Any](),
      new ResourceManager[Any](),
      settings
    )

  /** 1.0.1: the same `build` with all eleven arguments. */
  def routerPositional(tools: ToolManager[Any], settings: McpServerSettings): McpRouter[Any] =
    RouterBuilder.build[Any](
      info,
      Some("old instructions"),
      tools,
      new PromptManager[Any](),
      new ResourceManager[Any](),
      settings,
      None,
      None,
      SchemaValidator.permissive,
      Nil,
      ServerHooks.noop[Any]
    )

  /** 1.0.1: the 12-parameter `Builtins` constructor, the last three through its default getters. */
  def builtins(): Builtins[Any] =
    new Builtins[Any](
      info,
      None,
      ServerCapabilities(),
      ServerCapabilities(),
      new ToolManager[Any](),
      new PromptManager[Any](),
      new ResourceManager[Any](),
      false,
      false
    )

  /** A third-party `McpServerCore`, complete by the 1.0.1 definition of the trait: it implements
    * the eight members that were abstract then and nothing added since.
    */
  final class OldCore extends McpServerCore[Any]:
    val registered = new java.util.concurrent.atomic.AtomicReference[List[String]](Nil)

    protected val decodeContext: McpDecodeContext = DefaultDecodeContext.default

    private def record(name: String): ZIO[Any, Throwable, McpServerCore[Any]] =
      ZIO.succeed { val _ = registered.updateAndGet(name :: _); this }

    override def tool[R1 >: Any](
        definition: ToolDefinition,
        handler: (Map[String, Any], Option[McpContext]) => ZIO[R1, Throwable, Any],
        options: ToolRegistrationOptions
    ): ZIO[Any, Throwable, McpServerCore[Any]] = record(definition.name)

    override def resource[R1 >: Any](
        definition: ResourceDefinition,
        handler: () => ZIO[R1, Throwable, String | Array[Byte]]
    ): ZIO[Any, Throwable, McpServerCore[Any]] = record(definition.uri)

    override def resourceTemplate[R1 >: Any](
        definition: ResourceDefinition,
        handler: Map[String, String] => ZIO[R1, Throwable, String | Array[Byte]]
    ): ZIO[Any, Throwable, McpServerCore[Any]] = record(definition.uri)

    override def prompt[R1 >: Any](
        definition: PromptDefinition,
        handler: Map[String, Any] => ZIO[R1, Throwable, List[Message]]
    ): ZIO[Any, Throwable, McpServerCore[Any]] = record(definition.name)

    override def promptContextual[R1 >: Any](
        definition: PromptDefinition,
        handler: (Map[String, Any], Option[McpContext]) => ZIO[R1, Throwable, List[Message]]
    ): ZIO[Any, Throwable, McpServerCore[Any]] = record(definition.name)

    override def runStdio(): ZIO[Any, Throwable, Unit] = ZIO.unit

    override def runHttp()(using HttpTransportBackend): ZIO[Any, Throwable, Unit] = ZIO.unit
