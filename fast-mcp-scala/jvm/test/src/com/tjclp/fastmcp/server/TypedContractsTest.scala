package com.tjclp.fastmcp.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.*

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.JsonTestSupport.*
import com.tjclp.fastmcp.core.StructuredToolResult

class TypedContractsTest extends AnyFunSuite with Matchers:

  case class AddArgs(a: Int, b: Int)
  case class AddResult(sum: Int)
  enum Mood:
    case happy, sad, mixed
  case class MoodArgs(mood: Mood, note: Option[String] = None)
  case class MoodCollectionArgs(moods: List[Mood], fallback: Option[Mood])
  case class UserId(value: String)
  case class UserLookupArgs(id: UserId)
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
  given McpInputCodec[UserId] = McpInputCodec.string(
    """{"type":"string","pattern":"^usr_[a-z0-9]+$"}"""
  ) { raw =>
    Either.cond(raw.startsWith("usr_"), UserId(raw), s"Invalid user id '$raw'")
  }

  // --- GH #78 fixtures: Scala 3 enums in typed contracts, zero user-supplied givens ---
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

  // Schema-override guard: a user companion McpSchema for an enum must win over the
  // native macro's derived string-enum schema (review finding on GH #78).
  enum Grade:
    case A, B, C
  object Grade:
    given McpSchema[Grade] =
      McpSchema.instance("""{"type":"string","description":"user-grade-schema"}""")
  case class GradeArgs(grade: Grade)

  // Parameterized-case enum: the native macro rejects it for schema derivation (no faithful
  // string-enum shape exists), so the user supplies an McpSchema; decode still derives
  // zio-json's wrapper-object codec with zero decoder givens.
  enum Shape:
    case Circle(radius: Double)
    case Point
  object Shape:
    given McpSchema[Shape] = McpSchema.instance(
      """{"oneOf":[{"type":"object","properties":{"Circle":{"type":"object"}}},{"type":"object","properties":{"Point":{"type":"object"}}}]}"""
    )
  case class ShapeArgs(shape: Shape)

  // Inline-budget headroom: many enum fields in one args type.
  enum E1 { case A, B }; enum E2 { case A, B }; enum E3 { case A, B }; enum E4 { case A, B }
  enum E5 { case A, B }; enum E6 { case A, B }; enum E7 { case A, B }; enum E8 { case A, B }
  enum E9 { case A, B }; enum E10 { case A, B }
  case class ManyEnums(
      e1: E1, e2: E2, e3: E3, e4: E4, e5: E5,
      e6: E6, e7: E7, e8: E8, e9: E9, e10: E10
  )

  // Round-2 review fixtures (PR #80 review)
  case class AllOptional(a: Option[Int], b: Option[String])
  case class InnerDoc(@Param(description = "street-doc") street: String)
  case class OuterDoc(addr: Option[InnerDoc])
  sealed trait GResult[T]
  case class GOk[T](value: T) extends GResult[T]
  case class GErr[T](msg: String) extends GResult[T]
  object GResult:
    given McpInputCodec[GResult[Int]] = McpInputCodec.fromJsonDecoder(
      """{"oneOf":[{"type":"object","properties":{"GOk":{"type":"object"}}},{"type":"object","properties":{"GErr":{"type":"object"}}}]}"""
    )(DeriveJsonDecoder.gen[GResult[Int]])
  case class GArgs(result: GResult[Int])

  // Review-findings regression fixtures (PR #80 review)
  case class MapArgs(tags: Map[String, Int])
  case class EitherArgs(v: Either[Int, String])
  case class Wrap[A](value: A)
  case class WrapArgs(w: Wrap[Wrap[Int]])

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

  test("typed contracts derive singleton enum schemas and decoders without user givens (#78)") {
    val server = McpServer("TypedEnumServer")

    runUnsafe(
      server.tool(
        McpTool[MoodArgs, String](name = "describe-mood") { args =>
          s"mood:${args.mood}"
        }
      )
    )

    val schema = parse(
      server.toolManager.getToolDefinition("describe-mood").get.inputSchema.toJsonString
    ).toOption.get
    schema.hcursor
      .downField("properties")
      .downField("mood")
      .downField("type")
      .as[String] shouldBe Right("string")
    schema.hcursor
      .downField("properties")
      .downField("mood")
      .downField("enum")
      .as[List[String]] shouldBe Right(List("happy", "sad", "mixed"))

    runUnsafe(server.toolManager.callTool("describe-mood", Map("mood" -> "happy"), None)) shouldBe
      StructuredToolResult(List(TextContent("mood:happy")), None)

    val invalid = runUnsafe(
      server.toolManager.callTool("describe-mood", Map("mood" -> "angry"), None).exit
    )
    invalid.isFailure shouldBe true
    invalid.causeOption.get.prettyPrint should include("invalid enumeration value")
  }

  test("nested enum collections and custom input codecs stay zero-boilerplate") {
    val enumServer = McpServer("TypedEnumCollectionServer")
    val enumTool = McpTool[MoodCollectionArgs, String](name = "mood-list") { args =>
      s"${args.moods.mkString(",")}:${args.fallback.fold("none")(_.toString)}"
    }
    runUnsafe(enumServer.tool(enumTool))

    val enumSchema = parse(enumTool.definition.inputSchema.toJsonString).toOption.get
    enumSchema.hcursor
      .downField("properties")
      .downField("moods")
      .downField("items")
      .downField("enum")
      .as[List[String]] shouldBe Right(List("happy", "sad", "mixed"))
    runUnsafe(
      enumServer.toolManager.callTool(
        "mood-list",
        Map("moods" -> List("happy", "sad"), "fallback" -> "sad"),
        None
      )
    ) shouldBe StructuredToolResult(List(TextContent("happy,sad:sad")), None)

    val customServer = McpServer("TypedCustomCodecServer")
    val customTool = McpTool[UserLookupArgs, String](name = "find-user")(_.id.value)
    runUnsafe(customServer.tool(customTool))

    val customSchema = parse(customTool.definition.inputSchema.toJsonString).toOption.get
    customSchema.hcursor
      .downField("properties")
      .downField("id")
      .downField("pattern")
      .as[String] shouldBe Right("^usr_[a-z0-9]+$")
    runUnsafe(
      customServer.toolManager.callTool("find-user", Map("id" -> "usr_42"), None)
    ) shouldBe StructuredToolResult(List(TextContent("usr_42")), None)
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
    mood.downField("type").as[String] shouldBe Right("string")
    mood.downField("enum").as[List[String]].toOption.get should contain allOf ("happy", "sad", "mixed")
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
    nested.downField("type").as[String] shouldBe Right("string")

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

  test("parameterized-case enums: user McpSchema for schema, wrapper-object decode (GH #78)") {
    val schema = parse(ToolInputSchema.derived[ShapeArgs].toJsonString).toOption.get
    // Not a string enum — the user-supplied McpSchema is advertised for parameterized cases.
    schema.hcursor.downField("properties").downField("shape").downField("oneOf").succeeded shouldBe true

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
    schema.hcursor.downField("properties").downField("e10").downField("type").as[String] shouldBe Right("string")
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
    mood.downField("type").as[String] shouldBe Right("string")
    mood.downField("enum").as[List[String]].toOption.get should contain("happy")

    val r = runUnsafe(server.toolManager.callTool("report-tool", Map("mood" -> "happy"), None))
    r match
      case StructuredToolResult(List(TextContent(text, _, _)), structured) =>
        text should include(""""mood":"happy"""")
        structured.map(_.toString).getOrElse("") should include(""""mood":"happy"""")
      case other => fail(s"unexpected: $other")
  }

  test("a user-supplied McpSchema for a nested enum always wins (GH #78 review)") {
    val schema = parse(ToolInputSchema.derived[GradeArgs].toJsonString).toOption.get
    val grade = schema.hcursor.downField("properties").downField("grade")
    grade.downField("description").as[String] shouldBe Right("user-grade-schema")
    // The planted default would have added an enum constraint; the user schema has none.
    grade.downField("enum").succeeded shouldBe false
  }

  test("Map[String, V] advertises an object schema its decoder accepts (PR #80 review)") {
    val schema = parse(ToolInputSchema.derived[MapArgs].toJsonString).toOption.get
    val tags = schema.hcursor.downField("properties").downField("tags")
    tags.downField("type").as[String] shouldBe Right("object")
    tags.downField("additionalProperties").downField("type").as[String] shouldBe Right("integer")

    val server = McpServer("MapServer")
    runUnsafe(
      server.tool(
        McpTool[MapArgs, String](name = "map-tool", description = Some("d")) { args =>
          args.tags.toList.sorted.mkString(",")
        }
      )
    )
    val r = runUnsafe(
      server.toolManager.callTool("map-tool", Map("tags" -> Map("a" -> 1, "b" -> 2)), None)
    )
    r match
      case StructuredToolResult(List(TextContent(text, _, _)), _) => text shouldBe "(a,1),(b,2)"
      case other => fail(s"unexpected: $other")
  }

  test("Either advertises zio-json's wrapper-object shape and decodes it (PR #80 review)") {
    val schema = parse(ToolInputSchema.derived[EitherArgs].toJsonString).toOption.get
    val variants = schema.hcursor
      .downField("properties").downField("v").downField("oneOf").as[List[zio.json.ast.Json]]
      .toOption.get
    variants.flatMap(_.hcursor.downField("properties").keys.toList.flatten) should
      contain allOf ("Left", "Right")

    val server = McpServer("EitherServer")
    runUnsafe(
      server.tool(
        McpTool[EitherArgs, String](name = "either-tool", description = Some("d")) { args =>
          args.v.fold(i => s"L$i", s => s"R$s")
        }
      )
    )
    val r = runUnsafe(
      server.toolManager.callTool("either-tool", Map("v" -> Map("Right" -> "x")), None)
    )
    r match
      case StructuredToolResult(List(TextContent(text, _, _)), _) => text shouldBe "Rx"
      case other => fail(s"unexpected: $other")
  }

  test("Option fields advertise nullability alongside the inner schema (PR #80 review)") {
    val schema = parse(ToolInputSchema.derived[MoodArgs].toJsonString).toOption.get
    val note = schema.hcursor.downField("properties").downField("note")
    val branches = note.downField("anyOf").as[List[zio.json.ast.Json]].toOption.get
    branches.exists(_.hcursor.downField("type").as[String].toOption.contains("null")) shouldBe true
    branches.exists(_.hcursor.downField("type").as[String].toOption.contains("string")) shouldBe true
    // Option fields stay non-required.
    schema.hcursor.downField("required").as[List[String]].toOption.get should not contain "note"
  }

  test("generic case classes nested at different type arguments derive (PR #80 review)") {
    val schema = parse(ToolInputSchema.derived[WrapArgs].toJsonString).toOption.get
    schema.hcursor
      .downField("properties").downField("w")
      .downField("properties").downField("value")
      .downField("properties").downField("value")
      .downField("type").as[String] shouldBe Right("integer")
  }

  test("all-Optional args advertise no additionalProperties constraint (PR #80 review r2)") {
    val schema = parse(ToolInputSchema.derived[AllOptional].toJsonString).toOption.get
    schema.hcursor.downField("additionalProperties").failed shouldBe true
    schema.hcursor.downField("required").failed shouldBe true
    // The truly-empty schema (Unit) keeps the closed shape.
    val unit = parse(ToolInputSchema.derived[Unit].toJsonString).toOption.get
    unit.hcursor.downField("additionalProperties").as[Boolean] shouldBe Right(false)
  }

  test("@Param metadata survives behind Option-wrapped case classes (PR #80 review r2)") {
    val schema = parse(ToolInputSchema.derived[OuterDoc].toJsonString).toOption.get
    val addr = schema.hcursor.downField("properties").downField("addr")
    val branches = addr.downField("anyOf").as[List[zio.json.ast.Json]].toOption.get
    val inner = branches.find(_.hcursor.downField("properties").succeeded).getOrElse(
      fail(s"no object branch in ${branches.map(_.toJson)}")
    )
    inner.hcursor
      .downField("properties").downField("street")
      .downField("description").as[String] shouldBe Right("street-doc")
  }

  test("generic sealed ADTs work via McpInputCodec (PR #80 review r2)") {
    val schema = parse(ToolInputSchema.derived[GArgs].toJsonString).toOption.get
    schema.hcursor.downField("properties").downField("result").downField("oneOf").succeeded shouldBe true

    val server = McpServer("GenericAdtServer")
    runUnsafe(
      server.tool(
        McpTool[GArgs, String](name = "gadt-tool", description = Some("d")) { args =>
          args.result match
            case GOk(v) => s"ok:$v"
            case GErr(m) => s"err:$m"
        }
      )
    )
    val r = runUnsafe(
      server.toolManager.callTool("gadt-tool", Map("result" -> Map("GOk" -> Map("value" -> 5))), None)
    )
    r match
      case StructuredToolResult(List(TextContent(text, _, _)), _) => text shouldBe "ok:5"
      case other => fail(s"unexpected: $other")
  }

