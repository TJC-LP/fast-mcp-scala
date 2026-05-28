package com.tjclp.fastmcp.core

import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.wire.{Annotations, Icon, ResourceContents}

// --- Tool Annotations (MCP behavioral hints for clients) ---

case class ToolAnnotations(
    title: Option[String] = None,
    readOnlyHint: Option[Boolean] = None,
    destructiveHint: Option[Boolean] = None,
    idempotentHint: Option[Boolean] = None,
    openWorldHint: Option[Boolean] = None,
    returnDirect: Option[Boolean] = None
)

object ToolAnnotations:
  given JsonCodec[ToolAnnotations] = DeriveJsonCodec.gen[ToolAnnotations]

// --- Tool Related Types ---

case class ToolExample(
    name: Option[String],
    description: Option[String]
)

object ToolExample:
  given JsonEncoder[ToolExample] = DeriveJsonEncoder.gen[ToolExample]
  given JsonDecoder[ToolExample] = DeriveJsonDecoder.gen[ToolExample]

opaque type ToolInputSchema = String

object ToolInputSchema:
  private val DefaultJson = """{"type":"object","additionalProperties":true}"""

  val default: ToolInputSchema = unsafeFromJsonString(DefaultJson)

  def derived[A](using provider: ToolSchemaProvider[A]): ToolInputSchema =
    provider.inputSchema

  def fromJsonString(schema: String): Either[String, ToolInputSchema] =
    schema.fromJson[Json].map(_ => schema)

  def unsafeFromJsonString(schema: String): ToolInputSchema =
    fromJsonString(schema).fold(
      error => throw new IllegalArgumentException(s"Invalid tool input schema JSON: $error"),
      identity
    )

  def fromAst(schema: Json): ToolInputSchema =
    schema.toJson

  /** Wire codec: on the JSON-RPC wire `inputSchema` is an embedded JSON Schema *object*, never a
    * string. We store it as an opaque string, so encode = parse-string-to-AST and decode =
    * serialize-AST-to-string. Inside this companion the opacity is transparent.
    */
  given JsonCodec[ToolInputSchema] = JsonCodec(
    JsonEncoder[Json].contramap[ToolInputSchema] { s =>
      s.fromJson[Json].getOrElse(Json.Obj())
    },
    JsonDecoder[Json].map[ToolInputSchema](_.toJson)
  )

extension (schema: ToolInputSchema)
  def toJsonString: String = schema

  def toAst: Json =
    schema
      .fromJson[Json]
      .fold(
        error =>
          throw new IllegalStateException(s"Stored tool input schema is invalid JSON: $error"),
        identity
      )

case class ToolDefinition(
    name: String,
    description: Option[String],
    inputSchema: ToolInputSchema = ToolInputSchema.default,
    version: Option[String] = None,
    examples: List[ToolExample] = List.empty,
    deprecated: Boolean = false,
    deprecationMessage: Option[String] = None,
    tags: List[String] = List.empty,
    timeoutMillis: Option[Long] = None,
    annotations: Option[ToolAnnotations] = None,
    taskSupport: Option[TaskSupport] = None
):

  /** Effective task-support resolution: `None` (unspecified) is treated as
    * [[TaskSupport.Forbidden]] per spec 2025-11-25 §"Tool-Level Negotiation".
    */
  def effectiveTaskSupport: TaskSupport = taskSupport.getOrElse(TaskSupport.Forbidden)

// --- Prompt Related Types ---

case class PromptArgument(
    name: String,
    description: Option[String],
    required: Boolean = false
)

object PromptArgument:
  given JsonCodec[PromptArgument] = DeriveJsonCodec.gen[PromptArgument]

case class PromptDefinition(
    name: String,
    description: Option[String],
    arguments: Option[List[PromptArgument]]
)

// --- Role (used by Content, Annotations, Message) ---

enum Role:
  case User, Assistant

object Role:

  given JsonCodec[Role] = JsonCodec.string.transformOrFail(
    {
      case s if s.equalsIgnoreCase("user") => Right(Role.User)
      case s if s.equalsIgnoreCase("assistant") => Right(Role.Assistant)
      case s => Left(s"Invalid role: $s")
    },
    _.toString.toLowerCase
  )

// --- Content ADT (MCP 2025-11-25) ---
//
// Wire shape per spec: discriminated by `"type"` field. Variants:
//   "text"          → TextContent
//   "image"         → ImageContent
//   "audio"         → AudioContent
//   "resource_link" → ResourceLink (a Resource embedded as content with a `type` tag)
//   "resource"      → EmbeddedResource (full resource contents inline)
//
// Each variant carries optional [[Annotations]] (audience/priority/lastModified) and `_meta`.
// `_meta` is `Option[Map[String, Json]]` so absent ≠ null on the wire.
//
// IMPORTANT: zio-json's `@jsonDiscriminator` keys off each subtype's `@jsonHint`, NOT the
// `extends Content("text")` constructor arg (that arg is inert for serialization). Without the
// hints the wire tag would be the class name (`TextContent`) instead of the spec value (`text`).
// In the previous Jackson/Java-SDK architecture this codec never touched the wire, so the bug was
// latent; native dispatch makes it live.

@jsonDiscriminator("type")
sealed trait Content(@scala.annotation.unused `type`: String)

object Content:
  given JsonCodec[Content] = DeriveJsonCodec.gen[Content]

@jsonHint("text")
case class TextContent(
    text: String,
    annotations: Option[Annotations] = None,
    _meta: Option[Map[String, Json]] = None
) extends Content("text")

object TextContent:
  given JsonCodec[TextContent] = DeriveJsonCodec.gen[TextContent]

@jsonHint("image")
case class ImageContent(
    data: String,
    mimeType: String,
    annotations: Option[Annotations] = None,
    _meta: Option[Map[String, Json]] = None
) extends Content("image")

object ImageContent:
  given JsonCodec[ImageContent] = DeriveJsonCodec.gen[ImageContent]

@jsonHint("audio")
case class AudioContent(
    data: String,
    mimeType: String,
    annotations: Option[Annotations] = None,
    _meta: Option[Map[String, Json]] = None
) extends Content("audio")

object AudioContent:
  given JsonCodec[AudioContent] = DeriveJsonCodec.gen[AudioContent]

/** A resource the server can read, included inline as content. Per spec 2025-11-25 §Content, this
  * is a `Resource` plus a `type: "resource_link"` discriminator. Wire shape matches the full
  * [[Resource]] fields.
  */
@jsonHint("resource_link")
case class ResourceLink(
    uri: String,
    name: String,
    title: Option[String] = None,
    description: Option[String] = None,
    mimeType: Option[String] = None,
    annotations: Option[Annotations] = None,
    size: Option[Long] = None,
    icons: Option[List[Icon]] = None,
    _meta: Option[Map[String, Json]] = None
) extends Content("resource_link")

object ResourceLink:
  given JsonCodec[ResourceLink] = DeriveJsonCodec.gen[ResourceLink]

@jsonHint("resource")
case class EmbeddedResource(
    resource: ResourceContents,
    annotations: Option[Annotations] = None,
    _meta: Option[Map[String, Json]] = None
) extends Content("resource")

object EmbeddedResource:
  given JsonCodec[EmbeddedResource] = DeriveJsonCodec.gen[EmbeddedResource]

// --- Message Types ---

case class Message(
    role: Role,
    content: Content
)

object Message:
  given JsonCodec[Message] = DeriveJsonCodec.gen[Message]
