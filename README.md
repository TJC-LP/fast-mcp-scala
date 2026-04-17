# fast-mcp-scala

**Scala 3 for MCP: annotation-driven and typed-contract APIs on both JVM and Scala.js/Bun.**

fast-mcp-scala is a developer-friendly library for building [Model Context Protocol](https://modelcontextprotocol.io/) servers. It gives you two complementary paths to the same server:

- `@Tool`/`@Resource`/`@Prompt` annotations + `scanAnnotations[T]` for a zero-boilerplate, macro-driven experience (JVM + Scala.js/Bun)
- `McpTool.derived[...]`, `McpPrompt`, `McpStaticResource`, `McpTemplateResource` for first-class, testable, cross-platform contract values (JVM + Scala.js)

Built on **ZIO 2**, **Tapir**-derived schemas, **Jackson 3**, and the official **Java MCP SDK 1.1.1**. Runs under **stdio** or the full **Streamable HTTP** spec with a single flag.

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
- [Customizing decoding (Jackson 3)](#customizing-decoding-jackson-3)
- [Two backends, one API](#two-backends-one-api)
- [Spec coverage](#spec-coverage)
- [Running examples](#running-examples)
- [Claude Desktop integration](#claude-desktop-integration)
- [Developing locally](#developing-locally)

## Installation

```scala 3 ignore
// JVM — Java SDK-backed runtime with annotations, derived schemas, HTTP + stdio transports.
libraryDependencies += "com.tjclp" %% "fast-mcp-scala" % "0.3.0-rc1"

// Scala.js — TS SDK-backed runtime on Bun/Node + the same annotation and typed-contract APIs.
libraryDependencies += "com.tjclp" %%% "fast-mcp-scala" % "0.3.0-rc1"
```

Built against Scala 3.8.3. JVM requires JDK 17+. Scala.js artifact is published for `sjs1_3` (Scala.js 1.x); runs on Bun (first-class) and Node 18+.

## Quickstart

A single-file server with one tool — the same code lives in [`HelloWorld.scala`](fast-mcp-scala/jvm/src/com/tjclp/fastmcp/examples/HelloWorld.scala):

```scala 3 raw
//> using scala 3.8.3
//> using dep com.tjclp::fast-mcp-scala:0.3.0-rc1
//> using options "-Xcheck-macros" "-experimental"

import com.tjclp.fastmcp.*
import zio.*

object HelloWorld extends ZIOAppDefault:

  @Tool(name = Some("add"), description = Some("Add two numbers"), readOnlyHint = Some(true))
  def add(@Param("First operand") a: Int, @Param("Second operand") b: Int): Int = a + b

  override def run =
    for
      server <- ZIO.succeed(McpServer("HelloWorld", "0.1.0"))
      _      <- ZIO.attempt(server.scanAnnotations[HelloWorld.type])
      _      <- server.runStdio()
    yield ()
```

Exercise it through the MCP Inspector:

```bash
npx @modelcontextprotocol/inspector scala-cli scripts/quickstart.sc
```

## Choosing a registration path

| | Annotations (`@Tool` + `scanAnnotations`) | Typed contracts (`McpTool.derived`) |
|---|---|---|
| Platform | JVM + Scala.js/Bun | JVM + Scala.js/Bun |
| Style | Methods on an object, discovered by macro | First-class `val`s |
| Schema | Derived from method signature & `@Param` | Derived from case-class fields & `@Param` |
| Testing | Call the method directly | Invoke `.handler` on the value |
| Composability | Whatever methods the object exposes | Collect into lists, generate from config |
| Best for | Quick servers, prototypes, single-module apps | Libraries, cross-module sharing, production codebases |

See [`AnnotatedServer.scala`](fast-mcp-scala/jvm/src/com/tjclp/fastmcp/examples/AnnotatedServer.scala) for the annotation path and [`ContractServer.scala`](fast-mcp-scala/jvm/src/com/tjclp/fastmcp/examples/ContractServer.scala) for typed contracts. Both can coexist on the same server.

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

Pick your transport at run time — the tool/resource/prompt surface is identical.

### stdio (for Claude Desktop, MCP Inspector)

```scala 3 raw
server.runStdio()
```

### HTTP (for remote clients, load balancers, test harnesses)

`runHttp()` serves the full MCP Streamable HTTP spec: `POST /mcp` for JSON-RPC, the `mcp-session-id` header for session tracking, and SSE streams for long-running calls.

```scala 3 raw
val server = McpServer(
  "HttpServer",
  "0.1.0",
  settings = FastMcpServerSettings(port = 8090)
)
server.runHttp()
```

Toggle `stateless = true` on `FastMcpServerSettings` for request/response-only mode (no sessions, no SSE), useful behind load balancers.

| Setting | Default | Description |
|---|---|---|
| `host` | `0.0.0.0` | Bind address |
| `port` | `8000` | Listen port |
| `httpEndpoint` | `/mcp` | JSON-RPC endpoint path |
| `stateless` | `false` | Disable sessions and SSE |

Curl recipes for both modes are in [`HttpServer.scala`](fast-mcp-scala/jvm/src/com/tjclp/fastmcp/examples/HttpServer.scala).

## Customizing decoding (Jackson 3)

fast-mcp-scala uses Jackson 3 to turn raw JSON-RPC arguments into Scala values. Primitives, Scala enums, case classes, `Option`, `List`, `Map`, and `java.time` types work out of the box — no configuration required.

For custom wire formats, supply a `given JacksonConverter[T]`:

```scala 3 raw
import java.time.LocalDateTime

given JacksonConverter[LocalDateTime] = JacksonConverter.fromPartialFunction[LocalDateTime] {
  case s: String => LocalDateTime.parse(s)
}

given JacksonConverter[Task] = DeriveJacksonConverter.derived[Task]
```

The handler receives a `JacksonConversionContext` (not a raw Jackson mapper) — see [`docs/jackson-converter-enhancements.md`](docs/jackson-converter-enhancements.md) for the detailed API.

## Two backends, one API

fast-mcp-scala is a single library with two real runtime backends — JVM and Scala.js/Bun — behind the same shared abstract API:

```
         shared/src/                (platform-neutral Scala 3)
  ┌──────────────────────────────────────────────────────────┐
  │  annotations  │  typed contracts  │  Tool/Prompt/Resource │
  │  (@Tool, ...)│ (McpTool.derived)│  managers + McpContext │
  └──────────┬─────────────────────────────┬─────────────────┘
             │                             │
       jvm/src/ (FastMcpServer)      js/src/ (JsMcpServer)
       wraps Java MCP SDK            wraps TS MCP SDK via
       (mcp-core 1.1.1)              Scala.js facades, runs on Bun
```

`McpServer("name", "0.1.0")` returns the platform-appropriate server on each target. Typed contracts (`McpTool.derived`, `McpPrompt`, `McpStaticResource`, `McpTemplateResource`) compile and mount unchanged on both.

**What the Scala.js backend gives you**:

- A real MCP **server runtime** on Bun, wrapping the official `@modelcontextprotocol/sdk` — stdio (`runStdio`) and Streamable HTTP (`runHttp`) transports, with stateful (session + SSE) and stateless (JSON-response-only) modes.
- AJV-based schema validation of tool arguments, matching the JVM server's behaviour.
- `JsMcpContext` extension methods (`getClientInfo`, `getClientCapabilities`, `getSessionId`) for handlers that need client-session details.

**Current platform parity**:

| Capability | JVM | Scala.js (Bun-first) |
|---|---|---|
| `@Tool` / `@Resource` / `@Prompt` + `scanAnnotations[T]` | ✅ | ✅ |
| Typed contracts (`McpTool.derived`, `McpPrompt`, `McpStaticResource`, `McpTemplateResource`) | ✅ | ✅ |
| `ToolSchemaProvider[A]` auto-derivation from `@Param` | ✅ via Tapir | ✅ via Tapir |
| Stdio transport | ✅ (Java SDK) | ✅ (TS SDK) |
| Streamable HTTP — stateful (sessions + SSE) | ✅ (ZIO HTTP) | ✅ (Bun.serve + Web-Standard transport) |
| Streamable HTTP — stateless | ✅ | ✅ |
| Custom decoders | ✅ `JacksonConverter` | ✅ `given JsonDecoder[T] → McpDecoder[T]` via zio-json |

Node / Deno parity for the HTTP listener is a follow-up; the same `WebStandardStreamableHTTPServerTransport` works across runtimes, only the `Bun.serve(...)` entry point is Bun-specific today.

Proof: the conformance suite at [`JsServerConformanceTest.scala`](fast-mcp-scala/js/test/src/com/tjclp/fastmcp/conformance/JsServerConformanceTest.scala) stands up a `JsMcpServer` in-process and drives every MCP operation through the official TS SDK client via `InMemoryTransport`; [`JsServerHttpTest.scala`](fast-mcp-scala/js/test/src/com/tjclp/fastmcp/conformance/JsServerHttpTest.scala) verifies the Bun HTTP routing; [`ConformanceTest.scala`](fast-mcp-scala/js/test/src/com/tjclp/fastmcp/conformance/ConformanceTest.scala) runs a JS client against the JVM server for cross-backend parity.

### Running on Bun

```scala 3 raw
//> using scala 3.8.3
//> using dep com.tjclp::fast-mcp-scala_sjs1:0.3.0

import com.tjclp.fastmcp.*
import zio.*

object HelloBun extends ZIOAppDefault:
  @Tool(name = Some("add"), description = Some("Add two numbers"), readOnlyHint = Some(true))
  def add(@Param("First operand") a: Int, @Param("Second operand") b: Int): Int = a + b

  override def run =
    for
      server <- ZIO.succeed(McpServer("HelloBun", "0.1.0"))
      _ <- ZIO.attempt(server.scanAnnotations[HelloBun.type])
      _ <- server.runStdio()
    yield ()
```

For typed contracts on Scala.js, `McpTool.derived[...]` now auto-generates the input schema as well; import `sttp.tapir.generic.auto.*` at the call site the same way you do on the JVM.

Link with `./mill fast-mcp-scala.js.fastLinkJS`, then `bun run out/fast-mcp-scala/js/fastLinkJS.dest/main.js`. See [`HelloWorldJs.scala`](fast-mcp-scala/js/src/com/tjclp/fastmcp/examples/HelloWorldJs.scala) and [`HttpServerJs.scala`](fast-mcp-scala/js/src/com/tjclp/fastmcp/examples/HttpServerJs.scala) for runnable references.

## Spec coverage

fast-mcp-scala implements a focused subset of the [MCP specification](https://modelcontextprotocol.io/specification/):

| Capability | Status |
|---|---|
| Tools (list, call) + Tool Annotations/hints | ✅ |
| Static resources & resource templates | ✅ |
| Prompts with arguments | ✅ |
| `McpContext` (client info, capabilities) | ✅ |
| Stdio transport | ✅ |
| Streamable HTTP transport (sessions + SSE) | ✅ |
| Stateless HTTP transport | ✅ |
| Progress notifications | ❌ (not yet) |
| Sampling | ❌ (not yet) |
| Elicitation | ❌ (not yet) |
| Completion | ❌ (not yet) |
| Resource subscriptions | ❌ (not yet) |
| Log level control | ❌ (not yet) |

See the [CHANGELOG](CHANGELOG.md) for release-by-release changes.

## Running examples

**JVM** — [`fast-mcp-scala/jvm/src/com/tjclp/fastmcp/examples/`](fast-mcp-scala/jvm/src/com/tjclp/fastmcp/examples/):

| Example | Demonstrates |
|---|---|
| `HelloWorld.scala` | Minimum viable server — one tool, stdio |
| `AnnotatedServer.scala` | Flagship annotation path — tools, hints, `@Param` features, resources, prompts |
| `ContractServer.scala` | Typed contracts as first-class values; cross-platform story |
| `TaskManagerServer.scala` | Realistic domain server — custom Jackson converters, hints across a CRUD-style surface |
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
        "//> using dep com.tjclp::fast-mcp-scala:0.3.0-rc1",
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
libraryDependencies += "com.tjclp" %% "fast-mcp-scala" % "0.3.0-SNAPSHOT"
```

Or with Mill:

```scala 3 ignore
def ivyDeps = Agg(
  ivy"com.tjclp::fast-mcp-scala:0.3.0-SNAPSHOT"
)
```

Or point `scala-cli` at a built JAR directly:

```scala 3 ignore
//> using scala 3.8.3
//> using jar "/absolute/path/to/out/fast-mcp-scala/jvm/jar.dest/out.jar"
//> using options "-Xcheck-macros" "-experimental"
```
