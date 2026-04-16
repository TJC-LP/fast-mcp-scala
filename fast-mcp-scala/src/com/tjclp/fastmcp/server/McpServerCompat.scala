package com.tjclp.fastmcp
package server

import io.modelcontextprotocol.spec.McpSchema
import zio.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.core.JvmToolInputSchemaSupport.*
import com.tjclp.fastmcp.server.manager.*

/** JVM-only compatibility overloads for the shared McpServer API. */
extension (server: McpServer)
  def tool(
      name: String,
      handler: ContextualToolHandler,
      description: Option[String] = None,
      inputSchema: Either[McpSchema.JsonSchema, String],
      options: ToolRegistrationOptions = ToolRegistrationOptions(),
      annotations: Option[ToolAnnotations] = None
  ): ZIO[Any, Throwable, McpServer] =
    server.tool(
      name = name,
      handler = handler,
      description = description,
      inputSchema = fromEither(inputSchema),
      options = options,
      annotations = annotations
    )
