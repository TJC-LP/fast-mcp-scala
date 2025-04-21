package com.tjclp.fastmcp.core

import scala.annotation.StaticAnnotation
import scala.annotation.experimental

/** Marker annotation for methods representing MCP Tools
  *
  * This annotation is processed at compile time by the FastMCPScala.scanAnnotations macro, which
  * automatically registers the method as an MCP tool.
  *
  * The tool's description will be taken from the `description` parameter if provided. If
  * `description` is `None` or omitted, the macro will attempt to use the method's Scaladoc comment
  * as the description.
  *
  * @param name
  *   Optional name for the tool (defaults to method name)
  * @param description
  *   Optional description for the tool. If None, Scaladoc will be used.
  * @param examples
  *   Optional examples of how to use the tool
  * @param version
  *   Optional version of the tool
  * @param deprecated
  *   If true, the tool is marked as deprecated
  * @param deprecationMessage
  *   Optional message explaining deprecation reason
  * @param tags
  *   Optional list of tags to categorize the tool
  * @param timeoutMillis
  *   Optional timeout for tool execution in milliseconds
  */
class Tool(
    val name: Option[String] = None,
    val description: Option[String] = None,
    val examples: List[String] = List.empty,
    val version: Option[String] = None,
    val deprecated: Boolean = false,
    val deprecationMessage: Option[String] = None,
    val tags: List[String] = List.empty,
    val timeoutMillis: Option[Long] = None
) extends StaticAnnotation

/** Annotation for method parameters in Tool methods
  *
  * @param description
  *   Description of the parameter for tool documentation
  * @param example
  *   Optional example value for the parameter
  * @param required
  *   Whether the parameter is required (defaults to true)
  * @param schema
  *   Optional JSON schema override for the parameter type
  */
class ToolParam(
    val description: String,
    val example: Option[String] = None,
    val required: Boolean = true,
    val schema: Option[String] = None
) extends StaticAnnotation

/** Marker annotation for methods representing MCP Resources If uri contains {placeholders}, it's
  * treated as a template resource. Placeholders in the URI must match the method parameter names
  * unless @ResourceParam is used. Static resources (no placeholders) should have methods with no
  * parameters.
  *
  * @param uri
  *   The URI or URI template for the resource (e.g., "file:///data.txt" or
  *   "users://{userId}/profile")
  * @param name
  *   Optional name for the resource (defaults to method name)
  * @param description
  *   Optional description for the resource (defaults to Scaladoc)
  * @param mimeType
  *   Optional MIME type for the resource (defaults to "text/plain")
  */
class Resource(
    val uri: String,
    val name: Option[String] = None,
    val description: Option[String] = None,
    val mimeType: Option[String] = None
) extends StaticAnnotation

/** Marker annotation for resource method parameters, used to describe template arguments.
  *
  * @param description
  *   A human-readable description for the parameter
  * @param required
  *   Whether the parameter is required (defaults to true)
  */
class ResourceParam(
    val description: String,
    val required: Boolean = true
) extends StaticAnnotation

/** Marker annotation for methods representing MCP Prompts
  *
  * @param name
  *   Optional name for the prompt (defaults to method name)
  * @param description
  *   Optional description for the prompt (defaults to Scaladoc)
  */
class Prompt(
    val name: Option[String] = None,
    val description: Option[String] = None
) extends StaticAnnotation

/** Annotation for method parameters in Prompt methods
  *
  * @param description
  *   Description of the parameter for prompt documentation
  * @param required
  *   Whether the parameter is required (defaults to true)
  */
class PromptParam(
    val description: String,
    val required: Boolean = true
) extends StaticAnnotation
