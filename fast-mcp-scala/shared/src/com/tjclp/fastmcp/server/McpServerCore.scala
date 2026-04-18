package com.tjclp.fastmcp
package server

import zio.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.server.manager.ContextualToolHandler
import com.tjclp.fastmcp.server.manager.PromptHandler
import com.tjclp.fastmcp.server.manager.ResourceHandler
import com.tjclp.fastmcp.server.manager.ResourceTemplateHandler
import com.tjclp.fastmcp.server.manager.ToolRegistrationOptions

/** Platform-independent MCP server API.
  *
  * This is the trait that macros (`scanAnnotations`, `@Tool`, `@Resource`, `@Prompt`) target. Users
  * write against this API; the JVM backend (`FastMcpServer`) and future JS backends delegate to
  * their respective runtime implementations.
  */
trait McpServerCore:

  /** Platform-specific decode context used by typed contract mounting. */
  protected def decodeContext: McpDecodeContext

  // --- Tool registration ---

  def tool(
      definition: ToolDefinition,
      handler: ContextualToolHandler,
      options: ToolRegistrationOptions
  ): ZIO[Any, Throwable, McpServerCore]

  def tool(
      definition: ToolDefinition,
      handler: ContextualToolHandler
  ): ZIO[Any, Throwable, McpServerCore] =
    tool(definition, handler, ToolRegistrationOptions())

  def tool(
      name: String,
      handler: ContextualToolHandler,
      description: Option[String] = None,
      inputSchema: ToolInputSchema = ToolInputSchema.default,
      options: ToolRegistrationOptions = ToolRegistrationOptions(),
      annotations: Option[ToolAnnotations] = None
  ): ZIO[Any, Throwable, McpServerCore] =
    tool(
      definition = ToolDefinition(
        name = name,
        description = description,
        inputSchema = inputSchema,
        annotations = annotations
      ),
      handler = handler,
      options = options
    )

  def tool[In, Out](
      contract: McpTool[In, Out]
  ): ZIO[Any, Throwable, McpServerCore] =
    tool(contract, ToolRegistrationOptions())

  def tool[In, Out](
      contract: McpTool[In, Out],
      options: ToolRegistrationOptions
  ): ZIO[Any, Throwable, McpServerCore] =
    tool(
      definition = contract.definition,
      handler = (args: Map[String, Any], ctxOpt: Option[McpContext]) =>
        ZIO
          .attempt(contract.decoder.decode(contract.definition.name, args, decodeContext))
          .flatMap(input => contract.handler(input, ctxOpt))
          .map(contract.encoder.encode),
      options = options
    )

  // --- Resource registration ---

  def resource(
      definition: ResourceDefinition,
      handler: ResourceHandler
  ): ZIO[Any, Throwable, McpServerCore]

  def resource(
      uri: String,
      handler: ResourceHandler,
      name: Option[String] = None,
      description: Option[String] = None,
      mimeType: Option[String] = Some("text/plain")
  ): ZIO[Any, Throwable, McpServerCore] =
    resource(
      definition = ResourceDefinition(
        uri = uri,
        name = name,
        description = description,
        mimeType = mimeType,
        isTemplate = false,
        arguments = None
      ),
      handler = handler
    )

  def resource(
      contract: McpStaticResource
  ): ZIO[Any, Throwable, McpServerCore] =
    resource(contract.definition, contract.handler)

  def resource[In](
      contract: McpTemplateResource[In]
  ): ZIO[Any, Throwable, McpServerCore] =
    resourceTemplate(contract)

  // --- Template resource registration ---

  def resourceTemplate(
      definition: ResourceDefinition,
      handler: ResourceTemplateHandler
  ): ZIO[Any, Throwable, McpServerCore]

  def resourceTemplate(
      uriPattern: String,
      handler: ResourceTemplateHandler,
      name: Option[String] = None,
      description: Option[String] = None,
      mimeType: Option[String] = Some("text/plain"),
      arguments: Option[List[ResourceArgument]] = None
  ): ZIO[Any, Throwable, McpServerCore] =
    resourceTemplate(
      definition = ResourceDefinition(
        uri = uriPattern,
        name = name,
        description = description,
        mimeType = mimeType,
        isTemplate = true,
        arguments = arguments
      ),
      handler = handler
    )

  def resourceTemplate[In](
      contract: McpTemplateResource[In]
  ): ZIO[Any, Throwable, McpServerCore] =
    resourceTemplate(
      definition = contract.definition,
      handler = params =>
        ZIO
          .attempt(contract.decoder.decode(contract.definition.uri, params, decodeContext))
          .flatMap(contract.handler)
    )

  // --- Prompt registration ---

  def prompt(
      definition: PromptDefinition,
      handler: PromptHandler
  ): ZIO[Any, Throwable, McpServerCore]

  def prompt(
      name: String,
      handler: PromptHandler,
      description: Option[String] = None,
      arguments: Option[List[PromptArgument]] = None
  ): ZIO[Any, Throwable, McpServerCore] =
    prompt(
      definition = PromptDefinition(name, description, arguments),
      handler = handler
    )

  def prompt[In](
      contract: McpPrompt[In]
  ): ZIO[Any, Throwable, McpServerCore] =
    prompt(
      definition = contract.definition,
      handler = args =>
        ZIO
          .attempt(contract.decoder.decode(contract.definition.name, args, decodeContext))
          .flatMap(contract.handler)
    )

  // --- Server lifecycle ---

  def runStdio(): ZIO[Any, Throwable, Unit]

  def runHttp(): ZIO[Any, Throwable, Unit]
