package com.tjclp.fastmcp
package core

import com.tjclp.fastmcp.macros.JsonSchemaMacro

/** The single, platform-neutral `ToolSchemaProvider` derivation.
  *
  * Promoted to `shared/` from the (identical) old `JvmToolSchemaProviders` / `JsToolSchemaProviders`
  * — both already drove the same compile-time [[JsonSchemaMacro]] (Tapir-backed), so one copy
  * serves JVM and Scala.js. `McpTool.derived[...]` auto-generates input schemas as long as the call
  * site has Tapir schema derivation in scope (`import sttp.tapir.generic.auto.*`).
  */
object ToolSchemaProviders:

  given ToolSchemaProvider[Unit] with
    val inputSchema: ToolInputSchema =
      ToolInputSchema.unsafeFromJsonString(
        """{"type":"object","properties":{},"additionalProperties":false}"""
      )

  inline given [A]: ToolSchemaProvider[A] =
    ToolSchemaProvider.instance(
      ToolInputSchema.unsafeFromJsonString(JsonSchemaMacro.schemaForType[A].spaces2)
    )
