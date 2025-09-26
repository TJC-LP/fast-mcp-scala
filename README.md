# FastMCP-Scala

A high‑level, developer‑friendly **Scala 3** library for building Model Context Protocol (MCP) servers.

Features
- ZIO‑based effect handling and async support
- Annotation‑driven API (`@Tool`, `@Resource`, `@Prompt`)
- Automatic JSON Schema & handler generation via Scala 3 macros
- Seamless integration with the Java MCP SDK

## Installation

Add to your **`build.sbt`** (defaulting to **Scala 3.7.2**):

```scala 3 ignore
libraryDependencies += "com.tjclp" %% "fast-mcp-scala" % "0.2.1"
```

## Quickstart

```scala 3 raw
//> using scala 3.7.2
//> using dep com.tjclp::fast-mcp-scala:0.2.1
//> using options "-Xcheck-macros" "-experimental"

import com.tjclp.fastmcp.core.{Tool, Param, Prompt, Resource}
import com.tjclp.fastmcp.server.FastMcpServer
import com.tjclp.fastmcp.macros.RegistrationMacro.*
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
    -e '//> using dep com.tjclp::fast-mcp-scala:0.2.1' \
    --main-class com.tjclp.fastmcp.examples.AnnotatedServer
```

> [!WARNING]
> As of now, only STDIO is supported. We plan to support streamable http in the future.


### Integration with Claude Desktop

In Claude desktop, you can add the following to your `claude_desktop_config.json`:

```json 
{
  "mcpServers": {
    "example-fast-mcp-server": {
      "command": "scala-cli",
      "args": [
        "-e",
        "//> using dep com.tjclp::fast-mcp-scala:0.2.1",
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

#### 🔨 Publish to the Local Ivy Repository with `sbt`

In your cloned repository, set a working version
```scala 3 ignore
ThisBuild / version := "0.2.2-SNAPSHOT"
```

```bash
# From the fast-mcp-scala root
sbt publishLocal
```

Then, in your consuming sbt project:

```scala 3 ignore
libraryDependencies += "com.tjclp" %% "fast-mcp-scala" % "0.2.2-SNAPSHOT"
```

> `publishLocal` installs the artifact under `~/.ivy2/local` (or the Coursier cache when enabled).

#### 📦 Use the JAR Directly (Unmanaged Dependencies)

```bash
# Package the library
sbt package

# Copy the JAR – adjust Scala version / name if you change them
cp target/scala-3.7.2/fast-mcp-scala_3-0.2.2-SNAPSHOT.jar \
   /path/to/other-project/lib/
```

Unmanaged JARs placed in a project's `lib/` folder are picked up automatically by sbt.

#### 🚀 Using with `scala‑cli`

You can use `fast-mcp-scala` in another scala‑cli project:
```scala 3 ignore
//> using scala 3.7.2
//> using dep com.tjclp::fast-mcp-scala:0.2.1
//> using options "-Xcheck-macros" "-experimental"
```

You can also point directly at the local JAR:

```scala 3 ignore
//> using scala 3.7.2
//> using jar "/absolute/path/to/fast-mcp-scala_3-0.2.1.jar"
//> using options "-Xcheck-macros" "-experimental"
```