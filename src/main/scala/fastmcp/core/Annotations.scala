package fastmcp.core

import scala.annotation.StaticAnnotation

// Marker annotation for methods representing MCP Tools
class Tool(name: Option[String] = None, description: Option[String] = None) extends StaticAnnotation

// Marker annotation for methods representing MCP Resources
// If uri contains {placeholders}, it's treated as a template
class Resource(
    uri: String,
    name: Option[String] = None,
    description: Option[String] = None,
    mimeType: Option[String] = None
) extends StaticAnnotation

// Marker annotation for methods representing MCP Prompts
class Prompt(name: Option[String] = None, description: Option[String] = None) extends StaticAnnotation