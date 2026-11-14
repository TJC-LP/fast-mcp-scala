package com.tjclp.fastmcp
package server

import scala.annotation.unused

import zio.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.core.skills.McpSkill
import com.tjclp.fastmcp.server.skills.SkillProvider
import com.tjclp.fastmcp.server.manager.ContextualPromptHandler
import com.tjclp.fastmcp.server.manager.ContextualToolHandler
import com.tjclp.fastmcp.server.manager.PromptHandler
import com.tjclp.fastmcp.server.manager.ResourceHandler
import com.tjclp.fastmcp.server.manager.ResourceTemplateHandler
import com.tjclp.fastmcp.server.manager.ToolInputValidation
import com.tjclp.fastmcp.server.manager.ToolRegistrationOptions

/** Platform-independent MCP server API.
  *
  * This is the trait that macros (`scanAnnotations`, `@Tool`, `@Resource`, `@Prompt`) target. Users
  * write against this API; the single shared [[McpServer]] implements it, with platform differences
  * confined to the `TransportBackend` given.
  *
  * @tparam R
  *   the ZIO environment the server provides to all handlers. The default `McpServer("name")`
  *   factory pins `R = Any`; for layer-aware servers use `McpServer.typed[Client]("name")` and
  *   supply the layer via `server.runHttp().provide(...)`.
  *
  * Handler-accepting methods carry an `[R1 >: R]` bound: any handler whose environment is wider
  * than or equal to the server's `R` is acceptable. Concretely, a handler returning `ZIO[Client,
  * ...]` mounts on `McpServer[Client]` *and* on `McpServer[Client & Database]`, but not on
  * `McpServer[Any]` (since `Any` doesn't provide `Client`).
  */
trait McpServerCore[R]:

  /** Decode context used by typed contract mounting (the shared zio-json `DefaultDecodeContext`).
    */
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

  /** Registration of a typed contract: [[tool]] plus `McpTool.withDeferredTask`. [[McpServer]]
    * records the flag. The default cannot: an implementation that is not an `McpServer` (a core
    * from a custom `McpServerCoreFactory`, say one that wraps a server) has no way to receive it,
    * so a deferred tool fails to register there instead of silently creating its task up front.
    */
  private[fastmcp] def registerTool[R1 >: R](
      definition: ToolDefinition,
      handler: ContextualToolHandler[R1],
      options: ToolRegistrationOptions,
      deferTask: Boolean
  ): ZIO[Any, Throwable, McpServerCore[R]] =
    if deferTask then ZIO.fail(McpServerCore.deferredTaskUnsupported(this, definition.name))
    else tool(definition, handler, options)

  def tool[In, Out, R1 >: R](
      contract: McpTool.WithEnv[In, Out, R1]
  ): ZIO[Any, Throwable, McpServerCore[R]] =
    tool(contract, ToolRegistrationOptions())

  def tool[In, Out, R1 >: R](
      contract: McpTool.WithEnv[In, Out, R1],
      options: ToolRegistrationOptions
  ): ZIO[Any, Throwable, McpServerCore[R]] =
    registerTool[R1](
      deferTask = contract.deferTask,
      definition = contract.definition,
      handler = (args: Map[String, Any], ctxOpt: Option[McpContext]) =>
        ZIO
          .attempt(contract.decoder.decode(contract.definition.name, args, decodeContext))
          .mapError(McpServerCore.toolArgumentError(contract.definition.name, _))
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
    registerTool[Any](
      deferTask = contract.deferTask,
      definition = contract.definition,
      handler = (args: Map[String, Any], ctxOpt: Option[McpContext]) =>
        ZIO
          .attempt(contract.decoder.decode(contract.definition.name, args, decodeContext))
          .mapError(McpServerCore.toolArgumentError(contract.definition.name, _))
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

  /** For each registered tool, whether `tools/call` arguments are validated against its advertised
    * `inputSchema` before the handler runs, and what that schema declares that is not enforced.
    * Validation is on unless `McpServerSettings(validateToolInput = false)` or the tool's
    * `ToolRegistrationOptions(validateInputSchema = false)` turns it off. The default here (an
    * empty map) is for implementations that do not validate; [[McpServer]] reports every tool.
    */
  def toolInputValidation: Map[String, ToolInputValidation] = Map.empty

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

  /** Register a context-aware prompt: the handler also receives the request's [[McpContext]], so it
    * can drive MRTR input requests or read request metadata (parity with contextual tools).
    */
  def promptContextual[R1 >: R](
      definition: PromptDefinition,
      handler: ContextualPromptHandler[R1]
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
    promptContextual[R1](
      definition = contract.definition,
      handler = (args: Map[String, Any], ctx: Option[McpContext]) =>
        ZIO
          .attempt(contract.decoder.decode(contract.definition.name, args, decodeContext))
          .flatMap(in => contract.handler(in, ctx))
    )

  def prompt[In](
      contract: McpPrompt[In]
  ): ZIO[Any, Throwable, McpServerCore[R]] =
    promptContextual[Any](
      definition = contract.definition,
      handler = (args: Map[String, Any], ctx: Option[McpContext]) =>
        ZIO
          .attempt(contract.decoder.decode(contract.definition.name, args, decodeContext))
          .flatMap(in => contract.handler(in, ctx))
    )

  // --- Skills registration (io.modelcontextprotocol/skills) ---

  /** Publish skills atomically: every skill is validated, hashed and checked for conflicts with the
    * catalog and the resource registrations, and only then does the whole batch become visible
    * (`skills/list`, `skills/get`, `resources/read`, `resources/directory/read`). A skill whose
    * root is already published is replaced. Registering a skill declares the extension. Fails with
    * [[com.tjclp.fastmcp.core.skills.SkillError.Exception]] and publishes nothing on any problem.
    *
    * `skills`, `removeSkills` and `skillProvider` were added in 1.1.0. They are not abstract, so an
    * implementation compiled against 1.0.x still links; their default fails with an
    * `UnsupportedOperationException` naming the implementation. [[McpServer]] implements all three.
    */
  def skills(@unused skills: List[McpSkill]): ZIO[Any, Throwable, McpServerCore[R]] =
    ZIO.fail(McpServerCore.skillsUnsupported(this, "skills"))

  /** Publish one skill (see [[skills]]). */
  def skill(skill: McpSkill): ZIO[Any, Throwable, McpServerCore[R]] = skills(List(skill))

  /** Atomically withdraw the skills rooted at the given URIs (`skill://acme/refunds`). Clients
    * holding an older entry see verification failures on read and refresh via `skills/get`.
    */
  def removeSkills(@unused rootUris: List[String]): ZIO[Any, Throwable, McpServerCore[R]] =
    ZIO.fail(McpServerCore.skillsUnsupported(this, "removeSkills"))

  /** Mount a [[SkillProvider]] — a generated, remote or authorization-scoped catalog. Its
    * namespaces must not overlap published skills or other providers.
    */
  def skillProvider[R1 >: R](
      @unused provider: SkillProvider[R1]
  ): ZIO[Any, Throwable, McpServerCore[R]] =
    ZIO.fail(McpServerCore.skillsUnsupported(this, "skillProvider"))

  // --- Server lifecycle ---

  def runStdio(): ZIO[R, Throwable, Unit]

  /** Serve over HTTP. Takes the platform [[transport.HttpTransportBackend]] as a `using` parameter
    * (rather than capturing it at construction) so programs that never call `runHttp()` have no
    * reachable path into the HTTP stack — a stdio-only GraalVM native image sheds zio-http/Netty
    * entirely, and Scala.js DCE drops `Bun.serve`.
    */
  def runHttp()(using transport.HttpTransportBackend): ZIO[R, Throwable, Unit]

object McpServerCore:

  /** The failure of decoding a typed tool's arguments, worded for the caller of the tool.
    *
    * The default decoders are handed the TOOL name as the name of what they decode, so their
    * messages read `Failed to decode parameter '<tool>' from JSON: ...`, which calls the tool a
    * parameter. Those messages are rewritten here, where the tool is known:
    *   - a missing top-level member (zio-json's `.name(missing)`) becomes `Missing required
    *     argument 'name'`, a `NoSuchElementException`: exactly what an annotated tool reports for
    *     the same call, so the two registration paths agree when input validation is off (with
    *     validation on, the schema check answers first, with one message for both);
    *   - any other decode failure becomes `Failed to decode the arguments of tool '<tool>': ...`;
    *   - a number outside the input limits becomes `Arguments of tool '<tool>' rejected: ...`.
    *
    * Failures of a user's own `McpDecoder` are returned as they are.
    */
  private[fastmcp] def toolArgumentError(tool: String, error: Throwable): Throwable =
    val decodePrefix = s"Failed to decode parameter '$tool' from JSON: "
    val rejectPrefix = s"Parameter '$tool' rejected: "
    val message = Option(error.getMessage).getOrElse("")
    error match
      case _: IllegalArgumentException if message.startsWith(rejectPrefix) =>
        new IllegalArgumentException(
          s"Arguments of tool '$tool' rejected: ${message.drop(rejectPrefix.length)}"
        )
      case e: RuntimeException
          if e.getClass == classOf[RuntimeException] && message.startsWith(decodePrefix) =>
        val detail = message.drop(decodePrefix.length)
        missingTopLevelMember(detail) match
          case Some(name) => new NoSuchElementException(s"Missing required argument '$name'")
          case None =>
            new RuntimeException(s"Failed to decode the arguments of tool '$tool': $detail")
      case other => other

  /** `Some(name)` when a zio-json decode error says that the top-level member `name` is absent: the
    * text `.name(missing)`, alone or followed by the `. Value: ...` preview.
    */
  private def missingTopLevelMember(detail: String): Option[String] =
    val marker = "(missing)"
    val end = detail.indexOf(marker)
    if !detail.startsWith(".") || end < 2 then None
    else
      val name = detail.substring(1, end)
      val rest = detail.substring(end + marker.length)
      val nested = name.exists(c => c == '.' || c == '[' || c == '(' || c == ')')
      if !nested && (rest.isEmpty || rest.startsWith(". Value: ")) then Some(name) else None

  /** The failure the default `skills` / `removeSkills` / `skillProvider` return on an
    * implementation that does not override them.
    */
  /** The failure of registering a `McpTool.withDeferredTask` tool on an implementation that is not
    * an [[McpServer]].
    */
  private[server] def deferredTaskUnsupported(
      server: McpServerCore[?],
      tool: String
  ): UnsupportedOperationException =
    new UnsupportedOperationException(
      s"${server.getClass.getName} cannot register tool '$tool': it was built with " +
        "McpTool.withDeferredTask, which only McpServer supports (added in fast-mcp-scala " +
        "1.1.0). Register the tool on the McpServer itself, or drop withDeferredTask."
    )

  private[server] def skillsUnsupported(
      server: McpServerCore[?],
      method: String
  ): UnsupportedOperationException =
    new UnsupportedOperationException(
      s"${server.getClass.getName} does not implement McpServerCore.$method: it does not support " +
        "the Skills extension (io.modelcontextprotocol/skills). The method was added in " +
        "fast-mcp-scala 1.1.0; McpServer implements it."
    )
