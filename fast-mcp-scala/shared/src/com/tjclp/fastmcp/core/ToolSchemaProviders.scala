package com.tjclp.fastmcp
package core

import zio.json.*

import com.tjclp.fastmcp.macros.JsonSchemaMacro

/** The single, platform-neutral `ToolSchemaProvider` derivation.
  *
  * [[JsonSchemaMacro]] derives schemas directly from Scala 3 types on both JVM and Scala.js. No
  * schema-library import is required at the call site.
  */
object ToolSchemaProviders:

  given ToolSchemaProvider[Unit] with

    val inputSchema: ToolInputSchema =
      ToolInputSchema.unsafeFromJsonString(
        """{"type":"object","properties":{},"additionalProperties":false}"""
      )

  inline given [A]: ToolSchemaProvider[A] =
    ToolSchemaProvider.instance(
      ToolInputSchema.unsafeFromJsonString(JsonSchemaMacro.schemaForType[A].toJson)
    )

  /** Output-schema derivation for `McpTool#withOutputSchema` — same native macro as input. */
  inline given [A]: ToolOutputSchemaProvider[A] =
    ToolOutputSchemaProvider.instance(
      wire.ToolOutputSchema.unsafeFromJsonString(JsonSchemaMacro.schemaForType[A].toJson)
    )
