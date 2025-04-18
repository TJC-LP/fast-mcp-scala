package com.tjclp.fastmcp
package examples

import com.tjclp.fastmcp.server.*
import zio.*

/** A simple example server demonstrating the use of contextual tool handlers
  *
  * This example shows how to access client capabilities and information through the McpContext
  * passed to contextual tool handlers.
  */
object ContextEchoServer extends ZIOAppDefault:

  /** Main method that sets up and runs the server
    */
  def run: ZIO[Any, Throwable, ExitCode] =
    // Create a new server instance
    val server = FastMcpServer("ContextEchoServer", "1.0.0")

    // Register a tool that uses context to get client information
    val registerTool = server.tool(
      name = "echo",
      handler = (args: Map[String, Any], ctx: Option[McpContext]) => {
        // Extract client information from context
        val clientName = ctx.get.getClientInfo.map(_.name()).getOrElse("Unknown Client")
        val clientVersion = ctx.get.getClientInfo.map(_.version()).getOrElse("Unknown Version")

        // Extract client capabilities
        val hasRootListChanges = ctx.get.getClientCapabilities.exists(c =>
          c.roots() != null && c.roots().listChanged() == true
        )
        val hasSampling = ctx.get.getClientCapabilities.exists(c => c.sampling() != null)

        // Create a summary of the client and context information
        val summary = s"""
        |Client: $clientName v$clientVersion
        |Request Arguments: $args
        |Client Capabilities:
        |  - Root List Changes: $hasRootListChanges
        |  - Sampling: $hasSampling
        """.stripMargin

        ZIO.succeed(summary)
      },
      description = Some("Echoes back information about the client and the context of the request")
    )

    // Register with @Tool annotation

    // Register and run the server
    registerTool *> server.runStdio().as(ExitCode.success)

end ContextEchoServer
