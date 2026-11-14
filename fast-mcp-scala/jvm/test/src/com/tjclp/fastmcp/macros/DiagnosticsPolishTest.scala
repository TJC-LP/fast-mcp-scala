package com.tjclp.fastmcp
package macros

import scala.compiletime.testing.{typeCheckErrors, Error}

import org.scalatest.funsuite.AnyFunSuite
import zio.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.MacroDxHarness.{assertSomeMessageContains, messages, runUnsafe}
import com.tjclp.fastmcp.macros.RegistrationMacro.scanAnnotations
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.transport.JvmTransportBackend.given

object MissingArgTools:
  @Tool(name = Some("annotated")) def annotated(p: String, q: Int = 1): String = s"$p:$q"

case class MissingArgs(p: String, q: Int = 1)
case class NestedArgs(inner: MissingArgs)
case class NumberArgs(n: BigDecimal)

object EnvOnAny:
  @Tool def needsRef(a: Int): ZIO[Ref[Int], Throwable, Int] = ZIO.succeed(a)

object EnvPrompt:
  @Prompt def needsRef(a: String): ZIO[Ref[Int], Throwable, String] = ZIO.succeed(a)

object EnvResource:
  @Resource("env://x") def needsRef(): ZIO[Ref[Int], Throwable, String] = ZIO.succeed("")

object NonLiteralUri:
  val dynamic: String = "dyn://x"
  @Resource(uri = NonLiteralUri.dynamic) def r(): String = ""

object NonLiteralUriPositional:
  @Resource(NonLiteralUri.dynamic) def r(): String = ""

object ConstantUri:
  final val Uri = "const://x"
  @Resource(uri = ConstantUri.Uri) def r(): String = "constant"

object BadTaskSupport:
  @Tool(taskSupport = Some("sometimes")) def slow(a: Int): Int = a

object VarargsNotRequired:
  @Tool def join(@Param("parts", required = false) parts: String*): String = parts.mkString

object PlainNotRequired:
  @Tool def one(@Param("n", required = false) n: Int): Int = n

case class TypedNotRequired(@Param("n", required = false) n: List[Int])

case class DxTree(children: List[DxTree])
final class DxOpaque(val id: Int)

object RecursiveParam:
  @Tool def walk(tree: DxTree): String = ""

object OpaqueParam:
  @Tool def take(o: DxOpaque): String = ""

object NonStringMapParam:
  @Tool def take(m: Map[Int, String]): String = ""

case class OpaqueField(o: DxOpaque)

object StaticWithParams:
  @Resource("static://with-params") def r(id: String): String = id

/** C2.18 / D1.13 (GH #116, TJC-2362): the diagnostics a user reads when a tool is called with a
  * missing argument or when an annotated method cannot be registered.
  */
class DiagnosticsPolishTest extends AnyFunSuite:

  // ---- D1.13: a missing required argument, on both registration paths ----

  private def server(validate: Boolean): McpServer[Any] =
    val server = McpServer("missing-arg", settings = McpServerSettings(validateToolInput = validate))
    val _ = server.scanAnnotations[MissingArgTools.type]
    runUnsafe(server.tool(McpTool[MissingArgs, String](name = "typed")(a => s"${a.p}:${a.q}")))
    runUnsafe(server.tool(McpTool[NestedArgs, String](name = "nested")(_.toString)))
    runUnsafe(server.tool(McpTool[NumberArgs, String](name = "number")(_.n.toString)))
    server

  private def failure(server: McpServer[Any], tool: String, args: (String, Json)*): Throwable =
    runUnsafe(server.toolManager.callTool(tool, args.toMap, None).either).left.toOption
      .getOrElse(fail(s"$tool accepted $args"))

  test("on the wire both paths answer a missing argument with the same isError result") {
    val h = MacroDxHarness("missing-arg-wire") { server =>
      val _ = server.scanAnnotations[MissingArgTools.type]
      runUnsafe(server.tool(McpTool[MissingArgs, String](name = "typed")(a => s"${a.p}:${a.q}")))
    }
    val annotated = h.call("annotated", "{}")
    val typed = h.call("typed", "{}")
    assert(annotated.isError && typed.isError)
    assert(annotated.text == "Invalid arguments for tool 'annotated': missing required property 'p'")
    assert(typed.text == "Invalid arguments for tool 'typed': missing required property 'p'")
    // A tool execution error, not a JSON-RPC error: the frame carries `result`, never `error`.
    val raw = h.frame(
      """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"typed","arguments":{}}}"""
    )
    assert(raw.contains(""""isError":true""") && !raw.contains(""""error":"""), raw)
  }

  test("with validation off both paths still agree: the same exception and message") {
    val off = server(validate = false)
    val annotated = failure(off, "annotated")
    val typed = failure(off, "typed")
    assert(annotated.isInstanceOf[NoSuchElementException], annotated)
    assert(typed.isInstanceOf[NoSuchElementException], typed)
    assert(annotated.getMessage == "Missing required argument 'p'")
    assert(typed.getMessage == annotated.getMessage)
    // The defaulted member is not missing on either path.
    val args = Map[String, Any]("p" -> Json.Str("x"))
    assert(runUnsafe(off.toolManager.callTool("annotated", args, None)) == "x:1")
  }

  test("typed path: other decode failures name the tool as a tool, not as a parameter") {
    val off = server(validate = false)
    val wrongType = failure(off, "typed", "p" -> Json.Num(1))
    assert(wrongType.getMessage.startsWith("Failed to decode the arguments of tool 'typed': .p("), wrongType)
    assert(!wrongType.getMessage.contains("parameter 'typed'"))
    // A member missing inside a nested object is not a missing ARGUMENT: it keeps its path.
    val nested = failure(off, "nested", "inner" -> Json.Obj())
    assert(
      nested.getMessage.startsWith("Failed to decode the arguments of tool 'nested': .inner.p(missing)"),
      nested
    )
    val huge = failure(off, "number", "n" -> Json.Num(BigDecimal("1e100000")))
    assert(huge.isInstanceOf[IllegalArgumentException], huge)
    assert(huge.getMessage.startsWith("Arguments of tool 'number' rejected: "), huge)
  }

  test("toolArgumentError leaves a user decoder's own failure alone") {
    val own = new IllegalStateException("my decoder says no")
    assert(McpServerCore.toolArgumentError("t", own) eq own)
    val otherTool = new RuntimeException("Failed to decode parameter 'other' from JSON: .p(missing)")
    assert(McpServerCore.toolArgumentError("t", otherTool) eq otherTool)
  }

  // ---- C2.18: compile-time diagnostics ----

  test("env mismatch on a server without an environment suggests typed[<the method's env>]") {
    val errs: List[Error] = typeCheckErrors("""McpServer("s").scanAnnotations[EnvOnAny.type]""")
    assertSomeMessageContains(
      errs,
      "@Tool 'needsRef' requires ZIO environment 'zio.Ref[scala.Int]'",
      "McpServer.typed[Ref[Int]] (or wider)"
    )
    assert(!errs.exists(_.message.contains("Any &")), messages(errs))
    assertSomeMessageContains(
      typeCheckErrors("""McpServer("s").scanAnnotations[EnvPrompt.type]"""),
      "@Prompt 'needsRef'",
      "McpServer.typed[Ref[Int]]"
    )
    assertSomeMessageContains(
      typeCheckErrors("""McpServer("s").scanAnnotations[EnvResource.type]"""),
      "@Resource 'needsRef'",
      "McpServer.typed[Ref[Int]]"
    )
  }

  test("env mismatch on a server that has an environment suggests the intersection") {
    val errs: List[Error] =
      typeCheckErrors("""McpServer.typed[String]("s").scanAnnotations[EnvOnAny.type]""")
    assertSomeMessageContains(errs, "McpServer.typed[String & Ref[Int]] (or wider)")
  }

  test("a diagnostic about a method is positioned at the method") {
    val errs: List[Error] = typeCheckErrors("""McpServer("s").scanAnnotations[EnvOnAny.type]""")
    assert(errs.sizeIs == 1, messages(errs))
    assert(errs.head.lineContent.contains("def needsRef"), s"reported at: ${errs.head.lineContent}")
    val task: List[Error] = typeCheckErrors("""McpServer("s").scanAnnotations[BadTaskSupport.type]""")
    assert(task.head.lineContent.contains("def slow"), s"reported at: ${task.head.lineContent}")
  }

  test("@Resource with a non-literal uri says so") {
    for obj <- List("NonLiteralUri", "NonLiteralUriPositional") do
      val errs: List[Error] = obj match
        case "NonLiteralUri" => typeCheckErrors("""McpServer("s").scanAnnotations[NonLiteralUri.type]""")
        case _ => typeCheckErrors("""McpServer("s").scanAnnotations[NonLiteralUriPositional.type]""")
      assertSomeMessageContains(errs, "@Resource(uri) must be a string literal", "dynamic")
      assert(!errs.exists(_.message.contains("must have a 'uri' parameter")), messages(errs))
  }

  test("control: a final val constant is a valid @Resource uri") {
    val server = McpServer("constant-uri")
    val _ = server.scanAnnotations[ConstantUri.type]
    assert(server.resourceManager.listStaticResources().map(_.uri) == List("const://x"))
  }

  test("@Tool(taskSupport) with an unknown value names the method") {
    val errs: List[Error] = typeCheckErrors("""McpServer("s").scanAnnotations[BadTaskSupport.type]""")
    assertSomeMessageContains(errs, "@Tool(taskSupport) on method 'slow'", "got \"sometimes\"")
  }

  test("required = false on a repeated parameter is explained without compiler internals") {
    val errs: List[Error] =
      typeCheckErrors("""McpServer("s").scanAnnotations[VarargsNotRequired.type]""")
    assertSomeMessageContains(
      errs,
      "Parameter 'parts' in method 'join'",
      "is a repeated parameter (String*)",
      "parts: List[String] = Nil"
    )
    assert(!errs.exists(_.message.contains("Repeated")), messages(errs))
    assert(!errs.exists(_.message.contains("scala.")), messages(errs))
  }

  test("required = false without Option or default: both paths name the remedy") {
    assertSomeMessageContains(
      typeCheckErrors("""McpServer("s").scanAnnotations[PlainNotRequired.type]"""),
      "Parameter 'n' in method 'one' is marked as required=false",
      "Use Option[Int] or provide a default value."
    )
    assertSomeMessageContains(
      typeCheckErrors("""McpTool[TypedNotRequired, String](name = "t")(_.toString)"""),
      "Field 'n' in typed request TypedNotRequired is marked as required=false",
      "Use Option[List[Int]] or provide a default value."
    )
  }

  test("annotation path: an underivable parameter is pointed at McpInputCodec only") {
    for
      errs <- List(
        typeCheckErrors("""McpServer("s").scanAnnotations[RecursiveParam.type]"""),
        typeCheckErrors("""McpServer("s").scanAnnotations[OpaqueParam.type]"""),
        typeCheckErrors("""McpServer("s").scanAnnotations[NonStringMapParam.type]""")
      )
    do
      assert(errs.nonEmpty)
      assert(!errs.exists(_.message.contains("McpTool.withSchema")), messages(errs))
      assert(!errs.exists(_.message.contains("given McpSchema[")), messages(errs))
      assertSomeMessageContains(errs, "McpInputCodec[")
  }

  test("typed path: the schema remedies still include McpSchema and McpTool.withSchema") {
    val errs: List[Error] =
      typeCheckErrors("""com.tjclp.fastmcp.macros.JsonSchemaMacro.schemaForToolInput[OpaqueField]""")
    assertSomeMessageContains(errs, "McpInputCodec[", "McpSchema[", "McpTool.withSchema")
  }

  test("a static @Resource with parameters is reported at the method") {
    val errs: List[Error] = typeCheckErrors("""McpServer("s").scanAnnotations[StaticWithParams.type]""")
    assertSomeMessageContains(errs, "Static resource method 'r' must not have parameters")
    assert(errs.head.lineContent.contains("def r"), s"reported at: ${errs.head.lineContent}")
  }
