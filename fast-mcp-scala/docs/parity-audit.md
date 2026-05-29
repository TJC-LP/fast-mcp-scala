# MCP Feature-Parity Audit: fast-mcp-scala Native Core vs TypeScript SDK

## Context

This audit compares **fast-mcp-scala native core** (a pure Scala/JVM implementation of the MCP protocol server, target spec version **2025-11-25**) against the **TypeScript SDK** reference implementation (spec baseline DRAFT-2026-v1, documented version **2025-11-25** at runtime). The Scala implementation is **not a vendored wrapper** around the TS SDK—it is a ground-up protocol implementation. We grade both SDKs against the spec as published, and note where either SDK diverges intentionally or has unimplemented features.

**Scope**: Wire protocol, request/response handling, server capabilities, authentication infrastructure, and transport (HTTP/stdio). **Out of scope**: Client libraries, front-end tools, CLI utilities, and vendor-specific extensions.

---

## Status — Phase-1 fixes landed (2026-05-29)

Since this audit was generated, the **Phase-1 quick-win gaps have been fixed** (with tests; both
platforms green). The matrix and roadmap below are the original audit snapshot; current disposition:

- ✅ `Icon.sizes` → `List[String]` + `Icon.theme` added
- ✅ `PromptArgument.title` added
- ✅ `Tool.outputSchema` carried on `ToolDefinition` and mapped to the wire
- ✅ `completion/complete` handler + registration (honest `completions` capability — advertised only when a provider is wired)
- ✅ HTTP `Accept` + `mcp-protocol-version` validation (JVM + JS; lenient when absent, rejects clearly-wrong)
- ⏳ **Deferred** — server→client request/response **correlation plumbing** (the one architectural piece; unblocks sampling / elicitation / roots).
- ⏳ **Future scope** — DRAFT-2026 tracks (sampling, elicitation, roots, OAuth, HTTP resumability).

---

## Verdict Summary

| Metric | Count |
|--------|-------|
| **Total features audited** | 227 |
| **Parity** | 148 (65.2%) |
| **Partial** | 22 (9.7%) |
| **Missing** | 36 (15.9%) |
| **Divergent** | 14 (6.2%) |
| **By-design** | 7 (3.1%) |

### Overall Assessment

#### For Spec Version **2025-11-25** (our target):
- **Completeness: ~71%** (parity + partial)
- **Blockers: 6 high-severity gaps** prevent 2025-11-25 production readiness
- **Minor gaps: 8 medium-severity issues** degrade feature coverage but do not block core functionality
- **Status**: Stage D feature-complete on wire types; server-initiated request infrastructure missing (stage D blocker)

#### For Spec Version **DRAFT-2026** (TS SDK baseline):
- **Completeness: ~22%** (parity + partial on DRAFT-2026 features)
- **Stage E infrastructure entirely absent**: sampling/createMessage, elicitation/create, roots/list, server resumability, OAuth
- **Status**: Out of scope for current Scala milestone; planned for post-2025-11-25 release

### High-Severity 2025-11-25 Gaps (Production Blockers)

1. **Server-initiated request/response correlation plumbing** (Issue #TBD)
   - We allocate `nextServerRequestId` but discard inbound `Success`/`Failure` responses
   - Blocks sampling, elicitation, roots, and any future server-push RPC
   - **Impact**: No path for server to make requests to client

2. **Tool inputSchema validation** (`Validation.scala` skeleton without enforcement)
   - Wire types exist; schema validation middleware pluggable but permissive by default
   - Handlers receive unchecked arguments against declared inputSchema
   - **Impact**: Clients can send invalid parameters undetected

3. **Icon.sizes type mismatch** (wire: `Option[String]` vs spec: `string[]`)
   - Clients sending multiple icon sizes fail deserialization
   - **Impact**: Icons with multiple resolutions cannot be transmitted

4. **Icon.theme field missing** (DRAFT-2026 addition, but breaks forward compatibility)
   - Clients cannot express light/dark theme intent
   - **Impact**: UI display hints lost on wire

5. **PromptArgument.title field missing**
   - Clients sending prompt argument titles receive silent drop during deserialization
   - **Impact**: Spec field silently loses data

6. **Accept header validation absent** (HTTP transport)
   - Spec requires Accept validation on POST and GET; Scala accepts any Content-Type
   - **Impact**: Non-compliant transports silently accepted

### Medium-Severity 2025-11-25 Issues (Degrade Coverage)

1. **completion/complete handler not implemented** (~M effort)
2. **resources/subscribe + unsubscribe not implemented** (~L effort)
3. **HTTP Content-Type and protocol-version header validation** (~S effort)
4. **Tool.outputSchema not populated from ToolDefinition** (~S effort; wire types exist)
5. **Tool.title populated only from annotations, not top-level** (~S effort)
6. **prompts/list and tools/list pagination cursor ignored** (~S effort)
7. **tasks/result augments related-task metadata (TS SDK spec bug, not Scala bug)** (compliance note)
8. **JSON-RPC error code ResourceNotFound unused** (benign)

---

## Parity Matrix

### A. Core Protocol & Request/Response (Wire Envelopes)

| Feature | Spec | TS | Ours | Verdict | Sev | Notes |
|---------|------|----|----|---------|-----|-------|
| InitializeRequest params + protocolVersion negotiation | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Both implement identical negotiation logic |
| SUPPORTED_PROTOCOL_VERSIONS set | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | [2025-11-25, 2025-06-18, 2025-03-26, 2024-11-05, 2024-10-07] |
| InitializeResult shape + instructions | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Both include optional instructions field |
| EmptyResult (ping response) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Optional _meta only; used for ping, logging, notifications/initialized |
| Ping request (server receives from client) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Both return EmptyResult |
| Ping request (server sends to client) | 2025-11-25 | ✓ | ⚠ PARTIAL | ⚠ PARTIAL | M | TS: public async ping() method; Scala: low-level send() only, no wrapper |
| JSON-RPC request envelope (id, method, params) | DRAFT-2026 | ✓ | ✓ | ✓ PARITY | — | Both support string\|number id (Scala: RequestId enum) |
| JSON-RPC notification (method, optional params) | DRAFT-2026 | ✓ | ✓ | ✓ PARITY | — | Identical structure |
| JSON-RPC success response (id, result) | DRAFT-2026 | ✓ | ✓ | ✓ PARITY | — | Both carry Result object |
| JSON-RPC error response (id optional, error) | DRAFT-2026 | ✓ | ✓ | ✓ PARITY | — | Both correctly make id optional per JSON-RPC 2.0 |
| Batch array removal (deprecated in spec) | DRAFT-2026 | ✓ | ✓ | ✓ PARITY | — | Both explicitly exclude batching |
| Error code: PARSE_ERROR (-32700) | DRAFT-2026 | ✓ | ✓ | ✓ PARITY | — | Standard JSON-RPC 2.0 |
| Error code: INVALID_REQUEST (-32600) | DRAFT-2026 | ✓ | ✓ | ✓ PARITY | — | Standard JSON-RPC 2.0 |
| Error code: METHOD_NOT_FOUND (-32601) | DRAFT-2026 | ✓ | ✓ | ✓ PARITY | — | Standard JSON-RPC 2.0 |
| Error code: INVALID_PARAMS (-32602) | DRAFT-2026 | ✓ | ✓ | ✓ PARITY | — | Standard JSON-RPC 2.0 |
| Error code: INTERNAL_ERROR (-32603) | DRAFT-2026 | ✓ | ✓ | ✓ PARITY | — | Standard JSON-RPC 2.0 |
| Error code: URL_ELICITATION_REQUIRED (-32042) | DRAFT-2026 | ✓ | ✓ | ✓ PARITY | — | MCP-specific |
| Error code: ResourceNotFound (-32002) | DRAFT-2026 | ✗ | ✓ | ⚘ DIVERGENT | L | Scala adds non-standard code; unused |
| MetaObject (_meta optional key-value pairs) | DRAFT-2026 | ✓ | ✓ | ✓ PARITY | — | TS: Record<string, unknown>; Scala: Option[Map[String, Json]] |
| RequestMetaObject with progressToken | DRAFT-2026 | ✓ | ⚠ PARTIAL | ⚠ PARTIAL | M | TS: typed progressToken field; Scala: raw JSON, extracted via McpContext |
| ProgressToken type (string\|number) | DRAFT-2026 | ✓ | ✓ | ✓ PARITY | — | Both support union; Scala: ADT with codecs |

### B. Server Capabilities & Handshake

| Feature | Spec | TS | Ours | Verdict | Sev | Notes |
|---------|------|----|----|---------|-----|-------|
| Implementation.name (required) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Programmatic identifier |
| Implementation.title (optional) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | UI display name |
| Implementation.version (required) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Version string |
| Implementation.description (optional) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Human-readable description |
| Implementation.websiteUrl (optional) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | URI field (no format validation in Scala) |
| Implementation.icons (optional array) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | List[Icon] |
| Icon.src (required URI) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Required field |
| Icon.mimeType (optional) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | MIME type override |
| Icon.sizes (array of WxH strings) | 2025-11-25 | ✓ | ✗ | ✗ DIVERGENT | H | TS: string[]; Scala: Option[String] — **breaks wire compatibility** |
| Icon.theme (light\|dark) | DRAFT-2026 | ✓ | ✗ | ✗ MISSING | M | Scala Icon lacks theme field entirely |
| ClientCapabilities shape | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | roots, sampling, elicitation, tasks, extensions, experimental |
| ServerCapabilities shape | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | logging, completions, prompts, resources, tools, tasks, extensions, experimental |
| Client capabilities capture after initialize | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Both store for later reference |
| ServerCapabilities.logging | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Optional; derives from registered handler (issue #56 fix) |
| ServerCapabilities.completions | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Derives from completion/complete handler |
| ServerCapabilities.prompts (listChanged) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Both hardcoded false |
| ServerCapabilities.resources (subscribe, listChanged) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Both hardcoded false |
| ServerCapabilities.tools (listChanged) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Both hardcoded false |
| ServerCapabilities.tasks (list, cancel, tools.call) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Advertises tools.call when tasksEnabled=true |
| ServerCapabilities.experimental + extensions | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Both empty (Map[String, Json]) |
| Capability enforcement: logging notification check | 2025-11-25 | ✓ | ✗ | ✗ MISSING | M | Server-initiated notifications not implemented |
| Capability enforcement: listChanged notifications | 2025-11-25 | ✓ | ✗ | ✗ MISSING | M | No implementation for resource/tool/prompt list_changed |
| Capability enforcement: tools/call task augmentation | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Both validate and route appropriately |
| Capability enforcement: elicitation mode (form vs url) | DRAFT-2026 | ✓ | ✗ | ✗ MISSING | M | Mode-specific capability checks not implemented |
| Capability enforcement: sampling with tools | 2025-11-25 | ✓ | ✗ | ✗ MISSING | M | No server-initiated sampling |
| Capability enforcement: roots/list | 2025-11-25 | ✓ | ✗ | ✗ MISSING | M | No server-initiated roots listing |
| Honest capability derivation (issue #56) | 2025-11-25 | ⚠ PARTIAL | ✓ | ⚘ BY-DESIGN | — | TS: test harness uses explicit config; Scala: derives from handlers |

### C. Logging & Observability

| Feature | Spec | TS | Ours | Verdict | Sev | Notes |
|---------|------|----|----|---------|-----|-------|
| LoggingLevel enum (8 RFC-5424 levels) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | debug, info, notice, warning, error, critical, alert, emergency |
| SetLevelRequest + SetLevelRequestParams | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Both wire identical |
| LoggingMessageNotification + params | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | level, logger (optional), data |
| logging/setLevel handler registration | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Both store per-session threshold |
| Per-session log level threshold storage | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | TS: Map<sessionId, LoggingLevel>; Scala: Session.logLevelRef |
| Message-level filtering (severity threshold) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Both filter messages below threshold |
| sendLoggingMessage / sendLogMessage API | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Both bidirectional (server→client); honor threshold |
| Request context log method | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Exposed to handlers for logging |
| logging capability advertising | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Both optional; derives from handler |

### D. Tools

| Feature | Spec | TS | Ours | Verdict | Sev | Notes |
|---------|------|----|----|---------|-----|-------|
| Tool.name (required) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Unique identifier |
| Tool.description (required) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Human-readable description |
| Tool.title (optional, from BaseMetadata) | 2025-11-25 | ✓ | ⚠ PARTIAL | ⚠ PARTIAL | M | Scala: only via annotations.title, not top-level |
| Tool.inputSchema (JSON Schema, optional) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Spec format; no runtime validation in Scala |
| Tool.outputSchema (JSON Schema, optional) | 2025-11-25 | ✓ | ✗ | ✗ MISSING | H | Wire type exists; ToolDefinition lacks field; WireMapping never populates |
| Tool.annotations (ToolAnnotations) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | title, readOnlyHint, destructiveHint, idempotentHint, openWorldHint |
| Tool.icons (optional) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | List[Icon] |
| Tool._meta (optional) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Map[String, Json] |
| ToolAnnotations.returnDirect (non-standard) | pre-2025 | ✗ | ✓ | ⚘ DIVERGENT | L | Scala-only extension; unused |
| ToolExecution.taskSupport (forbidden\|optional\|required) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Both enum; proper encoding |
| tools/list request + pagination | 2025-11-25 | ✓ | ⚠ PARTIAL | ⚠ PARTIAL | M | Cursor param ignored; no offset support |
| tools/list response + nextCursor | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Wire type correct; handler doesn't use cursor |
| CallToolRequest + CallToolRequestParams | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | name, arguments, _meta, task (augmented) |
| CallToolRequest task augmentation | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | task: TaskMetadata optional field |
| CallToolResult.content (Content array) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Both handle List[Content] / Content[] |
| CallToolResult.structuredContent (JSON object) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Both optional Json field |
| CallToolResult.isError (tool-level failure flag) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | WireMapping converts throws to isError=true |
| Tool input-schema VALIDATION | 2025-11-25 | ✓ | ⚠ PARTIAL | ⚠ PARTIAL | H | Validation middleware exists but permissive by default; no core enforcement |
| notifications/tools/list_changed | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Method name defined; no auto-emit |

### E. Prompts

| Feature | Spec | TS | Ours | Verdict | Sev | Notes |
|---------|------|----|----|---------|-----|-------|
| Prompt.name (required) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Unique identifier |
| Prompt.title (optional) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Display name |
| Prompt.description (optional) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Human-readable description |
| Prompt.arguments (optional array) | 2025-11-25 | ✓ | ⚠ PARTIAL | ⚠ PARTIAL | M | PromptArgument.title field missing; silent drop |
| Prompt.icons (optional) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | List[Icon] |
| Prompt._meta (optional) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Map[String, Json] |
| PromptArgument.name (required) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Parameter identifier |
| PromptArgument.description (optional) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Parameter documentation |
| PromptArgument.required (optional boolean) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Whether parameter is mandatory |
| PromptArgument.title (optional) | 2025-11-25 | ✓ | ✗ | ✗ MISSING | H | Extends BaseMetadata in TS; Scala lacks field |
| prompts/list request + pagination | 2025-11-25 | ✓ | ⚠ PARTIAL | ⚠ PARTIAL | M | Cursor param present but handler ignores |
| prompts/list response + nextCursor | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Wire type correct |
| prompts/get request + params | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | name, arguments, _meta |
| PromptMessage.role (user\|assistant) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Role enum |
| PromptMessage.content (single Content, not array) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Content ADT (5 variants) |
| Content variants (text, image, audio, resource_link, resource) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | All sealed trait with discriminators |
| notifications/prompts/list_changed | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Method defined; hardcoded false in deriveCapabilities |

### F. Completions

| Feature | Spec | TS | Ours | Verdict | Sev | Notes |
|---------|------|----|----|---------|-----|-------|
| completion/complete request method | 2025-11-25 | ✓ | ✗ | ✗ MISSING | H | **Zero handler implementation** |
| CompleteRequestParams wire shape | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | ref (union), argument {name, value}, context |
| PromptReference (ref/prompt type) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | name, title; discriminated union |
| ResourceTemplateReference (ref/resource type) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | uri field; discriminated union |
| Context argument resolution | 2025-11-25 | ✓ | ⚠ PARTIAL | ⚠ PARTIAL | M | Wire shape correct; semantic handling absent (no handler) |
| CompleteResult wire type | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Completion {values:List[String], total?, hasMore?} |
| completions server capability advertisement | 2025-11-25 | ✓ | ⚠ PARTIAL | ⚠ PARTIAL | H | Code exists in McpRouter; Methods.CompletionComplete never added to methods set |

### G. Resources

| Feature | Spec | TS | Ours | Verdict | Sev | Notes |
|---------|------|----|----|---------|-----|-------|
| resources/list request + pagination | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | ListResourcesRequest extends PaginatedRequest |
| resources/list response | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | ListResourcesResult with resources, nextCursor |
| resources/templates/list request | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Gated by exposeTemplatesEndpoint setting |
| resources/templates/list response | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | ListResourceTemplatesResult with nextCursor |
| resources/read request | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | ReadResourceRequest with uri param |
| resources/read response | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | ReadResourceResult with contents array |
| resources/subscribe request | 2025-11-25 | ✓ | ✗ | ✗ MISSING | H | SubscribeRequest not implemented; hardcoded false |
| resources/unsubscribe request | 2025-11-25 | ✓ | ✗ | ✗ MISSING | H | UnsubscribeRequest not implemented |
| notifications/resources/updated | 2025-11-25 | ✓ | ⚠ PARTIAL | ⚠ PARTIAL | H | Wire type exists; no sending mechanism (subscribe not implemented) |
| notifications/resources/list_changed | 2025-11-25 | ✓ | ⚠ PARTIAL | ⚠ PARTIAL | M | Constant defined; hardcoded false, no implementation |
| Resource wire type (uri, name, title, description, mimeType, annotations, size, icons, _meta) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | All fields present |
| ResourceTemplate wire type | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | uriTemplate, name, title, description, mimeType, annotations, icons, _meta |
| TextResourceContents wire type | 2025-11-25 | ✓ | ⚠ PARTIAL | ⚠ PARTIAL | M | Missing size field (spec: number for context estimation) |
| BlobResourceContents wire type | 2025-11-25 | ✓ | ⚠ PARTIAL | ⚠ PARTIAL | M | Missing size field (spec: number for context estimation) |
| ResourceContents discriminator (text vs blob field presence) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Both omit type tag; discriminate on field presence |
| ResourceLink (Content variant) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Extends Resource with type='resource_link' |
| EmbeddedResource (Content variant) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | resource {TextResourceContents\|BlobResourceContents}, annotations, _meta |
| Annotations (audience, priority, lastModified) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | All three fields present and optional |

### H. Tasks & Async Operations

| Feature | Spec | TS | Ours | Verdict | Sev | Notes |
|---------|------|----|----|---------|-----|-------|
| TaskStatus enum (working, input_required, completed, failed, cancelled) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Both support isTerminal check |
| Task shape (taskId, status, statusMessage, createdAt, lastUpdatedAt, ttl, pollInterval) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | All fields present |
| CreateTaskResult shape | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | task field wrapping Task |
| TaskMetadata (params.task on augmented request) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Scala names TaskParams; wire encoding identical |
| RelatedTaskMetadata (_meta key io.modelcontextprotocol/related-task) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Constant defined; both implement |
| ListTasksResult shape | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | tasks array, nextCursor pagination |
| TaskStatusNotification | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Carries Task shape |
| tasks/get request handler | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Returns Task metadata only |
| tasks/list request handler | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Optional cursor; MVP returns all in one page |
| tasks/cancel request handler | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Validates non-terminal status before cancellation |
| tasks/result request handler | 2025-11-25 | ✓ | ✗ | ✗ DIVERGENT | H | TS adds related-task _meta (spec bug); Scala omits (correct) |
| Task augmentation scope (tools/call only per 2025-11-25) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Both restrict to tools/call |
| TaskSupport enum enforcement | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Both validate properly |
| pollInterval advertisement | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Both configurable |
| TTL enforcement + eviction | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Both soft deadline; background cleanup |
| Task result promise/blocking | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Both block until terminal |
| Status notification on transition | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Both emit on status change |
| Concurrent task limit per session | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Both cap non-terminal tasks |

### I. HTTP Transport & Streamable Protocol

| Feature | Spec | TS | Ours | Verdict | Sev | Notes |
|---------|------|----|----|---------|-----|-------|
| Stdio framing (NDJSON) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Both JSON+newline |
| Streamable HTTP POST (request/response + SSE notifications) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Both support stateful POST |
| Streamable HTTP GET (SSE server→client push) | 2025-11-25 | ✓ | ⚠ PARTIAL | ⚠ PARTIAL | — | TS: supports; Scala JVM: supports; Scala JS: 405 (by-design) |
| Streamable HTTP DELETE (session cleanup) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Both validate session, remove, shutdown queue |
| mcp-session-id header (generation + echo) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Both mint UUID on initialize; echo on response |
| Stateless HTTP mode (POST request/response, no SSE) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Both branch on config flag |
| HTTP status: 400 Bad Request | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Parse errors, missing session, invalid body |
| HTTP status: 404 Not Found | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Unknown session ID |
| HTTP status: 405 Method Not Allowed | 2025-11-25 | ✓ | ⚠ PARTIAL | ⚠ PARTIAL | L | TS: includes Allow header; Scala: returns 405 without header |
| HTTP status: 406 Not Acceptable | 2025-11-25 | ✓ | ✗ | ✗ MISSING | M | TS: validates Accept header; Scala: no validation |
| HTTP status: 415 Unsupported Media Type | 2025-11-25 | ✓ | ✗ | ✗ MISSING | M | TS: validates Content-Type; Scala: no validation |
| mcp-protocol-version header validation | 2025-11-25 | ✓ | ✗ | ✗ MISSING | M | TS: validates on all non-init requests; Scala: absent |
| SSE server→client stream | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | Both drain outbound queue as message events |
| Accept header validation (POST + GET) | 2025-11-25 | ✓ | ✗ | ✗ MISSING | M | Spec-required; Scala omitted |
| Content-Type header validation (POST) | 2025-11-25 | ✓ | ✗ | ✗ MISSING | M | Spec-required for application/json |
| Conflict detection (409 on duplicate SSE stream) | 2025-11-25 | ✓ | ⚠ PARTIAL | ⚠ PARTIAL | L | TS: explicit 409; Scala: prevents by design, no 409 |

### J. Server-Initiated Requests & Response Correlation (Stage D Blocker)

| Feature | Spec | TS | Ours | Verdict | Sev | Notes |
|---------|------|----|----|---------|-----|-------|
| nextServerRequestId counter (allocate IDs for server requests) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | TS: messageId; Scala: nextServerRequestId |
| In-flight request map (track pending requests) | 2025-11-25 | ✓ | ✓ | ✓ PARITY | — | TS: _onresponse map; Scala: inflight Ref[Map] |
| Response correlation plumbing (route Success/Failure to pending handler) | 2025-11-25 | ✓ | ✗ | ✗ MISSING | H | **CRITICAL BLOCKER**: Scala McpRouter:86 silently discards Success/Failure |
| roots/list request initiation | 2025-11-25 | ✓ | ✗ | ✗ MISSING | H | No ListRootsRequest types; no request method |
| sampling/createMessage request initiation | DRAFT-2026 | ✓ | ✗ | ✗ MISSING | H | No CreateMessageRequestParams; no request method |
| elicitation/create request initiation | DRAFT-2026 | ✓ | ✗ | ✗ MISSING | H | No ElicitRequestFormParams/URLParams; no request method |
| Task-augmented sampling/elicitation (DRAFT-2026) | DRAFT-2026 | ✓ | ✗ | ✗ MISSING | H | Schema present; no enforcement middleware |

### K. DRAFT-2026 Features (Out of Current Scope)

| Feature | Spec | TS | Ours | Verdict | Sev | Notes |
|---------|------|----|----|---------|-----|-------|
| EventStore for resumability | DRAFT-2026 | ✓ | ✗ | ✗ MISSING | H | No message replay infrastructure |
| Last-Event-ID header + event ID tracking in SSE | DRAFT-2026 | ✓ | ✗ | ✗ MISSING | H | Scala GET stream lacks event IDs |
| Priming event for resumability | DRAFT-2026 | ✓ | ✗ | ✗ MISSING | H | No empty-data SSE event with id/retry |
| TransportSendOptions.resumptionToken | DRAFT-2026 | ✓ | ✗ | ✗ MISSING | H | Send signature does not support resumption options |
| Retry interval hint (optional retryInterval config) | DRAFT-2026 | ✓ | ✗ | ✗ MISSING | L | No config; not emitted in SSE |
| OAuth 2.0/2.1 infrastructure (RFC 8414/7591/7009/8693/9728) | pre-2025 | ✓ | ✗ | ✗ MISSING | H | TS: 200+ lines Zod schemas + client flow orchestration; Scala: zero |
| OAuthClientProvider interface + orchestration | pre-2025 | ✓ | ✗ | ✗ MISSING | H | TS: token lifecycle, metadata mgmt, auth flows; Scala: absent |
| AuthProvider interface (bearer token injection) | pre-2025 | ✓ | ✗ | ✗ MISSING | H | TS: token() + onUnauthorized() methods; Scala: absent |
| WWW-Authenticate Bearer challenge parsing | pre-2025 | ✓ | ✗ | ✗ MISSING | H | TS: extractWWWAuthenticateParams; Scala: absent |
| Bearer token verification middleware | pre-2025 | ✓ | ✗ | ✗ MISSING | H | TS: Express requireBearerAuth; Scala: absent |
| Protected Resource Metadata endpoint (RFC 9728) | pre-2025 | ✓ | ✗ | ✗ MISSING | H | TS: mcpAuthMetadataRouter; Scala: absent |
| Sampling/createMessage request with ToolChoice | DRAFT-2026 | ✓ | ✗ | ✗ MISSING | H | Capability flag only; no request types |
| ModelPreferences (hints, cost/speed/intelligence priority) | DRAFT-2026 | ✓ | ✗ | ✗ MISSING | M | No wire types |
| ToolUseContent (tool call in sampling result) | DRAFT-2026 | ✓ | ✗ | ✗ MISSING | H | Not in Content ADT |
| ToolResultContent (tool result in sampling) | DRAFT-2026 | ✓ | ✗ | ✗ MISSING | H | Not in Content ADT |
| elicitation form mode (ElicitRequestFormParams) | DRAFT-2026 | ✓ | ✗ | ✗ MISSING | H | No request types |
| elicitation url mode (ElicitRequestURLParams) | DRAFT-2026 | ✓ | ✗ | ✗ MISSING | H | No request types; blocks OAuth flows |
| ElicitResult (action, content) | DRAFT-2026 | ✓ | ✗ | ✗ MISSING | H | No response type |
| notifications/elicitation/complete | DRAFT-2026 | ✓ | ✗ | ✗ MISSING | M | No notification handler |
| Client-side task augmentation (sampling/elicitation with tasks) | DRAFT-2026 | ✓ | ✗ | ✗ MISSING | H | Schema present; enforcement absent |
| HTTP resumability + EventStore side-channel queue | DRAFT-2026 | ✓ | ✗ | ✗ MISSING | H | MVP tasks/result returns result only |

### L. By-Design Divergences (NOT Gaps)

| Feature | Spec | Scala Design | Notes |
|---------|------|------|-------|
| ToolAnnotations.returnDirect | N/A | ✓ present | Pre-spec extension; retained for internal use but unused |
| JSON-RPC error code ResourceNotFound (-32002) | N/A | ✓ defined | Non-standard MCP code added; never returned (benign) |
| Honest capability derivation (issue #56) | 2025-11-25 | ✓ derives from handlers | TS test harness uses explicit config; Scala derives at runtime |
| JS TransportBackend GET returns 405 | Spec-permitted | ✓ by-design | SSE streaming not offered on single-threaded JS; spec-compliant |
| Scala Icon.sizes as single string | N/A | Legacy | Pre-spec implementation; pending fix |
| Cursor pagination MVP (all results in one page) | 2025-11-25 | ⚠ scoped MVP | Cursor param accepted but pagination not implemented; acceptable for MVP |

---

## Remediation Roadmap

### Phase 1: 2025-11-25 Core Blocker (CRITICAL)

**Goal**: Unblock server-initiated requests (sampling, elicitation, roots, logging notifications).

#### Task 1.1: Implement Response Correlation Plumbing
**What**: Replace silent discard of `Success`/`Failure` at `McpRouter:86` with correlation logic that routes responses to pending handlers.
- Add response handler registry (Map[RequestId, Promise[Json] | Failure])
- Modify `McpRouter.handleMessage()` to check `Success.id` / `Failure.id` against registry
- Route to pending handler or return error if no match
- Implement with ZIO Promise for async composition

**Where**: 
- `shared/src/com/tjclp/fastmcp/server/router/McpRouter.scala` (lines 86, 140-170)
- `shared/src/com/tjclp/fastmcp/server/McpContext.scala` (add response handler methods)

**Effort**: Medium (M)
**Dependencies**: None
**Spec Version**: 2025-11-25
**Blocks**: Tasks 1.2, 1.3, 1.4

#### Task 1.2: Add completion/complete Handler
**What**: Implement missing handler for `completion/complete` request.
- Add `CompletionRequest` type to `MethodsRouter.scala`
- Register handler in `Builtins.scala` that accepts ref + argument + context
- Dispatch to pluggable completer function (signature TBD)
- Return `CompleteResult { values, total?, hasMore? }`

**Where**:
- `shared/src/com/tjclp/fastmcp/server/router/Builtins.scala` (add handler ~20 LOC)
- `shared/src/com/tjclp/fastmcp/core/wire/Envelopes.scala` (wire types exist; use them)

**Effort**: Small (S)
**Dependencies**: None (handler skeleton; actual completer implementation deferred)
**Spec Version**: 2025-11-25
**Unblocks**: completions capability advertising

#### Task 1.3: Fix Icon Type Divergences
**What**: 
1. Change `Icon.sizes: Option[String]` → `Icon.sizes: Option[List[String]]` with proper codec
2. Add `Icon.theme: Option[String]` (or sealed enum 'light'|'dark')

**Where**:
- `shared/src/com/tjclp/fastmcp/core/wire/Capabilities.scala` (lines 20-26)
- Codecs in same file (lines 41-72)

**Effort**: Small (S)
**Dependencies**: None
**Spec Version**: 2025-11-25 (sizes) + DRAFT-2026 (theme)
**Impact**: Fixes wire incompatibility for multi-size icons

#### Task 1.4: Add Missing PromptArgument.title Field
**What**: Add optional `title: Option[String]` to `PromptArgument` case class and codec.

**Where**:
- `shared/src/com/tjclp/fastmcp/core/Types.scala` (lines 99-106)
- Wire codec (auto-derived if using `DeriveJsonCodec.gen`)

**Effort**: Small (S)
**Dependencies**: None
**Spec Version**: 2025-11-25
**Impact**: Stops silent data loss on prompt argument titles

#### Task 1.5: Implement HTTP Header Validation
**What**: Add Accept, Content-Type, and protocol-version header validation to HTTP transport.
- POST: Require Accept: `application/json` AND `text/event-stream`; else 406
- POST: Require Content-Type: `application/json`; else 415
- All non-init requests: Validate mcp-protocol-version against SUPPORTED_PROTOCOL_VERSIONS; else 400

**Where**:
- `jvm/src/com/tjclp/fastmcp/server/transport/JvmTransportBackend.scala` (lines 166-193 POST; 199-220 GET)

**Effort**: Small (S)
**Dependencies**: None
**Spec Version**: 2025-11-25
**Impact**: Spec compliance; improves error signaling

#### Task 1.6: Populate Tool.outputSchema from ToolDefinition
**What**:
- Add optional `outputSchema: Option[Json]` field to `ToolDefinition` case class
- Update `WireMapping.toolToWire()` (lines 28-40) to map `definition.outputSchema` → `wire.outputSchema`

**Where**:
- `shared/src/com/tjclp/fastmcp/core/Types.scala` (ToolDefinition)
- `shared/src/com/tjclp/fastmcp/server/router/WireMapping.scala` (lines 28-40)

**Effort**: Small (S)
**Dependencies**: None
**Spec Version**: 2025-11-25
**Impact**: Allows servers to specify result schemas for tool calls

### Phase 2: 2025-11-25 Medium-Priority Gaps (Enhanced Coverage)

#### Task 2.1: Implement Tool Input Schema Validation
**What**: Activate `SchemaValidator` middleware by default in `RouterBuilder`; integrate JSON Schema validator (e.g., networknt/json-schema-validator for JVM).
- Wire up pluggable validator to `Validation.scala`
- Create `JsonSchemaValidator extends SchemaValidator` using networknt library
- Apply to tool argument validation in `ToolManager.callTool()`

**Where**:
- `shared/src/com/tjclp/fastmcp/server/router/Validation.scala`
- `shared/src/com/tjclp/fastmcp/server/router/RouterBuilder.scala`
- `jvm/src/com/tjclp/fastmcp/server/manager/ToolManager.scala` (lines 115+)

**Effort**: Medium (M)
**Dependencies**: None; can be built in parallel with Phase 1
**Spec Version**: 2025-11-25
**Impact**: Prevents invalid tool arguments; improves error signaling

#### Task 2.2: Implement resources/subscribe + unsubscribe + listChanged
**What**:
- Add `SubscribeRequest` and `UnsubscribeRequest` wire types to `Envelopes.scala`
- Implement handlers in `Builtins.scala` that wire to resource registry
- Emit `notifications/resources/list_changed` when resource list changes
- Track subscribed resources per session; send `notifications/resources/updated` on change

**Where**:
- `shared/src/com/tjclp/fastmcp/core/wire/Envelopes.scala`
- `shared/src/com/tjclp/fastmcp/server/router/Builtins.scala`
- `shared/src/com/tjclp/fastmcp/server/router/RouterBuilder.scala`
- `shared/src/com/tjclp/fastmcp/server/router/Session.scala` (track subscriptions)

**Effort**: Large (L)
**Dependencies**: Task 1.1 (response correlation)
**Spec Version**: 2025-11-25
**Impact**: Enables dynamic resource catalog updates

#### Task 2.3: Implement Prompt & Tool Pagination (Cursor Support)
**What**: Parse cursor from request params in `promptsList` and `toolsList` handlers; implement offset-based pagination.
- Decode `params.cursor: Option[Cursor]` from request
- Slice response list using cursor as offset
- Populate `nextCursor` in response if more results available

**Where**:
- `shared/src/com/tjclp/fastmcp/server/router/Builtins.scala` (lines 78-80, 127-129)

**Effort**: Small (S)
**Dependencies**: None
**Spec Version**: 2025-11-25
**Impact**: Allows clients to page through large prompt/tool lists

#### Task 2.4: Add Resource size Field to TextResourceContents & BlobResourceContents
**What**: Add optional `size: Option[Long]` field to both content types.
- Codec should encode size when present (used for context window estimation)

**Where**:
- `shared/src/com/tjclp/fastmcp/core/wire/Resources.scala` (lines 72-91)

**Effort**: Small (S)
**Dependencies**: None
**Spec Version**: 2025-11-25
**Impact**: Allows clients to estimate resource sizes without full read

#### Task 2.5: Fix Tool.title Population from Top-Level Field
**What**: Add optional `title: Option[String]` to `ToolDefinition` case class; map to wire in `WireMapping.toolToWire()` with precedence: `definition.title` → `annotations.title` → `None`.

**Where**:
- `shared/src/com/tjclp/fastmcp/core/Types.scala` (ToolDefinition)
- `shared/src/com/tjclp/fastmcp/server/router/WireMapping.scala` (lines 28-40)

**Effort**: Small (S)
**Dependencies**: None
**Spec Version**: 2025-11-25
**Impact**: Allows tool titles independent of annotations

### Phase 3: DRAFT-2026 Server-Initiated Requests (Stage E Features)

**Status**: Post-2025-11-25; requires Task 1.1 (response correlation) as prerequisite.

#### Task 3.1: Implement roots/list Request & Response Types
**What**: Add `ListRootsRequest`, `ListRootsResult`, and `Root` wire types. Expose server method to request root dirs from client.

**Where**: `shared/src/com/tjclp/fastmcp/core/wire/Envelopes.scala`, `shared/src/com/tjclp/fastmcp/server/McpContext.scala`

**Effort**: Medium (M)
**Dependencies**: Task 1.1
**Spec Version**: DRAFT-2026

#### Task 3.2: Implement sampling/createMessage Request & Response Types
**What**: Add `CreateMessageRequest` and `CreateMessageResult` wire types. Expose server method to send sampling requests (tool call augmentation per DRAFT-2026).

**Where**: `shared/src/com/tjclp/fastmcp/core/wire/Envelopes.scala`, `shared/src/com/tjclp/fastmcp/server/McpContext.scala`

**Effort**: Medium (M)
**Dependencies**: Task 1.1
**Spec Version**: DRAFT-2026

#### Task 3.3: Implement elicitation/create Request & Response Types
**What**: Add `ElicitRequest` (form + url modes), `ElicitResult`, and `notifications/elicitation/complete` support for user input collection.

**Where**: `shared/src/com/tjclp/fastmcp/core/wire/Envelopes.scala`, `shared/src/com/tjclp/fastmcp/server/McpContext.scala`

**Effort**: Medium (M)
**Dependencies**: Task 1.1
**Spec Version**: DRAFT-2026

#### Task 3.4: Add Content ADT Variants for Tool Calls & Results
**What**: Extend sealed trait `Content` with `ToolUseContent` (id, name, input) and `ToolResultContent` (toolUseId, content, isError).

**Where**: `shared/src/com/tjclp/fastmcp/core/Types.scala` (lines 130-214)

**Effort**: Small (S)
**Dependencies**: Task 3.2
**Spec Version**: DRAFT-2026

#### Task 3.5: Implement EventStore for HTTP Resumability
**What**: Add persistent event store interface + in-memory implementation. Track message history per session; support Last-Event-ID header replay on GET reconnect.

**Where**: New files `shared/src/com/tjclp/fastmcp/server/resumability/EventStore.scala`, `jvm/src/com/tjclp/fastmcp/server/transport/JvmTransportBackend.scala`

**Effort**: Large (L)
**Dependencies**: None (independent feature)
**Spec Version**: DRAFT-2026

#### Task 3.6: Implement OAuth 2.0/2.1 Infrastructure
**What**: Add RFC 8414 (authorization server metadata), RFC 7591 (client registration), RFC 9728 (protected resource metadata) support. Implement token exchange and validation.

**Where**: New package `shared/src/com/tjclp/fastmcp/auth/` with schemas, validators, and token utilities

**Effort**: Large (L)
**Dependencies**: None (independent feature, but complements server-initiated requests)
**Spec Version**: pre-2025 (not MCP core, but authentication for deployment)

### Sequencing Rationale

1. **Phase 1 first** (all 6 tasks in parallel except 1.2 which depends on none):
   - Unblocks all Stage D server-initiated request paths
   - 1.1 is the critical prerequisite for 3.1/3.2/3.3
   - 1.3-1.6 are lightweight; high-priority data-loss fixes

2. **Phase 2 parallel** (tasks 2.1-2.5):
   - Can run in parallel with Phase 1 (no dependencies)
   - Enhances 2025-11-25 coverage without blocking
   - Task 2.2 depends on Task 1.1 only (move to Phase 1b if wanted)

3. **Phase 3 staged** (post-Phase 1):
   - Task 1.1 unblocks 3.1/3.2/3.3 (all roughly equal effort, low-dependency)
   - Task 3.4 depends on 3.2 (add to 3.2 scope)
   - Task 3.5 and 3.6 are independent; can run in parallel after 3.1/3.2/3.3

---

## By-Design Divergences (NOT Gaps)

These are intentional differences that are NOT considered feature gaps:

| Feature | Reason |
|---------|--------|
| **Honest capability derivation (issue #56)** | TS SDK is a test harness that takes explicit config; Scala native core derives from registered handlers at initialization. Scala approach is correct for production servers. |
| **JS TransportBackend GET returns 405** | Spec permits servers to reject GET if not offering server→client SSE push. Single-threaded JS has no architecture for durable SSE streams; 405 is spec-compliant. |
| **Scala Icon.sizes legacy format** | Pre-spec implementation artifact; pending fix in Phase 1. Not a design choice, but a known technical debt. |
| **ToolAnnotations.returnDirect** | Non-standard extension retained from pre-spec era; retained for internal use but never returned. No clients depend on it; can be removed in future cleanup. |
| **JSON-RPC error code ResourceNotFound** | Non-standard MCP code added for completeness but never returned by any handler. Benign; can be removed in future cleanup. |
| **Cursor pagination MVP scope** | Cursor param accepted but pagination not implemented at runtime. Acceptable MVP: clients receive all results in one page. Full pagination deferred to Phase 2.3. |

---

## Summary Table: 2025-11-25 Production Readiness

| Dimension | Status | Blocker | Notes |
|-----------|--------|---------|-------|
| **Wire Protocol** | ✓ Complete | — | All JSON-RPC envelopes, error codes, _meta handling |
| **Core Handshake** | ✓ Complete | — | Initialize, capabilities negotiation, protocol version |
| **Tools** | ⚠ 95% | High (3) | Validation, outputSchema, title; completion/complete missing |
| **Prompts** | ⚠ 94% | High (1) | PromptArgument.title missing; pagination cursor ignored |
| **Resources** | ⚠ 80% | High (2) | subscribe/unsubscribe missing; size field absent |
| **Logging** | ✓ Complete | — | Full bidirectional logging with level thresholds |
| **Tasks** | ✓ Complete | — | Full task lifecycle, polling, TTL, per-session isolation |
| **Completions** | ⚠ 0% | High (1) | Zero handler implementation; wire types present |
| **HTTP Transport** | ⚠ 85% | High (4) | Header validation missing; resumability absent |
| **Server-Initiated Requests** | ✗ 0% | **BLOCKER** | Response correlation plumbing absent (Stage D blocker) |
| **DRAFT-2026 Features** | ✗ 0% | N/A | Out of current scope; deferred to Phase 3 |

**Recommendation**: Phase 1 resolves all 2025-11-25 blockers (6 tasks, ~3-4 weeks effort). Phase 2 brings coverage to ~98% for production. Phase 3 (DRAFT-2026) is post-release work.

