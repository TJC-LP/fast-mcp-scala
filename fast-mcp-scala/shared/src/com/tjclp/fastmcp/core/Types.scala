package com.tjclp.fastmcp.core

import zio.json.*

// --- Tool Annotations (MCP behavioral hints for clients) ---

case class ToolAnnotations(
    title: Option[String] = None,
    readOnlyHint: Option[Boolean] = None,
    destructiveHint: Option[Boolean] = None,
    idempotentHint: Option[Boolean] = None,
    openWorldHint: Option[Boolean] = None,
    returnDirect: Option[Boolean] = None
)

// --- Tool Related Types ---

case class ToolExample(
    name: Option[String],
    description: Option[String]
)

object ToolExample:
  given JsonEncoder[ToolExample] = DeriveJsonEncoder.gen[ToolExample]
  given JsonDecoder[ToolExample] = DeriveJsonDecoder.gen[ToolExample]

case class ToolDefinition(
    name: String,
    description: Option[String],
    inputSchema: String = """{"type":"object","additionalProperties":true}""",
    version: Option[String] = None,
    examples: List[ToolExample] = List.empty,
    deprecated: Boolean = false,
    deprecationMessage: Option[String] = None,
    tags: List[String] = List.empty,
    timeoutMillis: Option[Long] = None,
    annotations: Option[ToolAnnotations] = None
)

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

// --- Content Types ---

@jsonDiscriminator("type")
sealed trait Content(@scala.annotation.unused `type`: String)

object Content:
  given JsonCodec[Content] = DeriveJsonCodec.gen[Content]

case class TextContent(
    text: String,
    audience: Option[List[Role]] = None,
    priority: Option[Double] = None
) extends Content("text")

object TextContent:
  given JsonCodec[TextContent] = DeriveJsonCodec.gen[TextContent]

case class ImageContent(
    data: String,
    mimeType: String,
    audience: Option[List[Role]] = None,
    priority: Option[Double] = None
) extends Content("image")

object ImageContent:
  given JsonCodec[ImageContent] = DeriveJsonCodec.gen[ImageContent]

case class EmbeddedResourceContent(
    uri: String,
    mimeType: String,
    text: Option[String] = None,
    blob: Option[String] = None
)

object EmbeddedResourceContent:
  given JsonCodec[EmbeddedResourceContent] = DeriveJsonCodec.gen[EmbeddedResourceContent]

case class EmbeddedResource(
    resource: EmbeddedResourceContent,
    audience: Option[List[Role]] = None,
    priority: Option[Double] = None
) extends Content("resource")

object EmbeddedResource:
  given JsonCodec[EmbeddedResource] = DeriveJsonCodec.gen[EmbeddedResource]

// --- Message Types ---

enum Role:
  case User, Assistant

object Role:
  given JsonCodec[Role] = JsonCodec.string.transformOrFail(
    {
      case s if s.equalsIgnoreCase("user")      => Right(Role.User)
      case s if s.equalsIgnoreCase("assistant") => Right(Role.Assistant)
      case s                                    => Left(s"Invalid role: $s")
    },
    _.toString.toLowerCase
  )

case class Message(
    role: Role,
    content: Content
)

object Message:
  given JsonCodec[Message] = DeriveJsonCodec.gen[Message]
