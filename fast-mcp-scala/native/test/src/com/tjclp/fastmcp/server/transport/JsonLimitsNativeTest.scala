package com.tjclp.fastmcp
package server.transport

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

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

  test("the template matcher handles a 100 KB adversarial URI without regex") {
    val start = java.lang.System.nanoTime()
    ResourceTemplatePattern("x://{a}.{b}.{c}").matches("x://" + "a" * 99_999 + ".") shouldBe None
    val ms = (java.lang.System.nanoTime() - start) / 1_000_000L
    ms should be < 500L
    ResourceTemplatePattern("x://{name}.{ext}").matches("x://archive.tar.gz") shouldBe Some(
      Map("name" -> "archive.tar", "ext" -> "gz")
    )
  }
