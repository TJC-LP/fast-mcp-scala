package com.tjclp.fastmcp.core.wire

import zio.json.*
import zio.json.ast.Json

/** Params for the server-initiated `elicitation/create` request (form mode, 2025-11-25).
  *
  * `requestedSchema` is a JSON Schema object the server constructs — the spec restricts it to a
  * flat object of primitive properties, but the server is the author, so we model it as an opaque
  * [[Json]] passthrough rather than re-encoding the `PrimitiveSchemaDefinition` union. The
  * DRAFT-2026 `url` mode is deferred.
  */
case class ElicitRequestParams(
    message: String,
    requestedSchema: Json,
    _meta: Option[Map[String, Json]] = None
)

object ElicitRequestParams:
  given JsonCodec[ElicitRequestParams] = DeriveJsonCodec.gen[ElicitRequestParams]

/** Result of `elicitation/create`: the user's `action` (`accept` | `decline` | `cancel`) and, on
  * accept, the collected flat form values.
  */
case class ElicitResult(
    action: String,
    content: Option[Map[String, Json]] = None,
    _meta: Option[Map[String, Json]] = None
)

object ElicitResult:
  given JsonCodec[ElicitResult] = DeriveJsonCodec.gen[ElicitResult]
