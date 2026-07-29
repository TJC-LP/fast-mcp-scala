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
  * write against this API; the JVM backend (`FastMcpServer`) and JS backend (`JsMcpServer`)
  * delegate to their respective runtime implementations.
  *
  * @tparam R
  *   the ZIO environment the server provides to all handlers. The default `McpServer("name")`
  *   factory pins `R = Any`; for layer-aware servers use `FastMcpServer[Client]("name")` (or
  *   `McpServer.typed[Client]`) and supply the layer via `server.runHttp().provide(...)`.
  *
  * Handler-accepting methods carry an `[R1 >: R]` bound: any handler whose environment is wider
  * than or equal to the server's `R` is acceptable. Concretely, a handler returning `ZIO[Client,
  * ...]` mounts on `McpServer[Client]` *and* on `McpServer[Client & Database]`, but not on
  * `McpServer[Any]` (since `Any` doesn't provide `Client`).
  */
trait McpServerCore[R]:

  /** Platform-specific decode context used by typed contract mounting. */
  protected def decodeContext: McpDecodeContext

  // --- Tool registration ---

  def tool[R1 >: R](
      definition: ToolDefinition,
      handler: ContextualToolHandler[R1],
      options: ToolRegistrationOptions
  ): ZIO[Any, Throwable, McpServerCore[R]]

  def tool[R1 >: R](
      definition: ToolDefinition,
      handler: ContextualToolHandler[R1]
  ): ZIO[Any, Throwable, McpServerCore[R]] =
    tool(definition, handler, ToolRegistrationOptions())

  def tool[R1 >: R](
      name: String,
      handler: ContextualToolHandler[R1],
      description: Option[String] = None,
      inputSchema: ToolInputSchema = ToolInputSchema.default,
      options: ToolRegistrationOptions = ToolRegistrationOptions(),
      annotations: Option[ToolAnnotations] = None,
      taskSupport: Option[TaskSupport] = None
  ): ZIO[Any, Throwable, McpServerCore[R]] =
    tool[R1](
      definition = ToolDefinition(
        name = name,
        description = description,
        inputSchema = inputSchema,
        annotations = annotations,
        taskSupport = taskSupport
      ),
      handler = handler,
      options = options
    )

  def tool[In, Out, R1 >: R](
      contract: McpTool.WithEnv[In, Out, R1]
  ): ZIO[Any, Throwable, McpServerCore[R]] =
    tool(contract, ToolRegistrationOptions())

  def tool[In, Out, R1 >: R](
      contract: McpTool.WithEnv[In, Out, R1],
      options: ToolRegistrationOptions
  ): ZIO[Any, Throwable, McpServerCore[R]] =
    tool[R1](
      definition = contract.definition,
      handler = (args: Map[String, Any], ctxOpt: Option[McpContext]) =>
        ZIO
          .attempt(contract.decoder.decode(contract.definition.name, args, decodeContext))
          .flatMap(input => contract.handler(input, ctxOpt))
          // Carry both renderings while `Out` is still known; the wire layer emits
          // structuredContent from it only when the tool declares an outputSchema.
          .map(out =>
            core.StructuredToolResult(
              contract.encoder.encode(out),
              contract.encoder.encodeStructured(out)
            )
          ),
      options = options
    )

  def tool[In, Out](
      contract: McpTool[In, Out]
  ): ZIO[Any, Throwable, McpServerCore[R]] =
    tool(contract, ToolRegistrationOptions())

  def tool[In, Out](
      contract: McpTool[In, Out],
      options: ToolRegistrationOptions
  ): ZIO[Any, Throwable, McpServerCore[R]] =
    tool[Any](
      definition = contract.definition,
      handler = (args: Map[String, Any], ctxOpt: Option[McpContext]) =>
        ZIO
          .attempt(contract.decoder.decode(contract.definition.name, args, decodeContext))
          .flatMap(input => contract.handler(input, ctxOpt))
          // Carry both renderings while `Out` is still known; the wire layer emits
          // structuredContent from it only when the tool declares an outputSchema.
          .map(out =>
            core.StructuredToolResult(
              contract.encoder.encode(out),
              contract.encoder.encodeStructured(out)
            )
          ),
      options = options
    )

  // --- Resource registration ---

  def resource[R1 >: R](
      definition: ResourceDefinition,
      handler: ResourceHandler[R1]
  ): ZIO[Any, Throwable, McpServerCore[R]]

  def resource[R1 >: R](
      uri: String,
      handler: ResourceHandler[R1],
      name: Option[String] = None,
      description: Option[String] = None,
      mimeType: Option[String] = Some("text/plain")
  ): ZIO[Any, Throwable, McpServerCore[R]] =
    resource[R1](
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

  def resource[R1 >: R](
      contract: McpStaticResource.WithEnv[R1]
  ): ZIO[Any, Throwable, McpServerCore[R]] =
    resource[R1](contract.definition, contract.handler)

  def resource(
      contract: McpStaticResource
  ): ZIO[Any, Throwable, McpServerCore[R]] =
    resource[Any](contract.definition, contract.handler)

  def resource[In, R1 >: R](
      contract: McpTemplateResource.WithEnv[In, R1]
  ): ZIO[Any, Throwable, McpServerCore[R]] =
    resourceTemplate[In, R1](contract)

  def resource[In](
      contract: McpTemplateResource[In]
  ): ZIO[Any, Throwable, McpServerCore[R]] =
    resourceTemplate[In](contract)

  // --- Template resource registration ---

  def resourceTemplate[R1 >: R](
      definition: ResourceDefinition,
      handler: ResourceTemplateHandler[R1]
  ): ZIO[Any, Throwable, McpServerCore[R]]

  def resourceTemplate[R1 >: R](
      uriPattern: String,
      handler: ResourceTemplateHandler[R1],
      name: Option[String] = None,
      description: Option[String] = None,
      mimeType: Option[String] = Some("text/plain"),
      arguments: Option[List[ResourceArgument]] = None
  ): ZIO[Any, Throwable, McpServerCore[R]] =
    resourceTemplate[R1](
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

  def resourceTemplate[In, R1 >: R](
      contract: McpTemplateResource.WithEnv[In, R1]
  ): ZIO[Any, Throwable, McpServerCore[R]] =
    resourceTemplate[R1](
      definition = contract.definition,
      handler = (params: Map[String, String]) =>
        ZIO
          .attempt(contract.decoder.decode(contract.definition.uri, params, decodeContext))
          .flatMap(contract.handler)
    )

  def resourceTemplate[In](
      contract: McpTemplateResource[In]
  ): ZIO[Any, Throwable, McpServerCore[R]] =
    resourceTemplate[Any](
      definition = contract.definition,
      handler = (params: Map[String, String]) =>
        ZIO
          .attempt(contract.decoder.decode(contract.definition.uri, params, decodeContext))
          .flatMap(contract.handler)
    )

  // --- Prompt registration ---

  def prompt[R1 >: R](
      definition: PromptDefinition,
      handler: PromptHandler[R1]
  ): ZIO[Any, Throwable, McpServerCore[R]]

  def prompt[R1 >: R](
      name: String,
      handler: PromptHandler[R1],
      description: Option[String] = None,
      arguments: Option[List[PromptArgument]] = None
  ): ZIO[Any, Throwable, McpServerCore[R]] =
    prompt[R1](
      definition = PromptDefinition(name, description, arguments),
      handler = handler
    )

  def prompt[In, R1 >: R](
      contract: McpPrompt.WithEnv[In, R1]
  ): ZIO[Any, Throwable, McpServerCore[R]] =
    prompt[R1](
      definition = contract.definition,
      handler = (args: Map[String, Any]) =>
        ZIO
          .attempt(contract.decoder.decode(contract.definition.name, args, decodeContext))
          .flatMap(contract.handler)
    )

  def prompt[In](
      contract: McpPrompt[In]
  ): ZIO[Any, Throwable, McpServerCore[R]] =
    prompt[Any](
      definition = contract.definition,
      handler = (args: Map[String, Any]) =>
        ZIO
          .attempt(contract.decoder.decode(contract.definition.name, args, decodeContext))
          .flatMap(contract.handler)
    )

  // --- Server lifecycle ---

  def runStdio(): ZIO[R, Throwable, Unit]

  def runHttp(): ZIO[R, Throwable, Unit]
