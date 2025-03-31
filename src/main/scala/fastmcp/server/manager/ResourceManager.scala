package fastmcp.server.manager

import fastmcp.core.*
import fastmcp.server.McpContext
import zio.*

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*
import scala.util.matching.Regex

/**
 * Function type for resource handlers
 * Returns either a String or a byte array wrapped in ZIO
 */
type ResourceHandler = () => ZIO[Any, Throwable, String | Array[Byte]]

/**
 * Function type for resource template handlers
 * Takes parameters extracted from URI and returns either a String or a byte array wrapped in ZIO
 */
type ResourceTemplateHandler = Map[String, String] => ZIO[Any, Throwable, String | Array[Byte]]

/**
 * Manager for MCP resources
 *
 * Responsible for registering, storing, and retrieving resources.
 * Handles both static resources and templated resources.
 */
class ResourceManager extends Manager[ResourceDefinition]:
  // Thread-safe storage for registered resources
  private val resources = new ConcurrentHashMap[String, (ResourceDefinition, ResourceHandler)]()

  // Thread-safe storage for registered resource templates
  private val resourceTemplates = new ConcurrentHashMap[ResourceTemplatePattern, (ResourceDefinition, ResourceTemplateHandler)]()

  /**
   * Register a static resource with the manager
   *
   * @param uri        Resource URI
   * @param handler    Function to execute when the resource is accessed
   * @param definition Resource definition
   * @return ZIO effect that completes with Unit on success or fails with ResourceRegistrationError
   */
  def addResource(uri: String, handler: ResourceHandler, definition: ResourceDefinition): ZIO[Any, Throwable, Unit] =
    ZIO.attempt {
      resources.put(uri, (definition, handler))
      ()
    }.mapError(e => new ResourceRegistrationError(s"Failed to register resource '$uri'", e))

  /**
   * Register a templated resource with the manager
   *
   * @param uriPattern Resource URI pattern with placeholders like {param}
   * @param handler    Function to execute when the resource is accessed
   * @param definition Resource definition
   * @return ZIO effect that completes with Unit on success or fails with ResourceRegistrationError
   */
  def addResourceTemplate(
                           uriPattern: String,
                           handler: ResourceTemplateHandler,
                           definition: ResourceDefinition
                         ): ZIO[Any, Throwable, Unit] =
    ZIO.attempt {
      val resourcePattern = ResourceTemplatePattern(uriPattern)
      resourceTemplates.put(resourcePattern, (definition, handler))
      ()
    }.mapError(e => new ResourceRegistrationError(s"Failed to register resource template '$uriPattern'", e))
  
  /**
   * Find a resource definition by URI
   *
   * @param uri Resource URI
   * @return Option containing the definition if found
   */
  def getResourceDefinition(uri: String): Option[ResourceDefinition] =
    Option(resources.get(uri)).map(_._1)

  /**
   * List all registered resource definitions
   *
   * @return List of all static and templated resource definitions
   */
  override def listDefinitions(): List[ResourceDefinition] =
    val staticResources = resources.values().asScala.map(_._1).toList
    val templateResources = resourceTemplates.values().asScala.map(_._1).toList
    staticResources ++ templateResources

  /**
   * Read a resource by URI
   *
   * First checks static resources, then tries to match against templated resources
   *
   * @param uri     Resource URI
   * @param context Optional context for the resource access
   * @return ZIO effect that completes with the resource content or fails with ResourceError
   */
  def readResource(uri: String, context: Option[McpContext]): ZIO[Any, Throwable, String | Array[Byte]] =
    getResourceHandler(uri) match
      case Some(handler) =>
        handler()
          .mapError(e => new ResourceAccessError(s"Error accessing resource '$uri'", e))

      case None =>
        findMatchingTemplate(uri) match
          case Some((_, _, handler, params)) =>
            handler(params)
              .mapError(e => new ResourceAccessError(s"Error accessing templated resource '$uri'", e))

          case None =>
            ZIO.fail(new ResourceNotFoundError(s"Resource '$uri' not found"))

  /**
   * Find a resource handler by URI
   *
   * @param uri Resource URI
   * @return Option containing the handler if found
   */
  def getResourceHandler(uri: String): Option[ResourceHandler] =
    Option(resources.get(uri)).map(_._2)

  /**
   * Find a resource template that matches the given URI
   *
   * @param uri URI to match against templates
   * @return Option containing the template pattern, definition, handler, and extracted parameters if found
   */
  def findMatchingTemplate(uri: String): Option[(ResourceTemplatePattern, ResourceDefinition, ResourceTemplateHandler, Map[String, String])] =
    resourceTemplates.entrySet().asScala
      .find { entry =>
        entry.getKey.matches(uri).isDefined
      }
      .map { entry =>
        val pattern = entry.getKey
        val (definition, handler) = entry.getValue
        val params = pattern.extractParams(uri).getOrElse(Map.empty)
        (pattern, definition, handler, params)
      }

/**
 * Represents a URI pattern with placeholders
 * For example: "/api/users/{id}/profile"
 */
case class ResourceTemplatePattern(pattern: String):
  private val paramRegex = """\{([^{}]+)}""".r
  private val paramNames = paramRegex.findAllIn(pattern).matchData.map(_.group(1)).toList

  // Convert the pattern to a regex that can match URIs and capture placeholder values
  private val matchRegex = new Regex(
    paramRegex.replaceAllIn(pattern, _ => """([^/]+)""")
  )

  /**
   * Extract parameters from a URI that matches this template
   *
   * @param uri URI to extract parameters from
   * @return Option with parameter map if the URI matches, None otherwise
   */
  def extractParams(uri: String): Option[Map[String, String]] =
    matches(uri).map { regexMatch =>
      paramNames.zipWithIndex.map { case (name, i) =>
        name -> regexMatch.group(i + 1)
      }.toMap
    }

  /**
   * Check if the given URI matches this template pattern
   *
   * @param uri URI to check
   * @return Option with the match if successful, None otherwise
   */
  def matches(uri: String): Option[Regex.Match] =
    matchRegex.findFirstMatchIn(uri)

/**
 * Custom exceptions for resource operations
 */
class ResourceError(message: String, cause: Throwable = null) extends RuntimeException(message, cause)

class ResourceNotFoundError(message: String) extends ResourceError(message)

class ResourceRegistrationError(message: String, cause: Throwable = null) extends ResourceError(message, cause)

class ResourceAccessError(message: String, cause: Throwable = null) extends ResourceError(message, cause)