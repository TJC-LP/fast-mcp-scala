package com.tjclp.fastmcp
package macros

import scala.compiletime.testing.{typeCheckErrors, Error}

import org.scalatest.funsuite.AnyFunSuite
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.MacroDxHarness.{assertSomeMessageContains, messages, runUnsafe}
import com.tjclp.fastmcp.macros.RegistrationMacro.scanAnnotations
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.transport.JvmTransportBackend.given

object SchemaFragmentTools:

  @Tool(name = Some("merge"))
  def merge(
      @Param(
        description = "Sort order",
        examples = List("date"),
        schema = Some("""{"type": "string", "enum": ["relevance", "date"]}""")
      ) sortBy: String,
      @Param(
        description = "Outer description",
        examples = List("outer"),
        schema = Some(
          """{"type": "string", "description": "Fragment description", "examples": ["inner"]}"""
        )
      ) own: String,
      @Param(description = "", schema = Some("""{"type": "integer", "minimum": 1}""")) bare: Int,
      @Param(description = "Anything goes", schema = Some("true")) any: String
  ): String = s"$sortBy/$own/$bare/$any"

case class FragmentArgs(
    @Param(
      description = "Page size",
      examples = List("10"),
      schema = Some("""{"type": "integer", "minimum": 1, "maximum": 100}""")
    ) size: Int
)

object BadSchemaNotJson:
  @Tool def t(@Param("x", schema = Some("not json {")) x: String): String = x

object BadSchemaNotObject:
  @Tool def t(@Param("x", schema = Some("\"string\"")) x: String): String = x

object BadSchemaPositional:
  @Tool def t(@Param("x", Nil, true, Some("[1, 2]")) x: String): String = x

case class BadSchemaField(@Param("x", schema = Some("{oops")) x: String)

object DocumentedTools:

  /** Adds one to a number.
    *
    * Second paragraph that must not reach the client.
    *
    * @param a
    *   the number
    */
  @Tool(name = Some("add_one"))
  def addOne(a: Int): Int = a + 1

  /** A description that wraps over two source lines and mentions [[scala.Int]] and `code` on the
    * way.
    * @return
    *   nothing of note
    */
  @Tool(name = Some("wrapped"))
  def wrapped(a: Int): Int = a

  /** Ignored: the annotation states the description. */
  @Tool(name = Some("explicit"), description = Some("From the annotation"))
  def explicit(a: Int): Int = a

  @Tool(name = Some("undocumented"))
  def undocumented(a: Int): Int = a

  /**
    */
  @Tool(name = Some("empty_doc"))
  def emptyDoc(a: Int): Int = a

  /** Greets someone. */
  @Prompt(name = Some("greet"))
  def greet(name: String): String = s"Hello $name"

  /** The current motto.
    *
    * Changes rarely.
    */
  @Resource("doc://motto")
  def motto(): String = "ship it"

  /** One user record. */
  @Resource("doc://users/{id}")
  def user(id: String): String = id

/** C2.19 / C2.20 / C2.21 (GH #116, TJC-2362): what `@Param(schema = ...)` keeps of its own
  * `@Param`, what a malformed fragment does, and what a Scaladoc comment contributes as a
  * description.
  */
class ParamSchemaAndDocTest extends AnyFunSuite:

  private def json(text: String): Json =
    text.fromJson[Json].fold(error => fail(s"bad JSON <$text>: $error"), identity)

  private def property(schema: Json, name: String): Json =
    schema.asObject
      .flatMap(_.get("properties"))
      .flatMap(_.asObject)
      .flatMap(_.get(name))
      .getOrElse(fail(s"no property '$name' in ${schema.toJson}"))

  // ---- C2.19 ----

  private lazy val fragments = MacroDxHarness("schema-fragments") { server =>
    val _ = server.scanAnnotations[SchemaFragmentTools.type]
    runUnsafe(server.tool(McpTool[FragmentArgs, String](name = "typed")(_.size.toString)))
  }

  test("@Param(schema): the description and examples of the @Param are added to the fragment") {
    assert(
      property(fragments.inputSchema("merge"), "sortBy") == json(
        """{"type":"string","enum":["relevance","date"],"description":"Sort order","examples":["date"]}"""
      )
    )
  }

  test("@Param(schema): what the fragment states itself is kept") {
    assert(
      property(fragments.inputSchema("merge"), "own") == json(
        """{"type":"string","description":"Fragment description","examples":["inner"]}"""
      )
    )
  }

  test("@Param(schema): an empty description adds nothing; a boolean schema is left alone") {
    val schema = fragments.inputSchema("merge")
    assert(property(schema, "bare") == json("""{"type":"integer","minimum":1}"""))
    assert(property(schema, "any") == Json.Bool(true))
  }

  test("@Param(schema) on a typed tool's field merges the same way and is still enforced") {
    assert(
      property(fragments.inputSchema("typed"), "size") == json(
        """{"type":"integer","minimum":1,"maximum":100,"description":"Page size","examples":["10"]}"""
      )
    )
    assert(fragments.call("typed", """{"size":5}""").text == "5")
    val refused = fragments.call("typed", """{"size":500}""")
    assert(refused.isError && refused.text.contains("'size'"), s"reply: $refused")
    val enumRefused = fragments.call("merge", """{"sortBy":"x","own":"o","bare":1,"any":"a"}""")
    assert(enumRefused.isError && enumRefused.text.contains("must be one of"), s"reply: $enumRefused")
  }

  // ---- C2.21 ----

  test("@Param(schema) that is not JSON is a compile-time error") {
    val errs: List[Error] =
      typeCheckErrors("""McpServer("s").scanAnnotations[BadSchemaNotJson.type]""")
    assertSomeMessageContains(errs, "@Param(schema) is not valid JSON", "must be a JSON object")
    assert(errs.sizeIs == 1, messages(errs))
  }

  test("@Param(schema) that is JSON but not a schema is a compile-time error") {
    val string: List[Error] =
      typeCheckErrors("""McpServer("s").scanAnnotations[BadSchemaNotObject.type]""")
    assertSomeMessageContains(string, "@Param(schema) must be a JSON Schema", "a JSON string")
    val array: List[Error] =
      typeCheckErrors("""McpServer("s").scanAnnotations[BadSchemaPositional.type]""")
    assertSomeMessageContains(array, "@Param(schema) must be a JSON Schema", "a JSON array")
  }

  test("a malformed @Param(schema) on a typed tool's field is a compile-time error too") {
    val errs: List[Error] =
      typeCheckErrors("""McpTool[BadSchemaField, String](name = "t")(_.x)""")
    assertSomeMessageContains(errs, "@Param(schema) is not valid JSON")
  }

  test("schemaLiteralProblem: objects and booleans pass, everything else is named") {
    assert(MacroUtils.schemaLiteralProblem("""{"type":"string"}""").isEmpty)
    assert(MacroUtils.schemaLiteralProblem("{}").isEmpty)
    assert(MacroUtils.schemaLiteralProblem("true").isEmpty)
    assert(MacroUtils.schemaLiteralProblem("false").isEmpty)
    assert(MacroUtils.schemaLiteralProblem("").exists(_.contains("not valid JSON")))
    assert(MacroUtils.schemaLiteralProblem("12").exists(_.contains("a JSON number")))
    assert(MacroUtils.schemaLiteralProblem("null").exists(_.contains("null")))
  }

  // ---- C2.20 ----

  test("cookDocstring: delimiters and leading asterisks go, the first paragraph stays") {
    assert(MacroUtils.cookDocstring("/** Adds one. */").contains("Adds one."))
    assert(
      MacroUtils
        .cookDocstring("/** First line\n    * second line.\n    *\n    * Next paragraph.\n    */")
        .contains("First line second line.")
    )
    assert(
      MacroUtils
        .cookDocstring("/**\n  * Summary.\n  * @param a the number\n  */")
        .contains("Summary.")
    )
    assert(
      MacroUtils
        .cookDocstring("/** Summary:\n  * {{{\n  * code()\n  * }}}\n  */")
        .contains("Summary:")
    )
    // Markup inside the paragraph is kept as written, an inner asterisk too.
    assert(
      MacroUtils.cookDocstring("/** Uses [[Foo]] and `bar` * 2. */").contains("Uses [[Foo]] and `bar` * 2.")
    )
    assert(MacroUtils.cookDocstring("/**\r\n * Windows.\r\n */").contains("Windows."))
  }

  test("cookDocstring: a comment without text gives no description") {
    assert(MacroUtils.cookDocstring("/** */").isEmpty)
    assert(MacroUtils.cookDocstring("/**\n  */").isEmpty)
    assert(MacroUtils.cookDocstring("/**\n  * @param a x\n  */").isEmpty)
    assert(MacroUtils.cookDocstring("").isEmpty)
  }

  private lazy val documented = MacroDxHarness("documented") { server =>
    val _ = server.scanAnnotations[DocumentedTools.type]
  }

  private def description(tool: String): Option[String] =
    documented.toolsList
      .find(_.asObject.flatMap(_.get("name")).flatMap(_.asString).contains(tool))
      .flatMap(_.asObject)
      .flatMap(_.get("description"))
      .flatMap(_.asString)

  private def listed(method: String, key: String): List[Json] =
    val raw = documented.frame(s"""{"jsonrpc":"2.0","id":5,"method":"$method","params":{}}""")
    json(raw).asObject
      .flatMap(_.get("result"))
      .flatMap(_.asObject)
      .flatMap(_.get(key))
      .flatMap(_.asArray)
      .map(_.toList)
      .getOrElse(fail(s"$method returned no $key: $raw"))

  private def descriptionOf(entries: List[Json], key: String, value: String): Option[String] =
    entries
      .find(_.asObject.flatMap(_.get(key)).flatMap(_.asString).contains(value))
      .flatMap(_.asObject)
      .flatMap(_.get("description"))
      .flatMap(_.asString)

  test("a @Tool without a description advertises the first paragraph of its Scaladoc") {
    assert(description("add_one").contains("Adds one to a number."))
    assert(
      description("wrapped").contains(
        "A description that wraps over two source lines and mentions [[scala.Int]] and `code` on the way."
      )
    )
  }

  test("an explicit description wins; no Scaladoc, or an empty one, means no description") {
    assert(description("explicit").contains("From the annotation"))
    assert(description("undocumented").isEmpty)
    assert(description("empty_doc").isEmpty)
  }

  test("@Prompt and @Resource descriptions come from the Scaladoc the same way") {
    assert(descriptionOf(listed("prompts/list", "prompts"), "name", "greet").contains("Greets someone."))
    assert(
      descriptionOf(listed("resources/list", "resources"), "uri", "doc://motto")
        .contains("The current motto.")
    )
    // Templates are listed on the wire only with `exposeTemplatesEndpoint`; read the registration.
    val server = McpServer("documented-templates")
    val _ = server.scanAnnotations[DocumentedTools.type]
    assert(
      server.resourceManager.listTemplateResources().map(d => d.uri -> d.description) ==
        List("doc://users/{id}" -> Some("One user record."))
    )
  }
