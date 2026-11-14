package downstream

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import com.tjclp.fastmcp.*
import com.tjclp.fastmcp.codec.DefaultDecodeContext
import com.tjclp.fastmcp.core.wire.ServerCapabilities
import com.tjclp.fastmcp.server.manager.{PromptManager, ResourceManager, ToolManager}
import com.tjclp.fastmcp.server.router.{Builtins, RouterBuilder, SchemaValidator, ServerHooks}

/** Source compatibility of the call shapes that have a hidden 1.0.x forwarder next to them
  * (TJC-5134). The assertion is mostly that this file COMPILES, from a package outside
  * `com.tjclp.fastmcp` like any user's code: the `private[fastmcp]` old-arity constructors,
  * `apply`s, `copy`s and `build` must not be visible here, so no call below may become ambiguous or
  * resolve to one of them. Values are checked too, so a call that silently changed target shows.
  */
class SourceCompatibilityTest extends AnyFunSuite with Matchers:

  private val allServerFields = McpServerSettings(
    "h",
    1,
    "/e",
    true,
    true,
    None,
    None,
    true,
    true,
    true,
    None,
    None,
    2,
    None,
    TaskSettings(),
    LimitSettings(),
    Some("i")
  )

  test("McpServerSettings: all named, partly positional, 17 positional, new, copy") {
    McpServerSettings().port shouldBe 8000
    McpServerSettings(host = "0.0.0.0", port = 1, validateToolInput = false).port shouldBe 1
    McpServerSettings("h", 2).port shouldBe 2
    McpServerSettings("h", 3, instructions = Some("i")).instructions shouldBe Some("i")
    new McpServerSettings("h", 4).port shouldBe 4
    new McpServerSettings(port = 5).port shouldBe 5

    // Seventeen positional arguments, the whole 1.0.x parameter list.
    allServerFields shouldBe McpServerSettings(
      host = "h",
      port = 1,
      httpEndpoint = "/e",
      exposeTemplatesEndpoint = true,
      stateless = true,
      disallowDelete = true,
      loggingEnabled = true,
      resourcesSubscribe = true,
      maxRequestBodyBytes = 2,
      maxSessions = None,
      sessionIdleTimeout = None,
      instructions = Some("i")
    )

    val custom = McpServerSettings(contextHeaders = Set("x-user"), dnsRebindingProtection = false)
    custom.copy(port = 6) shouldBe
      McpServerSettings(port = 6, contextHeaders = Set("x-user"), dnsRebindingProtection = false)
    custom.copy("other").contextHeaders shouldBe Set("x-user")
    custom.copy().eq(custom) shouldBe false
    custom.copy() shouldBe custom
  }

  test("TaskSettings: all named, partly positional, 11 positional, copy") {
    TaskSettings(enabled = true).enabled shouldBe true
    TaskSettings(true, 5L).defaultTtlMs shouldBe 5L
    TaskSettings(true, maxBytesTotal = 1L << 30).maxBytesTotal shouldBe 1L << 30
    TaskSettings(true, 1L, 2L, 3L, 4, 5, 6, 7, 8L, 9L, TaskOwnerKey.Transport) shouldBe
      new TaskSettings(true, 1L, 2L, 3L, 4, 5, 6, 7, 8L, 9L, TaskOwnerKey.Transport)
    val custom = TaskSettings(maxRequestBytes = 4096L)
    custom.copy(enabled = true) shouldBe TaskSettings(enabled = true, maxRequestBytes = 4096L)
    custom.copy(true, 1L).maxRequestBytes shouldBe 4096L
  }

  test("LimitSettings: all named, partly positional, 5 positional, copy") {
    LimitSettings(maxDepth = 9).maxDepth shouldBe 9
    LimitSettings(1024, 9).maxDepth shouldBe 9
    LimitSettings(1024, maxValues = 12).maxValues shouldBe 12
    LimitSettings(1, 2, 3, 4, 5) shouldBe new LimitSettings(1, 2, 3, 4, 5)
    LimitSettings(1, 2, 3, 4, 5).maxOutboundMessages shouldBe LimitSettings().maxOutboundMessages
    val custom = LimitSettings(maxValues = 12)
    custom.copy(maxDepth = 3) shouldBe LimitSettings(maxDepth = 3, maxValues = 12)
    custom.copy(1, 2, 3, 4, 5).maxValues shouldBe 12
  }

  test("apply as a function value has one meaning: the current parameter list") {
    // With a visible old-arity overload each of these would be an ambiguous reference.
    val limits = LimitSettings.apply
    limits(1, 2, 3, 4, 5, 6, 7, 8, 9, 10).maxNumberExponent shouldBe 10
    val tasks = TaskSettings.apply
    tasks(
      true,
      1L,
      2L,
      3L,
      4,
      5,
      6,
      7,
      8L,
      9L,
      TaskOwnerKey.Transport,
      10L,
      11L,
      12L,
      13L
    ).maxBytesTotal shouldBe 13L
    val server = McpServerSettings.apply
    server(
      "h",
      1,
      "/e",
      true,
      true,
      None,
      None,
      true,
      true,
      true,
      None,
      None,
      2,
      None,
      TaskSettings(),
      LimitSettings(),
      Some("i"),
      SkillSettings(),
      Set.empty,
      true,
      true,
      McpServerSettings.DefaultStdioDrainTimeout
    ) shouldBe allServerFields
    val routers = RouterBuilder.build[Any]
    val settings = McpServerSettings()
    routers(
      Implementation(name = "n", version = "v"),
      None,
      new ToolManager[Any](),
      new PromptManager[Any](),
      new ResourceManager[Any](),
      settings,
      None,
      None,
      SchemaValidator.permissive,
      Nil,
      ServerHooks.noop[Any],
      None
    ).limits shouldBe settings.limits
  }

  test("ToolManager, DefaultDecodeContext, Builtins and RouterBuilder.build") {
    new ToolManager[Any]().listDefinitions() shouldBe Nil
    new ToolManager[Any].listDefinitions() shouldBe Nil
    new ToolManager[Any](false).listDefinitions() shouldBe Nil
    new ToolManager[Any](validateInput = false).listDefinitions() shouldBe Nil

    new DefaultDecodeContext().maxDepth shouldBe 64
    new DefaultDecodeContext(8).maxDepth shouldBe 8
    new DefaultDecodeContext(8, 16).maxObjectFields shouldBe 16
    new DefaultDecodeContext(8, 16, maxValues = 3).maxValues shouldBe 3

    val info = Implementation(name = "n", version = "v")
    def tools = new ToolManager[Any]()
    def prompts = new PromptManager[Any]()
    def resources = new ResourceManager[Any]()
    val capabilities = ServerCapabilities()

    // Nine arguments (the rest defaulted), then the twelve of 1.0.x.
    new Builtins[Any](
      info,
      None,
      capabilities,
      capabilities,
      tools,
      prompts,
      resources,
      false,
      false
    )
    new Builtins[Any](
      info,
      None,
      capabilities,
      capabilities,
      tools,
      prompts,
      resources,
      false,
      false,
      None,
      ServerHooks.noop[Any],
      LimitSettings()
    )

    // Six arguments (the rest defaulted), then the eleven of 1.0.x.
    val settings = McpServerSettings()
    RouterBuilder.build[Any](info, None, tools, prompts, resources, settings).limits shouldBe
      settings.limits
    RouterBuilder
      .build[Any](
        info,
        None,
        tools,
        prompts,
        resources,
        settings,
        None,
        None,
        SchemaValidator.permissive,
        Nil,
        ServerHooks.noop[Any]
      )
      .limits shouldBe settings.limits
  }
