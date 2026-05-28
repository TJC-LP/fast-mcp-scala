package com.tjclp.fastmcp.core

import zio.json.*

/** Protocol-level constants and small wire atoms shared by every MCP message. */
object Protocol:

  /** The MCP protocol version this implementation targets on the wire. */
  val LatestProtocolVersion: String = "2025-11-25"

  /** Fallback negotiated version per the 2025-11-25 spec — if a client sends a version we don't
    * recognize, we respond with this and let them decide whether to disconnect.
    */
  val DefaultNegotiatedProtocolVersion: String = "2025-03-26"

  /** Versions this server is willing to negotiate. Listed newest-first. */
  val SupportedProtocolVersions: List[String] = List(
    LatestProtocolVersion,
    "2025-06-18",
    "2025-03-26",
    "2024-11-05",
    "2024-10-07"
  )

  /** JSON-RPC version string embedded in every request/response. */
  val JsonRpcVersion: String = "2.0"

/** Standard + MCP-specific JSON-RPC error codes (spec 2025-11-25). */
object ErrorCodes:
  // JSON-RPC 2.0 standard codes
  val ParseError: Int = -32700
  val InvalidRequest: Int = -32600
  val MethodNotFound: Int = -32601
  val InvalidParams: Int = -32602
  val InternalError: Int = -32603

  // MCP-specific codes
  val ResourceNotFound: Int = -32002
  val UrlElicitationRequired: Int = -32042

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
        case zio.json.ast.Json.Num(n) if BigDecimal(n).isWhole => Right(NumberToken(BigDecimal(n).toLong))
        case other => Left(s"progressToken must be string or whole number, got: $other")
      }
    )
