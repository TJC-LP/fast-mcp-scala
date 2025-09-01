package com.tjclp.fastmcp.core

import io.modelcontextprotocol.spec.McpSchema
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters.*

/** Tests for conversions from Scala core ADTs to Java MCP Schema types.
  */
class TypesConversionTest extends AnyFlatSpec with Matchers {

  "Role.toJava" should "map Scala Role.User to Java Role.USER" in {
    val j = Role.toJava(Role.User)
    j shouldBe McpSchema.Role.USER
  }

  it should "map Scala Role.Assistant to Java Role.ASSISTANT" in {
    Role.toJava(Role.Assistant) shouldBe McpSchema.Role.ASSISTANT
  }

  "TextContent.toJava" should "populate text, audience, and priority fields" in {
    val content = TextContent("hello", Some(List(Role.User, Role.Assistant)), Some(2.5))
    val j = content.toJava
    j shouldBe a[McpSchema.TextContent]
    val jt = j.asInstanceOf[McpSchema.TextContent]
    jt.text() shouldBe "hello"
    jt.audience().asScala.map(_.toString.toLowerCase) should contain allOf ("user", "assistant")
    jt.priority() shouldBe Double.box(2.5)
  }

  it should "set null for optional audience and priority when None" in {
    val content = TextContent("hi")
    val j = content.toJava
    val jt = j.asInstanceOf[McpSchema.TextContent]
    jt.text() shouldBe "hi"
    jt.audience() shouldBe null
    jt.priority() shouldBe null
  }

  "ImageContent.toJava" should "populate data, mimeType, audience, and priority fields" in {
    val content = ImageContent("data64", "image/png", Some(List(Role.User)), Some(0.5))
    val j = content.toJava
    val ji = j.asInstanceOf[McpSchema.ImageContent]
    ji.data() shouldBe "data64"
    ji.mimeType() shouldBe "image/png"
    ji.audience().asScala.map(_.toString.toLowerCase) should contain("user")
    ji.priority() shouldBe Double.box(0.5)
  }

  "EmbeddedResourceContent.toJava" should "produce TextResourceContents when text is defined" in {
    val erc = EmbeddedResourceContent("/u", "text/plain", text = Some("body"), blob = None)
    val j = erc.toJava
    val jt = j.asInstanceOf[McpSchema.TextResourceContents]
    jt.uri() shouldBe "/u"
    jt.mimeType() shouldBe "text/plain"
    jt.text() shouldBe "body"
  }

  it should "produce BlobResourceContents when blob is defined" in {
    val erc =
      EmbeddedResourceContent("/u2", "application/octet-stream", text = None, blob = Some("b64"))
    val j = erc.toJava
    val jb = j.asInstanceOf[McpSchema.BlobResourceContents]
    jb.uri() shouldBe "/u2"
    jb.mimeType() shouldBe "application/octet-stream"
    jb.blob() shouldBe "b64"
  }

  it should "throw IllegalArgumentException when neither text nor blob is defined" in {
    val erc = EmbeddedResourceContent("/bad", "application/json", text = None, blob = None)
    a[IllegalArgumentException] should be thrownBy erc.toJava
  }

  "EmbeddedResource.toJava" should "wrap ResourceContents correctly" in {
    val inner = EmbeddedResourceContent("/resource", "text/plain", text = Some("foo"), blob = None)
    val er = EmbeddedResource(inner, Some(List(Role.Assistant)), Some(1.0))
    val j = er.toJava
    val je = j.asInstanceOf[McpSchema.EmbeddedResource]
    je.resource() shouldBe a[McpSchema.TextResourceContents]
    je.audience().asScala.map(_.toString.toLowerCase) should contain("assistant")
    je.priority() shouldBe Double.box(1.0)
  }

  "PromptDefinition.toJava" should "convert name, description, and empty arguments when None" in {
    val pd = PromptDefinition("p1", Some("desc"), None)
    val j = PromptDefinition.toJava(pd)
    j.getClass.getSimpleName should include("Prompt")
    j.name() shouldBe "p1"
    j.description() shouldBe "desc"
    val args = j.arguments()
    assert(args == null || args.isEmpty, "arguments should be null or empty when None")
  }

  it should "convert arguments list to Java list when defined" in {
    val arg = PromptArgument("a", Some("d"), required = true)
    val pd = PromptDefinition("p2", None, Some(List(arg)))
    val j = PromptDefinition.toJava(pd)
    j.name() shouldBe "p2"
    j.description() shouldBe null
    val args = j.arguments()
    args should not be null
    val list = args.asScala
    list should have size 1
    val ja = list.head.asInstanceOf[McpSchema.PromptArgument]
    ja.name() shouldBe "a"
    ja.description() shouldBe "d"
    ja.required() shouldBe true
  }

  "Message.toJava" should "convert Scala Message to Java PromptMessage" in {
    val scalaMsg = Message(Role.User, TextContent("hi"))
    val javaMsg = Message.toJava(scalaMsg)

    javaMsg.role() shouldBe McpSchema.Role.USER
    javaMsg.content() shouldBe a[McpSchema.TextContent]
    val jt = javaMsg.content().asInstanceOf[McpSchema.TextContent]
    jt.text() shouldBe "hi"
  }
}
