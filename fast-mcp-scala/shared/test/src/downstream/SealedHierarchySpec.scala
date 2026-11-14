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

/** Review of PR #158: case names through `@jsonHintNames`, and constants as annotation arguments. */
@jsonHintNames(SnakeCase) sealed trait AdtSignal
case class AdtTurnLeft(degrees: Int) extends AdtSignal
@jsonHint("halt") case object AdtFullStop extends AdtSignal

@jsonHintNames(KebabCase) sealed trait AdtGear
case object AdtLowGear extends AdtGear
case object AdtHighGear extends AdtGear

object AdtKeys:
  final val Kind = "kind"
  final val Go = "go"

@jsonDiscriminator(AdtKeys.Kind) sealed trait AdtCommand
@jsonHint(name = AdtKeys.Go) case class AdtGo(speed: Int) extends AdtCommand
case object AdtWait extends AdtCommand

case class AdtNamedArgs(signal: AdtSignal, gear: AdtGear, command: AdtCommand)

/** A type no codec can be derived for. */
final class AdtHandle(val id: Int)

/** A hierarchy with hand-written codecs, one of whose cases has a field without any codec. */
sealed trait AdtOwned
case class AdtHeld(handle: AdtHandle) extends AdtOwned
case object AdtFree extends AdtOwned

object AdtOwned:

  given JsonDecoder[AdtOwned] =
    JsonDecoder.int.map(id => if id == 0 then AdtFree else AdtHeld(new AdtHandle(id)))

  given JsonEncoder[AdtOwned] = JsonEncoder.int.contramap {
    case AdtFree => 0
    case AdtHeld(handle) => handle.id
  }
  given McpSchema[AdtOwned] = McpSchema.instance("""{"type":"integer"}""")

/** The same gap one level down in a case class: `AdtCrate` has codecs, `AdtSlot` cannot have one. */
case class AdtSlot(handle: AdtHandle)
case class AdtCrate(slot: AdtSlot)

object AdtCrate:
  given JsonDecoder[AdtCrate] = JsonDecoder.int.map(id => AdtCrate(AdtSlot(new AdtHandle(id))))
  given JsonEncoder[AdtCrate] = JsonEncoder.int.contramap(_.slot.handle.id)
  given McpSchema[AdtCrate] = McpSchema.instance("""{"type":"integer"}""")

case class AdtOwnedArgs(owned: AdtOwned, more: List[AdtOwned], crate: Option[AdtCrate])
case class AdtOwnedOut(owned: AdtOwned, crate: AdtCrate)

object AdtOwnedAnnotated:

  @Tool(name = Some("owned"))
  def owned(owned: AdtOwned, crates: List[AdtCrate]): String =
    owned match
      case AdtFree => s"free/${crates.map(_.slot.handle.id)}"
      case AdtHeld(handle) => s"held ${handle.id}/${crates.map(_.slot.handle.id)}"

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

  // ---- review of PR #158 ----

  test("a hierarchy or case class with hand-written codecs is not looked into (uncodable fields inside)") {
    val server = scan(_.scanAnnotations[AdtOwnedAnnotated.type])
    val tool = McpTool[AdtOwnedArgs, AdtOwnedOut](name = "typed_owned") { args =>
      AdtOwnedOut(args.more.lastOption.getOrElse(args.owned), args.crate.getOrElse(AdtCrate(AdtSlot(new AdtHandle(-1)))))
    }
    val program =
      for
        _ <- server.tool(tool)
        annotated <- call(server, "owned", """{"owned":7,"crates":[1,2]}""")
        free <- call(server, "owned", """{"owned":0,"crates":[]}""")
        typed <- call(server, "typed_owned", """{"owned":0,"more":[3,4],"crate":9}""")
        refused <- call(server, "typed_owned", """{"owned":{"AdtFree":{}},"more":[]}""")
      yield (annotated, free, typed, refused)
    run(program).map { (annotated, free, typed, refused) =>
      assert(at(inputSchema(server, "owned"), "properties", "owned") == json("""{"type":"integer"}"""))
      // The user's decoder read the arguments, the user's encoder wrote the result.
      assert(annotated == Right(List("held 7/List(1, 2)")))
      assert(free == Right(List("free/List()")))
      assert(typed == Right(List("""{"owned":4,"crate":9}""")))
      assert(refused == Left("Invalid arguments for tool 'typed_owned': 'owned' must be integer, got an object"))
    }
  }

  test("@jsonHintNames names the cases in the schema as it does in the codec; constants are read") {
    val server = McpServer("adt-named")
    val tool = McpTool[AdtNamedArgs, String](name = "drive") { args =>
      s"${args.signal}|${args.gear}|${args.command}"
    }
    def args(signal: String, gear: String, command: String) =
      s"""{"signal":$signal,"gear":$gear,"command":$command}"""
    val program =
      for
        _ <- server.tool(tool)
        ok <- call(
          server,
          "drive",
          args("""{"adt_turn_left":{"degrees":3}}""", "\"adt-low-gear\"", """{"kind":"go","speed":5}""")
        )
        hinted <- call(server, "drive", args("""{"halt":{}}""", "\"adt-high-gear\"", """{"kind":"AdtWait"}"""))
        className <- call(
          server,
          "drive",
          args("""{"AdtTurnLeft":{"degrees":3}}""", "\"adt-low-gear\"", """{"kind":"AdtWait"}""")
        )
        gearName <- call(server, "drive", args("""{"halt":{}}""", "\"AdtLowGear\"", """{"kind":"AdtWait"}"""))
      yield (ok, hinted, className, gearName)
    run(program).map { (ok, hinted, className, gearName) =>
      val schema = inputSchema(server, "drive")
      assert(
        at(schema, "properties", "gear") == json("""{"type":"string","enum":["adt-low-gear","adt-high-gear"]}""")
      )
      assert(
        at(schema, "properties", "command") == json(
          """{"oneOf":[
            |{"type":"object","properties":{"kind":{"const":"go"},"speed":{"type":"integer"}},"required":["kind","speed"]},
            |{"type":"object","properties":{"kind":{"const":"AdtWait"}},"required":["kind"]}
            |]}""".stripMargin
        )
      )
      assert(ok == Right(List("AdtTurnLeft(3)|AdtLowGear|AdtGo(5)")))
      assert(hinted == Right(List("AdtFullStop|AdtHighGear|AdtWait")))
      assert(
        className == Left(
          "Invalid arguments for tool 'drive': 'signal' does not match any of the 2 allowed alternatives"
        )
      )
      assert(
        gearName == Left(
          "Invalid arguments for tool 'drive': 'gear' must be one of: adt-low-gear, adt-high-gear"
        )
      )
    }
  }

  test("a given JsonCodecConfiguration does not reach the derived decoder, and the input schema says so") {
    given JsonCodecConfiguration = JsonCodecConfiguration(
      sumTypeHandling = JsonCodecConfiguration.SumTypeHandling.DiscriminatorField("kind"),
      sumTypeMapping = SnakeCase,
      enumValuesAsStrings = false
    )
    val server = McpServer("adt-configured")
    val tool = McpTool[AdtArgs, String](name = "draw")(args => s"${args.shape}|${args.mode}")
    val program =
      for
        _ <- server.tool(tool)
        defaults <- call(
          server,
          "draw",
          """{"shape":{"AdtCircle":{"radius":2.5}},"mode":"AdtFast","nodes":[]}"""
        )
        configured <- call(
          server,
          "draw",
          """{"shape":{"kind":"adt_circle","radius":2.5},"mode":{"kind":"adt_fast"},"nodes":[]}"""
        )
      yield (defaults, configured)
    run(program).map { (defaults, configured) =>
      val schema = inputSchema(server, "draw")
      assert(at(schema, "properties", "shape") == circleOrRect)
      assert(at(schema, "properties", "mode") == modeEnum)
      assert(defaults == Right(List("AdtCircle(2.5)|AdtFast")))
      assert(configured.isLeft, s"the configured shape is neither advertised nor decoded: $configured")
    }
  }

  test("a given JsonCodecConfiguration does reach the derived encoder (why its outputSchema is refused)") {
    given JsonCodecConfiguration = JsonCodecConfiguration(
      sumTypeHandling = JsonCodecConfiguration.SumTypeHandling.DiscriminatorField("kind")
    )
    val server = McpServer("adt-configured-out")
    val tool = McpTool[AdtPoint, AdtOut](name = "describe") { point =>
      AdtOut(AdtCircle(point.x + 0.5), AdtSlow, AdtText(point.y.toString))
    }
    val program = server.tool(tool) *> call(server, "describe", """{"x":1,"y":2}""")
    run(program).map { result =>
      val expected = json(
        """{"shape":{"kind":"AdtCircle","radius":1.5},"mode":{"kind":"AdtSlow"},"node":{"kind":"AdtText","value":"2"}}"""
      )
      assert(result.map(_.map(json)) == Right(List(expected)))
    }
  }
