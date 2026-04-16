package com.tjclp.fastmcp
package server

/** Platform-independent MCP request context.
  *
  * Handlers receive `Option[McpContext]` regardless of platform. The JVM backend provides a
  * concrete subclass wrapping the Java SDK exchange; the JS backend wraps the TS SDK session.
  *
  * Users always reference `McpContext` — the platform-specific details are internal.
  */
open class McpContext private[fastmcp] (
    private[fastmcp] val javaExchangeToken: Option[Any] = None,
    private[fastmcp] val transportContextToken: Option[Any] = None
)

object McpContext:
  /** Default empty context — used by macros when no context is available. */
  def empty: McpContext = new McpContext

  /** Backward-compatible constructor shape for JVM call sites. */
  def apply(
      javaExchange: Option[Any] = None,
      transportContext: Option[Any] = None
  ): McpContext =
    new McpContext(javaExchange, transportContext)

  def unapply(context: McpContext): Some[(Option[Any], Option[Any])] =
    Some((context.javaExchangeToken, context.transportContextToken))
