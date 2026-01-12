package com.tjclp.fastmcp.core

import io.modelcontextprotocol.spec.McpSchema
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Separate test‑suite dedicated to the `ToolDefinition.toJava` helper.
  */
@SuppressWarnings(Array("org.wartremover.warts.Null"))
class ToolDefinitionConversionTest extends AnyFlatSpec with Matchers {

  "ToolDefinition.toJava" should "convert the Left(JsonSchema) case" in {
    // A minimal JSON schema – the Java SDK accepts the raw json string.
    val jsonSchema = new McpSchema.JsonSchema("object", null, null, true, null, null)

    val td = ToolDefinition(
      name = "td‑left",
      description = Some("desc"),
      inputSchema = Left(jsonSchema),
      version = Some("1.0.0"),
      examples = List.empty
    )

    val j = ToolDefinition.toJava(td)

    j.name() shouldBe "td‑left"
    j.description() shouldBe "desc"
    // The Java SDK represents the schema either as JsonSchema or String.
    j.inputSchema() shouldBe jsonSchema
  }

  it should "convert the Right(String) case" in {
    val schemaStr = """{ "type": "object" }"""

    val td = ToolDefinition(
      name = "td‑right",
      description = None,
      inputSchema = Right(schemaStr)
    )

    val j = ToolDefinition.toJava(td)

    j.name() shouldBe "td‑right"
    j.description() shouldBe null // description was None
    j.inputSchema() shouldBe new McpSchema.JsonSchema("object", null, null, null, null, null)
  }
}
