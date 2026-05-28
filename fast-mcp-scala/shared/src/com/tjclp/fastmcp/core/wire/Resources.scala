package com.tjclp.fastmcp.core.wire

import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.Role

/** Spec-defined annotations attached to resources and content blocks. Distinct from the macro
  * annotation set in `Annotations.scala` (`@Tool` / `@Param` / etc.) — this is a wire shape
  * carrying client-facing hints.
  *
  * Field semantics per MCP 2025-11-25:
  *   - `audience` lists who the content is for (clients use it to filter what reaches the model).
  *   - `priority` is `0.0 .. 1.0` — clients use it to decide what to surface under context pressure.
  *   - `lastModified` is an ISO 8601 timestamp for staleness display.
  */
case class Annotations(
    audience: Option[List[Role]] = None,
    priority: Option[Double] = None,
    lastModified: Option[String] = None
)

object Annotations:
  given JsonCodec[Annotations] = DeriveJsonCodec.gen[Annotations]

/** Wire shape for a resource the server exposes (returned in `resources/list`). Distinct from
  * [[ResourceDefinition]] (the user-facing registration type in `Contracts.scala`) — this is what
  * goes on the JSON-RPC wire.
  */
case class Resource(
    uri: String,
    name: String,
    title: Option[String] = None,
    description: Option[String] = None,
    mimeType: Option[String] = None,
    annotations: Option[Annotations] = None,
    size: Option[Long] = None,
    icons: Option[List[Icon]] = None,
    _meta: Option[Map[String, Json]] = None
)

object Resource:
  given JsonCodec[Resource] = DeriveJsonCodec.gen[Resource]

/** Wire shape for a resource template (returned in `resources/templates/list`). */
case class ResourceTemplate(
    uriTemplate: String,
    name: String,
    title: Option[String] = None,
    description: Option[String] = None,
    mimeType: Option[String] = None,
    annotations: Option[Annotations] = None,
    icons: Option[List[Icon]] = None,
    _meta: Option[Map[String, Json]] = None
)

object ResourceTemplate:
  given JsonCodec[ResourceTemplate] = DeriveJsonCodec.gen[ResourceTemplate]

/** Contents of a specific resource or sub-resource as returned by `resources/read`. Either text
  * (UTF-8 source) or blob (base64-encoded binary).
  *
  * Spec models this as `TextResourceContents | BlobResourceContents` — a sum type discriminated
  * by which of `text` / `blob` is present (not by a `type` field). Wire codecs hand-rolled in M3
  * will handle that discrimination.
  */
sealed trait ResourceContents:
  def uri: String
  def mimeType: Option[String]
  def `_meta`: Option[Map[String, Json]]

case class TextResourceContents(
    uri: String,
    text: String,
    mimeType: Option[String] = None,
    _meta: Option[Map[String, Json]] = None
) extends ResourceContents

object TextResourceContents:
  given JsonCodec[TextResourceContents] = DeriveJsonCodec.gen[TextResourceContents]

case class BlobResourceContents(
    uri: String,
    blob: String,
    mimeType: Option[String] = None,
    _meta: Option[Map[String, Json]] = None
) extends ResourceContents

object BlobResourceContents:
  given JsonCodec[BlobResourceContents] = DeriveJsonCodec.gen[BlobResourceContents]

object ResourceContents:
  // Hand-rolled in M3: presence-of-`text` vs presence-of-`blob` discrimination. Placeholder
  // derivation here so the type is summon-able; M3 will replace it.
  given JsonEncoder[ResourceContents] = JsonEncoder[Json].contramap {
    case t: TextResourceContents => t.toJsonAST.toOption.getOrElse(Json.Obj())
    case b: BlobResourceContents => b.toJsonAST.toOption.getOrElse(Json.Obj())
  }

  given JsonDecoder[ResourceContents] = JsonDecoder[Json].mapOrFail {
    case obj: Json.Obj =>
      val fields = obj.fields.toMap
      if fields.contains("text") then obj.toString.fromJson[TextResourceContents]
      else if fields.contains("blob") then obj.toString.fromJson[BlobResourceContents]
      else Left("ResourceContents requires either `text` or `blob` field")
    case other => Left(s"ResourceContents must be a JSON object, got: $other")
  }
