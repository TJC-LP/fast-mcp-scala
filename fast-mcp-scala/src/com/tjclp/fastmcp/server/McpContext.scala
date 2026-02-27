package com.tjclp.fastmcp.server

import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.server.McpAsyncServerExchange
import io.modelcontextprotocol.spec.McpSchema

/** Represents the context of an MCP request. Wraps either a stateful McpAsyncServerExchange (for
  * stdio/streaming transports) or a stateless McpTransportContext (for HTTP transport).
  */
case class McpContext(
    // Underlying Java exchange object for advanced use or accessing client capabilities
    javaExchange: Option[McpAsyncServerExchange] = None,
    // Transport-level context for stateless HTTP transport (carries HTTP headers, auth, etc.)
    transportContext: Option[McpTransportContext] = None
)

/** Extension methods for McpContext to provide a richer API
  */
extension (context: McpContext)

  /** Returns the client capabilities if available
    */
  def getClientCapabilities: Option[McpSchema.ClientCapabilities] =
    context.javaExchange.map(_.getClientCapabilities)

  /** Returns the client information if available
    */
  def getClientInfo: Option[McpSchema.Implementation] =
    context.javaExchange.map(_.getClientInfo)

// Future implementations:
// def log(level: String, message: String): Task[Unit] = ???
// def reportProgress(current: Double, total: Option[Double]): Task[Unit] = ???
// def readResource(uri: String): Task[String | Array[Byte]] = ???
