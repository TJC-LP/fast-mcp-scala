package com.tjclp.fastmcp.core.wire

import zio.json.*
import zio.json.ast.Json

/** Params for the server-initiated `elicitation/create` request, form mode (2025-11-25). On the
  * wire `mode` is an optional `"form"` literal — absent means form, keeping pre-mode clients
  * compatible; [[ElicitRequestUrlParams]] is the `"url"` variant of the union.
  *
  * `requestedSchema` is a JSON Schema object the server constructs — the spec restricts it to a
  * flat object of primitive properties, but the server is the author, so we model it as an opaque
  * [[Json]] passthrough rather than re-encoding the `PrimitiveSchemaDefinition` union.
  */
case class ElicitRequestParams(
    message: String,
    requestedSchema: Json,
    mode: Option[String] = None,
    _meta: Option[Map[String, Json]] = None
)

object ElicitRequestParams:
  given JsonCodec[ElicitRequestParams] = DeriveJsonCodec.gen[ElicitRequestParams]

/** Params for an `elicitation/create` URL-mode input request. `elicitationId` is retained for the
  * 2025-11-25 compatibility adapter and omitted from modern MRTR requests; modern servers encode
  * any retry correlation in `requestState`.
  */
case class ElicitRequestUrlParams(
    message: String,
    url: String,
    elicitationId: Option[String] = None,
    mode: String = "url",
    _meta: Option[Map[String, Json]] = None
)

object ElicitRequestUrlParams:
  given JsonCodec[ElicitRequestUrlParams] = DeriveJsonCodec.gen[ElicitRequestUrlParams]

  /** Source-compatible legacy constructor. The identifier is omitted on 2026 MRTR requests. */
  def apply(message: String, url: String, elicitationId: String): ElicitRequestUrlParams =
    new ElicitRequestUrlParams(message, url, Some(elicitationId))

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
