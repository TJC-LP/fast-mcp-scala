package com.tjclp.fastmcp.server.manager

import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters.*
import scala.util.matching.Regex

import zio.*

import com.tjclp.fastmcp.core.ResourceDefinition
import com.tjclp.fastmcp.jsonrpc.{McpError, McpErrorCarrier}
import com.tjclp.fastmcp.server.McpContext

/** Function type for resource handlers.
  *
  * Parameterized on the ZIO environment `R` so a handler declaring `ZIO[Client, Throwable, String |
  * Array[Byte]]` can be mounted on a server whose `R = Client` (or a subtype).
  */
type ResourceHandler[R] = () => ZIO[R, Throwable, String | Array[Byte]]

/** Function type for resource template handlers; analogous environment parameterization. */
type ResourceTemplateHandler[R] = Map[String, String] => ZIO[R, Throwable, String | Array[Byte]]

/** Manager for MCP resources.
  *
  * Since we assume scheme-based URIs such as `users://{id}/profile`, template match patterns are
  * anchored with `^` and `$` so only exact matches pass.
  *
  * @tparam R
  *   the ZIO environment all stored handlers may require; supplied by the server at `runHttp[R]()`
  *   / `runStdio[R]()` entry.
  */
class ResourceManager[R] extends Manager[ResourceDefinition]:

  private val staticResources =
    new ConcurrentHashMap[String, (ResourceDefinition, ResourceHandler[R])]()

  private val templateResources =
    new ConcurrentHashMap[String, (ResourceDefinition, ResourceTemplateHandler[R])]()

  def addStaticResource(
      uri: String,
      handler: ResourceHandler[R],
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
      handler: ResourceHandler[R],
      definition: ResourceDefinition
  ): ZIO[Any, Throwable, Unit] =
    addStaticResource(uri, handler, definition)

  def addTemplateResource(
      uriPattern: String,
      handler: ResourceTemplateHandler[R],
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
      handler: ResourceTemplateHandler[R],
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

  def getStaticResourceHandler(uri: String): Option[ResourceHandler[R]] =
    Option(staticResources.get(uri)).map(_._2)

  /** Alias for [[getStaticResourceHandler]] (kept for tests / direct manager access). */
  def getResourceHandler(uri: String): Option[ResourceHandler[R]] = getStaticResourceHandler(uri)

  def getTemplateResourceHandler(uriPattern: String): Option[ResourceTemplateHandler[R]] =
    Option(templateResources.get(uriPattern)).map(_._2)

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
    (ResourceTemplatePattern, ResourceDefinition, ResourceTemplateHandler[R], Map[String, String])
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

  @scala.annotation.nowarn("msg=unused explicit parameter")
  def readResource(
      uri: String,
      context: Option[McpContext]
  ): ZIO[R, Throwable, String | Array[Byte]] =
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
            ZIO.fail(new ResourceNotFoundError(uri))

end ResourceManager

/** Represents a URI pattern with placeholders. */
case class ResourceTemplatePattern(pattern: String):
  private val paramRegex = """\{([^{}]+)\}""".r
  val paramNames = paramRegex.findAllMatchIn(pattern).map(_.group(1)).toList

  private val matchRegex = {
    val regexString = paramRegex.replaceAllIn(pattern, _ => "([^/]+)")
    new Regex("^" + regexString + "$")
  }

  @scala.annotation.nowarn("msg=unused explicit parameter")
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

/** Unknown resource URI. Carries the URI so the wire error can include `data.uri` per spec. */
class ResourceNotFoundError(val uri: String)
    extends ResourceError(s"Resource '$uri' not found")
    with McpErrorCarrier:
  def toMcpError: McpError = McpError.resourceNotFound(uri)

class ResourceRegistrationError(message: String, cause: Option[Throwable] = None)
    extends ResourceError(message, cause)

class ResourceAccessError(message: String, cause: Option[Throwable] = None)
    extends ResourceError(message, cause)
