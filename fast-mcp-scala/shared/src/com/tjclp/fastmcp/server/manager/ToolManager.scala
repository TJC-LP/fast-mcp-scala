package com.tjclp.fastmcp
package server.manager

import java.lang.System as JSystem
import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters.*

import zio.*
import zio.json.ast.Json

import codec.DefaultDecodeContext
import core.*
import server.*

/** Context-aware tool handler.
  *
  * Parameterized on the ZIO environment `R`. A handler that declares `ZIO[Client, Throwable, ...]`
  * may be stored on a manager whose `R = Client` (or a subtype), so the user-supplied environment
  * (passed via `server.runHttp().provide(...)`) reaches the handler at execution time.
  */
type ContextualToolHandler[R] = (Map[String, Any], Option[McpContext]) => ZIO[R, Throwable, Any]

/** Options for tool registration
  *
  * @param validateInputSchema
  *   Check every call's arguments against the tool's advertised `inputSchema` before the handler
  *   runs (default). A call that does not conform fails with [[ToolArgumentError]] — on the wire an
  *   `isError` tool result, the same answer an undecodable argument gets — and the handler is not
  *   invoked. `false` restores decode-only behaviour for this one tool: the schema is then
  *   advertised but not enforced, and the handler must check whatever it relies on. Has no effect
  *   when the server was built with `McpServerSettings(validateToolInput = false)`. What is and is
  *   not enforced: [[com.tjclp.fastmcp.core.JsonSchemaValidator]].
  * @param validateOutputSchema
  *   Reserved; results are not validated against `outputSchema`.
  */
case class ToolRegistrationOptions(
    validateInputSchema: Boolean = true,
    validateOutputSchema: Boolean = false,
    allowOverrides: Boolean = false,
    warnOnDuplicates: Boolean = true
)

/** Whether one tool's `tools/call` arguments are checked against its advertised `inputSchema`.
  *
  * @param enforced
  *   `true` when a non-conforming call is rejected before the handler runs; `false` when validation
  *   is off for the tool (`ToolRegistrationOptions(validateInputSchema = false)`) or for the whole
  *   server (`McpServerSettings(validateToolInput = false)`)
  * @param unenforced
  *   what the schema declares that is NOT checked even when `enforced` is true — keywords outside
  *   the supported subset (`pattern`, `format`, `uniqueItems`, ...) and unusable `$ref`s; see
  *   [[com.tjclp.fastmcp.core.JsonSchemaValidator]]. A handler that relies on one of these must
  *   check it itself.
  */
case class ToolInputValidation(enforced: Boolean, unenforced: List[String])

/** Manager for MCP tools
  *
  * Responsible for registering, storing, and executing tools.
  *
  * @param validateInput
  *   server-wide switch for input-schema enforcement (`McpServerSettings.validateToolInput`). When
  *   `true` (default) each tool follows its own `ToolRegistrationOptions.validateInputSchema`; when
  *   `false` no tool's arguments are validated.
  * @tparam R
  *   the ZIO environment all stored handlers may require; supplied by the server at `runHttp[R]()`
  *   / `runStdio[R]()` entry.
  */
class ToolManager[R](validateInput: Boolean = true) extends Manager[ToolDefinition]:

  // Binary compatibility with 1.0.x: the no-argument constructor. `validateInput` takes its default.
  private[fastmcp] def this() = this(true)

  // Thread-safe storage for registered tools - public for direct access in examples
  val tools = new ConcurrentHashMap[String, (ToolDefinition, ContextualToolHandler[R])]()

  // The compiled inputSchema of each tool, next to (not inside) `tools` so that map keeps its
  // public shape. Keyed by name and tied to the exact definition it was compiled from.
  private val inputChecks = new ConcurrentHashMap[String, ToolManager.InputCheck]()

  // The tools registered with deferred task creation (`McpTool.withDeferredTask`), by name, each
  // with the definition it was registered with. Next to `tools` for the same reason as
  // `inputChecks`; an entry counts only while `tools` still holds that very definition.
  private val deferredTasks = new ConcurrentHashMap[String, ToolDefinition]()

  private def checkToolConflict(
      name: String,
      options: ToolRegistrationOptions
  ): Unit = // Check if tool already exists
    // Check if tool already exists
    if tools.containsKey(name) && !options.allowOverrides then
      if options.warnOnDuplicates then
        JSystem.err.println(
          s"[ToolManager] Warning: Tool '$name' already exists and will be overwritten"
        )
      else throw new ToolRegistrationError(s"Tool '$name' already exists")

  /** Register a context-aware tool handler directly
    *
    * @param name
    *   Tool name
    * @param handler
    *   Context-aware function to execute when the tool is called
    * @param definition
    *   Tool definition including description and schema
    */
  def addTool(
      name: String,
      handler: ContextualToolHandler[R],
      definition: ToolDefinition,
      options: ToolRegistrationOptions = ToolRegistrationOptions()
  ): ZIO[Any, Throwable, Unit] =
    addTool(name, handler, definition, options, deferTask = false)

  /** [[addTool]] for a typed contract: `deferTask` is `McpTool.withDeferredTask`, see
    * [[isTaskDeferred]].
    */
  private[fastmcp] def addTool(
      name: String,
      handler: ContextualToolHandler[R],
      definition: ToolDefinition,
      options: ToolRegistrationOptions,
      deferTask: Boolean
  ): ZIO[Any, Throwable, Unit] =
    ZIO
      .attempt {
        checkToolConflict(name, options)
        // Compiled first (so a schema is never half-registered), stored second: a call that sees
        // the new definition before its check is stored validates it on the spot (inputCheckFor).
        val check = ToolManager.InputCheck(definition, options.validateInputSchema)
        tools.put(name, (definition, handler))
        inputChecks.put(name, check)
        if deferTask then deferredTasks.put(name, definition) else deferredTasks.remove(name)
        ()
      }

  /** Whether calls to `name` start as ordinary requests and become tasks only where the handler
    * calls `McpContext.continueAsTask` (`McpTool.withDeferredTask`). `false` for a tool that
    * creates its task before the handler runs, and for an unknown tool.
    */
  def isTaskDeferred(name: String): Boolean =
    Option(deferredTasks.get(name)).exists(deferred =>
      getToolDefinition(name).exists(_ eq deferred)
    )

  /** The compiled input check for the definition currently stored under `name`. A definition that
    * did not come through [[addTool]] (written straight into [[tools]]) is compiled on first use
    * with validation requested, the registration default — and cached only if no registration
    * stored a check in the meantime, so a concurrent [[addTool]] always has the last word.
    */
  private def inputCheckFor(name: String, definition: ToolDefinition): ToolManager.InputCheck =
    val stored = Option(inputChecks.get(name))
    stored.filter(_.definition eq definition).getOrElse {
      val check = ToolManager.InputCheck(definition, requested = true)
      stored match
        case Some(stale) => inputChecks.replace(name, stale, check)
        case None => inputChecks.putIfAbsent(name, check)
      check
    }

  /** Whether calls to `name` are validated against its `inputSchema`, and what that schema declares
    * that the validator does not enforce. `None` for an unknown tool.
    */
  def inputValidation(name: String): Option[ToolInputValidation] =
    getToolDefinition(name).map { definition =>
      val check = inputCheckFor(name, definition)
      ToolInputValidation(validateInput && check.requested, check.validator.unenforced)
    }

  /** [[inputValidation]] for every registered tool, by tool name. */
  def inputValidationReport: Map[String, ToolInputValidation] =
    tools.keySet().asScala.iterator.flatMap(name => inputValidation(name).map(name -> _)).toMap

  /** Reject arguments that do not conform to the tool's advertised `inputSchema`. Wire arguments
    * are already `Json`; values passed in process go through the same coercion the default decoder
    * applies (`DefaultDecodeContext.toJsonAst`), so the validator sees what the decoder will see.
    */
  private def checkArguments(
      name: String,
      definition: ToolDefinition,
      arguments: Map[String, Any]
  ): ZIO[Any, Throwable, Unit] =
    if !validateInput then ZIO.unit
    else
      val check = inputCheckFor(name, definition)
      if !check.requested || !check.validator.constrains then ZIO.unit
      else
        ZIO
          .attempt {
            val fields = Chunk.fromIterator(
              arguments.iterator.map((key, value) => key -> DefaultDecodeContext.toJsonAst(value))
            )
            check.validator.validate(Json.Obj(fields))
          }
          .flatMap {
            case None => ZIO.unit
            case Some(reason) =>
              ZIO.fail(new ToolArgumentError(s"Invalid arguments for tool '$name': $reason"))
          }

  /** List all registered tool definitions
    */
  override def listDefinitions(): List[ToolDefinition] =
    tools.values().asScala.map(_._1).toList

  /** Call a tool by name with the provided arguments
    *
    * @param name
    *   Tool name
    * @param arguments
    *   Arguments to pass to the tool
    * @param context
    *   Optional context for the tool execution
    * @return
    *   ZIO effect that completes with the tool's result or fails with Throwable (errors will be
    *   converted to CallToolResult with isError=true at handler level). Arguments that do not
    *   conform to the tool's `inputSchema` fail with [[ToolArgumentError]] before the handler runs,
    *   unless validation is off for the tool or the server (see [[inputValidation]]).
    */
  def callTool(
      name: String,
      arguments: Map[String, Any],
      context: Option[McpContext]
  ): ZIO[R, Throwable, Any] =
    for
      // One read, so the definition validated against is the one whose handler runs.
      entry <- ZIO
        .fromOption(Option(tools.get(name)))
        .orElseFail(new ToolNotFoundError(s"Tool '$name' not found"))
      (definition, handler) = entry

      // Enforce the advertised inputSchema before any handler code runs.
      _ <- checkArguments(name, definition, arguments)

      // Call the handler
      result <- handler(arguments, context)
    yield result

  /** Get a tool handler by name
    *
    * @param name
    *   Tool name
    * @return
    *   Option containing the handler if found
    */
  def getToolHandler(name: String): Option[ContextualToolHandler[R]] =
    Option(tools.get(name)).map(_._2)

  /** Get a tool definition by name
    *
    * @param name
    *   Tool name
    * @return
    *   Option containing the definition if found
    */
  def getToolDefinition(name: String): Option[ToolDefinition] =
    Option(tools.get(name)).map(_._1)

object ToolManager:

  /** A tool's compiled `inputSchema`, tied to the definition it was compiled from.
    *
    * @param requested
    *   the registration's `ToolRegistrationOptions.validateInputSchema`
    */
  private final class InputCheck(
      val definition: ToolDefinition,
      val requested: Boolean,
      val validator: JsonSchemaValidator
  )

  private object InputCheck:

    def apply(definition: ToolDefinition, requested: Boolean): InputCheck =
      new InputCheck(definition, requested, JsonSchemaValidator.compile(definition.inputSchema))

/** Custom exceptions for tool operations
  */
@SuppressWarnings(Array("org.wartremover.warts.Null"))
class ToolError(message: String, cause: Option[Throwable] = None)
    extends RuntimeException(message, cause.orNull)

class ToolNotFoundError(message: String) extends ToolError(message)

class ToolRegistrationError(message: String, cause: Option[Throwable] = None)
    extends ToolError(message, cause)

class ToolExecutionError(message: String, cause: Option[Throwable] = None)
    extends ToolError(message, cause)

/** A call's arguments do not conform to the tool's advertised `inputSchema`. Raised by
  * `ToolManager.callTool` before the handler runs; on the wire it becomes an `isError` tool result
  * carrying the message, like any other failed call.
  */
class ToolArgumentError(message: String) extends ToolError(message)
