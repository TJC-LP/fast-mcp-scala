package com.tjclp.fastmcp
package server

import zio.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.server.manager.*

/** Platform-independent MCP server API.
  *
  * This is the trait that macros (`scanAnnotations`, `@Tool`, `@Resource`, `@Prompt`) target. Users
  * write against this API; the JVM backend (`FastMcpServer`) and JS backend delegate to their
  * respective SDK implementations.
  */
trait McpServer:

  // --- Tool registration ---

  def tool(
      name: String,
      handler: ContextualToolHandler,
      description: Option[String] = None,
      inputSchema: String = """{"type":"object","additionalProperties":true}""",
      options: ToolRegistrationOptions = ToolRegistrationOptions(),
      annotations: Option[ToolAnnotations] = None
  ): ZIO[Any, Throwable, McpServer]

  // --- Resource registration ---

  def resource(
      uri: String,
      handler: ResourceHandler,
      name: Option[String] = None,
      description: Option[String] = None,
      mimeType: Option[String] = Some("text/plain")
  ): ZIO[Any, Throwable, McpServer]

  def resourceTemplate(
      uriPattern: String,
      handler: ResourceTemplateHandler,
      name: Option[String] = None,
      description: Option[String] = None,
      mimeType: Option[String] = Some("text/plain"),
      arguments: Option[List[ResourceArgument]] = None
  ): ZIO[Any, Throwable, McpServer]

  // --- Prompt registration ---

  def prompt(
      name: String,
      handler: PromptHandler,
      description: Option[String] = None,
      arguments: Option[List[PromptArgument]] = None
  ): ZIO[Any, Throwable, McpServer]

  // --- Server lifecycle ---

  def runStdio(): ZIO[Any, Throwable, Unit]

  def runHttp(): ZIO[Any, Throwable, Unit]
