package com.tjclp.fastmcp
package macros

import org.scalatest.funsuite.AnyFunSuite
import zio.json.ast.Json

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.MacroDxHarness.parseJson
import com.tjclp.fastmcp.macros.RegistrationMacro.scanAnnotations

/** Annotation-path tools whose result type is neither `String` nor `Content`. */
object ResultRenderingTools:

  @Tool(name = Some("ret_unit"))
  def retUnit(@Param("p") p: Int): Unit = ()

  @Tool(name = Some("ret_option_some"))
  def retOptionSome(@Param("p") p: Int): Option[String] = Some(p.toString)

  @Tool(name = Some("ret_option_none"))
  def retOptionNone(@Param("p") p: Int): Option[String] = None

  @Tool(name = Some("ret_caseclass"))
  def retCaseClass(@Param("p") p: Int): DxItem = DxItem(p.toString, p)

  @Tool(name = Some("ret_liststring"))
  def retListString(@Param("p") p: Int): List[String] = List(p.toString, "b")

/** TJC-2331 (C2 rows `X_ret_unit`, `X_ret_option`, `X_ret_caseclass`, `X_ret_liststring`): the
  * annotation path puts Scala `toString` on the wire for any result that is not a `String`,
  * `Array[Byte]` or `Content` (WireMapping.scala:119 `case value => TextContent(value.toString)`),
  * so clients receive `"()"`, `"Some(1)"`, `"DxItem(1,1)"` and `"List(1, b)"`. The typed path
  * encodes the same values as JSON through `McpEncoder`; the annotation path must not degrade to
  * `toString`.
  */
class ResultRenderingTest extends AnyFunSuite:

  private lazy val h = MacroDxHarness("result-rendering") { server =>
    val _ = server.scanAnnotations[ResultRenderingTools.type]
  }

  test("a Unit result is not rendered as the text \"()\"") {
    val out = h.call("ret_unit", """{"p":1}""")
    assert(!out.isError, s"unexpected error: $out")
    assert(!out.texts.contains("()"), s"Unit result rendered via toString: ${out.texts}")
  }

  test("Some(x) is rendered as x, not as \"Some(x)\"") {
    val out = h.call("ret_option_some", """{"p":1}""")
    assert(!out.isError, s"unexpected error: $out")
    assert(out.text == "1", s"Option result rendered via toString: ${out.texts}")
  }

  test("None is not rendered as the text \"None\"") {
    val out = h.call("ret_option_none", """{"p":1}""")
    assert(!out.isError, s"unexpected error: $out")
    assert(!out.texts.contains("None"), s"Option result rendered via toString: ${out.texts}")
  }

  test("a case-class result is rendered as JSON, not via toString") {
    val out = h.call("ret_caseclass", """{"p":1}""")
    assert(!out.isError, s"unexpected error: $out")
    val rendered = out.structuredContent.orElse(parseJson(out.text).toOption)
    val fields = rendered.flatMap(_.asObject)
    assert(
      fields.exists(o => o.get("name").contains(Json.Str("1")) && o.get("qty").contains(Json.Num(1))),
      s"case-class result is not JSON {\"name\":\"1\",\"qty\":1}: text=${out.texts} " +
        s"structuredContent=${out.structuredContent}"
    )
  }

  test("a List[String] result is rendered as a JSON array, not via toString") {
    val out = h.call("ret_liststring", """{"p":1}""")
    assert(!out.isError, s"unexpected error: $out")
    val rendered = out.structuredContent.orElse(parseJson(out.text).toOption)
    assert(
      rendered.contains(Json.Arr(Json.Str("1"), Json.Str("b"))),
      s"List[String] result is not the JSON array [\"1\",\"b\"]: text=${out.texts} " +
        s"structuredContent=${out.structuredContent}"
    )
  }
