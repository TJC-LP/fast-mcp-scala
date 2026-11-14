package downstream

import scala.concurrent.{ExecutionContext, Future}

import org.scalatest.funsuite.AsyncFunSuite
import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.core.wire.CallToolResult
import com.tjclp.fastmcp.server.router.WireMapping

enum AdtTone:
  case LOUD, QUIET

sealed trait AdtShape
case class AdtCircle(radius: Double) extends AdtShape
// Fractional values throughout: a whole Double prints as "2" on Scala.js and "2.0" elsewhere.
case class AdtRect(w: Double, h: Double = 0.5) extends AdtShape
case object AdtOrigin extends AdtShape

/** Case objects only: zio-json reads and writes the case name. */
sealed trait AdtMode
case object AdtFast extends AdtMode
case object AdtSlow extends AdtMode

/** A sealed trait inside another one: zio-json sees the leaves only. */
sealed trait AdtNode
sealed trait AdtLeaf extends AdtNode
case class AdtText(value: String) extends AdtLeaf
case object AdtBreak extends AdtLeaf
case class AdtStyled(tone: AdtTone, shape: AdtShape, point: Option[AdtPoint]) extends AdtNode

case class AdtPoint(x: Int, y: Int)

sealed abstract class AdtAction
final case class AdtMove(to: AdtPoint) extends AdtAction
case object AdtStop extends AdtAction

case class AdtArgs(
    shape: AdtShape,
    mode: AdtMode,
    nodes: List[AdtNode],
    action: Option[AdtAction],
    named: Map[String, AdtShape] = Map.empty
)

case class AdtOut(shape: AdtShape, mode: AdtMode, node: AdtNode)

/** zio-json's annotations for the wire shape of a hierarchy. */
@jsonDiscriminator("type") sealed trait AdtEvent
case class AdtOpened(by: String, at: Option[Int]) extends AdtEvent
@jsonHint("closed") case class AdtClosed(code: Int = 0) extends AdtEvent
case object AdtReset extends AdtEvent

sealed trait AdtLevel
@jsonHint("low") case object AdtLow extends AdtLevel
case object AdtHigh extends AdtLevel

sealed trait AdtWrapped
@jsonHint("pair") case class AdtPair(left: Int, right: Int) extends AdtWrapped
case object AdtNothing extends AdtWrapped

case class AdtAnnotatedArgs(event: AdtEvent, level: AdtLevel, wrapped: AdtWrapped)

object AdtAnnotated:

  @Tool(name = Some("area"))
  def area(shape: AdtShape): String = shape.toString

  @Tool(name = Some("mode"))
  def mode(mode: AdtMode, fallback: Option[AdtMode]): String = s"$mode/$fallback"

  @Tool(name = Some("nodes"))
  def nodes(nodes: List[AdtNode], action: AdtAction): String = s"$nodes/$action"

/** GH #119, row C2.24, on all three platforms, from a package outside `com.tjclp.fastmcp` with the
  * root import a user writes: sealed-trait hierarchies as arguments (schema and decoding) and as
  * fields of a result.
  */
class SealedHierarchySpec extends AsyncFunSuite:

  // See StdioLoggingSpec: ScalaTest's serial context does not run ZIO's completions on Scala.js.
  override implicit val executionContext: ExecutionContext = ExecutionContext.global

  private def run[A](effect: Task[A]): Future[A] =
    Unsafe.unsafe(implicit unsafe => Runtime.default.unsafe.runToFuture(effect))

  private def json(text: String): Json =
    text.fromJson[Json].fold(error => fail(s"bad test JSON <$text>: $error"), identity)

  private def arguments(text: String): Map[String, Any] =
    json(text).asObject.map(_.fields.toMap).getOrElse(fail(s"not an object: $text"))

  /** One call through the tool manager, rendered as the `tools/call` result a client receives. */
  private def wire(server: McpServer[Any], tool: String, args: String = "{}"): Task[CallToolResult] =
    val outputSchema = server.toolManager.getToolDefinition(tool).flatMap(_.outputSchema)
    server.toolManager
      .callTool(tool, arguments(args), None)
      .map(result => WireMapping.toolResultToWire(result, outputSchema))

  private def texts(result: CallToolResult): List[String] =
    result.content.collect { case text: TextContent => text.text }

  /** The texts of a successful call, or the message of a failed one. */
  private def call(server: McpServer[Any], tool: String, args: String = "{}"): Task[Either[String, List[String]]] =
    wire(server, tool, args).either.map {
      case Right(result) => Right(texts(result))
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

  private def scan(register: McpServer[Any] => Any): McpServer[Any] =
    val server = McpServer("gh-119")
    val _ = register(server)
    server

  private val circleOrRect = json(
    """{"oneOf":[
      |{"type":"object","properties":{"AdtCircle":{"type":"object","properties":{"radius":{"type":"number"}},"required":["radius"]}},"required":["AdtCircle"]},
      |{"type":"object","properties":{"AdtRect":{"type":"object","properties":{"w":{"type":"number"},"h":{"type":"number"}},"required":["w"]}},"required":["AdtRect"]},
      |{"type":"object","properties":{"AdtOrigin":{"type":"object","properties":{},"additionalProperties":false}},"required":["AdtOrigin"]}
      |]}""".stripMargin
  )

  private val modeEnum = json("""{"type":"string","enum":["AdtFast","AdtSlow"]}""")

  private def adtAnnotated: McpServer[Any] = scan(_.scanAnnotations[AdtAnnotated.type])

  test("@Tool: a sealed-trait parameter advertises a oneOf of wrapper objects and decodes them") {
    val server = adtAnnotated
    val program =
      for
        circle <- call(server, "area", """{"shape":{"AdtCircle":{"radius":2.5}}}""")
        rect <- call(server, "area", """{"shape":{"AdtRect":{"w":1.5}}}""")
        origin <- call(server, "area", """{"shape":{"AdtOrigin":{}}}""")
        unknown <- call(server, "area", """{"shape":{"AdtSquare":{}}}""")
        bare <- call(server, "area", """{"shape":{"radius":2.5}}""")
      yield (circle, rect, origin, unknown, bare)
    run(program).map { (circle, rect, origin, unknown, bare) =>
      assert(at(inputSchema(server, "area"), "properties", "shape") == circleOrRect)
      assert(circle == Right(List("AdtCircle(2.5)")))
      // The Scala default of a case's field is applied, and the schema does not require it.
      assert(rect == Right(List("AdtRect(1.5,0.5)")))
      assert(origin == Right(List("AdtOrigin")))
      val refused =
        "Invalid arguments for tool 'area': 'shape' does not match any of the 3 allowed alternatives"
      assert(unknown == Left(refused))
      assert(bare == Left(refused))
    }
  }

  test("@Tool: a hierarchy of case objects is a string enumeration of the case names") {
    val server = adtAnnotated
    val program =
      for
        both <- call(server, "mode", """{"mode":"AdtFast","fallback":"AdtSlow"}""")
        one <- call(server, "mode", """{"mode":"AdtSlow"}""")
        wrapper <- call(server, "mode", """{"mode":{"AdtFast":{}}}""")
        unknown <- call(server, "mode", """{"mode":"AdtWarp"}""")
      yield (both, one, wrapper, unknown)
    run(program).map { (both, one, wrapper, unknown) =>
      val schema = inputSchema(server, "mode")
      assert(at(schema, "properties", "mode") == modeEnum)
      assert(
        at(schema, "properties", "fallback") ==
          Json.Obj("anyOf" -> Json.Arr(modeEnum, Json.Obj("type" -> Json.Str("null"))))
      )
      assert(both == Right(List("AdtFast/Some(AdtSlow)")))
      assert(one == Right(List("AdtSlow/None")))
      assert(wrapper == Left("Invalid arguments for tool 'mode': 'mode' must be string, got an object"))
      assert(unknown == Left("Invalid arguments for tool 'mode': 'mode' must be one of: AdtFast, AdtSlow"))
    }
  }

  test("@Tool: a nested sealed trait contributes its cases, and a case's fields derive in turn") {
    val server = adtAnnotated
    val styled =
      """{"AdtStyled":{"tone":"LOUD","shape":{"AdtCircle":{"radius":1.5}},"point":{"x":1,"y":2}}}"""
    val program =
      for
        ok <- call(
          server,
          "nodes",
          s"""{"nodes":[{"AdtText":{"value":"a"}},{"AdtBreak":{}},$styled],"action":{"AdtMove":{"to":{"x":3,"y":4}}}}"""
        )
        stop <- call(server, "nodes", """{"nodes":[],"action":{"AdtStop":{}}}""")
        nested <- call(
          server,
          "nodes",
          """{"nodes":[{"AdtLeaf":{"AdtText":{"value":"a"}}}],"action":{"AdtStop":{}}}"""
        )
      yield (ok, stop, nested)
    run(program).map { (ok, stop, nested) =>
      val cases = at(inputSchema(server, "nodes"), "properties", "nodes", "items", "oneOf").asArray
        .map(_.toList.flatMap(_.asObject.toList.flatMap(_.get("required").toList)))
        .getOrElse(Nil)
        .flatMap(_.asArray.toList.flatMap(_.toList.flatMap(_.asString)))
      assert(cases == List("AdtText", "AdtBreak", "AdtStyled"))
      assert(
        ok == Right(
          List(
            "List(AdtText(a), AdtBreak, AdtStyled(LOUD,AdtCircle(1.5),Some(AdtPoint(1,2))))/" +
              "AdtMove(AdtPoint(3,4))"
          )
        )
      )
      assert(stop == Right(List("List()/AdtStop")))
      assert(nested.isLeft, s"the nested trait is not a level of its own: $nested")
    }
  }

  test("McpTool: an In with sealed-trait fields derives its schema and its decoder") {
    val server = McpServer("adt-typed")
    val tool = McpTool[AdtArgs, String](name = "draw") { args =>
      s"${args.shape}|${args.mode}|${args.nodes}|${args.action}|${args.named.toList.sortBy(_._1)}"
    }
    val program =
      for
        _ <- server.tool(tool)
        full <- call(
          server,
          "draw",
          """{"shape":{"AdtRect":{"w":1.5,"h":2.5}},"mode":"AdtFast","nodes":[{"AdtBreak":{}}],
            |"action":{"AdtStop":{}},"named":{"o":{"AdtOrigin":{}}}}""".stripMargin
        )
        minimal <- call(
          server,
          "draw",
          """{"shape":{"AdtOrigin":{}},"mode":"AdtSlow","nodes":[]}"""
        )
        wrong <- call(
          server,
          "draw",
          """{"shape":{"AdtOrigin":{}},"mode":"AdtWarp","nodes":[]}"""
        )
      yield (full, minimal, wrong)
    run(program).map { (full, minimal, wrong) =>
      val schema = inputSchema(server, "draw")
      assert(at(schema, "properties", "shape") == circleOrRect)
      assert(at(schema, "properties", "mode") == modeEnum)
      assert(at(schema, "properties", "named", "additionalProperties") == circleOrRect)
      assert(at(schema, "required") == json("""["shape","mode","nodes"]"""))
      assert(
        full == Right(
          List("AdtRect(1.5,2.5)|AdtFast|List(AdtBreak)|Some(AdtStop)|List((o,AdtOrigin))")
        )
      )
      assert(minimal == Right(List("AdtOrigin|AdtSlow|List()|None|List()")))
      assert(wrong == Left("Invalid arguments for tool 'draw': 'mode' must be one of: AdtFast, AdtSlow"))
    }
  }

  test("McpTool: @jsonDiscriminator and @jsonHint decide the advertised shape, as they decide the codec's") {
    val server = McpServer("adt-annotated")
    val tool = McpTool[AdtAnnotatedArgs, String](name = "log") { args =>
      s"${args.event}|${args.level}|${args.wrapped}"
    }
    def args(event: String, level: String = "\"low\"", wrapped: String = """{"AdtNothing":{}}""") =
      s"""{"event":$event,"level":$level,"wrapped":$wrapped}"""
    val program =
      for
        _ <- server.tool(tool)
        opened <- call(server, "log", args("""{"type":"AdtOpened","by":"me"}"""))
        closed <- call(server, "log", args("""{"type":"closed"}""", "\"AdtHigh\"", """{"pair":{"left":1,"right":2}}"""))
        reset <- call(server, "log", args("""{"type":"AdtReset"}"""))
        wrapper <- call(server, "log", args("""{"AdtOpened":{"by":"me"}}"""))
        unhinted <- call(server, "log", args("""{"type":"AdtClosed"}"""))
        level <- call(server, "log", args("""{"type":"AdtReset"}""", "\"AdtLow\""))
      yield (opened, closed, reset, wrapper, unhinted, level)
    run(program).map { (opened, closed, reset, wrapper, unhinted, level) =>
      val schema = inputSchema(server, "log")
      assert(
        at(schema, "properties", "event") == json(
          """{"oneOf":[
            |{"type":"object","properties":{"type":{"const":"AdtOpened"},"by":{"type":"string"},"at":{"anyOf":[{"type":"integer"},{"type":"null"}]}},"required":["type","by"]},
            |{"type":"object","properties":{"type":{"const":"closed"},"code":{"type":"integer"}},"required":["type"]},
            |{"type":"object","properties":{"type":{"const":"AdtReset"}},"required":["type"]}
            |]}""".stripMargin
        )
      )
      assert(at(schema, "properties", "level") == json("""{"type":"string","enum":["low","AdtHigh"]}"""))
      assert(
        at(schema, "properties", "wrapped", "oneOf").asArray
          .map(_.toList.flatMap(_.asObject.toList.flatMap(_.get("required").toList)))
          .contains(List(json("""["pair"]"""), json("""["AdtNothing"]""")))
      )
      assert(opened == Right(List("AdtOpened(me,None)|AdtLow|AdtNothing")))
      assert(closed == Right(List("AdtClosed(0)|AdtHigh|AdtPair(1,2)")))
      assert(reset == Right(List("AdtReset|AdtLow|AdtNothing")))
      val refused =
        "Invalid arguments for tool 'log': 'event' does not match any of the 3 allowed alternatives"
      assert(wrapper == Left(refused))
      assert(unhinted == Left(refused))
      assert(level == Left("Invalid arguments for tool 'log': 'level' must be one of: low, AdtHigh"))
    }
  }

  test("McpTool: a result with sealed-trait fields is written in the shape its schema describes") {
    val server = McpServer("adt-out")
    val tool = McpTool[AdtPoint, AdtOut](name = "describe") { point =>
      AdtOut(AdtCircle(point.x + 0.5), AdtSlow, AdtText(point.y.toString))
    }.withOutputSchema
    val program = server.tool(tool) *> wire(server, "describe", """{"x":1,"y":2}""")
    run(program).map { result =>
      val expected =
        json("""{"shape":{"AdtCircle":{"radius":1.5}},"mode":"AdtSlow","node":{"AdtText":{"value":"2"}}}""")
      assert(result.structuredContent.contains(expected))
      assert(texts(result).map(json) == List(expected))
      val outputSchema = server.toolManager
        .getToolDefinition("describe")
        .flatMap(_.outputSchema)
        .map(schema => schema.toJsonAST.getOrElse(Json.Null))
        .getOrElse(fail("no outputSchema"))
      assert(at(outputSchema, "properties", "shape", "oneOf").asArray.map(_.size).contains(3))
      assert(at(outputSchema, "properties", "mode") == modeEnum)
    }
  }
