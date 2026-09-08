package com.tjclp.fastmcp
package macros

import org.scalatest.funsuite.AnyFunSuite

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.RegistrationMacro.scanAnnotations

/** Every parameter here is `@Param(required = false)` WITH a Scala default — the only spelling the
  * macro accepts for an optional non-`Option` parameter (ToolProcessor.scala:120-133). The
  * advertised schema therefore drops `p` from `required`; a client that honours the schema omits
  * `p` and expects the Scala default to apply.
  */
object DefaultOmissionTools:

  @Tool(name = Some("int_default"))
  def intDefault(@Param("p", required = false) p: Int = 1): String = p.toString

  @Tool(name = Some("list_default"))
  def listDefault(@Param("p", required = false) p: List[Int] = Nil): String = p.toString

  @Tool(name = Some("vector_default"))
  def vectorDefault(@Param("p", required = false) p: Vector[Int] = Vector.empty): String =
    p.toString

  @Tool(name = Some("set_default"))
  def setDefault(@Param("p", required = false) p: Set[Int] = Set.empty): String = p.toString

  @Tool(name = Some("map_default"))
  def mapDefault(@Param("p", required = false) p: Map[String, Int] = Map.empty): String =
    p.toString

  @Tool(name = Some("item_default"))
  def itemDefault(@Param("p", required = false) p: DxItem = DxItem("x", 1)): String = p.toString

  @Tool(name = Some("item_list_default"))
  def itemListDefault(@Param("p", required = false) p: List[DxItem] = Nil): String = p.toString

  @Tool(name = Some("item_map_default"))
  def itemMapDefault(@Param("p", required = false) p: Map[String, DxItem] = Map.empty): String =
    p.toString

  @Tool(name = Some("option_color_list_default"))
  def optionColorListDefault(@Param("p", required = false) p: List[Option[Color]] = Nil): String =
    p.toString

  @Tool(name = Some("option_color_map_default"))
  def optionColorMapDefault(
      @Param("p", required = false) p: Map[String, Option[Color]] = Map.empty
  ): String = p.toString

/** TJC-2331 (C2 fuzz rows `S_*_rf_dy`): a defaulted non-`Option` parameter is advertised as
  * optional, so a `tools/call` that omits it must succeed with the Scala default applied. Today the
  * generated handler does `Map.getOrElse(key, throw NoSuchElementException("Key not found in map"))`
  * (MapToFunctionMacro.scala:189-196) and never consults the `<method>$default$N` getter, so the
  * schema promises what the runtime refuses.
  */
class DefaultAppliedOnOmissionTest extends AnyFunSuite:

  private lazy val h = MacroDxHarness("default-omission") { server =>
    val _ = server.scanAnnotations[DefaultOmissionTools.type]
  }

  private val cases: List[(String, String)] = List(
    "int_default" -> "1",
    "list_default" -> "List()",
    "vector_default" -> "Vector()",
    "set_default" -> "Set()",
    "map_default" -> "Map()",
    "item_default" -> "DxItem(x,1)",
    "item_list_default" -> "List()",
    "item_map_default" -> "Map()",
    "option_color_list_default" -> "List()",
    "option_color_map_default" -> "Map()"
  )

  for (tool, expected) <- cases do
    test(s"$tool: a call that omits the defaulted parameter succeeds with the Scala default") {
      val required = h.required(tool)
      assert(!required.contains("p"), s"$tool advertises p as required: $required")
      val out = h.call(tool, "{}")
      assert(
        !out.isError,
        s"$tool advertises p as optional (required = $required) yet rejected {}: ${out.text}"
      )
      assert(out.text == expected, s"$tool: expected the default <$expected>, got <${out.text}>")
    }

  test("control: supplying the parameter explicitly still works") {
    val out = h.call("int_default", """{"p":41}""")
    assert(!out.isError && out.text == "41", s"unexpected reply: $out")
    val items = h.call("item_list_default", """{"p":[{"name":"y","qty":2}]}""")
    assert(!items.isError && items.text == "List(DxItem(y,2))", s"unexpected reply: $items")
  }

  test("advertised `required` and runtime omission handling agree for every defaulted tool") {
    val disagreements = cases.map(_._1).flatMap { tool =>
      val advertisedRequired = h.required(tool).contains("p")
      val omissionRejected = h.call(tool, "{}").isError
      Option.when(advertisedRequired != omissionRejected)(
        s"$tool: required=$advertisedRequired but omission rejected=$omissionRejected"
      )
    }
    assert(disagreements.isEmpty, disagreements.mkString("\n"))
  }
