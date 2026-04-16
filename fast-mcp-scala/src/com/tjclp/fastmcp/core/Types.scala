package com.tjclp.fastmcp.core

import scala.jdk.CollectionConverters.*

import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.spec.McpSchema

private[fastmcp] object JvmToolInputSchemaSupport:
  private val jsonMapper = McpJsonDefaults.getMapper()

  def fromJavaSchema(schema: McpSchema.JsonSchema): ToolInputSchema =
    ToolInputSchema.unsafeFromJsonString(jsonMapper.writeValueAsString(schema))

  def fromEither(schema: Either[McpSchema.JsonSchema, String]): ToolInputSchema =
    schema match
      case Left(javaSchema) => fromJavaSchema(javaSchema)
      case Right(json) => ToolInputSchema.unsafeFromJsonString(json)

/** JVM-only toJava extension methods for shared MCP types. These are internal to fast-mcp-scala and
  * not part of the user-facing DSL.
  */
@SuppressWarnings(Array("org.wartremover.warts.Null"))
private[fastmcp] object TypeConversions:

  extension (ta: ToolAnnotations)

    def toJava: McpSchema.ToolAnnotations =
      new McpSchema.ToolAnnotations(
        ta.title.orNull,
        ta.readOnlyHint.map(Boolean.box).orNull,
        ta.destructiveHint.map(Boolean.box).orNull,
        ta.idempotentHint.map(Boolean.box).orNull,
        ta.openWorldHint.map(Boolean.box).orNull,
        ta.returnDirect.map(Boolean.box).orNull
      )

  extension (td: ToolDefinition)

    def toJava: McpSchema.Tool =
      val baseToolBuilder =
        McpSchema.Tool.Builder().name(td.name).description(td.description.orNull)

      td.annotations.foreach { ta =>
        ta.title.foreach(t => baseToolBuilder.title(t))
        baseToolBuilder.annotations(ta.toJava)
      }

      val jsonMapper = McpJsonDefaults.getMapper()
      val jsonSchema =
        jsonMapper.readValue(td.inputSchema.toJsonString, classOf[McpSchema.JsonSchema])
      baseToolBuilder.inputSchema(jsonSchema).build()

  extension (pa: PromptArgument)

    def toJava: McpSchema.PromptArgument =
      new McpSchema.PromptArgument(pa.name, pa.description.orNull, pa.required)

  extension (pd: PromptDefinition)

    def toJava: McpSchema.Prompt =
      val javaArgs = pd.arguments.map(_.map(_.toJava).asJava).orNull
      new McpSchema.Prompt(pd.name, pd.description.orNull, javaArgs)

  extension (c: Content)

    def toJava: McpSchema.Content = c match
      case tc: TextContent =>
        val ann = new McpSchema.Annotations(
          tc.audience.map(roles => roles.map(_.toJava).asJava).orNull,
          tc.priority.map(Double.box).orNull
        )
        new McpSchema.TextContent(ann, tc.text)
      case ic: ImageContent =>
        val ann = new McpSchema.Annotations(
          ic.audience.map(roles => roles.map(_.toJava).asJava).orNull,
          ic.priority.map(Double.box).orNull
        )
        new McpSchema.ImageContent(ann, ic.data, ic.mimeType)
      case er: EmbeddedResource =>
        val ann = new McpSchema.Annotations(
          er.audience.map(roles => roles.map(_.toJava).asJava).orNull,
          er.priority.map(Double.box).orNull
        )
        new McpSchema.EmbeddedResource(ann, er.resource.toJava)

  extension (erc: EmbeddedResourceContent)

    def toJava: McpSchema.ResourceContents =
      if erc.text.isDefined then
        new McpSchema.TextResourceContents(erc.uri, erc.mimeType, erc.text.get)
      else if erc.blob.isDefined then
        new McpSchema.BlobResourceContents(erc.uri, erc.mimeType, erc.blob.get)
      else
        throw new IllegalArgumentException(
          s"EmbeddedResourceContent for ${erc.uri} must have text or blob"
        )

  extension (r: Role)

    def toJava: McpSchema.Role = r match
      case Role.User => McpSchema.Role.USER
      case Role.Assistant => McpSchema.Role.ASSISTANT

  extension (m: Message)

    def toJava: McpSchema.PromptMessage =
      new McpSchema.PromptMessage(m.role.toJava, m.content.toJava)
