# FastMCP-Scala

A high-level, developer-friendly Scala 3 library for building Model Context Protocol (MCP) servers.

Features:
- ZIO-based effect handling and async support
- Annotation-driven API (`@Tool`, `@Param`, `@Resource`, `@Prompt`)
- Automatic JSON schema & handler generation via Scala 3 macros
- Seamless integration with the Java MCP SDK

## Installation

Add to your `build.sbt`:

```scala
libraryDependencies += "com.tjclp" %% "fast-mcp-scala" % "0.1.0"
```

## Quickstart (Annotation-based)

```scala
import fastmcp.core.{Tool, ToolParam, Prompt, PromptParam, Resource}
import fastmcp.server.FastMcpServer
import zio._

// Define annotated tools, prompts, and resources
object Example

:
@Tool(name = Some("add"), description = Some("Add two numbers"))
def add(
         @ToolParam("First operand") a: Double,
         @ToolParam("Second operand") b: Double
       ): Double = a + b

@Prompt(name = Some("greet"), description = Some("Generate a greeting message"))
def greet(@PromptParam("Name to greet") name: String): String =
  s"Hello, $name!"

// Note: resource templates (templated URIs) are not yet supported;
// coming soon when the MCP java-sdk adds template support.
@Resource(uri = "file://test", description = Some("Test resource"))
def test(): String = "This is a test"

object ExampleServer extends ZIOAppDefault

:
override def run =
  for
    server <- ZIO.succeed(FastMcpServer("AllInOneServer", "0.1.0"))
    _ <- ZIO.attempt(server.scanAnnotations[Example.type])
    _ <- server.runStdio()
  yield ()
```

For more examples and detailed docs, see the `docs/guide.md`.

## License

MIT