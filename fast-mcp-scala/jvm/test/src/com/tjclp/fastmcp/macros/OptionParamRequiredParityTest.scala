package com.tjclp.fastmcp
package macros

import org.scalatest.funsuite.AnyFunSuite

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.macros.RegistrationMacro.scanAnnotations

/** `Option[T]` parameters carrying a bare `@Param("...")` (no `required = ...` argument). Without
  * `@Param` an `Option` parameter is optional (JsonSchemaMacro.scala:46); adding a description must
  * not silently flip it to required.
  */
object OptionParamTools:

  @Tool(name = Some("opt_int"))
  def optInt(@Param("p") p: Option[Int]): String = p.toString

  @Tool(name = Some("opt_int_default"))
  def optIntDefault(@Param("p") p: Option[Int] = None): String = p.toString

  @Tool(name = Some("opt_color"))
  def optColor(@Param("p") p: Option[Color]): String = p.toString

  @Tool(name = Some("opt_opt_color"))
  def optOptColor(@Param("p") p: Option[Option[Color]]): String = p.toString

  @Tool(name = Some("opt_item"))
  def optItem(@Param("p") p: Option[DxItem]): String = p.toString

  @Tool(name = Some("opt_either"))
  def optEither(@Param("p") p: Option[Either[Color, String]]): String = p.toString

  /** Explicit `required = true` on an `Option`: whatever the schema says, the runtime must agree. */
  @Tool(name = Some("opt_explicit_required"))
  def optExplicitRequired(@Param("p", required = true) p: Option[String]): String = p.toString

  /** Three annotated parameters declared out of alphabetical order. */
  @Tool(name = Some("declared_order"))
  def declaredOrder(@Param("b") b: Int, @Param("a") a: Int, @Param("c") c: Int): String =
    s"$b$a$c"

/** Typed-contract twin of the annotation fixtures (C2 row `X_in_option_default`). */
case class OptQ(
    @Param("q") q: String,
    @Param("limit") limit: Option[Int],
    @Param("n", required = false) n: Int = 3
)

/** TJC-2331 (C2 rows `S_*_option_ru_*`, `S_option_color_none_ru_*`, `X_in_option_default`,
  * `P0xx many25`): an `Option` parameter with a bare `@Param` is advertised in `required`, yet
  * omitting it (and sending `null`) succeeds — the schema and the runtime disagree. `@Param`'s
  * `required` defaults to `true` and ToolProcessor.scala:120-133 re-adds the name via
  * MacroUtils.injectParamMetadata (MacroUtils.scala:692-704). The same helper also re-sorts
  * `required` alphabetically (MacroUtils.scala:811) so it no longer follows `properties`.
  */
class OptionParamRequiredParityTest extends AnyFunSuite:

  private lazy val h = MacroDxHarness("option-required") { server =>
    val _ = server.scanAnnotations[OptionParamTools.type]
  }

  private val bareOptionTools =
    List("opt_int", "opt_int_default", "opt_color", "opt_opt_color", "opt_item", "opt_either")

  for tool <- bareOptionTools do
    test(s"$tool: a bare @Param on an Option parameter does not make it required") {
      val required = h.required(tool)
      assert(
        !required.contains("p"),
        s"$tool advertises the Option parameter as required: $required"
      )
    }

  test("control: omitting a bare-@Param Option parameter is accepted as None") {
    bareOptionTools.foreach { tool =>
      val out = h.call(tool, "{}")
      assert(!out.isError && out.text == "None", s"$tool {} -> $out")
    }
  }

  // DEFERRED to 1.0.1 (C2.18): an explicit `@Param(required = true)` on an `Option` is advertised
  // required but `{}` is still accepted as `None`. `pendingUntilFixed` keeps the canary armed: the
  // day the runtime rejects the omission this test fails and the guard must be dropped.
  test("advertised `required` and runtime omission handling agree for every Option tool") {
    pendingUntilFixed {
      val disagreements = (bareOptionTools :+ "opt_explicit_required").flatMap { tool =>
        val advertisedRequired = h.required(tool).contains("p")
        val omissionRejected = h.call(tool, "{}").isError
        Option.when(advertisedRequired != omissionRejected)(
          s"$tool: required=$advertisedRequired but omission rejected=$omissionRejected"
        )
      }
      assert(disagreements.isEmpty, disagreements.mkString("\n"))
    }
  }

  // DEFERRED to 1.0.1 (C2.23): `required` is re-sorted alphabetically (MacroUtils `.sorted`) while
  // `properties` keep declaration order. Cosmetic; folded into the C2.6 `required`/defaults change.
  test("`required` lists parameters in declaration order, like `properties`") {
    pendingUntilFixed {
      val properties = h.propertyNames("declared_order")
      val required = h.required("declared_order")
      assert(properties == List("b", "a", "c"), s"properties order: $properties")
      assert(required == properties, s"required $required does not follow properties $properties")
    }
  }

  test("typed contract: an Option field with a bare @Param is not required") {
    val typed = MacroDxHarness("option-required-typed") { server =>
      MacroDxHarness.runUnsafe(
        server.tool(McpTool[OptQ, String](name = "opt_q")(q => q.toString))
      )
    }
    val required = typed.required("opt_q")
    assert(required.contains("q"), s"q must stay required: $required")
    assert(!required.contains("limit"), s"Option field 'limit' advertised as required: $required")
    // The typed path applies the Scala default n = 3 — the parity anchor for the annotation path.
    val out = typed.call("opt_q", """{"q":"x"}""")
    assert(!out.isError && out.text == "OptQ(x,None,3)", s"unexpected reply: $out")
  }
