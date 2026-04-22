# Changelog

All notable changes to fast-mcp-scala will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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