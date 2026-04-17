# FastMCP-Scala

**Scala 3 for MCP: annotation-driven on the JVM, typed contracts everywhere they fit.**

FastMCP-Scala is a developer-friendly library for building [Model Context Protocol](https://modelcontextprotocol.io/) servers. It gives you two complementary paths to the same server:

- `@Tool`/`@Resource`/`@Prompt` annotations + `scanAnnotations[T]` for a zero-boilerplate, macro-driven experience (JVM)
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
- [Cross-platform (Scala.js)](#cross-platform-scalajs)
- [Spec coverage](#spec-coverage)
- [Running examples](#running-examples)
- [Claude Desktop integration](#claude-desktop-integration)
- [Developing locally](#developing-locally)

## Installation

```scala 3 ignore
libraryDependencies += "com.tjclp" %% "fast-mcp-scala" % "0.3.0"
```

Built against Scala 3.8.3. Requires JDK 17+.

## Quickstart

A single-file server with one tool — the same code lives in [`HelloWorld.scala`](fast-mcp-scala/src/com/tjclp/fastmcp/examples/HelloWorld.scala):

```scala 3 raw
//> using scala 3.8.3
//> using dep com.tjclp::fast-mcp-scala:0.3.0
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
| Platform | JVM only | JVM + Scala.js |
| Style | Methods on an object, discovered by macro | First-class `val`s |
| Schema | Derived from method signature & `@Param` | Derived from case-class fields & `@Param` |
| Testing | Call the method directly | Invoke `.handler` on the value |
| Composability | Whatever methods the object exposes | Collect into lists, generate from config |
| Best for | Quick servers, prototypes, single-module apps | Libraries, cross-module sharing, production codebases |

See [`AnnotatedServer.scala`](fast-mcp-scala/src/com/tjclp/fastmcp/examples/AnnotatedServer.scala) for the annotation path and [`ContractServer.scala`](fast-mcp-scala/src/com/tjclp/fastmcp/examples/ContractServer.scala) for typed contracts. Both can coexist on the same server.

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

Full demo in [`AnnotatedServer.scala`](fast-mcp-scala/src/com/tjclp/fastmcp/examples/AnnotatedServer.scala).

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

See [`TaskManagerServer.scala`](fast-mcp-scala/src/com/tjclp/fastmcp/examples/TaskManagerServer.scala) for hints across a realistic tool set.

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

Return a `List[Message]` — FastMCP-Scala handles the MCP framing:

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

Runnable demo: [`ContextEchoServer.scala`](fast-mcp-scala/src/com/tjclp/fastmcp/examples/ContextEchoServer.scala).

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

Curl recipes for both modes are in [`HttpServer.scala`](fast-mcp-scala/src/com/tjclp/fastmcp/examples/HttpServer.scala).

## Customizing decoding (Jackson 3)

FastMCP-Scala uses Jackson 3 to turn raw JSON-RPC arguments into Scala values. Primitives, Scala enums, case classes, `Option`, `List`, `Map`, and `java.time` types work out of the box — no configuration required.

For custom wire formats, supply a `given JacksonConverter[T]`:

```scala 3 raw
import java.time.LocalDateTime

given JacksonConverter[LocalDateTime] = JacksonConverter.fromPartialFunction[LocalDateTime] {
  case s: String => LocalDateTime.parse(s)
}

given JacksonConverter[Task] = DeriveJacksonConverter.derived[Task]
```

The handler receives a `JacksonConversionContext` (not a raw Jackson mapper) — see [`docs/jackson-converter-enhancements.md`](docs/jackson-converter-enhancements.md) for the detailed API.

## Cross-platform (Scala.js)

The `shared/src/` tree — annotations, types, managers, typed contracts — compiles to both JVM and Scala.js. That means `McpTool.derived[...]` values, `PromptArgument` / `ResourceArgument` metadata, and even custom `McpDecoder[T]` / `McpEncoder[A]` instances can live in a module that's cross-published.

**What this buys you**: share tool/prompt/resource *definitions* across your JVM server and any Scala.js code (clients, test harnesses, browser UIs) without duplication or type drift.

**What it doesn't buy you**: the server *runtime* is JVM-only. The Scala.js module in this project is a test harness — it uses the TypeScript MCP SDK (via Scala.js facades) as a client to exercise a JVM-side server over stdio. There is no Bun/Node-based MCP server transport.

Conformance proof: [`SharedContractSurfaceTest.scala`](fast-mcp-scala/js/test/src/com/tjclp/fastmcp/contracts/SharedContractSurfaceTest.scala) asserts the typed contracts compile and mount under Scala.js; [`ConformanceTest.scala`](fast-mcp-scala/js/test/src/com/tjclp/fastmcp/conformance/ConformanceTest.scala) runs 17 MCP operations against a JVM server from a Scala.js client.

## Spec coverage

FastMCP-Scala implements a focused subset of the [MCP specification](https://modelcontextprotocol.io/specification/):

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

All examples live under [`fast-mcp-scala/src/com/tjclp/fastmcp/examples/`](fast-mcp-scala/src/com/tjclp/fastmcp/examples/):

| Example | Demonstrates |
|---|---|
| `HelloWorld.scala` | Minimum viable server — one tool, stdio |
| `AnnotatedServer.scala` | Flagship annotation path — tools, hints, `@Param` features, resources, prompts |
| `ContractServer.scala` | Typed contracts as first-class values; cross-platform story |
| `TaskManagerServer.scala` | Realistic domain server — custom Jackson converters, hints across a CRUD-style surface |
| `ContextEchoServer.scala` | `McpContext` introspection inside a tool handler |
| `HttpServer.scala` | HTTP transport (Streamable default, Stateless via a flag) with curl recipes |

Run any of them via Mill:

```bash
./mill fast-mcp-scala.runMain com.tjclp.fastmcp.examples.HelloWorld
```

Or through `scala-cli` directly from the quickstart script:

```bash
scala-cli scripts/quickstart.sc
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
        "//> using dep com.tjclp::fast-mcp-scala:0.3.0",
        "--main-class",
        "com.tjclp.fastmcp.examples.AnnotatedServer"
      ]
    }
  }
}
```

> FastMCP-Scala example servers are for demo purposes only — they don't do anything useful, but they make it easy to see MCP in action.

For architectural detail, see [`docs/architecture.md`](docs/architecture.md).

## License

[MIT](LICENSE)

---

## Developing locally

### Build commands (Mill)

```bash
./mill fast-mcp-scala.compile                                   # Compile
./mill fast-mcp-scala.test                                      # JVM tests
./mill fast-mcp-scala.js.test.bunTest                           # Scala.js conformance tests
./mill fast-mcp-scala.test + fast-mcp-scala.js.test.bunTest     # Both
./mill fast-mcp-scala.checkFormat                               # CI format check
./mill fast-mcp-scala.reformat                                  # Auto-format
./mill fast-mcp-scala.publishLocal                              # Publish to ~/.ivy2/local
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
//> using jar "/absolute/path/to/out/fast-mcp-scala/jar.dest/out.jar"
//> using options "-Xcheck-macros" "-experimental"
```
