package com.tjclp.fastmcp
package server

/** Platform-independent MCP request context.
  *
  * Handlers receive `Option[McpContext]` regardless of platform. The JVM backend provides a concrete
  * subclass wrapping the Java SDK exchange; the JS backend wraps the TS SDK session.
  *
  * Users always reference `McpContext` — the platform-specific details are internal.
  */
open class McpContext

object McpContext:
  /** Default empty context — used by macros when no context is available. */
  def empty: McpContext = new McpContext
