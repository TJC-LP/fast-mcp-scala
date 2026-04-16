package com.tjclp.fastmcp.core

import io.modelcontextprotocol.spec.McpSchema
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.tjclp.fastmcp.core.TypeConversions.*

/** Separate test-suite dedicated to the ToolDefinition toJava conversion.
  */
@SuppressWarnings(Array("org.wartremover.warts.Null"))
class ToolDefinitionConversionTest extends AnyFlatSpec with Matchers {

  "ToolDefinition.toJava" should "convert a String schema" in {
    val schemaStr = """{ "type": "object" }"""

    val td = ToolDefinition(
      name = "td-string",
      description = None,
      inputSchema = schemaStr
    )

    val j = td.toJava

    j.name() shouldBe "td-string"
    j.description() shouldBe null
    j.inputSchema() shouldBe new McpSchema.JsonSchema("object", null, null, null, null, null)
  }

  it should "set annotations on the Java Tool when present" in {
    val td = ToolDefinition(
      name = "annotated-tool",
      description = Some("A tool with annotations"),
      inputSchema = """{ "type": "object" }""",
      annotations = Some(
        ToolAnnotations(
          title = Some("Annotated Tool"),
          readOnlyHint = Some(true),
          destructiveHint = Some(false)
        )
      )
    )
    val j = td.toJava
    j.name() shouldBe "annotated-tool"
    j.title() shouldBe "Annotated Tool"
    j.annotations() should not be null
    j.annotations().title() shouldBe "Annotated Tool"
    j.annotations().readOnlyHint() shouldBe java.lang.Boolean.TRUE
    j.annotations().destructiveHint() shouldBe java.lang.Boolean.FALSE
    j.annotations().idempotentHint() shouldBe null
  }

  it should "leave annotations null when not specified" in {
    val td = ToolDefinition(
      name = "no-annot-tool",
      description = None,
      inputSchema = """{ "type": "object" }"""
    )
    val j = td.toJava
    j.annotations() shouldBe null
    j.title() shouldBe null
  }

  "ToolAnnotations.toJava" should "convert all-specified annotations" in {
    val ta = ToolAnnotations(
      title = Some("My Tool"),
      readOnlyHint = Some(true),
      destructiveHint = Some(false),
      idempotentHint = Some(true),
      openWorldHint = Some(false),
      returnDirect = Some(true)
    )
    val j = ta.toJava
    j.title() shouldBe "My Tool"
    j.readOnlyHint() shouldBe java.lang.Boolean.TRUE
    j.destructiveHint() shouldBe java.lang.Boolean.FALSE
    j.idempotentHint() shouldBe java.lang.Boolean.TRUE
    j.openWorldHint() shouldBe java.lang.Boolean.FALSE
    j.returnDirect() shouldBe java.lang.Boolean.TRUE
  }

  it should "convert None values to null" in {
    val ta = ToolAnnotations()
    val j = ta.toJava
    j.title() shouldBe null
    j.readOnlyHint() shouldBe null
    j.destructiveHint() shouldBe null
    j.idempotentHint() shouldBe null
    j.openWorldHint() shouldBe null
    j.returnDirect() shouldBe null
  }
}
