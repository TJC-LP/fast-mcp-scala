package com.tjclp.fastmcp
package macros

import scala.compiletime.testing.typeCheckErrors

import org.scalatest.funsuite.AnyFunSuite

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.RegistrationMacro.scanAnnotations
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.transport.JvmTransportBackend.given

// ---------------------------------------------------------------------------------------------
// Negative fixtures (TOP-LEVEL so the typeCheckErrors snippets can name them). Every one of them
// compiled silently at HEAD (F4 / TJC-2298) and registered with mixed provenance or last-writer-
// wins semantics; they must now be compile-time errors.
// ---------------------------------------------------------------------------------------------

object DupToolNames:
  @Tool def dup(@Param("a") a: Int): Int = a
  @Tool def dup(@Param("a") a: String): String = a

object DupExplicitNames:
  @Tool(name = Some("same")) def one(a: Int): Int = a
  @Tool(name = Some("same")) def two(a: String): String = a

object DupPrompts:
  @Prompt def p(a: Int): String = ""
  @Prompt def p(a: String): String = ""

object DupResources:
  @Resource("static://x") def x1(): String = ""
  @Resource("static://x") def x2(): String = ""

object DupTemplates:
  @Resource("users://{id}") def a(id: String): String = id
  @Resource("users://{userId}") def b(userId: String): String = userId

class NotAnObject:
  @Tool def t(a: Int): Int = a

object SiblingDefault:
  def opt(s: String = "d"): String = s
  @Tool def opt(@Param("x", required = false) x: Int): Int = x

case class ApplyOverloadReq(x: Int, @Param(description = "y", required = false) y: Boolean)

object ApplyOverloadReq:

  def apply(x: Int, y: Boolean = true, z: String): ApplyOverloadReq =
    new ApplyOverloadReq(x, y)

object NonLiteralNames:
  val n: String = "runtime-name"
  val flag: Boolean = true

object NonLiteralToolName:
  @Tool(name = Some(NonLiteralNames.n)) def t(a: Int): Int = a

object NonLiteralHint:
  @Tool(readOnlyHint = Some(NonLiteralNames.flag)) def t(a: Int): Int = a

object NonLiteralResourceName:
  @Resource("res://x", name = Some(NonLiteralNames.n)) def r(): String = ""

/** Compile-time diagnostics for F4 (TJC-2298): duplicate registered keys within one scanned object,
  * non-object scan targets, and `required = false` gates that used to be satisfied by a same-named
  * sibling's (or a companion `apply` overload's) default getters.
  */
class OverloadNegativeTest extends AnyFunSuite:

  private def messages(errors: List[scala.compiletime.testing.Error]): List[String] =
    errors.map(_.message)

  private def assertSomeMessageContains(
      errors: List[scala.compiletime.testing.Error],
      fragments: String*
  ): Unit =
    val msgs = messages(errors)
    fragments.foreach { fragment =>
      assert(
        msgs.exists(_.contains(fragment)),
        s"expected an error containing <$fragment>; got: ${msgs.mkString("\n---\n")}"
      )
    }

  test("two @Tool overloads registering the same default name are rejected, naming both") {
    val errs: List[scala.compiletime.testing.Error] = typeCheckErrors("""
      val s = McpServer.typed[Any]("neg")
      s.scanAnnotations[DupToolNames.type]
    """)
    assertSomeMessageContains(
      errs,
      "@Tool name 'dup'",
      "dup(a: Int)",
      "dup(a: String)",
      "OverloadNegativeTest.scala"
    )
  }

  test("two @Tool methods with the same explicit name are rejected") {
    val errs: List[scala.compiletime.testing.Error] = typeCheckErrors("""
      val s = McpServer.typed[Any]("neg")
      s.scanAnnotations[DupExplicitNames.type]
    """)
    assertSomeMessageContains(errs, "@Tool name 'same'", "one(a: Int)", "two(a: String)")
  }

  test("two @Prompt overloads registering the same name are rejected") {
    val errs: List[scala.compiletime.testing.Error] = typeCheckErrors("""
      val s = McpServer.typed[Any]("neg")
      s.scanAnnotations[DupPrompts.type]
    """)
    assertSomeMessageContains(errs, "@Prompt name 'p'")
  }

  test("two @Resource methods with the same static uri are rejected") {
    val errs: List[scala.compiletime.testing.Error] = typeCheckErrors("""
      val s = McpServer.typed[Any]("neg")
      s.scanAnnotations[DupResources.type]
    """)
    assertSomeMessageContains(errs, "@Resource uri 'static://x'")
  }

  test("two @Resource templates differing only in placeholder names are rejected") {
    val errs: List[scala.compiletime.testing.Error] = typeCheckErrors("""
      val s = McpServer.typed[Any]("neg")
      s.scanAnnotations[DupTemplates.type]
    """)
    assertSomeMessageContains(errs, "@Resource template 'users://{}'", "placeholder names")
  }

  test("scanning a class (not an object's singleton type) is rejected with a hint") {
    val errs: List[scala.compiletime.testing.Error] = typeCheckErrors("""
      val s = McpServer.typed[Any]("neg")
      s.scanAnnotations[NotAnObject]
    """)
    assertSomeMessageContains(errs, "requires the singleton type of an object")
  }

  test("required=false is not satisfied by a same-named sibling's default getter") {
    val errs: List[scala.compiletime.testing.Error] = typeCheckErrors("""
      val s = McpServer.typed[Any]("neg")
      s.scanAnnotations[SiblingDefault.type]
    """)
    assertSomeMessageContains(errs, "marked as required=false", "'x'")
  }

  test("required=false on a typed-request field is not satisfied by a companion apply overload") {
    val errs: List[scala.compiletime.testing.Error] = typeCheckErrors("""
      ToolInputSchema.derived[ApplyOverloadReq]
    """)
    assertSomeMessageContains(
      errs,
      "Field 'y' in typed request ApplyOverloadReq is marked as required=false"
    )
  }

  test(
    "a non-literal @Tool name is a compile-time error, not a silent fallback to the method name"
  ) {
    val errs: List[scala.compiletime.testing.Error] = typeCheckErrors("""
      val s = McpServer.typed[Any]("neg")
      s.scanAnnotations[NonLiteralToolName.type]
    """)
    assertSomeMessageContains(
      errs,
      "@Tool(name) must be a literal Option[String]",
      "NonLiteralNames.n"
    )
  }

  test("a non-literal @Tool hint is a compile-time error") {
    val errs: List[scala.compiletime.testing.Error] = typeCheckErrors("""
      val s = McpServer.typed[Any]("neg")
      s.scanAnnotations[NonLiteralHint.type]
    """)
    assertSomeMessageContains(errs, "@Tool(readOnlyHint) must be a literal Option[Boolean]")
  }

  test("a non-literal @Resource name is a compile-time error") {
    val errs: List[scala.compiletime.testing.Error] = typeCheckErrors("""
      val s = McpServer.typed[Any]("neg")
      s.scanAnnotations[NonLiteralResourceName.type]
    """)
    assertSomeMessageContains(errs, "@Resource(name) must be a literal Option[String]")
  }

  test("control: the positive fixtures still compile (no false-positive collisions)") {
    val errs: List[scala.compiletime.testing.Error] = typeCheckErrors("""
      val s = McpServer.typed[Any]("ok")
      val _ = s.scanAnnotations[OverloadedTools.type]
      val _ = s.scanAnnotations[DescriptionOnlyTools.type]
      val _ = s.scanAnnotations[OverloadedResources.type]
      val _ = s.scanAnnotations[OverloadedPrompts.type]
      val _ = s.scanAnnotations[SpelledNames.type]
      val _ = s.scanAnnotations[StaticBraceAndTemplate.type]
      val _ = ToolInputSchema.derived[CaseWithCtorDefault]
    """)
    assert(errs == Nil, s"unexpected errors: ${messages(errs).mkString("\n---\n")}")
  }
