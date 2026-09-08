package com.tjclp.fastmcp
package macros

import scala.compiletime.testing.{typeCheckErrors, Error}

import org.scalatest.funsuite.AnyFunSuite

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.MacroDxHarness.{assertSomeMessageContains, messages}
import com.tjclp.fastmcp.macros.RegistrationMacro.{scanAnnotations, scanAnnotationsQuiet}
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.transport.JvmTransportBackend.given

// ---------------------------------------------------------------------------------------------
// Declaration shapes that compile silently today (TOP-LEVEL so typeCheckErrors can name them).
// ---------------------------------------------------------------------------------------------

trait DxPingBase:
  @Tool(name = Some("inherited_ping"))
  def ping(@Param("p") p: Int): String = p.toString

/** The only annotated members are inherited from the trait. */
object DxFromTrait extends DxPingBase

object DxValFunction:
  @Tool(name = Some("val_ping"))
  val ping: Int => String = p => p.toString

object DxOuter:

  object Inner:
    @Tool(name = Some("nested_ping"))
    def ping(@Param("p") p: Int): String = p.toString

object DxCurried:

  @Tool(name = Some("curried"))
  def curried(@Param("p", required = true) p: Option[String] = None)(q: Int): String = s"$p/$q"

object DxGeneric:
  @Tool(name = Some("generic"))
  def generic[G](@Param("p") p: Int, g: G): String = p.toString + g.toString

object DxArity23:

  @Tool(name = Some("arity23"))
  def arity23(
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
      p12: Int,
      p13: Int,
      p14: Int,
      p15: Int,
      p16: Int,
      p17: Int,
      p18: Int,
      p19: Int,
      p20: Int,
      p21: Int,
      p22: Int,
      p23: Int
  ): Int =
    p1 + p2 + p3 + p4 + p5 + p6 + p7 + p8 + p9 + p10 + p11 + p12 + p13 + p14 + p15 + p16 + p17 +
      p18 + p19 + p20 + p21 + p22 + p23

object DxPlain:
  @Tool(name = Some("plain_ping"))
  def ping(@Param("p") p: Int): String = p.toString

/** TJC-2331 (C2 rows `X_decl_*`, `P002`/`P030` curried, `P042` generic, `P049`/`P027` many25):
  * declaration shapes the scan accepts without a diagnostic and then mis-registers.
  *
  *   - `@Tool` on a `val`, inside a nested object, or inherited from a trait: silently ignored
  *     (RegistrationMacro.scala:46 walks `declaredMethods` only), `tools/list` is empty.
  *   - curried methods: only the first parameter list registers (ToolProcessor.scala:107 takes
  *     `paramSymss.head`); the partially applied `Function1`'s `toString` goes on the wire.
  *   - generic methods: the macro crashes ("Exception occurred while executing macro expansion ...
  *     partially applied Term") at MacroUtils.scala:74, positioned on the object header.
  *   - more than 22 parameters: compiles, advertises, and every complete call dies at the
  *     RefResolver arity guard (RefResolver.scala:490).
  */
class DeclarationShapeNegativeTest extends AnyFunSuite:

  private inline def scan(inline obj: String): String =
    "val s = McpServer.typed[Any](\"neg\"); s.scanAnnotations[" + obj + ".type]"

  test("@Tool inherited from a trait mixin is registered") {
    val server = McpServer("from-trait")
    val _ = server.scanAnnotationsQuiet[DxFromTrait.type]
    val names = server.toolManager.listDefinitions().map(_.name)
    assert(
      names.contains("inherited_ping"),
      s"trait-inherited @Tool silently dropped; registered tools = $names"
    )
  }

  test("@Tool on a val is a compile-time error, not a silent skip") {
    val errs: List[Error] = typeCheckErrors(scan("DxValFunction"))
    assert(errs.nonEmpty, "@Tool on `val ping: Int => String` compiled and registered nothing")
    assertSomeMessageContains(errs, "ping")
  }

  test("@Tool inside a nested object is a compile-time error, not a silent skip") {
    val errs: List[Error] = typeCheckErrors(scan("DxOuter"))
    assert(errs.nonEmpty, "scanAnnotations[DxOuter.type] compiled and registered nothing")
    assertSomeMessageContains(errs, "Inner")
  }

  test("a curried @Tool method is rejected, naming the method and the extra parameter list") {
    val errs: List[Error] = typeCheckErrors(scan("DxCurried"))
    assert(
      errs.nonEmpty,
      "curried @Tool compiled; only (p) registers and Function1.toString goes on the wire"
    )
    assertSomeMessageContains(errs, "curried", "parameter list")
  }

  test("a generic @Tool method is a diagnostic naming the method, not a macro crash") {
    val errs: List[Error] = typeCheckErrors(scan("DxGeneric"))
    assert(errs.nonEmpty, "generic @Tool compiled")
    assert(
      !errs.exists(_.message.contains("Exception occurred while executing macro expansion")),
      s"macro crashed instead of reporting:\n${messages(errs)}"
    )
    assertSomeMessageContains(errs, "generic", "type parameter")
  }

  test("a @Tool method with more than 22 parameters is rejected at compile time") {
    val errs: List[Error] = typeCheckErrors(scan("DxArity23"))
    assert(
      errs.nonEmpty,
      "23-parameter @Tool compiled; every complete call fails at the RefResolver 22-argument guard"
    )
    assertSomeMessageContains(errs, "arity23", "22")
  }

  test("control: a plain object with a plain @Tool method compiles and registers") {
    val errs: List[Error] = typeCheckErrors(scan("DxPlain"))
    assert(errs.isEmpty, messages(errs))
    val server = McpServer("plain")
    val _ = server.scanAnnotations[DxPlain.type]
    assert(server.toolManager.listDefinitions().map(_.name) == List("plain_ping"))
  }
