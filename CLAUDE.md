# CLAUDE.md - FastMCP-Scala Development Guide

## Project Overview

FastMCP-Scala is a high-level Scala 3 library for building Model Context Protocol (MCP) servers. It provides two registration paths:

1. **Annotation-driven** (`@Tool`, `@Resource`, `@Prompt` + `scanAnnotations`) — zero-boilerplate, JVM-only
2. **Typed contracts** (`McpTool`, `McpPrompt`, `McpStaticResource`, `McpTemplateResource`) — explicit, cross-platform (JVM + Scala.js)

Both paths converge on the same `McpServer` trait and support `@Param` metadata on parameters/fields.

## Build System

**Build tool**: Mill 1.1.5 (configured in `.mill-version`)
**Scala**: 3.8.3
**Plugins**: mill-bun-plugin 0.2.0 (Scala.js + Bun integration)

### Common Commands

```bash
# Aggregates (run across JVM + Scala.js)
./mill fast-mcp-scala.compile                       # Compile all platforms
./mill fast-mcp-scala.test                          # All tests (JVM + Bun conformance)
./mill fast-mcp-scala.reformat                      # Auto-format every Scala source
./mill fast-mcp-scala.checkFormat                   # Scalafmt check (CI uses this)

# Single-platform
./mill fast-mcp-scala.jvm.test                      # JVM tests only
./mill fast-mcp-scala.js.test.bunTest               # Scala.js conformance tests only
./mill fast-mcp-scala.jvm.test com.tjclp.fastmcp.macros.ToolProcessorTest

# Publish
./mill fast-mcp-scala.jvm.publishLocal              # Publish JVM artifact to ~/.ivy2/local
```

## Project Structure

```
fast-mcp-scala/
├── build.mill                 # Mill build definition
├── .mill-version              # Mill version (1.1.5)
├── fast-mcp-scala/
│   ├── shared/src/            # Platform-independent code (JVM + JS)
│   │   └── com/tjclp/fastmcp/
│   │       ├── core/
│   │       │   ├── Annotations.scala    # @Tool, @Param, @Resource, @Prompt
│   │       │   ├── Types.scala          # ToolDefinition, Content, ToolInputSchema, etc.
│   │       │   └── Contracts.scala      # McpTool, McpPrompt, McpDecoder, McpEncoder
│   │       ├── runtime/                 # RefResolver
│   │       └── server/
│   │           ├── McpServer.scala      # McpServerPlatform trait (abstract API)
│   │           ├── McpContext.scala     # Platform-independent context base
│   │           ├── FastMcpServerSettings.scala
│   │           └── manager/            # ToolManager, PromptManager, ResourceManager
│   ├── jvm/
│   │   ├── src/               # JVM-specific code
│   │   │   └── com/tjclp/fastmcp/
│   │   │       ├── core/Types.scala         # TypeConversions (toJava extensions, private[fastmcp])
│   │   │       ├── macros/                  # Scala 3 macro implementations (JVM-only)
│   │   │       │   ├── ToolProcessor.scala
│   │   │       │   ├── ResourceProcessor.scala
│   │   │       │   ├── PromptProcessor.scala
│   │   │       │   ├── RegistrationMacro.scala  # scanAnnotations entry point
│   │   │       │   ├── JsonSchemaMacro.scala
│   │   │       │   ├── JacksonConverter.scala   # extends McpDecoder (bridges to shared)
│   │   │       │   └── JacksonConversionContext.scala  # extends McpDecodeContext
│   │   │       ├── server/
│   │   │       │   ├── FastMcpServer.scala      # JVM implementation (extends McpServerPlatform)
│   │   │       │   ├── McpContext.scala         # JvmMcpContext (private[fastmcp])
│   │   │       │   ├── McpServerBuilders.scala  # McpServer companion (factory methods)
│   │   │       │   └── transport/
│   │   │       └── examples/
│   │   └── test/src/          # JVM test sources
│   └── js/                    # Scala.js code (Bun runtime)
│       ├── src/               # MCP TS SDK facades + McpTestClient
│       └── test/src/          # Conformance tests + contract surface tests
```

## Key Concepts

### Annotation Path (JVM-only)

```scala
object MyServer extends ZIOAppDefault:
  @Tool(name = Some("add"), description = Some("Add two numbers"))
  def add(@Param("First number") a: Int, @Param("Second number") b: Int): Int = a + b

  override def run =
    for
      server <- ZIO.succeed(McpServer("MyServer"))
      _ <- ZIO.attempt(server.scanAnnotations[MyServer.type])
      _ <- server.runStdio()
    yield ()
```

### Typed Contract Path (cross-platform)

```scala
case class AddArgs(@Param("First number") a: Int, @Param("Second number") b: Int)

val addTool = McpTool.derived[AddArgs, Int](
  name = "add",
  description = Some("Add two numbers")
) { args => ZIO.succeed(args.a + args.b) }

// Mount:
server.tool(addTool)
```

### When to Use Which

| | Annotations | Typed Contracts |
|---|---|---|
| Platform | JVM only | JVM + Scala.js |
| Boilerplate | Zero (macro-driven) | Minimal (case class + builder) |
| Schema | Auto from method signature | Auto from case class via `ToolSchemaProvider` |
| `@Param` | On method parameters | On case class fields |
| Composability | Methods on an object | First-class values |
| Best for | Quick servers, prototyping | Libraries, cross-platform, production |

### Annotations

- `@Tool` - Marks a method as an MCP tool. Supports behavioral hints:
  - `title`, `readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint`, `returnDirect`
- `@Resource` - Marks a method as an MCP resource (static or templated)
- `@Prompt` - Marks a method as an MCP prompt
- `@Param` - Describes parameters/fields with metadata:
  - `description: String` - Parameter description
  - `example: Option[String]` - Example value
  - `required: Boolean` - Override required status
  - `schema: Option[String]` - Custom JSON Schema override

### Typed Contracts

- `McpTool[In, Out]` - Typed tool with `McpTool.derived` for auto-schema derivation
- `McpPrompt[In]` - Typed prompt with manual argument metadata
- `McpStaticResource` - Typed static resource
- `McpTemplateResource[In]` - Typed resource template
- `McpDecoder[T]` / `McpEncoder[A]` - Platform-neutral codecs
- `ToolSchemaProvider[A]` - Auto-derives `inputSchema` from `@Param`-annotated case classes
- `McpEncoder` falls back to `JsonEncoder[A]` → `TextContent(a.toJson)` via ZIO JSON

### Transports

- **Stdio** (`runStdio()`) — stdin/stdout, used by MCP clients
- **HTTP** (`runHttp()`) — streamable (sessions + SSE) by default, set `stateless = true` for stateless

### Cross-Platform Architecture

The codebase is split into three sibling trees under `fast-mcp-scala/`:
- `shared/` — annotations, types, managers, `McpServerPlatform` trait, typed contracts
- `jvm/` — Java SDK interop (`TypeConversions`, `JvmMcpContext`), macros, transports, examples
- `js/` — Scala.js facades for `@modelcontextprotocol/sdk`, conformance tests

JVM module reads from `shared/src/ + jvm/src/`. JS module reads from `shared/src/ + js/src/`.

### Java SDK Interop

FastMCP-Scala wraps the Java MCP SDK 1.1.1 (`mcp-core` + `mcp-json-jackson3`). Interop is internal:
- `TypeConversions` — `private[fastmcp]` extension methods (`.toJava`)
- `JvmMcpContext` — `private[fastmcp]`, extends `McpContext`
- `JacksonConverter extends McpDecoder` — bridges JVM converters to shared codec layer
- `JacksonConversionContext extends McpDecodeContext` — Jackson 3 backed

## Code Quality

### WartRemover

Configured in `build.mill` (v3.5.6):
- **Errors** (fail build): `Null`, `TryPartial`, `TripleQuestionMark`, `ArrayEquals`
- **Warnings**: `Var`, `Return`, `AsInstanceOf`, `IsInstanceOf`

### Formatting

Uses Scalafmt with config in `.scalafmt.conf`. Always run `./mill fast-mcp-scala.reformat` before committing.

## Testing

JVM tests in `fast-mcp-scala/jvm/test/src/`. Scala.js tests in `fast-mcp-scala/js/test/src/`.

Key test classes:
- `ToolProcessorTest` - Integration tests for @Tool processing
- `JsonSchemaMacroTest` - Schema generation tests
- `TypedContractsTest` - Typed contract mounting tests
- `ZioHttpStatelessTransportTest` - HTTP transport integration tests
- `ZioHttpStreamableTransportProviderTest` - SSE transport tests
- `ConformanceTest` (JS) - 17 cross-platform conformance tests against AnnotatedServer

## CI/CD

- **CI** (`.github/workflows/ci.yml`): Runs on PRs and main pushes, tests on JDK 17, 21, 24
- **Release** (`.github/workflows/release.yml`): Triggered by `v*` tags, publishes to Maven Central

## Common Tasks

### Adding a New Feature

1. Platform-independent code goes in `shared/src/`
2. JVM-specific code stays in `jvm/src/`
3. Add tests in `jvm/test/src/` or `js/test/src/`
4. Run `./mill fast-mcp-scala.test` (runs both JVM and JS aggregates)
5. Run `./mill fast-mcp-scala.checkFormat` (or `reformat`)

### Modifying Macros

Macros are in `fast-mcp-scala/jvm/src/com/tjclp/fastmcp/macros/`. After changes:
```bash
rm -rf out/fast-mcp-scala && ./mill fast-mcp-scala.compile
```

### Testing Locally

```bash
./mill fast-mcp-scala.jvm.publishLocal
```

Then in your project use version `0.3.0-SNAPSHOT`.

## Dependencies

Key dependencies (versions in `build.mill`):
- Scala 3.8.3
- ZIO 2.1.20 - Effect system
- ZIO JSON 0.7.44 - JSON codecs (shared)
- ZIO HTTP 3.4.0 - HTTP transport
- Jackson 3.0.3 (`tools.jackson`) - Runtime conversion (JVM)
- Tapir 1.11.42 - Schema derivation
- Java MCP SDK 1.1.1 - Protocol implementation (`mcp-core` + `mcp-json-jackson3`)
- mill-bun-plugin 0.2.0 - Scala.js + Bun build integration
- `@modelcontextprotocol/sdk` 1.29.0 - TS MCP SDK (JS conformance tests)
- WartRemover 3.5.6 - Code quality
- ScalaTest 3.2.19 - Testing
