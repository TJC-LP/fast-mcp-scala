# Fast-MCP-Scala

A high-level, developer-friendly API for building Model Context Protocol (MCP) servers in Scala 3.

## Overview

Fast-MCP-Scala provides a Scala wrapper around the Model Context Protocol Java SDK, offering a more idiomatic and functional way to create MCP servers. It uses ZIO for effect handling and offers annotation-based API similar to Python's fastMCP.

## Features

- **ZIO Integration**: Uses ZIO for effect handling, error management, and asynchronous operations.
- **Type Safety**: Leverages Scala 3's type system for improved safety and clarity.
- **Functional API**: Provides a clean, functional API for defining tools, resources, and prompts.
- **Annotation Support**: Uses Scala annotations (`@Tool`, `@Resource`, `@Prompt`) for easy registration.
- **Java SDK Integration**: Integrates seamlessly with the underlying Java MCP SDK.

## Getting Started

### Installation

Add the following dependency to your build.sbt:

```scala
libraryDependencies += "io.fast-mcp-scala" %% "fast-mcp-scala" % "0.1.0-SNAPSHOT"
```

### Example

Here's a simple example of creating an MCP server with Fast-MCP-Scala:

```scala
import fastmcp.core.*
import fastmcp.server.*
import zio.*

object SimpleCalculator extends ZIOAppDefault:
  override def run =
    // Create a new FastMCPScala server
    val server = FastMCPScala("SimpleCalculator", "0.1.0")
    
    for {
      // Register a calculator tool
      _ <- server.tool(
        name = "add",
        description = Some("Adds two numbers"),
        handler = args => {
          val a = args.getOrElse("a", 0.0).asInstanceOf[Double]
          val b = args.getOrElse("b", 0.0).asInstanceOf[Double]
          ZIO.succeed(a + b)
        }
      )
      
      // Run the server with stdio transport
      _ <- server.runStdio()
    } yield ()
```

### Annotation-based Example

You can also use annotations to define your tools, resources, and prompts:

```scala
import fastmcp.core.*
import fastmcp.server.*
import fastmcp.server.annotation.AnnotationProcessor
import zio.*

object AnnotatedCalculator extends ZIOAppDefault:
  override def run =
    val server = FastMCPScala("AnnotatedCalculator", "0.1.0")
    
    for {
      _ <- AnnotationProcessor.processAnnotations(CalculatorService, server)
      _ <- server.runStdio()
    } yield ()

object CalculatorService:
  @Tool(name = Some("add"), description = Some("Adds two numbers"))
  def addition(a: Double = 0.0, b: Double = 0.0): Double = a + b
  
  @Resource(uri = "/calculator/help")
  def getHelp(): String = "Calculator API Help"
  
  @Prompt(name = Some("calculate_prompt"))
  def calculatePrompt(): List[Message] = 
    List(Message(Role.User, TextContent("What's 2+2?")))
```

## Project Status

This project is in early development. Features and APIs may change significantly.

## Running the Server

> **IMPORTANT**: Since the MCP server uses STDIO for communication, standard output (stdout) is already in use for the MCP protocol. All logging and user messages are redirected to stderr or log files.

### Using Scala CLI (Recommended)

The simplest way to run FastMCP-Scala is with the `main.scala` launcher file using [Scala CLI](https://scala-cli.virtuslab.org/):

```bash
# Install Scala CLI if you don't have it
brew install scala-cli    # macOS with Homebrew
# or
curl -sSLf https://scala-cli.virtuslab.org/get | sh    # Other platforms

# Run SimpleServer
scala-cli run main.scala --main-class fastmcp.examples.SimpleServer

# Run AnnotatedServer
scala-cli run main.scala --main-class fastmcp.examples.AnnotatedServer

# Run the main application (with command-line args support)
scala-cli run main.scala --main-class fastmcp.FastMCPMain
```

This approach avoids SBT filesystem permission issues entirely by using scala-cli directly with the existing ZIO applications.

### Using SBT

You can also use SBT to run the server, but you may need to configure the global base directory to avoid permission issues:

```bash
# Run with SBT (avoid permission errors with global base directory)
sbt -Dsbt.global.base=$HOME/.sbt/1.0 run

# Run a specific example
sbt -Dsbt.global.base=$HOME/.sbt/1.0 "runMain fastmcp.examples.SimpleServer"
sbt -Dsbt.global.base=$HOME/.sbt/1.0 "runMain fastmcp.examples.AnnotatedServer"
```

## License

This project is licensed under the MIT License.