package com.tjclp.fastmcp.server

import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.server.McpAsyncServerExchange
import io.modelcontextprotocol.spec.McpSchema

/** JVM-specific MCP context carrying the Java SDK exchange and transport objects. Internal — users
  * interact via the [[McpContext]] base class and its extension methods.
  */
private[fastmcp] class JvmMcpContext(
    val javaExchange: Option[McpAsyncServerExchange] = None,
    val transportContext: Option[McpTransportContext] = None
) extends McpContext(javaExchange, transportContext)

/** Extension methods on McpContext that provide JVM capabilities when available. These are safe to
  * call on any McpContext — they return None on non-JVM platforms.
  */
extension (context: McpContext)

  def javaExchange: Option[McpAsyncServerExchange] =
    context.javaExchangeToken.collect { case exchange: McpAsyncServerExchange => exchange }

  def transportContext: Option[McpTransportContext] =
    context.transportContextToken.collect { case transport: McpTransportContext => transport }

  def copy(
      javaExchange: Option[Any] = context.javaExchangeToken,
      transportContext: Option[Any] = context.transportContextToken
  ): McpContext =
    McpContext(javaExchange, transportContext)

  def getClientCapabilities: Option[McpSchema.ClientCapabilities] =
    context.javaExchange.map(_.getClientCapabilities)

  def getClientInfo: Option[McpSchema.Implementation] =
    context.javaExchange.map(_.getClientInfo)
