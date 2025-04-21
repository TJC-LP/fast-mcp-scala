# FastMCP-Scala

A high‑level, developer‑friendly **Scala 3** library for building Model Context Protocol (MCP) servers.

Features
- ZIO‑based effect handling and async support
- Annotation‑driven API (`@Tool`, `@Resource`, `@Prompt`)
- Automatic JSON Schema & handler generation via Scala 3 macros
- Seamless integration with the Java MCP SDK

---

## Installation (Coming Soon)

Add to your **`build.sbt`** (defaulting to **Scala 3.6.4**):

```scala
libraryDependencies += "com.tjclp" %% "fast-mcp-scala" % "0.1.0"
```

---

## Developing Locally

When hacking on *FastMCP‑Scala* itself, you can consume a local build in any project.

### 🔨 Publish to the Local Ivy Repository with `sbt` (Recommended)

```bash
# From the fast-mcp-scala root
sbt publishLocal
```

Then, in your consuming sbt project:

```scala
libraryDependencies += "com.tjclp" %% "fast-mcp-scala" % "0.1.0-SNAPSHOT"
```

> `publishLocal` installs the artifact under `~/.ivy2/local` (or the Coursier cache when enabled).

---

### 📦Use the JAR Directly (Unmanaged Dependencies)

```bash
# Package the library
sbt package

# Copy the JAR – adjust Scala version / name if you change them
cp target/scala-3.6.4/fast-mcp-scala_3-0.1.0-SNAPSHOT.jar \
   /path/to/other-project/lib/
```

Unmanaged JARs placed in a project’s `lib/` folder are picked up automatically by sbt.

---

### 🚀Using with **scala‑cli**

You can use `fast-mcp-scala` in another scala‑cli project:
```scala
//> using scala 3.6.4
//> using dep com.tjclp::fast-mcp-scala:0.1.0-SNAPSHOT
//> using options "-Xcheck-macros" "-experimental"
```

You can also point directly at the local JAR:

```scala
//> using scala 3.6.4
//> using lib "/absolute/path/to/fast-mcp-scala_3-0.1.0-SNAPSHOT.jar"
//> using options "-Xcheck-macros" "-experimental"
```

---

## Quickstart (Annotation‑based)

```scala
//> using scala 3.6.4
//> using dep com.tjclp::fast-mcp-scala:0.1.0-SNAPSHOT
//> using options "-Xcheck-macros" "-experimental"

import com.tjclp.fastmcp.core.{Tool, ToolParam, Prompt, PromptParam, Resource}
import com.tjclp.fastmcp.server.FastMcpServer
import com.tjclp.fastmcp.macros.RegistrationMacro.*
import zio.*

// Define annotated tools, prompts, and resources
object Example:
  @Tool(name = Some("add"), description = Some("Add two numbers"))
  def add(
      @ToolParam("First operand") a: Double,
      @ToolParam("Second operand") b: Double
    ): Double = a + b

  @Prompt(name = Some("greet"), description = Some("Generate a greeting message"))
  def greet(@PromptParam("Name to greet") name: String): String =
    s"Hello, $name!"

  // Note: resource templates (templated URIs) are not yet supported;
  // coming soon when the MCP java‑sdk adds template support.
  @Resource(uri = "file://test", description = Some("Test resource"))
  def test(): String = "This is a test"

object ExampleServer extends ZIOAppDefault:
  override def run =
    for
      server <- ZIO.succeed(FastMcpServer("ExampleServer"))
      _      <- ZIO.attempt(server.scanAnnotations[Example.type])
      _      <- server.runStdio()
    yield ()
```

The above example can be run using `scala-cli scripts/quickstart.scala` from the repo root (make sure to run `sbt publishLocal` first). You can run the server via the MCP inspector by running
```bash 
 npx @modelcontextprotocol/inspector scala-cli <path_to_repo>/scripts/quickstart.scala
```

For additional examples and in‑depth docs, see **`docs/guide.md`**.

---

## License

[MIT](LICENSE)