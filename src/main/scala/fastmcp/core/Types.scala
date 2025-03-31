package fastmcp.core

import zio.json.*
import io.modelcontextprotocol.spec.McpSchema // Import Java MCP Schema

import scala.jdk.CollectionConverters.* // For Java/Scala collection conversions

// Define basic types mirroring MCP Schema

// --- Tool Related Types ---
// For now we'll use a simpler ToolExample representation
case class ToolExample(
  name: Option[String],
  description: Option[String]
)

object ToolExample:
  given JsonEncoder[ToolExample] = DeriveJsonEncoder.gen[ToolExample]
  given JsonDecoder[ToolExample] = DeriveJsonDecoder.gen[ToolExample]

// Enhanced tool definition with more metadata
case class ToolDefinition(
    name: String,
    description: Option[String],
    inputSchema: Either[McpSchema.JsonSchema, String], // Can be either MCP's JsonSchema or a string schema
    version: Option[String] = None,
    examples: List[ToolExample] = List.empty,
    deprecated: Boolean = false,
    deprecationMessage: Option[String] = None,
    tags: List[String] = List.empty,
    timeoutMillis: Option[Long] = None
)

object ToolDefinition:
  
  // Helper to convert to Java SDK Tool
  def toJava(td: ToolDefinition): McpSchema.Tool =
    val tool = td.inputSchema match {
      case Left(mcpSchema) => 
        // Directly use McpSchema.JsonSchema
        new McpSchema.Tool(
          td.name, 
          td.description.orNull, 
          mcpSchema
        )
      case Right(stringSchema) => 
        // Use string schema - MCP SDK will parse it
        new McpSchema.Tool(
          td.name, 
          td.description.orNull, 
          stringSchema
        )
    }
    
    // Add any additional properties via setters if needed
    // (will depend on future Java SDK enhancements)
    
    tool


// --- Resource Related Types ---
case class ResourceDefinition(
    uri: String,
    name: Option[String],
    description: Option[String],
    mimeType: Option[String] = Some("text/plain")
    // annotations: Option[McpSchema.Annotations] = None // Add later if needed
)

object ResourceDefinition:
 // Helper to convert to Java SDK Resource
  def toJava(rd: ResourceDefinition): McpSchema.Resource =
    new McpSchema.Resource(
      rd.uri,
      rd.name.orNull,
      rd.description.orNull,
      rd.mimeType.orNull,
      null // annotations placeholder
    )


// --- Prompt Related Types ---
case class PromptArgument(
    name: String,
    description: Option[String],
    required: Boolean = false
)
object PromptArgument:
  given JsonCodec[PromptArgument] = DeriveJsonCodec.gen[PromptArgument]
  // Helper to convert to Java SDK PromptArgument
  def toJava(pa: PromptArgument): McpSchema.PromptArgument =
    new McpSchema.PromptArgument(pa.name, pa.description.orNull, pa.required)

case class PromptDefinition(
    name: String,
    description: Option[String],
    arguments: Option[List[PromptArgument]]
)
object PromptDefinition:
  // Helper to convert to Java SDK Prompt
  def toJava(pd: PromptDefinition): McpSchema.Prompt =
    val javaArgs = pd.arguments.map(_.map(PromptArgument.toJava).asJava).orNull
    new McpSchema.Prompt(pd.name, pd.description.orNull, javaArgs)


// --- Content Types ---
// Use sealed trait for ADT pattern, enabling exhaustive matching
sealed trait Content(`type`: String):
  def toJava: McpSchema.Content // Abstract method to convert to Java SDK type

object Content:
  // Define codecs for subtypes first
  given JsonCodec[TextContent] = DeriveJsonCodec.gen[TextContent]
  given JsonCodec[ImageContent] = DeriveJsonCodec.gen[ImageContent]
  given JsonCodec[EmbeddedResourceContent] = DeriveJsonCodec.gen[EmbeddedResourceContent] // Codec for the inner part
  given JsonCodec[EmbeddedResource] = DeriveJsonCodec.gen[EmbeddedResource]

  // Derive codec for the sealed trait
  given JsonCodec[Content] = DeriveJsonCodec.gen[Content]

case class TextContent(
    text: String,
    audience: Option[List[Role]] = None,
    priority: Option[Double] = None
) extends Content("text"):
  override def toJava: McpSchema.TextContent =
    new McpSchema.TextContent(
      audience.map(roles => roles.map(Role.toJava).asJava).orNull, 
      priority.map(Double.box).orNull, 
      text
    )

case class ImageContent(
    data: String, // Base64 encoded image data
    mimeType: String,
    audience: Option[List[Role]] = None,
    priority: Option[Double] = None
) extends Content("image"):
  override def toJava: McpSchema.ImageContent =
    new McpSchema.ImageContent(
      audience.map(roles => roles.map(Role.toJava).asJava).orNull, 
      priority.map(Double.box).orNull, 
      data, 
      mimeType
    )

// Represents resource content embedded within a message or tool result
case class EmbeddedResourceContent(
    uri: String,
    mimeType: String,
    text: Option[String] = None, // For text resources
    blob: Option[String] = None  // For binary resources (Base64 encoded)
):
  def toJava: McpSchema.ResourceContents =
    if text.isDefined then
      new McpSchema.TextResourceContents(uri, mimeType, text.get)
    else if blob.isDefined then
      new McpSchema.BlobResourceContents(uri, mimeType, blob.get)
    else // Should ideally not happen if validated properly
      throw new IllegalArgumentException(s"EmbeddedResourceContent for $uri must have text or blob")

case class EmbeddedResource(
    resource: EmbeddedResourceContent,
    audience: Option[List[Role]] = None,
    priority: Option[Double] = None
) extends Content("resource"):
  override def toJava: McpSchema.EmbeddedResource =
    new McpSchema.EmbeddedResource(
      audience.map(roles => roles.map(Role.toJava).asJava).orNull, 
      priority.map(Double.box).orNull, 
      resource.toJava
    )


// --- Message Types ---
// Represents the role in a conversation (user or assistant)
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
  // Helper to convert to Java SDK Role
  def toJava(r: Role): McpSchema.Role = r match {
    case Role.User => McpSchema.Role.USER
    case Role.Assistant => McpSchema.Role.ASSISTANT
  }

case class Message(
    role: Role,
    content: Content
)
object Message:
  given JsonCodec[Message] = DeriveJsonCodec.gen[Message]
  // Helper to convert to Java SDK PromptMessage
  def toJava(m: Message): McpSchema.PromptMessage =
    new McpSchema.PromptMessage(Role.toJava(m.role), m.content.toJava)