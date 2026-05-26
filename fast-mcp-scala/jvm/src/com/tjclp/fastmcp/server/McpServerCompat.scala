package com.tjclp.fastmcp
package server

import io.modelcontextprotocol.spec.McpSchema
import zio.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.core.JvmToolInputSchemaSupport.*
import com.tjclp.fastmcp.server.manager.*

/** JVM-only compatibility overloads for the shared McpServerCore API. */
extension [R](server: McpServerCore[R])

  def tool[R1 >: R](
      name: String,
      handler: ContextualToolHandler[R1],
      description: Option[String] = None,
      inputSchema: Either[McpSchema.JsonSchema, String],
      options: ToolRegistrationOptions = ToolRegistrationOptions(),
      annotations: Option[ToolAnnotations] = None
  ): ZIO[Any, Throwable, McpServerCore[R]] =
    server.tool[R1](
      name = name,
      handler = handler,
      description = description,
      inputSchema = fromEither(inputSchema),
      options = options,
      annotations = annotations
    )
