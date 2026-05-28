package com.tjclp.fastmcp.server.router

import java.util.Base64

import zio.json.ast.Json

import com.tjclp.fastmcp.core.{
  Content,
  ImageContent,
  Message,
  PromptDefinition,
  ResourceDefinition,
  TextContent,
  ToolDefinition,
  ToolExecution,
  TaskSupport
}
import com.tjclp.fastmcp.core.wire.*

/** Pure functions mapping the user-facing registration types (held by the managers) into the
  * 2025-11-25 wire shapes the built-in handlers return. No effects, no platform specifics.
  */
object WireMapping:

  /** [[ToolDefinition]] → wire [[Tool]]. `execution.taskSupport` is emitted only when the server
    * is task-enabled and the tool opts in (≠ Forbidden).
    */
  def toolToWire(d: ToolDefinition, tasksEnabled: Boolean): Tool =
    val exec =
      if tasksEnabled && d.effectiveTaskSupport != TaskSupport.Forbidden then
        Some(ToolExecution(Some(d.effectiveTaskSupport)))
      else None
    Tool(
      name = d.name,
      inputSchema = d.inputSchema,
      title = d.annotations.flatMap(_.title),
      description = d.description,
      annotations = d.annotations,
      execution = exec
    )

  /** [[ResourceDefinition]] → wire [[Resource]] (static) — used for `resources/list`. */
  def resourceToWire(d: ResourceDefinition): Resource =
    Resource(
      uri = d.uri,
      name = d.name.getOrElse(d.uri),
      description = d.description,
      mimeType = d.mimeType
    )

  /** [[ResourceDefinition]] → wire [[ResourceTemplate]] — used for `resources/templates/list`. */
  def templateToWire(d: ResourceDefinition): ResourceTemplate =
    ResourceTemplate(
      uriTemplate = d.uri,
      name = d.name.getOrElse(d.uri),
      description = d.description,
      mimeType = d.mimeType
    )

  /** [[PromptDefinition]] → wire [[Prompt]] — used for `prompts/list`. */
  def promptToWire(d: PromptDefinition): Prompt =
    Prompt(name = d.name, description = d.description, arguments = d.arguments)

  /** Convert a tool handler's untyped result into a [[CallToolResult]]. Ports the old
    * `FastMcpServer.transformToolResult`: a `String` becomes one `TextContent`, an `Array[Byte]`
    * a base64 image, a `Content` / `List[Content]` passes through, and anything else degrades to
    * its `toString`. Tool *failures* never reach here — those are mapped to `isError = true`
    * separately at the dispatch boundary.
    */
  def toolResultToWire(result: Any): CallToolResult =
    val content: List[Content] = result match
      case s: String => List(TextContent(s))
      case bytes: Array[Byte] =>
        List(ImageContent(Base64.getEncoder.encodeToString(bytes), "application/octet-stream"))
      case c: Content => List(c)
      case lst: List[?] if lst.forall(_.isInstanceOf[Content]) =>
        lst.collect { case c: Content => c }
      case other => List(TextContent(other.toString))
    CallToolResult(content = content, isError = Some(false))

  /** Prompt handler result (`List[Message]`) → wire [[PromptMessage]] list. */
  def promptMessagesToWire(messages: List[Message]): List[PromptMessage] =
    messages.map(m => PromptMessage(role = m.role, content = m.content))

  /** Resource read result (`String | Array[Byte]`) → wire [[ResourceContents]]. Text stays text;
    * bytes become a base64 blob.
    */
  def resourceContentsToWire(uri: String, mimeType: Option[String], body: String | Array[Byte]): ResourceContents =
    body match
      case s: String => TextResourceContents(uri = uri, text = s, mimeType = mimeType)
      case bytes: Array[Byte] =>
        BlobResourceContents(
          uri = uri,
          blob = Base64.getEncoder.encodeToString(bytes),
          mimeType = mimeType.orElse(Some("application/octet-stream"))
        )
