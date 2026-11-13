package com.tjclp.fastmcp.compat

import java.lang.reflect.{Constructor, Method, Modifier}

import compatclient.{OldApp, OldInternals, OldSettings}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.codec.{DefaultDecodeContext, JsonLimits}
import com.tjclp.fastmcp.core.TaskOwnerKey
import com.tjclp.fastmcp.core.skills.{McpSkill, SkillUri}
import com.tjclp.fastmcp.core.wire.{Implementation, Resource, ServerCapabilities, Skill}
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.manager.{PromptManager, ResourceManager, ToolManager}
import com.tjclp.fastmcp.server.router.{Builtins, McpRouter, RouterBuilder, SchemaValidator}
import com.tjclp.fastmcp.server.router.{ServerHooks, Session}
import com.tjclp.fastmcp.server.skills.{SkillFileContent, SkillProvider}
import com.tjclp.fastmcp.server.transport.MessageLoop

/** Binary compatibility with fast-mcp-scala 1.0.x, proved at link time (TJC-5134).
  *
  * Two independent checks, both on the current classes:
  *
  *   - '''Cross-version.''' `compatclient.*` is compiled against the released 1.0.1 jar
  *     (`fast-mcp-scala.compat.client`), so its bytecode names the 1.0.1 signatures. Calling it
  *     here links those signatures against the current classes; a missing one is a
  *     `NoSuchMethodError`. It covers the settings classes, the lower-level constructors, a
  *     third-party `McpServerCore`, and a server whose annotation and contract macros were expanded
  *     by 1.0.1.
  *   - '''Reflective.''' Every 1.0.x signature the library keeps only as a `private[fastmcp]`
  *     forwarder is looked up by its exact JVM descriptor and invoked. This needs no released jar,
  *     and it keeps guarding the forwarders once a later release becomes the MiMa baseline (MiMa
  *     skips members that are package-private in the baseline).
  */
class BinaryCompatibilityTest extends AnyFunSuite with Matchers:

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  // Current settings whose 1.1.0 fields are all away from their defaults.
  private val serverWithNewFields = McpServerSettings(
    skills = SkillSettings(enabled = true),
    contextHeaders = Set("x-user"),
    validateToolInput = false,
    dnsRebindingProtection = false
  )

  private val tasksWithNewFields = TaskSettings(
    maxRequestBytes = 1024L,
    maxResultBytes = 2048L,
    maxBytesPerOwner = 4096L,
    maxBytesTotal = 8192L
  )

  private val limitsWithNewFields = LimitSettings(
    maxOutboundMessages = 7,
    maxArrayElements = 8,
    maxValues = 9,
    maxNumberDigits = 10,
    maxNumberExponent = 11
  )

  // ---------------------------------------------------------------------------------------------
  // Cross-version: code compiled against 1.0.1
  // ---------------------------------------------------------------------------------------------

  test("1.0.1 client: McpServerSettings construction gives the 1.1.0 fields their defaults") {
    OldSettings.serverDefaults() shouldBe McpServerSettings()
    OldSettings.serverNamed() shouldBe McpServerSettings(port = 9001, instructions = Some("old"))
    OldSettings.serverWithNew() shouldBe McpServerSettings(host = "0.0.0.0")
    OldSettings.serverPositional() shouldBe McpServerSettings(
      host = "old.example",
      port = 9002,
      httpEndpoint = "/old",
      exposeTemplatesEndpoint = true,
      stateless = true,
      keepAliveInterval = Some(java.time.Duration.ofSeconds(5)),
      sessionIdleTimeout = None,
      disallowDelete = true,
      loggingEnabled = true,
      resourcesSubscribe = true,
      allowedHosts = Some(Set("old.example")),
      allowedOrigins = Some(Set("https://old.example")),
      maxRequestBodyBytes = 4096,
      maxSessions = Some(7),
      tasks = TaskSettings(enabled = true),
      limits = LimitSettings(maxDepth = 9),
      instructions = Some("positional")
    )
  }

  test("1.0.1 client: McpServerSettings.copy keeps the 1.1.0 fields of the copied value") {
    val copied = OldSettings.serverCopy(serverWithNewFields)
    copied shouldBe serverWithNewFields.copy(port = 9003)
    copied.skills shouldBe SkillSettings(enabled = true)
    copied.contextHeaders shouldBe Set("x-user")
    copied.validateToolInput shouldBe false
    copied.dnsRebindingProtection shouldBe false
  }

  test("1.0.1 client: a 17-pattern McpServerSettings extractor still reads its positions") {
    val settings = serverWithNewFields.copy(host = "h", port = 5, instructions = Some("i"))
    OldSettings.serverFields(settings) shouldBe (("h", 5, Some("i")))
  }

  test("1.0.1 client: TaskSettings construction, copy and extractor") {
    OldSettings.taskDefaults() shouldBe TaskSettings()
    OldSettings.taskNamed() shouldBe TaskSettings(enabled = true, maxStoredTotal = 11)
    OldSettings.taskPositional() shouldBe TaskSettings(
      enabled = true,
      defaultTtlMs = 1000L,
      maxTtlMs = 2000L,
      pollIntervalMs = 50L,
      maxConcurrentPerSession = 2,
      maxConcurrentTotal = 4,
      maxStoredPerOwner = 3,
      maxStoredTotal = 5,
      minResultRetentionMs = 10L,
      sweepIntervalMs = 20L,
      ownerKey = TaskOwnerKey.Transport
    )
    val copied = OldSettings.taskCopy(tasksWithNewFields)
    copied shouldBe tasksWithNewFields.copy(pollIntervalMs = 77L)
    copied.maxRequestBytes shouldBe 1024L
    copied.maxBytesTotal shouldBe 8192L
    OldSettings.taskFields(tasksWithNewFields.copy(enabled = true, defaultTtlMs = 5L)) shouldBe
      ((true, 5L, TaskOwnerKey.Transport))
  }

  test("1.0.1 client: LimitSettings construction, copy and extractor") {
    OldSettings.limitDefaults() shouldBe LimitSettings()
    OldSettings.limitNamed() shouldBe LimitSettings(maxDepth = 12)
    OldSettings.limitPositional() shouldBe LimitSettings(
      maxFrameChars = 1000,
      maxDepth = 10,
      maxObjectFields = 20,
      maxUriChars = 30,
      maxSubscriptionsPerSession = 40
    )
    val copied = OldSettings.limitCopy(limitsWithNewFields)
    copied shouldBe limitsWithNewFields.copy(maxUriChars = 99)
    copied.maxOutboundMessages shouldBe 7
    copied.maxNumberExponent shouldBe 11
    OldSettings.limitFields(limitsWithNewFields.copy(maxFrameChars = 3)) shouldBe ((3, 1024))
  }

  // A known limit, pinned so that it is not rediscovered: `Mirror.fromProduct` reads one element
  // per current field. A type class that a 1.0.x jar derived from the `Mirror` of a settings class
  // (a JSON decoder, say) passes the old number of fields and fails. Recompiling fixes it.
  // CONTRIBUTING.md, "What MiMa does not see".
  test("1.0.1 client: known limit, a Mirror-derived constructor of a settings class fails") {
    an[IndexOutOfBoundsException] should be thrownBy OldSettings.limitFromMirror()
  }

  test("1.0.1 client: new ToolManager() validates input, the 1.1.0 default") {
    val manager = OldInternals.toolManager()
    manager.listDefinitions() shouldBe Nil
    runUnsafe(
      manager.addTool(
        "echo",
        (args, _) => ZIO.succeed(args.getOrElse("text", "")),
        com.tjclp.fastmcp.core.ToolDefinition(
          name = "echo",
          description = None,
          inputSchema = com.tjclp.fastmcp.core.ToolInputSchema.unsafeFromJsonString(
            """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}"""
          )
        )
      )
    )
    manager.inputValidationReport("echo").enforced shouldBe true
  }

  test("1.0.1 client: DefaultDecodeContext(maxDepth, maxObjectFields)") {
    val context = OldInternals.decodeContext()
    context.maxDepth shouldBe 8
    context.maxObjectFields shouldBe 16
    context.maxArrayElements shouldBe JsonLimits.DefaultMaxArrayElements
    context.maxValues shouldBe JsonLimits.DefaultMaxValues
    context.maxNumberDigits shouldBe JsonLimits.DefaultMaxNumberDigits
    context.maxNumberExponent shouldBe JsonLimits.DefaultMaxNumberExponent
  }

  test("1.0.1 client: the 11-parameter RouterBuilder.build and the 12-parameter Builtins") {
    // An enabled skills setting changes nothing here: the old `build` wires no skill registry.
    val router = OldInternals.routerWithDefaults(McpServerSettings(skills = SkillSettings(true)))
    // No tool is registered, so `tools/list` is not wired: the router answers, with an error.
    rpc(router, "tools/list", "{}").toJson should include("-32601")
    rpc(router, "skills/list", "{}").toJson should include("-32601")

    val tools = OldInternals.toolManager()
    val positional = OldInternals.routerPositional(tools, McpServerSettings())
    val initialized = initialize(positional)
    initialized._2.toJson should include("old instructions")

    OldInternals.builtins() shouldBe a[Builtins[?]]
  }

  test(
    "1.0.1 client: an McpServerCore from before the Skills extension fails loudly, not silently"
  ) {
    val core = new OldInternals.OldCore
    val className = "compatclient.OldInternals$OldCore"

    def failure(effect: ZIO[Any, Throwable, Any]): Throwable =
      runUnsafe(effect.flip.orElseFail(new AssertionError("the default did not fail")))

    val skills = failure(core.skills(Nil))
    skills shouldBe an[UnsupportedOperationException]
    skills.getMessage should (include(className) and include("McpServerCore.skills"))
    skills.getMessage should include("io.modelcontextprotocol/skills")

    val skill = McpSkill.fromMarkdown("acme/copy", "---\nname: copy\ndescription: d\n---\n")
    failure(core.skill(skill)).getMessage should include("McpServerCore.skills")
    failure(core.removeSkills(List("skill://acme/copy"))).getMessage should
      include("McpServerCore.removeSkills")
    failure(core.skillProvider(EmptyProvider)).getMessage should
      include("McpServerCore.skillProvider")

    // The rest of the trait works on the old implementation, 1.1.0 additions included.
    core.toolInputValidation shouldBe Map.empty
    val _ = runUnsafe(core.tool(OldApp.tools.head))
    core.registered.get() shouldBe List("typed-sum")
  }

  test("1.0.1 client: a server whose macros 1.0.1 expanded registers and serves everything") {
    val core = runUnsafe(OldApp.buildCore)
    core shouldBe a[McpServer[?]]
    val server = core match
      case s: McpServer[Any] @unchecked => s
      case other => fail(s"unexpected core: $other")
    server.name shouldBe "old-app"
    server.settings shouldBe McpServerSettings(
      exposeTemplatesEndpoint = true,
      instructions = Some("compiled against 1.0.1")
    )
    server.toolManager.listDefinitions().map(_.name).toSet shouldBe
      Set(
        "add",
        "label",
        "area",
        "effect",
        "who",
        "defaulted",
        "typed-sum",
        "typed-zio",
        "typed-defaulted"
      )
    // Every tool 1.0.1 registered is validated by default now, and its schema compiles.
    server.toolInputValidation.values.map(_.enforced).toSet shouldBe Set(true)

    val router = runUnsafe(server.buildRouter)
    val (session, init) = initialize(router)
    init.toJson should include("compiled against 1.0.1")

    def call(name: String, arguments: String): String =
      rpc(router, "tools/call", s"""{"name":"$name","arguments":$arguments}""", session).toJson

    val listed = rpc(router, "tools/list", "{}", session).toJson
    listed should include("\"First number\"")
    listed should include("\"The text of the label\"")
    listed should (include("\"circle\"") and include("\"square\""))
    listed should include("\"outputSchema\"")

    call("add", """{"a":2,"b":3}""") should include("\"text\":\"5\"")
    call("label", """{"label":{"text":"hi","upper":true}}""") should include("\"text\":\"HI\"")
    call("area", """{"shape":"square","scale":2.5}""") should include("\"text\":\"2.50\"")
    call("area", """{"shape":"square"}""") should include("\"text\":\"1.00\"")
    call("effect", """{"word":"abc"}""") should include("\"text\":\"cba\"")
    call("who", "{}") should include("client=compat-test")
    call("typed-sum", """{"left":20,"right":22}""") should include(
      "\"structuredContent\":{\"sum\":42}"
    )
    call("typed-zio", """{"name":"Ada"}""") should include("hi Ada")
    // Input validation, new in 1.1.0, applies to the 1.0.1-derived schema.
    call("add", """{"a":"two","b":3}""") should include("\"isError\":true")

    // What does change for a jar that is not recompiled. 1.0.1 listed a parameter with a Scala
    // default in `required` (and applied the default when it was missing); that schema is in the
    // old bytecode, and 1.1.0 enforces the advertised schema by default. Sending the argument
    // works; omitting it, which 1.0.x accepted, is refused until the server is recompiled (1.1.0
    // derives the parameter as optional). CHANGELOG, "Compatibility".
    listed should include(""""required":["x","y"]""")
    call("defaulted", """{"x":1,"y":2}""") should include("\"text\":\"3\"")
    call("defaulted", """{"x":1}""") should
      (include("\"isError\":true") and include("missing required property 'y'"))
    call("typed-defaulted", """{"value":2,"factor":5}""") should include("\"text\":\"10\"")
    call("typed-defaulted", """{"value":2}""") should
      (include("\"isError\":true") and include("missing required property 'factor'"))

    def read(uri: String): String =
      rpc(router, "resources/read", s"""{"uri":"$uri"}""", session).toJson

    read("old://welcome") should include("welcome from 1.0.1")
    read("old://users/42") should include("user 42")
    read("old://typed") should include("\"text\":\"typed\"")
    read("old://typed/Ada") should include("typed Ada")
    rpc(router, "resources/templates/list", "{}", session).toJson should
      (include("old://users/{userId}") and include("old://typed/{name}"))

    def prompt(name: String): String =
      rpc(router, "prompts/get", s"""{"name":"$name","arguments":{"name":"Ada"}}""", session).toJson

    prompt("greet") should include("Greet Ada.")
    prompt("typed-greet") should include("Hello Ada!")
  }

  // ---------------------------------------------------------------------------------------------
  // Reflective: the exact JVM signatures of 1.0.x
  // ---------------------------------------------------------------------------------------------

  private val int = classOf[Int]
  private val long = classOf[Long]
  private val boolean = classOf[Boolean]
  private val option = classOf[Option[?]]

  /** One settings class as one release had it: its field names and JVM parameter types then, and a
    * current value with every later field away from its default.
    */
  private final case class Shape(
      release: String,
      cls: Class[?],
      companion: AnyRef,
      fields: List[String],
      types: List[Class[?]],
      defaults: Product,
      withNewFields: Product
  ):
    def arity: Int = fields.size
    def name: String = s"${cls.getSimpleName} of $release"

  private val serverFields = List(
    "host",
    "port",
    "httpEndpoint",
    "exposeTemplatesEndpoint",
    "stateless",
    "keepAliveInterval",
    "sessionIdleTimeout",
    "disallowDelete",
    "loggingEnabled",
    "resourcesSubscribe",
    "allowedHosts",
    "allowedOrigins",
    "maxRequestBodyBytes",
    "maxSessions",
    "tasks",
    "limits",
    "instructions"
  )

  private val serverTypes = List(
    classOf[String],
    int,
    classOf[String],
    boolean,
    boolean,
    option,
    option,
    boolean,
    boolean,
    boolean,
    option,
    option,
    int,
    option,
    classOf[TaskSettings],
    classOf[LimitSettings],
    option
  )

  private val shapes = List(
    // 1.0.0 had no `instructions`: sixteen fields.
    Shape(
      "1.0.0",
      classOf[McpServerSettings],
      McpServerSettings,
      serverFields.take(16),
      serverTypes.take(16),
      McpServerSettings(),
      serverWithNewFields.copy(instructions = Some("kept"))
    ),
    Shape(
      "1.0.1",
      classOf[McpServerSettings],
      McpServerSettings,
      serverFields,
      serverTypes,
      McpServerSettings(),
      serverWithNewFields
    ),
    Shape(
      "1.0.x",
      classOf[TaskSettings],
      TaskSettings,
      List(
        "enabled",
        "defaultTtlMs",
        "maxTtlMs",
        "pollIntervalMs",
        "maxConcurrentPerSession",
        "maxConcurrentTotal",
        "maxStoredPerOwner",
        "maxStoredTotal",
        "minResultRetentionMs",
        "sweepIntervalMs",
        "ownerKey"
      ),
      List(boolean, long, long, long, int, int, int, int, long, long, classOf[TaskOwnerKey]),
      TaskSettings(),
      tasksWithNewFields
    ),
    Shape(
      "1.0.x",
      classOf[LimitSettings],
      LimitSettings,
      List(
        "maxFrameChars",
        "maxDepth",
        "maxObjectFields",
        "maxUriChars",
        "maxSubscriptionsPerSession"
      ),
      List(int, int, int, int, int),
      LimitSettings(),
      limitsWithNewFields
    )
  )

  private def boxed(values: Iterator[Any]): Array[AnyRef] =
    values.map {
      case ref: AnyRef => ref
      case other => fail(s"not a reference: $other")
    }.toArray

  private def publicMethod(cls: Class[?], name: String, types: List[Class[?]]): Method =
    val method = cls.getMethod(name, types*)
    withClue(s"$method: ") { Modifier.isPublic(method.getModifiers) shouldBe true }
    method

  private def publicConstructor(cls: Class[?], types: List[Class[?]]): Constructor[?] =
    val constructor = cls.getConstructor(types*)
    withClue(s"$constructor: ") { Modifier.isPublic(constructor.getModifiers) shouldBe true }
    constructor

  for shape <- shapes do
    val n = shape.arity

    test(s"${shape.name}: its $n fields are still the first $n, in the same order") {
      shape.defaults.productElementNames.take(n).toList shouldBe shape.fields
      shape.defaults.productArity should be > n
    }

    test(s"${shape.name}: the $n-argument constructor and apply give later fields their defaults") {
      val arguments = boxed(shape.defaults.productIterator.take(n))
      publicConstructor(shape.cls, shape.types).newInstance(arguments*) shouldBe shape.defaults

      val onCompanion = publicMethod(shape.companion.getClass, "apply", shape.types)
      Modifier.isStatic(onCompanion.getModifiers) shouldBe false
      onCompanion.invoke(shape.companion, arguments*) shouldBe shape.defaults

      // The static forwarder Java (and `invokestatic` call sites) link to. The receiver is unused.
      val static = publicMethod(shape.cls, "apply", shape.types)
      Modifier.isStatic(static.getModifiers) shouldBe true
      static.invoke(shape.companion, arguments*) shouldBe shape.defaults
    }

    test(s"${shape.name}: the $n-argument copy keeps every later field") {
      val current = shape.withNewFields
      current should not be shape.defaults
      val copy = publicMethod(shape.cls, "copy", shape.types)
      val unchanged = copy.invoke(current, boxed(current.productIterator.take(n))*)
      unchanged shouldBe current

      // Replace the old fields by the defaults' own; the newer fields must still be `current`'s.
      val replaced = copy.invoke(current, boxed(shape.defaults.productIterator.take(n))*) match
        case product: Product => product
        case other => fail(s"not a product: $other")
      replaced.productIterator.take(n).toList shouldBe shape.defaults.productIterator.take(n).toList
      replaced.productIterator.drop(n).toList shouldBe current.productIterator.drop(n).toList
      replaced.productIterator.drop(n).toList should not be
        shape.defaults.productIterator.drop(n).toList
    }

    // Scala 3 gives a case class `apply` no default getters of its own: `Foo(x = 1)` reads the
    // constructor's (`$lessinit$greater$default$N`), on the companion or through its static
    // forwarder. `copy` has its own, on the instance.
    test(s"${shape.name}: the positional default getters 1 to $n keep their meaning") {
      val current = shape.withNewFields
      for i <- 1 to n do
        val expected = shape.defaults.productElement(i - 1)
        val getter = s"$$lessinit$$greater$$default$$$i"
        withClue(s"default $i (${shape.fields(i - 1)}): ") {
          publicMethod(shape.companion.getClass, getter, Nil).invoke(shape.companion) shouldBe
            expected
          val static = publicMethod(shape.cls, getter, Nil)
          Modifier.isStatic(static.getModifiers) shouldBe true
          static.invoke(shape.companion) shouldBe expected
          publicMethod(shape.cls, s"copy$$default$$$i", Nil).invoke(current) shouldBe
            current.productElement(i - 1)
        }
    }

  test("the companions of the settings classes still print as their class name") {
    McpServerSettings.toString shouldBe "McpServerSettings"
    TaskSettings.toString shouldBe "TaskSettings"
  }

  test("ToolManager: the no-argument constructor of 1.0.x") {
    publicConstructor(classOf[ToolManager[?]], Nil).newInstance() shouldBe a[ToolManager[?]]
  }

  test("DefaultDecodeContext: the (Int, Int) constructor of 1.0.x") {
    val context = publicConstructor(classOf[DefaultDecodeContext], List(int, int))
      .newInstance(Int.box(5), Int.box(6)) match
      case c: DefaultDecodeContext => c
      case other => fail(s"unexpected: $other")
    (context.maxDepth, context.maxObjectFields) shouldBe ((5, 6))
    context.maxArrayElements shouldBe new DefaultDecodeContext().maxArrayElements
  }

  private val builtins12 = List(
    classOf[Implementation],
    option,
    classOf[ServerCapabilities],
    classOf[ServerCapabilities],
    classOf[ToolManager[?]],
    classOf[PromptManager[?]],
    classOf[ResourceManager[?]],
    boolean,
    boolean,
    option,
    classOf[ServerHooks[?]],
    classOf[LimitSettings]
  )

  private val build11 = List(
    classOf[Implementation],
    option,
    classOf[ToolManager[?]],
    classOf[PromptManager[?]],
    classOf[ResourceManager[?]],
    classOf[McpServerSettings],
    option,
    option,
    classOf[SchemaValidator],
    classOf[List[?]],
    classOf[ServerHooks[?]]
  )

  test("Builtins: the 12-parameter constructor of 1.0.x") {
    val built = publicConstructor(classOf[Builtins[?]], builtins12).newInstance(
      Implementation(name = "n", version = "v"),
      None,
      ServerCapabilities(),
      ServerCapabilities(),
      new ToolManager[Any](),
      new PromptManager[Any](),
      new ResourceManager[Any](),
      Boolean.box(false),
      Boolean.box(false),
      None,
      ServerHooks.noop[Any],
      LimitSettings()
    )
    built shouldBe a[Builtins[?]]
  }

  test("RouterBuilder: the 11-parameter build of 1.0.x, on the object and as a static forwarder") {
    def arguments: Array[AnyRef] = Array(
      Implementation(name = "n", version = "v"),
      None,
      new ToolManager[Any](),
      new PromptManager[Any](),
      new ResourceManager[Any](),
      McpServerSettings(),
      None,
      None,
      SchemaValidator.permissive,
      Nil,
      ServerHooks.noop[Any]
    )
    val onObject = publicMethod(RouterBuilder.getClass, "build", build11)
    onObject.invoke(RouterBuilder, arguments*) shouldBe a[McpRouter[?]]
    val static =
      publicMethod(Class.forName("com.tjclp.fastmcp.server.router.RouterBuilder"), "build", build11)
    Modifier.isStatic(static.getModifiers) shouldBe true
    static.invoke(RouterBuilder, arguments*) shouldBe a[McpRouter[?]]
  }

  // What code expanded by the 1.0.x macros calls at run time, and MiMa does not check: `MacroUtils`
  // is `private[macros]` (MiMa skips the whole object), and the `inline$` accessors have synthetic
  // names. The list is every such reference in the 1.0.1 client and in the example servers inside
  // the 1.0.1 jar, plus the two other run-time helpers `MacroUtils` had.
  private val json = "zio.json.ast.Json"
  private val macroUtils = "com.tjclp.fastmcp.macros.MacroUtils$"

  private val expansionTargets: List[(String, String, List[String], String)] = List(
    (macroUtils, "injectParamMetadata", List(json, "scala.collection.immutable.Map"), json),
    (
      macroUtils,
      "injectSchemaMetadata",
      List(json, "com.tjclp.fastmcp.macros.SchemaMetadataNode"),
      json
    ),
    (
      macroUtils,
      "invokeFunctionWithArgs",
      List("java.lang.Object", "scala.collection.immutable.List"),
      "java.lang.Object"
    ),
    (macroUtils, "injectParamDescriptions", List(json, "scala.collection.immutable.Map"), json),
    (macroUtils, "resolveJsonRefs", List(json), json),
    ("com.tjclp.fastmcp.macros.ToolProcessor$", "inline$MacroUtils", Nil, macroUtils),
    ("com.tjclp.fastmcp.macros.JsonSchemaMacro$", "inline$MacroUtils", Nil, macroUtils),
    ("com.tjclp.fastmcp.macros.MapToFunctionMacro$", "inline$MacroUtils", Nil, macroUtils),
    (
      "com.tjclp.fastmcp.macros.MapToFunctionMacro$",
      "inline$baseContext",
      Nil,
      "com.tjclp.fastmcp.codec.DefaultDecodeContext"
    )
  )

  test("the run-time helpers 1.0.x macro expansions call keep their JVM signatures") {
    for (owner, name, parameters, result) <- expansionTargets do
      withClue(s"$owner.$name: ") {
        val method = publicMethod(Class.forName(owner), name, parameters.map(Class.forName))
        method.getReturnType.getName shouldBe result
      }
  }

  test("McpServerCore: the methods added in 1.1.0 are not abstract") {
    val added = classOf[McpServerCore[?]].getMethods.toList.filter { method =>
      Set("skills", "skill", "removeSkills", "skillProvider", "toolInputValidation")(method.getName)
    }
    added.map(_.getName).toSet should have size 5
    for method <- added do
      withClue(s"$method: ") { Modifier.isAbstract(method.getModifiers) shouldBe false }
  }

  // ---------------------------------------------------------------------------------------------

  private object EmptyProvider extends SkillProvider[Any]:
    def namespaces: List[SkillUri] = Nil
    def list(context: McpContext): ZIO[Any, Throwable, List[Skill]] = ZIO.succeed(Nil)
    def get(uri: String, context: McpContext): ZIO[Any, Throwable, Option[Skill]] = ZIO.none

    def read(uri: String, context: McpContext): ZIO[Any, Throwable, Option[SkillFileContent]] =
      ZIO.none

    def readDirectory(
        uri: String,
        context: McpContext
    ): ZIO[Any, Throwable, Option[List[Resource]]] = ZIO.none

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"compat-test","version":"1.0"}}}"""

  private def frame(router: McpRouter[Any], session: Session, text: String): Json =
    runUnsafe(MessageLoop.handleFrame(router, session, text))
      .flatMap(_.fromJson[Json].toOption)
      .getOrElse(fail(s"no reply to $text"))

  private def initialize(router: McpRouter[Any]): (Session, Json) =
    val session = runUnsafe(Session.make("binary-compat"))
    (session, frame(router, session, initFrame))

  private def rpc(router: McpRouter[Any], method: String, params: String): Json =
    rpc(router, method, params, initialize(router)._1)

  private def rpc(router: McpRouter[Any], method: String, params: String, session: Session): Json =
    frame(router, session, s"""{"jsonrpc":"2.0","id":2,"method":"$method","params":$params}""")
