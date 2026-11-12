package com.tjclp.fastmcp.server

import java.util.concurrent.atomic.AtomicInteger

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.core.JsonSchemaValidator
import com.tjclp.fastmcp.server.manager.{ToolArgumentError, ToolInputValidation, ToolRegistrationOptions}
import com.tjclp.fastmcp.server.router.{McpRouter, Session}
import com.tjclp.fastmcp.server.transport.MessageLoop

/** Annotated tools whose constraints live ONLY in the schema (`@Param(schema = ...)`): the Scala
  * types (`String`, `Int`) accept any value, so nothing but schema enforcement keeps an
  * out-of-constraint argument away from the method body.
  */
object ValidatedAnnotatedTools:
  val sortCalls = new AtomicInteger(0)
  val pageCalls = new AtomicInteger(0)

  @Tool(name = Some("sort"))
  def sort(
      @Param(
        description = "Sort order",
        schema = Some("""{"type":"string","enum":["relevance","date"]}""")
      )
      order: String
  ): String =
    sortCalls.incrementAndGet()
    s"sorted by $order"

  @Tool(name = Some("page"))
  def page(
      @Param(
        description = "Page size",
        schema = Some("""{"type":"integer","minimum":1,"maximum":100}""")
      )
      size: Int
  ): String =
    pageCalls.incrementAndGet()
    s"page of $size"

/** Finding 11: a `tools/call` whose arguments do not conform to the tool's advertised `inputSchema`
  * is rejected before the handler runs — on the annotation path, the typed-contract path, in
  * process and over the wire — and a conforming call still succeeds.
  */
class ToolInputValidationTest extends AnyFunSuite with Matchers:

  case class OrderArgs(order: String)
  case class SizeArgs(size: Int)
  case class IdArgs(id: String)

  // Everything the derivation emits, in one input type.
  enum Level:
    case low, high
  case class Point(x: Double, y: Double)
  sealed trait Shape
  case class Circle(radius: Double) extends Shape
  case object Origin extends Shape
  case class Rich(
      text: String,
      count: Int,
      big: Long,
      ratio: Double,
      flag: Boolean,
      level: Level,
      maybeLevel: Option[Level],
      maybeText: Option[String],
      point: Point,
      maybePoint: Option[Point],
      points: List[Point],
      names: Set[String],
      scores: Map[String, Int],
      either: Either[Int, String],
      when: java.time.Instant,
      day: java.time.LocalDate,
      id: java.util.UUID,
      @Param(description = "Optional, with a default", required = false)
      withDefault: Int = 7
  )
  case class Defaulted(a: Int, s: String = "x")
  case class OptionalOverride(
      @Param(
        description = "Sort order",
        schema = Some("""{"type":"string","enum":["relevance","date"]}"""),
        required = false
      )
      order: Option[String] = None
  )
  case class FieldArgs(
      @Param(
        description = "Sort order",
        schema = Some("""{"type":"string","enum":["relevance","date"]}""")
      )
      order: String,
      @Param(
        description = "Page size",
        schema = Some("""{"type":"integer","minimum":1,"maximum":100}""")
      )
      size: Int
  )

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private def call(server: McpServer[Any], name: String, args: (String, Json)*): Either[Throwable, Any] =
    runUnsafe(server.toolManager.callTool(name, args.toMap, None).either)

  private def rendered(result: Either[Throwable, Any]): String = result match
    case Right(core.StructuredToolResult(content, _)) =>
      content.collect { case t: TextContent => t.text }.mkString
    case Right(other) => other.toString
    case Left(err) => fail(s"expected a result, got $err")

  private def num(n: Int): Json = Json.Num(n)

  private val enumSchema = ToolInputSchema.unsafeFromJsonString(
    """{"type":"object","properties":{"order":{"type":"string","enum":["relevance","date"]}},"required":["order"]}"""
  )

  private val boundsSchema = ToolInputSchema.unsafeFromJsonString(
    """{"type":"object","properties":{"size":{"type":"integer","minimum":1,"maximum":100}},"required":["size"]}"""
  )

  // ---- annotation path: @Param(schema = ...) ----

  test("annotation path: a value outside the declared enum is rejected before the method runs") {
    val server = McpServer("AnnotatedEnum")
    server.scanAnnotations[ValidatedAnnotatedTools.type]
    val before = ValidatedAnnotatedTools.sortCalls.get()

    val rejected = call(server, "sort", "order" -> Json.Str("drop table"))
    rejected.isLeft shouldBe true
    rejected.left.toOption.get shouldBe a[ToolArgumentError]
    ValidatedAnnotatedTools.sortCalls.get() shouldBe before

    rendered(call(server, "sort", "order" -> Json.Str("date"))) shouldBe "sorted by date"
    ValidatedAnnotatedTools.sortCalls.get() shouldBe before + 1
  }

  test("annotation path: a number outside the declared bounds is rejected before the method runs") {
    val server = McpServer("AnnotatedBounds")
    server.scanAnnotations[ValidatedAnnotatedTools.type]
    val before = ValidatedAnnotatedTools.pageCalls.get()

    call(server, "page", "size" -> num(0)).isLeft shouldBe true
    call(server, "page", "size" -> num(101)).isLeft shouldBe true
    call(server, "page", "size" -> num(1000000)).isLeft shouldBe true
    ValidatedAnnotatedTools.pageCalls.get() shouldBe before

    rendered(call(server, "page", "size" -> num(1))) shouldBe "page of 1"
    rendered(call(server, "page", "size" -> num(100))) shouldBe "page of 100"
    ValidatedAnnotatedTools.pageCalls.get() shouldBe before + 2
  }

  // ---- typed-contract path: McpTool.withSchema ----

  test("McpTool.withSchema: a value outside the declared enum is rejected before the handler runs") {
    val calls = new AtomicInteger(0)
    val server = McpServer("ContractEnum")
    runUnsafe(
      server.tool(
        McpTool.withSchema[OrderArgs, String](name = "sort", inputSchema = enumSchema) { args =>
          calls.incrementAndGet()
          s"sorted by ${args.order}"
        }
      )
    )

    val rejected = call(server, "sort", "order" -> Json.Str("popularity"))
    rejected.isLeft shouldBe true
    rejected.left.toOption.get shouldBe a[ToolArgumentError]
    calls.get() shouldBe 0

    rendered(call(server, "sort", "order" -> Json.Str("relevance"))) shouldBe "sorted by relevance"
    calls.get() shouldBe 1
  }

  test("McpTool.withSchema: a number outside the declared bounds is rejected before the handler runs") {
    val calls = new AtomicInteger(0)
    val server = McpServer("ContractBounds")
    runUnsafe(
      server.tool(
        McpTool.withSchema[SizeArgs, String](name = "page", inputSchema = boundsSchema) { args =>
          calls.incrementAndGet()
          s"page of ${args.size}"
        }
      )
    )

    call(server, "page", "size" -> num(0)).isLeft shouldBe true
    call(server, "page", "size" -> num(101)).isLeft shouldBe true
    calls.get() shouldBe 0

    rendered(call(server, "page", "size" -> num(50))) shouldBe "page of 50"
    calls.get() shouldBe 1
  }

  test("typed contract with @Param(schema = ...) fields enforces the per-field overrides") {
    val calls = new AtomicInteger(0)
    val server = McpServer("ContractFields")
    runUnsafe(
      server.tool(
        McpTool[FieldArgs, String](name = "list") { args =>
          calls.incrementAndGet()
          s"${args.order}/${args.size}"
        }
      )
    )

    call(server, "list", "order" -> Json.Str("nope"), "size" -> num(5)).isLeft shouldBe true
    call(server, "list", "order" -> Json.Str("date"), "size" -> num(500)).isLeft shouldBe true
    calls.get() shouldBe 0

    rendered(call(server, "list", "order" -> Json.Str("date"), "size" -> num(5))) shouldBe "date/5"
    calls.get() shouldBe 1
  }

  // ---- over the wire: same shape as an undecodable argument ----

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""

  private def frame(router: McpRouter[Any], session: Session, s: String): String =
    runUnsafe(MessageLoop.handleFrame(router, session, s)).getOrElse(fail("no reply"))

  test("tools/call over the wire: a schema violation answers like an undecodable argument (isError)") {
    val calls = new AtomicInteger(0)
    val server = McpServer("WireValidation")
    runUnsafe(
      server.tool(
        McpTool.withSchema[SizeArgs, String](name = "page", inputSchema = boundsSchema) { args =>
          calls.incrementAndGet()
          s"page of ${args.size}"
        }
      )
    )
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("wire-validation"))
    frame(router, session, initFrame)

    def toolsCall(id: Int, arguments: String): Json =
      frame(
        router,
        session,
        s"""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"page","arguments":$arguments}}"""
      ).fromJson[Json].fold(e => fail(e), identity)

    def isError(reply: Json): Option[Boolean] =
      reply.asObject.flatMap(_.get("result")).flatMap(_.asObject).flatMap(_.get("isError")).flatMap(_.asBoolean)

    // Baseline: what the codebase already answers for an argument that cannot be decoded.
    val undecodable = toolsCall(2, """{"size":"not a number"}""")
    isError(undecodable) shouldBe Some(true)
    undecodable.asObject.flatMap(_.get("error")) shouldBe None

    // A well-typed value outside the declared bounds: same in-band shape, handler never ran.
    val outOfBounds = toolsCall(3, """{"size":5000}""")
    isError(outOfBounds) shouldBe Some(true)
    outOfBounds.asObject.flatMap(_.get("error")) shouldBe None
    calls.get() shouldBe 0

    val ok = toolsCall(4, """{"size":7}""")
    isError(ok) should not be Some(true)
    ok.toJson should include("page of 7")
    calls.get() shouldBe 1
  }

  test("a task-augmented call is validated too: the task ends in an error result, the handler never runs") {
    val calls = new AtomicInteger(0)
    val server = McpServer(
      "TaskValidation",
      settings = McpServerSettings(tasks = TaskSettings(enabled = true, pollIntervalMs = 20))
    )
    runUnsafe(
      server.tool(
        McpTool
          .withSchema[SizeArgs, String](name = "page", inputSchema = boundsSchema) { args =>
            calls.incrementAndGet()
            s"page of ${args.size}"
          }
          .withTaskSupport(TaskSupport.Optional)
      )
    )
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("task-validation"))
    frame(router, session, initFrame)

    val created = frame(
      router,
      session,
      """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"page","arguments":{"size":5000},"task":{"ttl":60000}}}"""
    )
    val taskId = """"taskId":"([^"]+)"""".r
      .findFirstMatchIn(created)
      .map(_.group(1))
      .getOrElse(fail(s"no taskId in: $created"))

    val terminal = runUnsafe(
      (ZIO.sleep(20.millis) *> ZIO.attempt(
        frame(
          router,
          session,
          s"""{"jsonrpc":"2.0","id":3,"method":"tasks/get","params":{"taskId":"$taskId"}}"""
        )
      )).repeatUntil(body => !body.contains(""""status":"working""""))
        .timeoutFail(new RuntimeException("task never left `working`"))(15.seconds)
    )
    val result = frame(
      router,
      session,
      s"""{"jsonrpc":"2.0","id":4,"method":"tasks/result","params":{"taskId":"$taskId"}}"""
    )
    withClue(s"tasks/get: $terminal; tasks/result: $result") {
      result should include("Invalid arguments for tool 'page'")
      result should include(""""isError":true""")
    }
    calls.get() shouldBe 0
  }

  // ---- opting one tool out keeps the old decode-only behaviour ----

  test("ToolRegistrationOptions(validateInputSchema = false) leaves a tool's arguments unvalidated") {
    val calls = new AtomicInteger(0)
    val server = McpServer("OptOut")
    runUnsafe(
      server.tool(
        McpTool.withSchema[SizeArgs, String](name = "page", inputSchema = boundsSchema) { args =>
          calls.incrementAndGet()
          s"page of ${args.size}"
        },
        ToolRegistrationOptions(validateInputSchema = false)
      )
    )

    rendered(call(server, "page", "size" -> num(5000))) shouldBe "page of 5000"
    calls.get() shouldBe 1
  }

  // ---- the server-wide switch and the effective state ----

  test("McpServerSettings(validateToolInput = false) turns enforcement off for every tool") {
    val calls = new AtomicInteger(0)
    val server = McpServer("ServerOff", settings = McpServerSettings(validateToolInput = false))
    runUnsafe(
      server.tool(
        McpTool.withSchema[SizeArgs, String](name = "typed-page", inputSchema = boundsSchema) { args =>
          calls.incrementAndGet()
          s"page of ${args.size}"
        }
      )
    )
    server.scanAnnotations[ValidatedAnnotatedTools.type]

    rendered(call(server, "typed-page", "size" -> num(5000))) shouldBe "page of 5000"
    rendered(call(server, "sort", "order" -> Json.Str("anything"))) shouldBe "sorted by anything"
    calls.get() shouldBe 1
    server.toolInputValidation.keySet shouldBe Set("typed-page", "sort", "page")
    server.toolInputValidation.values.map(_.enforced).toSet shouldBe Set(false)
  }

  test("validation is on by default and the effective state is discoverable per tool") {
    McpServerSettings().validateToolInput shouldBe true

    val patternSchema = ToolInputSchema.unsafeFromJsonString(
      """{"type":"object","properties":{"id":{"type":"string","pattern":"^usr_[a-z0-9]+$","maxLength":32}},"required":["id"]}"""
    )
    val server = McpServer("Discoverable")
    runUnsafe(
      server.tool(
        McpTool.withSchema[SizeArgs, String](name = "page", inputSchema = boundsSchema)(_.size.toString)
      ) *>
        server.tool(
          McpTool.withSchema[SizeArgs, String](name = "unchecked", inputSchema = boundsSchema)(_.size.toString),
          ToolRegistrationOptions(validateInputSchema = false)
        ) *>
        server.tool(McpTool.withSchema[IdArgs, String](name = "lookup", inputSchema = patternSchema)(_.id))
    )

    server.toolInputValidation shouldBe Map(
      "page" -> ToolInputValidation(enforced = true, unenforced = Nil),
      "unchecked" -> ToolInputValidation(enforced = false, unenforced = Nil),
      "lookup" -> ToolInputValidation(enforced = true, unenforced = List("pattern"))
    )
    server.toolManager.inputValidation("page") shouldBe Some(ToolInputValidation(true, Nil))
    server.toolManager.inputValidation("no-such-tool") shouldBe None

    // `pattern` is reported as unenforced and is in fact not evaluated; `maxLength` next to it is.
    rendered(call(server, "lookup", "id" -> Json.Str("not a user id"))) shouldBe "not a user id"
    call(server, "lookup", "id" -> Json.Str("x" * 33)).isLeft shouldBe true
  }

  test("a definition written straight into ToolManager.tools is validated like a registered one") {
    val calls = new AtomicInteger(0)
    val server = McpServer("DirectPut")
    server.toolManager.tools.put(
      "page",
      (
        ToolDefinition(name = "page", description = None, inputSchema = boundsSchema),
        (_, _) => ZIO.succeed(calls.incrementAndGet())
      )
    )
    call(server, "page", "size" -> num(5000)).isLeft shouldBe true
    calls.get() shouldBe 0
    call(server, "page", "size" -> num(5)).isRight shouldBe true
    server.toolManager.inputValidation("page") shouldBe Some(ToolInputValidation(true, Nil))
  }

  test("re-registering a tool replaces its validator along with its definition") {
    val server = McpServer("Reregister")
    val replace = ToolRegistrationOptions(allowOverrides = true)
    runUnsafe(
      server.tool(
        McpTool.withSchema[SizeArgs, String](name = "page", inputSchema = boundsSchema)(_.size.toString),
        replace
      )
    )
    call(server, "page", "size" -> num(5000)).isLeft shouldBe true
    runUnsafe(
      server.tool(
        McpTool.withSchema[SizeArgs, String](
          name = "page",
          inputSchema = ToolInputSchema.unsafeFromJsonString(
            """{"type":"object","properties":{"size":{"type":"integer","maximum":9000}},"required":["size"]}"""
          )
        )(_.size.toString),
        replace
      )
    )
    rendered(call(server, "page", "size" -> num(5000))) shouldBe "5000"
    call(server, "page", "size" -> num(9001)).isLeft shouldBe true
  }

  // ---- derived schemas accept their own valid inputs ----

  private given JsonEncoder[Level] = JsonEncoder.string.contramap(_.toString)
  private given JsonEncoder[Point] = DeriveJsonEncoder.gen[Point]
  private given JsonEncoder[Rich] = DeriveJsonEncoder.gen[Rich]

  private val rich = Rich(
    text = "t",
    count = 3,
    big = 9007199254740993L,
    ratio = 0.25,
    flag = true,
    level = Level.high,
    maybeLevel = Some(Level.low),
    maybeText = None,
    point = Point(1, 2),
    maybePoint = Some(Point(3, 4)),
    points = List(Point(5, 6), Point(7, 8)),
    names = Set("a", "b"),
    scores = Map("x" -> 1, "y" -> 2),
    either = Right("r"),
    when = java.time.Instant.parse("2026-01-02T03:04:05Z"),
    day = java.time.LocalDate.parse("2026-01-02"),
    id = java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
  )

  private def fieldsOf(json: Json): Seq[(String, Json)] = json match
    case Json.Obj(fields) => fields.toSeq
    case other => fail(s"expected an object, got $other")

  test("a derived schema accepts every valid encoding of its own type") {
    val server = McpServer("Derived")
    runUnsafe(server.tool(McpTool[Rich, String](name = "rich")(r => s"${r.level}/${r.withDefault}")))
    server.toolInputValidation("rich").enforced shouldBe true

    val encoded = fieldsOf(rich.toJsonAST.fold(e => fail(e), identity))
    // As encoded: absent None, every field present.
    rendered(call(server, "rich", encoded*)) shouldBe "high/7"
    // Optional fields sent as explicit null, the default omitted.
    val withNulls = encoded.filterNot(_._1 == "withDefault").map {
      case (k @ ("maybeLevel" | "maybeText" | "maybePoint"), _) => k -> Json.Null
      case other => other
    } ++ Seq("maybeText" -> Json.Null)
    rendered(call(server, "rich", withNulls*)) shouldBe "high/7"
    // Optional fields absent altogether; Left side of the Either.
    val minimal = encoded.filterNot((k, _) => k.startsWith("maybe") || k == "withDefault").map {
      case ("either", _) => "either" -> Json.Obj("Left" -> Json.Num(1))
      case other => other
    }
    rendered(call(server, "rich", minimal*)) shouldBe "high/7"
  }

  test("a derived schema rejects what its type could not hold") {
    val server = McpServer("DerivedRejects")
    val calls = new AtomicInteger(0)
    runUnsafe(server.tool(McpTool[Rich, String](name = "rich") { _ =>
      calls.incrementAndGet()
      "ran"
    }))
    val encoded = fieldsOf(rich.toJsonAST.fold(e => fail(e), identity)).toMap

    def withField(field: (String, Json)): Either[Throwable, Any] = call(server, "rich", (encoded + field).toSeq*)

    withField("level" -> Json.Str("medium")).isLeft shouldBe true
    withField("maybeLevel" -> Json.Str("medium")).isLeft shouldBe true
    withField("count" -> Json.Str("3")).isLeft shouldBe true
    withField("count" -> Json.Num(BigDecimal("3.5"))).isLeft shouldBe true
    withField("point" -> Json.Obj("x" -> Json.Num(1))).isLeft shouldBe true
    withField("points" -> Json.Arr(Json.Obj("x" -> Json.Num(1), "y" -> Json.Str("2")))).isLeft shouldBe true
    withField("scores" -> Json.Obj("x" -> Json.Str("1"))).isLeft shouldBe true
    withField("either" -> Json.Str("r")).isLeft shouldBe true
    call(server, "rich", (encoded - "text").toSeq*).isLeft shouldBe true
    calls.get() shouldBe 0
  }

  test("a defaulted field is optional only when the schema says so (required = false)") {
    val server = McpServer("Defaults")
    runUnsafe(server.tool(McpTool[Defaulted, String](name = "defaulted")(d => s"${d.a}:${d.s}")))
    // A default alone leaves the field in the advertised `required` list, and the list is enforced.
    val omitted = call(server, "defaulted", "a" -> num(1))
    omitted.left.toOption.map(_.getMessage) shouldBe
      Some("Invalid arguments for tool 'defaulted': missing required property 's'")
    rendered(call(server, "defaulted", "a" -> num(1), "s" -> Json.Str("y"))) shouldBe "1:y"
  }

  test("a sealed-trait parameter validates through its derived oneOf") {
    object Tools:
      val shapeTool = McpTool.withSchema[OrderArgs, String](
        name = "shape",
        inputSchema = ToolInputSchema.unsafeFromJsonString(
          Json
            .Obj(
              "type" -> Json.Str("object"),
              "properties" -> Json.Obj(
                "shape" -> com.tjclp.fastmcp.macros.JsonSchemaMacro.schemaForType[Shape]
              )
            )
            .toJson
        )
      )(_ => "ok")
    val validator = JsonSchemaValidator.compile(Tools.shapeTool.definition.inputSchema)
    validator.unenforced shouldBe Nil
    def shape(json: String): Option[String] =
      validator.validate(Json.Obj("shape" -> json.fromJson[Json].fold(e => fail(e), identity)))
    shape("""{"Circle":{"radius":2.5}}""") shouldBe None
    shape("""{"Origin":{}}""") shouldBe None
    shape("""{"Circle":{"radius":"wide"}}""") should not be None
    shape("""{"Origin":{"extra":1}}""") should not be None
    shape("""{"Square":{}}""") should not be None
  }

  test("an Option parameter whose schema override does not admit null rejects an explicit null") {
    val server = McpServer("OptionalOverride")
    runUnsafe(
      server.tool(McpTool[OptionalOverride, String](name = "sort")(_.order.getOrElse("default")))
    )
    rendered(call(server, "sort")) shouldBe "default"
    rendered(call(server, "sort", "order" -> Json.Str("date"))) shouldBe "date"
    call(server, "sort", "order" -> Json.Str("nope")).isLeft shouldBe true
    // The override replaced the derived nullable wrapper, so null is not part of the contract.
    call(server, "sort", "order" -> Json.Null).isLeft shouldBe true
  }
