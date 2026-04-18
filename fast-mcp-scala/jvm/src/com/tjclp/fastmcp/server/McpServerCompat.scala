package com.tjclp.fastmcp
package server

import io.modelcontextprotocol.spec.McpSchema
import zio.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.core.JvmToolInputSchemaSupport.*
import com.tjclp.fastmcp.server.manager.*

/** JVM-only compatibility overloads for the shared McpServerCore API. */
extension (server: McpServerCore)

  def tool(
      name: String,
      handler: ContextualToolHandler,
      description: Option[String] = None,
      inputSchema: Either[McpSchema.JsonSchema, String],
      options: ToolRegistrationOptions = ToolRegistrationOptions(),
      annotations: Option[ToolAnnotations] = None
  ): ZIO[Any, Throwable, McpServerCore] =
    server.tool(
      name = name,
      handler = handler,
      description = description,
      inputSchema = fromEither(inputSchema),
      options = options,
      annotations = annotations
    )
