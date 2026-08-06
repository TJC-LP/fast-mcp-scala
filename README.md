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
- [Customizing decoding (zio-json)](#customizing-decoding-zio-json)
- [One core, two transports](#one-core-two-transports)
- [Spec coverage](#spec-coverage)
- [Running examples](#running-examples)
- [Claude Desktop integration](#claude-desktop-integration)
- [Developing locally](#developing-locally)

## Installation

```scala 3 ignore
// JVM — native Scala MCP core with annotations, derived schemas, HTTP + stdio transports.
libraryDependencies += "com.tjclp" %% "fast-mcp-scala" % "0.5.0"

// Scala.js — the same native core on Bun (Bun.serve + Node stdio), same annotation and typed-contract APIs.
libraryDependencies += "com.tjclp" %%% "fast-mcp-scala" % "0.5.0"
```

Built against Scala 3.8.3. JVM requires JDK 17+. Scala.js artifact is published for `sjs1_3` (Scala.js 1.x); runs on Bun (first-class) and Node 18+.

## Quickstart

A single-file server with one tool — the same code lives in [`HelloWorld.scala`](fast-mcp-scala/jvm/src/com/tjclp/fastmcp/examples/HelloWorld.scala):

```scala 3 raw
//> using scala 3.8.3
//> using dep com.tjclp::fast-mcp-scala:0.5.0
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

Flip to `Http` and override `settings` to tune the listener. `runHttp()` serves the full MCP Streamable HTTP spec: `POST /mcp` for JSON-RPC, the `mcp-session-id` header for session tracking, and SSE streams for long-running calls.

```scala 3 raw
object MyHttpServer extends McpServerApp[Http, MyHttpServer.type]:
  override def settings = McpServerSettings(port = 8090)

  @Tool(...) def hello(name: String): String = s"Hello, $name!"
```

Toggle `stateless = true` on `McpServerSettings` for request/response-only mode (no sessions, no SSE), useful behind load balancers.

Need lower-level control? Skip the sugar trait and construct directly — `val server = McpServer("name", "0.1.0")` returns the platform-appropriate server, and you can call `.tool(...)` / `.runHttp()` yourself inside your own `ZIOAppDefault`.

| Setting | Default | Description |
|---|---|---|
| `host` | `127.0.0.1` | Bind address (**changed in 0.5.0** from `0.0.0.0` per the spec's bind-localhost guidance; set `"0.0.0.0"` explicitly for containers / external exposure) |
| `port` | `8000` | Listen port |
| `httpEndpoint` | `/mcp` | JSON-RPC endpoint path |
| `stateless` | `false` | Disable sessions and SSE |
| `sessionIdleTimeout` | `30 minutes` | Evict streamable sessions with no client activity (live GET streams are exempt); `None` disables |
| `keepAliveInterval` | `None` | When set, emit SSE heartbeats on quiet streams so proxies don't kill long calls |
| `allowedHosts` | `None` | DNS-rebinding guard: reject requests whose `Host`/`Origin` isn't in the set (403) |
| `loggingEnabled` | `false` | Advertise `logging` and wire `logging/setLevel` |
| `resourcesSubscribe` | `false` | Advertise `resources.subscribe` and wire subscribe/unsubscribe |

On the streamable transport, POST requests must send `Accept: application/json, text/event-stream` (replies stream as SSE) and only `initialize` may open a session — header-less non-initialize requests get `400`. Curl recipes are in [`HttpServer.scala`](fast-mcp-scala/jvm/src/com/tjclp/fastmcp/examples/HttpServer.scala).

## Tasks (experimental, off by default)

MCP Tasks (spec **2025-11-25**) wrap long-running `tools/call` invocations in a durable, polled state machine. Clients send `params.task: {ttl}`, get a `CreateTaskResult` immediately, and then poll `tasks/get` / `tasks/list` / `tasks/cancel` / `tasks/result` until completion. Useful for LLM batch jobs, expensive computation, and integrations with external job APIs that would otherwise time out under request/response.

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

`taskSupport` values: `"forbidden"` (default), `"optional"` (clients may augment), `"required"` (clients must — bare calls return `-32601`).

**Transport policy**: Tasks are native router middleware (no transport special-casing), so they work on any transport whose session outlives a single request:

- **Streamable HTTP** (`runHttp()`, the default) and **stdio** both support tasks, on JVM and Bun alike.
- **Stateless HTTP** does not — every client would share one task namespace — so `tasks.enabled` with `stateless = true` fails fast at startup with `IllegalStateException`.

Task ids come from the platform CSPRNG, a task that outlives its TTL is interrupted (not orphaned), and terminal results stay pollable until the TTL sweeps them. When enabled, the `tasks` capability is advertised at `initialize` and each opt-in tool surfaces `execution.taskSupport` on `tools/list`.

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
                   │  ├─ Session (per connection)         │
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

- The same native MCP **server runtime** on Bun — stdio (`runStdio`, Node stdin) and Streamable HTTP (`runHttp`, `Bun.serve`), with stateful (session + per-request SSE) and stateless (JSON-response-only) modes.
- Pluggable tool-argument validation via the shared `Validation.scala` seam (permissive by default on both platforms).
- The shared `McpContext` — `getClientInfo`, `getClientCapabilities`, `progressToken`, `sendLogMessage`, `sendProgress`, `listRoots`, `createMessage`, `elicit`, `elicitUrl` — identical on JVM and JS.

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
//> using dep com.tjclp::fast-mcp-scala_sjs1:0.5.0

import com.tjclp.fastmcp.{*, given}

object HelloBun extends McpServerApp[Stdio, HelloBun.type]:
  @Tool(name = Some("add"), description = Some("Add two numbers"), readOnlyHint = Some(true))
  def add(@Param("First operand") a: Int, @Param("Second operand") b: Int): Int = a + b
```

Same shape as the JVM — the `McpServerApp` trait picks up the shared `McpServerCoreFactory` given and builds the one shared `McpServer` over the Bun `TransportBackend`. For typed contracts on Scala.js, `McpTool[...]` auto-generates the input schema as well; import `sttp.tapir.generic.auto.*` at the call site the same way you do on the JVM.

Link with `./mill fast-mcp-scala.js.fastLinkJS`, then `bun run out/fast-mcp-scala/js/fastLinkJS.dest/main.js`. See [`HelloWorldJs.scala`](fast-mcp-scala/js/src/com/tjclp/fastmcp/examples/HelloWorldJs.scala) and [`HttpServerJs.scala`](fast-mcp-scala/js/src/com/tjclp/fastmcp/examples/HttpServerJs.scala) for runnable references.

## Spec coverage

fast-mcp-scala implements a focused subset of the [MCP specification](https://modelcontextprotocol.io/specification/):

| Capability | Status |
|---|---|
| Tools (list, call) + Tool Annotations/hints | ✅ |
| Structured tool output (`outputSchema` + `structuredContent` via `.withOutputSchema`) | ✅ |
| Static resources & resource templates | ✅ |
| Prompts with arguments | ✅ |
| `McpContext` (client info, capabilities, progress token) | ✅ |
| Stdio transport | ✅ |
| Streamable HTTP transport (sessions + per-request SSE) | ✅ |
| Stateless HTTP transport | ✅ |
| Progress notifications | ✅ |
| Sampling (`createMessage`, incl. `tools`/`toolChoice`) | ✅ |
| Elicitation (form + URL modes) | ✅ |
| Roots (`listRoots`) | ✅ |
| Completion (`completion/complete`) | ✅ |
| Resource subscriptions (`resources/subscribe`) | ✅ (opt-in) |
| Log level control (`logging/setLevel`) | ✅ (opt-in) |
| Cancellation (`notifications/cancelled`) | ✅ |
| Tasks (spec 2025-11-25, experimental) | ✅ (opt-in) |
| DNS-rebinding protection (`allowedHosts`) | ✅ (opt-in) |
| Header validation (`Accept`, `mcp-protocol-version`) | ✅ |
| Session idle eviction + SSE keepalives | ✅ |

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
        "//> using dep com.tjclp::fast-mcp-scala:0.5.0",
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
libraryDependencies += "com.tjclp" %% "fast-mcp-scala" % "0.5.0"
```

Or with Mill:

```scala 3 ignore
def ivyDeps = Agg(
  ivy"com.tjclp::fast-mcp-scala:0.5.0"
)
```

Or point `scala-cli` at a built JAR directly:

```scala 3 ignore
//> using scala 3.8.3
//> using jar "/absolute/path/to/out/fast-mcp-scala/jvm/jar.dest/out.jar"
//> using options "-Xcheck-macros" "-experimental"
```
