# Changelog

All notable changes to fast-mcp-scala will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased] - 0.3.0-rc2: McpServerApp sugar + unified macros

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

// After (0.3.0-rc2)
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

## [0.3.0] - Cross-Platform Contracts & Jackson 3 (2026-04-16)

### Added
- Shared typed contract layer — `McpTool`, `McpPrompt`, `McpStaticResource`, `McpTemplateResource` with platform-neutral `McpDecoder` / `McpEncoder` codecs (#32)
- Cross-platform Scala.js support via `shared/src/` + `src/` (JVM) + `js/src/` layout; typed contracts compile to Scala.js, and a JS conformance test harness exercises the JVM server over stdio (#30)
- MCP Tool Annotations support — `title`, `readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint`, `returnDirect` on `@Tool` (#29)
- Unified HTTP transport: `runHttp()` with Streamable HTTP (sessions + SSE) as default and `stateless = true` opt-in (#26, #27, #28)

### Changed (breaking)
- **Jackson 3 migration** (#31): dropped `jackson-databind 2.x` and `jackson-module-scala` / `jackson-datatype-jdk8` / `jackson-datatype-jsr310` for `tools.jackson 3.0.3` (native Scala and `java.time` support). Custom `JacksonConverter` implementations now receive a `JacksonConversionContext` instead of raw `JsonMapper` + `ClassTagExtensions`. Java MCP SDK binding switched from `mcp-json-jackson2` to `mcp-json-jackson3`.
- **Removed deprecated annotations**: `@ToolParam`, `@ResourceParam`, `@PromptParam` — use `@Param` (deprecated in 0.2.1; scheduled for removal in 0.3.0).
- Module layout: `fast-mcp-scala/shared/src/` and `fast-mcp-scala/js/src/` are new source roots; consumers wiring the project as a git submodule or with custom build logic may need to update paths.
- Scala 3.8.1 → 3.8.3, WartRemover 3.5.5 → 3.5.6 (#33).
- Java MCP SDK `0.17.2` → `1.1.1` (`mcp-core` + `mcp-json-jackson3`).

### Migration
Two places most users will feel the upgrade:

1. **`@Param` only**: replace any remaining `@ToolParam` / `@ResourceParam` / `@PromptParam` with `@Param` (deprecated since 0.2.1; removed in 0.3.0).
2. **Custom Jackson converters**: if you defined a `given JacksonConverter[T]` that accessed `JsonMapper` directly, switch to the `JacksonConversionContext` argument — see `docs/jackson-converter-enhancements.md`.

The annotation-driven path (`@Tool` + `scanAnnotations`) and the default decoders require no changes.

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