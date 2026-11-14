package downstream

import java.util.concurrent.TimeUnit

import scala.concurrent.{ExecutionContext, Future}

import org.scalatest.funsuite.AsyncFunSuite
import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.core.StructuredToolResult

enum DxShade:
  case LIGHT, DARK

case class DxPoint(x: Int, y: Int)
case class DxPair(pair: (Int, String), maybe: Option[(String, DxShade)])
case class DxUnits(unit: TimeUnit, others: List[TimeUnit])
case class DxUnitOut(unit: TimeUnit, shade: DxShade)

object DxAnnotated:

  @Tool(name = Some("tuple_option"))
  def tupleOption(p: Option[(Int, String)]): String = p.toString

  @Tool(name = Some("tuple_list"))
  def tupleList(p: List[(Int, DxShade)]): String = p.toString

  @Tool(name = Some("time_unit"))
  def timeUnit(unit: TimeUnit, more: Map[String, TimeUnit]): String =
    s"$unit/${more.toList.sortBy(_._1).map((k, v) => s"$k=$v").mkString(",")}"

  @Tool(name = Some("day"))
  def day(day: java.time.DayOfWeek, month: java.time.Month): String = s"$day/$month"

object DxFragment:

  @Tool(name = Some("sorted"))
  def sorted(
      @Param(
        description = "Sort order",
        examples = List("date"),
        schema = Some("""{"type": "string", "enum": ["relevance", "date"]}""")
      ) sortBy: String
  ): String = sortBy

object DxResources:

  @Resource("dx://users/{id}", mimeType = Some("application/json"))
  def user(id: String): String = s"""{"id":"$id"}"""

  @Resource("dx://notes/{id}")
  def note(id: String): String = s"note $id"

object DxMissing:

  @Tool(name = Some("annotated"))
  def annotated(p: String): String = p

object DxContextual:

  @Tool(name = Some("who"))
  def who(request: McpContext, n: Int): String =
    s"${request.getClientInfo.map(_.name).getOrElse("nobody")}:$n"

  @Prompt(name = Some("hello"))
  def hello(@Param("Who to greet") name: String, context: McpContext): String =
    s"Hello $name from ${context.getClientInfo.map(_.name).getOrElse("nobody")}"

/** The macro and schema behaviour of TJC-2362 (GH #116) that depends on what each platform's
  * compiler and runtime see: codec derivation (`Unit` input, enum / `List` / `Vector` results,
  * tuples, Java enums) and the schemas that go with it. Compiled into the JVM, Scala.js and Scala
  * Native test modules (`shared/test/src`), from a package outside `com.tjclp.fastmcp`, with the
  * root import a user writes.
  */
class MacroSchemaDxSpec extends AsyncFunSuite:

  // See StdioLoggingSpec: ScalaTest's serial context does not run ZIO's completions on Scala.js.
  override implicit val executionContext: ExecutionContext = ExecutionContext.global

  private def run[A](effect: Task[A]): Future[A] =
    Unsafe.unsafe(implicit unsafe => Runtime.default.unsafe.runToFuture(effect))

  private def json(text: String): Json =
    text.fromJson[Json].fold(error => fail(s"bad test JSON <$text>: $error"), identity)

  private def arguments(text: String): Map[String, Any] =
    json(text).asObject.map(_.fields.toMap).getOrElse(fail(s"not an object: $text"))

  /** One call through the tool manager (schema validation, then decode, then the handler): the
    * text a client would receive, or the failure.
    */
  private def call(server: McpServer[Any], tool: String, args: String): Task[Either[String, String]] =
    server.toolManager.callTool(tool, arguments(args), None).either.map {
      case Right(StructuredToolResult(content, _)) =>
        Right(content.collect { case text: TextContent => text.text }.mkString)
      case Right(other) => Right(other.toString)
      case Left(error) => Left(String.valueOf(error.getMessage))
    }

  private def inputSchema(server: McpServer[Any], tool: String): Json =
    server.toolManager
      .getToolDefinition(tool)
      .map(_.inputSchema.toJsonString)
      .map(json)
      .getOrElse(fail(s"tool '$tool' is not registered"))

  private def at(schema: Json, path: String*): Json =
    path.foldLeft(schema)((node, key) => node.asObject.flatMap(_.get(key)).getOrElse(Json.Null))

  private def strings(node: Json): List[String] =
    node.asArray.map(_.toList.flatMap(_.asString)).getOrElse(Nil)

  private val timeUnitNames =
    List("NANOSECONDS", "MICROSECONDS", "MILLISECONDS", "SECONDS", "MINUTES", "HOURS", "DAYS")

  private def annotated: McpServer[Any] =
    val server = McpServer("macro-schema-dx")
    val _ = server.scanAnnotations[DxAnnotated.type]
    server

  // ---- C2.11: McpTool[Unit, Out] ----

  test("McpTool[Unit, String] compiles, is called with {} and refuses arguments") {
    val server = McpServer("unit-in")
    val program =
      for
        _ <- server.tool(McpTool[Unit, String](name = "ping")(_ => "pong"))
        ok <- call(server, "ping", "{}")
        extra <- call(server, "ping", """{"a":1}""")
      yield (ok, extra)
    run(program).map { (ok, extra) =>
      assert(ok == Right("pong"))
      assert(extra == Left("Invalid arguments for tool 'ping': unexpected property 'a'"))
      assert(
        inputSchema(server, "ping") ==
          json("""{"type":"object","properties":{},"additionalProperties":false}""")
      )
    }
  }

  // ---- C2.12: results that had no McpEncoder ----

  test("a bare Scala 3 enum result is written as its case name") {
    val server = McpServer("enum-out")
    val program =
      server.tool(McpTool[DxPoint, DxShade](name = "shade")(p => if p.x > 0 then DxShade.LIGHT else DxShade.DARK)) *>
        call(server, "shade", """{"x":1,"y":2}""")
    run(program).map(out => assert(out == Right("\"LIGHT\"")))
  }

  test("List and Vector results are written as a JSON array") {
    val server = McpServer("list-out")
    val program =
      for
        _ <- server.tool(McpTool[DxPoint, List[String]](name = "names")(p => List(s"x${p.x}", s"y${p.y}")))
        _ <- server.tool(McpTool[DxPoint, Vector[Int]](name = "numbers")(p => Vector(p.x, p.y)))
        _ <- server.tool(
          McpTool[DxPoint, List[Content]](name = "content")(p => List(TextContent(s"${p.x},${p.y}")))
        )
        names <- call(server, "names", """{"x":1,"y":2}""")
        numbers <- call(server, "numbers", """{"x":1,"y":2}""")
        content <- call(server, "content", """{"x":1,"y":2}""")
      yield (names, numbers, content)
    run(program).map { (names, numbers, content) =>
      assert(names == Right("""["x1","y2"]"""))
      assert(numbers == Right("[1,2]"))
      // List[Content] is content, not JSON: it keeps its own instance.
      assert(content == Right("1,2"))
    }
  }

  // ---- C2.13: one wire shape for a tuple ----

  test("a tuple is the {_1, _2} object its schema advertises, also inside Option and List") {
    val server = annotated
    val tupleSchema = json(
      """{"type":"object","properties":{"_1":{"type":"integer"},"_2":{"type":"string"}},"required":["_1","_2"]}"""
    )
    assert(at(inputSchema(server, "tuple_option"), "properties", "p", "anyOf").asArray.map(_.head).contains(tupleSchema))
    val program =
      for
        some <- call(server, "tuple_option", """{"p":{"_1":1,"_2":"x"}}""")
        none <- call(server, "tuple_option", "{}")
        array <- call(server, "tuple_option", """{"p":[1,"x"]}""")
        list <- call(server, "tuple_list", """{"p":[{"_1":1,"_2":"DARK"}]}""")
      yield (some, none, array, list)
    run(program).map { (some, none, array, list) =>
      assert(some == Right("Some((1,x))"))
      assert(none == Right("None"))
      assert(array.isLeft, s"the array shape is not what the schema describes: $array")
      assert(list == Right("List((1,DARK))"))
    }
  }

  test("a tuple field of a typed tool's input is the same object") {
    val server = McpServer("tuple-field")
    val program =
      server.tool(McpTool[DxPair, String](name = "pair")(_.toString)) *>
        call(server, "pair", """{"pair":{"_1":1,"_2":"a"},"maybe":{"_1":"b","_2":"LIGHT"}}""")
    run(program).map(out => assert(out == Right("DxPair((1,a),Some((b,LIGHT)))")))
  }

  test("a tuple in a result is still written as a JSON array, derived elements included") {
    val server = McpServer("tuple-out")
    val program =
      server.tool(
        McpTool[DxPoint, DxPair](name = "pair_out")(p => DxPair((p.x, "a"), Some(("b", DxShade.DARK))))
      ) *> call(server, "pair_out", """{"x":1,"y":2}""")
    run(program).map { out =>
      assert(out.map(json) == Right(json("""{"pair":[1,"a"],"maybe":["b","DARK"]}""")))
    }
  }

  // ---- C2.14: Java enums ----

  test("a Java enum parameter advertises its constant names and decodes them") {
    val server = annotated
    val schema = inputSchema(server, "time_unit")
    assert(strings(at(schema, "properties", "unit", "enum")) == timeUnitNames)
    assert(strings(at(schema, "properties", "more", "additionalProperties", "enum")) == timeUnitNames)
    val program =
      for
        ok <- call(server, "time_unit", """{"unit":"SECONDS","more":{"a":"DAYS","b":"HOURS"}}""")
        bad <- call(server, "time_unit", """{"unit":"seconds","more":{}}""")
      yield (ok, bad)
    run(program).map { (ok, bad) =>
      assert(ok == Right("SECONDS/a=DAYS,b=HOURS"))
      assert(bad.left.exists(_.contains("must be one of: NANOSECONDS")), s"got $bad")
    }
  }

  test("a Java enum field of a typed tool's input and output derives its codec") {
    val server = McpServer("java-enum-typed")
    val program =
      for
        _ <- server.tool(McpTool[DxUnits, String](name = "units")(u => s"${u.unit}+${u.others.mkString(",")}"))
        _ <- server.tool(
          McpTool[DxUnits, DxUnitOut](name = "unit_out")(u => DxUnitOut(u.unit, DxShade.DARK))
        )
        in <- call(server, "units", """{"unit":"MINUTES","others":["DAYS","HOURS"]}""")
        out <- call(server, "unit_out", """{"unit":"MINUTES","others":[]}""")
      yield (in, out)
    run(program).map { (in, out) =>
      assert(strings(at(inputSchema(server, "units"), "properties", "unit", "enum")) == timeUnitNames)
      assert(in == Right("MINUTES+DAYS,HOURS"))
      assert(out.map(json) == Right(json("""{"unit":"MINUTES","shade":"DARK"}""")))
    }
  }

  test("java.time.DayOfWeek and Month advertise their names") {
    val server = annotated
    val schema = inputSchema(server, "day")
    assert(
      strings(at(schema, "properties", "day", "enum")) ==
        List("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")
    )
    assert(strings(at(schema, "properties", "month", "enum")).take(2) == List("JANUARY", "FEBRUARY"))
    assert(strings(at(schema, "properties", "month", "enum")).sizeIs == 12)
    val program =
      for
        ok <- call(server, "day", """{"day":"FRIDAY","month":"MAY"}""")
        bad <- call(server, "day", """{"day":"Friyay","month":"MAY"}""")
      yield (ok, bad)
    run(program).map { (ok, bad) =>
      assert(ok == Right("FRIDAY/MAY"))
      assert(bad.left.exists(_.contains("must be one of: MONDAY")), s"got $bad")
    }
  }

  // ---- D1.32 / D1.33: McpContext by type ----

  test("a McpContext parameter of any name is injected into a @Tool and a @Prompt") {
    val server = McpServer("context-by-type")
    val _ = server.scanAnnotations[DxContextual.type]
    // A user cannot build a context with client info (the constructor is the library's), so the
    // injected one is told apart from a missing argument by the call succeeding at all.
    val context = McpContext.empty
    val promptArguments =
      server.promptManager.getPromptDefinition("hello").flatMap(_.arguments).getOrElse(Nil).map(_.name)
    val program =
      for
        tool <- server.toolManager.callTool("who", arguments("""{"n":3}"""), Some(context))
        prompt <- server.promptManager.getPrompt("hello", Map("name" -> "Ada"), Some(context))
      yield (tool, prompt)
    run(program).map { (tool, prompt) =>
      assert(at(inputSchema(server, "who"), "properties") == json("""{"n":{"type":"integer"}}"""))
      assert(promptArguments == List("name"))
      assert(tool == "nobody:3")
      assert(prompt.map(_.content) == List(TextContent("Hello Ada from nobody")))
    }
  }

  // ---- C2.19: @Param(schema) keeps the description and examples ----

  test("@Param(schema): description and examples are merged into the fragment, which is enforced") {
    val server = McpServer("fragment")
    val _ = server.scanAnnotations[DxFragment.type]
    val program =
      for
        ok <- call(server, "sorted", """{"sortBy":"date"}""")
        bad <- call(server, "sorted", """{"sortBy":"size"}""")
      yield (ok, bad)
    run(program).map { (ok, bad) =>
      assert(
        at(inputSchema(server, "sorted"), "properties", "sortBy") == json(
          """{"type":"string","enum":["relevance","date"],"description":"Sort order","examples":["date"]}"""
        )
      )
      assert(ok == Right("date"))
      assert(bad.left.exists(_.contains("must be one of: relevance, date")), s"got $bad")
    }
  }

  // ---- D1.23: the mime type of a templated read ----

  test("a templated read carries the mimeType its template declares") {
    val server = McpServer("template-mime")
    val _ = server.scanAnnotations[DxResources.type]
    val program =
      for
        declared <- server.resourceManager.readResourceWithMime("dx://users/7", None)
        undeclared <- server.resourceManager.readResourceWithMime("dx://notes/7", None)
      yield (declared, undeclared)
    run(program).map { (declared, undeclared) =>
      assert(declared == (Some("application/json"), """{"id":"7"}"""))
      assert(undeclared == (None, "note 7"))
    }
  }

  // ---- D1.13: one message for a missing argument on both registration paths ----

  test("a missing required argument reads the same on both paths, with validation on and off") {
    def messages(validate: Boolean): Task[(Either[String, String], Either[String, String])] =
      val server = McpServer("missing", settings = McpServerSettings(validateToolInput = validate))
      val _ = server.scanAnnotations[DxMissing.type]
      for
        _ <- server.tool(McpTool[DxPoint, String](name = "typed")(_.toString))
        annotated <- call(server, "annotated", "{}")
        typed <- call(server, "typed", """{"y":1}""")
      yield (annotated, typed)
    val program =
      for
        on <- messages(validate = true)
        off <- messages(validate = false)
      yield List(on._1, on._2, off._1, off._2)
    run(program).map { results =>
      assert(
        results == List(
          Left("Invalid arguments for tool 'annotated': missing required property 'p'"),
          Left("Invalid arguments for tool 'typed': missing required property 'x'"),
          Left("Missing required argument 'p'"),
          Left("Missing required argument 'x'")
        )
      )
    }
  }
