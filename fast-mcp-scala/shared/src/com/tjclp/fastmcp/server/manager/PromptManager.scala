package com.tjclp.fastmcp
package server.manager

import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters.*

import zio.*

import core.*
import server.*

/** Function type for prompt handlers Takes arguments and returns a list of Messages wrapped in ZIO
  */
type PromptHandler = Map[String, Any] => ZIO[Any, Throwable, List[Message]]

/** Manager for MCP prompts
  *
  * Responsible for registering, storing, and rendering prompts
  */
class PromptManager extends Manager[PromptDefinition]:
  // Thread-safe storage for registered prompts
  private val prompts = new ConcurrentHashMap[String, (PromptDefinition, PromptHandler)]()

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
      handler: PromptHandler,
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
  def getPrompt(
      name: String,
      arguments: Map[String, Any],
      context: Option[McpContext]
  ): ZIO[Any, Throwable, List[Message]] =
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
            handler(arguments)
              .mapError(e => new PromptExecutionError(s"Error rendering prompt '$name'", Some(e)))

      case None =>
        ZIO.fail(new PromptNotFoundError(s"Prompt '$name' not found"))

  /** Get a prompt handler by name
    *
    * @param name
    *   Prompt name
    * @return
    *   Option containing the handler if found
    */
  def getPromptHandler(name: String): Option[PromptHandler] =
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

class PromptNotFoundError(message: String) extends PromptError(message)

class PromptRegistrationError(message: String, cause: Option[Throwable] = None)
    extends PromptError(message, cause)

class PromptExecutionError(message: String, cause: Option[Throwable] = None)
    extends PromptError(message, cause)

class PromptArgumentError(message: String) extends PromptError(message)
