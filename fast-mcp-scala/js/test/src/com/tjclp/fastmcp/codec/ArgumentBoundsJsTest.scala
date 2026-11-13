package com.tjclp.fastmcp
package codec

import org.scalatest.funsuite.AnyFunSuite
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.jsonrpc.JsonRpcMessage
import com.tjclp.fastmcp.server.LimitSettings
import com.tjclp.fastmcp.server.transport.MessageLoop

/** The array-length, value-count and number-magnitude limits on the JS engine (TJC-5087): the same
  * shared code as the JVM (`JsonLimits`, `DefaultDecodeContext.decodeRaw`), exercised here because
  * the costs it bounds are worst on a single-threaded runtime with a pure-Scala `BigInteger`.
  * Hazardous numbers are built from `(unscaled, scale)` so the test itself never does
  * magnitude-sized work.
  */
class ArgumentBoundsJsTest extends AnyFunSuite:

  private val ctx = DefaultDecodeContext.default
  private val limits = LimitSettings()

  private def now(): Double = scala.scalajs.js.Dynamic.global.performance.now().asInstanceOf[Double]

  private def call(arguments: String): String =
    s"""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"t","arguments":$arguments}}"""

  private def refusal(frame: String, settings: LimitSettings = limits): String =
    MessageLoop.parseFrame(frame, settings) match
      case Left(JsonRpcMessage.Failure(None, err)) =>
        assert(err.code == -32700)
        err.message
      case other => fail(s"expected a -32700 parse failure, got $other")

  private def scaled(scale: Int): Json =
    Json.Num(new java.math.BigDecimal(java.math.BigInteger.ONE, scale))

  private def collidingStrings(blocks: Int): IndexedSeq[String] =
    val parts = Array("Aa", "BB", "C#")
    (0 until math.pow(3, blocks).toInt).map { k =>
      val sb = new StringBuilder
      var rest = k
      var b = 0
      while b < blocks do
        sb.append(parts(rest % 3))
        rest /= 3
        b += 1
      sb.result()
    }

  test("an extreme exponent is refused at the frame check in bounded time") {
    Seq("1e999999999", "-1E+2147483647", "1e-999999999", "1e513").foreach { literal =>
      val start = now()
      val message = refusal(call(s"""{"n":$literal}"""))
      val elapsed = now() - start
      assert(message.contains("maxNumberExponent"), s"$literal: $message")
      assert(elapsed < 500.0, s"$literal took $elapsed ms")
    }
    assert(refusal(call(s"""{"n":${"1" * 513}}""")).contains("maxNumberDigits"))
  }

  test("decodeRaw refuses an out-of-range number before a BigInt / BigDecimal decoder runs") {
    // The moderate scale comes first on purpose: without the guard it decodes (cheaply) and
    // fails this test at once, before the scales that would cost minutes and gigabytes.
    Seq(-20000, -999999999, 999999999, Int.MinValue + 1, Int.MaxValue).foreach { scale =>
      val node = scaled(scale)
      val start = now()
      val bigInt = intercept[IllegalArgumentException](
        DefaultDecodeContext.decodeRaw[BigInt]("n", node, ctx, JsonDecoder[BigInt])
      )
      val bigDecimal = intercept[IllegalArgumentException](
        DefaultDecodeContext.decodeRaw[BigDecimal]("d", node, ctx, JsonDecoder[BigDecimal])
      )
      val nested = intercept[IllegalArgumentException](
        DefaultDecodeContext.decodeRaw[Json](
          "j",
          Json.Obj("xs" -> Json.Arr(Json.Num(1), node)),
          ctx,
          JsonDecoder[Json]
        )
      )
      val elapsed = now() - start
      assert(bigInt.getMessage.contains("maxNumberExponent"), s"scale $scale")
      assert(bigDecimal.getMessage.contains("maxNumberExponent"), s"scale $scale")
      assert(nested.getMessage.contains("maxNumberExponent"), s"scale $scale")
      assert(elapsed < 500.0, s"scale $scale took $elapsed ms")
    }
  }

  test("a number written as a JSON string is refused only when it becomes a BigDecimal") {
    val node = Json.Str("1e999999999")
    val start = now()
    val ex = intercept[IllegalArgumentException](
      DefaultDecodeContext.decodeRaw[BigDecimal]("d", node, ctx, JsonDecoder[BigDecimal])
    )
    val noIntegerPart = intercept[IllegalArgumentException](
      DefaultDecodeContext.decodeRaw[BigDecimal](
        "d",
        Json.Str(".5e999999999"),
        ctx,
        JsonDecoder[BigDecimal]
      )
    )
    assert(noIntegerPart.getMessage.contains("maxNumberExponent"))
    val nested = intercept[IllegalArgumentException](
      DefaultDecodeContext.decodeRaw[List[BigDecimal]](
        "d",
        Json.Arr(Json.Num(1), node),
        ctx,
        JsonDecoder[List[BigDecimal]]
      )
    )
    assert(now() - start < 500.0)
    assert(ex.getMessage.contains("maxNumberExponent"))
    assert(nested.getMessage.contains("maxNumberExponent"))
    assert(
      DefaultDecodeContext.decodeRaw[String]("s", node, ctx, JsonDecoder[String]) == "1e999999999"
    )
    assert(
      DefaultDecodeContext.decodeRaw[BigDecimal](
        "d",
        Json.Str("12.50"),
        ctx,
        JsonDecoder[BigDecimal]
      ) == BigDecimal("12.50")
    )
  }

  test("ordinary numbers still decode: Long and Double ranges, in-range big integers") {
    def node(literal: String): Json =
      s"[$literal]".fromJson[Json] match
        case Right(Json.Arr(elems)) if elems.length == 1 => elems(0)
        case other => fail(s"$literal: $other")
    Seq(Long.MaxValue, Long.MinValue, 0L, 9007199254740993L).foreach { value =>
      assert(MessageLoop.parseFrame(call(s"""{"n":$value}""")).isRight)
      assert(
        DefaultDecodeContext.decodeRaw[Long]("n", node(value.toString), ctx, JsonDecoder[Long]) ==
          value
      )
    }
    Seq("1.7976931348623157E308", "-1.7976931348623157e308", "4.9E-324", "0.1").foreach { literal =>
      assert(MessageLoop.parseFrame(call(s"""{"n":$literal}""")).isRight, literal)
      assert(
        DefaultDecodeContext
          .decodeRaw[Double]("n", node(literal), ctx, JsonDecoder[Double]) == literal.toDouble,
        literal
      )
    }
    val start = now()
    val big = DefaultDecodeContext.decodeRaw[BigInt]("n", node("1e400"), ctx, JsonDecoder[BigInt])
    assert(big == BigInt(10).pow(400))
    assert(big.bitLength <= 3402) // (maxNumberDigits + maxNumberExponent) x log2(10)
    assert(now() - start < 500.0)
  }

  test("array and value bounds: an over-long array and a frame of too many values are refused") {
    val over = Seq.fill(limits.maxArrayElements + 1)("1").mkString("[", ",", "]")
    assert(refusal(call(s"""{"xs":$over}""")).contains("maxArrayElements"))
    val at = Seq.fill(limits.maxArrayElements)("1").mkString("[", ",", "]")
    assert(MessageLoop.parseFrame(call(s"""{"xs":$at}""")).isRight)

    val megabyte = "[" + "1," * 524_000 + "1]"
    val start = now()
    assert(refusal(call(s"""{"xs":$megabyte}""")).contains("maxArrayElements"))
    assert(now() - start < 1000.0)

    val rows = Seq.fill(1000)("[1,2,3]").mkString("[", ",", "]")
    val many = (1 to 24).map(i => s""""t$i":$rows""").mkString("{", ",", "}")
    assert(refusal(call(many)).contains("maxValues"))

    val ex = intercept[IllegalArgumentException](ctx.parseJsonArray("blob", over))
    assert(ex.getMessage.contains("maxArrayElements"))
    // moderate exponent: unguarded, the unwrapping of this number expands it (cheaply at 1e20000)
    val ex2 = intercept[IllegalArgumentException](ctx.parseJsonArray("blob", "[1e20000]"))
    assert(ex2.getMessage.contains("maxNumberExponent"))
  }

  test("a Set built from an array of fully colliding strings at the bound stays inside the budget") {
    val colliding = collidingStrings(8).take(limits.maxArrayElements)
    assert(colliding.size == limits.maxArrayElements)
    assert(colliding.map(_.hashCode).distinct.size == 1)
    val array = Json.Arr(zio.Chunk.fromIterable(colliding.map(Json.Str(_))))
    val _ = DefaultDecodeContext.decodeRaw[Set[String]]("s", Json.Arr(), ctx, JsonDecoder[Set[String]])
    val start = now()
    val set = DefaultDecodeContext.decodeRaw[Set[String]]("s", array, ctx, JsonDecoder[Set[String]])
    val elapsed = now() - start
    assert(set.size == limits.maxArrayElements)
    info(s"Set[String] of ${limits.maxArrayElements} fully colliding elements decoded in $elapsed ms")
    assert(elapsed < 2000.0, s"took $elapsed ms")
  }
