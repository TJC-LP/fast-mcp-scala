package com.tjclp.fastmcp
package codec

import org.scalatest.funsuite.AnyFunSuite
import zio.json.*

import com.tjclp.fastmcp.core.{Content, McpDecoder, McpEncoder, TextContent}

class JsMcpDecodeContextTest extends AnyFunSuite:

  import JsMcpDecoders.given

  case class Add(a: Int, b: Int) derives JsonDecoder
  case class User(id: String, profile: UserProfile) derives JsonDecoder
  case class UserProfile(name: String, age: Int) derives JsonDecoder

  private val ctx = JsMcpDecodeContext.default

  test("writeValueAsString round-trips a Scala Map via js.JSON") {
    val map = Map("a" -> 2, "b" -> 3)
    val json = ctx.writeValueAsString(map)
    val parsed = ctx.parseJsonObject("x", json)
    assert(parsed("a") == 2)
    assert(parsed("b") == 3)
  }

  test("zioJsonDecoder decodes a Map into a typed case class") {
    val args: Map[String, Any] = Map("a" -> 2, "b" -> 3)
    val decoded = McpDecoder[Add].decode("add", args, ctx)
    assert(decoded == Add(2, 3))
  }

  test("zioJsonDecoder decodes nested structures") {
    val args: Map[String, Any] =
      Map("id" -> "u1", "profile" -> Map("name" -> "Ada", "age" -> 36))
    val decoded = McpDecoder[User].decode("get_user", args, ctx)
    assert(decoded == User("u1", UserProfile("Ada", 36)))
  }

  test("zioJsonDecoder surfaces a descriptive error for bad input") {
    val ex = intercept[RuntimeException](
      McpDecoder[Add].decode("add", Map("a" -> "not-a-number", "b" -> 3), ctx)
    )
    assert(ex.getMessage.contains("Failed to decode parameter 'add'"))
  }

  test("McpEncoder[Array[Byte]] emits a single base64 TextContent") {
    val payload: Array[Byte] = "Hello".getBytes("UTF-8")
    val encoded: List[Content] = McpEncoder[Array[Byte]].encode(payload)
    assert(encoded.size == 1)
    encoded.head match
      case tc: TextContent => assert(tc.text == "SGVsbG8=")
      case other => fail(s"Unexpected content: $other")
  }
