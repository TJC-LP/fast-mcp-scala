package com.tjclp.fastmcp.server

import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.server.McpAsyncServerExchange
import io.modelcontextprotocol.spec.McpSchema

/** JVM-specific MCP context carrying the Java SDK exchange and transport objects.
  * Internal — users interact via the [[McpContext]] base class and its extension methods.
  */
private[fastmcp] class JvmMcpContext(
    val javaExchange: Option[McpAsyncServerExchange] = None,
    val transportContext: Option[McpTransportContext] = None
) extends McpContext

/** Extension methods on McpContext that provide JVM capabilities when available.
  * These are safe to call on any McpContext — they return None on non-JVM platforms.
  */
extension (context: McpContext)

  def getClientCapabilities: Option[McpSchema.ClientCapabilities] =
    context match
      case jvm: JvmMcpContext => jvm.javaExchange.map(_.getClientCapabilities)
      case _                  => None

  def getClientInfo: Option[McpSchema.Implementation] =
    context match
      case jvm: JvmMcpContext => jvm.javaExchange.map(_.getClientInfo)
      case _                  => None
