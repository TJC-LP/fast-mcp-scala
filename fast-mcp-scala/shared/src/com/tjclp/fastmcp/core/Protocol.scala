package com.tjclp.fastmcp.core

import zio.json.*

/** Protocol-level constants and small wire atoms shared by every MCP message. */
object Protocol:

  /** The MCP protocol version this implementation targets on the wire. */
  val LatestProtocolVersion: String = "2026-07-28"

  /** Version assumed for HTTP requests that omit the `mcp-protocol-version` header, per the spec's
    * backwards-compatibility rule (the header postdates 2025-03-26, so header-less clients speak at
    * most that revision). Legacy `initialize` negotiation remains within the legacy version set.
    */
  val DefaultNegotiatedProtocolVersion: String = "2025-03-26"

  /** Versions this server is willing to negotiate. Listed newest-first. */
  val LegacyProtocolVersions: List[String] = List(
    "2025-11-25",
    "2025-06-18",
    "2025-03-26",
    "2024-11-05",
    "2024-10-07"
  )

  /** Versions advertised by `server/discover`, newest first. The pre-2026 revisions are retained
    * through the legacy initialize/session adapter; all 2026 requests use the stateless path.
    */
  val SupportedProtocolVersions: List[String] = LatestProtocolVersion :: LegacyProtocolVersions

  def isStatelessVersion(version: String): Boolean = version == LatestProtocolVersion

  /** JSON-RPC version string embedded in every request/response. */
  val JsonRpcVersion: String = "2.0"

/** Standard + MCP-specific JSON-RPC error codes (spec 2026-07-28). */
object ErrorCodes:
  // JSON-RPC 2.0 standard codes
  val ParseError: Int = -32700
  val InvalidRequest: Int = -32600
  val MethodNotFound: Int = -32601
  val InvalidParams: Int = -32602
  val InternalError: Int = -32603

  // MCP-specific codes
  // Reserved by older revisions; modern resource misses use InvalidParams.
  val LegacyResourceNotFound: Int = -32002

  @deprecated("Resource misses use InvalidParams (-32602) as of MCP 2026-07-28", "1.0.0-RC1")
  val ResourceNotFound: Int = InvalidParams
  val UrlElicitationRequired: Int = -32042

  val HeaderMismatch: Int = -32020
  val MissingRequiredClientCapability: Int = -32021
  val UnsupportedProtocolVersion: Int = -32022

  // Implementation-defined transport-level codes (JSON-RPC server-error range; TS SDK parity for
  // HTTP-layer rejections that never reach the router).
  val TransportError: Int = -32000
  val SessionNotFound: Int = -32001

/** Opaque pagination cursor. Wire shape is a string; treating it as opaque keeps cursor
  * production/consumption sites from leaking encoding choices.
  */
opaque type Cursor = String

object Cursor:
  def apply(value: String): Cursor = value
  extension (c: Cursor) def value: String = c

  // Inside the companion the opacity is transparent, so a String codec IS a Cursor codec.
  given JsonCodec[Cursor] = JsonCodec.string

/** Token identifying an in-flight request for progress correlation. Wire shape is `string |
  * number`.
  */
enum ProgressToken:
  case StringToken(value: String)
  case NumberToken(value: Long)

object ProgressToken:

  given JsonCodec[ProgressToken] =
    JsonCodec(
      JsonEncoder[zio.json.ast.Json].contramap[ProgressToken] {
        case StringToken(s) => zio.json.ast.Json.Str(s)
        case NumberToken(n) => zio.json.ast.Json.Num(BigDecimal(n))
      },
      JsonDecoder[zio.json.ast.Json].mapOrFail {
        case zio.json.ast.Json.Str(s) => Right(StringToken(s))
        // Json.Num wraps java.math.BigDecimal — wrap in scala BigDecimal for isWhole/toLong.
        case zio.json.ast.Json.Num(n) if BigDecimal(n).isWhole =>
          Right(NumberToken(BigDecimal(n).toLong))
        case other => Left(s"progressToken must be string or whole number, got: $other")
      }
    )
