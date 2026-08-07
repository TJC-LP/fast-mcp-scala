package com.tjclp.fastmcp.core.wire

import zio.json.*
import zio.json.ast.Json

// Import only non-colliding core symbols by name. A wildcard `import core.*` would shadow the
// same-package wire types (Tool/Resource/Prompt) with core's `@Tool`/`@Resource`/`@Prompt`
// annotation classes — Scala ranks wildcard imports ABOVE same-package-different-file defs.
import com.tjclp.fastmcp.core.{Content, Cursor, TaskParams}

/** Request `params` and result bodies for every MCP method fast-mcp-scala handles as a server.
  *
  * These are the *inner* shapes — the JSON-RPC envelope (`jsonrpc`/`id`/`method`) lives in the M4
  * `jsonrpc` package. A handler decodes `params` into one of these, returns one of the result
  * types, and the router wraps it back into a `JSONRPCResultResponse`.
  *
  * Naming mirrors the spec exactly. Optional fields are `Option` so absent ≠ null on the wire;
  * `_meta` is `Option[Map[String, Json]]` throughout for the same reason.
  */

// ---------- Common ----------

/** Params common to paginated list requests (`tools/list`, `resources/list`, etc.). */
case class PaginatedRequestParams(
    cursor: Option[Cursor] = None,
    _meta: Option[Map[String, Json]] = None
)

object PaginatedRequestParams:
  given JsonCodec[PaginatedRequestParams] = DeriveJsonCodec.gen[PaginatedRequestParams]

/** An empty result, used by modern acknowledgements and legacy ping/log-level methods. */
case class EmptyResult(_meta: Option[Map[String, Json]] = None)

object EmptyResult:
  given JsonCodec[EmptyResult] = DeriveJsonCodec.gen[EmptyResult]

enum CacheScope:
  case Public, Private

object CacheScope:

  given JsonCodec[CacheScope] = JsonCodec.string.transformOrFail(
    {
      case "public" => Right(CacheScope.Public)
      case "private" => Right(CacheScope.Private)
      case other => Left(s"Invalid cache scope: $other")
    },
    {
      case CacheScope.Public => "public"
      case CacheScope.Private => "private"
    }
  )

/** Conservative cache defaults: immediately stale and private to the current authorization context.
  * Applications can safely add more permissive caching in a future settings surface.
  */
object CacheHints:
  val TtlMs: Long = 0L
  val Scope: CacheScope = CacheScope.Private

// ---------- server/discover ----------

case class DiscoverResult(
    supportedVersions: List[String],
    capabilities: ServerCapabilities,
    instructions: Option[String] = None,
    ttlMs: Long = CacheHints.TtlMs,
    cacheScope: CacheScope = CacheHints.Scope,
    _meta: Option[Map[String, Json]] = None
)

object DiscoverResult:
  given JsonCodec[DiscoverResult] = DeriveJsonCodec.gen[DiscoverResult]

// ---------- initialize ----------

case class InitializeRequestParams(
    protocolVersion: String,
    capabilities: ClientCapabilities,
    clientInfo: Implementation
)

object InitializeRequestParams:
  given JsonCodec[InitializeRequestParams] = DeriveJsonCodec.gen[InitializeRequestParams]

case class InitializeResult(
    protocolVersion: String,
    capabilities: ServerCapabilities,
    serverInfo: Implementation,
    instructions: Option[String] = None,
    _meta: Option[Map[String, Json]] = None
)

object InitializeResult:
  given JsonCodec[InitializeResult] = DeriveJsonCodec.gen[InitializeResult]

// ---------- tools/list ----------

case class ListToolsResult(
    tools: List[Tool],
    nextCursor: Option[Cursor] = None,
    ttlMs: Long = CacheHints.TtlMs,
    cacheScope: CacheScope = CacheHints.Scope,
    _meta: Option[Map[String, Json]] = None
)

object ListToolsResult:
  given JsonCodec[ListToolsResult] = DeriveJsonCodec.gen[ListToolsResult]

// ---------- tools/call ----------

/** `tools/call` params. `task` is the task-augmentation request body (spec 2025-11-25); present
  * only when the client wants task-wrapped execution. `arguments` is left as raw `Json` so the
  * tool's own decoder (derived from its input schema) owns parsing.
  */
case class CallToolRequestParams(
    name: String,
    arguments: Option[Json] = None,
    task: Option[TaskParams] = None,
    inputResponses: Option[Map[String, Json]] = None,
    requestState: Option[String] = None,
    _meta: Option[Map[String, Json]] = None
)

object CallToolRequestParams:
  given JsonCodec[CallToolRequestParams] = DeriveJsonCodec.gen[CallToolRequestParams]

/** `tools/call` result. `isError` distinguishes a tool-level failure (reported in-band so the model
  * can self-correct) from a protocol error. `structuredContent` carries typed output when the tool
  * declares an `outputSchema`.
  */
case class CallToolResult(
    content: List[Content],
    structuredContent: Option[Json] = None,
    isError: Option[Boolean] = None,
    _meta: Option[Map[String, Json]] = None
)

object CallToolResult:
  given JsonCodec[CallToolResult] = DeriveJsonCodec.gen[CallToolResult]

// ---------- resources/list ----------

case class ListResourcesResult(
    resources: List[Resource],
    nextCursor: Option[Cursor] = None,
    ttlMs: Long = CacheHints.TtlMs,
    cacheScope: CacheScope = CacheHints.Scope,
    _meta: Option[Map[String, Json]] = None
)

object ListResourcesResult:
  given JsonCodec[ListResourcesResult] = DeriveJsonCodec.gen[ListResourcesResult]

// ---------- resources/templates/list ----------

case class ListResourceTemplatesResult(
    resourceTemplates: List[ResourceTemplate],
    nextCursor: Option[Cursor] = None,
    ttlMs: Long = CacheHints.TtlMs,
    cacheScope: CacheScope = CacheHints.Scope,
    _meta: Option[Map[String, Json]] = None
)

object ListResourceTemplatesResult:
  given JsonCodec[ListResourceTemplatesResult] = DeriveJsonCodec.gen[ListResourceTemplatesResult]

// ---------- resources/read ----------

case class ReadResourceRequestParams(
    uri: String,
    inputResponses: Option[Map[String, Json]] = None,
    requestState: Option[String] = None,
    _meta: Option[Map[String, Json]] = None
)

object ReadResourceRequestParams:
  given JsonCodec[ReadResourceRequestParams] = DeriveJsonCodec.gen[ReadResourceRequestParams]

case class ReadResourceResult(
    contents: List[ResourceContents],
    ttlMs: Long = CacheHints.TtlMs,
    cacheScope: CacheScope = CacheHints.Scope,
    _meta: Option[Map[String, Json]] = None
)

object ReadResourceResult:
  given JsonCodec[ReadResourceResult] = DeriveJsonCodec.gen[ReadResourceResult]

// ---------- resources/subscribe + resources/unsubscribe ----------

case class SubscribeRequestParams(uri: String, _meta: Option[Map[String, Json]] = None)

object SubscribeRequestParams:
  given JsonCodec[SubscribeRequestParams] = DeriveJsonCodec.gen[SubscribeRequestParams]

case class UnsubscribeRequestParams(uri: String, _meta: Option[Map[String, Json]] = None)

object UnsubscribeRequestParams:
  given JsonCodec[UnsubscribeRequestParams] = DeriveJsonCodec.gen[UnsubscribeRequestParams]

// ---------- prompts/list ----------

case class ListPromptsResult(
    prompts: List[Prompt],
    nextCursor: Option[Cursor] = None,
    ttlMs: Long = CacheHints.TtlMs,
    cacheScope: CacheScope = CacheHints.Scope,
    _meta: Option[Map[String, Json]] = None
)

object ListPromptsResult:
  given JsonCodec[ListPromptsResult] = DeriveJsonCodec.gen[ListPromptsResult]

// ---------- prompts/get ----------

case class GetPromptRequestParams(
    name: String,
    arguments: Option[Map[String, String]] = None,
    inputResponses: Option[Map[String, Json]] = None,
    requestState: Option[String] = None,
    _meta: Option[Map[String, Json]] = None
)

object GetPromptRequestParams:
  given JsonCodec[GetPromptRequestParams] = DeriveJsonCodec.gen[GetPromptRequestParams]

case class GetPromptResult(
    messages: List[PromptMessage],
    description: Option[String] = None,
    _meta: Option[Map[String, Json]] = None
)

object GetPromptResult:
  given JsonCodec[GetPromptResult] = DeriveJsonCodec.gen[GetPromptResult]

// ---------- completion/complete ----------

/** Reference target for a completion request — either a prompt or a resource template. Sum type
  * discriminated by the spec's `type` field (`ref/prompt` | `ref/resource`).
  */
@jsonDiscriminator("type")
sealed trait CompletionReference(@scala.annotation.unused `type`: String)

@jsonHint("ref/prompt")
case class PromptReference(name: String, title: Option[String] = None)
    extends CompletionReference("ref/prompt")

object PromptReference:
  given JsonCodec[PromptReference] = DeriveJsonCodec.gen[PromptReference]

@jsonHint("ref/resource")
case class ResourceTemplateReference(uri: String) extends CompletionReference("ref/resource")

object ResourceTemplateReference:
  given JsonCodec[ResourceTemplateReference] = DeriveJsonCodec.gen[ResourceTemplateReference]

object CompletionReference:
  given JsonCodec[CompletionReference] = DeriveJsonCodec.gen[CompletionReference]

case class CompletionArgument(name: String, value: String)

object CompletionArgument:
  given JsonCodec[CompletionArgument] = DeriveJsonCodec.gen[CompletionArgument]

case class CompletionContext(arguments: Option[Map[String, String]] = None)

object CompletionContext:
  given JsonCodec[CompletionContext] = DeriveJsonCodec.gen[CompletionContext]

case class CompleteRequestParams(
    ref: CompletionReference,
    argument: CompletionArgument,
    context: Option[CompletionContext] = None,
    _meta: Option[Map[String, Json]] = None
)

object CompleteRequestParams:
  given JsonCodec[CompleteRequestParams] = DeriveJsonCodec.gen[CompleteRequestParams]

/** The inner `completion` object of a `completion/complete` result. `values` is capped at 100 by
  * the spec — enforcement lives in the handler, not the type.
  */
case class Completion(
    values: List[String],
    total: Option[Int] = None,
    hasMore: Option[Boolean] = None
)

object Completion:
  given JsonCodec[Completion] = DeriveJsonCodec.gen[Completion]

case class CompleteResult(
    completion: Completion,
    _meta: Option[Map[String, Json]] = None
)

object CompleteResult:
  given JsonCodec[CompleteResult] = DeriveJsonCodec.gen[CompleteResult]

// ---------- subscriptions/listen ----------

case class SubscriptionFilter(
    toolsListChanged: Option[Boolean] = None,
    promptsListChanged: Option[Boolean] = None,
    resourcesListChanged: Option[Boolean] = None,
    resourceSubscriptions: Option[List[String]] = None
)

object SubscriptionFilter:
  given JsonCodec[SubscriptionFilter] = DeriveJsonCodec.gen[SubscriptionFilter]

case class SubscriptionsListenRequestParams(
    notifications: SubscriptionFilter,
    _meta: Option[Map[String, Json]] = None
)

object SubscriptionsListenRequestParams:

  given JsonCodec[SubscriptionsListenRequestParams] =
    DeriveJsonCodec.gen[SubscriptionsListenRequestParams]

case class SubscriptionsAcknowledgedNotificationParams(
    notifications: SubscriptionFilter,
    _meta: Option[Map[String, Json]] = None
)

object SubscriptionsAcknowledgedNotificationParams:

  given JsonCodec[SubscriptionsAcknowledgedNotificationParams] =
    DeriveJsonCodec.gen[SubscriptionsAcknowledgedNotificationParams]
