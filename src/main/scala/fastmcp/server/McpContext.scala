package fastmcp.server

import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema

/**
 * Represents the context of an MCP request
 * Acts as a wrapper around the Java McpSyncServerExchange
 */
case class McpContext(
  // Underlying Java exchange object for advanced use or accessing client capabilities
  javaExchange: Option[McpSyncServerExchange] = None
)

/**
 * Extension methods for McpContext to provide a richer API
 */
extension (context: McpContext)
  /**
   * Returns the client capabilities if available
   */
  def getClientCapabilities: Option[McpSchema.ClientCapabilities] =
    context.javaExchange.map(_.getClientCapabilities)

  /**
   * Returns the client information if available
   */
  def getClientInfo: Option[McpSchema.Implementation] =
    context.javaExchange.map(_.getClientInfo)

  // Future implementations:
  // def log(level: String, message: String): Task[Unit] = ???
  // def reportProgress(current: Double, total: Option[Double]): Task[Unit] = ???
  // def readResource(uri: String): Task[String | Array[Byte]] = ???