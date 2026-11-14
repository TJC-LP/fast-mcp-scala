package com.tjclp.fastmcp
package macros

import scala.compiletime.testing.{typeCheckErrors, Error}

import org.scalatest.funsuite.AnyFunSuite
import zio.json.*

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.macros.MacroDxHarness.{assertSomeMessageContains, messages, runUnsafe}

/** An enum whose companion carries the user's own `McpEncoder`. */
enum GapMood:
  case UP, DOWN

object GapMood:
  given McpEncoder[GapMood] = McpEncoder.instance(mood => List(TextContent(s"mood:${mood.ordinal}")))

/** An enum whose companion carries the user's own `JsonEncoder` (custom spelling). */
enum GapTone:
  case LOUD, QUIET

object GapTone:
  given JsonEncoder[GapTone] = JsonEncoder.string.contramap(_.toString.toLowerCase)

/** An enum with a parameterized case: zio-json's wrapper-object encoding. */
enum GapShape:
  case Dot
  case Circle(radius: Int)

/** A result element with a `JsonEncoder` and a user `McpEncoder` for the list of it. */
case class GapRow(id: Int) derives JsonEncoder

object GapRow:
  given McpEncoder[List[GapRow]] = McpEncoder.instance(rows => List(TextContent(s"${rows.size} rows")))

/** A result element without any encoder. */
final class GapOpaque(val id: Int)

sealed trait GapSealed
case class GapLeaf(n: Int) extends GapSealed

/** C2.11 / C2.12 (GH #116): typed-contract type arguments that had no codec — `In = Unit`, a bare
  * enum `Out`, `List` / `Vector` `Out` — and the rule that a user's own instance still wins.
  * `MacroSchemaDxSpec` runs the positive half on all three platforms.
  */
class TypedCodecGapsTest extends AnyFunSuite:

  private val item = """{"name":"x","qty":2}"""

  test("McpTool[Unit, String] compiles and is called with an empty arguments object") {
    val errs: List[Error] = typeCheckErrors("""McpTool[Unit, String](name = "u")(_ => "ok")""")
    assert(errs.isEmpty, messages(errs))
    val h = MacroDxHarness("unit-in") { server =>
      runUnsafe(server.tool(McpTool[Unit, String](name = "u")(_ => "ok")))
    }
    assert(h.inputSchema("u").toJson == """{"type":"object","properties":{},"additionalProperties":false}""")
    val out = h.call("u", "{}")
    assert(!out.isError && out.text == "ok", s"reply: $out")
    val extra = h.call("u", """{"a":1}""")
    assert(extra.isError && extra.text.contains("unexpected property 'a'"), s"reply: $extra")
  }

  test("the environment-aware and withSchema factories accept Unit too") {
    val errs: List[Error] = typeCheckErrors(
      """
      val a = McpTool[Unit, String, Any](name = "u")(_ => "ok")
      val b = McpTool.withSchema[Unit, String]("u", ToolInputSchema.default)(_ => "ok")
      """
    )
    assert(errs.isEmpty, messages(errs))
  }

  test("a bare enum Out encodes as the case name, with and without an outputSchema-free tool") {
    val h = MacroDxHarness("enum-out") { server =>
      runUnsafe(server.tool(McpTool[DxItem, Color](name = "color")(_ => Color.GREEN)))
      runUnsafe(server.tool(McpTool[DxItem, GapShape](name = "shape")(i => GapShape.Circle(i.qty))))
    }
    val color = h.call("color", item)
    assert(!color.isError && color.text == "\"GREEN\"", s"reply: $color")
    // A parameterized-case enum keeps zio-json's wrapper object.
    val shape = h.call("shape", item)
    assert(!shape.isError && shape.text == """{"Circle":{"radius":2}}""", s"reply: $shape")
  }

  test("a user's McpEncoder or JsonEncoder for an enum wins over the derived one") {
    val h = MacroDxHarness("enum-user") { server =>
      runUnsafe(server.tool(McpTool[DxItem, GapMood](name = "mood")(_ => GapMood.DOWN)))
      runUnsafe(server.tool(McpTool[DxItem, GapTone](name = "tone")(_ => GapTone.LOUD)))
    }
    assert(h.call("mood", item).text == "mood:1")
    assert(h.call("tone", item).text == "\"loud\"")
  }

  test("List[A] and Vector[A] results encode as a JSON array") {
    val h = MacroDxHarness("list-out") { server =>
      runUnsafe(server.tool(McpTool[DxItem, List[String]](name = "strings")(i => List(i.name, "b"))))
      runUnsafe(server.tool(McpTool[DxItem, List[Int]](name = "ints")(i => List(i.qty, 3))))
      runUnsafe(server.tool(McpTool[DxItem, Vector[String]](name = "vector")(i => Vector(i.name))))
      runUnsafe(server.tool(McpTool[DxItem, List[GapTone]](name = "tones")(_ => List(GapTone.QUIET))))
      runUnsafe(server.tool(McpTool[DxItem, List[Option[Int]]](name = "options")(_ => List(Some(1), None))))
      runUnsafe(server.tool(McpTool[DxItem, Seq[String]](name = "seq")(i => Seq(i.name))))
    }
    assert(h.call("strings", item).text == """["x","b"]""")
    assert(h.call("ints", item).text == "[2,3]")
    assert(h.call("vector", item).text == """["x"]""")
    assert(h.call("tones", item).text == """["quiet"]""")
    assert(h.call("options", item).text == "[1,null]")
    assert(h.call("seq", item).text == """["x"]""")
  }

  test("content lists are still passed through, not serialized") {
    val h = MacroDxHarness("content-out") { server =>
      runUnsafe(
        server.tool(
          McpTool[DxItem, List[Content]](name = "content")(i => List(TextContent(i.name), TextContent("b")))
        )
      )
      runUnsafe(
        server.tool(McpTool[DxItem, List[TextContent]](name = "text")(i => List(TextContent(i.name))))
      )
    }
    assert(h.call("content", item).texts == List("x", "b"))
    assert(h.call("text", item).texts == List("x"))
  }

  test("a user's McpEncoder[List[A]] wins over the List instance") {
    val h = MacroDxHarness("list-user") { server =>
      given McpEncoder[List[Int]] = McpEncoder.instance(ints => List(TextContent(s"sum=${ints.sum}")))
      runUnsafe(server.tool(McpTool[DxItem, List[Int]](name = "local")(i => List(i.qty, 3))))
    }
    assert(h.call("local", item).text == "sum=5")
  }

  test("an Out without any encoder names the type and the remedies") {
    val errs: List[Error] =
      typeCheckErrors("""McpTool[DxItem, GapOpaque](name = "o")(i => new GapOpaque(i.qty))""")
    assertSomeMessageContains(
      errs,
      "No McpEncoder[com.tjclp.fastmcp.macros.GapOpaque] found",
      "given zio.json.JsonEncoder[com.tjclp.fastmcp.macros.GapOpaque]",
      "return a String / Content"
    )
  }

  test("a sealed trait or Option of an unencodable type is still not derived") {
    assert(typeCheckErrors("""McpTool[DxItem, GapSealed](name = "s")(i => GapLeaf(i.qty))""").nonEmpty)
    assert(
      typeCheckErrors("""McpTool[DxItem, Option[GapOpaque]](name = "s")(_ => None)""").nonEmpty
    )
    assert(
      typeCheckErrors("""McpTool[DxItem, List[GapOpaque]](name = "s")(_ => Nil)""").nonEmpty
    )
  }

  test("an In without a decoder names the type and the remedies") {
    val errs: List[Error] =
      typeCheckErrors(
        """McpTool.withSchema[GapOpaque, String]("o", ToolInputSchema.default)(_.id.toString)"""
      )
    assertSomeMessageContains(
      errs,
      "No McpDecoder[com.tjclp.fastmcp.macros.GapOpaque] found",
      "McpInputCodec[com.tjclp.fastmcp.macros.GapOpaque]"
    )
  }

  // Not fixed (C2.12, third item): the pure and the effectful `Builder.apply` overloads both
  // accept `_ => None` when Out is an Option, and no change to either that keeps their signatures
  // removes the ambiguity. The documented spelling keeps working.
  test("Out = Option[T]: Option.empty[T] selects the pure overload") {
    val errs: List[Error] = typeCheckErrors(
      """McpTool[DxItem, Option[String]](name = "o")(_ => Option.empty[String])"""
    )
    assert(errs.isEmpty, messages(errs))
  }
