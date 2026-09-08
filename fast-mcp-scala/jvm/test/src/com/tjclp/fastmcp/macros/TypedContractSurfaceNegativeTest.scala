package com.tjclp.fastmcp
package macros

import scala.compiletime.testing.{typeCheckErrors, Error}

import org.scalatest.funsuite.AnyFunSuite

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.macros.MacroDxHarness.{assertSomeMessageContains, messages}

/** TJC-2331 (C2 rows `X_in_string`, `X_in_int`, `X_out_enum`): the typed-contract type arguments
  * that today either compile into an uncallable tool or fail with a bare implicit-not-found.
  *
  *   - `McpTool[String, _]` / `McpTool[Int, _]` compile and advertise `inputSchema = {"type":
  *     "string"}` / `{"type": "integer"}` (ToolSchemaProviders.scala:22-25 has no root guard); MCP
  *     `arguments` is always an object, so no call can ever satisfy the decoder.
  *   - `McpTool[DxItem, Color]` (an enum result) fails with "No given instance of type
  *     McpEncoder[Color]" although docs/custom-types.md promises Scala 3 enums in `In`/`Out` need
  *     no user-supplied givens (GH #78).
  */
class TypedContractSurfaceNegativeTest extends AnyFunSuite:

  test("McpTool[String, _] is rejected at compile time: tool inputs must be object types") {
    val errs: List[Error] = typeCheckErrors("""McpTool[String, String](name = "s")(s => s)""")
    assert(
      errs.nonEmpty,
      "McpTool[String, String] compiled; it advertises inputSchema {\"type\":\"string\"}, which no " +
        "MCP arguments object can satisfy"
    )
    assertSomeMessageContains(errs, "case class")
  }

  test("McpTool[Int, _] is rejected at compile time: tool inputs must be object types") {
    val errs: List[Error] = typeCheckErrors("""McpTool[Int, String](name = "i")(i => i.toString)""")
    assert(
      errs.nonEmpty,
      "McpTool[Int, String] compiled; it advertises inputSchema {\"type\":\"integer\"}, which no " +
        "MCP arguments object can satisfy"
    )
    assertSomeMessageContains(errs, "case class")
  }

  test("an enum result type needs no hand-written McpEncoder (docs/custom-types.md)") {
    val errs: List[Error] = typeCheckErrors("""McpTool[DxItem, Color](name = "e")(_ => Color.RED)""")
    assert(errs.isEmpty, s"McpTool[DxItem, Color] does not compile:\n${messages(errs)}")
  }

  test("control: a case-class In and a String Out compile") {
    val errs: List[Error] = typeCheckErrors("""McpTool[DxItem, String](name = "ok")(_.name)""")
    assert(errs.isEmpty, messages(errs))
  }
