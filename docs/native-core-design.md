# Native Scala MCP Core — Design Doc

**Status:** Draft · M0 (workspace bootstrapped)
**Linear:** [TJC-1131](https://linear.app/tjc-technologies/issue/TJC-1131/replace-javats-mcp-sdks-with-native-scala-mcp-core)
**Trigger:** GitHub issue [#56](https://github.com/TJC-LP/fast-mcp-scala/issues/56) (logging capability mis-advertised on stateless transport)
**Related PR:** [#58](https://github.com/TJC-LP/fast-mcp-scala/pull/58) — quick-win for the stateless 400 (lands on `main`; this branch will obviate it)

## Why

fast-mcp-scala wraps the official Java MCP SDK (`mcp-core` + `mcp-json-jackson3`) on JVM and the TypeScript MCP SDK (`@modelcontextprotocol/sdk`) on Scala.js. Each wrap costs us:

| Friction | Where it shows up today |
|---|---|
| Capability mis-advertisement we can't fix | Issue #56. `McpAsyncServer.java:136,164` forces `.mutate().logging().build()` on whatever we pass. Stateless avoids the override, but **also** never registers a `logging/setLevel` handler — verified gap through Java SDK v2.0.0-M3. |
| Jackson ↔ ZIO JSON bridge | `jvm/src/com/tjclp/fastmcp/macros/JacksonConverter.scala`, `JacksonConversionContext.scala` |
| Mono ↔ ZIO bridging | Every handler in `FastMcpServer.scala` (~1162 lines) and `JsMcpServer.scala` (~822 lines) |
| Stateful/stateless code duplication | `ZioHttpStatelessTransport`, `ZioHttpStreamableTransportProvider`, plus a `TaskAugmentedHttpTransport` hack on top |
| TS SDK Zod schemas at runtime | `js/src/com/tjclp/fastmcp/facades/server/Schemas.scala`, `AjvJsonSchemaValidator.scala` |
| Inability to ship spec features promptly | Server logging emit hooks, future SEPs — all gated on SDKs catching up |

This rewrite removes both SDK dependencies from the production classpath. The TS SDK stays only as a **test-time client** for cross-validation. The Java SDK is deleted entirely.

## Non-goals

- **MCP client support.** fast-mcp-scala is server-only and stays that way.
- **Wire batching.** The spec dropped batches at 2025-06-18.
- **Spring / sync server variants.** ZIO-only.
- **Maven Central coordinate changes.** Artifact IDs are stable.

## Guiding principles

1. **User-facing API stays stable.** `@Tool`, `@Resource`, `@Prompt`, `scanAnnotations`, `McpTool.derived`, `McpServer.typed[R]`, `McpServerSettings` keep their shapes. Internal interop types are the only thing changing.
2. **`shared/` carries more.** Without SDK interop locked to JVM, schema / JSON-RPC / dispatch all collapse into a single platform-neutral codebase.
3. **Capability auto-derivation.** The dispatcher exposes only capabilities whose handlers are actually registered. Issue #56 disappears by construction.
4. **Tests stay green at every milestone.** The existing suite is our regression net. Merge happens only at M8 green.
5. **Tasks 2025-11-25 first-class.** No more transport-layer interceptor — task dispatch is just router middleware.

## Open decisions (resolve at M1)

| | Decision | Notes |
|---|---|---|
| 1 | Spec target | Default: 2025-11-25 only. Drop pre-2025-06-18 wire support. Need user sign-off. |
| 2 | JSON Schema validation on `tools/call` | TS SDK enforces; Java SDK enforces from v2.0.0-M1. Options: (a) skip, (b) optional middleware, (c) required. Default proposal: (b) — pull a lightweight validator (`networknt/json-schema-validator` on JVM, `ajv` only at runtime if needed) as opt-in M4 middleware. |
| 3 | Backwards-compat shims | Default: drop deprecated aliases (`FastMcpServerSettings`) at 0.4.0. Project is pre-1.0; cleaner break is justifiable. |
| 4 | TS SDK as test dependency | Default: keep `@modelcontextprotocol/sdk` in `bunTestInstall` only. Provides ongoing cross-validation; doesn't bloat production. |

## Architecture sketch

```
                       ┌──────────────────────────────────────┐
                       │     User code (annotations / typed   │
                       │     contracts / @Tool / etc.)        │
                       └─────────────────┬────────────────────┘
                                         │
                                         ▼
                       ┌──────────────────────────────────────┐
                       │     McpServer / FastMcpServer /      │
                       │     JsMcpServer (thin orchestrators) │
                       └─────────────────┬────────────────────┘
                                         │ register(tool|resource|prompt)
                                         ▼
                       ┌──────────────────────────────────────┐
                       │     McpRouter [shared/]              │
                       │  ├─ handler map (capability source)  │
                       │  ├─ Session (per connection)         │
                       │  ├─ middleware (val / tasks / err)   │
                       │  └─ built-ins (initialize, ping,     │
                       │       tools/list, …, logging/setLevel│
                       │       — registered only when wired)  │
                       └─────────────────┬────────────────────┘
                                         │
                  ┌──────────────────────┼──────────────────────┐
                  ▼                      ▼                      ▼
        ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
        │  Stdio (NDJSON) │    │  HTTP stateless │    │ HTTP streamable │
        │  ZIO Stream /   │    │  ZIO HTTP /     │    │ ZIO HTTP /      │
        │  Bun stdin out  │    │  Bun.serve      │    │ Bun.serve + SSE │
        └─────────────────┘    └─────────────────┘    └─────────────────┘
```

Macros (`@Tool`, `@Resource`, `@Prompt`, `scanAnnotations`) target `server.tool(toolDef, handler)` and friends — those signatures stay constant, so macro consumers don't change.

## Milestone plan

Each milestone ends in a compilable, test-passing checkpoint. Commits push incrementally; PR opens against `main` only at M8 green.

### M1 · MCP schema in pure Scala (`shared/`)

Audit & complete `shared/src/com/tjclp/fastmcp/core/Types.scala` so every 2025-11-25 wire type has a Scala 3 representation independent of any SDK:

- `Implementation`, `ServerCapabilities` (+ sub-capabilities: `LoggingCapabilities`, `ToolCapabilities`, `ResourceCapabilities`, `PromptCapabilities`, `CompletionsCapabilities`, `TasksCapabilities`, `ExperimentalCapabilities`).
- `Content` ADT: `TextContent`, `ImageContent`, `AudioContent`, `EmbeddedResource`, `ResourceLink`.
- `Tool`, `ToolInputSchema`, `ToolOutputSchema`, `ToolAnnotations` (incl. `taskSupport`).
- `Resource`, `ResourceTemplate`, `ResourceContents` (text vs. blob).
- `Prompt`, `PromptArgument`, `PromptMessage`.
- `LoggingLevel`, `LoggingMessageNotification`, `SetLevelRequest`.
- Request/result envelopes: `initialize`, `tools/list`, `tools/call`, `resources/list`, `resources/read`, `resources/templates/list`, `prompts/list`, `prompts/get`, `completion/complete`, `logging/setLevel`, `ping`.
- Notifications: `notifications/initialized`, `notifications/cancelled`, `notifications/message`, `notifications/resources/updated`, list-changed notifications, `notifications/progress`.
- Tasks 2025-11-25 envelope (already in `core.Tasks` — audit).
- `Cursor` (pagination), `ProtocolVersion`.
- `ErrorCodes` constants.

**Exit criteria:** all types compile in `shared/`; no SDK types referenced from `shared/`.

### M2 · ZIO JSON codecs (`shared/`)

Encoders/decoders for every M1 type. Use `DeriveJsonCodec.gen` where shape matches; hand-write where wire shape diverges (sum-type tagging, absent-vs-null).

**Exit criteria:** captured wire-message fixtures (initialize, tools/list, tools/call, resources/read, etc.) round-trip through codecs. Spec-conformant absent-vs-null (e.g. `_meta` absent rather than null).

### M3 · JSON-RPC 2.0 core (`shared/`)

- `JsonRpcMessage` ADT: `Request(id, method, params)`, `Notification(method, params)`, `Response(id, result | error)`.
- `Handler[R] = (Session, Json) => ZIO[R, McpError, Json]`.
- Server-initiated request plumbing (sampling, elicit, log notifications).
- `McpError` ADT replacing SDK `McpError` — ZIO-friendly. `ErrorMapper.scala` collapses into this.
- Cancellation: incoming `notifications/cancelled` cancels in-flight fibers by request id.

### M4 · Dispatcher / server kernel (`shared/`)

The piece that replaces `McpAsyncServer` and TS `Server`:

- `McpRouter` builds an immutable handler map from registered tools/resources/prompts/completions + built-ins.
- `ServerCapabilities` **derived** from the handler map. `logging` advertised iff a logging emit point is registered. `tools.listChanged` toggles based on dynamic registration. **Issue #56 fixed here by construction.**
- Built-in handlers: `ping`, `initialize`, `notifications/initialized`, `tools/list`, `tools/call`, `resources/list`, `resources/read`, `resources/templates/list`, `prompts/list`, `prompts/get`, `completion/complete`, `logging/setLevel`.
- `Session`: per-connection state (negotiated protocol version, log level, subscriptions, server-initiated request counter).
- Middleware chain: validation → tasks augmentation → handler → error mapping. `TaskDispatcher` becomes a clean middleware.
- Hooks: `BeforeToolCall`, `AfterToolCall`, `OnError`. Wire logging emit / metrics / tracing here.

### M5 · Stdio transports

- **JVM**: ZIO Stream reads NDJSON from stdin, parses, drives router, writes NDJSON to stdout.
- **JS**: Bun process IO using existing facades.
- Single shared core (`StdioTransport.scala`) wraps router; platform code is a thin I/O adapter.

### M6 · HTTP transports

- **Stateless**: `POST /mcp` → router → JSON-RPC reply.
- **Streamable**: `POST /mcp` (with `mcp-session-id`), `GET /mcp` (SSE), `DELETE /mcp` (terminate). Session store: `Ref[Map[SessionId, SessionState]]`.
- Task augmentation flows through router middleware (M4), not the HTTP layer.

### M7 · Migrate user-facing API

`FastMcpServer.scala` and `JsMcpServer.scala` shrink dramatically — they become orchestrators over `McpRouter`. `McpContext` collapses into a single shared definition with new methods: `sendLogMessage`, `sendProgress`, `sessionId`, `requestId`. Macros unchanged. Typed contracts unchanged.

### M8 · Cut over and delete SDK dependencies

Only after M1–M7 are green:

- Delete `mcp-core` + `mcp-json-jackson3` from `build.mill`.
- Delete `@modelcontextprotocol/sdk` from production `bunInstall` (keep in `bunTestInstall`).
- Delete `js/src/com/tjclp/fastmcp/facades/server/` tree.
- Delete `jvm/src/com/tjclp/fastmcp/macros/JacksonConverter.scala`, `JacksonConversionContext.scala`.
- Collapse `jvm/src/com/tjclp/fastmcp/core/Types.scala` JVM-specific `toJava`/`fromJava` into shared.
- Regenerate native-image reachability metadata.
- Update `CLAUDE.md` "Java SDK Interop" → "Native Scala MCP core".
- Bump to `0.4.0-SNAPSHOT`; tag `0.4.0-RC1`.

### M9 · Conformance + issue #56 closeout

- MCP Inspector end-to-end on stdio, stateless HTTP, streamable HTTP.
- Regression test: capabilities derived from registered handlers.
- Optional `LoggingHook` follow-up.

## Risks

| Risk | Mitigation |
|---|---|
| Hidden SDK quirks (malformed-message tolerance, header casing, SSE keep-alive timing) | M9 Inspector run + TS SDK as test-time client catches divergence |
| Macro consumers break | Macros target stable internal surface; audit `RegistrationMacro` at M7 boundary before deleting SDK |
| Native-image regressions | Regenerate metadata in M8; native-image smoke before tagging 0.4.0-RC1 |
| Schema-validation parity loss | Optional validator middleware in M4 |
| Partial migration leaks | Each milestone produces a compilable, test-passing tree |

## Decision log

| Date | Decision | Notes |
|---|---|---|
| 2026-05-27 | Two-track response to #56 | Quick win (PR #58) + this rewrite. |
| 2026-05-27 | Worktree + branch `tjc-1131-native-mcp-core` | Branched from `main` (98d1097). |
| 2026-05-27 | TS SDK stays as test-only dep (tentative) | Confirm at M1. |
| 2026-05-28 | Pacing: **chaotic, re-green at M8 only** | Strip SDKs first; accept a red tree M1→M7; single PR at M8. |
| 2026-05-28 | Version target **0.5.0** | v0.4.0 already shipped on `main`. Kill `FastMcpServerSettings` alias (honors the deprecation promise originally aimed at 0.4.0). |
| 2026-05-28 | **No `FastMcpServer`/`JsMcpServer` split** | ONE concrete `McpServer` in `shared/`; platforms contribute only transport adapters via given-based dispatch. Delete `McpServerCore` trait + factory in M7. |
| 2026-05-28 | TS SDK confirmed test-only | Dropped from production `bunDeps`; returns in `js.test`. |
| 2026-05-28 | `~/git/typescript-sdk` is the canonical reference | pnpm monorepo: `packages/core/src/types/spec.types.ts` (3250 lines) is the wire-type source of truth; `packages/server` for dispatch. |

## Working notes

### M1 — Burn (done, commit 56bcb49)
Deleted 21 files / ~2240 lines. All Java + TS SDK refs gone from production except `FastMcpServer.scala` / `JsMcpServer.scala` (gutted in M7). Known cascade breakage to clean up later: `ExportsJvm.scala` (re-exports deleted `JacksonConverter` / `JvmToolSchemaProviders` / `McpEncoders`) and `examples/TaskManagerServer.scala` (uses deleted `DeriveJacksonConverter`). `Versions.mcpSdk` + `Versions.jackson3` removed.

### M2 — Schema (done, commits 0c7fead, ef5a944, ad6dfbd)
Pure-Scala wire types for all 2025-11-25 server-handled messages. **Verified: every new `shared/core` file typechecks clean** (`jvm.compile` errors are all M1 cascade in the two files named above). Layout decision: spec wire types live in `core.wire.*` to avoid colliding with the macro annotation classes in `core` (`@Tool`/`@Resource`/`@Prompt` are top-level `core` types named `Tool`/`Resource`/`Prompt`). New files:
- `core/Protocol.scala` — version constants, `ErrorCodes`, `Cursor`, `ProgressToken`
- `core/Logging.scala` — `LoggingLevel` (+severity), `SetLevelRequestParams`, `LoggingMessageNotificationParams`
- `core/wire/Capabilities.scala` — `Implementation`, `Icon`, server/client capability trees
- `core/wire/Resources.scala` — `Annotations`, `Resource`, `ResourceTemplate`, `ResourceContents` ADT
- `core/wire/Tools.scala` — `Tool`, `ToolOutputSchema`
- `core/wire/Prompts.scala` — `Prompt`, `PromptMessage`
- `core/wire/Envelopes.scala` — all request-params + result bodies
- `core/wire/Notifications.scala` — `NotificationMethods` + params
`Content` ADT in `core/Types.scala` refactored to 2025-11-25 shape (added `AudioContent`, `ResourceLink`; `Annotations` + `_meta` on every variant; dropped `EmbeddedResourceContent`).

### M3 — Codecs (done, commit 0a79205)
Trivial `DeriveJsonCodec.gen` givens inlined in M2; M3 did the hard cases and **validated all four against real zio-json 0.7.44 / Scala 3.8.3 via a standalone scala-cli harness — all pass** (can't run the in-tree suite until M8 since the module is red):
- `Content` ADT: `@jsonHint` drives the `type` discriminator (NOT the `extends Content("text")` ctor arg — latent bug, the codec never hit the wire under Jackson). Fixed for all 5 variants + `CompletionReference`.
- `ToolInputSchema`/`ToolOutputSchema`: embedded-object codec (opaque-string ↔ JSON object), so `inputSchema` is an object on the wire, never a string.
- `ResourceContents`: AST-discriminated text-vs-blob (no `type` tag), rejects objects with neither field.
- absent-vs-null `_meta`: confirmed zio-json omits `None` fields (no `null` emitted) by default — no special handling needed.
Regression net: `jvm/test/.../core/wire/WireCodecRoundTripTest.scala`.

### M4 — JSON-RPC core + router + middleware (done, commit pending push)
Native dispatch kernel. Envelope codec validated standalone (round-trips + structural discrimination all pass). **Caught a second latent bug: `Json.Num` wraps `java.math.BigDecimal` (no `.isWhole`/`.toLong`) — fixed in `JsonRpc.scala` RequestId AND `Protocol.scala` ProgressToken.** New files:
- `jsonrpc/JsonRpc.scala` — `RequestId`, `JsonRpcErrorObject`, `JsonRpcMessage` ADT (Request/Notification/Success/Failure), hand-rolled structural codec
- `jsonrpc/McpError.scala` — error ADT (subsumes deleted `ErrorMapper`)
- `server/router/Session.scala` — per-connection state + in-flight fiber registry + outbound queue
- `server/router/Middleware.scala` — `RequestHandler`/`NotificationHandler`, `Middleware` chain, `ServerHooks`
- `server/router/McpRouter.scala` — dispatcher; **capabilities derived from registered handlers (issue #56 fix)**; fork-per-request cancellation
Whole shared/ tree compiled via scala-cli (zio+zio-json+tapir): **all M2–M4 files clean**; only the 4 macro processors error, due to the platform-split macro support files (`MapToFunctionMacro`/`MacroUtils`) absent from a standalone source set — orthogonal, fine in the real mill build.

### M5 — Built-in handlers + validation middleware (in progress)
Decode-path note: the typed-contract decoder was Jackson-backed on JVM (`JacksonConversionContext`, deleted M1) and `js.JSON`-backed on JS (`JsMcpDecodeContext`). Per the convergence directive, M5 introduces ONE shared zio-json-based `McpDecodeContext` impl in `shared/codec/`, used by both platforms. tools/call bridges `arguments: Json` → `Map[String,Any]` → manager → `Any` → `CallToolResult` (porting old `transformToolResult`).

Update this section at each milestone boundary.
