package com.tjclp.fastmcp.server.manager

import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters.*
import scala.util.matching.Regex

import zio.*

import com.tjclp.fastmcp.server.McpContext

/** Describes one argument for a resource template placeholder. Similar to PromptArgument.
  */
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
    isTemplate: Boolean = false, // Added: Flag to indicate if it's a template
    arguments: Option[List[ResourceArgument]] = None // Added: Arguments for templates
)

/** Companion with toJava method that decides Resource vs. ResourceTemplate.
  */
@SuppressWarnings(Array("org.wartremover.warts.Null"))
object ResourceDefinition:
  import io.modelcontextprotocol.spec.McpSchema
  import scala.jdk.CollectionConverters.*

  // This method now returns a union type
  def toJava(rd: ResourceDefinition): McpSchema.Resource | McpSchema.ResourceTemplate =
    if rd.isTemplate then
      // --- Create ResourceTemplate ---
      // Embed arguments in annotations for client discovery
      // NOTE: The Java SDK McpSchema.Annotations currently only supports audience/priority.
      // We'll store args in the experimental map for now, hoping the SDK might support it later
      // or that clients can parse it.
      val experimentalAnnotationsMap = new java.util.HashMap[String, Object]()
      rd.arguments.foreach { argList =>
        val javaArgsList = argList.map { arg =>
          val argMap = new java.util.HashMap[String, Object]()
          argMap.put("name", arg.name)
          arg.description.foreach(d => argMap.put("description", d))
          argMap.put("required", java.lang.Boolean.valueOf(arg.required))
          argMap.asInstanceOf[Object] // Store each arg map as an Object in the list
        }.asJava // Convert List[Map] to java.util.List<Object>
        experimentalAnnotationsMap.put("fastmcp_resource_arguments", javaArgsList)
      }

      // Create Annotations object - pass null for audience/priority for now
      val annotations = new McpSchema.Annotations(null, null)
      // TODO: Revisit if Java SDK adds direct support for experimental data in Annotations

      new McpSchema.ResourceTemplate(
        rd.uri, // Use the URI pattern
        rd.name.orNull,
        rd.description.orNull,
        rd.mimeType.getOrElse("text/plain"), // Default MIME type if needed
        annotations // Pass annotations (currently only holds audience/priority)
        // experimentalAnnotationsMap // Cannot pass experimental data directly here yet
      )
    else
      // --- Create static Resource using builder ---
      McpSchema.Resource
        .builder()
        .uri(rd.uri)
        .name(rd.name.orNull)
        .description(rd.description.orNull)
        .mimeType(rd.mimeType.getOrElse("text/plain"))
        .annotations(new McpSchema.Annotations(null, null))
        .build()

/** Function type for resource handlers Returns either a String or a byte array wrapped in ZIO
  */
type ResourceHandler = () => ZIO[Any, Throwable, String | Array[Byte]]

/** Function type for resource template handlers Takes parameters extracted from URI and returns
  * either a String or a byte array wrapped in ZIO
  */
type ResourceTemplateHandler = Map[String, String] => ZIO[Any, Throwable, String | Array[Byte]]

/** Manager for MCP resources
  *
  * Responsible for registering, storing, and retrieving resources. Handles both static resources
  * and templated resources.
  *
  * Since we assume we are always scheme-based, e.g. "users://{id}/profile", the match patterns are
  * anchored with ^ and $ so only exact matches pass.
  */
class ResourceManager extends Manager[ResourceDefinition]:
  // Thread-safe storage for registered resources
  private val resources = new ConcurrentHashMap[String, (ResourceDefinition, ResourceHandler)]()

  // Thread-safe storage for registered resource templates - Key is now String (URI pattern)
  private val resourceTemplates =
    new ConcurrentHashMap[String, (ResourceDefinition, ResourceTemplateHandler)]()

  /** Register a static resource with the manager
    *
    * @param uri
    *   Resource URI
    * @param handler
    *   Function to execute when the resource is accessed
    * @param definition
    *   Resource definition
    * @return
    *   ZIO effect that completes with Unit on success or fails with ResourceRegistrationError
    */
  def addResource(
      uri: String,
      handler: ResourceHandler,
      definition: ResourceDefinition
  ): ZIO[Any, Throwable, Unit] =
    ZIO
      .attempt {
        // Ensure isTemplate is false for static resources
        val staticDefinition = definition.copy(isTemplate = false, arguments = None)
        if resources.containsKey(uri) then
          // Handle duplicate registration if needed (e.g., log warning or throw error based on settings)
          java.lang.System.err.println(
            s"[ResourceManager] Warning: Static resource with URI '$uri' already exists. Overwriting."
          )
        resources.put(uri, (staticDefinition, handler))
        ()
      }
      .mapError(e => new ResourceRegistrationError(s"Failed to register resource '$uri'", Some(e)))

  /** Register a templated resource with the manager
    *
    * @param uriPattern
    *   Resource URI pattern with placeholders like {param}, e.g. "users://{id}/profile"
    * @param handler
    *   Function to execute when the resource is accessed
    * @param definition
    *   Resource definition (should include arguments if template)
    * @return
    *   ZIO effect that completes with Unit on success or fails with ResourceRegistrationError
    */
  def addResourceTemplate(
      uriPattern: String,
      handler: ResourceTemplateHandler,
      definition: ResourceDefinition // Ensure this accepts the full definition
  ): ZIO[Any, Throwable, Unit] =
    ZIO
      .attempt {
        // Validate that all placeholders in uriPattern have corresponding arguments
        val pattern = ResourceTemplatePattern(uriPattern)
        val placeholderNames = pattern.paramNames
        val argumentNames = definition.arguments.map(_.map(_.name)).getOrElse(List.empty).toSet

        val missingArgs = placeholderNames.filterNot(argumentNames.contains)
        if missingArgs.nonEmpty then
          throw new IllegalArgumentException(
            s"Template URI pattern '$uriPattern' contains placeholders [${missingArgs.mkString(", ")}] " +
              s"that don't have corresponding arguments in the definition"
          )

        // Ensure isTemplate is true and arguments are stored (using the passed definition)
        val templateDefinition =
          definition.copy(isTemplate = true) // Arguments should be in the passed definition
        // Use the uriPattern string directly as the key
        if resourceTemplates.containsKey(uriPattern) then
          // Handle duplicate registration if needed
          java.lang.System.err.println(
            s"[ResourceManager] Warning: Resource template with pattern '$uriPattern' already exists. Overwriting."
          )
        resourceTemplates.put(uriPattern, (templateDefinition, handler))
        ()
      }
      .mapError(e =>
        new ResourceRegistrationError(s"Failed to register resource template '$uriPattern'", Some(e))
      )

  /** Find a resource definition by URI
    *
    * @param uri
    *   Resource URI
    * @return
    *   Option containing the definition if found
    */
  def getResourceDefinition(uri: String): Option[ResourceDefinition] =
    Option(resources.get(uri)).map(_._1)

  /** List all registered resource definitions
    *
    * @return
    *   List of all static and templated resource definitions
    */
  override def listDefinitions(): List[ResourceDefinition] =
    val staticResourcesList = resources.values().asScala.map(_._1).toList
    val templateResourcesList = resourceTemplates.values().asScala.map(_._1).toList
    staticResourcesList ++ templateResourcesList

  /** List only template resource definitions
    *
    * @return
    *   List of all templated resource definitions
    */
  def listTemplateDefinitions(): List[ResourceDefinition] =
    resourceTemplates.values().asScala.map(_._1).toList

  /** Get a template handler by its exact pattern string
    *
    * @param uriPattern
    *   The exact URI pattern (e.g., "users://{userId}")
    * @return
    *   Option containing the handler if found
    */
  def getTemplateHandler(uriPattern: String): Option[ResourceTemplateHandler] =
    Option(resourceTemplates.get(uriPattern)).map(_._2)

  /** Read a resource by URI
    *
    * @return
    *   ZIO effect that completes with the resource content or fails with Throwable
    */
  def readResource(
      uri: String,
      context: Option[McpContext]
  ): ZIO[Any, Throwable, String | Array[Byte]] =
    // 1. Check static resources first
    Option(resources.get(uri)) match
      case Some((_, handler)) =>
        handler() // Static handler takes no params
          .mapError(e => new ResourceAccessError(s"Error accessing static resource '$uri'", Some(e)))
      // 2. If no static match, check templates
      case None =>
        findMatchingTemplate(uri) match
          case Some((_, _, handler, params)) =>
            handler(params) // Template handler takes extracted params
              .mapError(e =>
                new ResourceAccessError(
                  s"Error accessing templated resource '$uri' with params $params",
                  Some(e)
                )
              )
          // 3. If no match found
          case None =>
            ZIO.fail(new ResourceNotFoundError(s"Resource '$uri' not found"))

  /** Find a resource handler by URI
    */
  def getResourceHandler(uri: String): Option[ResourceHandler] =
    Option(resources.get(uri)).map(_._2)

  /** Find a resource template that matches the given URI
    *
    * @param uri
    *   URI to match against templates (e.g. "users://123/profile")
    * @return
    *   Option containing the template pattern, definition, handler, and extracted parameters if
    *   found
    */
  def findMatchingTemplate(uri: String): Option[
    (ResourceTemplatePattern, ResourceDefinition, ResourceTemplateHandler, Map[String, String])
  ] =
    resourceTemplates
      .entrySet()
      .asScala
      .iterator // Use iterator for potentially better performance on large maps
      .map { entry =>
        val patternString = entry.getKey
        val pattern = ResourceTemplatePattern(patternString)
        val (definition, handler) = entry.getValue
        pattern
          .matches(uri)
          .map(regexMatch => (pattern, definition, handler, pattern.extractParams(uri, regexMatch)))
      }
      .collectFirst { case Some(result) => result }

end ResourceManager

/** Represents a URI pattern with placeholders. We assume "scheme://..." style, for example:
  * "users://{id}/profile". We anchor the pattern with ^...$ so we must match fully.
  */
case class ResourceTemplatePattern(pattern: String):
  // Regex to find placeholders like {userId}
  private val paramRegex = """\{([^{}]+)\}""".r
  // Extract the names of the placeholders
  val paramNames = paramRegex.findAllMatchIn(pattern).map(_.group(1)).toList

  // Convert the pattern string into a regex that captures the placeholder values
  // Example: "users://{id}/profile" -> "^users://([^/]+)/profile$"
  private val matchRegex = {
    // Replace {placeholder} with a capturing group ([^/]+) that matches any character except '/'
    val regexString = paramRegex.replaceAllIn(pattern, _ => "([^/]+)")
    // Anchor the regex to match the entire string
    new Regex("^" + regexString + "$")
  }

  /** Extracts parameters from a given URI based on the template pattern. Assumes the URI has
    * already been matched using `matches`.
    *
    * @param uri
    *   The URI to extract parameters from.
    * @param regexMatch
    *   The successful Regex.Match object from `matches`.
    * @return
    *   A map of parameter names to their extracted values.
    */
  def extractParams(uri: String, regexMatch: Regex.Match): Map[String, String] =
    paramNames.zipWithIndex.map { case (name, i) =>
      // Group 0 is the full match, groups 1, 2, ... correspond to captures
      name -> regexMatch.group(i + 1)
    }.toMap

  /** Checks if the given URI matches this template pattern.
    *
    * @param uri
    *   The URI to check.
    * @return
    *   An Option containing the Regex.Match object if it matches, None otherwise.
    */
  def matches(uri: String): Option[Regex.Match] =
    matchRegex.findFirstMatchIn(uri)

/** Custom exceptions for resource operations
  */
@SuppressWarnings(Array("org.wartremover.warts.Null"))
class ResourceError(message: String, cause: Option[Throwable] = None)
    extends RuntimeException(message, cause.orNull)

class ResourceNotFoundError(message: String) extends ResourceError(message)

class ResourceRegistrationError(message: String, cause: Option[Throwable] = None)
    extends ResourceError(message, cause)

class ResourceAccessError(message: String, cause: Option[Throwable] = None)
    extends ResourceError(message, cause)
