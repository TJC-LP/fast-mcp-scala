package com.tjclp.fastmcp.server.manager

import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters.*
import scala.util.matching.Regex

import zio.*

import com.tjclp.fastmcp.server.McpContext

/** Describes one argument for a resource template placeholder. */
case class ResourceArgument(
    name: String,
    description: Option[String],
    required: Boolean = true
)

case class ResourceDefinition(
    uri: String,
    name: Option[String],
    description: Option[String],
    mimeType: Option[String] = Some("text/plain"),
    isTemplate: Boolean = false,
    arguments: Option[List[ResourceArgument]] = None
)

/** Function type for resource handlers */
type ResourceHandler = () => ZIO[Any, Throwable, String | Array[Byte]]

/** Function type for resource template handlers */
type ResourceTemplateHandler = Map[String, String] => ZIO[Any, Throwable, String | Array[Byte]]

/** Manager for MCP resources.
  *
  * Since we assume scheme-based URIs such as `users://{id}/profile`, template match patterns are
  * anchored with `^` and `$` so only exact matches pass.
  */
class ResourceManager extends Manager[ResourceDefinition]:
  private val staticResources = new ConcurrentHashMap[String, (ResourceDefinition, ResourceHandler)]()
  private val templateResources =
    new ConcurrentHashMap[String, (ResourceDefinition, ResourceTemplateHandler)]()

  def addStaticResource(
      uri: String,
      handler: ResourceHandler,
      definition: ResourceDefinition
  ): ZIO[Any, Throwable, Unit] =
    ZIO
      .attempt {
        val staticDefinition = definition.copy(isTemplate = false, arguments = None)
        if staticResources.containsKey(uri) then
          java.lang.System.err.println(
            s"[ResourceManager] Warning: Static resource with URI '$uri' already exists. Overwriting."
          )
        staticResources.put(uri, (staticDefinition, handler))
        ()
      }
      .mapError(e => new ResourceRegistrationError(s"Failed to register resource '$uri'", Some(e)))

  /** Backward-compatible alias for the previous ResourceManager API. */
  def addResource(
      uri: String,
      handler: ResourceHandler,
      definition: ResourceDefinition
  ): ZIO[Any, Throwable, Unit] =
    addStaticResource(uri, handler, definition)

  def addTemplateResource(
      uriPattern: String,
      handler: ResourceTemplateHandler,
      definition: ResourceDefinition
  ): ZIO[Any, Throwable, Unit] =
    ZIO
      .attempt {
        val pattern = ResourceTemplatePattern(uriPattern)
        val placeholderNames = pattern.paramNames
        val argumentNames = definition.arguments.map(_.map(_.name)).getOrElse(List.empty).toSet

        val missingArgs = placeholderNames.filterNot(argumentNames.contains)
        if missingArgs.nonEmpty then
          throw new IllegalArgumentException(
            s"Template URI pattern '$uriPattern' contains placeholders [${missingArgs.mkString(", ")}] " +
              s"that don't have corresponding arguments in the definition"
          )

        val templateDefinition = definition.copy(isTemplate = true)
        if templateResources.containsKey(uriPattern) then
          java.lang.System.err.println(
            s"[ResourceManager] Warning: Resource template with pattern '$uriPattern' already exists. Overwriting."
          )
        templateResources.put(uriPattern, (templateDefinition, handler))
        ()
      }
      .mapError(e =>
        new ResourceRegistrationError(
          s"Failed to register resource template '$uriPattern'",
          Some(e)
        )
      )

  /** Backward-compatible alias for the previous ResourceManager API. */
  def addResourceTemplate(
      uriPattern: String,
      handler: ResourceTemplateHandler,
      definition: ResourceDefinition
  ): ZIO[Any, Throwable, Unit] =
    addTemplateResource(uriPattern, handler, definition)

  override def listDefinitions(): List[ResourceDefinition] =
    (staticResources.values().asScala.map(_._1) ++
      templateResources.values().asScala.map(_._1)).toList

  def listStaticResources(): List[ResourceDefinition] =
    staticResources.values().asScala.map(_._1).toList

  def listTemplateResources(): List[ResourceDefinition] =
    templateResources.values().asScala.map(_._1).toList

  def getStaticResourceHandler(uri: String): Option[ResourceHandler] =
    Option(staticResources.get(uri)).map(_._2)

  /** Alias used by FastMcpServer */
  def getResourceHandler(uri: String): Option[ResourceHandler] = getStaticResourceHandler(uri)

  def getTemplateResourceHandler(uriPattern: String): Option[ResourceTemplateHandler] =
    Option(templateResources.get(uriPattern)).map(_._2)

  /** Alias used by FastMcpServer */
  def getTemplateHandler(uriPattern: String): Option[ResourceTemplateHandler] = getTemplateResourceHandler(uriPattern)

  def getResourceDefinition(uri: String): Option[ResourceDefinition] =
    Option(staticResources.get(uri)).map(_._1)

  def listTemplateDefinitions(): List[ResourceDefinition] = listTemplateResources()

  /** Extract parameters from a URI matching a template pattern */
  def extractTemplateParams(
      template: String,
      uri: String
  ): Option[Map[String, String]] =
    val pattern = ResourceTemplatePattern(template)
    pattern.matches(uri).map(pattern.extractParams(uri, _))

  def findMatchingTemplate(uri: String): Option[
    (ResourceTemplatePattern, ResourceDefinition, ResourceTemplateHandler, Map[String, String])
  ] =
    templateResources
      .entrySet()
      .asScala
      .iterator
      .map { entry =>
        val patternString = entry.getKey
        val pattern = ResourceTemplatePattern(patternString)
        val (definition, handler) = entry.getValue
        pattern
          .matches(uri)
          .map(regexMatch => (pattern, definition, handler, pattern.extractParams(uri, regexMatch)))
      }
      .collectFirst { case Some(result) => result }

  def readResource(
      uri: String,
      context: Option[McpContext]
  ): ZIO[Any, Throwable, String | Array[Byte]] =
    Option(staticResources.get(uri)) match
      case Some((_, handler)) =>
        handler()
          .mapError(e =>
            new ResourceAccessError(s"Error accessing static resource '$uri'", Some(e))
          )
      case None =>
        findMatchingTemplate(uri) match
          case Some((_, _, handler, params)) =>
            handler(params)
              .mapError(e =>
                new ResourceAccessError(
                  s"Error accessing templated resource '$uri' with params $params",
                  Some(e)
                )
              )
          case None =>
            ZIO.fail(new ResourceNotFoundError(s"Resource '$uri' not found"))

end ResourceManager

/** Represents a URI pattern with placeholders. */
case class ResourceTemplatePattern(pattern: String):
  private val paramRegex = """\{([^{}]+)\}""".r
  val paramNames = paramRegex.findAllMatchIn(pattern).map(_.group(1)).toList

  private val matchRegex = {
    val regexString = paramRegex.replaceAllIn(pattern, _ => "([^/]+)")
    new Regex("^" + regexString + "$")
  }

  def extractParams(
      uri: String,
      regexMatch: Regex.Match
  ): Map[String, String] =
    paramNames.zipWithIndex.map { case (name, idx) =>
      name -> regexMatch.group(idx + 1)
    }.toMap

  def matches(uri: String): Option[Regex.Match] =
    matchRegex.findFirstMatchIn(uri)

@SuppressWarnings(Array("org.wartremover.warts.Null"))
class ResourceError(message: String, cause: Option[Throwable] = None)
    extends RuntimeException(message, cause.orNull)

class ResourceNotFoundError(message: String) extends ResourceError(message)

class ResourceRegistrationError(message: String, cause: Option[Throwable] = None)
    extends ResourceError(message, cause)

class ResourceAccessError(message: String, cause: Option[Throwable] = None)
    extends ResourceError(message, cause)
