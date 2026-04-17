package com.tjclp.fastmcp
package facades
package server

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** Facade for the TS SDK's `McpError` — a subclass of `Error` carrying a JSON-RPC error code.
  *
  * Throwing one of these inside a request handler tells the TS SDK to return a JSON-RPC error
  * response with the given code. For tool failures we prefer returning `{ isError: true }` in the
  * `tools/call` result instead, to preserve fast-mcp-scala's existing semantics.
  */
@JSImport("@modelcontextprotocol/sdk/types.js", "McpError")
@js.native
class McpError(code: Int, message: String, data: js.UndefOr[js.Any] = js.undefined) extends js.Error

/** JSON-RPC error codes (mirrors `ErrorCode` in `@modelcontextprotocol/sdk/types.js`). */
object ErrorCode:
  val ConnectionClosed: Int = -32000
  val RequestTimeout: Int = -32001
  val ParseError: Int = -32700
  val InvalidRequest: Int = -32600
  val MethodNotFound: Int = -32601
  val InvalidParams: Int = -32602
  val InternalError: Int = -32603
  val UrlElicitationRequired: Int = -32042
