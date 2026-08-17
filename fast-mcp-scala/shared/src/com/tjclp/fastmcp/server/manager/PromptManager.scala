package com.tjclp.fastmcp
package server.manager

import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters.*

import zio.*

import core.*
import jsonrpc.{McpError, McpErrorCarrier}
import server.*

/** Function type for prompt handlers Takes arguments and returns a list of Messages wrapped in ZIO.
  *
  * Parameterized on the ZIO environment `R`. A handler that declares `ZIO[Client, Throwable, ...]`
  * may be stored on a manager whose `R = Client` (or a subtype), so the user-supplied environment
  * (passed via `server.runHttp().provide(...)`) reaches the handler at execution time.
  */
type PromptHandler[R] = Map[String, Any] => ZIO[R, Throwable, List[Message]]

/** Context-aware prompt handler: like [[PromptHandler]] but also receives the request's
  * [[McpContext]], so a prompt can drive MRTR input requests (`ctx.sendRequest`) or read request
  * metadata — mirroring contextual tool handlers.
  */
type ContextualPromptHandler[R] =
  (Map[String, Any], Option[McpContext]) => ZIO[R, Throwable, List[Message]]

/** Manager for MCP prompts
  *
  * Responsible for registering, storing, and rendering prompts.
  *
  * @tparam R
  *   the ZIO environment all stored handlers may require; supplied by the server at `runHttp[R]()`
  *   / `runStdio[R]()` entry.
  */
class PromptManager[R] extends Manager[PromptDefinition]:

  // Thread-safe storage for registered prompts (stored context-aware; plain handlers are adapted)
  private val prompts =
    new ConcurrentHashMap[String, (PromptDefinition, ContextualPromptHandler[R])]()

  /** Register a prompt with the manager
    *
    * @param name
    *   Prompt name
    * @param handler
    *   Function to execute when the prompt is rendered
    * @param definition
    *   Prompt definition
    * @return
    *   ZIO effect that completes with Unit on success or fails with PromptRegistrationError
    */
  def addPrompt(
      name: String,
      handler: PromptHandler[R],
      definition: PromptDefinition
  ): ZIO[Any, Throwable, Unit] =
    addContextualPrompt(name, (args, _) => handler(args), definition)

  /** Register a context-aware prompt (see [[ContextualPromptHandler]]). */
  def addContextualPrompt(
      name: String,
      handler: ContextualPromptHandler[R],
      definition: PromptDefinition
  ): ZIO[Any, Throwable, Unit] =
    ZIO
      .attempt {
        prompts.put(name, (definition, handler))
        ()
      }
      .mapError(e => new PromptRegistrationError(s"Failed to register prompt '$name'", Some(e)))

  /** List all registered prompt definitions
    */
  override def listDefinitions(): List[PromptDefinition] =
    prompts.values().asScala.map(_._1).toList

  /** Render a prompt by name with the provided arguments
    *
    * Validates that all required arguments are provided before executing the handler
    *
    * @param name
    *   Prompt name
    * @param arguments
    *   Arguments to pass to the prompt
    * @param context
    *   Optional context for the prompt rendering
    * @return
    *   ZIO effect that completes with the prompt messages or fails with Throwable
    */
  @scala.annotation.nowarn("msg=unused explicit parameter")
  def getPrompt(
      name: String,
      arguments: Map[String, Any],
      context: Option[McpContext]
  ): ZIO[R, Throwable, List[Message]] =
    getPromptHandler(name) match
      case Some(handler) =>
        // Get the prompt definition to validate required arguments
        getPromptDefinition(name).flatMap(definition =>
          // Check for required arguments
          val missingArgs = definition.arguments
            .getOrElse(List.empty)
            .filter(_.required)
            .map(_.name)
            .filterNot(arguments.contains)

          if missingArgs.nonEmpty then
            Some(
              ZIO.fail(
                new PromptArgumentError(
                  s"Missing required arguments for prompt '$name': ${missingArgs.mkString(", ")}"
                )
              )
            )
          else None
        ) match
          case Some(error) => error
          case None =>
            handler(arguments, context).mapError {
              // McpErrors pass through untouched — the MRTR input_required sentinel in
              // particular must reach the router intact to become an InputRequiredResult.
              case m: McpError => m
              case e => new PromptExecutionError(s"Error rendering prompt '$name'", Some(e))
            }

      case None =>
        ZIO.fail(new PromptNotFoundError(s"Prompt '$name' not found"))

  /** Get a prompt handler by name
    *
    * @param name
    *   Prompt name
    * @return
    *   Option containing the handler if found
    */
  def getPromptHandler(name: String): Option[ContextualPromptHandler[R]] =
    Option(prompts.get(name)).map(_._2)

  /** Get a prompt definition by name
    *
    * @param name
    *   Prompt name
    * @return
    *   Option containing the definition if found
    */
  def getPromptDefinition(name: String): Option[PromptDefinition] =
    Option(prompts.get(name)).map(_._1)

/** Custom exceptions for prompt operations
  */
@SuppressWarnings(Array("org.wartremover.warts.Null"))
class PromptError(message: String, cause: Option[Throwable] = None)
    extends RuntimeException(message, cause.orNull)

class PromptNotFoundError(message: String) extends PromptError(message) with McpErrorCarrier:
  // Unknown prompt name is bad input, not a server fault.
  def toMcpError: McpError = McpError.invalidParams(message)

class PromptRegistrationError(message: String, cause: Option[Throwable] = None)
    extends PromptError(message, cause)

class PromptExecutionError(message: String, cause: Option[Throwable] = None)
    extends PromptError(message, cause)

class PromptArgumentError(message: String) extends PromptError(message) with McpErrorCarrier:
  // Missing/invalid prompt arguments are a request problem (-32602).
  def toMcpError: McpError = McpError.invalidParams(message)
