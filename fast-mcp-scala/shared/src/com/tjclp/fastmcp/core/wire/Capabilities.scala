package com.tjclp.fastmcp.core.wire

import zio.json.*
import zio.json.ast.Json

/** Describes the MCP implementation announcing itself on `initialize`. */
case class Implementation(
    name: String,
    version: String,
    title: Option[String] = None,
    description: Option[String] = None,
    websiteUrl: Option[String] = None,
    icons: Option[List[Icon]] = None
)

object Implementation:
  given JsonCodec[Implementation] = DeriveJsonCodec.gen[Implementation]

/** Optional UI icon attached to an [[Implementation]] or other metadata-bearing wire types. */
case class Icon(
    src: String,
    mimeType: Option[String] = None,
    sizes: Option[String] = None
)

object Icon:
  given JsonCodec[Icon] = DeriveJsonCodec.gen[Icon]

// ---------- Server capability sub-types ----------

case class ToolsCapability(listChanged: Option[Boolean] = None)

object ToolsCapability:
  given JsonCodec[ToolsCapability] = DeriveJsonCodec.gen[ToolsCapability]

case class ResourcesCapability(
    subscribe: Option[Boolean] = None,
    listChanged: Option[Boolean] = None
)

object ResourcesCapability:
  given JsonCodec[ResourcesCapability] = DeriveJsonCodec.gen[ResourcesCapability]

case class PromptsCapability(listChanged: Option[Boolean] = None)

object PromptsCapability:
  given JsonCodec[PromptsCapability] = DeriveJsonCodec.gen[PromptsCapability]

/** Capability advertising tools/list of supported task-augmented requests. The spec uses an
  * open-ended `JSONObject` per request type — we model that as `Json` so unknown fields survive
  * round-trips.
  */
case class ServerTasksCapability(
    list: Option[Json] = None,
    cancel: Option[Json] = None,
    requests: Option[ServerTasksRequests] = None
)

object ServerTasksCapability:
  given JsonCodec[ServerTasksCapability] = DeriveJsonCodec.gen[ServerTasksCapability]

case class ServerTasksRequests(tools: Option[ServerTasksToolsRequest] = None)

object ServerTasksRequests:
  given JsonCodec[ServerTasksRequests] = DeriveJsonCodec.gen[ServerTasksRequests]

case class ServerTasksToolsRequest(call: Option[Json] = None)

object ServerTasksToolsRequest:
  given JsonCodec[ServerTasksToolsRequest] = DeriveJsonCodec.gen[ServerTasksToolsRequest]

/** Capabilities the server advertises on `initialize`.
  *
  * Per the design doc, this is **derived from the registered handler map** at runtime — the router
  * opts in `tools`/`resources`/`prompts`/etc. only when handlers are wired. Fixes issue #56 by
  * construction: `logging` is `None` unless a logging hook is registered.
  */
case class ServerCapabilities(
    experimental: Option[Map[String, Json]] = None,
    logging: Option[Json] = None,
    completions: Option[Json] = None,
    prompts: Option[PromptsCapability] = None,
    resources: Option[ResourcesCapability] = None,
    tools: Option[ToolsCapability] = None,
    tasks: Option[ServerTasksCapability] = None,
    extensions: Option[Map[String, Json]] = None
)

object ServerCapabilities:
  given JsonCodec[ServerCapabilities] = DeriveJsonCodec.gen[ServerCapabilities]

// ---------- Client capability sub-types ----------

case class RootsCapability(listChanged: Option[Boolean] = None)

object RootsCapability:
  given JsonCodec[RootsCapability] = DeriveJsonCodec.gen[RootsCapability]

case class SamplingCapability(
    context: Option[Json] = None,
    tools: Option[Json] = None
)

object SamplingCapability:
  given JsonCodec[SamplingCapability] = DeriveJsonCodec.gen[SamplingCapability]

case class ElicitationCapability(
    form: Option[Json] = None,
    url: Option[Json] = None
)

object ElicitationCapability:
  given JsonCodec[ElicitationCapability] = DeriveJsonCodec.gen[ElicitationCapability]

case class ClientTasksCapability(
    list: Option[Json] = None,
    cancel: Option[Json] = None,
    requests: Option[ClientTasksRequests] = None
)

object ClientTasksCapability:
  given JsonCodec[ClientTasksCapability] = DeriveJsonCodec.gen[ClientTasksCapability]

case class ClientTasksRequests(
    sampling: Option[ClientTasksSamplingRequest] = None,
    elicitation: Option[ClientTasksElicitationRequest] = None
)

object ClientTasksRequests:
  given JsonCodec[ClientTasksRequests] = DeriveJsonCodec.gen[ClientTasksRequests]

case class ClientTasksSamplingRequest(createMessage: Option[Json] = None)

object ClientTasksSamplingRequest:
  given JsonCodec[ClientTasksSamplingRequest] = DeriveJsonCodec.gen[ClientTasksSamplingRequest]

case class ClientTasksElicitationRequest(create: Option[Json] = None)

object ClientTasksElicitationRequest:

  given JsonCodec[ClientTasksElicitationRequest] =
    DeriveJsonCodec.gen[ClientTasksElicitationRequest]

/** Capabilities the client advertises on `initialize`. */
case class ClientCapabilities(
    experimental: Option[Map[String, Json]] = None,
    roots: Option[RootsCapability] = None,
    sampling: Option[SamplingCapability] = None,
    elicitation: Option[ElicitationCapability] = None,
    tasks: Option[ClientTasksCapability] = None,
    extensions: Option[Map[String, Json]] = None
)

object ClientCapabilities:
  given JsonCodec[ClientCapabilities] = DeriveJsonCodec.gen[ClientCapabilities]
