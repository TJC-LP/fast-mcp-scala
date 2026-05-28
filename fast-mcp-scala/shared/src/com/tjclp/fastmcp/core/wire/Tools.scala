package com.tjclp.fastmcp.core.wire

import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.{ToolAnnotations, ToolInputSchema}
import com.tjclp.fastmcp.core.ToolExecution

/** Wire shape of a tool descriptor as returned in `tools/list` responses.
  *
  * Distinct from [[com.tjclp.fastmcp.core.ToolDefinition]] (the user-facing registration shape).
  * The dispatcher converts ToolDefinition → Tool when assembling the `tools/list` payload.
  *
  *   - `inputSchema` is the JSON Schema object literal directly on the wire — codecs translate
  *     between the opaque-string [[ToolInputSchema]] and the embedded object at the serialization
  *     boundary (M3).
  *   - `outputSchema` is optional, only present when the tool returns structured content.
  *   - `execution.taskSupport` is set when the tool's `effectiveTaskSupport ≠ Forbidden` and the
  *     server is task-enabled.
  */
case class Tool(
    name: String,
    inputSchema: ToolInputSchema,
    title: Option[String] = None,
    description: Option[String] = None,
    outputSchema: Option[ToolOutputSchema] = None,
    annotations: Option[ToolAnnotations] = None,
    execution: Option[ToolExecution] = None,
    icons: Option[List[Icon]] = None,
    _meta: Option[Map[String, Json]] = None
)

/** Optional JSON Schema describing the tool's structured output. Stored as an opaque string for
  * the same reasons [[ToolInputSchema]] is — schema authoring lives outside the type system.
  */
opaque type ToolOutputSchema = String

object ToolOutputSchema:
  def fromJsonString(schema: String): Either[String, ToolOutputSchema] =
    schema.fromJson[Json].map(_ => schema)

  def unsafeFromJsonString(schema: String): ToolOutputSchema =
    fromJsonString(schema).fold(
      error => throw new IllegalArgumentException(s"Invalid tool output schema JSON: $error"),
      identity
    )

  def fromAst(schema: Json): ToolOutputSchema = schema.toJson

extension (schema: ToolOutputSchema)
  def toJsonString: String = schema
  def toAst: Json =
    schema.fromJson[Json].fold(
      error =>
        throw new IllegalStateException(s"Stored tool output schema is invalid JSON: $error"),
      identity
    )
