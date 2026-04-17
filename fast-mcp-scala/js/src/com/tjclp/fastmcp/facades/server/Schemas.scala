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
