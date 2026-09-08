package com.tjclp.fastmcp
package macros

import scala.compiletime.testing.{typeCheckErrors, Error}

import org.scalatest.funsuite.AnyFunSuite
import zio.json.ast.Json

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.macros.MacroDxHarness.{assertSomeMessageContains, messages, runUnsafe}

/** TJC-2331 (C2 rows `X_in_string`, `X_in_int`, `X_out_enum`, schema I1 `out-*`): the
  * typed-contract type arguments that today either compile into an uncallable tool or fail with a
  * bare implicit-not-found.
  *
  *   - `McpTool[String, _]` / `McpTool[Int, _]` compile and advertise `inputSchema = {"type":
  *     "string"}` / `{"type": "integer"}` (ToolSchemaProviders.scala has no root guard); MCP
  *     `arguments` is always an object, so no call can ever satisfy the decoder (C2.4).
  *   - `.withOutputSchema` compiles for `Out = String` / `Option[String]` / `List[Content]` and
  *     advertises a non-object `outputSchema` while emitting no `structuredContent` at all — the
  *     spec says a tool with `outputSchema` MUST return a conforming `structuredContent`; `Out =
  *     Unit` advertises the empty object but emits nothing (C2.5).
  *   - `McpTool[DxItem, Color]` (an enum result) fails with "No given instance of type
  *     McpEncoder[Color]" although docs/custom-types.md promises Scala 3 enums in `In`/`Out` need
  *     no user-supplied givens (GH #78) — DEFERRED to 1.0.1 (C2.12).
  */
class TypedContractSurfaceNegativeTest extends AnyFunSuite:

  test("McpTool[String, _] is rejected at compile time: tool inputs must be object types") {
    val errs: List[Error] = typeCheckErrors("""McpTool[String, String](name = "s")(s => s)""")
    assert(
      errs.nonEmpty,
      "McpTool[String, String] compiled; it advertises inputSchema {\"type\":\"string\"}, which no " +
        "MCP arguments object can satisfy"
    )
    assertSomeMessageContains(errs, "case class", "String")
  }

  test("McpTool[Int, _] is rejected at compile time: tool inputs must be object types") {
    val errs: List[Error] = typeCheckErrors("""McpTool[Int, String](name = "i")(i => i.toString)""")
    assert(
      errs.nonEmpty,
      "McpTool[Int, String] compiled; it advertises inputSchema {\"type\":\"integer\"}, which no " +
        "MCP arguments object can satisfy"
    )
    assertSomeMessageContains(errs, "case class", "NoArgs")
  }

  // List[Int] (not List[DxItem]): its McpDecoder resolves, so the schema guard is what fails.
  test("McpTool[List[Int], _] is rejected at compile time: an array root is not an object") {
    val errs: List[Error] =
      typeCheckErrors("""McpTool[List[Int], String](name = "l")(_.size.toString)""")
    assert(errs.nonEmpty, "McpTool[List[Int], String] compiled with an array inputSchema root")
    assertSomeMessageContains(errs, "case class", "array")
  }

  test("control: a Map[String, Int] In is an object root and compiles") {
    val errs: List[Error] =
      typeCheckErrors("""McpTool[Map[String, Int], String](name = "m")(_.size.toString)""")
    assert(errs.isEmpty, messages(errs))
  }

  test(".withOutputSchema on Out = String is rejected: structuredContent must be an object") {
    val errs: List[Error] =
      typeCheckErrors("""McpTool[DxItem, String](name = "o")(_.name).withOutputSchema""")
    assert(
      errs.nonEmpty,
      "McpTool[DxItem, String].withOutputSchema compiled; it advertises outputSchema " +
        "{\"type\":\"string\"} and can never emit a conforming structuredContent"
    )
    assertSomeMessageContains(errs, "withOutputSchema", "case class", "String")
  }

  test(".withOutputSchema on Out = Option[String] is rejected") {
    val errs: List[Error] = typeCheckErrors(
      """McpTool[DxItem, Option[String]](name = "o")(i => Option(i.name)).withOutputSchema"""
    )
    assert(errs.nonEmpty, "McpTool[DxItem, Option[String]].withOutputSchema compiled")
    assertSomeMessageContains(errs, "withOutputSchema", "case class")
  }

  test(".withOutputSchema on Out = List[Content] is rejected") {
    val errs: List[Error] = typeCheckErrors(
      """McpTool[DxItem, List[Content]](name = "o")(i => List(TextContent(i.name))).withOutputSchema"""
    )
    assert(errs.nonEmpty, "McpTool[DxItem, List[Content]].withOutputSchema compiled")
    assertSomeMessageContains(errs, "withOutputSchema", "case class")
  }

  test("Out = Unit with .withOutputSchema emits the conforming empty-object structuredContent") {
    val h = MacroDxHarness("unit-out") { server =>
      runUnsafe(server.tool(McpTool[DxItem, Unit](name = "u")(_ => ()).withOutputSchema))
    }
    val out = h.call("u", """{"name":"x","qty":1}""")
    assert(!out.isError, s"unexpected error: $out")
    assert(
      out.structuredContent.contains(Json.Obj()),
      s"Unit result advertises an empty-object outputSchema but emitted structuredContent = " +
        s"${out.structuredContent}"
    )
  }

  test("control: a case-class Out with .withOutputSchema compiles") {
    val errs: List[Error] =
      typeCheckErrors("""McpTool[DxItem, DxItem](name = "cc")(identity).withOutputSchema""")
    assert(errs.isEmpty, messages(errs))
  }

  // DEFERRED to 1.0.1 (C2.12): the `McpEncoder` Mirror fallback needs `ProductOf`, so a bare enum
  // `Out` has no encoder although enum FIELDS of `Out` derive. Additive fix (Mirror.SumOf path).
  test("an enum result type needs no hand-written McpEncoder (docs/custom-types.md)") {
    pendingUntilFixed {
      val errs: List[Error] =
        typeCheckErrors("""McpTool[DxItem, Color](name = "e")(_ => Color.RED)""")
      assert(errs.isEmpty, s"McpTool[DxItem, Color] does not compile:\n${messages(errs)}")
    }
  }

  test("control: a case-class In and a String Out compile") {
    val errs: List[Error] = typeCheckErrors("""McpTool[DxItem, String](name = "ok")(_.name)""")
    assert(errs.isEmpty, messages(errs))
  }
