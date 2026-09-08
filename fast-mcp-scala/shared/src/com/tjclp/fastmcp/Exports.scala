package com.tjclp.fastmcp

export core.{
  toAst,
  toJsonString,
  AsResourceBody,
  AudioContent,
  Content,
  EmbeddedResource,
  ImageContent,
  McpCodec,
  McpDecodeContext,
  McpDecoder,
  McpEncoder,
  McpInputCodec,
  McpPrompt,
  McpSchema,
  McpStaticResource,
  McpTemplateResource,
  McpTool,
  Message,
  Param,
  Prompt,
  PromptArgument,
  PromptDefinition,
  Resource,
  ResourceArgument,
  ResourceDefinition,
  ResourceLink,
  Role,
  TaskOwnerKey,
  TaskSupport,
  TextContent,
  ToHandlerEffect,
  Tool,
  ToolAnnotations,
  ToolDefinition,
  ToolExample,
  ToolInputSchema,
  ToolSchemaProvider
}
// `resources/read` payloads and `EmbeddedResource.resource`: the text-or-blob ADT.
export core.wire.{BlobResourceContents, ResourceContents, TextResourceContents}
export core.McpEncoder.given
export core.ToHandlerEffect.given
// Native-core shared codec + schema derivation (one copy for both platforms).
export core.ToolSchemaProviders.given
export codec.McpDecoders.given
export macros.RegistrationMacro.*
export server.{
  Http,
  LimitSettings,
  McpContext,
  McpServer,
  McpServerApp,
  McpServerCore,
  McpServerCoreFactory,
  McpServerSettings,
  Stdio,
  TaskSettings,
  Transport,
  TransportRunner
}
export server.McpServer.given
