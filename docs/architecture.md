# FastMCP-Scala Architecture

A short tour of how the library is put together. For a user-facing overview see the [README](../README.md); this document is for people who want to understand what happens between "I wrote a `@Tool`" and "an MCP client can call it."

## Two registration paths, two backends

The shared abstract `McpServer` trait is the single user-facing API. Users pick a registration path (annotations vs typed contracts) and `McpServer("name", version)` returns the backend appropriate for their platform — `FastMcpServer` (JVM, wraps Java MCP SDK) or `JsMcpServer` (Scala.js, wraps TS MCP SDK). Both backends hand registration off to the same platform-neutral `Tool/Prompt/Resource` managers and then wire manager callbacks to their respective runtime's request handlers.

```
                        ┌───────────────────────────────────────┐
  @Tool / @Resource     │           RegistrationMacro           │
  @Prompt methods  ─────►   (scanAnnotations[T] — JVM + JS)     ├──┐
                        └───────────────────────────────────────┘  │
                                                                   ▼
                        ┌───────────────────────────────────────┐ ┌───────────────┐
  McpTool.derived       │     .tool / .prompt / .resource       │ │  ToolManager  │
  McpPrompt        ─────►  extension methods on McpServer       ├─►  PromptManager │
  McpStaticResource     │     (typed contract path)             │ │  ResourceManager │
  McpTemplateResource   └───────────────────────────────────────┘ └───────┬───────┘
                                                                          │
                              ┌───────────────────────────────────────────┴─┐
                              ▼                                             ▼
                       ┌───────────────┐                            ┌───────────────┐
                       │ FastMcpServer │                            │  JsMcpServer  │
                       │   (JVM)       │                            │  (Scala.js)   │
                       └───────┬───────┘                            └───────┬───────┘
                               │                                            │
                               ▼                                            ▼
                       ┌───────────────┐                            ┌───────────────┐
                       │ Java MCP SDK  │                            │  TS MCP SDK   │
                       │  (mcp-core)   │                            │  (@modelcontext│
                       │               │                            │   protocol/sdk)│
                       └───────┬───────┘                            └───────┬───────┘
                               │                                            │
                               ▼                                            ▼
                 stdio │ Streamable HTTP │ Stateless HTTP    stdio │ Streamable HTTP │ Stateless HTTP
                       (ZIO HTTP transport)                        (Bun.serve on Bun; Node/Deno follow-up)
```

## Module layout

```
fast-mcp-scala/
├── shared/src/com/tjclp/fastmcp/        # platform-independent (JVM + Scala.js)
│   ├── core/
│   │   ├── Annotations.scala             # @Tool, @Resource, @Prompt, @Param
│   │   ├── Contracts.scala               # McpTool, McpPrompt, McpDecoder, McpEncoder
│   │   └── Types.scala                   # ToolDefinition, Content, Message, ...
│   ├── runtime/RefResolver.scala         # method-handle lookup helper for the macro
│   └── server/
│       ├── McpServer.scala               # trait implemented by FastMcpServer
│       ├── McpContext.scala              # base class extended by JvmMcpContext
│       ├── FastMcpServerSettings.scala
│       └── manager/                      # ToolManager, PromptManager, ResourceManager
│
├── src/com/tjclp/fastmcp/               # JVM-specific
│   ├── core/
│   │   ├── JvmToolInputSchemaSupport.scala
│   │   └── TypeConversions.scala         # .toJava extensions (private[fastmcp])
│   ├── macros/
│   │   ├── RegistrationMacro.scala       # scanAnnotations[T] entry point
│   │   ├── ToolProcessor.scala           # emits tool registrations at compile time
│   │   ├── ResourceProcessor.scala
│   │   ├── PromptProcessor.scala
│   │   ├── JsonSchemaMacro.scala         # input schema from method signature
│   │   ├── MapToFunctionMacro.scala      # Map[String, Any] → typed arg bridge
│   │   ├── JacksonConverter.scala        # bridges Jackson to McpDecoder
│   │   └── JacksonConversionContext.scala
│   ├── server/
│   │   ├── FastMcpServer.scala           # the JVM implementation (Java SDK wrapper)
│   │   ├── McpContext.scala              # JvmMcpContext (private[fastmcp])
│   │   ├── McpServerBuilders.scala       # McpServer factory object
│   │   └── transport/                    # stdio + zio-http transports
│   └── examples/                         # runnable example servers
│
└── js/src/com/tjclp/fastmcp/            # Scala.js backend (Bun-first)
    ├── codec/                            # JS McpDecoder / decode-context support
    ├── facades/                          # TS SDK + Bun runtime facades
    ├── macros/                           # JS annotation + schema-derivation macros
    ├── server/                           # JsMcpServer + JsMcpContext
    └── examples/                         # runnable Bun examples
```

**JVM module sources** = `shared/src/` + `src/`. **Scala.js module sources** = `shared/src/` + `js/src/`. Mill's `Task.Sources` wires this in `build.mill`.

## The annotation path at compile time

When you write:

```scala 3 raw
object MyServer:
  @Tool(name = Some("add"))
  def add(@Param("a") a: Int, @Param("b") b: Int): Int = a + b

val server = McpServer("MyServer")
server.scanAnnotations[MyServer.type]
```

`scanAnnotations` is an inline macro defined in `RegistrationMacro.scala`. Expansion happens in roughly four stages:

1. **Discovery** — `MacroUtils` reflects over `T` (the object's type) and collects every method symbol carrying `@Tool`, `@Resource`, or `@Prompt`.

2. **Schema generation** — for `@Tool` methods, `JsonSchemaMacro.schemaForFunctionArgs` walks the parameter list and emits a `ToolInputSchema` value. It uses Tapir's `Schema` derivation under the hood, which supports case classes, Scala 3 enums, `Option`, collections, and a handful of primitives. `@Param` metadata is folded in (descriptions, examples, required flags, custom schema fragments).

3. **Handler generation** — `MapToFunctionMacro` generates a function `(Map[String, Any], Option[McpContext]) => ZIO[Any, Throwable, Any]` that: (a) extracts each parameter from the `Map` by name, (b) decodes it into the expected Scala type using the target platform's decoder path (`JacksonConverter` on the JVM, `McpDecoder`/`zio-json` on JS), (c) optionally passes an `McpContext` if the method signature asks for one, and (d) calls the original method. The method handle is resolved at runtime via `RefResolver` (no runtime reflection in the hot path).

4. **Registration** — for each discovered method, the macro emits a `server.tool(definition, handler, options)` (or `.prompt`, `.resource`) call, inlined at the call site of `scanAnnotations[T]`.

Everything after stage 4 is identical to the typed-contract path.

## The typed-contract path

No runtime reflection. You build `McpTool`/`McpPrompt`/`McpStaticResource`/`McpTemplateResource` values explicitly — or with `McpTool.derived[In, Out]`, which picks up a given `ToolSchemaProvider[In]` (macro-provided on both JVM and Scala.js) and an `McpEncoder[Out]` (auto-derivable from any `zio-json` `JsonEncoder`).

The `server.tool(McpTool)` extension method bridges the shared contract to the same internal `ToolManager.addTool` call the annotation path uses, supplying `JacksonConverter`-derived decoding on the JVM.

## Java SDK interop

`FastMcpServer` wraps `io.modelcontextprotocol.sdk.mcp-core` 1.1.1. The boundary is small and deliberately private:

- **`TypeConversions`** (JVM only, `private[fastmcp]`) — extension methods `.toJava` that convert Scala `ToolDefinition`, `Content`, `Message`, etc. into their Java SDK equivalents.
- **`JvmMcpContext`** (JVM only, `private[fastmcp]`) — extends the shared `McpContext` base with `javaExchange`, `getClientInfo`, `getClientCapabilities`.
- **`JacksonConverter` and `JacksonConversionContext`** (JVM only) — bridge the shared `McpDecoder` / `McpDecodeContext` interfaces to a Jackson 3 mapper. Supplied by default for every `@Param`-annotated type.

Users only see the public extension methods on `McpServer` (`.tool`, `.prompt`, `.resource`, `.scanAnnotations`, `.runStdio`, `.runHttp`). The Java types stay inside the library.

## Transports

Three runtime transports, all backed by the Java SDK:

| Transport | Entry point | Layer |
|---|---|---|
| Stdio | `server.runStdio()` | `StdioServerTransportProvider` from Java SDK |
| Streamable HTTP (default) | `server.runHttp()` | `ZioHttpStreamableTransportProvider` → `McpStreamableServerTransportProvider` |
| Stateless HTTP | `server.runHttp()` with `stateless = true` | `ZioHttpStatelessTransport` → `McpStatelessServerTransport` |

HTTP transports are implemented on **ZIO HTTP 3.4.0**, not on Java HTTP primitives. This keeps the ZIO side of the server in control of the event loop, lifetimes, and cancellation; the Java SDK sees a `McpServerTransportProvider` that speaks its own protocol.

## Scala.js surface

The Scala.js module compiles the `shared/src/` tree plus `js/src/`. `shared/src/` still avoids Java-only APIs — `JvmToolInputSchemaSupport`, `JacksonConverter`, `FastMcpServer`, and `TypeConversions` remain outside `shared/` so the same public contracts compile on both backends.

The `js/src/` tree now contains a real Scala.js MCP *server* runtime:

- **`JsMcpServer`** — Scala.js implementation of the shared `McpServerPlatform`, wrapping the TS SDK's low-level `Server`.
- **`JsMcpContext`** — JS-side context extensions for client info, capabilities, and session id.
- **TS SDK facades** — server, transports, schemas, AJV validation, Bun runtime integration.
- **Examples + tests** — standalone Bun stdio / HTTP examples plus pure-JS conformance coverage.

Design choice: the JS backend keeps the shared Scala `ToolManager` / `PromptManager` / `ResourceManager` as the canonical registry, then binds MCP method handlers onto the TS SDK server. This preserves the existing raw JSON-schema-first Scala API while still using the official TS SDK for transport, framing, and protocol negotiation.

Runtime support today is **Bun-first**:

- `runStdio()` uses the TS SDK `StdioServerTransport`
- `runHttp()` uses Bun's `Bun.serve(...)` plus the TS SDK `WebStandardStreamableHTTPServerTransport`
- `stateless = true` creates a fresh server+transport per POST in JSON-response mode
- `stateless = false` creates one server+transport per MCP session, keyed by `mcp-session-id`

Node/Deno HTTP listeners are a follow-up; the underlying TS SDK web-standard transport already supports those runtimes, but this repo currently ships only the Bun listener.

## Error handling

Tool handlers return `ZIO[Any, Throwable, Out]`. Failures are surfaced to the client via the MCP "isError" result path (not as transport-layer exceptions) — `ToolManager.callTool` catches the `ZIO.Fail` and packages it into a `CallToolResult(isError = true, ...)`. Resource and prompt handlers follow the same pattern through their respective managers.

## Dependency summary

Pinned in `build.mill`:

- **Scala** 3.8.3
- **ZIO** 2.1.20, **zio-json** 0.7.44, **zio-schema** 1.7.4, **zio-http** 3.4.0
- **Tapir** 1.11.42 (schema derivation) + apispec 0.11.10 (JSON Schema emitter)
- **Jackson 3** (`tools.jackson.core:jackson-databind` 3.0.3)
- **Java MCP SDK** 1.1.1 (`mcp-core` + `mcp-json-jackson3`)
- **mill-bun-plugin** 0.2.0 (Scala.js + Bun integration)
- **WartRemover** 3.5.6 (linting)

## Further reading

- [`../README.md`](../README.md) — user-facing feature tour
- [`../CHANGELOG.md`](../CHANGELOG.md) — release notes
- [`./jackson-converter-enhancements.md`](./jackson-converter-enhancements.md) — details on writing custom `JacksonConverter`s
- [`../CLAUDE.md`](../CLAUDE.md) — contributor quick reference for the Mill build
