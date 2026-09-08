package com.tjclp.fastmcp
package macros

import org.scalatest.funsuite.AnyFunSuite

import com.tjclp.fastmcp.core.*
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

/** TJC-2331 (C2 rows `X_ret_unit`, `X_ret_option`, `X_ret_caseclass`, `X_ret_liststring`; row
  * C2.9, DOCUMENTED as a 1.0 limit): on the annotation path a result that is not a `String`, an
  * `Array[Byte]` or a `Content` reaches the wire as `TextContent(result.toString)` and never as
  * `structuredContent` (WireMapping `case value => TextContent(value.toString)`). Typed contracts
  * (`McpTool`, optionally `.withOutputSchema`) are the JSON path. These tests pin the documented
  * rendering so a change to it is deliberate; 1.1.0 plans to route such results through
  * `McpEncoder` / `JsonEncoder` when one is summonable, at which point they flip.
  */
class ResultRenderingTest extends AnyFunSuite:

  private lazy val h = MacroDxHarness("result-rendering") { server =>
    val _ = server.scanAnnotations[ResultRenderingTools.type]
  }

  test("a Unit result is rendered as the text \"()\" (documented toString rendering)") {
    val out = h.call("ret_unit", """{"p":1}""")
    assert(!out.isError, s"unexpected error: $out")
    assert(out.texts == List("()") && out.structuredContent.isEmpty, s"Unit rendering: $out")
  }

  test("Some(x) is rendered as the text \"Some(x)\" (documented toString rendering)") {
    val out = h.call("ret_option_some", """{"p":1}""")
    assert(!out.isError, s"unexpected error: $out")
    assert(out.text == "Some(1)" && out.structuredContent.isEmpty, s"Option rendering: $out")
  }

  test("None is rendered as the text \"None\" (documented toString rendering)") {
    val out = h.call("ret_option_none", """{"p":1}""")
    assert(!out.isError, s"unexpected error: $out")
    assert(out.text == "None" && out.structuredContent.isEmpty, s"Option rendering: $out")
  }

  test("a case-class result is rendered via toString, not as JSON (documented; use McpTool for JSON)") {
    val out = h.call("ret_caseclass", """{"p":1}""")
    assert(!out.isError, s"unexpected error: $out")
    assert(
      out.text == "DxItem(1,1)" && out.structuredContent.isEmpty,
      s"case-class rendering: $out"
    )
  }

  test("a List[String] result is rendered via toString, not as a JSON array (documented)") {
    val out = h.call("ret_liststring", """{"p":1}""")
    assert(!out.isError, s"unexpected error: $out")
    assert(out.text == "List(1, b)" && out.structuredContent.isEmpty, s"List rendering: $out")
  }
