# CLAUDE.md - FastMCP-Scala Development Guide

## Project Overview

FastMCP-Scala is a high-level Scala 3 library for building Model Context Protocol (MCP) servers. It provides annotation-driven APIs (`@Tool`, `@Resource`, `@Prompt`) with automatic JSON Schema generation via Scala 3 macros.

## Build System

**Build tool**: Mill 1.1.0-RC3 (configured in `.mill-version`)

### Common Commands

```bash
# Compile
./mill fast-mcp-scala.compile

# Run tests
./mill fast-mcp-scala.test

# Run specific test class
./mill fast-mcp-scala.test com.tjclp.fastmcp.macros.ToolProcessorTest

# Format code
./mill fast-mcp-scala.reformat

# Check formatting (CI uses this)
./mill fast-mcp-scala.checkFormat

# Generate coverage report
./mill fast-mcp-scala.scoverage.htmlReport

# Publish locally for testing
./mill fast-mcp-scala.publishLocal
```

## Project Structure

```
fast-mcp-scala/
├── build.mill                 # Mill build definition
├── .mill-version              # Mill version (1.1.0-RC3)
├── .mill-jvm-opts             # JVM options for Mill
├── fast-mcp-scala/
│   ├── src/com/tjclp/fastmcp/
│   │   ├── core/              # Core types, annotations, ADTs
│   │   │   ├── Annotations.scala    # @Tool, @Param, @Resource, @Prompt
│   │   │   └── Types.scala          # ToolDefinition, Content types, etc.
│   │   ├── macros/            # Scala 3 macro implementations
│   │   │   ├── ToolProcessor.scala       # Processes @Tool annotations
│   │   │   ├── ResourceProcessor.scala   # Processes @Resource annotations
│   │   │   ├── PromptProcessor.scala     # Processes @Prompt annotations
│   │   │   ├── MacroUtils.scala          # Shared macro utilities
│   │   │   ├── JsonSchemaMacro.scala     # JSON Schema generation
│   │   │   ├── RegistrationMacro.scala   # scanAnnotations entry point
│   │   │   └── schema/                   # Schema extraction helpers
│   │   ├── runtime/           # Runtime utilities (RefResolver)
│   │   ├── server/            # Server implementation
│   │   │   ├── FastMcpServer.scala       # Main server class
│   │   │   ├── McpContext.scala          # Request context
│   │   │   └── manager/                  # Tool/Resource/Prompt managers
│   │   └── examples/          # Example servers
│   └── test/src/              # Test sources (mirrors src structure)
└── scripts/                   # Example scripts for scala-cli
```

## Key Concepts

### Annotations

- `@Tool` - Marks a method as an MCP tool
- `@Resource` - Marks a method as an MCP resource (static or templated)
- `@Prompt` - Marks a method as an MCP prompt
- `@Param` - Describes method parameters with metadata:
  - `description: String` - Parameter description
  - `example: Option[String]` - Example value (adds `examples` array to schema)
  - `required: Boolean` - Override required status (must use `Option[T]` or default value if `false`)
  - `schema: Option[String]` - Custom JSON Schema override

### Macro System

The project uses Scala 3 macros extensively. Key compiler options:
- `-Xcheck-macros` - Enable macro checking
- `-experimental` - Enable experimental features
- `-Xmax-inlines:128` - Increase inline limit for complex macros

The main entry point is `scanAnnotations[T]` which:
1. Finds all `@Tool`, `@Resource`, `@Prompt` methods in type `T`
2. Generates JSON schemas for parameters
3. Registers handlers with the appropriate managers

### Java SDK Interop

FastMCP-Scala wraps the Java MCP SDK (`io.modelcontextprotocol:mcp`). Key interop points:
- `Types.scala` - Scala ADTs with `toJava` methods
- `FastMcpServer.scala` - Bridges ZIO effects to Reactor Mono
- Use `@SuppressWarnings(Array("org.wartremover.warts.Null"))` at Java boundaries

## Code Quality

### WartRemover

Configured in `build.mill`:
- **Errors** (fail build): `Null`, `TryPartial`, `TripleQuestionMark`, `ArrayEquals`
- **Warnings**: `Var`, `Return`, `AsInstanceOf`, `IsInstanceOf`

To suppress warnings at Java SDK boundaries:
```scala
@SuppressWarnings(Array("org.wartremover.warts.Null"))
```

### Formatting

Uses Scalafmt with config in `.scalafmt.conf`. Always run `./mill fast-mcp-scala.reformat` before committing.

## Testing

Tests are in `fast-mcp-scala/test/src/`. Key test classes:
- `MacroUtilsTest` - Unit tests for macro utilities
- `ToolProcessorTest` - Integration tests for @Tool processing
- `JsonSchemaMacroTest` - Schema generation tests
- `ContextPropagationTest` - Context injection tests

Run all tests: `./mill fast-mcp-scala.test`

## CI/CD

- **CI** (`.github/workflows/ci.yml`): Runs on PRs and main pushes, tests on JDK 17, 21, 24
- **Release** (`.github/workflows/release.yml`): Triggered by `v*` tags, publishes to Maven Central

## Common Tasks

### Adding a New Feature

1. Implement in appropriate package under `fast-mcp-scala/src/`
2. Add tests in `fast-mcp-scala/test/src/`
3. Run `./mill fast-mcp-scala.test`
4. Run `./mill fast-mcp-scala.checkFormat` (or `reformat`)

### Modifying Macros

Macros are in `fast-mcp-scala/src/com/tjclp/fastmcp/macros/`. Key files:
- `ToolProcessor.scala` - Start here for @Tool changes
- `MacroUtils.scala` - Shared utilities (schema injection, annotation parsing)
- `JsonSchemaMacro.scala` - Schema generation from types

After macro changes, clean and recompile:
```bash
rm -rf out/fast-mcp-scala && ./mill fast-mcp-scala.compile
```

### Testing Locally with Another Project

```bash
./mill fast-mcp-scala.publishLocal
```

Then in your project use version `0.2.4-SNAPSHOT`.

## Dependencies

Key dependencies (versions in `build.mill`):
- ZIO 2.1.20 - Effect system
- Tapir 1.11.42 - Schema derivation
- Circe - JSON handling
- Java MCP SDK 0.13.1 - Protocol implementation
- ScalaTest 3.2.19 - Testing

## Troubleshooting

### Macro Compilation Errors

If you see "Cannot prove that X <:< Y" or similar:
1. Clean: `rm -rf out/fast-mcp-scala`
2. Recompile: `./mill fast-mcp-scala.compile`

### WartRemover Null Errors

For Java SDK interop, add suppression annotation to the class/object:
```scala
@SuppressWarnings(Array("org.wartremover.warts.Null"))
class MyJavaInteropClass { ... }
```

### Test Compilation Issues

Macro tests may need a clean rebuild:
```bash
rm -rf out/fast-mcp-scala/test && ./mill fast-mcp-scala.test.compile
```
