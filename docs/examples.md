# Examples

← [README](../README.md) · see also [Platforms](./platforms.md) · [CONTRIBUTING](../CONTRIBUTING.md) for build commands

All example servers are demo-only: they do nothing useful, but they make it easy to see MCP in
action and to copy a working shape.

## Cross-platform (`shared/`)

[`fast-mcp-scala/shared/src/com/tjclp/fastmcp/examples/`](../fast-mcp-scala/shared/src/com/tjclp/fastmcp/examples/)
compiles and runs on all three platforms (JVM, Scala.js/Bun, Scala Native):

| Example | Demonstrates |
|---|---|
| `HelloWorld.scala` | Minimum viable server: one tool, stdio |
| `AnnotatedServer.scala` | Flagship annotation path: tools, hints, `@Param` features, resources, prompts |
| `ContractServer.scala` | Typed contracts as first-class values |
| `ContextEchoServer.scala` | `McpContext` introspection inside a tool handler |
| `conformance/ConformanceServer.scala` | The server the official conformance suite runs against, with the SEP-2575 / SEP-2322 diagnostic fixtures |

## JVM-only

[`fast-mcp-scala/jvm/src/com/tjclp/fastmcp/examples/`](../fast-mcp-scala/jvm/src/com/tjclp/fastmcp/examples/):

| Example | Demonstrates |
|---|---|
| `HttpServer.scala` | HTTP transport: modern stateless POST + request-scoped SSE, with the legacy session adapter enabled by default |
| `TaskManagerServer.scala` | Realistic domain server: custom decoders, hints across a CRUD-style surface |
| `conformance/ConformanceServerJvm.scala` | JVM entry point for the conformance server (`scripts/conformance.sh jvm`) |

## Scala.js / Bun

[`fast-mcp-scala/js/src/com/tjclp/fastmcp/examples/`](../fast-mcp-scala/js/src/com/tjclp/fastmcp/examples/):

| Example | Demonstrates |
|---|---|
| `HttpServerJs.scala` | Streamable HTTP on `Bun.serve`, plus a typed tool with an explicit schema (`McpTool.withSchema`) |
| `conformance/ConformanceServerJs.scala` | Exports `startConformance`, the entry point `scripts/conformance.sh js` imports |

## Running them

**JVM**, through Mill or scala-cli:

```bash
./mill fast-mcp-scala.jvm.runMain com.tjclp.fastmcp.examples.HelloWorld

scala-cli scripts/quickstart.sc                               # a single-file server
scala-cli scripts/examples.sc --main-class com.tjclp.fastmcp.examples.AnnotatedServer
```

`scripts/examples.sc` is a launcher pinned to the latest published artifact; pass any of the
example main classes above. Exercise a stdio server interactively with the MCP Inspector:

```bash
npx @modelcontextprotocol/inspector scala-cli scripts/quickstart.sc
```

**Bun.** The in-repo js bundle has no runnable main yet (see [platforms.md](./platforms.md#in-this-repository)).
Package a single-file server with scala-cli and run it on Bun:

```bash
scala-cli --power package --js --js-version 1.22.0 HelloBun.scala -o hello.mjs
bun run hello.mjs
```

**Scala Native.** Link the `AnnotatedServer` demo and drive it through the MCP handshake:

```bash
./mill fast-mcp-scala.scalaNative.nativeLink
scripts/native-smoke.sh "$(./mill --no-server show fast-mcp-scala.scalaNative.nativeLink | tr -d '"' | sed 's/^ref:[^:]*:[^:]*://')"
```

**Conformance suite**, locally, against any platform:

```bash
scripts/conformance.sh jvm            # active suite, both protocol eras, empty baseline
scripts/conformance.sh js 8078 2026   # Bun, only the scenarios 2026-07-28 requires
scripts/conformance.sh native         # the GraalVM HTTP image against the JVM baseline
```

**Claude Desktop.** Point `claude_desktop_config.json` at any stdio example; the README shows the
`AnnotatedServer` configuration.
