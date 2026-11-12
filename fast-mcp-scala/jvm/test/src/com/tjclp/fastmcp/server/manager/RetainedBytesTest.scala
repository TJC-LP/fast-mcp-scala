package com.tjclp.fastmcp.server.manager

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.Cause
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.jsonrpc.McpError

/** The estimate the task store charges ([[RetainedBytes]]) must never be smaller than the UTF-16
  * payload it stands for, must charge a document of many small nodes more than its wire size, and
  * must stay cheap and stack-safe on hostile shapes.
  */
class RetainedBytesTest extends AnyFunSuite with Matchers:

  test("a string is charged at least two bytes per character") {
    RetainedBytes.ofJson(Json.Str("x" * 10_000)) should be >= 20_000L
    RetainedBytes.ofText("") should be > 0L
  }

  test("a document of many small nodes is charged several times its wire size") {
    val doc: Json = Json.Arr((1 to 10_000).map(_ => Json.Num(0))*)
    val wire = doc.toJson.length.toLong
    RetainedBytes.ofJson(doc) should be >= 4 * wire
  }

  test("object members are charged for their keys as well as their values") {
    val keys: Json = Json.Obj((1 to 100).map(i => ("k" * 100 + i) -> (Json.Null: Json))*)
    RetainedBytes.ofJson(keys) should be >= 100L * 2 * 100
  }

  test("a number is charged by its magnitude, not a flat rate") {
    val huge: Json = Json.Num(new java.math.BigDecimal("9" * 20_000))
    RetainedBytes.ofJson(huge) should be >= 8_000L
    RetainedBytes.ofJson(Json.Num(1)) should be < 200L
  }

  test("the walk gives up once it passes the limit") {
    val doc: Json = Json.Arr((1 to 1000).map(_ => Json.Str("y" * 1000))*)
    val full = RetainedBytes.ofJson(doc)
    val capped = RetainedBytes.ofJson(doc, limit = 10_000L)
    capped should be > 10_000L
    capped should be < full / 10
  }

  test("a deeply nested value does not overflow the stack") {
    val deep = (1 to 200_000).foldLeft(Json.Null: Json)((inner, _) => Json.Arr(inner))
    RetainedBytes.ofJson(deep) should be > 200_000L
  }

  test("values the router never stores are still charged something") {
    RetainedBytes.of("text") shouldBe RetainedBytes.ofText("text")
    RetainedBytes.of(new Array[Byte](4096)) should be >= 4096L
    RetainedBytes.of(42) shouldBe RetainedBytes.Opaque
  }

  test("a failure is charged for its messages and its JSON error data") {
    val plain = Cause.fail(new RuntimeException("m" * 5_000): Throwable)
    RetainedBytes.ofCause(plain) should be >= 10_000L
    val withData = Cause.fail(
      McpError(-32000, "boom", Some(Json.Obj("blob" -> Json.Str("d" * 5_000)))): Throwable
    )
    RetainedBytes.ofCause(withData) should be >= 10_000L
    RetainedBytes.ofCause(Cause.die(new IllegalStateException("d" * 5_000))) should be >= 10_000L
  }
