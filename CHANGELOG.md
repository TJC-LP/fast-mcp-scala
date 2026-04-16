# Changelog

All notable changes to FastMCP-Scala will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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