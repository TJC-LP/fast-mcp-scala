package fastmcp.core

import scala.annotation.{StaticAnnotation, experimental}
import scala.quoted.*

import fastmcp.server.FastMCPScala
import fastmcp.server.manager.ToolRegistrationOptions
import fastmcp.macros.MapToFunctionMacro
import fastmcp.macros.JsonSchemaMacro
import io.modelcontextprotocol.spec.McpSchema

/**
 * Marker annotation for methods representing MCP Tools
 * 
 * This annotation is processed at compile time by the FastMCPScala.scanAnnotations macro,
 * which automatically registers the method as an MCP tool.
 *
 * The tool's description will be taken from the `description` parameter if provided.
 * If `description` is `None` or omitted, the macro will attempt to use the method's
 * Scaladoc comment as the description.
 *
 * @param name Optional name for the tool (defaults to method name)
 * @param description Optional description for the tool. If None, Scaladoc will be used.
 * @param examples Optional examples of how to use the tool
 * @param version Optional version of the tool
 * @param deprecated If true, the tool is marked as deprecated
 * @param deprecationMessage Optional message explaining deprecation reason
 * @param tags Optional list of tags to categorize the tool
 * @param timeoutMillis Optional timeout for tool execution in milliseconds
 */
class Tool(
    val name: Option[String] = None,
    val description: Option[String] = None, // Description can come from here or Scaladoc
    val examples: List[String] = List.empty,
    val version: Option[String] = None,
    val deprecated: Boolean = false,
    val deprecationMessage: Option[String] = None,
    val tags: List[String] = List.empty,
    val timeoutMillis: Option[Long] = None
) extends StaticAnnotation

/**
 * Annotation for method parameters in Tool methods
 * 
 * @param description Description of the parameter for tool documentation
 * @param example Optional example value for the parameter
 * @param required Whether the parameter is required (defaults to true)
 * @param schema Optional JSON schema override for the parameter type
 */
class Param(
    description: String,
    example: Option[String] = None,
    required: Boolean = true,
    schema: Option[String] = None
) extends StaticAnnotation

/**
 * Annotation for case class fields to provide schema metadata
 * Used with ZIO Schema to enhance the generated JSON schemas
 *
 * @param description Description of the field
 * @param example Optional example value for documentation
 * @param format Optional format string (e.g. "date-time", "email", etc.)
 * @param deprecated Whether this field is deprecated
 * @param required Whether this field is required (for Option fields)
 * @param minimum Optional minimum value for numeric fields
 * @param maximum Optional maximum value for numeric fields
 * @param pattern Optional regex pattern for string fields
 * @param minLength Optional minimum length for string fields
 * @param maxLength Optional maximum length for string fields
 */
class SchemaField(
    description: String = "",
    example: Option[String] = None,
    format: Option[String] = None,
    deprecated: Boolean = false,
    required: Boolean = true,
    minimum: Option[Double] = None,
    maximum: Option[Double] = None,
    pattern: Option[String] = None,
    minLength: Option[Int] = None,
    maxLength: Option[Int] = None
) extends StaticAnnotation

/**
 * Marker annotation for methods representing MCP Resources
 * If uri contains {placeholders}, it's treated as a template
 * 
 * @param uri The URI for the resource
 * @param name Optional name for the resource
 * @param description Optional description for the resource
 * @param mimeType Optional MIME type for the resource
 */
class Resource(
    uri: String,
    name: Option[String] = None,
    description: Option[String] = None,
    mimeType: Option[String] = None
) extends StaticAnnotation

/**
 * Marker annotation for methods representing MCP Prompts
 * 
 * @param name Optional name for the prompt (defaults to method name)
 * @param description Optional description for the prompt
 */
class Prompt(
    name: Option[String] = None, 
    description: Option[String] = None
) extends StaticAnnotation

/**
 * Annotation for method parameters in Prompt methods
 * 
 * @param description Description of the parameter for prompt documentation
 * @param required Whether the parameter is required (defaults to true)
 */
class PromptParam(
    description: String,
    required: Boolean = true
) extends StaticAnnotation