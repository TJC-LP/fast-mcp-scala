package com.tjclp.fastmcp
package examples

import com.tjclp.fastmcp.*

/** `McpContext` introspection from inside a tool handler.
  *
  * The annotation path detects a parameter named `ctx: McpContext` and threads the runtime context
  * through instead of decoding it from the JSON-RPC args. The handler below uses it to surface the
  * client's declared info and capabilities.
  */
object ContextEchoServer extends McpServerApp[Stdio, ContextEchoServer.type]:

  override def name: String = "ContextEchoServer"
  override def version: String = "1.0.0"

  @Tool(
    name = Some("echo"),
    description = Some("Echoes back information about the client and the context of the request")
  )
  def echo(
      @Param(description = "Optional note to include in the echo", required = false)
      note: Option[String],
      ctx: McpContext
  ): String =
    val clientName = ctx.getClientInfo.map(_.name()).getOrElse("Unknown Client")
    val clientVersion = ctx.getClientInfo.map(_.version()).getOrElse("Unknown Version")
    val hasRootListChanges = ctx.getClientCapabilities
      .exists(c => c.roots() != null && c.roots().listChanged() == true)
    val hasSampling = ctx.getClientCapabilities.exists(c => c.sampling() != null)
    s"""Client: $clientName v$clientVersion
       |Note: ${note.getOrElse("(none)")}
       |Client Capabilities:
       |  - Root List Changes: $hasRootListChanges
       |  - Sampling: $hasSampling""".stripMargin
