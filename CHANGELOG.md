# Changelog

All notable changes to fast-mcp-scala will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.5.0] - 2026-07-29

Highlights: **the entire MCP protocol layer is now native pure-Scala 3** — both
vendored SDKs are gone from the production classpath. The JSON-RPC core, wire
types, router, built-in handlers, middleware, and the Tasks state machine all
live in `shared/`; the JVM and Scala.js modules contribute only a single
`given TransportBackend`. The headline user-facing API (`@Tool`,
`McpTool[In, Out]`, `McpServer.typed[R]`) is unchanged; see **Removed** for the
internals that went with the SDKs. Verified against the official MCP
conformance suite: **42/42 on both platforms**, zero expected failures.

### Added

- **Server→client requests with response correlation** on session-bearing
  transports (stdio + streamable HTTP): `McpContext.listRoots()`,
  `createMessage(...)` (sampling — including the 2025-11-25 `tools` /
  `toolChoice` fields), `elicit(...)` (form mode), and `elicitUrl(...)`
  (URL mode, with `ElicitRequestUrlParams.requiredError` producing the spec's
  `-32042` error carrying `data.elicitations`).
- **Structured tool output**: `McpTool#withOutputSchema` derives an
  `outputSchema` from `Out` (`ToolOutputSchemaProvider`, both platforms) and
  every call then emits conforming `structuredContent` alongside the text
  fallback. (Annotation-path derivation from method return types is a tracked
  follow-up.)
- **Progress + logging APIs on `McpContext`**: `progressToken`,
  `sendProgress(...)`, `sendLogMessage(...)` (honoring `logging/setLevel`).
- **`completion/complete`** via `server.completion(handler)`, and opt-in
  **`resources/subscribe` / `unsubscribe`** (`resourcesSubscribe = true`) and
  **`logging/setLevel`** (`loggingEnabled = true`) — each advertised only when
  wired.
- **Transport hardening**: DNS-rebinding protection (`allowedHosts` +
  `HostGuard`, 403), `Accept` / `mcp-protocol-version` validation (absent
  version header ⇒ `2025-03-26` assumed), idle-session eviction
  (`sessionIdleTimeout`, default 30 minutes, live-GET-exempt), SSE keepalives
  (`keepAliveInterval` — previously accepted but never implemented, in any
  release), one GET SSE stream per session (409 on the second), and JSON-RPC
  error bodies (`-32000`/`-32001`) on transport-level rejections.
- **Per-request POST SSE on both platforms**: each streamable request's
  notifications and server→client sub-requests stream on its own response,
  ordered before the final reply.
- Session and task ids from the **platform CSPRNG** (`java.util.UUID` /
  Web Crypto).
- Wire completeness: `Icon.sizes`/`theme`, `PromptArgument.title`,
  `Tool.outputSchema` on `tools/list`.
- **Official conformance harness in CI**: `scripts/conformance.sh` +
  `.github/workflows/conformance.yml`, both platforms, empty
  expected-failure baselines; the TS SDK conformance client and bun are now
  pinned (1.29.0 / 1.3.14).

### Changed

- **BREAKING — native MCP core; both SDKs removed from production.** The Java MCP
  SDK (`mcp-core` + `mcp-json-jackson3`) and Jackson are removed from the JVM
  classpath, and the TypeScript `@modelcontextprotocol/sdk` is removed from the
  JS production bundle (it stays as a test-time conformance client). Wire
  (de)serialization is ZIO JSON on both platforms.
- **One `McpServer[R]` for both platforms.** The per-platform `FastMcpServer`
  (JVM) and `JsMcpServer` (JS) are replaced by a single `shared/` `McpServer`;
  each platform supplies only a `TransportBackend` given.
- **Capabilities are derived from the registered handler map.** A capability is
  advertised only when its handler is wired — so `logging` is no longer
  advertised unless a logging handler exists (the root cause of #56).
- **Tool handler failures now surface as `CallToolResult { isError: true }`**
  (with the message as text content) instead of a JSON-RPC protocol error, per
  the MCP spec. An unknown tool name still returns a protocol error.
- **Spec target is 2025-11-25.** Pre-2025-06-18 wire support is dropped.
- **BREAKING — HTTP servers bind `127.0.0.1` by default** (was `0.0.0.0`), per
  the spec's bind-localhost guidance against DNS-rebinding exposure.
  **Migration:** containerized or externally exposed deployments must set
  `McpServerSettings(host = "0.0.0.0")` explicitly.
- **Streamable POSTs must `Accept` both `application/json` and
  `text/event-stream`** (replies stream as SSE; TS SDK parity — absent `Accept`
  is still treated as accept-anything). Stateless HTTP keeps the json-only
  requirement. Only `initialize` may open a session: header-less non-initialize
  POSTs get `400`, malformed bodies `400` with a `-32700` JSON-RPC body (both
  used to mint a session and answer `200`).
- **Requests before `initialize` answer `-32600`** (`ping` exempt; the gate
  opens once `initialize` is answered; re-initialize renegotiates leniently).
  Stateless sessions are born ready.
- **Tasks transport policy**: tasks are router middleware and now work over
  **stdio** as well as streamable HTTP (the 0.4.0 stdio ban was a Java-SDK
  limitation); `tasks.enabled` with `stateless = true` **fails fast at
  startup** — stateless previously appeared to work but every client shared one
  task namespace. A task that outlives its TTL is now interrupted, terminal
  results stay pollable until the TTL sweeps them, and `tasks/list` rejects
  cursors it never issued (`-32602`).
- Transports, native: stdio + stateless HTTP + streamable HTTP (durable sessions,
  SSE, DELETE) on the JVM via pure ZIO HTTP (no `Unsafe`/Mono bridge); `Bun.serve`
  + Node stdio on Scala.js. The streamable `GET` SSE *push* channel is JVM-only
  for now; the JS transport returns `405` for `GET` (spec-allowed — per-request
  POST SSE carries server→client traffic on both platforms).

### Removed

- The deprecated `FastMcpServerSettings` alias (use `McpServerSettings`).
- **`FastMcpServer` and `JsMcpServer`** (never deprecated — they went with the
  SDKs they wrapped). **Migration:** construct via `McpServer("name")` /
  `McpServer.typed[R]("name")`; bare type references become `McpServer[Any]`.
- **`JacksonConverter`, `DeriveJacksonConverter`, `JacksonConversionContext`**
  (the Jackson decode path is gone). **Migration:** supply a
  `given JsonDecoder[T]` — the shared zio-json derivation
  (`codec/McpDecoders.scala`) turns it into the `McpDecoder[T]` contracts use.
  Note `java.time` fields no longer decode for free; give them a `JsonDecoder`.
- **`JsMcpContext`** — the shared `McpContext` now carries client info,
  capabilities, progress, logging, and server→client requests on both platforms.
- **Content ADT reshape** (spec alignment): `audience`/`priority` moved off the
  content constructors into `annotations` (`ContentAnnotations`), and
  `EmbeddedResourceContent` is replaced by `ResourceContents`
  (`TextResourceContents` / `BlobResourceContents`).
- Stale GraalVM native-image reachability metadata (it described the removed
  Java SDK + Jackson reflection). Regenerate with the `native-image-agent`
  against a native-core server if you build native images.

### Fixed

- **#56** — the server no longer advertises a `logging` capability when no
  logging handler is registered, on every transport (stdio, stateless HTTP,
  streamable HTTP). Verified end-to-end on both platforms.
- **Spec error codes at the dispatch boundary**: unknown `resources/read` URIs
  now answer `-32002` with `data.uri` (was `-32603`); unknown prompts, unknown
  task ids on `tasks/result`, and the task concurrency cap answer `-32602`
  (were `-32603` / `-32002` / `-32603`; 0.4.0 parity restored for the task
  codes).
- **`notifications/cancelled` now closes the request's SSE stream** instead of
  holding the HTTP connection open forever; client disconnects mid-request
  interrupt the handler on JS too (ReadableStream `cancel` hook). The
  `initialize` request cannot be cancelled (spec).
- **Task TTL expiry interrupts still-running work** instead of orphaning an
  invisible daemon fiber whose task polls as "Unknown task".
- **JSON-RPC envelope hardening**: a request with explicit `id: null` answers
  `-32600` (it used to decode as a droppable notification); missing/wrong
  `jsonrpc` version, fractional ids, and non-string methods answer `-32600`
  with the id echoed where recoverable; `tools/call` with a non-object
  `arguments` answers `-32602` (used to become an empty argument map).
- **stdio frames dispatch concurrently**, so a handler awaiting a
  server→client response (roots/sampling/elicitation) no longer deadlocks the
  read loop; both stdio loops interrupt their outbound drainer at EOF.
- `Task.ttl` encodes as an explicit `null` when absent (2025-11-25 requires
  present-and-nullable; strict TS-SDK clients rejected the omitted key).
- The `Content` wire discriminator uses `@jsonHint` values (`"text"`,
  `"image"`, ...) — a latent bug in the pre-native codec that never reached the
  wire while Jackson owned serialization.
- `tasks/list` with no `params` now decodes (optional cursor) instead of failing
  with `-32602`.

## [0.4.0] - 2026-05-27

Highlights: a type-safety overhaul that threads the ZIO environment `R`
through the entire server stack, plus a fix for issue #56.

### Added

- **End-to-end ZIO environment threading for handlers (`R`-aware servers).**
  `@Tool` / `@Resource` / `@Prompt` methods — and typed-contract handlers —
  can now return `ZIO[R, E, A]` with `R ≠ Any`. Construct the server with
  `McpServer.typed[R]("name")` (or `FastMcpServer.typed[R]`) and provide the
  layer once at the boundary: `server.runHttp().provide(Client.default)`.
  No more wrapping every method body in its own `.provide(...)`. Closes #55.
- **Compile-time `R`-mismatch errors from the annotation macros.** If a
  `@Tool` / `@Resource` / `@Prompt` method requires an `R` the server can't
  satisfy, the macro now emits a targeted compile-time error pointing at the
  offending handler, instead of failing at runtime when a missing layer is
  encountered. Replaces the previous blanket `errorAndAbort` on any
  `ZIO[R, ...]` return type.
- **Typed contract factories accept an explicit environment type.**
  `McpTool[In, Out, R]`, `McpPrompt[In, R]`, `McpTemplateResource[In, R]`,
  and `McpStaticResource.withEnv[R]` cover the typed paths. Existing
  no-environment arities (`McpTool[In, Out]`, `McpPrompt[In]`,
  `McpStaticResource`, `McpTemplateResource[In]`) remain source-compatible.

### Changed

- **`McpServerCore`, `FastMcpServer`, `JsMcpServer`, the managers, and the
  typed-contract handlers are now parameterized on `R`.** The default
  `McpServer("name")` factory still returns the `R = Any` form for
  back-compat; explicit type application (`FastMcpServer[Client]("name")`)
  or `McpServer.typed[R]("name")` gives a typed server.
  **Migration:** code that referenced `FastMcpServer` or `JsMcpServer` as
  bare types now needs `FastMcpServer[Any]` / `JsMcpServer[Any]`.
- **Handler dispatch captures `ZIO.runtime[R]` once at server entry and
  runs every handler effect on that runtime.** This is how the user's
  `.provide(layer)` at the server boundary reaches every handler
  invocation, including forked daemon fibers used by `TaskDispatcher` (JVM)
  and `TaskManager.create` (JS) — they inherit the surrounding runtime via
  `forkDaemon`.

### Fixed

- **Stop advertising the `logging` capability on the stateless HTTP transport.** The Java MCP
  SDK 1.1.1 stateless server never registers a `logging/setLevel` handler, so spec-compliant
  clients (e.g. MCP Inspector) would call the advertised method and receive HTTP 400 with
  `Missing handler for request type: logging/setLevel`. `FastMcpServer` now passes `null` for
  the `logging` argument when building `ServerCapabilities`, matching the JS path which never
  advertised it. The stateful HTTP and stdio paths still cosmetically advertise `logging:{}`
  because the underlying Java SDK forces `.mutate().logging().build()` at
  `McpAsyncServer.java:136,164`; there the SDK auto-registers a no-op handler so clients do
  not see a 400. Closes #56.

## [0.3.2] - 2026-04-28

### Added

- **Experimental MCP Tasks API exported at the top level.** The 2025-11-25 Tasks surface is now available from the root package exports.

### Fixed

- **Effectful annotation handlers now execute their returned effects.** `@Tool`, `@Resource`, and `@Prompt` macro-generated handlers now run returned `ZIO`, `Try`, and `Either` values instead of treating them as plain return payloads.

## [0.3.1] - 2026-04-22

### Added

- **`BunPublishModule` mixin on the Scala.js artifact.** `fast-mcp-scala_sjs1_3` now publishes with `META-INF/bun/bun-dependencies.json` embedded in the JAR, listing the runtime JS dependencies (`@modelcontextprotocol/sdk@1.29.0`, `zod@4.3.6`, `zod-to-json-schema@3.25.1`). Downstream Scala.js consumers that use `BunScalaJSModule` (from `mill-bun-plugin` 0.2.0+) pick these up automatically via `classpathBunManifests` — no need to redeclare the SDK in their own `bunDeps`. Manifest-only by default (cross-platform safe); set `bunPublishVendoredRuntime = true` to also embed a resolved `node_modules/` tree.

## [0.3.0] - 2026-04-22

### Added

- **GraalVM native-image reachability metadata** shipped with the JVM artifact at `META-INF/native-image/com.tjclp/fast-mcp-scala_3/reachability-metadata.json`. Covers Jackson record introspection over `io.modelcontextprotocol.spec.McpSchema$*`, zio-json's derivation, reactor-core, izumi-reflect, and `JacksonConversionContext`. Downstream apps can now build a working `native-image` of a fast-mcp-scala stdio server with zero hand-written config — just add `mill.javalib.NativeImageModule` and `jvmId = "graalvm-community:25.0.1"`. Covers initialize / notifications/initialized / tools/list / tools/call / ping / resources/list / prompts/list — the full protocol surface the Claude Agent SDK exercises during handshake.

See the `[0.3.0-rc4]` section below for the cumulative rc1..rc4 changes rolled into this release (shared typed contracts, `McpServerApp` sugar, Jackson 3 migration, cross-platform Scala.js split, unified HTTP transport, MCP Tool Annotations, `$schema` key fix).

## [0.3.0-rc4] - Strip `$schema` root key from tool inputSchema

### Fixed

- **`$schema` root key in generated tool `inputSchema` breaks Anthropic clients.** Tapir's `TapirSchemaToJsonSchema` emits a `"$schema": "http://json-schema.org/draft/2020-12/schema#"` annotation on every generated schema. Anthropic validates tool `input_schema` keys against `^[a-zA-Z0-9_.-]{1,64}$`, so the leading `$` triggers `invalid_request_error` in Claude Code and an opaque `model_request_failed_error` in Claude managed agents — killing the session before any tool call runs. `MacroUtils.resolveJsonRefs` now strips the root `$schema` key alongside `$defs`. See issue #44.
- **`McpServerApp[T, Self]` annotation scan.** The rc2 tag shipped with a silent bug: the inline `scanAnnotationsQuiet[Self]` call expanded with `Self` still abstract at the trait compile site, so every `@Tool` / `@Prompt` / `@Resource` method on a subclass was invisible to MCP clients. rc3 introduces a `SelfScan[Self]` typeclass whose `inline given` expands at the subclass's instantiation site — where `Self` is concrete — so the macro sees the real singleton and registrations fire. No subclass code changes required.

### Deprecated

- **`0.3.0-rc2` is broken.** The artifact exists on Maven Central but the `McpServerApp` sugar trait does not register annotated methods. Do not use it — skip straight to rc4.
- **`0.3.0-rc3` is broken on Anthropic clients.** Every tool registered via `@Tool` or `McpTool` ships a `$schema` root key in its `inputSchema` that Anthropic's tool_use validator rejects — Claude Code surfaces `invalid_request_error`, and Claude managed agents surface an opaque `model_request_failed_error` that kills the session before any tool call runs. Do not use rc3 with Anthropic clients — skip to rc4.

### Added
- **`McpServerApp[T, Self]` sugar trait** — declarative entry point for building MCP servers. Extend on a top-level object; transport is a phantom type parameter (`Stdio` / `Http`); no `override def run`, no `import zio.*`, no ZIO ceremony in user code. Eight-line Hello World.
- **`Transport` marker types + `TransportRunner[T]` typeclass** — compile-time dispatch from transport parameter to `runStdio()` / `runHttp()`. Future transports slot in as a new case object + new given.
- **`ToHandlerEffect[F[_]]` typeclass** — lifts plain values, `ZIO[Any, E, _]` (any `E <: Throwable`), `Either[Throwable, _]`, or `scala.util.Try` into the internal ZIO handler shape. Bring your own given for other effect systems (`cats.effect.IO`, Monix, ...).
- **`AsResourceBody[A]` typeclass** — witnesses `String` / `Array[Byte]` for resource handler returns so pure-value `McpStaticResource(uri)("hello")` works without union-type annotations.
- **`McpServerCoreFactory` typeclass** — platform-neutral factory with given instances on JVM (builds `FastMcpServer`) and JS (`JsMcpServer`). Lets shared code construct the right concrete server without linking against platform types.
- **`scanAnnotationsQuiet[T]` macro overload** — variant of `scanAnnotations` that suppresses the "no annotations found" warning. Used internally by `McpServerApp` so contract-only servers don't flag it.

### Changed (breaking)
- **`McpServerPlatform` → `McpServerCore`.** The `type McpServer = McpServerPlatform` alias is retired. Users who annotated variables as `McpServer` (the type) change to `McpServerCore`. The platform-specific `object McpServer` factory (`McpServer("name", "0.1.0")`) is unchanged.
- **`FastMcpServerSettings` → `McpServerSettings`.** A deprecated type alias + val alias stays for one release cycle to ease the rename.
- **Typed contract factories collapsed.** `McpTool.derived`, `derivedContextual`, `withDefinition`, `contextualWithDefinition` removed from the public API. Replaced by a single `McpTool[In, Out](name, ...) { handler }` primary factory (Builder pattern — schema auto-derived from `ToolSchemaProvider[In]`) and `McpTool.withSchema` for hand-written JSON schemas. Same shape for `McpPrompt`, `McpStaticResource`, `McpTemplateResource`.
- **`McpTool` / `McpPrompt` / `McpTemplateResource` case classes** now carry their decoder/encoder as `private[fastmcp]` fields, stored at construction time. `McpServerCore.tool(contract)` / `.prompt(contract)` / `.resource(contract)` no longer require `using McpDecoder / McpEncoder` at the call site — they read from the contract.
- **Six macro files consolidated into `shared/src/.../macros/`.** `AnnotationProcessorBase`, `MapToFunctionMacro`, `PromptProcessor`, `RegistrationMacro`, `ResourceProcessor`, `ToolProcessor` now live once, target `Expr[McpServerCore]`, and compile for both platforms. `MapToFunctionMacro` stays per-platform (Jackson vs zio-json divergence). Deletes ~620 duplicate lines net.

### Migration

```scala
// Before (0.3.0-rc1)
object HelloWorld extends ZIOAppDefault:
  @Tool(name = Some("add"))
  def add(a: Int, b: Int): Int = a + b

  override def run: ZIO[Any, Throwable, Unit] =
    for
      server <- ZIO.succeed(FastMcpServer("HelloWorld", "0.1.0"))
      _      <- ZIO.attempt(server.scanAnnotations[HelloWorld.type])
      _      <- server.runStdio()
    yield ()

// After (0.3.0-rc4)
object HelloWorld extends McpServerApp[Stdio, HelloWorld.type]:
  @Tool(name = Some("add"))
  def add(a: Int, b: Int): Int = a + b
```

```scala
// Before
val addTool = McpTool.derived[AddArgs, AddResult](name = "add") { args =>
  ZIO.succeed(AddResult(args.a + args.b))
}

// After — pure value
val addTool = McpTool[AddArgs, AddResult](name = "add") { args =>
  AddResult(args.a + args.b)
}
// — or keep using ZIO; `given zio` lifts it transparently
val addTool = McpTool[AddArgs, AddResult](name = "add") { args =>
  ZIO.succeed(AddResult(args.a + args.b))
}
```

```scala
// Before
val settings = FastMcpServerSettings(port = 8090)
val server: McpServer = concrete

// After
val settings = McpServerSettings(port = 8090)
val server: McpServerCore = concrete
```

## [0.2.3] - Bug Fix Release (2026-02-16)

### Fixed
- Exit cleanly on stdin EOF instead of hanging forever (#25)

### Changed
- Updated Scala from 3.7.4 to 3.8.1
- Updated MCP SDK from 0.17.0 to 0.17.2
- Updated WartRemover from 3.4.1 to 3.5.5

## [0.2.2] - Annotation Enhancements (2026-01-12)

### Added
- Made `@Param` `examples` field functional with `List[String]` (#23)
- Made `@Param` annotation `example`, `required`, and `schema` fields functional (#22)

## [0.1.1] - `RefResolver` Patch (2025-05-08)

### Fixed
- Fixed `RefResolver` to handle functions with more than 3 arguments
- Added explicit support for functions with 4-22 arguments
- Added clear error message when attempting to use more than 22 arguments (Scala's built-in limit)

## [0.1.0] - Initial Release (2025-04-25)

### Added
- Initial public release of FastMCP
- Support for Scala-native MCP function tools
- JSON Schema generation for function parameters
- Runtime function resolution
