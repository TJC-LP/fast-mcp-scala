package com.tjclp.fastmcp.jsonrpc

import zio.json.ast.Json

import com.tjclp.fastmcp.core.ErrorCodes

/** A protocol-level error that crosses the wire as a JSON-RPC error response.
  *
  * Replaces the Java SDK's `McpError` and subsumes the deleted `ErrorMapper`: it is both a
  * `Throwable` (so handlers can `ZIO.fail` it) and directly renderable to a [[JsonRpcErrorObject]].
  * Tool-level failures do NOT use this — those are reported in-band via `CallToolResult.isError` so
  * the model can self-correct. `McpError` is for "couldn't find the tool / unknown method /
  * malformed params" conditions.
  */
final case class McpError(
    code: Int,
    message: String,
    data: Option[Json] = None
) extends RuntimeException(message):

  def toErrorObject: JsonRpcErrorObject = JsonRpcErrorObject(code, message, data)

/** Domain errors that know their own JSON-RPC representation. [[McpError.fromThrowable]] maps any
  * carrier via `toMcpError`, so error types can live next to their producers (managers) without
  * this layer depending on their packages. Wire codes stay centralized in [[ErrorCodes]].
  */
trait McpErrorCarrier:
  def toMcpError: McpError

object McpError:

  // Never crosses the wire. It tunnels an MRTR interim result through user handler error channels
  // until the request dispatcher can turn it back into a successful `input_required` result.
  private val InputRequiredSentinel: Int = Int.MinValue

  def parseError(message: String): McpError = McpError(ErrorCodes.ParseError, message)
  def invalidRequest(message: String): McpError = McpError(ErrorCodes.InvalidRequest, message)

  def methodNotFound(method: String): McpError =
    McpError(ErrorCodes.MethodNotFound, s"Method not found: $method")
  def invalidParams(message: String): McpError = McpError(ErrorCodes.InvalidParams, message)
  def internalError(message: String): McpError = McpError(ErrorCodes.InternalError, message)

  def resourceNotFound(uri: String): McpError =
    McpError(
      ErrorCodes.InvalidParams,
      s"Resource not found: $uri",
      Some(Json.Obj("uri" -> Json.Str(uri)))
    )

  def headerMismatch(message: String): McpError =
    McpError(ErrorCodes.HeaderMismatch, message)

  def unsupportedProtocolVersion(requested: String, supported: List[String]): McpError =
    McpError(
      ErrorCodes.UnsupportedProtocolVersion,
      s"Unsupported protocol version: $requested",
      Some(
        Json.Obj(
          "supported" -> Json.Arr(supported.map(Json.Str(_))*),
          "requested" -> Json.Str(requested)
        )
      )
    )

  def missingRequiredClientCapability(requiredCapabilities: Json): McpError =
    McpError(
      ErrorCodes.MissingRequiredClientCapability,
      "The request requires a client capability that was not declared",
      Some(Json.Obj("requiredCapabilities" -> requiredCapabilities))
    )

  private[fastmcp] def inputRequired(
      key: String,
      request: Json,
      requestState: Option[String] = None
  ): McpError =
    val fields = List(
      "resultType" -> Json.Str("input_required"),
      "inputRequests" -> Json.Obj(key -> request)
    ) ++ requestState.map(value => "requestState" -> Json.Str(value)).toList
    McpError(
      InputRequiredSentinel,
      "Additional client input is required",
      Some(Json.Obj(fields*))
    )

  private[fastmcp] def inputRequiredResult(error: Throwable): Option[Json] =
    error match
      case e: McpError if e.code == InputRequiredSentinel => e.data
      case _ => None

  /** Map an arbitrary throwable to an `McpError`. Ports the classification the old
    * `ErrorMapper.errorMessage` did, but produces a structured protocol error rather than a string.
    * Used at the dispatch boundary when a handler fails with a non-`McpError` throwable.
    */
  def fromThrowable(err: Throwable): McpError =
    err match
      case e: McpError => e
      case c: McpErrorCarrier => c.toMcpError
      case _: java.util.concurrent.TimeoutException =>
        McpError.internalError(s"Operation timed out: ${msg(err)}")
      case _: IllegalArgumentException =>
        McpError.invalidParams(s"Invalid argument: ${msg(err)}")
      // Missing request data (e.g. a required argument key absent on the annotation path) is a
      // request problem (-32602), not a server fault.
      case _: NoSuchElementException =>
        McpError.invalidParams(msg(err))
      case _ =>
        McpError.internalError(msg(err))

  private def msg(err: Throwable): String =
    Option(err.getMessage).getOrElse(s"Internal error: ${err.getClass.getSimpleName}")
