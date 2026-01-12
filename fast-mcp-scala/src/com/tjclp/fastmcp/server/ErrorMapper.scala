package com.tjclp.fastmcp.server

import io.modelcontextprotocol.spec.McpSchema

object ErrorMapper:

  /** Maps a Throwable to a CallToolResult with isError flag set to true
    */
  def toCallToolResult(err: Throwable): McpSchema.CallToolResult =
    val errorContent = Option(err.getMessage).getOrElse(s"Error: ${err.getClass.getSimpleName}")

    // Return as CallToolResult with isError flag set to true
    new McpSchema.CallToolResult(errorContent, true)

  /** Maps common exception types to reasonable error messages for MCP error reporting
    */
  def errorMessage(err: Throwable): String =
    err match
      case _: java.util.concurrent.TimeoutException =>
        s"Operation timed out: ${err.getMessage}"
      case _: IllegalArgumentException =>
        s"Invalid argument: ${err.getMessage}"
      case _: NoSuchElementException =>
        s"Not found: ${err.getMessage}"
      case _ =>
        Option(err.getMessage).getOrElse(s"Internal error: ${err.getClass.getSimpleName}")

  // Java interop helpers
