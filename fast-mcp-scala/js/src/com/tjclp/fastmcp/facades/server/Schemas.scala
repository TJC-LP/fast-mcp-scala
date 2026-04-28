package com.tjclp.fastmcp
package facades
package server

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** Opaque handles for the TS SDK's Zod request schemas. We pass these through to
  * `Server.setRequestHandler(schema, handler)` without inspecting them — all validation happens
  * inside the TS SDK.
  */
@JSImport("@modelcontextprotocol/sdk/types.js", "InitializeRequestSchema")
@js.native
object InitializeRequestSchema extends js.Any

@JSImport("@modelcontextprotocol/sdk/types.js", "ListToolsRequestSchema")
@js.native
object ListToolsRequestSchema extends js.Any

@JSImport("@modelcontextprotocol/sdk/types.js", "CallToolRequestSchema")
@js.native
object CallToolRequestSchema extends js.Any

@JSImport("@modelcontextprotocol/sdk/types.js", "ListResourcesRequestSchema")
@js.native
object ListResourcesRequestSchema extends js.Any

@JSImport("@modelcontextprotocol/sdk/types.js", "ListResourceTemplatesRequestSchema")
@js.native
object ListResourceTemplatesRequestSchema extends js.Any

@JSImport("@modelcontextprotocol/sdk/types.js", "ReadResourceRequestSchema")
@js.native
object ReadResourceRequestSchema extends js.Any

@JSImport("@modelcontextprotocol/sdk/types.js", "ListPromptsRequestSchema")
@js.native
object ListPromptsRequestSchema extends js.Any

@JSImport("@modelcontextprotocol/sdk/types.js", "GetPromptRequestSchema")
@js.native
object GetPromptRequestSchema extends js.Any

// --- Experimental MCP Tasks (spec 2025-11-25) ---

@JSImport("@modelcontextprotocol/sdk/types.js", "GetTaskRequestSchema")
@js.native
object GetTaskRequestSchema extends js.Any

@JSImport("@modelcontextprotocol/sdk/types.js", "ListTasksRequestSchema")
@js.native
object ListTasksRequestSchema extends js.Any

@JSImport("@modelcontextprotocol/sdk/types.js", "CancelTaskRequestSchema")
@js.native
object CancelTaskRequestSchema extends js.Any

/** TS SDK calls the `tasks/result` schema `GetTaskPayloadRequestSchema` (the response is the
  * underlying request's payload).
  */
@JSImport("@modelcontextprotocol/sdk/types.js", "GetTaskPayloadRequestSchema")
@js.native
object GetTaskPayloadRequestSchema extends js.Any
