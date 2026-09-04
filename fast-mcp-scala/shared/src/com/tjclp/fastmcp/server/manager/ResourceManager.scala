package com.tjclp.fastmcp.server.manager

import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters.*

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
  * Templates such as `users://{id}/profile` are compiled ONCE at registration into a
  * [[ResourceTemplatePattern]] — a regex-free, linear-time matcher where literal text is matched
  * verbatim and each placeholder matches one non-empty path segment (no `/`); a URI matches only as
  * a whole. Client URIs are bounded upstream (`limits.maxUriChars`, enforced in the built-in
  * handlers) before they reach [[findMatchingTemplate]].
  *
  * @tparam R
  *   the ZIO environment all stored handlers may require; supplied by the server at `runHttp[R]()`
  *   / `runStdio[R]()` entry.
  */
class ResourceManager[R] extends Manager[ResourceDefinition]:

  private val staticResources =
    new ConcurrentHashMap[String, (ResourceDefinition, ResourceHandler[R])]()

  private val templateResources =
    new ConcurrentHashMap[
      String,
      (ResourceTemplatePattern, ResourceDefinition, ResourceTemplateHandler[R])
    ]()

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
        else
          // Same shape under different placeholder names (`users://{id}` vs `users://{userId}`)
          // matches the same URIs; whichever the map iterates first wins at read time.
          val shape = ResourceManager.placeholderShape(uriPattern)
          templateResources
            .keySet()
            .asScala
            .find(ResourceManager.placeholderShape(_) == shape)
            .foreach { existing =>
              java.lang.System.err.println(
                s"[ResourceManager] Warning: Resource template '$uriPattern' matches the same URIs as " +
                  s"already-registered '$existing'."
              )
            }
        templateResources.put(uriPattern, (pattern, templateDefinition, handler))
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
      templateResources.values().asScala.map(_._2)).toList

  def listStaticResources(): List[ResourceDefinition] =
    staticResources.values().asScala.map(_._1).toList

  def listTemplateResources(): List[ResourceDefinition] =
    templateResources.values().asScala.map(_._2).toList

  def getStaticResourceHandler(uri: String): Option[ResourceHandler[R]] =
    Option(staticResources.get(uri)).map(_._2)

  /** Alias for [[getStaticResourceHandler]] (kept for tests / direct manager access). */
  def getResourceHandler(uri: String): Option[ResourceHandler[R]] = getStaticResourceHandler(uri)

  def getTemplateResourceHandler(uriPattern: String): Option[ResourceTemplateHandler[R]] =
    Option(templateResources.get(uriPattern)).map(_._3)

  def getResourceDefinition(uri: String): Option[ResourceDefinition] =
    Option(staticResources.get(uri)).map(_._1)

  def listTemplateDefinitions(): List[ResourceDefinition] = listTemplateResources()

  /** Extract parameters from a URI matching a template pattern (convenience / test API — compiles
    * the template on every call; registered templates are compiled once and stored).
    */
  def extractTemplateParams(
      template: String,
      uri: String
  ): Option[Map[String, String]] =
    ResourceTemplatePattern.parse(template).toOption.flatMap(_.matches(uri))

  /** Find the registered template matching `uri`, using the patterns compiled at registration. */
  def findMatchingTemplate(uri: String): Option[
    (ResourceTemplatePattern, ResourceDefinition, ResourceTemplateHandler[R], Map[String, String])
  ] =
    templateResources
      .values()
      .asScala
      .iterator
      .map { case (pattern, definition, handler) =>
        pattern.matches(uri).map(params => (pattern, definition, handler, params))
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

object ResourceManager:

  /** `users://{userId}/x` → `users://{}/x`: the URI shape independent of placeholder names. */
  private[fastmcp] def placeholderShape(pattern: String): String =
    ResourceTemplatePattern.parse(pattern) match
      case Right(compiled) =>
        compiled.segments
          .map(_.map {
            case ResourceTemplatePattern.Part.Literal(t) => t
            case ResourceTemplatePattern.Part.Variable(_) => "{}"
          }.mkString)
          .mkString("/")
      case Left(_) => pattern

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
