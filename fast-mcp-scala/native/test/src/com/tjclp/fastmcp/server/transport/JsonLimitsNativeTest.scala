package com.tjclp.fastmcp
package server.transport

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.stream.*

import com.tjclp.fastmcp.codec.DefaultDecodeContext
import com.tjclp.fastmcp.jsonrpc.JsonRpcMessage
import com.tjclp.fastmcp.server.LimitSettings
import com.tjclp.fastmcp.server.manager.ResourceTemplatePattern

/** Platform canary for the inbound input limits on Scala Native (TJC-2295). Stdio is the only
  * Native transport, so `MessageLoop.parseFrame` is the whole surface: a deep frame and a
  * hash-colliding object are rejected with -32700 before the parser allocates, embedded JSON is
  * bounded, and the template matcher is regex-free (the SN RE2 shim is bypassed). The process
  * continuing to the end of the suite is the point.
  */
class JsonLimitsNativeTest extends AnyFunSuite with Matchers:

  private def message(result: Either[JsonRpcMessage, JsonRpcMessage]): String =
    result match
      case Left(JsonRpcMessage.Failure(None, err)) =>
        err.code shouldBe -32700
        err.message
      case other => fail(s"expected a -32700 parse failure, got $other")

  private def collidingKeys(blocks: Int): IndexedSeq[String] =
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

  test("a 100 000-deep frame is rejected as maxDepth without touching the parser") {
    message(MessageLoop.parseFrame("[" * 100_000 + "]" * 100_000)) should include("maxDepth")
  }

  test("a 3^9-colliding-key object is rejected as maxObjectFields") {
    val keys = collidingKeys(9)
    keys.map(_.hashCode).distinct.size shouldBe 1
    val frame =
      s"""{"jsonrpc":"2.0","id":1,"method":"ping",${keys.map(k => s""""$k":0""").mkString(",")}}"""
    message(
      MessageLoop.parseFrame(frame, LimitSettings(maxFrameChars = 8 * 1024 * 1024))
    ) should include(
      "maxObjectFields"
    )
  }

  test("a normal frame still parses under the default limits") {
    MessageLoop.parseFrame("""{"jsonrpc":"2.0","id":1,"method":"ping"}""").isRight shouldBe true
  }

  test("embedded JSON strings are depth-bounded before any recursive walk") {
    val ex = intercept[IllegalArgumentException](
      DefaultDecodeContext.default.parseJsonArray("blob", "[" * 300 + "]" * 300)
    )
    ex.getMessage should include("maxDepth")
    // 50 000 levels would overflow the native stack inside zio-json's recursive parser (not
    // catchable on Scala Native) — the linear pre-scan rejects the text before the parser runs.
    val ex2 = intercept[IllegalArgumentException](
      DefaultDecodeContext.default.parseJsonArray("blob", "[" * 50_000 + "]" * 50_000)
    )
    ex2.getMessage should include("maxDepth")
    val ex3 = intercept[IllegalArgumentException](
      DefaultDecodeContext.default.parseJsonObject(
        "blob",
        (1 to 1100).map(i => s""""k$i":$i""").mkString("{", ",", "}")
      )
    )
    ex3.getMessage should include("maxObjectFields")
  }

  test("an over-long array and a frame of too many values are rejected before the parser runs") {
    val limits = LimitSettings()
    val megabyte = "[" + "1," * 524_000 + "1]"
    message(MessageLoop.parseFrame(megabyte)) should include("maxArrayElements")
    val at = Seq.fill(limits.maxArrayElements)("1").mkString("[", ",", "]")
    MessageLoop
      .parseFrame(s"""{"jsonrpc":"2.0","id":1,"method":"ping","params":{"xs":$at}}""")
      .isRight shouldBe true
    val rows = Seq.fill(1000)("[1,2,3]").mkString("[", ",", "]")
    val many = (1 to 24).map(i => s""""t$i":$rows""").mkString("{", ",", "}")
    message(MessageLoop.parseFrame(many)) should include("maxValues")
    val ex = intercept[IllegalArgumentException](
      DefaultDecodeContext.default.parseJsonArray("blob", megabyte)
    )
    ex.getMessage should include("maxArrayElements")
  }

  test("numbers with extreme exponents are rejected at the frame and before any decoder") {
    Seq("1e999999999", "-1E+2147483647", "1e-999999999", "1e513").foreach { literal =>
      val start = java.lang.System.nanoTime()
      val frame = s"""{"jsonrpc":"2.0","id":1,"method":"ping","params":{"n":$literal}}"""
      message(MessageLoop.parseFrame(frame)) should include("maxNumberExponent")
      (java.lang.System.nanoTime() - start) / 1_000_000L should be < 500L
    }
    // Built from (unscaled, scale): the test itself never does magnitude-sized work. The moderate
    // scale comes first on purpose: without the guard it decodes (cheaply) and fails this test at
    // once, before the scales that would cost minutes and gigabytes.
    Seq(-20000, -999999999, 999999999).foreach { scale =>
      val node = zio.json.ast.Json.Num(new java.math.BigDecimal(java.math.BigInteger.ONE, scale))
      val start = java.lang.System.nanoTime()
      val ex = intercept[IllegalArgumentException](
        DefaultDecodeContext.decodeRaw[BigInt](
          "n",
          node,
          DefaultDecodeContext.default,
          zio.json.JsonDecoder[BigInt]
        )
      )
      ex.getMessage should include("maxNumberExponent")
      (java.lang.System.nanoTime() - start) / 1_000_000L should be < 500L
    }
    val ok =
      """{"jsonrpc":"2.0","id":1,"method":"ping","params":{"a":9223372036854775807,"b":1.7976931348623157E308,"c":4.9E-324}}"""
    MessageLoop.parseFrame(ok).isRight shouldBe true
  }

  test("the template matcher handles a 100 KB adversarial URI without regex") {
    val start = java.lang.System.nanoTime()
    ResourceTemplatePattern("x://{a}.{b}.{c}").matches("x://" + "a" * 99_999 + ".") shouldBe None
    val ms = (java.lang.System.nanoTime() - start) / 1_000_000L
    ms should be < 500L
    ResourceTemplatePattern("x://{name}.{ext}").matches("x://archive.tar.gz") shouldBe Some(
      Map("name" -> "archive.tar", "ext" -> "gz")
    )
  }

  test("Native stdio preserves padded overflow and recovers on the next line") {
    val limits = LimitSettings(maxFrameChars = 128)
    val ping = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
    val input = ping + " " * 200 + "discarded junk\n" + " " * 200 + "\n" + ping + "\n"
    val frames = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(
          ZStream
            .fromIterable(input.grouped(17).toList)
            .via(BoundedLines.pipeline(limits.maxFrameChars))
            .filter(MessageLoop.shouldDispatchStdioFrame(_, limits))
            .runCollect
        )
        .getOrThrowFiberFailure()
    }
    frames.length shouldBe 3
    message(MessageLoop.parseFrame(frames(0), limits)) should include("maxFrameChars")
    message(MessageLoop.parseFrame(frames(1), limits)) should include("maxFrameChars")
    MessageLoop.parseFrame(frames(2), limits).isRight shouldBe true
  }
