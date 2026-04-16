# FastMCP-Scala

A high‑level, developer‑friendly **Scala 3** library for building Model Context Protocol (MCP) servers.

Features
- ZIO‑based effect handling and async support
- Annotation‑driven API (`@Tool`, `@Resource`, `@Prompt`)
- Automatic JSON Schema & handler generation via Scala 3 macros
- **Two transports** — `runStdio()` or `runHttp()` (streamable by default, `stateless = true` for lightweight mode)
- Seamless integration with the Java MCP SDK 1.0.0

## Installation

Add to your **`build.sbt`** (defaulting to **Scala 3.7.2**):

```scala 3 ignore
libraryDependencies += "com.tjclp" %% "fast-mcp-scala" % "0.2.3"
```

## Quickstart

```scala 3 raw
//> using scala 3.7.2
//> using dep com.tjclp::fast-mcp-scala:0.2.3
//> using options "-Xcheck-macros" "-experimental"

import com.tjclp.fastmcp.*
import zio.*

// Define annotated tools, prompts, and resources
object Example:
    @Tool(name = Some("add"), description = Some("Add two numbers"))
    def add(
             @Param("First operand") a: Double,
             @Param("Second operand") b: Double
           ): Double = a + b
    
    @Prompt(name = Some("greet"), description = Some("Generate a greeting message"))
    def greet(@Param("Name to greet") name: String): String =
      s"Hello, $name!"
    
    @Resource(uri = "file://test", description = Some("Test resource"))
    def test(): String = "This is a test"
    
    @Resource(uri = "user://{userId}", description = Some("Test resource"))
    def getUser(@Param("The user id") userId: String): String = s"User ID: $userId"

object ExampleServer extends ZIOAppDefault:
    override def run =
      for
        server <- ZIO.succeed(FastMcpServer("ExampleServer", "0.2.0"))
        _ <- ZIO.attempt(server.scanAnnotations[Example.type])
        _ <- server.runStdio()
      yield ()
```

For most use cases, `import com.tjclp.fastmcp.*` is sufficient. If you need the default `JacksonConverter`
instances in scope explicitly, use `import com.tjclp.fastmcp.{given, *}`.

Low-level custom `JacksonConverter` implementations now receive a `JacksonConversionContext`
instead of direct Jackson mapper/module types. The common DSL stays under
`import com.tjclp.fastmcp.*`.

### Running Examples

The above example can be run using `scala-cli README.md` or `scala-cli scripts/quickstart.sc` from the repo root. You can run the server via the MCP inspector by running:


```bash 
npx @modelcontextprotocol/inspector scala-cli README.md
```
or
```bash 
npx @modelcontextprotocol/inspector scala-cli scripts/quickstart.sc
```

You can also run examples directly from the command line:
```bash 
scala-cli \
    -e '//> using dep com.tjclp::fast-mcp-scala:0.2.3' \
    --main-class com.tjclp.fastmcp.examples.AnnotatedServer
```

### HTTP Transport (Recommended for Remote)

FastMCP-Scala supports the full MCP Streamable HTTP spec with session management and SSE streaming. Just call `runHttp()`:

```scala 3 raw
//> using scala 3.7.2
//> using dep com.tjclp::fast-mcp-scala:0.2.3
//> using options "-Xcheck-macros" "-experimental"

import com.tjclp.fastmcp.*
import zio.*

object StreamableExample:
  @Tool(name = Some("greet"), description = Some("Greet someone by name"))
  def greet(@Param("Name to greet") name: String): String =
    s"Hello, $name!"

object StreamableServer extends ZIOAppDefault:
  override def run =
    val server = FastMcpServer(
      name = "StreamableExample",
      version = "0.1.0",
      settings = FastMcpServerSettings(port = 8090)
    )
    for
      _ <- ZIO.attempt(server.scanAnnotations[StreamableExample.type])
      _ <- server.runHttp()
    yield ()
```

Then test with curl:

```bash
# Initialize (returns mcp-session-id header)
curl -s -D- -X POST http://localhost:8090/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}'

# Call tool (SSE stream response)
curl -N -X POST http://localhost:8090/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "mcp-session-id: <session-id>" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"greet","arguments":{"name":"World"}}}'

# Delete session
curl -X DELETE http://localhost:8090/mcp -H "mcp-session-id: <session-id>"
```

### Stateless HTTP Transport

For lightweight servers that don't need sessions or SSE, set `stateless = true`:

```scala 3 raw
//> using scala 3.7.2
//> using dep com.tjclp::fast-mcp-scala:0.2.3
//> using options "-Xcheck-macros" "-experimental"

import com.tjclp.fastmcp.*
import zio.*

object HttpExample:
  @Tool(name = Some("greet"), description = Some("Greet someone by name"))
  def greet(@Param("Name to greet") name: String): String =
    s"Hello, $name!"

object HttpServer extends ZIOAppDefault:
  override def run =
    val server = FastMcpServer(
      name = "HttpExample",
      version = "0.1.0",
      settings = FastMcpServerSettings(port = 8090, stateless = true)
    )
    for
      _ <- ZIO.attempt(server.scanAnnotations[HttpExample.type])
      _ <- server.runHttp()
    yield ()
```

Then test with curl:

```bash
# Initialize
curl -s -X POST http://localhost:8090/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl-test","version":"1.0"}}}'

# Call tool
curl -s -X POST http://localhost:8090/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"greet","arguments":{"name":"World"}}}'
```

The HTTP transport settings are configured via `FastMcpServerSettings`:

| Setting | Default | Description |
|---------|---------|-------------|
| `host` | `0.0.0.0` | Bind address |
| `port` | `8000` | Listen port |
| `httpEndpoint` | `/mcp` | JSON-RPC endpoint path |
| `stateless` | `false` | When true, disables sessions and SSE (stateless mode) |

### Integration with Claude Desktop

In Claude desktop, you can add the following to your `claude_desktop_config.json`:

```json 
{
  "mcpServers": {
    "example-fast-mcp-server": {
      "command": "scala-cli",
      "args": [
        "-e",
        "//> using dep com.tjclp::fast-mcp-scala:0.2.3",
        "--main-class",
        "com.tjclp.fastmcp.examples.AnnotatedServer"
      ]
    }
  }
}
```

> Note: FastMCP-Scala example servers are for demo purposes only and don't do anything useful

For additional examples and in‑depth docs, see **`docs/guide.md`**.

## License

[MIT](LICENSE)

---

## Development Documentation

### Developing Locally

When hacking on *FastMCP‑Scala* itself, you can consume a local build in any project.

#### 🔨 Build Commands (Mill)

FastMCP-Scala uses [Mill](https://mill-build.org/) as its build tool.

```bash
# Compile the library
./mill fast-mcp-scala.compile

# Run tests
./mill fast-mcp-scala.test

# Check code formatting
./mill fast-mcp-scala.checkFormat

# Auto-format code
./mill fast-mcp-scala.reformat

# Generate test coverage report
./mill fast-mcp-scala.scoverage.htmlReport
```

#### 🔨 Publish to the Local Ivy Repository

```bash
# From the fast-mcp-scala root
./mill fast-mcp-scala.publishLocal
```

Then, in your consuming sbt project:

```scala 3 ignore
libraryDependencies += "com.tjclp" %% "fast-mcp-scala" % "0.2.4-SNAPSHOT"
```

Or in Mill:

```scala 3 ignore
def ivyDeps = Agg(
  ivy"com.tjclp::fast-mcp-scala:0.2.4-SNAPSHOT"
)
```

> `publishLocal` installs the artifact under `~/.ivy2/local`.

#### 📦 Use the JAR Directly (Unmanaged Dependencies)

```bash
# Build the JAR
./mill fast-mcp-scala.jar

# The JAR is located at:
# out/fast-mcp-scala/jar.dest/out.jar
```

#### 🚀 Using with `scala‑cli`

You can use `fast-mcp-scala` in another scala‑cli project:
```scala 3 ignore
//> using scala 3.7.2
//> using dep com.tjclp::fast-mcp-scala:0.2.3
//> using options "-Xcheck-macros" "-experimental"
```

You can also point directly at the local JAR:

```scala 3 ignore
//> using scala 3.7.2
//> using jar "/absolute/path/to/out/fast-mcp-scala/jar.dest/out.jar"
//> using options "-Xcheck-macros" "-experimental"
```
