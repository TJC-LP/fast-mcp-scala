# CLAUDE.md - fast-mcp-scala Development Guide

## Project Overview

fast-mcp-scala is a high-level Scala 3 library for building Model Context Protocol (MCP) servers. It provides two registration paths:

1. **Annotation-driven** (`@Tool`, `@Resource`, `@Prompt` + `scanAnnotations`) — zero-boilerplate on JVM and Scala.js/Bun
2. **Typed contracts** (`McpTool`, `McpPrompt`, `McpStaticResource`, `McpTemplateResource`) — explicit, cross-platform (JVM + Scala.js)

Both paths converge on the same `McpServer` trait and support `@Param` metadata on parameters/fields.

## Build System

**Build tool**: Mill 1.1.5 (configured in `.mill-version`)
**Scala**: 3.8.3
**Plugins**: mill-bun-plugin 0.2.1 (Scala.js + Bun integration)

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
./mill fast-mcp-scala.js.publishLocal               # Publish Scala.js artifact to ~/.ivy2/local
./mill -i __.publishLocal                           # Publish both artifacts
```

## Project Structure

```
fast-mcp-scala/
├── build.mill                 # Mill build definition
├── .mill-version              # Mill version (1.1.5)
├── fast-mcp-scala/
│   ├── shared/src/            # Platform-independent code (JVM + JS) — the native MCP core
│   │   └── com/tjclp/fastmcp/
│   │       ├── core/
│   │       │   ├── Annotations.scala    # @Tool, @Param, @Resource, @Prompt
│   │       │   ├── Types.scala          # ToolDefinition, Content, ToolInputSchema, etc.
│   │       │   ├── Contracts.scala      # McpTool, McpPrompt, McpDecoder, McpEncoder
│   │       │   ├── Protocol.scala       # protocol versions + JSON-RPC error codes
│   │       │   ├── Tasks.scala          # MCP Tasks wire types (spec 2025-11-25)
│   │       │   └── wire/                # 2025-11-25 wire shapes (capabilities, tools, ...)
│   │       ├── jsonrpc/                 # JSON-RPC 2.0 envelope + McpError
│   │       ├── codec/                   # DefaultDecodeContext + McpDecoders (zio-json)
│   │       ├── macros/                  # scanAnnotations + @Tool/@Resource/@Prompt processors
│   │       ├── runtime/                 # RefResolver
│   │       ├── examples/                # cross-platform ConformanceServer
│   │       └── server/
│   │           ├── McpServer.scala      # THE server class (both platforms)
│   │           ├── McpServerCore.scala  # abstract API the macros target
│   │           ├── McpContext.scala     # request context incl. server→client requests
│   │           ├── McpServerSettings.scala
│   │           ├── manager/             # Tool/Prompt/Resource/Task managers
│   │           ├── router/              # McpRouter, Builtins, Session, middleware
│   │           └── transport/           # TransportBackend seam, MessageLoop, HostGuard
│   ├── jvm/
│   │   ├── src/               # JVM-specific code
│   │   │   └── com/tjclp/fastmcp/
│   │   │       ├── macros/                  # JsonSchemaMacro, MacroUtils, schema/ (Tapir-backed)
│   │   │       ├── server/transport/JvmTransportBackend.scala  # ZIO HTTP + System.in/out
│   │   │       └── examples/
│   │   └── test/src/          # JVM test sources
│   └── js/                    # Scala.js code (Bun-first runtime)
│       ├── src/               # JsTransportBackend (Bun.serve + Node stdio), facades, examples
│       └── test/src/          # Conformance, HTTP, codec, contract surface tests
```

## Key Concepts

### Annotation Path (JVM + Scala.js/Bun)

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

#### ZIO environment-aware handlers

Annotated methods may also return `ZIO[R, E, A]` with `R ≠ Any`. Construct the server with the
matching environment type (`McpServer.typed[R]("name")`) and provide the layer at the server
boundary via `.provide(...)`:

```scala
object MyServer extends ZIOAppDefault:
  @Tool() def fetch(): ZIO[zio.http.Client, Throwable, String] =
    ZIO.serviceWithZIO[zio.http.Client](_.url("https://example.com").get)

  override def run =
    for
      server <- ZIO.succeed(McpServer.typed[zio.http.Client]("MyServer"))
      _ <- ZIO.attempt(server.scanAnnotations[MyServer.type])
      _ <- server.runHttp().provide(zio.http.Client.default)
    yield ()
```

If a method's required `R` isn't satisfied by the server's type, the macro emits a compile-time
error pointing at the mismatched handler.

### Typed Contract Path (cross-platform)

```scala
import sttp.tapir.generic.auto.*   // enables ToolSchemaProvider derivation

case class AddArgs(@Param("First number") a: Int, @Param("Second number") b: Int)

val addTool = McpTool[AddArgs, Int](
  name = "add",
  description = Some("Add two numbers")
) { args => ZIO.succeed(args.a + args.b) }

// Mount:
server.tool(addTool)
```

`.withOutputSchema` (given a `JsonEncoder` for `Out`) additionally advertises a derived
`outputSchema` on `tools/list` and emits conforming `structuredContent` on every call.

### When to Use Which

| | Annotations | Typed Contracts |
|---|---|---|
| Platform | JVM + Scala.js | JVM + Scala.js |
| Boilerplate | Zero (macro-driven) | Minimal (case class + builder) |
| Schema | Auto from method signature | Auto from case class via `ToolSchemaProvider` on JVM and JS |
| `@Param` | On method parameters | On case class fields |
| Composability | Methods on an object | First-class values |
| Best for | Quick servers, prototyping | Libraries, cross-platform, production |

### Annotations

- `@Tool` - Marks a method as an MCP tool. Supports behavioral hints:
  - `title`, `readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint`, `returnDirect`
  - `taskSupport: Option[String]` — opt into experimental MCP Tasks polling: `"forbidden"` (default), `"optional"`, or `"required"`. See "Tasks" section below.
- `@Resource` - Marks a method as an MCP resource (static or templated)
- `@Prompt` - Marks a method as an MCP prompt
- `@Param` - Describes parameters/fields with metadata:
  - `description: String` - Parameter description
  - `example: Option[String]` - Example value
  - `required: Boolean` - Override required status
  - `schema: Option[String]` - Custom JSON Schema override

### Typed Contracts

- `McpTool[In, Out]` / `McpTool[In, Out, R](...)` - Typed tool with auto-schema derivation; supply `R` explicitly for layer-aware handlers.
- `McpPrompt[In]` / `McpPrompt[In, R](...)` - Typed prompt with manual argument metadata
- `McpStaticResource` / `McpStaticResource.withEnv[R]` - Typed static resource
- `McpTemplateResource[In]` / `McpTemplateResource[In, R](...)` - Typed resource template
- `McpDecoder[T]` / `McpEncoder[A]` - Platform-neutral codecs
- `ToolSchemaProvider[A]` - Auto-derives `inputSchema` from `@Param`-annotated case classes on both JVM and JS
- `McpEncoder` falls back to `JsonEncoder[A]` → `TextContent(a.toJson)` via ZIO JSON

### Transports

- **Stdio** (`runStdio()`) — stdin/stdout, used by MCP clients
- **HTTP** (`runHttp()`) — streamable (sessions + per-request SSE, GET push channel on JVM, DELETE termination) by default; set `stateless = true` for stateless. Binds `127.0.0.1` by default (set `host = "0.0.0.0"` for containers); only `initialize` mints a session; idle sessions evict after `sessionIdleTimeout`; `allowedHosts` enables the DNS-rebinding guard; `keepAliveInterval` enables SSE heartbeats

### Tasks (experimental, off by default)

MCP Tasks (spec **2025-11-25**) wrap long-running `tools/call` invocations in a durable, polled state machine. Clients send `params.task: {ttl}`, get a `CreateTaskResult` immediately, then poll `tasks/get` / `tasks/result` / `tasks/list` / `tasks/cancel`.

**Enable per server**:

```scala
val server = McpServer(
  name = "my-server",
  settings = McpServerSettings(tasks = TaskSettings(enabled = true))
)
```

**Opt in per tool** (annotation):

```scala
@Tool(name = Some("expensive-op"), taskSupport = Some("optional"))
def expensiveOp(@Param("input") x: String): String = ???
```

**Opt in per tool** (typed contract):

```scala
val tool = McpTool[Args, Result](name = "expensive-op")(args => work(args))
  .withTaskSupport(TaskSupport.Optional)
```

`taskSupport` values: `"forbidden"` (default — no tasks), `"optional"` (clients may augment with a task), `"required"` (clients must — bare calls return `-32601`).

**Transport policy** (both platforms):

- Tasks dispatch is native **router middleware** (no transport-layer special-casing).
- Modern 2026-07-28 **bearer tasks work on every transport**, including stateless HTTP; bearer
  tasks are invisible to legacy protocol sessions (and vice versa).
- The **legacy task surface** (`params.task`, `tasks/get|list|cancel|result`) works on any
  transport whose session outlives a single request: **streamable HTTP** (`runHttp()`, the
  default) and **stdio** (one durable session per process). On the **stateless legacy adapter**
  all clients share one session identity, so legacy task requests there are rejected at runtime
  with `-32601`.
- Task ids come from the platform CSPRNG; a task that outlives its TTL is interrupted (not
  orphaned); terminal results stay pollable until the TTL sweeps the entry.

The `tasks` capability is advertised on `initialize` only when `settings.tasks.enabled` is true. The `execution.taskSupport` field is injected on `tools/list` entries that opt in.

### Cross-Platform Architecture

The codebase is split into three sibling trees under `fast-mcp-scala/`:
- `shared/` — the entire native MCP core: annotations, wire types, JSON-RPC, ZIO JSON codecs, the router + built-in handlers + middleware, `McpServer[R]` + `McpServerCore`, typed contracts, and the `TransportBackend` seam
- `jvm/` — the JVM `TransportBackend` (ZIO HTTP + `System.in`/`System.out`), the schema-derivation macros, and examples
- `js/` — the Scala.js `TransportBackend` (`Bun.serve` + Node stdio), small JS facades, and examples

JVM module reads from `shared/src/ + jvm/src/`. JS module reads from `shared/src/ + js/src/` (plus the compile-time-only schema macros under `jvm/src/.../macros`).

### Native MCP core (no vendored SDK)

As of 0.5.0 there is **no wrapped SDK** — the MCP protocol layer is pure Scala 3 in `shared/`:
- `jsonrpc/` — `JsonRpcMessage` + `McpError` (the JSON-RPC 2.0 envelope)
- `core/wire/` + `core/Types.scala` — the 2025-11-25 wire types with ZIO JSON codecs
- `server/router/` — `McpRouter`, `Builtins`, `Middleware`, `RouterBuilder`, `WireMapping`, Tasks
- `codec/` — `DefaultDecodeContext` + `McpDecoders` (one ZIO JSON decode path for both platforms)
- `server/transport/` — `TransportBackend` (the platform seam) + `MessageLoop` (parse → dispatch → encode)

Each platform provides exactly one `given TransportBackend` (`JvmTransportBackend` / `JsTransportBackend`); everything else is shared. The TypeScript `@modelcontextprotocol/sdk` is used only as a test-time conformance client.

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
- `JvmHttpTransportTest` - HTTP transport integration (sessions, 400s, 409, keepalive, eviction)
- `TaskHttpTransportTest` - the full MCP Tasks lifecycle over streamable HTTP
- `StdioLoopLifecycleTest` - stdin-EOF / interruption contracts for the stdio loop
- `JsonRpcEnvelopeTest` / `McpErrorMappingTest` - envelope discrimination + wire error codes
- `WireCodecRoundTripTest` - wire-type codec round-trips (2025-11-25 shapes)
- `StructuredOutputTest` - outputSchema + structuredContent + ServerHooks
- `ConformanceGapsTest` / `ParityFixesTest` - regression nets for closed spec gaps
- `ConformanceTest` (JS) - 17 cross-platform conformance tests against AnnotatedServer (real TS SDK client over stdio)
- `JsServerHttpTest` (JS) - Bun HTTP routing, session lifecycle, HostGuard coverage

## CI/CD

- **CI** (`.github/workflows/ci.yml`): Runs on PRs and main pushes, tests on JDK 17, 21, 24
- **Conformance** (`.github/workflows/conformance.yml`): official `@modelcontextprotocol/conformance` suite against both platforms — 42/42, with expected-failure baselines at `conformance/baseline-{jvm,js}.yml` kept EMPTY (any regression fails the gate). Run locally via `scripts/conformance.sh {jvm|js}`.
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
./mill fast-mcp-scala.js.publishLocal
./mill -i __.publishLocal
```

Then in your project use version `1.0.0-RC2-SNAPSHOT`.

## Dependencies

Key dependencies (versions in `build.mill`):
- Scala 3.8.3
- ZIO 2.1.20 - Effect system
- ZIO JSON 0.7.44 - JSON codecs (shared)
- ZIO HTTP 3.4.0 - HTTP transport
- Tapir 1.11.42 - Compile-time JSON Schema derivation
- mill-bun-plugin 0.2.1 - Scala.js + Bun build integration
- `@modelcontextprotocol/sdk` 1.29.0 - TS MCP SDK, pinned in the js module's `bunDeps`; consumed only by the `js.test` conformance client (zero production `@JSImport`s)
- WartRemover 3.5.6 - Code quality
- ScalaTest 3.2.19 - Testing
