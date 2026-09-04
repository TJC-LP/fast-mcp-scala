# fast-mcp-scala

[![Maven Central](https://img.shields.io/maven-central/v/com.tjclp/fast-mcp-scala_3?label=Maven%20Central)](https://central.sonatype.com/artifact/com.tjclp/fast-mcp-scala_3)
[![CI](https://img.shields.io/github/actions/workflow/status/TJC-LP/fast-mcp-scala/ci.yml?branch=main&label=CI)](https://github.com/TJC-LP/fast-mcp-scala/actions/workflows/ci.yml)
[![Conformance](https://img.shields.io/github/actions/workflow/status/TJC-LP/fast-mcp-scala/conformance.yml?branch=main&label=Conformance)](https://github.com/TJC-LP/fast-mcp-scala/actions/workflows/conformance.yml)
[![Native Image](https://img.shields.io/github/actions/workflow/status/TJC-LP/fast-mcp-scala/native.yml?branch=main&label=Native%20Image)](https://github.com/TJC-LP/fast-mcp-scala/actions/workflows/native.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**Scala 3 for MCP: annotation-driven and typed-contract APIs on the JVM, Scala.js/Bun, and Scala Native.**

fast-mcp-scala is a developer-friendly library for building [Model Context Protocol](https://modelcontextprotocol.io/) servers. Extend one trait, declare your tools, done:

```scala 3 raw
object HelloWorld extends McpServerApp[Stdio, HelloWorld.type]:
  @Tool(name = Some("add"))
  def add(@Param("a") a: Int, @Param("b") b: Int): Int = a + b
```

Two registration paths, `@Tool`-style annotations and typed `McpTool` contracts, converge on the same backend. Built on **ZIO 2** and **zio-json**, with JSON Schemas derived directly by Scala 3 macros. The whole MCP protocol layer (JSON-RPC, wire types, router, transports) is native Scala 3 in `shared/`; there is no vendored SDK. It targets **MCP 2026-07-28** and keeps a compatibility adapter for earlier protocol revisions.

## Installation

```scala 3 ignore
// sbt — JVM
libraryDependencies += "com.tjclp" %% "fast-mcp-scala" % "1.0.0-RC3"

// sbt — Scala.js (Bun-first) or Scala Native (stdio only, experimental); %%% picks the platform artifact
libraryDependencies += "com.tjclp" %%% "fast-mcp-scala" % "1.0.0-RC3"

//> using dep com.tjclp::fast-mcp-scala:1.0.0-RC3    // scala-cli, JVM
//> using dep com.tjclp::fast-mcp-scala::1.0.0-RC3   // scala-cli, Scala.js or Native (with `//> using platform ...`)
```

Built against Scala 3.9.0 LTS; consumers compile with `-experimental` (the annotation macros require it). JVM: JDK 17+ (CI tests the LTS releases 17, 21, and 25). Scala.js: `sjs1_3`, runs on Bun (first-class) and Node 18+; Scala 3.9 output needs a 1.22+ linker. Scala Native: `native0.5_3`, stdio only, experimental. Platform details and quickstarts: [docs/platforms.md](docs/platforms.md).

## Quickstart

A single-file server with one tool; the same code lives in [`HelloWorld.scala`](fast-mcp-scala/shared/src/com/tjclp/fastmcp/examples/HelloWorld.scala):

```scala 3 raw
//> using scala 3.9.0
//> using dep com.tjclp::fast-mcp-scala:1.0.0-RC3
//> using options "-Xcheck-macros" "-experimental"

import com.tjclp.fastmcp.{*, given}

object HelloWorld extends McpServerApp[Stdio, HelloWorld.type]:

  @Tool(name = Some("add"), description = Some("Add two numbers"), readOnlyHint = Some(true))
  def add(@Param("First operand") a: Int, @Param("Second operand") b: Int): Int = a + b
```

No `import zio.*`, no `override def run`, no `ZIO.succeed(...)`. The `McpServerApp[T, Self]` trait handles server construction, annotation scanning, and transport lifecycle; the transport is a phantom type parameter (`Stdio` / `Http`) that selects the runner at compile time.

Exercise it through the MCP Inspector:

```bash
npx @modelcontextprotocol/inspector scala-cli scripts/quickstart.sc
```

## Choosing a registration path

| | Annotations (`@Tool` + `scanAnnotations`) | Typed contracts (`McpTool`) |
|---|---|---|
| Style | Methods on an object, discovered by macro | First-class `val`s |
| Schema | Derived from method signature & `@Param` | Derived from case-class fields & `@Param` |
| Testing | Call the method directly | Invoke `.handler` on the value |
| Composability | Whatever methods the object exposes | Collect into lists, generate from config |
| Best for | Quick servers, prototypes, single-module apps | Libraries, cross-module sharing, production codebases |

Both work on every platform and coexist on the same server: override `tools` / `prompts` / `staticResources` / `templateResources` on your `McpServerApp` to mount typed contracts alongside annotated methods.

```scala 3 raw
object MyServer extends McpServerApp[Stdio, MyServer.type]:
  @Tool(name = Some("ping")) def ping(): String = "pong"

  override val tools = List(
    McpTool[AddArgs, AddResult](name = "add") { args =>
      AddResult(args.a + args.b)            // plain value — auto-lifted
    }
  )
```

Handler lambdas return plain values, `ZIO`, `Either[Throwable, _]`, or `scala.util.Try`; the `ToHandlerEffect[F[_]]` typeclass picks the right lift, and you can bring your own given for other effect systems. See [`AnnotatedServer.scala`](fast-mcp-scala/shared/src/com/tjclp/fastmcp/examples/AnnotatedServer.scala) for the annotation path and [`ContractServer.scala`](fast-mcp-scala/shared/src/com/tjclp/fastmcp/examples/ContractServer.scala) for typed contracts.

## Tools and `@Param` metadata

Every tool parameter can carry metadata that flows into the derived JSON Schema:

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

- `description` populates the schema's `description` field
- `examples` populates the JSON Schema `examples` array (clients can show suggestions)
- `required = false`, combined with `Option[...]` or a default value, marks the field optional
- `schema` is a raw JSON Schema fragment that overrides the derived schema entirely

Enums, nested case classes, `Option`, collections, and `java.time` values derive with no user-supplied givens; custom wire shapes go through `McpInputCodec`. See [docs/custom-types.md](docs/custom-types.md).

## Tool hints

MCP Tool Annotations tell the client how a tool behaves. Set them on `@Tool`:

| Hint | Meaning |
|---|---|
| `title` | Human-readable display name (distinct from the wire-level `name`) |
| `readOnlyHint` | The tool only reads state; safe to call without confirmation |
| `destructiveHint` | The tool may irreversibly modify state; clients should confirm |
| `idempotentHint` | Repeated calls with the same args have the effect of one call |
| `openWorldHint` | The tool reaches outside the local process (network, filesystem, APIs) |
| `returnDirect` | Return the result directly to the user, skipping LLM post-processing |

[`TaskManagerServer.scala`](fast-mcp-scala/jvm/src/com/tjclp/fastmcp/examples/TaskManagerServer.scala) applies hints across a realistic tool set.

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

Return a `List[Message]`; fast-mcp-scala handles the MCP framing:

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

Add a `ctx: McpContext` parameter to an annotated method to read the client's declared info and capabilities, request metadata, and to send progress or logging:

```scala 3 raw
@Tool(name = Some("echo"), description = Some("Echo client and request context"))
def echo(
    @Param(description = "Optional note to include", required = false) note: Option[String],
    ctx: McpContext
): String =
  val clientName = ctx.getClientInfo.map(_.name).getOrElse("Unknown Client")
  s"Hello from $clientName${note.fold("")(n => s": $n")}"
```

Typed contracts use `McpTool.contextual`, whose handler receives `(In, Option[McpContext])`. Runnable demo: [`ContextEchoServer.scala`](fast-mcp-scala/shared/src/com/tjclp/fastmcp/examples/ContextEchoServer.scala).

## Transports

Transport is a phantom type parameter on `McpServerApp[T, Self]`: `Stdio` or `Http`.

### stdio (for Claude Desktop, MCP Inspector)

```scala 3 raw
object MyServer extends McpServerApp[Stdio, MyServer.type]:
  @Tool(...) def hello(name: String): String = s"Hello, $name!"
```

### HTTP (for remote clients, load balancers, test harnesses)

```scala 3 raw
object MyHttpServer extends McpServerApp[Http, MyHttpServer.type]:
  override def settings = McpServerSettings(port = 8090)

  @Tool(...) def hello(name: String): String = s"Hello, $name!"
```

For MCP 2026-07-28, `runHttp()` accepts one stateless JSON-RPC message per `POST /mcp`, answering with JSON or a request-scoped SSE stream. Older clients are served by a legacy initialize/session adapter that is on by default.

| Setting | Default | Description |
|---|---|---|
| `host` | `127.0.0.1` | Bind address; set `"0.0.0.0"` for containers or external exposure |
| `port` | `8000` | Listen port |
| `stateless` | `false` | Disable the legacy session adapter; modern requests are always stateless |

All settings, required request headers, error-code mapping, the legacy adapter, and lower-level construction without the sugar trait: [docs/transports.md](docs/transports.md).

## Native image (GraalVM)

Stdio servers compile to self-contained GraalVM binaries with **zero hand-written reachability metadata** (about 35 MB, instant startup, no JVM in the container): registration and schema derivation are compile-time macros, and the transport-seam split keeps zio-http/netty out of stdio-only images. HTTP servers compile too and pass the official conformance suite as a native binary in CI. Recipes, flags, and the metadata audit loop: [docs/native-image.md](docs/native-image.md).

## Platforms

One core, three targets. The protocol layer is shared; each platform contributes only a transport backend.

| | JVM | Scala.js / Bun | Scala Native (experimental) |
|---|---|---|---|
| Annotations, typed contracts, `McpServerApp` | ✅ | ✅ | ✅ |
| Stdio | ✅ | ✅ | ✅ (LLVM binary) |
| Streamable HTTP, MCP 2026-07-28 | ✅ ZIO HTTP | ✅ `Bun.serve` | ✗ by design¹ |
| Legacy HTTP session adapter | ✅ | ✅ | ✗ by design¹ |
| Tasks extension | ✅ | ✅ | ✅ (stdio) |
| Standalone binary | GraalVM native image | — | LLVM via Scala Native |

¹ zio-http has no Scala Native artifacts, so `McpServerApp[Http]` does not compile there; a socket-based backend is in progress ([#81](https://github.com/TJC-LP/fast-mcp-scala/issues/81)).

The official MCP conformance suite runs in CI against the JVM and Bun servers and against the GraalVM native binary, with empty expected-failure baselines. Full parity matrix, Bun and Scala Native quickstarts: [docs/platforms.md](docs/platforms.md); coverage details: [docs/spec-coverage.md](docs/spec-coverage.md).

## Documentation

- [docs/transports.md](docs/transports.md) — stdio, modern Streamable HTTP, the legacy adapter, every `McpServerSettings` field
- [docs/tasks.md](docs/tasks.md) — the experimental MCP Tasks extension
- [docs/custom-types.md](docs/custom-types.md) — `McpInputCodec`, `McpSchema`, `@Param(schema = ...)`, `McpTool.withSchema`
- [docs/platforms.md](docs/platforms.md) — parity matrix, running on Bun and Scala Native
- [docs/native-image.md](docs/native-image.md) — GraalVM recipes for stdio and HTTP servers
- [docs/spec-coverage.md](docs/spec-coverage.md) — MCP 2026-07-28 coverage matrix and how it is verified
- [docs/examples.md](docs/examples.md) — the example servers and how to run them
- [docs/architecture.md](docs/architecture.md) — how the library is put together
- [docs/2026-07-28-upgrade.md](docs/2026-07-28-upgrade.md) — wire behavior, review matrix, release gate ledgers
- [docs/native-core-design.md](docs/native-core-design.md) — design record for the native core
- [CHANGELOG.md](CHANGELOG.md) · [ROADMAP.md](ROADMAP.md) · [CONTRIBUTING.md](CONTRIBUTING.md) · [SECURITY.md](SECURITY.md) · [DEPENDENCY_POLICY.md](DEPENDENCY_POLICY.md)

## Claude Desktop integration

Add to `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "fast-mcp-scala-example": {
      "command": "scala-cli",
      "args": [
        "-e",
        "//> using dep com.tjclp::fast-mcp-scala:1.0.0-RC3",
        "--main-class",
        "com.tjclp.fastmcp.examples.AnnotatedServer"
      ]
    }
  }
}
```

> fast-mcp-scala example servers are for demo purposes only. They don't do anything useful, but they make it easy to see MCP in action.

## License

[MIT](LICENSE)
