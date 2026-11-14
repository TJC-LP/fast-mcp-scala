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
libraryDependencies += "com.tjclp" %% "fast-mcp-scala" % "1.0.1"

// sbt — Scala.js (Bun-first) or Scala Native (experimental); %%% picks the platform artifact
libraryDependencies += "com.tjclp" %%% "fast-mcp-scala" % "1.0.1"

//> using dep com.tjclp::fast-mcp-scala:1.0.1    // scala-cli, JVM
//> using dep com.tjclp::fast-mcp-scala::1.0.1   // scala-cli, Scala.js or Native (with `//> using platform ...`)
```

Built against Scala 3.9.0 LTS: **consuming 1.0.0 requires Scala 3.9.0 or newer** (it emits TASTy 28.9, which 3.8 and older compilers cannot read; the RC3 prerelease, built with Scala 3.8.3, is the last release a Scala 3.8 project can use). 1.0.0 is not compiled with `-experimental`, so consumers no longer need the flag (the release candidates required it on every registration path; TJC-2335). JVM: JDK 17+ (CI tests the LTS releases 17, 21, and 25). Scala.js: `sjs1_3`, runs on Bun (first-class) and, for stdio, on Node 18+ (verified with Node 18 and 26 in the 1.0.0 dogfood, not yet in CI; the HTTP listener is `Bun.serve`-only, and bearer task ids come from `globalThis.crypto`, which Node 18 exposes only behind `--experimental-global-webcrypto`); Scala 3.9 output needs a Scala.js 1.22+ linker (Mill: mill-bun 0.3.x with an explicit `scalaJSVersion`; scala-cli: `--js-version 1.22.0`). Scala Native: `native0.5_3`, experimental (stdio in 1.0.x; streamable HTTP is on `main`, unreleased). Platform details and quickstarts: [docs/platforms.md](docs/platforms.md).

## Quickstart

A single-file server with one tool; the same code lives in [`HelloWorld.scala`](fast-mcp-scala/shared/src/com/tjclp/fastmcp/examples/HelloWorld.scala):

```scala 3 raw
//> using scala 3.9.0
//> using dep com.tjclp::fast-mcp-scala:1.0.1

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

The first run downloads the compiler and dependencies (about 60 MB) and compiles the script, which
can exceed the Inspector's 15 s connect timeout (it then reports only `Connection closed`). Run
`scala-cli compile scripts/quickstart.sc` once beforehand, or pass `--connect-timeout <ms>` to the
Inspector.

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
case class AddArgs(a: Int, b: Int)
case class AddResult(sum: Int)

object MyServer extends McpServerApp[Stdio, MyServer.type]:
  @Tool(name = Some("ping")) def ping(): String = "pong"

  override val tools = List(
    McpTool[AddArgs, AddResult](name = "add") { args =>
      AddResult(args.a + args.b)            // plain value — auto-lifted
    }
  )
```

A typed tool's `In` is a case class (its schema must be a JSON object, as MCP `arguments` always are); a tool with no arguments takes `Unit` (`McpTool[Unit, String]`) or an empty case class. Handler lambdas return plain values, `ZIO`, `Either[Throwable, _]`, or `scala.util.Try`; the `ToHandlerEffect[F[_], R]` typeclass picks the right lift (`R` is the ZIO environment — `Any` for the plain builders), and you can bring your own given for other effect systems by implementing `ToHandlerEffect[F, Any]` for your effect type `F`. See [`AnnotatedServer.scala`](fast-mcp-scala/shared/src/com/tjclp/fastmcp/examples/AnnotatedServer.scala) for the annotation path and [`ContractServer.scala`](fast-mcp-scala/shared/src/com/tjclp/fastmcp/examples/ContractServer.scala) for typed contracts.

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
- `required` is derived from the parameter: an `Option[...]` parameter and a parameter with a default value are optional, every other parameter is required. A client may omit an optional argument; the method then gets `None` or the default. The same holds for the fields of a case class used as an argument, at any depth. Write `required = true` to require an `Option` or defaulted parameter anyway. `required = false` is accepted only on an `Option` or defaulted parameter, where it states what is already the case
- `required` lists the parameters in declaration order, the order of `properties`
- `schema` is a raw JSON Schema fragment that overrides the derived schema entirely — repeat the `description` inside the fragment, since the derived property (description and examples included) is replaced wholesale

The advertised `inputSchema` is enforced: a `tools/call` whose arguments do not conform (a value outside an `enum`, a number outside its bounds, a missing `required` member, a wrong type) is answered with an `isError` result before the method runs. `pattern` and `format` are among the keywords that are advertised but not evaluated; the full list, the opt-outs (`McpServerSettings(validateToolInput = false)`, per-tool `ToolRegistrationOptions(validateInputSchema = false)`) and `server.toolInputValidation` are in [docs/custom-types.md](docs/custom-types.md#what-the-server-enforces).

An annotated method's result is sent as `TextContent(result.toString)` unless it is a `String`, a `Content`, a `List[Content]`, an `Array[Byte]` (or a `ZIO` of one of those): a `Some(1)`, a case class or a `Map` arrives as Scala's `toString` text, not JSON. Return a `String`/`Content`, or use a typed `McpTool` (with `.withOutputSchema` for `structuredContent`) when clients need JSON.

Overloading is fine: only the annotated overload is registered, and its schema and handler come from that exact declaration; two annotated overloads must register distinct `name`s — duplicate names or resource URI patterns within one object are a compile-time error. Annotation arguments such as `name`, `description` and the hints must be literals (`Some("...")`, `Option("...")`, `None`, or a `final val` constant); anything else is a compile-time error.

An annotated method has exactly one parameter list (no currying, no `using` clauses), no type parameters, and at most 22 parameters, and only members declared directly on the scanned object are registered (`private`/`protected` included — visibility does not gate exposure, so annotate only what you mean to publish). An annotated inherited member or `val`, like any other unsupported shape, is a compile-time error naming it and the fix; an annotated method inside a nested object is reported as well (an error when the scanned object declares nothing of its own, otherwise a warning, since the nested object may be scanned separately).

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
def userProfile(@Param("The user id") userId: String): String =
  s"""{"userId":"$userId"}"""
```

Templates are listed through `resources/templates/list` only when `exposeTemplatesEndpoint` is set
(`resources/list` never lists templates; with the default `false` the templates endpoint answers an
empty page and clients derive templates from `{}` URIs; `resources/read` on a matching URI works
either way). [`AnnotatedServer.scala`](fast-mcp-scala/shared/src/com/tjclp/fastmcp/examples/AnnotatedServer.scala)
turns it on:

```scala 3 raw
override def settings = McpServerSettings(exposeTemplatesEndpoint = true)
```

A placeholder matches a non-empty run of characters within one path segment (never `/`); literal text is matched verbatim (not as a regex); placeholders in the same segment must be separated by literal text (a template such as `x://{a}{b}` is rejected at registration, so the server fails to start). Client URIs longer than `limits.maxUriChars` (8192) are rejected with `-32602`.

## Skills (`io.modelcontextprotocol/skills`)

Publish [Agent Skills](https://agentskills.io/specification) alongside tools, resources and prompts.
A skill is a `SKILL.md` (YAML frontmatter + Markdown) plus supporting files; the server serves every
file as an ordinary resource, answers `skills/list` / `skills/get` with a complete manifest (verbatim
frontmatter, `sha256:` digest and byte size per file) and browses directories with
`resources/directory/read`. Cross-platform, every transport:

```scala 3 raw
object SkillsServer extends McpServerApp[Stdio, SkillsServer.type]:
  override val skills: List[McpSkill] = List(
    McpSkill.fromMarkdown(
      skillPath = "acme/reconcile-positions",
      markdown = """---
                   |name: reconcile-positions
                   |description: Reconcile broker positions against the ledger and flag breaks.
                   |---
                   |Read `references/rules.md` first.
                   |""".stripMargin,
      files = Map(
        "references/rules.md" -> SkillFile.text("# Rules\n"),
        "assets/example.bin" -> SkillFile.binary(Array[Byte](0, 1, 2))
      )
    )
  )
```

Registering a skill declares the extension (`{"directoryRead": true}`) and the `resources`
capability; `SkillSettings(enabled = true)` serves an empty catalog. Dynamic catalogs implement
`SkillProvider`; on the JVM, `SkillDirectoryLoader` publishes an on-disk skill directory (regular
files only, symlinks refused). Digests prove consistency, not trust — the host still verifies,
approves and decides what to activate. Full guide: [docs/skills.md](docs/skills.md); pinned spec
revisions and the requirement-to-test matrix: [docs/skills-conformance.md](docs/skills-conformance.md).

## Prompts

Return a `List[Message]`; fast-mcp-scala handles the MCP framing:

```scala 3 raw
@Prompt(name = Some("greeting"), description = Some("Personalized greeting"))
def greeting(
    @Param("Name of the person") name: String,
    @Param("Optional title") title: String = ""
): List[Message] =
  List(Message(Role.User, TextContent(s"Generate a warm greeting for $title $name.")))
```

A prompt that returns a single `String` is automatically wrapped into a `User` message. An argument with a default value (`title` above) or of type `Option[...]` is advertised as optional; the others are required.

## Context (`McpContext`)

Add a parameter named exactly `ctx` of type `McpContext` to a `@Tool` method to read the client's declared info and capabilities, request metadata, and to send progress or logging (the parameter is recognised by its name and is not part of the tool's schema; `@Prompt` and `@Resource` methods do not take a context parameter):

```scala 3 raw
@Tool(name = Some("echo"), description = Some("Echo client and request context"))
def echo(
    @Param(description = "Optional note to include", required = false) note: Option[String],
    ctx: McpContext
): String =
  val clientName = ctx.getClientInfo.map(_.name).getOrElse("Unknown Client")
  s"Hello from $clientName${note.fold("")(n => s": $n")}"
```

Client-visible logging goes through the context too: `ctx.sendLogMessage(level, data)` returns a
`ZIO` (so the handler returns one), and `LoggingLevel` (in `com.tjclp.fastmcp.core`) is root-exported
since 1.0.0 — the release candidates needed it imported by name, which still works:

```scala 3 raw
import zio.*
import zio.json.ast.Json
import com.tjclp.fastmcp.core.LoggingLevel   // optional since 1.0.0 (root-exported); the release candidates need it

@Tool(name = Some("echo_logged"), description = Some("Echo the note and log it"))
def echoLogged(@Param("Note to echo") note: String, ctx: McpContext): ZIO[Any, Throwable, String] =
  ctx.sendLogMessage(LoggingLevel.Info, Json.Str(s"echo: $note")).as(note)
```

Behind an authenticating proxy, a handler can read the identity the proxy forwards. List the header
names in `McpServerSettings.contextHeaders` (nothing is exposed unless listed) and read them with
`ctx.header(name)` / `ctx.headers`; `ctx.protocolSessionId` is the `Mcp-Session-Id` of a legacy
session and `None` everywhere else:

```scala 3 raw
object MyServer extends McpServerApp[Http, MyServer.type]:
  override def settings = McpServerSettings(
    contextHeaders = Set("x-forwarded-email", "x-forwarded-access-token")
  )

  @Tool(name = Some("whoami"), description = Some("Who is calling"))
  def whoami(ctx: McpContext): String =
    ctx.header("x-forwarded-email").getOrElse("anonymous")
```

The values are whatever the request carried, read per request and never logged by the library; they
are trustworthy only when the proxy sets or strips those headers
([details](docs/transports.md#request-headers-in-handlers-contextheaders)).

Typed contracts use the builder's `.contextual` — `McpTool[In, Out](name = "echo").contextual { (in, ctx) => ... }` — whose handler receives `(In, Option[McpContext])`. Runnable demo: [`ContextEchoServer.scala`](fast-mcp-scala/shared/src/com/tjclp/fastmcp/examples/ContextEchoServer.scala).

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
| `allowedHosts` | `None` (automatic) | `Host` names accepted by the Host/Origin guard; on a loopback bind the loopback names are allowed automatically |

**Host / Origin validation.** The server above binds loopback, and binding loopback alone does not protect against browser-originated requests: a web page open on the same machine can still reach the port. So a loopback server checks every request with no configuration — a `Host` that is not `localhost` / `127.0.0.1` / `[::1]`, or an `Origin` that is not the server's own, is refused with 403. When you bind a non-loopback address (`host = "0.0.0.0"`) nothing can be inferred, so the guard is off and startup logs a warning: set `allowedHosts` (and `allowedOrigins` for browser front-ends on another origin) and put an authenticating proxy in front. A loopback listener behind a reverse proxy that forwards its own `Host` lists that name in `allowedHosts`; `dnsRebindingProtection = false` is the explicit opt-out. Details: [docs/transports.md](docs/transports.md#host--origin-validation).

All settings, required request headers, error-code mapping, the legacy adapter, and lower-level construction without the sugar trait: [docs/transports.md](docs/transports.md).

## Native image (GraalVM)

Stdio servers compile to self-contained GraalVM binaries with **zero hand-written reachability metadata** (about 35 MB, instant startup, no JVM in the container): registration and schema derivation are compile-time macros, and the transport-seam split keeps zio-http/netty out of stdio-only images — exclude `dev.zio:zio-http_3` from the dependency (netty arrives only through zio-http, so that single exclusion sheds both). HTTP servers compile too and pass the official conformance suite as a native binary in CI. Recipes, flags, and the metadata audit loop: [docs/native-image.md](docs/native-image.md).

## Platforms

One core, three targets. The protocol layer is shared; each platform contributes only a transport backend.

| | JVM | Scala.js / Bun | Scala Native (experimental) |
|---|---|---|---|
| Annotations, typed contracts, `McpServerApp` | ✅ | ✅ | ✅ |
| Stdio | ✅ | ✅ | ✅ (LLVM binary) |
| Streamable HTTP, MCP 2026-07-28 | ✅ ZIO HTTP | ✅ `Bun.serve` | ✅ built-in socket server¹ |
| Legacy HTTP session adapter | ✅ | ✅ | ✅¹ |
| Tasks extension | ✅ | ✅ | ✅ |
| Standalone binary | GraalVM native image | — | LLVM via Scala Native |

¹ zio-http has no Scala Native artifacts, so Scala Native serves HTTP through a built-in HTTP/1.1 + SSE server over `java.net.ServerSocket` (plaintext; terminate TLS at a proxy). It is unreleased (on `main`, after 1.0.1) and **experimental**: the backend is marked `@experimental`, so an HTTP server on Scala Native compiles only with the `-experimental` compiler flag (stdio servers need nothing). The reason is a Scala Native 0.5.12 runtime defect that can abort the process under concurrent payloads of 8 KiB and up — see [the socket backend](docs/transports.md#the-socket-backend). The same server is an opt-in, netty-free HTTP backend on the JVM.

The official MCP conformance suite runs in CI against the JVM, Bun and Scala Native servers and against the GraalVM native binary, with empty expected-failure baselines. Full parity matrix, Bun and Scala Native quickstarts: [docs/platforms.md](docs/platforms.md); coverage details: [docs/spec-coverage.md](docs/spec-coverage.md).

## Documentation

- [docs/transports.md](docs/transports.md) — stdio, modern Streamable HTTP, the legacy adapter, every `McpServerSettings` field
- [docs/tasks.md](docs/tasks.md) — the experimental MCP Tasks extension
- [docs/skills.md](docs/skills.md) — the Skills extension (`io.modelcontextprotocol/skills`, SEP-2640): authoring, URIs, digests, providers, directory reads, host responsibilities
- [docs/skills-conformance.md](docs/skills-conformance.md) — pinned spec revisions, requirement-to-test matrix, upstream conformance run
- [docs/custom-types.md](docs/custom-types.md) — `McpInputCodec`, `McpSchema`, `@Param(schema = ...)`, `McpTool.withSchema`
- [docs/platforms.md](docs/platforms.md) — parity matrix, running on Bun and Scala Native
- [docs/native-image.md](docs/native-image.md) — GraalVM recipes for stdio and HTTP servers
- [docs/spec-coverage.md](docs/spec-coverage.md) — MCP 2026-07-28 coverage matrix and how it is verified
- [docs/examples.md](docs/examples.md) — the example servers and how to run them
- [docs/architecture.md](docs/architecture.md) — how the library is put together
- [docs/upgrading.md](docs/upgrading.md) — moving a 0.x or release-candidate project to 1.0.0, organised by compiler error
- [MCP 2026-07-28 protocol upgrade](docs/2026-07-28-upgrade.md) — wire behavior, review matrix, release gate ledgers
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
        "//> using scala 3.9.0",
        "-e",
        "//> using dep com.tjclp::fast-mcp-scala:1.0.1",
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
