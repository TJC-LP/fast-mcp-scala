package com.tjclp.fastmcp
package macros

import org.scalacheck.{Prop, Test}
import org.scalacheck.Prop.propBoolean
import org.scalacheck.rng.Seed
import org.scalacheck.util.Pretty
import org.scalatest.funsuite.AnyFunSuite
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.macros.RegistrationMacro.scanAnnotations

/** A nested input type: `number` has no default, `size` has one, `sort` has one and a description.
  */
case class DfPage(number: Int, size: Int = 20, @Param("Sort key") sort: String = "date")

/** Typed-contract inputs. */
case class DfBare(a: Int, s: String = "x")
case class DfDescribed(@Param("First") a: Int, @Param("Suffix") s: String = "x")
case class DfRequired(a: Int, @Param("Suffix", required = true) s: String = "x")
case class DfNested(q: String, page: DfPage)
case class DfNestedDefault(q: String, page: DfPage = DfPage(1))
case class DfPages(pages: List[DfPage], byName: Map[String, DfPage] = Map.empty)
case class DfGeneric[T](items: List[T], size: Int = 10)

/** Twelve fields, declared out of alphabetical order, one `@Param` (the metadata pass rewrites
  * `required`, which is where the alphabetical sort used to happen).
  */
case class DfTwelve(
    p1: Int,
    p2: Int,
    p3: Int,
    p4: Int,
    p5: Int,
    p6: Int,
    p7: Int,
    p8: Int,
    p9: Int,
    @Param("ten") p10: Int,
    p11: Int,
    p12: Int
)

/** A typed tool result: a defaulted field of an OUTPUT type stays required. */
case class DfOut(a: Int, s: String = "x", @Param("Count") n: Int = 1, note: Option[String] = None)

object DfOut:
  given JsonEncoder[DfOut] = DeriveJsonEncoder.gen[DfOut]

object DefaultedTools:

  // (a) an annotated method, the defaulted parameter has no @Param
  @Tool(name = Some("bare"))
  def bare(a: Int, s: String = "x"): String = s"$a:$s"

  // (b) an annotated method, the defaulted parameter has a description-only @Param
  @Tool(name = Some("described"))
  def described(@Param("First") a: Int, @Param("Suffix") s: String = "x"): String = s"$a:$s"

  // An explicit `required = true` wins over the default.
  @Tool(name = Some("required_default"))
  def requiredDefault(
      @Param("First") a: Int,
      @Param("Suffix", required = true) s: String = "x"
  ): String = s"$a:$s"

  // The positional spelling of `required = true` (description, examples, required).
  @Tool(name = Some("required_positional"))
  def requiredPositional(@Param("Suffix", Nil, true) s: String = "x"): String = s

  // Every parameter defaulted: the schema has no `required` at all.
  @Tool(name = Some("all_defaulted"))
  def allDefaulted(a: Int = 1, @Param("Suffix") s: String = "x", flag: Boolean = true): String =
    s"$a:$s:$flag"

  // (d) a nested case class with defaulted fields, as a parameter and inside a collection
  @Tool(name = Some("nested"))
  def nested(page: DfPage): String = s"${page.number}/${page.size}/${page.sort}"

  @Tool(name = Some("nested_list"))
  def nestedList(pages: List[DfPage]): String =
    pages.map(p => s"${p.number}/${p.size}/${p.sort}").mkString(",")

  @Tool(name = Some("nested_param_default"))
  def nestedParamDefault(page: DfPage = DfPage(7, 5)): String =
    s"${page.number}/${page.size}/${page.sort}"

  // Declaration order, more than ten parameters, with and without @Param.
  @Tool(name = Some("twelve_bare"))
  def twelveBare(
      p1: Int,
      p2: Int,
      p3: Int,
      p4: Int,
      p5: Int,
      p6: Int,
      p7: Int,
      p8: Int,
      p9: Int,
      p10: Int,
      p11: Int,
      p12: Int
  ): String = List(p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12).sum.toString

  @Tool(name = Some("twelve_described"))
  def twelveDescribed(
      @Param("one") p1: Int,
      p2: Int,
      @Param("three") p3: Int,
      p4: Int,
      p5: Int,
      p6: Int,
      p7: Int,
      p8: Int,
      p9: Int,
      @Param("ten") p10: Int,
      @Param("eleven") p11: Int,
      p12: Int
  ): String = List(p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12).sum.toString

  // Order with optional parameters in between: `required` skips them and keeps the order.
  @Tool(name = Some("mixed_order"))
  def mixedOrder(
      @Param("z") zeta: Int,
      skipped: Int = 0,
      @Param("m") mu: Int,
      maybe: Option[Int],
      @Param("a") alpha: Int,
      @Param("kept", required = true) kept: Int = 9
  ): String = s"$zeta$skipped$mu$maybe$alpha$kept"

  @Prompt(name = Some("bare_prompt"))
  def barePrompt(name: String, greeting: String = "Hello"): String = s"$greeting, $name"

  @Prompt(name = Some("described_prompt"))
  def describedPrompt(
      @Param("Name") name: String,
      @Param("Greeting") greeting: String = "Hello"
  ): String = s"$greeting, $name"

  @Prompt(name = Some("required_prompt"))
  def requiredPrompt(
      @Param("Name") name: String,
      @Param("Greeting", required = true) greeting: String = "Hello"
  ): String = s"$greeting, $name"

/** TJC-5117 (GH #116 rows C2.6, C2.23). A parameter or input field with a Scala default is not in
  * the advertised `required` list unless `@Param(required = true)` is written, because the handler
  * applies the default when the argument is omitted; `required` follows declaration order. Every
  * call here goes through `tools/call` / `prompts/get` frames on a server with input validation on
  * (the default), so an over-constrained schema would refuse the call before the handler.
  */
class DefaultedNotRequiredTest extends AnyFunSuite:

  private lazy val annotated = MacroDxHarness("defaulted-annotated") { server =>
    val _ = server.scanAnnotations[DefaultedTools.type]
  }

  private lazy val typed = MacroDxHarness("defaulted-typed") { server =>
    def page(p: DfPage): String = s"${p.number}/${p.size}/${p.sort}"
    val tools = List(
      server.tool(McpTool[DfBare, String](name = "bare")(d => s"${d.a}:${d.s}")),
      server.tool(McpTool[DfDescribed, String](name = "described")(d => s"${d.a}:${d.s}")),
      server.tool(McpTool[DfRequired, String](name = "required_default")(d => s"${d.a}:${d.s}")),
      server.tool(McpTool[DfNested, String](name = "nested")(d => s"${d.q}:${page(d.page)}")),
      server.tool(
        McpTool[DfNestedDefault, String](name = "nested_default")(d => s"${d.q}:${page(d.page)}")
      ),
      server.tool(
        McpTool[DfPages, String](name = "pages")(d =>
          d.pages.map(page).mkString(",") + s"|${d.byName.size}"
        )
      ),
      server.tool(
        McpTool[DfGeneric[Int], String](name = "generic")(d => s"${d.items.sum}/${d.size}")
      ),
      server.tool(McpTool[DfTwelve, String](name = "twelve")(_ => "ok")),
      server.tool(McpTool[DfBare, DfOut](name = "out")(d => DfOut(d.a)).withOutputSchema)
    )
    tools.foreach(tool => MacroDxHarness.runUnsafe(tool))
  }

  private def schemaAt(schema: Json, path: String*): Json =
    path.foldLeft(schema) { (json, key) =>
      json.asObject.flatMap(_.get(key)).getOrElse(fail(s"no '$key' in ${json.toJson}"))
    }

  private def hasKey(schema: Json, key: String): Boolean =
    schema.asObject.exists(_.contains(key))

  private def requiredIn(schema: Json): List[String] = MacroDxHarness.stringArray(schema, "required")

  private def reply(h: MacroDxHarness, method: String, params: String): Json =
    val raw = h.frame(s"""{"jsonrpc":"2.0","id":99,"method":"$method","params":$params}""")
    raw.fromJson[Json].fold(err => fail(s"unparseable reply <$raw>: $err"), identity)

  private def promptArguments(prompt: String): Map[String, Boolean] =
    val prompts = schemaAt(reply(annotated, "prompts/list", "{}"), "result", "prompts").asArray
      .map(_.toList)
      .getOrElse(Nil)
    val found = prompts
      .find(_.asObject.flatMap(_.get("name")).flatMap(_.asString).contains(prompt))
      .getOrElse(fail(s"prompt '$prompt' not listed"))
    schemaAt(found, "arguments").asArray
      .map(_.toList)
      .getOrElse(Nil)
      .flatMap { arg =>
        val obj = arg.asObject.getOrElse(Json.Obj())
        obj
          .get("name")
          .flatMap(_.asString)
          .map(_ -> obj.get("required").flatMap(_.asBoolean).getOrElse(false))
      }
      .toMap

  private def promptText(prompt: String, arguments: String): Either[String, String] =
    val json = reply(annotated, "prompts/get", s"""{"name":"$prompt","arguments":$arguments}""")
    json.asObject.flatMap(_.get("result")) match
      case Some(result) =>
        val messages = schemaAt(result, "messages").asArray.map(_.toList).getOrElse(Nil)
        Right(messages.flatMap(m => schemaAt(m, "content", "text").asString).mkString("\n"))
      case None => Left(json.toJson)

  // ---- (a) annotated method, no @Param ----

  test("annotated, no @Param: a defaulted parameter is not required and the default is applied") {
    assert(annotated.required("bare") == List("a"))
    val omitted = annotated.call("bare", """{"a":1}""")
    assert(!omitted.isError && omitted.text == "1:x", s"reply: $omitted")
    val supplied = annotated.call("bare", """{"a":1,"s":"y"}""")
    assert(!supplied.isError && supplied.text == "1:y", s"reply: $supplied")
  }

  // ---- (b) annotated method, description-only @Param ----

  test("annotated, description-only @Param: a defaulted parameter is not required") {
    assert(annotated.required("described") == List("a"))
    val description = schemaAt(annotated.inputSchema("described"), "properties", "s", "description")
    assert(description.asString.contains("Suffix"))
    val omitted = annotated.call("described", """{"a":1}""")
    assert(!omitted.isError && omitted.text == "1:x", s"reply: $omitted")
  }

  test("annotated: when every parameter has a default the schema has no `required` and {} is accepted") {
    assert(!hasKey(annotated.inputSchema("all_defaulted"), "required"))
    val out = annotated.call("all_defaulted", "{}")
    assert(!out.isError && out.text == "1:x:true", s"reply: $out")
  }

  // ---- explicit required = true ----

  test("annotated: @Param(required = true) on a defaulted parameter is required and enforced") {
    for tool <- List("required_default", "required_positional") do
      assert(annotated.required(tool).contains("s"), s"$tool: ${annotated.required(tool)}")
    val omitted = annotated.call("required_default", """{"a":1}""")
    assert(omitted.isError, s"an omitted required argument must be refused: $omitted")
    assert(
      omitted.text ==
        "Invalid arguments for tool 'required_default': missing required property 's'",
      omitted.text
    )
    assert(annotated.call("required_positional", "{}").isError)
    val supplied = annotated.call("required_default", """{"a":1,"s":"y"}""")
    assert(!supplied.isError && supplied.text == "1:y", s"reply: $supplied")
  }

  // ---- (d) nested case class, annotation path ----

  test("annotated: a nested case class's defaulted fields are not required, with or without @Param") {
    val page = schemaAt(annotated.inputSchema("nested"), "properties", "page")
    assert(requiredIn(page) == List("number"))
    assert(schemaAt(page, "properties", "sort", "description").asString.contains("Sort key"))
    val out = annotated.call("nested", """{"page":{"number":3}}""")
    assert(!out.isError && out.text == "3/20/date", s"reply: $out")
    val partial = annotated.call("nested", """{"page":{"number":3,"sort":"name"}}""")
    assert(!partial.isError && partial.text == "3/20/name", s"reply: $partial")
    // The field without a default is still required and enforced.
    assert(annotated.call("nested", """{"page":{"size":5}}""").isError)
  }

  test("annotated: the rule holds for a case class inside a collection") {
    val item = schemaAt(annotated.inputSchema("nested_list"), "properties", "pages", "items")
    assert(requiredIn(item) == List("number"))
    val out = annotated.call("nested_list", """{"pages":[{"number":1},{"number":2,"size":5}]}""")
    assert(!out.isError && out.text == "1/20/date,2/5/date", s"reply: $out")
  }

  test("annotated: a defaulted case-class parameter can be omitted altogether") {
    assert(!hasKey(annotated.inputSchema("nested_param_default"), "required"))
    val omitted = annotated.call("nested_param_default", "{}")
    assert(!omitted.isError && omitted.text == "7/5/date", s"reply: $omitted")
    val supplied = annotated.call("nested_param_default", """{"page":{"number":2}}""")
    assert(!supplied.isError && supplied.text == "2/20/date", s"reply: $supplied")
  }

  // ---- (c) typed contracts ----

  test("typed: a defaulted field is not required and the default is applied") {
    assert(typed.required("bare") == List("a"))
    val omitted = typed.call("bare", """{"a":1}""")
    assert(!omitted.isError && omitted.text == "1:x", s"reply: $omitted")
    val supplied = typed.call("bare", """{"a":1,"s":"y"}""")
    assert(!supplied.isError && supplied.text == "1:y", s"reply: $supplied")
  }

  test("typed: a description-only @Param on a defaulted field does not re-require it") {
    assert(typed.required("described") == List("a"))
    val omitted = typed.call("described", """{"a":1}""")
    assert(!omitted.isError && omitted.text == "1:x", s"reply: $omitted")
  }

  test("typed: @Param(required = true) on a defaulted field is required and enforced") {
    assert(typed.required("required_default") == List("a", "s"))
    val omitted = typed.call("required_default", """{"a":1}""")
    assert(omitted.isError, s"an omitted required field must be refused: $omitted")
    assert(
      omitted.text ==
        "Invalid arguments for tool 'required_default': missing required property 's'",
      omitted.text
    )
  }

  // ---- (d) nested case class, typed path ----

  test("typed: a nested case class's defaulted fields are not required and take their defaults") {
    val page = schemaAt(typed.inputSchema("nested"), "properties", "page")
    assert(typed.required("nested") == List("q", "page"))
    assert(requiredIn(page) == List("number"))
    val out = typed.call("nested", """{"q":"k","page":{"number":3}}""")
    assert(!out.isError && out.text == "k:3/20/date", s"reply: $out")
    assert(typed.call("nested", """{"q":"k","page":{}}""").isError)
  }

  test("typed: a defaulted field of case-class type can be omitted altogether") {
    assert(typed.required("nested_default") == List("q"))
    val omitted = typed.call("nested_default", """{"q":"k"}""")
    assert(!omitted.isError && omitted.text == "k:1/20/date", s"reply: $omitted")
    val supplied = typed.call("nested_default", """{"q":"k","page":{"number":4,"size":2}}""")
    assert(!supplied.isError && supplied.text == "k:4/2/date", s"reply: $supplied")
  }

  test("typed: the rule holds inside a list, a map value and a generic case class") {
    val schema = typed.inputSchema("pages")
    assert(requiredIn(schema) == List("pages"))
    assert(requiredIn(schemaAt(schema, "properties", "pages", "items")) == List("number"))
    assert(
      requiredIn(schemaAt(schema, "properties", "byName", "additionalProperties")) ==
        List("number")
    )
    val out = typed.call("pages", """{"pages":[{"number":1}]}""")
    assert(!out.isError && out.text == "1/20/date|0", s"reply: $out")

    assert(typed.required("generic") == List("items"))
    val generic = typed.call("generic", """{"items":[1,2,3]}""")
    assert(!generic.isError && generic.text == "6/10", s"reply: $generic")
  }

  // ---- output schemas keep the old rule ----

  test("an output schema still lists a defaulted field as required") {
    val tool = typed.toolsList
      .find(_.asObject.flatMap(_.get("name")).flatMap(_.asString).contains("out"))
      .getOrElse(fail("tool 'out' not advertised"))
    val output = schemaAt(tool, "outputSchema")
    assert(requiredIn(output) == List("a", "s", "n"), output.toJson)
    // The same type as an INPUT drops the defaulted fields.
    assert(requiredIn(JsonSchemaMacro.schemaForToolInput[DfOut]) == List("a"))
    assert(requiredIn(JsonSchemaMacro.schemaForToolOutput[DfOut]) == List("a", "s", "n"))
    // The result really carries the defaulted fields.
    val out = typed.call("out", """{"a":5}""")
    assert(out.structuredContent.map(_.toJson).contains("""{"a":5,"s":"x","n":1}"""), s"$out")
  }

  test("schemaForType uses the input rule, at every depth") {
    assert(requiredIn(JsonSchemaMacro.schemaForType[DfBare]) == List("a"))
    val nested = JsonSchemaMacro.schemaForType[DfNested]
    assert(requiredIn(schemaAt(nested, "properties", "page")) == List("number"))
    val out = JsonSchemaMacro.schemaForToolOutput[DfNested]
    assert(requiredIn(schemaAt(out, "properties", "page")) == List("number", "size", "sort"))
  }

  test("schemaForFunctionArgs alone keeps a defaulted top-level parameter required") {
    // The function type carries no defaults; the @Tool processor removes the parameter.
    val schema = JsonSchemaMacro.schemaForFunctionArgs(DefaultedTools.bare)
    assert(requiredIn(schema) == List("a", "s"))
    // A nested case class is input all the same.
    val nested = JsonSchemaMacro.schemaForFunctionArgs(DefaultedTools.nested)
    assert(requiredIn(schemaAt(nested, "properties", "page")) == List("number"))
  }

  // ---- declaration order ----

  private val twelve = (1 to 12).map(i => s"p$i").toList

  test("`required` follows declaration order with more than ten parameters") {
    for tool <- List("twelve_bare", "twelve_described") do
      assert(annotated.propertyNames(tool) == twelve, s"$tool properties")
      assert(annotated.required(tool) == twelve, s"$tool required")
    assert(typed.propertyNames("twelve") == twelve)
    assert(typed.required("twelve") == twelve)
  }

  test("`required` keeps declaration order around optional parameters") {
    assert(
      annotated.propertyNames("mixed_order") ==
        List("zeta", "skipped", "mu", "maybe", "alpha", "kept")
    )
    assert(annotated.required("mixed_order") == List("zeta", "mu", "alpha", "kept"))
  }

  // ---- prompts ----

  test("a defaulted prompt argument is optional unless @Param(required = true) is written") {
    assert(promptArguments("bare_prompt") == Map("name" -> true, "greeting" -> false))
    assert(promptArguments("described_prompt") == Map("name" -> true, "greeting" -> false))
    assert(promptArguments("required_prompt") == Map("name" -> true, "greeting" -> true))
    assert(promptText("bare_prompt", """{"name":"Ada"}""") == Right("Hello, Ada"))
    assert(promptText("described_prompt", """{"name":"Ada"}""") == Right("Hello, Ada"))
    assert(promptText("described_prompt", """{"name":"Ada","greeting":"Hi"}""") == Right("Hi, Ada"))
    // The explicitly required argument is enforced although it has a default.
    assert(promptText("required_prompt", """{"name":"Ada"}""").isLeft)
    assert(
      promptText("required_prompt", """{"name":"Ada","greeting":"Hi"}""") == Right("Hi, Ada")
    )
  }

  // ---- schema and handler agree ----

  private val params =
    Test.Parameters.default.withMinSuccessfulTests(40).withInitialSeed(Seed(20261112L))

  private def check(prop: Prop): Unit =
    val result = Test.check(params, prop)
    assert(result.passed, Pretty.pretty(result, Pretty.Params(2)))

  private val annotatedTools = List(
    "bare",
    "described",
    "required_default",
    "required_positional",
    "all_defaulted",
    "nested",
    "nested_list",
    "nested_param_default",
    "mixed_order"
  )

  private val typedTools =
    List("bare", "described", "required_default", "nested", "nested_default", "pages", "generic")

  private val suites: List[(String, () => MacroDxHarness, List[String])] =
    List(("annotated", () => annotated, annotatedTools), ("typed", () => typed, typedTools))

  for (label, harness, tools) <- suites do
    test(s"$label: every argument object that conforms to the advertised schema reaches the handler") {
      val h = harness()
      tools.foreach { tool =>
        val schema = h.inputSchema(tool)
        check(Prop.forAllNoShrink(SchemaGen.conforming(schema)) { args =>
          val out = h.call(tool, args)
          (!out.isError) :| s"tool=$tool args=${args.toJson} reply=${out.text}"
        })
        check(Prop.forAllNoShrink(SchemaGen.requiredOnly(schema)) { args =>
          val out = h.call(tool, args)
          (!out.isError) :| s"tool=$tool required-only args=${args.toJson} reply=${out.text}"
        })
      }
    }

    test(s"$label: dropping any one required key is refused") {
      val h = harness()
      tools.foreach { tool =>
        val schema = h.inputSchema(tool)
        val required = requiredIn(schema)
        val full = SchemaGen
          .requiredOnly(schema)
          .apply(org.scalacheck.Gen.Parameters.default, Seed(7L))
          .flatMap(_.asObject)
          .getOrElse(fail(s"no instance for $tool"))
        required.foreach { key =>
          val out = h.call(tool, full.remove(key))
          assert(out.isError, s"$tool accepted a call without required '$key': $out")
        }
      }
    }
