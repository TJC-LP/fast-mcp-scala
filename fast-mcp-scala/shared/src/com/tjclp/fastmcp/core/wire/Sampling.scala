package com.tjclp.fastmcp.core.wire

import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.{Content, Role}

/** One message in a sampling conversation for the server-initiated `sampling/createMessage`
  * request. Reuses the core [[Content]] ADT; per spec 2025-11-25 valid sampling content is
  * text/image/audio.
  */
case class SamplingMessage(
    role: Role,
    content: Content,
    _meta: Option[Map[String, Json]] = None
)

object SamplingMessage:
  given JsonCodec[SamplingMessage] = DeriveJsonCodec.gen[SamplingMessage]

/** A hint nudging the client's model selection (substring match on `name`). */
case class ModelHint(name: Option[String] = None)

object ModelHint:
  given JsonCodec[ModelHint] = DeriveJsonCodec.gen[ModelHint]

/** The server's model-selection preferences for a sampling request (priorities are 0..1). */
case class ModelPreferences(
    hints: Option[List[ModelHint]] = None,
    costPriority: Option[Double] = None,
    speedPriority: Option[Double] = None,
    intelligencePriority: Option[Double] = None
)

object ModelPreferences:
  given JsonCodec[ModelPreferences] = DeriveJsonCodec.gen[ModelPreferences]

/** Controls tool usage in sampling requests (2025-11-25): `mode` is `"auto"` (default) |
  * `"required"` | `"none"`.
  */
case class ToolChoice(mode: Option[String] = None)

object ToolChoice:
  given JsonCodec[ToolChoice] = DeriveJsonCodec.gen[ToolChoice]

/** Params for the server-initiated `sampling/createMessage` request (2025-11-25). `tools` /
  * `toolChoice` require the client to have declared `sampling.tools` in its capabilities — the
  * client MUST error otherwise.
  */
case class CreateMessageRequestParams(
    messages: List[SamplingMessage],
    maxTokens: Int,
    modelPreferences: Option[ModelPreferences] = None,
    systemPrompt: Option[String] = None,
    includeContext: Option[String] = None,
    temperature: Option[Double] = None,
    stopSequences: Option[List[String]] = None,
    metadata: Option[Json] = None,
    tools: Option[List[Tool]] = None,
    toolChoice: Option[ToolChoice] = None,
    _meta: Option[Map[String, Json]] = None
)

object CreateMessageRequestParams:
  given JsonCodec[CreateMessageRequestParams] = DeriveJsonCodec.gen[CreateMessageRequestParams]

/** Result of `sampling/createMessage` — the sampled message plus the model that produced it. */
case class CreateMessageResult(
    role: Role,
    content: Content,
    model: String,
    stopReason: Option[String] = None,
    _meta: Option[Map[String, Json]] = None
)

object CreateMessageResult:
  given JsonCodec[CreateMessageResult] = DeriveJsonCodec.gen[CreateMessageResult]
