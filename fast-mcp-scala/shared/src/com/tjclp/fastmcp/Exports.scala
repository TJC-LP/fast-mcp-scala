package com.tjclp.fastmcp

export core.{
  toAst,
  toJsonString,
  AsResourceBody,
  AudioContent,
  Content,
  EmbeddedResource,
  ImageContent,
  LoggingLevel,
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
  ProgressToken,
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
// Shapes a handler must NAME to call the public `McpContext` / `McpServer` surface: the
// server→client request params and results (`createMessage`, `elicit` / `elicitUrl`, `listRoots`),
// the client identity snapshots (`getClientInfo` / `getClientCapabilities`) and the
// `completion/complete` provider's input and output. `core.wire.Tool` (the sampling `tools`
// element) stays unexported: it would collide with the `@Tool` annotation exported above.
export core.wire.{
  ClientCapabilities,
  CompleteRequestParams,
  Completion,
  CompletionArgument,
  CompletionContext,
  CompletionReference,
  CreateMessageRequestParams,
  CreateMessageResult,
  ElicitRequestParams,
  ElicitRequestUrlParams,
  ElicitResult,
  Implementation,
  ListRootsResult,
  ModelHint,
  ModelPreferences,
  PromptReference,
  ResourceTemplateReference,
  Root,
  SamplingMessage,
  ToolChoice
}
// Skills extension (io.modelcontextprotocol/skills): the authoring types, the URI policy, the
// verification helpers and the wire entry shapes a server author or a host-side verifier names.
export core.skills.{
  HeldEntry,
  McpSkill,
  SkillError,
  SkillFile,
  SkillIdentity,
  SkillSnapshot,
  SkillUri,
  SkillVerifier,
  VerificationFailure,
  VerificationOutcome
}
export core.wire.{Skill, SkillFrontmatter, SkillResource, SkillResources, Skills}
export server.skills.{SkillFileContent, SkillProvider}
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
  SkillSettings,
  Stdio,
  TaskSettings,
  Transport,
  TransportRunner
}
export server.McpServer.given
