# Fast-MCP-Scala

A high-level, developer-friendly API for building Model Context Protocol (MCP) servers in Scala 3.

## Overview

Fast-MCP-Scala provides a Scala wrapper around the Model Context Protocol Java SDK, offering a more idiomatic and functional way to create MCP servers. It uses ZIO for effect handling and offers annotation-based API similar to Python's fastMCP.

## Features

- **ZIO Integration**: Uses ZIO for effect handling, error management, and asynchronous operations.
- **Type Safety**: Leverages Scala 3's type system for improved safety and clarity.
- **Functional API**: Provides a clean, functional API for defining tools, resources, and prompts.
- **Annotation Support**: Uses Scala annotations (`@Tool`, `@Resource`, `@Prompt`) for easy registration.
- **Auto Schema Generation**: Automatically generates JSON schemas from Scala types.
- **Parameter Documentation**: Uses `@Param` annotations to document tool parameters.
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

### Typed Tool Example

For better type safety, you can use the typed tool API:

```scala
// Define input and output types
case class CalculatorInput(
  operation: String = "add",
  numbers: List[Double] = List(0.0, 0.0)
)
  
case class CalculatorOutput(
  result: Double,
  operation: String,
  inputs: List[Double]
)

// Define JSON codecs
given JsonEncoder[CalculatorInput] = DeriveJsonEncoder.gen[CalculatorInput]
given JsonDecoder[CalculatorInput] = DeriveJsonDecoder.gen[CalculatorInput]
given JsonEncoder[CalculatorOutput] = DeriveJsonEncoder.gen[CalculatorOutput]
given JsonDecoder[CalculatorOutput] = DeriveJsonDecoder.gen[CalculatorOutput]

// Implement typed tool handler
object CalculatorTool extends TypedToolHandler[CalculatorInput, CalculatorOutput]:
  override def handle(input: CalculatorInput, context: Option[McpContext]): ZIO[Any, Throwable, CalculatorOutput] =
    ZIO.attempt {
      val result = input.operation match
        case "add" => input.numbers.sum
        case "multiply" => input.numbers.product
        case _ => throw new IllegalArgumentException(s"Unknown operation: ${input.operation}")
      
      CalculatorOutput(result, input.operation, input.numbers)
    }

// Register the typed tool
server.typedTool(
  name = "calculator",
  handler = CalculatorTool,
  description = Some("Perform calculations on a list of numbers")
)
```

### Annotation-based Example

You can also use annotations to define your tools, resources, and prompts with automatic schema generation:

```scala
import fastmcp.core.*
import fastmcp.server.*
import fastmcp.macros.*
import zio.*

object AnnotatedCalculator extends ZIOAppDefault:
  /**
   * Tool with primitive parameters and automatically generated schema
   */
  @Tool(
    name = Some("addNumbers"),
    description = Some("Simple calculator that adds two numbers"),
    examples = List("addNumbers(1, 2) = 3", "addNumbers(-5, 10) = 5")
  )
  def add(
    @Param(description = "First number to add") a: Int,
    @Param(description = "Second number to add") b: Int
  ): Int = a + b
  
  /**
   * Tool with complex parameter types
   */
  @Tool(
    name = Some("calculator"),
    description = Some("Perform a calculation on a list of numbers")
  )
  def calculator(
    @Param(description = "The operation to perform (add, multiply, min, max, avg)") 
    operation: String,
    
    @Param(description = "List of numbers to operate on") 
    numbers: List[Double]
  ): CalculatorResult =
    // Implementation...
    CalculatorResult(operation, numbers, result)
    
  case class CalculatorResult(operation: String, numbers: List[Double], result: Double)
  
  // JSON codecs for our custom types
  given JsonEncoder[CalculatorResult] = DeriveJsonEncoder.gen[CalculatorResult]
  given JsonDecoder[CalculatorResult] = DeriveJsonDecoder.gen[CalculatorResult]

  override def run =
    val server = FastMCPScala("AnnotatedCalculator", "0.1.0")
    
    for {
      // Process all annotations in this object
      _ <- server.processAnnotations[AnnotatedCalculator.type]
      _ <- server.runStdio()
    } yield ()
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

# Run TypedToolExample
scala-cli run main.scala --main-class fastmcp.examples.TypedToolExample

# Run AnnotatedServer
scala-cli run main.scala --main-class fastmcp.examples.AnnotatedServer

# Run the main application (with command-line args support)
scala-cli run main.scala --main-class fastmcp.FastMCPMain
```

This approach avoids SBT filesystem permission issues entirely by using scala-cli directly.

### Using SBT

You can also use SBT to run the server, but you may need to configure the global base directory to avoid permission issues:

```bash
# Run with SBT (avoid permission errors with global base directory)
sbt -Dsbt.global.base=$HOME/.sbt/1.0 run

# Run a specific example
sbt -Dsbt.global.base=$HOME/.sbt/1.0 "runMain fastmcp.examples.SimpleServer"
sbt -Dsbt.global.base=$HOME/.sbt/1.0 "runMain fastmcp.examples.TypedToolExample"
sbt -Dsbt.global.base=$HOME/.sbt/1.0 "runMain fastmcp.examples.AnnotatedServer"
```

## License

This project is licensed under the MIT License.