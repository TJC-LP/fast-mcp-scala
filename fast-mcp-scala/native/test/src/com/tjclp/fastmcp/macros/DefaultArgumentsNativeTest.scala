package com.tjclp.fastmcp.macros

import org.scalatest.funsuite.AnyFunSuite
import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.core.StructuredToolResult
import com.tjclp.fastmcp.server.manager.ToolArgumentError

/** Input and output types of the TJC-5117 cases below. */
case class DfNativePage(number: Int, size: Int = 20, @Param("Sort key") sort: String = "date")
case class DfNativeIn(a: Int, s: String = "x")
case class DfNativeNested(q: String, page: DfNativePage)
case class DfNativeRequired(a: Int, @Param("Suffix", required = true) s: String = "x")
case class DfNativeOut(a: Int, s: String = "x")

object DfNativeOut:
  given JsonEncoder[DfNativeOut] = DeriveJsonEncoder.gen[DfNativeOut]

/** Scala Native mirror of the JVM `DefaultArgumentsTest` (TJC-2334) and `DefaultedNotRequiredTest`
  * (TJC-5117): the same macro expansion must apply Scala default arguments for an omitted
  * `tools/call` / `prompts/get` parameter in a native binary, a description-only `@Param` on an
  * `Option` parameter must not land in the advertised `required` array, and a parameter or input
  * field with a default is not required unless `@Param(required = true)` says so.
  */
class DefaultArgumentsNativeTest extends AnyFunSuite:

  object DefaultsNativeTools:

    @Tool(name = Some("bare_default"))
    def bareDefault(a: Int, s: String = "x"): String = s"$a:$s"

    @Tool(name = Some("described_default"))
    def describedDefault(@Param("First") a: Int, @Param("Suffix") s: String = "x"): String =
      s"$a:$s"

    @Tool(name = Some("required_default"))
    def requiredDefault(
        @Param("First") a: Int,
        @Param("Suffix", required = true) s: String = "x"
    ): String = s"$a:$s"

    @Tool(name = Some("nested_default"))
    def nestedDefault(page: DfNativePage): String = s"${page.number}/${page.size}/${page.sort}"

    @Tool(name = Some("twelve"))
    def twelve(
        p1: Int,
        p2: Int,
        @Param("three") p3: Int,
        p4: Int,
        p5: Int,
        p6: Int,
        p7: Int,
        p8: Int,
        p9: Int,
        p10: Int,
        @Param("eleven") p11: Int,
        p12: Int
    ): String = List(p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12).sum.toString

    @Prompt(name = Some("bare_prompt"))
    def barePrompt(name: String, greeting: String = "Hello"): String = s"$greeting, $name"

    @Tool(name = Some("with_default"))
    def withDefault(
        @Param("First") a: Int,
        @Param("Suffix", required = false) s: String = "x"
    ): String = s"$a:$s"

    @Tool(name = Some("opt_param"))
    def optParam(
        @Param("Label") label: String,
        @Param("Optional note") note: Option[String]
    ): String = s"$label:${note.getOrElse("none")}"

    @Prompt(name = Some("default_prompt"))
    def defaultPrompt(
        @Param("Name") name: String,
        @Param("Greeting", required = false) greeting: String = "Hello"
    ): String = s"$greeting, $name"

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }

  private def inputSchemaOf(server: McpServer[Any], tool: String): Json =
    val toolDef = server.toolManager.getToolDefinition(tool)
    assert(toolDef.isDefined, s"tool '$tool' should be registered")
    toolDef.get.inputSchema.toJsonString.fromJson[Json].fold(e => fail(e), identity)

  private def requiredIn(schema: Json): List[String] =
    schema.asObject.flatMap(_.get("required")).flatMap(_.as[List[String]].toOption).getOrElse(Nil)

  private def requiredOf(server: McpServer[Any], tool: String): List[String] =
    requiredIn(inputSchemaOf(server, tool))

  private def property(schema: Json, name: String): Json =
    schema.asObject
      .flatMap(_.get("properties"))
      .flatMap(_.asObject)
      .flatMap(_.get(name))
      .getOrElse(fail(s"no property '$name' in ${schema.toJson}"))

  /** `tools/call` through the tool manager, which enforces the advertised schema first. */
  private def call(server: McpServer[Any], tool: String, args: (String, Json)*): Either[Throwable, String] =
    runUnsafe(server.toolManager.callTool(tool, args.toMap, None).either).map {
      case StructuredToolResult(content, _) => content.collect { case t: TextContent => t.text }.mkString
      case other => other.toString
    }

  private def annotatedServer(name: String): McpServer[Any] =
    val server = McpServer(name)
    val _ = server.scanAnnotations[DefaultsNativeTools.type]
    server

  // ---- TJC-5117: a defaulted parameter or input field is not required ----

  test("annotated, no @Param: a defaulted parameter is not required and takes the default under Scala Native") {
    val server = annotatedServer("BareDefaultNative")
    assert(requiredOf(server, "bare_default") == List("a"))
    assert(call(server, "bare_default", "a" -> Json.Num(1)) == Right("1:x"))
    assert(call(server, "bare_default", "a" -> Json.Num(1), "s" -> Json.Str("y")) == Right("1:y"))
  }

  test("annotated, description-only @Param: a defaulted parameter is not required under Scala Native") {
    val server = annotatedServer("DescribedDefaultNative")
    assert(requiredOf(server, "described_default") == List("a"))
    assert(call(server, "described_default", "a" -> Json.Num(1)) == Right("1:x"))
  }

  test("@Param(required = true) on a defaulted parameter is required and refused when omitted under Scala Native") {
    val server = annotatedServer("RequiredDefaultNative")
    assert(requiredOf(server, "required_default") == List("a", "s"))
    val omitted = call(server, "required_default", "a" -> Json.Num(1))
    assert(omitted.left.toOption.exists(_.isInstanceOf[ToolArgumentError]), s"got $omitted")
    assert(
      omitted.left.toOption.map(_.getMessage).contains(
        "Invalid arguments for tool 'required_default': missing required property 's'"
      ),
      s"got $omitted"
    )
    assert(call(server, "required_default", "a" -> Json.Num(1), "s" -> Json.Str("y")) == Right("1:y"))
  }

  test("a nested case class's defaulted fields are not required and take their defaults under Scala Native") {
    val server = annotatedServer("NestedDefaultNative")
    assert(requiredIn(property(inputSchemaOf(server, "nested_default"), "page")) == List("number"))
    assert(
      call(server, "nested_default", "page" -> Json.Obj("number" -> Json.Num(3))) == Right("3/20/date")
    )
    assert(call(server, "nested_default", "page" -> Json.Obj("size" -> Json.Num(5))).isLeft)
  }

  test("typed contracts: defaulted fields, top level and nested, are not required under Scala Native") {
    val server = McpServer("TypedDefaultsNative")
    runUnsafe(server.tool(McpTool[DfNativeIn, String](name = "typed")(d => s"${d.a}:${d.s}")))
    runUnsafe(
      server.tool(
        McpTool[DfNativeNested, String](name = "typed_nested")(d =>
          s"${d.q}:${d.page.number}/${d.page.size}/${d.page.sort}"
        )
      )
    )
    runUnsafe(server.tool(McpTool[DfNativeRequired, String](name = "typed_required")(d => s"${d.a}:${d.s}")))

    assert(requiredOf(server, "typed") == List("a"))
    assert(call(server, "typed", "a" -> Json.Num(1)) == Right("1:x"))

    assert(requiredOf(server, "typed_nested") == List("q", "page"))
    assert(requiredIn(property(inputSchemaOf(server, "typed_nested"), "page")) == List("number"))
    assert(
      call(server, "typed_nested", "q" -> Json.Str("k"), "page" -> Json.Obj("number" -> Json.Num(3))) ==
        Right("k:3/20/date")
    )

    assert(requiredOf(server, "typed_required") == List("a", "s"))
    assert(call(server, "typed_required", "a" -> Json.Num(1)).isLeft)
  }

  test("an output schema still lists a defaulted field as required under Scala Native") {
    val tool = McpTool[DfNativeIn, DfNativeOut](name = "out")(d => DfNativeOut(d.a)).withOutputSchema
    val output = tool.definition.outputSchema
      .map(_.toJsonAST.fold(e => fail(e), identity))
      .getOrElse(fail("no outputSchema"))
    assert(requiredIn(output) == List("a", "s"))
    assert(requiredIn(tool.definition.inputSchema.toJsonString.fromJson[Json].fold(e => fail(e), identity)) == List("a"))
  }

  test("`required` follows declaration order with more than ten parameters under Scala Native") {
    val server = annotatedServer("OrderNative")
    assert(requiredOf(server, "twelve") == (1 to 12).map(i => s"p$i").toList)
  }

  test("a defaulted prompt argument without @Param is optional under Scala Native") {
    val server = annotatedServer("BarePromptNative")
    val arguments = server.promptManager.getPromptDefinition("bare_prompt").flatMap(_.arguments).getOrElse(Nil)
    assert(arguments.map(a => a.name -> a.required) == List("name" -> true, "greeting" -> false))
    val messages = runUnsafe(server.promptManager.getPrompt("bare_prompt", Map("name" -> "Ada"), None))
    val text = messages.head.content match
      case TextContent(t, _, _) => t
      case other => fail(s"expected TextContent, got $other")
    assert(text == "Hello, Ada")
  }

  test("an omitted `required = false` parameter with a default takes the Scala default under Scala Native") {
    val server = McpServer("DefaultsNative")
    val _ = server.scanAnnotations[DefaultsNativeTools.type]

    assert(!requiredOf(server, "with_default").contains("s"))
    assert(runUnsafe(server.toolManager.callTool("with_default", Map("a" -> 2), None)) == "2:x")
  }

  test("a description-only @Param on an Option parameter is not required under Scala Native") {
    val server = McpServer("OptionNative")
    val _ = server.scanAnnotations[DefaultsNativeTools.type]

    val required = requiredOf(server, "opt_param")
    assert(!required.contains("note"), s"Option parameter 'note' must not be required: $required")
    assert(required.contains("label"), s"non-Option parameter 'label' stays required: $required")
    assert(runUnsafe(server.toolManager.callTool("opt_param", Map("label" -> "l"), None)) == "l:none")
  }

  test("an omitted `required = false` prompt argument with a default takes the Scala default under Scala Native") {
    val server = McpServer("PromptDefaultsNative")
    val _ = server.scanAnnotations[DefaultsNativeTools.type]

    val messages = runUnsafe(
      server.promptManager.getPrompt("default_prompt", Map("name" -> "Ada"), None)
    )
    val text = messages.head.content match
      case TextContent(t, _, _) => t
      case other => fail(s"expected TextContent, got $other")
    assert(text == "Hello, Ada")
  }
