package downstream

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

import org.scalatest.funsuite.AsyncFunSuite
import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.core.wire.CallToolResult
import com.tjclp.fastmcp.macros.ResultEncoderResolution
import com.tjclp.fastmcp.server.router.WireMapping

/** No encoder of the user's: the library could derive one, the 1.0 rendering is kept. */
case class EncPlain(a: Int, b: String)

/** A `JsonEncoder` the user asked for. */
case class EncCoded(a: Int, b: String) derives JsonCodec

/** A hand-written `JsonEncoder` with its own shape. */
case class EncShaped(a: Int)

object EncShaped:
  given JsonEncoder[EncShaped] = JsonEncoder.int.contramap(_.a)

/** A case class with an `McpEncoder` in its companion. */
case class EncContent(a: Int)

object EncContent:

  given McpEncoder[EncContent] =
    McpEncoder.instance(value => List(TextContent(s"a=${value.a}"), TextContent("second")))

/** Nothing can encode it. */
final class EncOpaque(val id: Int)

/** Its only encoder is the one in scope where the annotations are scanned. */
final class EncScoped(val id: Int)

/** A case class that contains a type with a user encoder, and has none of its own. */
case class EncOuter(inner: EncCoded)

object EncodedResults:

  @Tool(name = Some("coded"))
  def coded(): EncCoded = EncCoded(1, "x")

  @Tool(name = Some("coded_list"))
  def codedList(): List[EncCoded] = List(EncCoded(1, "x"), EncCoded(2, "y"))

  @Tool(name = Some("coded_option"))
  def codedOption(present: Boolean): Option[EncCoded] =
    if present then Some(EncCoded(1, "x")) else None

  @Tool(name = Some("coded_map"))
  def codedMap(): Map[String, EncCoded] = Map("k" -> EncCoded(1, "x"))

  @Tool(name = Some("shaped_zio"))
  def shapedZio(): UIO[EncShaped] = ZIO.succeed(EncShaped(7))

  @Tool(name = Some("content"))
  def content(): EncContent = EncContent(3)

  @Tool(name = Some("content_either"))
  def contentEither(): Either[Throwable, EncContent] = Right(EncContent(4))

  @Tool(name = Some("content_try"))
  def contentTry(): Try[EncContent] = Try(EncContent(5))

  @Tool(name = Some("failing"))
  def failing(): Either[Throwable, EncContent] = Left(new IllegalStateException("no result"))

object ScopedResults:

  @Tool(name = Some("scoped"))
  def scoped(): EncScoped = new EncScoped(9)

/** A case class whose companion keeps its `McpEncoder` to itself. */
case class EncPrivate(a: Int)

object EncPrivate:

  private given McpEncoder[EncPrivate] =
    McpEncoder.instance(value => List(TextContent(s"private ${value.a}")))

  /** Inside the companion the instance is the one in scope. */
  def encoded(value: EncPrivate): List[Content] = summon[McpEncoder[EncPrivate]].encode(value)

object UnchangedResults:

  @Tool(name = Some("private_companion"))
  def privateCompanion(): EncPrivate = EncPrivate(1)

  @Tool(name = Some("plain_list"))
  def plainList(): List[EncPlain] = List(EncPlain(1, "x"))

  @Tool(name = Some("plain_option"))
  def plainOption(): Option[EncPlain] = Some(EncPlain(1, "x"))

  @Tool(name = Some("plain_map"))
  def plainMap(): Map[String, EncPlain] = Map("k" -> EncPlain(1, "x"))

  @Tool(name = Some("plain"))
  def plain(): EncPlain = EncPlain(1, "x")

  @Tool(name = Some("outer"))
  def outer(): EncOuter = EncOuter(EncCoded(1, "x"))

  @Tool(name = Some("unit"))
  def unit(): Unit = ()

  @Tool(name = Some("option"))
  def option(): Option[String] = Some("1")

  @Tool(name = Some("strings"))
  def strings(): List[String] = List("1", "b")

  @Tool(name = Some("tone"))
  def tone(): AdtTone = AdtTone.LOUD

  @Tool(name = Some("number"))
  def number(): Int = 42

  @Tool(name = Some("text"))
  def text(): String = "plain text"

  @Tool(name = Some("content"))
  def content(): Content = TextContent("content")

  @Tool(name = Some("contents"))
  def contents(): List[Content] = List(TextContent("one"), TextContent("two"))

  @Tool(name = Some("dynamic"))
  def dynamic(): Any = "decided at run time"

/** GH #119, row C2.9, on all three platforms, from a package outside `com.tjclp.fastmcp` with the
  * root import a user writes (the macro tells a user's encoder from the library's by the package
  * it is declared in): how the result of an annotated `@Tool` method reaches the wire.
  */
class AnnotatedResultEncodingSpec extends AsyncFunSuite:

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

  private def callAll(server: McpServer[Any], tools: List[String]): Task[Map[String, List[String]]] =
    ZIO
      .foreach(tools)(tool => wire(server, tool).map(result => tool -> texts(result)))
      .map(_.toMap)

  private def scan(register: McpServer[Any] => Any): McpServer[Any] =
    val server = McpServer("gh-119")
    val _ = register(server)
    server

  private def encoded: McpServer[Any] = scan(_.scanAnnotations[EncodedResults.type])

  test("a result with a user JsonEncoder is sent as JSON text, also inside List, Option and Map") {
    val server = encoded
    val program =
      for
        plain <- callAll(server, List("coded", "coded_list", "coded_map", "shaped_zio"))
        some <- call(server, "coded_option", """{"present":true}""")
        none <- call(server, "coded_option", """{"present":false}""")
      yield (plain, some, none)
    run(program).map { (plain, some, none) =>
      assert(plain("coded") == List("""{"a":1,"b":"x"}"""))
      assert(plain("coded_list") == List("""[{"a":1,"b":"x"},{"a":2,"b":"y"}]"""))
      assert(plain("coded_map") == List("""{"k":{"a":1,"b":"x"}}"""))
      assert(plain("shaped_zio") == List("7"))
      assert(some == Right(List("""{"a":1,"b":"x"}""")))
      assert(none == Right(List("null")))
    }
  }

  test("a result with a user McpEncoder in its companion is the content that encoder builds") {
    val server = encoded
    run(callAll(server, List("content", "content_either", "content_try"))).map { results =>
      assert(results("content") == List("a=3", "second"))
      assert(results("content_either") == List("a=4", "second"))
      assert(results("content_try") == List("a=5", "second"))
    }
  }

  test("an encoded result carries no structuredContent, and a failure stays a failure") {
    val server = encoded
    val program =
      for
        ok <- wire(server, "coded")
        failed <- call(server, "failing")
      yield (ok, failed)
    run(program).map { (ok, failed) =>
      assert(ok.structuredContent.isEmpty && ok.isError.contains(false))
      assert(failed == Left("no result"))
    }
  }

  test("an McpEncoder in scope where the annotations are scanned is used") {
    given McpEncoder[EncScoped] = McpEncoder.instance(value => List(TextContent(s"id ${value.id}")))
    val server = scan(_.scanAnnotations[ScopedResults.type])
    run(call(server, "scoped")).map(result => assert(result == Right(List("id 9"))))
  }

  // ---- C2.9: every other result is sent as in 1.0 ----

  test("results without a user-defined encoder keep the 1.0 rendering") {
    val server = scan(_.scanAnnotations[UnchangedResults.type])
    val tools = List(
      "plain",
      "outer",
      "unit",
      "option",
      "strings",
      "tone",
      "number",
      "text",
      "content",
      "contents",
      "dynamic",
      "private_companion",
      "plain_list",
      "plain_option",
      "plain_map"
    )
    run(callAll(server, tools)).map { results =>
      assert(results("plain") == List("EncPlain(1,x)"))
      assert(results("outer") == List("EncOuter(EncCoded(1,x))"))
      // `toString` of the unit value: "()" on the JVM and Scala Native, "undefined" on Scala.js.
      assert(results("unit") == List(().toString))
      assert(results("option") == List("Some(1)"))
      assert(results("strings") == List("List(1, b)"))
      assert(results("tone") == List("LOUD"))
      assert(results("number") == List("42"))
      assert(results("text") == List("plain text"))
      assert(results("content") == List("content"))
      assert(results("contents") == List("one", "two"))
      assert(results("dynamic") == List("decided at run time"))
      // A `private given` in the companion is not the macro's to use: the 1.0 rendering.
      assert(results("private_companion") == List("EncPrivate(1)"))
      assert(EncPrivate.encoded(EncPrivate(1)) == List(TextContent("private 1")))
      assert(results("plain_list") == List("List(EncPlain(1,x))"))
      assert(results("plain_option") == List("Some(EncPlain(1,x))"))
      assert(results("plain_map") == List("Map(k -> EncPlain(1,x))"))
    }
  }

  test("the decision per result type: encode, the 1.0 rendering, or the no-encoder warning") {
    Future.successful {
      assert(ResultEncoderResolution.describe[EncCoded] == "encode")
      assert(ResultEncoderResolution.describe[List[EncCoded]] == "encode")
      assert(ResultEncoderResolution.describe[Vector[EncCoded]] == "encode")
      assert(ResultEncoderResolution.describe[Seq[EncCoded]] == "encode")
      assert(ResultEncoderResolution.describe[Set[EncCoded]] == "encode")
      assert(ResultEncoderResolution.describe[Map[String, EncCoded]] == "encode")
      assert(ResultEncoderResolution.describe[Option[EncShaped]] == "encode")
      assert(ResultEncoderResolution.describe[EncContent] == "encode")

      assert(ResultEncoderResolution.describe[EncPlain] == "runtime")
      assert(ResultEncoderResolution.describe[EncOuter] == "runtime")
      assert(ResultEncoderResolution.describe[Unit] == "runtime")
      assert(ResultEncoderResolution.describe[Option[String]] == "runtime")
      assert(ResultEncoderResolution.describe[List[Int]] == "runtime")
      assert(ResultEncoderResolution.describe[AdtTone] == "runtime")
      assert(ResultEncoderResolution.describe[String] == "runtime")
      assert(ResultEncoderResolution.describe[TextContent] == "runtime")
      assert(ResultEncoderResolution.describe[List[Content]] == "runtime")
      assert(ResultEncoderResolution.describe[Array[Byte]] == "runtime")
      assert(ResultEncoderResolution.describe[Any] == "runtime")
      assert(ResultEncoderResolution.describe[Nothing] == "runtime")

      // Warned about when the method is expanded: nothing can encode these.
      assert(ResultEncoderResolution.describe[EncOpaque] == "no-encoder")
      assert(ResultEncoderResolution.describe[EncScoped] == "no-encoder")
      assert(ResultEncoderResolution.describe[AdtShape] == "no-encoder")
      assert(ResultEncoderResolution.describe[List[EncOpaque]] == "no-encoder")
      assert(ResultEncoderResolution.describe[Map[String, Option[EncOpaque]]] == "no-encoder")
      assert(ResultEncoderResolution.describe[Either[String, EncOpaque]] == "no-encoder")

      // A container of a type the library can encode is sent, and treated, like that type: the
      // 1.0 rendering, no warning (a build with -Werror keeps compiling).
      assert(ResultEncoderResolution.describe[List[EncPlain]] == "runtime")
      assert(ResultEncoderResolution.describe[Vector[EncPlain]] == "runtime")
      assert(ResultEncoderResolution.describe[Option[EncPlain]] == "runtime")
      assert(ResultEncoderResolution.describe[Map[String, EncPlain]] == "runtime")
      assert(ResultEncoderResolution.describe[Either[String, List[EncPlain]]] == "runtime")
      assert(ResultEncoderResolution.describe[(Int, EncPlain)] == "runtime")
      assert(ResultEncoderResolution.describe[EncPrivate] == "runtime")
    }
  }
