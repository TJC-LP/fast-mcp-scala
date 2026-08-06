package com.tjclp.fastmcp.server.router

import java.util.Base64

import zio.json.ast.Json

import com.tjclp.fastmcp.core.{
  Content,
  ImageContent,
  Message,
  PromptDefinition,
  ResourceDefinition,
  StructuredToolResult,
  TaskSupport,
  TextContent,
  ToolDefinition,
  ToolExecution
}
import com.tjclp.fastmcp.core.wire.*

/** Pure functions mapping the user-facing registration types (held by the managers) into the
  * 2025-11-25 wire shapes the built-in handlers return. No effects, no platform specifics.
  */
object WireMapping:

  /** [[ToolDefinition]] → wire [[Tool]]. `execution.taskSupport` is emitted only when the server is
    * task-enabled and the tool opts in (≠ Forbidden).
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
      outputSchema = d.outputSchema,
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

  /** Single construction point for every outbound `result` payload (called by the router when it
    * wraps a handler's JSON into a [[com.tjclp.fastmcp.jsonrpc.JsonRpcMessage.Success]]). A no-op
    * today; the 2026-07-28 revision requires `resultType: "complete"` on every result — inject it
    * here when the router grows multi-version support.
    */
  def completeResult(result: Json): Json = result

  /** Convert a tool handler's untyped result into a [[CallToolResult]]. A typed contract arrives as
    * a [[StructuredToolResult]] carrying both renderings — its structured form is emitted as
    * `structuredContent` when (and only when) the tool declares an `outputSchema`, per spec.
    * Untyped results keep the long-standing coercions: a `String` becomes one `TextContent`, an
    * `Array[Byte]` a base64 image, a `Content` / `List[Content]` passes through, and anything else
    * degrades to its `toString`. Tool *failures* never reach here — those are mapped to `isError =
    * true` separately at the dispatch boundary.
    */
  def toolResultToWire(result: Any, outputSchema: Option[ToolOutputSchema]): CallToolResult =
    result match
      case StructuredToolResult(content, structured) =>
        CallToolResult(
          content = content,
          structuredContent = structured.filter(_ => outputSchema.isDefined),
          isError = Some(false)
        )
      case other =>
        val content: List[Content] = other match
          case s: String => List(TextContent(s))
          case bytes: Array[Byte] =>
            List(ImageContent(Base64.getEncoder.encodeToString(bytes), "application/octet-stream"))
          case c: Content => List(c)
          case lst: List[?] if lst.forall(_.isInstanceOf[Content]) =>
            lst.collect { case c: Content => c }
          case value => List(TextContent(value.toString))
        CallToolResult(content = content, isError = Some(false))

  /** Convert a tool *execution* failure into an error [[CallToolResult]] (`isError = true`) with
    * the message as text content. Per the MCP spec, a handler that throws surfaces as a tool result
    * with `isError = true`, not as a JSON-RPC protocol error.
    */
  def toolErrorToWire(error: Throwable): CallToolResult =
    CallToolResult(
      content = List(TextContent(Option(error.getMessage).getOrElse(error.toString))),
      isError = Some(true)
    )

  /** Prompt handler result (`List[Message]`) → wire [[PromptMessage]] list. */
  def promptMessagesToWire(messages: List[Message]): List[PromptMessage] =
    messages.map(m => PromptMessage(role = m.role, content = m.content))

  /** Resource read result (`String | Array[Byte]`) → wire [[ResourceContents]]. Text stays text;
    * bytes become a base64 blob.
    */
  def resourceContentsToWire(
      uri: String,
      mimeType: Option[String],
      body: String | Array[Byte]
  ): ResourceContents =
    body match
      case s: String => TextResourceContents(uri = uri, text = s, mimeType = mimeType)
      case bytes: Array[Byte] =>
        BlobResourceContents(
          uri = uri,
          blob = Base64.getEncoder.encodeToString(bytes),
          mimeType = mimeType.orElse(Some("application/octet-stream"))
        )
