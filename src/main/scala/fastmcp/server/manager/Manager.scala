package fastmcp.server.manager

import fastmcp.core.*
import fastmcp.server.McpContext
import zio.*

/**
 * Base trait for all manager classes in the FastMCP-Scala implementation
 */
trait Manager[DefinitionType]:
  /** 
   * Returns a list of all registered definitions
   */
  def listDefinitions(): List[DefinitionType]