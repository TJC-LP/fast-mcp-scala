package com.tjclp.fastmcp
package codec

import scala.reflect.ClassTag

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.{McpDecodeContext, McpDecoder, ResourceArgument}
import com.tjclp.fastmcp.jsonrpc.McpError
import com.tjclp.fastmcp.macros.MapToFunctionMacro
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.router.Session
import com.tjclp.fastmcp.server.transport.JvmTransportBackend.given
import com.tjclp.fastmcp.server.transport.MessageLoop

/** The argument decode path after TJC-2295 / F3: wire `Json` nodes decode through zio-json's
  * guarded `fromJsonAST` (no unguarded recursive re-encode), `writeValueAsString` converts a
  * StackOverflowError into an IllegalArgumentException, and JSON embedded in string arguments is
  * depth/width-bounded before any recursive walk. Every deep input here is built programmatically
  * so the frame-level limits are bypassed — this is the second line of defence under test.
  */
class DecodePathTest extends AnyFunSuite with Matchers:

  import McpDecoders.given

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  case class Add(a: Int, b: Int) derives JsonDecoder
  case class Profile(name: String, age: Int) derives JsonDecoder
  case class User(id: String, profile: Profile) derives JsonDecoder
  case class NoArgs()
  case class Wrapped(x: Json) derives JsonDecoder

  private val ctx = DefaultDecodeContext.default

  private def deepArray(depth: Int): Json =
    (1 to depth).foldLeft(Json.Null: Json)((acc, _) => Json.Arr(acc))

  // ---- fromJsonAST path ----

  test("McpDecoder decodes a Map of wire Json nodes straight from the AST") {
    McpDecoder[Add].decode("add", Map("a" -> Json.Num(2), "b" -> Json.Num(3)), ctx) shouldBe Add(2, 3)
    McpDecoder[User].decode(
      "user",
      Map("id" -> Json.Str("u1"), "profile" -> Json.Obj("name" -> Json.Str("Ada"), "age" -> Json.Num(36))),
      ctx
    ) shouldBe User("u1", Profile("Ada", 36))
    // Mirror-derived fallback (no explicit JsonDecoder) — the inline given.
    McpDecoder[NoArgs].decode("t", Map.empty[String, Any], ctx) shouldBe NoArgs()
    // Raw Scala values still work (toJsonAst coerces them).
    McpDecoder[Add].decode("add", Map("a" -> 2, "b" -> 3), ctx) shouldBe Add(2, 3)
  }

  test("macro-generated per-parameter decoders handle Option/List/Map from Json-valued maps") {
    def f(a: Int, b: Option[String], c: List[Int], d: Map[String, Int]): String =
      s"$a|${b.getOrElse("-")}|${c.sum}|${d.values.sum}"
    val fn = MapToFunctionMacro.callByMap(f)
    fn(
      Map(
        "a" -> Json.Num(1),
        "b" -> Json.Str("x"),
        "c" -> Json.Arr(Json.Num(1), Json.Num(2)),
        "d" -> Json.Obj("k" -> Json.Num(3), "l" -> Json.Num(4))
      )
    ) shouldBe "1|x|3|7"
    // Absent Option → None; JSON null → None.
    fn(Map("a" -> Json.Num(1), "c" -> Json.Arr(), "d" -> Json.Obj())) shouldBe "1|-|0|0"
    fn(Map("a" -> Json.Num(1), "b" -> Json.Null, "c" -> Json.Arr(), "d" -> Json.Obj())) shouldBe "1|-|0|0"
  }

  test("bad input keeps the documented error prefix and a truncated value preview") {
    val ex = intercept[RuntimeException](
      McpDecoder[Add].decode("add", Map("a" -> "not-a-number", "b" -> 3), ctx)
    )
    ex.getMessage should include("Failed to decode parameter 'add' from JSON:")
    ex.getMessage should include("Value:")

    val wide = Json.Obj("a" -> Json.Str("x" * 5000), "b" -> Json.Num(1))
    val long = intercept[RuntimeException](McpDecoder[Add].decode("add", wide, ctx))
    long.getMessage.length should be < 1200
    long.getMessage should include("…")
  }

  test("a custom McpDecodeContext keeps the legacy writeValueAsString + decodeJson path") {
    var calls = 0
    val spy = new McpDecodeContext:
      def convertValue[T: ClassTag](name: String, rawValue: Any): T = ctx.convertValue(name, rawValue)
      def parseJsonArray(name: String, rawJson: String): List[Any] = ctx.parseJsonArray(name, rawJson)
      def parseJsonObject(name: String, rawJson: String): Map[String, Any] =
        ctx.parseJsonObject(name, rawJson)
      def writeValueAsString(value: Any): String =
        calls += 1
        ctx.writeValueAsString(value)
    McpDecoder[Add].decode("add", Map("a" -> 1, "b" -> 2), spy) shouldBe Add(1, 2)
    calls shouldBe 1
  }

  // ---- StackOverflowError never escapes ----

  test("writeValueAsString on a 200 000-deep value throws IllegalArgumentException, not an Error") {
    val deep = deepArray(200_000)
    val ex = intercept[IllegalArgumentException](ctx.writeValueAsString(deep))
    ex.getMessage should include("nested too deeply")
  }

  test("decoding a deep raw value through McpDecoder never lets a java.lang.Error out") {
    val deep = deepArray(200_000)
    // NoArgs ignores the extra field: no recursion into the value at all.
    McpDecoder[NoArgs].decode("t", Map("x" -> deep), ctx) shouldBe NoArgs()
    // Wrapped[x: Json] would re-encode/decode the value on the legacy path; now guarded either way.
    val outcome =
      try Right(McpDecoder[Wrapped].decode("t", Map("x" -> deep), ctx))
      catch case e: RuntimeException => Left(e)
    outcome.isRight || outcome.isLeft shouldBe true // no java.lang.Error reached this line
  }

  // ---- embedded JSON strings ----

  test("parseJsonArray / parseJsonObject bound the depth and width of embedded JSON") {
    val deep300 = "[" * 300 + "]" * 300
    val ex = intercept[IllegalArgumentException](ctx.parseJsonArray("blob", deep300))
    ex.getMessage should include("maxDepth")

    // Far past what the parser itself can take: the linear pre-scan rejects the text before
    // zio-json's recursive parser ever runs (so this is safe on Scala Native too, where a parser
    // stack overflow would not be catchable).
    val deep50k = "[" * 50_000 + "]" * 50_000
    val ex2 = intercept[IllegalArgumentException](ctx.parseJsonArray("blob", deep50k))
    ex2.getMessage should include("maxDepth")

    val wide = (1 to 1100).map(i => s""""k$i":$i""").mkString("{", ",", "}")
    val ex3 = intercept[IllegalArgumentException](ctx.parseJsonObject("blob", wide))
    ex3.getMessage should include("maxObjectFields")

    val four = new DefaultDecodeContext(maxDepth = 4)
    four.parseJsonArray("blob", "[[[[1]]]]") shouldBe List(List(List(List(1))))
    val ex4 = intercept[IllegalArgumentException](four.parseJsonArray("blob", "[[[[[1]]]]]"))
    ex4.getMessage should include("maxDepth (4)")
    // A custom decoder reaching the bypass through the public context API gets the same guard.
    val custom = new McpDecoder[List[Any]]:
      def decode(name: String, rawValue: Any, context: McpDecodeContext): List[Any] =
        context.parseJsonArray(name, rawValue.toString)
    val ex5 = intercept[IllegalArgumentException](custom.decode("blob", deep300, ctx))
    ex5.getMessage should include("maxDepth")
    McpError.fromThrowable(new IllegalArgumentException("x")).code shouldBe -32602
  }

  // ---- end to end ----

  test("tools/call with 60-deep arguments gets a JSON-RPC reply and the JVM stays up") {
    case class Args(x: Json) derives JsonDecoder
    val server = McpServer("Deep", "0.1.0")
    runUnsafe(
      server.tool(McpTool[Args, String](name = "wrap", description = Some("wraps"))(_ => "ok")) *>
        server.tool(McpTool[NoArgs, String](name = "noop", description = Some("no args"))(_ => "ok")) *>
        server.resource(
          McpTemplateResource[Args](
            uriPattern = "d://{x}",
            arguments = List(ResourceArgument("x", None, required = true))
          )(_ => "r")
        )
    )
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("deep"))
    val init =
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""
    runUnsafe(MessageLoop.handleFrame(router, session, init)).getOrElse("") should include("serverInfo")

    val deep = "[" * 60 + "1" + "]" * 60
    List("wrap", "noop").zipWithIndex.foreach { (tool, i) =>
      val frame =
        s"""{"jsonrpc":"2.0","id":${10 + i},"method":"tools/call","params":{"name":"$tool","arguments":{"x":$deep}}}"""
      val reply = runUnsafe(MessageLoop.handleFrame(router, session, frame)).getOrElse(fail("no reply"))
      reply should include(s""""id":${10 + i}""")
      (reply.contains("\"result\"") || reply.contains("-32602") || reply.contains("-32603")) shouldBe true
    }
  }
