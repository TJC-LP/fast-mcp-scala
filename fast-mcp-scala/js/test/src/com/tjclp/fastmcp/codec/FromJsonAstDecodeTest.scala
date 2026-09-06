package com.tjclp.fastmcp
package codec

import org.scalatest.funsuite.AnyFunSuite
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.McpDecoder
import com.tjclp.fastmcp.server.manager.ResourceTemplatePattern

/** The AST decode path and the embedded-JSON bounds on the JS engine (TJC-2295 / F3), plus a
  * regex-free spot check of the template matcher on JavaScriptCore (F2).
  */
class FromJsonAstDecodeTest extends AnyFunSuite:

  import McpDecoders.given

  case class Add(a: Int, b: Int) derives JsonDecoder

  /** No explicit JsonDecoder: goes through the INLINE `derivedZioJsonDecoder` given, which splices
    * `DefaultDecodeContext.decodeRaw` into this compilation unit.
    */
  case class Point(x: Int, y: Int)

  private val ctx = DefaultDecodeContext.default

  private def now(): Double = scala.scalajs.js.Dynamic.global.performance.now().asInstanceOf[Double]

  test("McpDecoder decodes a Map of wire Json values (the AST path) on JS") {
    assert(
      McpDecoder[Add].decode("add", Map("a" -> Json.Num(2), "b" -> Json.Num(3)), ctx) == Add(2, 3)
    )
    assert(
      McpDecoder[Point].decode("p", Map("x" -> Json.Num(1), "y" -> Json.Num(-1)), ctx) == Point(
        1,
        -1
      )
    )
    assert(
      McpDecoder[Add].decode("add", Json.Obj("a" -> Json.Num(5), "b" -> Json.Num(6)), ctx) == Add(
        5,
        6
      )
    )
  }

  test("a 64-deep Json argument decodes or fails with a RuntimeException — never a RangeError") {
    val deep = (1 to 64).foldLeft(Json.Null: Json)((acc, _) => Json.Arr(acc))
    val outcome =
      try Right(McpDecoder[Add].decode("add", Map("a" -> deep, "b" -> Json.Num(1)), ctx))
      catch case e: RuntimeException => Left(e)
    assert(outcome.isLeft, "a nested array is not an Int")
    assert(outcome.swap.toOption.exists(_.getMessage.contains("Failed to decode parameter 'add'")))
  }

  test("parseJsonArray bounds embedded JSON depth") {
    val ex = intercept[IllegalArgumentException](ctx.parseJsonArray("blob", "[" * 300 + "]" * 300))
    assert(ex.getMessage.contains("maxDepth"))
    assert(ctx.parseJsonArray("blob", "[[1]]") == List(List(1)))
  }

  test("ResourceTemplatePattern is regex-free and linear on the JS engine") {
    assert(
      ResourceTemplatePattern("x://{name}.{ext}").matches("x://archive.tar.gz") ==
        Some(Map("name" -> "archive.tar", "ext" -> "gz"))
    )
    val pattern = ResourceTemplatePattern("x://{a}-{b}")
    val _ = pattern.matches("x://aaa") // warm-up
    val uri = "x://" + "a" * 100_000
    val start = now()
    val result = pattern.matches(uri)
    val elapsed = now() - start
    assert(result.isEmpty)
    assert(elapsed < 100.0, s"took $elapsed ms")
    val three = ResourceTemplatePattern("x://{a}.{b}.{c}")
    val start2 = now()
    assert(three.matches("x://" + "a" * 99_999 + ".").isEmpty)
    assert(now() - start2 < 100.0)
  }
