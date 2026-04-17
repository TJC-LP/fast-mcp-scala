package com.tjclp.fastmcp
package macros

import core.Tool
import server.*

// Define a function with ctx parameter
object ContextAwareTool:

  @Tool(name = Some("context-aware-tool"))
  def contextAwareTool(message: String, ctx: McpContext): String = {
    val clientName = ctx.getClientInfo.map(_.name()).getOrElse("Unknown")
    s"Message: $message, Client: $clientName"
  }
end ContextAwareTool
