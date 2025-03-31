# FastMCP-Scala

A high-level, developer-friendly API for building Model Context Protocol (MCP) servers in Scala 3 with macro-driven automation.

## Overview

FastMCP-Scala provides a Scala wrapper around the Model Context Protocol Java SDK, offering a more idiomatic and functional way to create MCP servers. It uses ZIO for effect handling and offers annotation-based API similar to Python's fastMCP, with powerful Scala 3 macro-driven automation for schema generation and tool registration.

## Features

- **ZIO Integration**: Uses ZIO for effect handling, error management, and asynchronous operations.
- **Type Safety**: Leverages Scala 3's type system for improved safety and clarity.
- **Functional API**: Provides a clean, functional API for defining tools, resources, and prompts.
- **Macro-Driven Annotations**: Uses Scala 3 macros and annotations (`@Tool`, `@Resource`, `@Prompt`) for automatic registration with zero boilerplate.
- **Auto Schema Generation**: Automatically generates JSON schemas from Scala types and method signatures at compile time.
- **Parameter Documentation**: Uses `@Param` annotations to document tool parameters and generate schemas.
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

### Annotation-based Example with Macro Processing

You can use annotations with Scala 3 macros to define your tools with automatic schema generation and zero boilerplate:

```scala
import fastmcp.core.{Tool, Param}
import fastmcp.server.FastMCPScala
import fastmcp.macros.MacroAnnotationProcessor.{given, *}
import zio.*
import zio.json.*

object CalculatorTools:
  /**
   * Simple addition tool with primitive parameters
   */
  @Tool(
    name = Some("add"),
    description = Some("Simple calculator that adds two numbers")
  )
  def add(
    @Param("First number to add") a: Int,
    @Param("Second number to add") b: Int
  ): Int = a + b
  
  /**
   * Advanced calculator with operation selection
   */
  @Tool(
    name = Some("calculator"),
    description = Some("Perform a calculation with two numbers")
  )
  def calculate(
    @Param("First number") a: Double,
    @Param("Second number") b: Double,
    @Param(
      "Operation to perform (add, subtract, multiply, divide)",
      required = false
    ) operation: String = "add"
  ): CalculatorResult =
    val result = operation.toLowerCase match
      case "add" | "+" => a + b
      case "subtract" | "-" => a - b
      case "multiply" | "*" => a * b
      case "divide" | "/" => 
        if (b == 0) throw new IllegalArgumentException("Cannot divide by zero")
        else a / b
      case _ => throw new IllegalArgumentException(s"Unknown operation: $operation")
    
    CalculatorResult(operation, List(a, b), result)
    
object StringTools:
  /**
   * Text transformation tool
   */
  @Tool(
    name = Some("transform"),
    description = Some("Transforms text using various operations")
  )
  def transformText(
    @Param("Text to transform") text: String,
    @Param(
      "Transformation to apply (uppercase, lowercase, capitalize, reverse)",
      required = false
    ) transformation: String = "uppercase"
  ): String =
    transformation.toLowerCase match
      case "uppercase" => text.toUpperCase
      case "lowercase" => text.toLowerCase
      case "capitalize" => text.split(" ").map(_.capitalize).mkString(" ")
      case "reverse" => text.reverse
      case _ => throw new IllegalArgumentException(s"Unknown transformation: $transformation")

case class CalculatorResult(
  operation: String,
  numbers: List[Double],
  result: Double
)

// JSON codecs
given JsonEncoder[CalculatorResult] = DeriveJsonEncoder.gen[CalculatorResult]
given JsonDecoder[CalculatorResult] = DeriveJsonDecoder.gen[CalculatorResult]

object MacroAnnotatedServer extends ZIOAppDefault:
  override def run: ZIO[Any, Throwable, Unit] =
    for
      // Create the server
      server <- ZIO.succeed(FastMCPScala("MacroAnnotatedServer", "0.1.0"))
      
      // Process tools using new macro processor
      _ <- ZIO.attempt {
        // Scan tools in the CalculatorTools object
        server.scanAnnotations[CalculatorTools.type]
        
        // Scan tools in the StringTools object
        server.scanAnnotations[StringTools.type]
      }
      
      // Run the server
      _ <- server.runStdio()
    yield ()
```

The macro system will:
1. Analyze your annotated methods at compile time
2. Generate JSON schemas from parameter types and annotations
3. Create handlers that properly decode parameters and invoke your methods
4. Register everything with your MCP server automatically

## New Macro-Driven Approach

The latest addition to FastMCP-Scala is a fully automated macro-driven approach for tool registration. This feature:

- Uses Scala 3's powerful compile-time metaprogramming capabilities
- Automatically generates JSON schemas from method signatures
- Creates tool handlers from annotated methods with zero boilerplate
- Supports both primitive types and complex return values
- Handles parameter validation and type conversion automatically

Check out the detailed documentation in [Macro-Driven Approach](docs/macro-driven-approach.md) to learn more about this powerful feature.

## Project Status

This project is in active development. Features and APIs may change as we continue to improve the framework.

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