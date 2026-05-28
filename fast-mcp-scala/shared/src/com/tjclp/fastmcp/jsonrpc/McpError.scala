package com.tjclp.fastmcp.jsonrpc

import zio.json.ast.Json

import com.tjclp.fastmcp.core.ErrorCodes

/** A protocol-level error that crosses the wire as a JSON-RPC error response.
  *
  * Replaces the Java SDK's `McpError` and subsumes the deleted `ErrorMapper`: it is both a
  * `Throwable` (so handlers can `ZIO.fail` it) and directly renderable to a
  * [[JsonRpcErrorObject]]. Tool-level failures do NOT use this — those are reported in-band via
  * `CallToolResult.isError` so the model can self-correct. `McpError` is for "couldn't find the
  * tool / unknown method / malformed params" conditions.
  */
final case class McpError(
    code: Int,
    message: String,
    data: Option[Json] = None
) extends RuntimeException(message):

  def toErrorObject: JsonRpcErrorObject = JsonRpcErrorObject(code, message, data)

object McpError:

  def parseError(message: String): McpError = McpError(ErrorCodes.ParseError, message)
  def invalidRequest(message: String): McpError = McpError(ErrorCodes.InvalidRequest, message)
  def methodNotFound(method: String): McpError =
    McpError(ErrorCodes.MethodNotFound, s"Method not found: $method")
  def invalidParams(message: String): McpError = McpError(ErrorCodes.InvalidParams, message)
  def internalError(message: String): McpError = McpError(ErrorCodes.InternalError, message)
  def resourceNotFound(uri: String): McpError =
    McpError(ErrorCodes.ResourceNotFound, s"Resource not found: $uri", Some(Json.Obj("uri" -> Json.Str(uri))))

  /** Map an arbitrary throwable to an `McpError`. Ports the classification the old
    * `ErrorMapper.errorMessage` did, but produces a structured protocol error rather than a
    * string. Used at the dispatch boundary when a handler fails with a non-`McpError` throwable.
    */
  def fromThrowable(err: Throwable): McpError =
    err match
      case e: McpError => e
      case _: java.util.concurrent.TimeoutException =>
        McpError.internalError(s"Operation timed out: ${msg(err)}")
      case _: IllegalArgumentException =>
        McpError.invalidParams(s"Invalid argument: ${msg(err)}")
      case _: NoSuchElementException =>
        McpError(ErrorCodes.ResourceNotFound, s"Not found: ${msg(err)}")
      case _ =>
        McpError.internalError(msg(err))

  private def msg(err: Throwable): String =
    Option(err.getMessage).getOrElse(s"Internal error: ${err.getClass.getSimpleName}")
