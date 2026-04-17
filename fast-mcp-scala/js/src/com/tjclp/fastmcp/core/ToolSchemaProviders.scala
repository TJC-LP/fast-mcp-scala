package com.tjclp.fastmcp
package core

import com.tjclp.fastmcp.macros.JsonSchemaMacro

/** Scala.js-side `ToolSchemaProvider` derivation.
  *
  * Uses the same compile-time `JsonSchemaMacro` as the JVM backend, so `McpTool.derived[...]` can
  * auto-generate input schemas on JS as long as the call site imports `sttp.tapir.generic.auto.*`
  * for Tapir schema derivation.
  */
object JsToolSchemaProviders:

  given ToolSchemaProvider[Unit] with

    val inputSchema: ToolInputSchema =
      ToolInputSchema.unsafeFromJsonString(
        """{"type":"object","properties":{},"additionalProperties":false}"""
      )

  inline given [A]: ToolSchemaProvider[A] =
    ToolSchemaProvider.instance(
      ToolInputSchema.unsafeFromJsonString(JsonSchemaMacro.schemaForType[A].spaces2)
    )
