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

  /** Derived for any `A` whose schema root is a JSON object (case classes, `Map[String, V]`, types
    * with a user `McpSchema`); a scalar / collection / `Option` / `Either` / enum `In` is a
    * compile-time error, because MCP `arguments` is always an object and such a tool could never be
    * called.
    */
  inline given [A]: ToolSchemaProvider[A] =
    ToolSchemaProvider.instance(
      ToolInputSchema.unsafeFromJsonString(JsonSchemaMacro.schemaForToolInput[A].toJson)
    )

  /** Output-schema derivation for `McpTool#withOutputSchema` — same native macro as input, same
    * object-root guard: `structuredContent` is always a JSON object, so `Out = String` / `Option` /
    * a collection / an enum is a compile-time error (wrap it in a case class); `Unit` derives the
    * empty object and `McpEncoder[Unit]` emits the conforming `{}`.
    */
  inline given [A]: ToolOutputSchemaProvider[A] =
    ToolOutputSchemaProvider.instance(
      wire.ToolOutputSchema.unsafeFromJsonString(JsonSchemaMacro.schemaForToolOutput[A].toJson)
    )
