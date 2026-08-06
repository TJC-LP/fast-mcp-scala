package com.tjclp.fastmcp.core.wire

import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.{Content, PromptArgument, Role}

/** Wire shape of a prompt descriptor as returned in `prompts/list`.
  *
  * Distinct from [[com.tjclp.fastmcp.core.PromptDefinition]] (the user-facing registration shape).
  * Adds spec fields (`title`, `icons`, `_meta`) the registration form doesn't carry.
  */
case class Prompt(
    name: String,
    title: Option[String] = None,
    description: Option[String] = None,
    arguments: Option[List[PromptArgument]] = None,
    icons: Option[List[Icon]] = None,
    _meta: Option[Map[String, Json]] = None
)

object Prompt:
  given JsonCodec[Prompt] = DeriveJsonCodec.gen[Prompt]

/** A message returned as part of `prompts/get`. Distinct from [[com.tjclp.fastmcp.core.Message]]
  * (which is also user-facing) because `PromptMessage.content` is a single `Content`, never a
  * sequence.
  */
case class PromptMessage(
    role: Role,
    content: Content
)

object PromptMessage:
  given JsonCodec[PromptMessage] = DeriveJsonCodec.gen[PromptMessage]
