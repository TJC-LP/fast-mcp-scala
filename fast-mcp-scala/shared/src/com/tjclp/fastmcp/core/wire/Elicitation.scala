package com.tjclp.fastmcp.core.wire

import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.ErrorCodes
import com.tjclp.fastmcp.jsonrpc.McpError

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

/** Params for the server-initiated `elicitation/create` request, URL mode (2025-11-25): the client
  * navigates the user to `url` to complete an out-of-band interaction. `elicitationId` is a
  * server-minted opaque correlation id (unique per server).
  */
case class ElicitRequestUrlParams(
    message: String,
    url: String,
    elicitationId: String,
    mode: String = "url",
    _meta: Option[Map[String, Json]] = None
)

object ElicitRequestUrlParams:
  given JsonCodec[ElicitRequestUrlParams] = DeriveJsonCodec.gen[ElicitRequestUrlParams]

  /** The `-32042 URL elicitation required` protocol error, carrying the pending elicitation(s) in
    * `data.elicitations` (TS SDK `UrlElicitationRequiredError` shape). A tool raises it to tell the
    * client: complete these out-of-band interactions, then retry.
    */
  def requiredError(
      elicitations: List[ElicitRequestUrlParams],
      message: String = "URL elicitation required"
  ): McpError =
    val data = Json.Obj(
      "elicitations" -> Json.Arr(elicitations.flatMap(_.toJsonAST.toOption)*)
    )
    McpError(ErrorCodes.UrlElicitationRequired, message, Some(data))

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
