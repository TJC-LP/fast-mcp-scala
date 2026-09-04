# CLAUDE.md - fast-mcp-scala Development Guide

## Project Overview

fast-mcp-scala is a high-level Scala 3 library for building Model Context Protocol (MCP) servers. It provides two registration paths:

1. **Annotation-driven** (`@Tool`, `@Resource`, `@Prompt` + `scanAnnotations`) — zero-boilerplate on JVM, Scala.js/Bun, and Scala Native
2. **Typed contracts** (`McpTool`, `McpPrompt`, `McpStaticResource`, `McpTemplateResource`) — explicit, first-class values, all three platforms

Both paths converge on the same `McpServer` trait and support `@Param` metadata on parameters/fields.

Three platforms: **JVM** (stdio + HTTP), **Scala.js/Bun** (stdio + HTTP), and **Scala Native** (stdio only, EXPERIMENTAL — published as `fast-mcp-scala_native0.5_3`; zio-http has no Native artifacts, so `McpServerApp[Http]` fails to compile there by design).

## Build System

**Build tool**: Mill 1.1.8 (configured in `.mill-version`)
**Scala**: 3.9.0 (LTS)
**Plugins**: mill-bun-plugin 0.3.1 (Scala.js + Bun integration; explicit `scalaJSVersion`, managed Bun 1.4.1, frozen `js/bun.lock`)

### Common Commands

```bash
# Aggregates (JVM + Scala.js + Scala Native)
./mill fast-mcp-scala.compile                       # Compile all platforms
./mill fast-mcp-scala.test                          # All tests, all three platforms
./mill fast-mcp-scala.reformat                      # Auto-format every Scala source
./mill fast-mcp-scala.checkFormat                   # Scalafmt check (CI uses this)

# Single-platform
./mill fast-mcp-scala.jvm.test                      # JVM tests only
./mill fast-mcp-scala.js.test                       # Scala.js conformance tests only (Bun)
./mill fast-mcp-scala.js.bunLock                    # Regenerate js/bun.lock after changing bunDevDeps
./mill fast-mcp-scala.scalaNative.test              # Scala Native tests (links a native binary)
./mill fast-mcp-scala.scalaNative.nativeLink        # Standalone LLVM binary of AnnotatedServer
./mill fast-mcp-scala.jvm.test com.tjclp.fastmcp.macros.ToolProcessorTest

# Publish
./mill fast-mcp-scala.jvm.publishLocal              # Publish JVM artifact to ~/.ivy2/local
./mill fast-mcp-scala.js.publishLocal               # Publish Scala.js artifact to ~/.ivy2/local
./mill -i __.publishLocal                           # Publish all three artifacts
```

## Project Structure

```
fast-mcp-scala/
├── build.mill                 # Versions, compiler flags, shared module traits
├── .mill-version              # Mill version (1.1.8)
├── fast-mcp-scala/
│   ├── package.mill           # The platform modules (jvm/js/scalaNative) + aggregates
│   ├── shared/src/            # Platform-independent code (all 3 platforms) — the native MCP core
│   │   └── com/tjclp/fastmcp/
│   │       ├── core/
│   │       │   ├── Annotations.scala    # @Tool, @Param, @Resource, @Prompt
│   │       │   ├── Types.scala          # ToolDefinition, Content, ToolInputSchema, etc.
│   │       │   ├── Contracts.scala      # McpTool, McpPrompt, McpDecoder, McpEncoder
│   │       │   ├── Protocol.scala       # protocol versions + JSON-RPC error codes
│   │       │   ├── Tasks.scala          # Tasks extension (2026-07-28) + legacy task wire types
│   │       │   └── wire/                # 2026-07-28 wire shapes + compatibility-adapter shapes
│   │       ├── jsonrpc/                 # JSON-RPC 2.0 envelope + McpError
│   │       ├── codec/                   # DefaultDecodeContext + McpDecoders (zio-json)
│   │       ├── macros/                  # scanAnnotations, @Tool/@Resource/@Prompt processors,
│   │       │                            #   JsonSchemaMacro + MacroUtils (schema derivation)
│   │       ├── runtime/                 # RefResolver
│   │       ├── examples/                # cross-platform examples (HelloWorld, AnnotatedServer,
│   │       │                            #   ContractServer, ContextEchoServer, ConformanceServer)
│   │       └── server/
│   │           ├── McpServer.scala      # THE server class (all platforms)
│   │           ├── McpServerCore.scala  # abstract API the macros target
│   │           ├── McpContext.scala     # request context incl. server→client requests
│   │           ├── McpServerSettings.scala
│   │           ├── manager/             # Tool/Prompt/Resource/Task managers
│   │           ├── router/              # McpRouter, Builtins, Session, middleware
│   │           └── transport/           # TransportBackend seam, StdioLoop, MessageLoop, HostGuard
│   ├── jvm/
│   │   ├── src/               # JVM-specific code
│   │   │   └── com/tjclp/fastmcp/
│   │   │       ├── server/transport/        # JvmTransportBackend (stdio) + JvmHttpBackend (netty)
│   │   │       └── examples/                # JVM-only: HttpServer, TaskManagerServer
│   │   └── test/src/          # JVM test sources
│   ├── js/                    # Scala.js code (Bun-first runtime)
│   │   ├── src/               # JsTransportBackend (Bun.serve + Node stdio), facades, examples
│   │   └── test/src/          # Conformance, HTTP, codec, contract surface tests
│   └── native/                # Scala Native code (EXPERIMENTAL, stdio only)
│       ├── src/               # NativeTransportBackend (System.in/out + /dev/urandom)
│       └── test/src/          # Surface, contract, and stdio-lifecycle canaries
```

## Key Concepts

### Annotation Path (all platforms)

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
| Platform | JVM + Scala.js + Scala Native | JVM + Scala.js + Scala Native |
| Boilerplate | Zero (macro-driven) | Minimal (case class + builder) |
| Schema | Auto from method signature | Auto from case class via `ToolSchemaProvider` on every platform |
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
  - `examples: List[String]` - Example values (JSON Schema `examples` array)
  - `required: Boolean` - Override required status
  - `schema: Option[String]` - Custom JSON Schema override

### Typed Contracts

- `McpTool[In, Out]` / `McpTool[In, Out, R](...)` - Typed tool with auto-schema derivation; supply `R` explicitly for layer-aware handlers.
- `McpPrompt[In]` / `McpPrompt[In, R](...)` - Typed prompt with manual argument metadata
- `McpStaticResource` / `McpStaticResource.withEnv[R]` - Typed static resource
- `McpTemplateResource[In]` / `McpTemplateResource[In, R](...)` - Typed resource template
- `McpDecoder[T]` / `McpEncoder[A]` - Platform-neutral codecs
- `ToolSchemaProvider[A]` - Auto-derives `inputSchema` from `@Param`-annotated case classes on both JVM and JS
- `McpEncoder` falls back to `JsonEncoder[A]` → `TextContent(a.toJson)` via ZIO JSON; case classes without any `JsonEncoder` derive one automatically (Mirror-based, `NotGiven`-guarded)
- Scala 3 enums and nested case classes in `In`/`Out` need no user-supplied givens (GH #78): schemas render string enums, codecs derive string-based; user-defined instances always win (macro-side summon-first, never exported givens). Machinery: `shared/.../macros/EnumTypeCollector.scala` + `ZioJsonEnumDerivation.scala`

### Transports

- **Stdio** (`runStdio()`) — stdin/stdout, used by MCP clients
- **HTTP** (`runHttp()`) — MCP 2026-07-28 is stateless: one JSON-RPC message per `POST /mcp`, answered with JSON or a request-scoped SSE stream; no sessions, GET stream, or DELETE on the modern path. Older protocol versions are routed to the legacy initialize/session/GET/DELETE adapter, which is on by default; `stateless = true` disables only that adapter's session store. Binds `127.0.0.1` by default (set `host = "0.0.0.0"` for containers); idle legacy sessions evict after `sessionIdleTimeout`; `allowedHosts` enables the DNS-rebinding guard; `keepAliveInterval` enables SSE heartbeats. Full reference: `docs/transports.md`

### Tasks (experimental, off by default)

MCP Tasks are the official **`io.modelcontextprotocol/tasks` extension** (MCP 2026-07-28). A client declares the extension in its per-request capabilities; the server may return a flat `resultType: "task"` bearer handle, and the client polls `tasks/get`, cancels with `tasks/cancel`, and uses `tasks/update` only when a task waits for input. `params.task`, `tasks/list`, and `tasks/result` belong to the 2025-11-25 compatibility adapter and are rejected on modern requests. Full reference: `docs/tasks.md`.

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

`taskSupport` values: `"forbidden"` (default — always synchronous), `"optional"` (may return a task when the client supports the extension), `"required"` (requires the extension; otherwise `-32021`). Modern `tools/list` does not expose `execution.taskSupport`; legacy clients still see it.

**Transport policy** (all platforms):

- Tasks dispatch is native **router middleware** (no transport-layer special-casing).
- Modern **bearer tasks work on every transport**, including stateless HTTP and stdio; possession
  of an id grants access, so authorization belongs in front of the endpoint. Bearer tasks are
  invisible to legacy protocol sessions (and vice versa).
- The **legacy task surface** (`params.task`, `tasks/get|list|cancel|result`) works on any
  transport whose session outlives a single request (legacy streamable HTTP, stdio). On the
  **stateless legacy adapter** all clients share one session identity, so legacy task requests
  there are rejected with `-32601`.
- Task ids come from the platform CSPRNG (`/dev/urandom` on Scala Native); a task that outlives
  its TTL is interrupted (not orphaned); terminal results stay pollable until the TTL sweeps them.
- Not yet implemented: `input_required` suspension and task-status notifications.

### Cross-Platform Architecture

The codebase is split into three sibling trees under `fast-mcp-scala/`:
- `shared/` — the entire native MCP core: annotations, wire types, JSON-RPC, ZIO JSON codecs, the router + built-in handlers + middleware, `McpServer[R]` + `McpServerCore`, typed contracts, and the `TransportBackend` seam
- `jvm/` — the JVM `TransportBackend` (`System.in`/`System.out`), the `HttpTransportBackend` (ZIO HTTP), and JVM-only examples
- `js/` — the Scala.js `TransportBackend` (`Bun.serve` + Node stdio), small JS facades, and the Bun HTTP example
- `native/` — the Scala Native `TransportBackend` (stdio only, EXPERIMENTAL)

Every module reads exactly `shared/src/ + <platform>/src/`. Nothing reaches across platform trees: the schema-derivation macros and every platform-pure example live in `shared/`, so `shared/` compiles standalone on all three targets.

### Native MCP core (no vendored SDK)

As of 0.5.0 there is **no wrapped SDK** — the MCP protocol layer is pure Scala 3 in `shared/`:
- `jsonrpc/` — `JsonRpcMessage` + `McpError` (the JSON-RPC 2.0 envelope)
- `core/wire/` + `core/Types.scala` — the 2026-07-28 wire types (plus compatibility-adapter shapes) with ZIO JSON codecs
- `server/router/` — `McpRouter`, `Builtins`, `Middleware`, `RouterBuilder`, `WireMapping`, Tasks
- `codec/` — `DefaultDecodeContext` + `McpDecoders` (one ZIO JSON decode path for both platforms)
- `server/transport/` — `TransportBackend` (the platform seam) + `MessageLoop` (parse → dispatch → encode)

Each platform provides exactly one `given TransportBackend` (`JvmTransportBackend` / `JsTransportBackend` / `NativeTransportBackend`) and, where HTTP exists, an `HttpTransportBackend` (`JvmHttpBackend`; the JS backend provides both givens; Scala Native provides none, so `runHttp()` does not compile there); everything else is shared. The TypeScript `@modelcontextprotocol/sdk` is used only as a test-time conformance client.

## Code Quality

### WartRemover

Configured in `build.mill` (v3.6.1):
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
- `WireCodecRoundTripTest` - wire-type codec round-trips (2026-07-28 + legacy shapes)
- `StructuredOutputTest` - outputSchema + structuredContent + ServerHooks
- `ConformanceGapsTest` / `ParityFixesTest` - regression nets for closed spec gaps
- `ConformanceTest` (JS) - 17 cross-platform conformance tests against AnnotatedServer (real TS SDK client over stdio)
- `JsServerHttpTest` (JS) - Bun HTTP routing, session lifecycle, HostGuard coverage

## CI/CD

- **CI** (`.github/workflows/ci.yml`): Runs on PRs and main pushes, tests on the LTS JDKs 17, 21, 25
- **Conformance** (`.github/workflows/conformance.yml`): official `@modelcontextprotocol/conformance` suite (oracle pinned in `scripts/conformance.sh`, currently `0.2.0-alpha.11`) against the JVM and Bun servers, with expected-failure baselines at `conformance/baseline-{jvm,js}.yml` kept EMPTY (any regression fails the gate). `native.yml` runs the same suite against the GraalVM HTTP image. Run locally via `scripts/conformance.sh {jvm|js|native} [port] [active|2026]`.
- **Release** (`.github/workflows/release.yml`): Triggered by `v*` tags, publishes to Maven Central

## Common Tasks

### Adding a New Feature

1. Platform-independent code goes in `shared/src/`
2. JVM-specific code stays in `jvm/src/`
3. Add tests in `jvm/test/src/`, `js/test/src/`, or `native/test/src/`
4. Run `./mill fast-mcp-scala.test` (runs all three platform aggregates)
5. Run `./mill fast-mcp-scala.checkFormat` (or `reformat`)

### Modifying Macros

Macros are in `fast-mcp-scala/shared/src/com/tjclp/fastmcp/macros/` (all three platforms compile
them). Incremental builds go stale after macro edits or file moves — expansion then fails with a
`NoClassDefFoundError` or a spurious `-Xcheck-macros` "Malformed tree". After changes:
```bash
rm -rf out/fast-mcp-scala && ./mill fast-mcp-scala.compile
```

### Testing Locally

```bash
./mill fast-mcp-scala.jvm.publishLocal
./mill fast-mcp-scala.js.publishLocal
./mill -i __.publishLocal
```

Then use the version printed by `./mill show fast-mcp-scala.jvm.publishVersion` (the `build.mill` default, a `-SNAPSHOT` during development). Contributor workflow, quality gates, and release steps: `CONTRIBUTING.md`.

## Dependencies

Key dependencies (versions in `build.mill`):
- Scala 3.9.0 LTS
- ZIO 2.1.20 - Effect system
- ZIO JSON 0.7.44 - JSON codecs (shared)
- ZIO HTTP 3.4.0 - HTTP transport
- Native Scala 3 macros - Compile-time JSON Schema derivation
- mill-bun-plugin 0.3.1 - Scala.js + Bun build integration (Scala.js 1.22.0 pinned via `Versions.scalaJs`)
- `@modelcontextprotocol/sdk` 1.29.0 - TS MCP SDK, pinned in the js module's `bunDevDeps` and frozen by the committed `fast-mcp-scala/js/bun.lock`; consumed only by the `js.test` conformance client (zero production `@JSImport`s, absent from the published bun manifest)
- WartRemover 3.6.1 - Code quality
- ScalaTest 3.2.19 - Testing
