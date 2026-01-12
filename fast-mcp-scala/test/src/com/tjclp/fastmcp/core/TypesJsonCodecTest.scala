package com.tjclp.fastmcp.core

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.json.*

/** Round‑trip JSON codec tests for the core ADTs that rely on zio‑json.
  *
  * These tests focus purely on the (de)serialisation layer – they do **not** exercise the
  * Java‑interop helpers which are covered elsewhere.
  */
class TypesJsonCodecTest extends AnyFlatSpec with Matchers {

  "Role JsonCodec" should "encode and decode both roles in lowercase" in {
    val userJson = Role.User.toJson
    val assistantJson = Role.Assistant.toJson

    assert(userJson == "\"user\"")
    assert(assistantJson == "\"assistant\"")

    assert(userJson.fromJson[Role] == Right(Role.User))
    assert(assistantJson.fromJson[Role] == Right(Role.Assistant))
  }

  it should "fail to decode an invalid role string" in {
    assert("\"moderator\"".fromJson[Role].isLeft)
  }

  "TextContent JsonCodec" should "round‑trip TextContent" in {
    val original = TextContent("hello", Some(List(Role.User)), Some(1.2))
    val json = original.toJson
    val decoded = json.fromJson[TextContent]

    assert(decoded == Right(original))
  }

  "ImageContent JsonCodec" should "round‑trip ImageContent" in {
    val original = ImageContent("data64", "image/png", None, None)
    val json = original.toJson
    val decoded = json.fromJson[ImageContent]

    assert(decoded == Right(original))
  }

  "EmbeddedResource JsonCodec" should "round‑trip EmbeddedResource" in {
    val erc = EmbeddedResourceContent("/uri", "text/plain", text = Some("body"), blob = None)
    val original = EmbeddedResource(erc, None, None)
    val json = original.toJson
    val decoded = json.fromJson[EmbeddedResource]

    assert(decoded == Right(original))
  }

  "Message JsonCodec" should "round‑trip messages" in {
    val msg = Message(Role.Assistant, TextContent("hello"))
    assert(msg.toJson.fromJson[Message] == Right(msg))
  }

  "PromptArgument JsonCodec" should "round‑trip" in {
    val arg = PromptArgument("name", Some("desc"), required = true)
    assert(arg.toJson.fromJson[PromptArgument] == Right(arg))
  }
}
