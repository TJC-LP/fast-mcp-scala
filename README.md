# fast-mcp-scala

**Scala 3 for MCP: annotation-driven and typed-contract APIs on both JVM and Scala.js/Bun.**

fast-mcp-scala is a developer-friendly library for building [Model Context Protocol](https://modelcontextprotocol.io/) servers. Extend one trait, declare your tools, done:

```scala 3 raw
object HelloWorld extends McpServerApp[Stdio, HelloWorld.type]:
  @Tool(name = Some("add"))
  def add(@Param("a") a: Int, @Param("b") b: Int): Int = a + b
```

No `override def run`, no `import zio.*`, no ceremony. Two complementary registration paths converge on the same backend:

- `@Tool` / `@Resource` / `@Prompt` annotations + `scanAnnotations[T]` for a zero-boilerplate, macro-driven experience (JVM + Scala.js/Bun)
- `McpTool`, `McpPrompt`, `McpStaticResource`, `McpTemplateResource` for first-class, testable, cross-platform contract values — handlers return plain values, `ZIO`, `Either[Throwable, _]`, or `Try` via the `ToHandlerEffect` typeclass

Built on **ZIO 2**, **Tapir**-derived schemas, and **zio-json** on both platforms. The whole MCP protocol layer — JSON-RPC, wire types, router, transports — is **native pure Scala 3** in `shared/`; there is no vendored SDK (the official TS SDK appears only as a test-time conformance client). Transport is a phantom type parameter — `McpServerApp[Stdio, Self.type]` or `McpServerApp[Http, Self.type]` — with compile-time runner dispatch.

## Contents

- [Installation](#installation)
- [Quickstart](#quickstart)
- [Choosing a registration path](#choosing-a-registration-path)
- [Tools and `@Param` metadata](#tools-and-param-metadata)
- [Tool hints](#tool-hints)
- [Resources (static and templated)](#resources-static-and-templated)
- [Prompts](#prompts)
- [Context (`McpContext`)](#context-mcpcontext)
- [Transports](#transports)
- [Native image (GraalVM)](#native-image-graalvm)
- [Customizing decoding (zio-json)](#customizing-decoding-zio-json)
- [One core, two transports](#one-core-two-transports)
- [Spec coverage](#spec-coverage)
- [Running examples](#running-examples)
- [Claude Desktop integration](#claude-desktop-integration)
- [Developing locally](#developing-locally)

## Installation

```scala 3 ignore
// JVM — native Scala MCP core with annotations, derived schemas, HTTP + stdio transports.
libraryDependencies += "com.tjclp" %% "fast-mcp-scala" % "1.0.0-RC1"

// Scala.js — the same native core on Bun (Bun.serve + Node stdio), same annotation and typed-contract APIs.
libraryDependencies += "com.tjclp" %%% "fast-mcp-scala" % "1.0.0-RC1"
```

Built against Scala 3.8.3. JVM requires JDK 17+. Scala.js artifact is published for `sjs1_3` (Scala.js 1.x); runs on Bun (first-class) and Node 18+.

## Quickstart

A single-file server with one tool — the same code lives in [`HelloWorld.scala`](fast-mcp-scala/jvm/src/com/tjclp/fastmcp/examples/HelloWorld.scala):

```scala 3 raw
//> using scala 3.8.3
//> using dep com.tjclp::fast-mcp-scala:1.0.0-RC1
//> using options "-Xcheck-macros" "-experimental"

import com.tjclp.fastmcp.{*, given}

object HelloWorld extends McpServerApp[Stdio, HelloWorld.type]:

  @Tool(name = Some("add"), description = Some("Add two numbers"), readOnlyHint = Some(true))
  def add(@Param("First operand") a: Int, @Param("Second operand") b: Int): Int = a + b
```

That's it — no `import zio.*`, no `override def run`, no `ZIO.succeed(...)`. The `McpServerApp[T, Self]` trait handles server construction, annotation scanning, and transport lifecycle. Transport is a phantom type parameter (`Stdio` / `Http`) that compile-time-selects the runner.

Exercise it through the MCP Inspector:

```bash
npx @modelcontextprotocol/inspector scala-cli scripts/quickstart.sc
```

## Choosing a registration path

| | Annotations (`@Tool` + `scanAnnotations`) | Typed contracts (`McpTool`) |
|---|---|---|
| Platform | JVM + Scala.js/Bun | JVM + Scala.js/Bun |
| Style | Methods on an object, discovered by macro | First-class `val`s |
| Schema | Derived from method signature & `@Param` | Derived from case-class fields & `@Param` |
| Testing | Call the method directly | Invoke `.handler` on the value |
| Composability | Whatever methods the object exposes | Collect into lists, generate from config |
| Best for | Quick servers, prototypes, single-module apps | Libraries, cross-module sharing, production codebases |

Both coexist on the same server — override `tools` / `prompts` / `staticResources` / `templateResources` on your `McpServerApp` to mount typed contracts alongside annotated methods:

```scala 3 raw
object MyServer extends McpServerApp[Stdio, MyServer.type]:
  @Tool(name = Some("ping")) def ping(): String = "pong"

  override val tools = List(
    McpTool[AddArgs, AddResult](name = "add") { args =>
      AddResult(args.a + args.b)            // plain value — auto-lifted
    }
  )
```

Handler lambdas return plain values, `ZIO`, `Either[Throwable, _]`, or `scala.util.Try` — the `ToHandlerEffect[F[_]]` typeclass picks the right lift. Bring your own given for other effect systems (`cats.effect.IO`, Monix, ...).

See [`AnnotatedServer.scala`](fast-mcp-scala/jvm/src/com/tjclp/fastmcp/examples/AnnotatedServer.scala) for the annotation path and [`ContractServer.scala`](fast-mcp-scala/jvm/src/com/tjclp/fastmcp/examples/ContractServer.scala) for typed contracts.

## Tools and `@Param` metadata

Every tool parameter can carry metadata that flows into the derived JSON schema:

```scala 3 raw
@Tool(name = Some("search"), description = Some("Search with optional filters"))
def search(
    @Param(description = "Search query", examples = List("scala", "mcp"))
    query: String,
    @Param(description = "Maximum results", examples = List("10", "25"), required = false)
    limit: Option[Int],
    @Param(
      description = "Sort order",
      schema = Some("""{"type": "string", "enum": ["relevance", "date"]}""")
    )
    sortBy: String
): String = ???
```

- `description` — populates the schema's `description` field
- `examples` — populates the JSON Schema `examples` array (clients can show suggestions)
- `required = false` — combined with `Option[...]` or a default value, marks the field optional
- `schema` — raw JSON Schema fragment that overrides the derived schema entirely (useful for enum constraints, patterns, or numeric bounds Scala types can't express)

Full demo in [`AnnotatedServer.scala`](fast-mcp-scala/jvm/src/com/tjclp/fastmcp/examples/AnnotatedServer.scala).

## Tool hints

MCP Tool Annotations (a.k.a. behavioral hints) tell the client how your tool behaves. Set them on `@Tool`:

| Hint | Meaning |
|---|---|
| `title` | Human-readable display name (distinct from the wire-level `name`) |
| `readOnlyHint` | The tool only reads state; safe to call without confirmation |
| `destructiveHint` | The tool may irreversibly modify state — clients should confirm |
| `idempotentHint` | Repeated calls with the same args produce the same effect as one call |
| `openWorldHint` | The tool reaches outside the local process (network, filesystem, APIs) |
| `returnDirect` | Return the result directly to the user, skipping LLM post-processing |

```scala 3 raw
@Tool(
  name = Some("listTasks"),
  description = Some("List tasks with optional filtering"),
  readOnlyHint = Some(true),
  idempotentHint = Some(true),
  openWorldHint = Some(false)
)
def listTasks(filter: TaskFilter): List[Task] = ...
```

See [`TaskManagerServer.scala`](fast-mcp-scala/jvm/src/com/tjclp/fastmcp/examples/TaskManagerServer.scala) for hints across a realistic tool set.

## Resources (static and templated)

Static resources have a fixed URI and no parameters:

```scala 3 raw
@Resource(uri = "static://welcome", description = Some("A welcome message"))
def welcome(): String = "Welcome!"
```

Templated resources use `{placeholders}` in the URI, matched against method parameter names:

```scala 3 raw
@Resource(
  uri = "users://{userId}/profile",
  description = Some("User profile as JSON"),
  mimeType = Some("application/json")
)
def userProfile(@Param("The user id") userId: String): String = ...
```

## Prompts

Return a `List[Message]` — fast-mcp-scala handles the MCP framing:

```scala 3 raw
@Prompt(name = Some("greeting"), description = Some("Personalized greeting"))
def greeting(
    @Param("Name of the person") name: String,
    @Param("Optional title", required = false) title: String = ""
): List[Message] =
  List(Message(Role.User, TextContent(s"Generate a warm greeting for $title $name.")))
```

A prompt that returns a single `String` is automatically wrapped into a `User` message.

## Context (`McpContext`)

Add an optional `ctx: McpContext` (annotation path) or use `McpTool.contextual` (typed-contract path) to access the client's declared info and capabilities:

```scala 3 raw
def echo(args: Map[String, Any], ctx: Option[McpContext]): String =
  val clientName = ctx.flatMap(_.getClientInfo.map(_.name())).getOrElse("unknown")
  s"Hello from $clientName"
```

Runnable demo: [`ContextEchoServer.scala`](fast-mcp-scala/jvm/src/com/tjclp/fastmcp/examples/ContextEchoServer.scala).

## Transports

Transport is a phantom type parameter on `McpServerApp[T, Self]` — `Stdio` or `Http`. The matching `TransportRunner[T]` given resolves at compile time, so there's no run-time transport plumbing in user code.

### stdio (for Claude Desktop, MCP Inspector)

```scala 3 raw
object MyServer extends McpServerApp[Stdio, MyServer.type]:
  @Tool(...) def hello(name: String): String = s"Hello, $name!"
```

### HTTP (for remote clients, load balancers, test harnesses)

Flip to `Http` and override `settings` to tune the listener. For MCP **2026-07-28**, `runHttp()` accepts one stateless JSON-RPC message per `POST /mcp`; a request may receive a request-scoped SSE stream for progress, logging, subscriptions, and its final response. Protocol sessions, `Mcp-Session-Id`, the standalone GET stream, SSE replay, and HTTP DELETE are not used by the modern path.

```scala 3 raw
object MyHttpServer extends McpServerApp[Http, MyHttpServer.type]:
  override def settings = McpServerSettings(port = 8090)

  @Tool(...) def hello(name: String): String = s"Hello, $name!"
```

`stateless` now controls only the initialization-era compatibility adapter. Modern requests are stateless regardless of the flag. Leaving it `false` (the default) permits older clients to fall back to the former initialize/session/GET/DELETE flow; setting it `true` disables that legacy session store.

Need lower-level control? Skip the sugar trait and construct directly — `val server = McpServer("name", "0.1.0")` returns the platform-appropriate server, and you can call `.tool(...)` / `.runHttp()` yourself inside your own `ZIOAppDefault`.

| Setting | Default | Description |
|---|---|---|
| `host` | `127.0.0.1` | Bind address (**changed in 0.5.0** from `0.0.0.0` per the spec's bind-localhost guidance; set `"0.0.0.0"` explicitly for containers / external exposure) |
| `port` | `8000` | Listen port |
| `httpEndpoint` | `/mcp` | JSON-RPC endpoint path |
| `stateless` | `false` | Disable the legacy HTTP session store; modern requests are always stateless |
| `sessionIdleTimeout` | `30 minutes` | Evict legacy sessions with no client activity (live legacy GET streams are exempt); `None` disables |
| `keepAliveInterval` | `None` | When set, emit SSE heartbeats on quiet streams so proxies don't kill long calls |
| `allowedHosts` | `None` | DNS-rebinding guard: reject requests whose `Host`/`Origin` isn't in the set (403) |
| `loggingEnabled` | `false` | Advertise logging; use per-request `_meta` levels in 2026 and `logging/setLevel` for legacy clients |
| `resourcesSubscribe` | `false` | Enable legacy `resources/subscribe`; modern clients use `subscriptions/listen` |

Modern POST requests must include `Content-Type: application/json`, an `Accept` header listing both JSON and SSE, `MCP-Protocol-Version: 2026-07-28`, and `Mcp-Method`; tool calls, resource reads, and prompt gets also require `Mcp-Name`. The protocol version and client capabilities are repeated in every request's `params._meta`. Header/body mismatches return HTTP 400 with `-32020`; unsupported versions return `-32022`; unknown request methods return HTTP 404 with `-32601`. The complete wire-behavior and review matrix is in the [2026-07-28 upgrade guide](docs/2026-07-28-upgrade.md).

## Native image (GraalVM)

Stdio servers compile to self-contained native binaries with **zero hand-written reachability
metadata** — registration and schema derivation are compile-time macros, so there is nothing for
closed-world analysis to miss, and the transport-seam split keeps zio-http/netty out of
stdio-only images entirely (~35 MB, instant startup, no JVM in the container):

```scala
object server extends ScalaModule with mill.javalib.NativeImageModule {
  def scalaVersion = "3.8.3"
  def scalacOptions = Seq("-experimental")   // the annotation macros require it
  def mvnDeps = Seq(mvn"com.tjclp::fast-mcp-scala:<version>".exclude("dev.zio" -> "zio-http_3"))
  def mainClass = Some("com.example.MyServer")
  override def jvmVersion = Task { "graalvm-community:25.0.2" }
  override def nativeImageOptions = Task { super.nativeImageOptions() ++ Seq("--no-fallback") }
}
```

CI builds and exercises a native `AnnotatedServer` on every PR (`scripts/native-smoke.sh`).
HTTP-transport native images are in progress. Full recipe, caveats, and the metadata audit loop:
[docs/native-image.md](docs/native-image.md).

## Tasks (experimental, off by default)

MCP Tasks are now the official **`io.modelcontextprotocol/tasks` extension**. A client declares the extension in its per-request capabilities; the server may then return a flat `resultType: "task"` bearer handle without per-call augmentation. Clients poll `tasks/get`, cancel with `tasks/cancel`, and use `tasks/update` only when a task is waiting for input. `tasks/list`, `tasks/result`, and `params.task` belong to the 2025-11-25 compatibility adapter and are rejected on modern requests.

Enable per server (off by default — the spec marks Tasks experimental):

```scala 3 raw
val server = McpServer(
  name = "my-server",
  settings = McpServerSettings(tasks = TaskSettings(enabled = true))
)
```

Opt in per tool — annotation path:

```scala 3 raw
@Tool(name = Some("expensive-op"), taskSupport = Some("optional"))
def expensiveOp(@Param("input") x: String): String = ???
```

Opt in per tool — typed-contract path:

```scala 3 raw
val tool = McpTool[Args, Result](name = "expensive-op")(args => work(args))
  .withTaskSupport(TaskSupport.Optional)
```

`taskSupport` remains the server-side policy: `"forbidden"` (default) always runs synchronously; `"optional"` may return a task when the client supports the extension; `"required"` requires the extension and otherwise returns `-32021`. Modern `tools/list` does not expose the removed `execution.taskSupport` field; legacy clients still see and use it.

**Transport policy**: modern task IDs are bearer handles, so task creation and polling work over stdio and both HTTP settings on JVM and Bun. Keep them secret and enforce authorization around the MCP endpoint: possession of an ID grants access to that task. Legacy task IDs remain scoped to their initialized session.

Task IDs come from the platform CSPRNG, a task that outlives its TTL is interrupted (not orphaned), and terminal results stay pollable until the TTL sweeps them. The current server creates working/completed/failed/cancelled tool tasks; it implements `tasks/update` validation but does not yet suspend a task in `input_required`, and task-status notifications are not emitted. The extension remains off by default.

## Customizing decoding (zio-json)

fast-mcp-scala decodes raw JSON-RPC arguments into Scala values with **zio-json** on both platforms (`codec/McpDecoders.scala` over the shared `DefaultDecodeContext`). Primitives, Scala 3 enums, case classes, `Option`, `List`, and `Map` work out of the box.

For anything else — including `java.time` types, which no longer decode for free now that Jackson is gone — supply a `given JsonDecoder[T]`; the shared derivation turns it into the `McpDecoder[T]` the contract layer needs:

```scala 3 raw
import java.time.LocalDateTime
import zio.json.*

given JsonDecoder[LocalDateTime] =
  JsonDecoder[String].mapOrFail(s =>
    scala.util.Try(LocalDateTime.parse(s)).toEither.left.map(_.getMessage)
  )

case class Task(title: String, due: LocalDateTime) derives JsonDecoder
```

Implement `McpDecoder[T]` directly only when the wire format can't be expressed as a `JsonDecoder`.

## One core, two transports

fast-mcp-scala is a single native MCP implementation. The entire protocol layer — JSON-RPC envelope, wire types, router, built-in handlers, middleware, the Tasks state machine — lives in `shared/`; each platform contributes only a `TransportBackend`:

```
                   ┌──────────────────────────────────────┐
                   │  user code: @Tool / typed contracts  │
                   └─────────────────┬────────────────────┘
                                     ▼
                   ┌──────────────────────────────────────┐
                   │  McpServer  [shared/]                │
                   └─────────────────┬────────────────────┘
                                     │ register(tool|resource|prompt)
                                     ▼
                   ┌──────────────────────────────────────┐
                   │  McpRouter  [shared/]                │
                   │  ├─ handler map (capability source)  │
                   │  ├─ RequestContext (per call)        │
                   │  ├─ Session (stdio / legacy queues)  │
                   │  ├─ middleware (validation / tasks)  │
                   │  └─ built-ins, registered only when  │
                   │     their backing content is wired   │
                   └─────────────────┬────────────────────┘
                                     │ TransportBackend (the platform seam)
              ┌──────────────────────┼──────────────────────┐
              ▼                      ▼                      ▼
    ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
    │  stdio (NDJSON) │    │  HTTP stateless │    │ HTTP streamable │
    │  ZIO Stream /   │    │  ZIO HTTP /     │    │ ZIO HTTP /      │
    │  Node stdin     │    │  Bun.serve      │    │ Bun.serve + SSE │
    └─────────────────┘    └─────────────────┘    └─────────────────┘
```

Capabilities are **derived from the registered handler map** — a capability is advertised only when its handler is actually wired, so the server can never over-advertise (the root cause of #56 is gone by construction). `McpServerApp[T, Self]` is the declarative entry point on both targets; typed contracts (`McpTool`, `McpPrompt`, `McpStaticResource`, `McpTemplateResource`) compile and mount unchanged on both.

**What the Scala.js target gives you**:

- The same native MCP **server runtime** on Bun — stdio (`runStdio`, Node stdin) and modern stateless Streamable HTTP (`runHttp`, `Bun.serve`), plus the version-selected legacy session adapter.
- Pluggable tool-argument validation via the shared `Validation.scala` seam (permissive by default on both platforms).
- The shared `McpContext` — client info/capabilities, request/trace metadata, progress/logging, and MRTR-backed Roots/Sampling/Elicitation — identical on JVM and JS.

**Current platform parity**:

| Capability | JVM | Scala.js (Bun-first) |
|---|---|---|
| `McpServerApp[T, Self]` sugar trait | ✅ | ✅ |
| `@Tool` / `@Resource` / `@Prompt` + `scanAnnotations[T]` | ✅ | ✅ |
| Typed contracts (`McpTool`, `McpPrompt`, `McpStaticResource`, `McpTemplateResource`) | ✅ | ✅ |
| `ToolSchemaProvider[A]` auto-derivation from `@Param` | ✅ via Tapir | ✅ via Tapir |
| `ToHandlerEffect[F]` — plain values / ZIO / Either / Try | ✅ | ✅ |
| Stdio transport | ✅ (native) | ✅ (native) |
| Streamable HTTP — stateful (sessions + per-request SSE) | ✅ (ZIO HTTP) | ✅ (Bun.serve) |
| Streamable HTTP — stateless | ✅ | ✅ |
| Standalone GET SSE push channel | ✅ | 405 (per-request SSE covers server→client) |
| Custom decoders | ✅ `given JsonDecoder[T] → McpDecoder[T]` | ✅ same (shared zio-json path) |

Node / Deno parity for the HTTP listener is a follow-up; only the `Bun.serve(...)` entry point is Bun-specific today.

Proof: the official **MCP conformance suite** runs against both platforms in CI ([`scripts/conformance.sh`](scripts/conformance.sh) + [`.github/workflows/conformance.yml`](.github/workflows/conformance.yml)) at **42/42** with zero expected failures; [`ConformanceTest.scala`](fast-mcp-scala/js/test/src/com/tjclp/fastmcp/conformance/ConformanceTest.scala) additionally drives the official TS SDK client against the JVM server over stdio, and [`JsServerHttpTest.scala`](fast-mcp-scala/js/test/src/com/tjclp/fastmcp/conformance/JsServerHttpTest.scala) verifies the Bun HTTP routing.

### Running on Bun

```scala 3 raw
//> using scala 3.8.3
//> using dep com.tjclp::fast-mcp-scala_sjs1:1.0.0-RC1

import com.tjclp.fastmcp.{*, given}

object HelloBun extends McpServerApp[Stdio, HelloBun.type]:
  @Tool(name = Some("add"), description = Some("Add two numbers"), readOnlyHint = Some(true))
  def add(@Param("First operand") a: Int, @Param("Second operand") b: Int): Int = a + b
```

Same shape as the JVM — the `McpServerApp` trait picks up the shared `McpServerCoreFactory` given and builds the one shared `McpServer` over the Bun `TransportBackend`. For typed contracts on Scala.js, `McpTool[...]` auto-generates the input schema as well; import `sttp.tapir.generic.auto.*` at the call site the same way you do on the JVM.

Link with `./mill fast-mcp-scala.js.fastLinkJS`, then `bun run out/fast-mcp-scala/js/fastLinkJS.dest/main.js`. See [`HelloWorldJs.scala`](fast-mcp-scala/js/src/com/tjclp/fastmcp/examples/HelloWorldJs.scala) and [`HttpServerJs.scala`](fast-mcp-scala/js/src/com/tjclp/fastmcp/examples/HttpServerJs.scala) for runnable references.

## Spec coverage

The native path targets **MCP 2026-07-28** and retains an initialization-based adapter for the older versions listed by `Protocol.LegacyProtocolVersions`:

| Capability | Status |
|---|---|
| Tools (list, call) + Tool Annotations/hints | ✅ |
| Structured tool output (`outputSchema` + `structuredContent` via `.withOutputSchema`) | ✅ |
| Static resources & resource templates | ✅ |
| Prompts with arguments | ✅ |
| Stateless per-request metadata + `server/discover` | ✅ |
| Required `resultType` + cache hints | ✅ |
| `McpContext` (client info, capabilities, progress, trace metadata) | ✅ |
| Stdio transport | ✅ |
| Streamable HTTP (stateless POST + request-scoped SSE) | ✅ |
| Legacy initialize/session/GET/DELETE HTTP adapter | ✅ |
| `Mcp-Method`, `Mcp-Name`, and `x-mcp-header` validation | ✅ |
| Progress notifications | ✅ |
| MRTR for Roots, Sampling, and Elicitation | ✅ |
| Completion (`completion/complete`) | ✅ |
| `subscriptions/listen` handshake and stream lifecycle | ✅; no dynamic change publishers yet |
| Per-request log level | ✅ (opt-in) |
| Deprecated Roots, Sampling, Logging legacy surfaces | ✅ (compatibility only) |
| Cancellation (`notifications/cancelled`) | ✅ |
| Tasks extension | ✅ (opt-in; no task `input_required` production yet) |
| DNS-rebinding protection (`allowedHosts`) | ✅ (opt-in) |
| Legacy session idle eviction + SSE keepalives | ✅ |

See the [CHANGELOG](CHANGELOG.md) for release-by-release changes.

## Running examples

**JVM** — [`fast-mcp-scala/jvm/src/com/tjclp/fastmcp/examples/`](fast-mcp-scala/jvm/src/com/tjclp/fastmcp/examples/):

| Example | Demonstrates |
|---|---|
| `HelloWorld.scala` | Minimum viable server — one tool, stdio |
| `AnnotatedServer.scala` | Flagship annotation path — tools, hints, `@Param` features, resources, prompts |
| `ContractServer.scala` | Typed contracts as first-class values; cross-platform story |
| `TaskManagerServer.scala` | Realistic domain server — custom decoders, hints across a CRUD-style surface |
| `ContextEchoServer.scala` | `McpContext` introspection inside a tool handler |
| `HttpServer.scala` | HTTP transport (Streamable default, Stateless via a flag) with curl recipes |

```bash
./mill fast-mcp-scala.jvm.runMain com.tjclp.fastmcp.examples.HelloWorld
# or, via scala-cli:
scala-cli scripts/quickstart.sc
```

**Scala.js / Bun** — [`fast-mcp-scala/js/src/com/tjclp/fastmcp/examples/`](fast-mcp-scala/js/src/com/tjclp/fastmcp/examples/):

| Example | Demonstrates |
|---|---|
| `HelloWorldJs.scala` | Minimum viable server on Bun — one tool, stdio |
| `HttpServerJs.scala` | Streamable HTTP transport on Bun — stateful sessions or stateless |

```bash
./mill fast-mcp-scala.js.fastLinkJS
bun run out/fast-mcp-scala/js/fastLinkJS.dest/main.js
```

## Claude Desktop integration

Add to `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "fast-mcp-scala-example": {
      "command": "scala-cli",
      "args": [
        "-e",
        "//> using dep com.tjclp::fast-mcp-scala:1.0.0-RC1",
        "--main-class",
        "com.tjclp.fastmcp.examples.AnnotatedServer"
      ]
    }
  }
}
```

> fast-mcp-scala example servers are for demo purposes only — they don't do anything useful, but they make it easy to see MCP in action.

For architectural detail, see [`docs/architecture.md`](docs/architecture.md).

## License

[MIT](LICENSE)

---

## Developing locally

### Build commands (Mill)

```bash
./mill fast-mcp-scala.compile                                   # Compile JVM + Scala.js
./mill fast-mcp-scala.test                                      # All tests (JVM + Bun conformance)
./mill fast-mcp-scala.checkFormat                               # Scalafmt check (all sources)
./mill fast-mcp-scala.reformat                                  # Auto-format (all sources)
./mill fast-mcp-scala.jvm.test                                  # JVM tests only
./mill fast-mcp-scala.js.test.bunTest                           # Scala.js conformance tests only
./mill fast-mcp-scala.jvm.publishLocal                          # Publish JVM artifact to ~/.ivy2/local
```

### Consuming a local build

After `publishLocal`:

```scala 3 ignore
libraryDependencies += "com.tjclp" %% "fast-mcp-scala" % "1.0.0-RC2-SNAPSHOT"
```

Or with Mill:

```scala 3 ignore
def ivyDeps = Agg(
  ivy"com.tjclp::fast-mcp-scala:1.0.0-RC2-SNAPSHOT"
)
```

Or point `scala-cli` at a built JAR directly:

```scala 3 ignore
//> using scala 3.8.3
//> using jar "/absolute/path/to/out/fast-mcp-scala/jvm/jar.dest/out.jar"
//> using options "-Xcheck-macros" "-experimental"
```
