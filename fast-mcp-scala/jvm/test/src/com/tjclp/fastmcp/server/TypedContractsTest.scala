package com.tjclp.fastmcp.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import io.circe.parser.parse
import sttp.tapir.generic.auto.*
import zio.*
import zio.json.*

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.core.StructuredToolResult

class TypedContractsTest extends AnyFunSuite with Matchers:

  case class AddArgs(a: Int, b: Int)
  case class AddResult(sum: Int)
  case class GreetingArgs(name: String)
  case class UserProfileArgs(userId: String)
  case class AddressArgs(
      @Param(description = "Street name", examples = List("Main St"))
      street: String,
      @Param(description = "Postal code", required = false)
      postalCode: Option[String] = None
  )
  case class ProfileArgs(
      @Param(description = "Display name")
      name: String,
      @Param(description = "Primary address")
      address: AddressArgs,
      @Param(
        description = "Current account status",
        schema = Some("""{"type":"string","enum":["active","disabled"],"description":"Current account status"}""")
      )
      status: String
  )

  given JsonEncoder[AddResult] = DeriveJsonEncoder.gen[AddResult]

  // --- GH #78 fixtures: Scala 3 enums in typed contracts, zero user-supplied givens ---
  enum Mood:
    case happy, sad, mixed
  case class MoodArgs(mood: Mood, note: Option[String])
  case class MoodInner(mood: Mood)
  case class MoodOuter(inner: MoodInner, alt: Option[Mood])

  // Override guard: a user companion decoder with CUSTOM (lowercase) naming must win.
  enum Tone:
    case Formal, Casual
  object Tone:
    given JsonDecoder[Tone] = JsonDecoder.string.mapOrFail {
      case "formal" => Right(Tone.Formal)
      case "casual" => Right(Tone.Casual)
      case other => Left(s"unknown tone: $other")
    }
  case class ToneArgs(tone: Tone)

  // Parameterized-case enum: schema stays a coproduct, decode uses wrapper objects — must compile.
  enum Shape:
    case Circle(radius: Double)
    case Point
  case class ShapeArgs(shape: Shape)

  // Inline-budget headroom: many enum fields in one args type.
  enum E1 { case A, B }; enum E2 { case A, B }; enum E3 { case A, B }; enum E4 { case A, B }
  enum E5 { case A, B }; enum E6 { case A, B }; enum E7 { case A, B }; enum E8 { case A, B }
  enum E9 { case A, B }; enum E10 { case A, B }
  case class ManyEnums(
      e1: E1, e2: E2, e3: E3, e4: E4, e5: E5,
      e6: E6, e7: E7, e8: E8, e9: E9, e10: E10
  )

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }

  test("typed tool contracts decode request types and encode structured results") {
    val server = McpServer("TypedToolServer")

    runUnsafe(
      server.tool(
        McpTool[AddArgs, AddResult](
          name = "typed-add",
          description = Some("Add two numbers")
        ) { args =>
          AddResult(args.a + args.b)
        }
      )
    )

    val schema = server.toolManager.getToolDefinition("typed-add").get.inputSchema.toJsonString
    schema should include(""""a"""")
    schema should include(""""b"""")

    val result = runUnsafe(
      server.toolManager.callTool("typed-add", Map("a" -> 2, "b" -> 5), None)
    )

    result match
      case StructuredToolResult(List(TextContent(text, _, _)), structured) =>
        text shouldBe """{"sum":7}"""
        structured.map(_.toString) shouldBe Some("""{"sum":7}""")
      case other =>
        fail(s"Unexpected typed tool result: $other")
  }

  test("typed contextual tool contracts receive McpContext") {
    val server = McpServer("TypedContextServer")

    runUnsafe(
      server.tool(
        McpTool[AddArgs, String](
          name = "typed-context",
          description = Some("Context-aware typed tool")
        ).contextual { (args, ctxOpt) =>
          val suffix = if ctxOpt.isDefined then "ctx" else "missing"
          s"${args.a + args.b}:$suffix"
        }
      )
    )

    val result = runUnsafe(
      server.toolManager.callTool(
        "typed-context",
        Map("a" -> 1, "b" -> 4),
        Some(McpContext.empty)
      )
    )

    result shouldBe StructuredToolResult(List(TextContent("5:ctx")), None)
  }

  test("typed prompt and resource contracts mount through the existing managers") {
    val server = McpServer("TypedSupportServer")

    runUnsafe(
      server.prompt(
        McpPrompt[GreetingArgs](
          name = "typed-prompt",
          arguments = List(PromptArgument("name", Some("The name to greet"), required = true))
        ) { args =>
          List(Message(Role.User, TextContent(s"Hello ${args.name}!")))
        }
      )
    )

    runUnsafe(
      server.resource(
        McpStaticResource(
          uri = "static://welcome",
          description = Some("Welcome message")
        )("welcome")
      )
    )

    runUnsafe(
      server.resource(
        McpTemplateResource[UserProfileArgs](
          uriPattern = "users://{userId}/profile",
          description = Some("User profile"),
          arguments = List(ResourceArgument("userId", Some("The user id"), required = true))
        ) { args =>
          s"profile:${args.userId}"
        }
      )
    )

    val promptResult =
      runUnsafe(server.promptManager.getPrompt("typed-prompt", Map("name" -> "Ada"), None))
    promptResult shouldBe List(Message(Role.User, TextContent("Hello Ada!")))

    val staticResult = runUnsafe(server.resourceManager.readResource("static://welcome", None))
    staticResult shouldBe "welcome"

    val templateResult =
      runUnsafe(server.resourceManager.readResource("users://42/profile", None))
    templateResult shouldBe "profile:42"
  }

  test("typed contract no-env arities remain source compatible") {
    val tool: McpTool[AddArgs, AddResult] =
      McpTool[AddArgs, AddResult](name = "compat-add") { args =>
        AddResult(args.a + args.b)
      }
    val tools: List[McpTool[?, ?]] = List(tool)

    val prompt: McpPrompt[GreetingArgs] =
      McpPrompt[GreetingArgs](
        name = "compat-prompt",
        arguments = List(PromptArgument("name", Some("The name to greet"), required = true))
      ) { args =>
        List(Message(Role.User, TextContent(s"Hello ${args.name}!")))
      }
    val prompts: List[McpPrompt[?]] = List(prompt)

    val staticResource: McpStaticResource =
      McpStaticResource(uri = "static://compat")("compat")
    val staticResources: List[McpStaticResource] = List(staticResource)

    val templateResource: McpTemplateResource[UserProfileArgs] =
      McpTemplateResource[UserProfileArgs](
        uriPattern = "users://{userId}/compat",
        arguments = List(ResourceArgument("userId", Some("The user id"), required = true))
      ) { args =>
        s"compat:${args.userId}"
      }
    val templateResources: List[McpTemplateResource[?]] = List(templateResource)

    tools.head.definition.name shouldBe "compat-add"
    prompts.head.definition.name shouldBe "compat-prompt"
    staticResources.head.definition.uri shouldBe "static://compat"
    templateResources.head.definition.isTemplate shouldBe true
  }

  test("environment typed tool contracts accept no-env ZIO handlers") {
    val server = McpServer.typed[Ref[Int]]("TypedNoEnvZioServer", "0.1.0")
    val contract =
      McpTool[AddArgs, AddResult, Ref[Int]](
        name = "typed-no-env-zio",
        description = Some("No-env ZIO handler on an env-aware contract")
      ) { args =>
        ZIO.succeed(AddResult(args.a + args.b))
      }

    val result = runUnsafe(
      (for
        _ <- server.tool(contract)
        out <- server.toolManager.callTool("typed-no-env-zio", Map("a" -> 3, "b" -> 4), None)
      yield out).provideLayer(ZLayer.fromZIO(Ref.make(0)))
    )

    result shouldBe StructuredToolResult(
      List(TextContent("""{"sum":7}""")),
      Some(zio.json.ast.Json.Obj("sum" -> zio.json.ast.Json.Num(7)))
    )
  }

  test("typed request schemas include @Param metadata on fields and nested fields") {
    val schema = parse(ToolInputSchema.derived[ProfileArgs].toJsonString).toOption.get

    val nameDesc =
      schema.hcursor.downField("properties").downField("name").downField("description").as[String]
    nameDesc shouldBe Right("Display name")

    val addressDesc =
      schema.hcursor.downField("properties").downField("address").downField("description").as[String]
    addressDesc shouldBe Right("Primary address")

    val nestedStreetDesc =
      schema.hcursor
        .downField("properties")
        .downField("address")
        .downField("properties")
        .downField("street")
        .downField("description")
        .as[String]
    nestedStreetDesc shouldBe Right("Street name")

    val nestedStreetExamples =
      schema.hcursor
        .downField("properties")
        .downField("address")
        .downField("properties")
        .downField("street")
        .downField("examples")
        .as[List[String]]
    nestedStreetExamples shouldBe Right(List("Main St"))

    val addressRequired =
      schema.hcursor
        .downField("properties")
        .downField("address")
        .downField("required")
        .as[List[String]]
        .getOrElse(Nil)
    addressRequired should not contain "postalCode"

    val statusEnum =
      schema.hcursor.downField("properties").downField("status").downField("enum").as[List[String]]
    statusEnum shouldBe Right(List("active", "disabled"))
  }

  test("enum field derives a string-enum schema with zero user givens (GH #78)") {
    val schema = parse(ToolInputSchema.derived[MoodArgs].toJsonString).toOption.get
    val mood = schema.hcursor.downField("properties").downField("mood")
    mood.get[String]("type") shouldBe Right("string")
    mood.get[List[String]]("enum").toOption.get should contain allOf ("happy", "sad", "mixed")
  }

  test("enum field decodes with zero user givens; invalid values error cleanly (GH #78)") {
    val server = McpServer("EnumDecodeServer")
    runUnsafe(
      server.tool(
        McpTool[MoodArgs, String](name = "mood-tool", description = Some("d")) { args =>
          s"${args.mood} ${args.note.getOrElse("-")}"
        }
      )
    )
    val ok = runUnsafe(server.toolManager.callTool("mood-tool", Map("mood" -> "happy"), None))
    ok match
      case StructuredToolResult(List(TextContent(text, _, _)), _) => text shouldBe "happy -"
      case other => fail(s"unexpected: $other")

    val bad = runUnsafe(
      server.toolManager.callTool("mood-tool", Map("mood" -> "furious"), None).either
    )
    bad.isLeft shouldBe true
    bad.left.toOption.get.getMessage should (include("mood") and include("invalid enumeration value"))
  }

  test("enum nested one level deeper and behind Option decodes and schemas correctly (GH #78)") {
    val schema = parse(ToolInputSchema.derived[MoodOuter].toJsonString).toOption.get
    val nested = schema.hcursor
      .downField("properties").downField("inner")
      .downField("properties").downField("mood")
    nested.get[String]("type") shouldBe Right("string")

    val server = McpServer("EnumNestedServer")
    runUnsafe(
      server.tool(
        McpTool[MoodOuter, String](name = "nested-tool", description = Some("d")) { args =>
          s"${args.inner.mood}/${args.alt.getOrElse("-")}"
        }
      )
    )
    val r = runUnsafe(
      server.toolManager.callTool(
        "nested-tool",
        Map("inner" -> Map("mood" -> "sad"), "alt" -> "mixed"),
        None
      )
    )
    r match
      case StructuredToolResult(List(TextContent(text, _, _)), _) => text shouldBe "sad/mixed"
      case other => fail(s"unexpected: $other")
  }

  test("a user-supplied companion JsonDecoder for an enum always wins (GH #78)") {
    val server = McpServer("EnumOverrideServer")
    runUnsafe(
      server.tool(
        McpTool[ToneArgs, String](name = "tone-tool", description = Some("d")) { args =>
          args.tone.toString
        }
      )
    )
    val r = runUnsafe(server.toolManager.callTool("tone-tool", Map("tone" -> "formal"), None))
    r match
      case StructuredToolResult(List(TextContent(text, _, _)), _) => text shouldBe "Formal"
      case other => fail(s"unexpected: $other")
    // The derived (capitalized) name must NOT decode — proving the custom decoder was used.
    val cap = runUnsafe(
      server.toolManager.callTool("tone-tool", Map("tone" -> "Formal"), None).either
    )
    cap.isLeft shouldBe true
  }

  test("parameterized-case enums compile: coproduct schema, wrapper-object decode (GH #78)") {
    val schema = parse(ToolInputSchema.derived[ShapeArgs].toJsonString).toOption.get
    // Not a string enum — the coproduct path is intentional for parameterized cases.
    schema.hcursor.downField("properties").downField("shape").get[String]("type") should not be Right("string")

    val server = McpServer("ShapeServer")
    runUnsafe(
      server.tool(
        McpTool[ShapeArgs, String](name = "shape-tool", description = Some("d")) { args =>
          args.shape.toString
        }
      )
    )
    val r = runUnsafe(
      server.toolManager.callTool(
        "shape-tool",
        Map("shape" -> Map("Circle" -> Map("radius" -> 2.0))),
        None
      )
    )
    r match
      case StructuredToolResult(List(TextContent(text, _, _)), _) => text should include("Circle")
      case other => fail(s"unexpected: $other")
  }

  test("ten enum fields derive within the inline budget (GH #78)") {
    val schema = parse(ToolInputSchema.derived[ManyEnums].toJsonString).toOption.get
    schema.hcursor.downField("properties").downField("e10").get[String]("type") shouldBe Right("string")
    val server = McpServer("ManyEnumServer")
    runUnsafe(
      server.tool(
        McpTool[ManyEnums, String](name = "many", description = Some("d"))(_ => "ok")
      )
    )
    val args = (1 to 10).map(i => s"e$i" -> "A").toMap[String, Any]
    val r = runUnsafe(server.toolManager.callTool("many", args, None))
    r match
      case StructuredToolResult(List(TextContent(text, _, _)), _) => text shouldBe "ok"
      case other => fail(s"unexpected: $other")
  }

  test("Out with enum field encodes and derives outputSchema with zero user givens (GH #78 Part 3)") {
    case class Report(mood: Mood, n: Int)
    val server = McpServer("EnumOutServer")
    runUnsafe(
      server.tool(
        McpTool[MoodArgs, Report](name = "report-tool", description = Some("d")) { args =>
          Report(args.mood, 7)
        }.withOutputSchema
      )
    )
    val defn = server.toolManager.getToolDefinition("report-tool").get
    val outSchemaStr = {
      import com.tjclp.fastmcp.core.wire.toJsonString
      defn.outputSchema.get.toJsonString
    }
    val outSchema = parse(outSchemaStr).toOption.get
    val mood = outSchema.hcursor.downField("properties").downField("mood")
    mood.get[String]("type") shouldBe Right("string")
    mood.get[List[String]]("enum").toOption.get should contain("happy")

    val r = runUnsafe(server.toolManager.callTool("report-tool", Map("mood" -> "happy"), None))
    r match
      case StructuredToolResult(List(TextContent(text, _, _)), structured) =>
        text should include(""""mood":"happy"""")
        structured.map(_.toString).getOrElse("") should include(""""mood":"happy"""")
      case other => fail(s"unexpected: $other")
  }
