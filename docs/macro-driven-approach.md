# FastMCP-Scala: Macro-Driven MCP Tool Framework

This guide explains how to use FastMCP-Scala's macro-driven approach for defining MCP tools with automatic schema generation, leveraging Scala 3's powerful metaprogramming capabilities.

## Quick Start

Here's a minimal example of how to define and use tools with annotations:

```scala
import fastmcp.core.{Tool, Param}
import fastmcp.server.FastMCPScala
import fastmcp.macros.MacroAnnotationProcessor.{given, *}
import zio.*

object MyTools:
  // Simple tool with primitive types
  @Tool(
    name = Some("add"),
    description = Some("Adds two numbers together")
  )
  def add(
    @Param("First number") a: Int,
    @Param("Second number") b: Int
  ): Int = a + b

@main def run(): Unit =
  val server = FastMCPScala("MyServer", "0.1.0")
  
  // Scan for tools in the MyTools object
  server.scanAnnotations[MyTools.type]
  
  // Run the server
  zio.Unsafe.unsafeCompat { implicit unsafe =>
    zio.Runtime.default.unsafe.run(server.runStdio()).getOrThrowFiberFailure()
  }
```

## Understanding the Macro-Driven Approach

The macro-driven approach consists of three main components:

1. **Annotations (`@Tool`, `@Param`)**: Markers that provide metadata about tools and parameters
2. **Macros (`ToolMacros`)**: Compile-time code that inspects the annotations and generates schema/registration code
3. **Runtime Support (`FastMCPScala.registerMacroTool`)**: Methods called by the generated code to actually register tools

This approach provides significant benefits:
- Zero boilerplate for schema definition
- Type safety with minimal runtime overhead
- Clear, declarative tool definitions
- Automatic parameter extraction and JSON schema generation

## Annotations

### @Tool Annotation

Use this to mark methods that should be registered as MCP tools:

```scala
@Tool(
  name = Some("toolName"),         // Optional: defaults to method name if not provided
  description = Some("Tool description"), // Optional: description shown in tool listing
  examples = List("example1", "example2"), // Optional: usage examples
  version = Some("1.0"),           // Optional: tool version
  deprecated = false,              // Optional: mark as deprecated
  deprecationMessage = None,       // Optional: message explaining deprecation
  tags = List("tag1", "tag2"),     // Optional: categorization tags
  timeoutMillis = Some(5000L)      // Optional: execution timeout
)
def myTool(...): ReturnType = ...
```

### @Param Annotation

Use this to provide metadata for tool parameters:

```scala
@Param(
  "Parameter description",           // Required: description of the parameter
  example = Some("Example value"),   // Optional: example value for documentation
  required = true,                   // Optional: whether parameter is required (default: true)
  schema = Some("{...}")             // Optional: custom JSON schema override
)
```

## Automatic Schema Generation

The framework automatically converts Scala types to JSON Schema:

| Scala Type               | JSON Schema Type    |
|--------------------------|---------------------|
| Int, Long, Short, Byte   | "integer"           |
| Double, Float            | "number"            |
| Boolean                  | "boolean"           |
| String, Char             | "string"            |
| List, Seq, Array         | "array"             |
| Map, case class          | "object"            |

### Default Values

Parameters with default values are automatically marked as optional in the schema:

```scala
def greet(
  @Param("Person to greet") name: String,
  @Param("Language code") language: String = "en"  // Optional parameter with default
): String = ...
```

## Organizing Tools

For better organization, group related tools inside objects:

```scala
object CalculatorTools:
  @Tool(description = Some("Addition tool"))
  def add(a: Int, b: Int): Int = a + b
  
  @Tool(description = Some("Multiplication tool"))
  def multiply(a: Int, b: Int): Int = a * b

object StringTools:
  @Tool(description = Some("Text formatter"))
  def format(text: String): String = ...
```

Then register each tool group separately:

```scala
server.scanAnnotations[CalculatorTools.type]
server.scanAnnotations[StringTools.type]
```

## Return Values

Tools can return any type that can be converted to a string or a JSON value:

- Primitive types (Int, String, etc.) are converted to string
- Case classes with JSON codecs are converted to JSON
- Custom types need appropriate encoder/decoder implementations

## Integration with ZIO

For asynchronous operations, return a ZIO effect from your tool method:

```scala
@Tool(description = Some("Fetches data asynchronously"))
def fetchData(url: String): ZIO[Any, Throwable, Data] =
  for
    response <- Http.get(url)
    data <- ZIO.attempt(parseData(response))
  yield data
```

The framework will automatically handle the effect and extract the result.

## How It Works Under the Hood

When you call `server.scanAnnotations[MyTools.type]`:

1. The `ToolMacros.processAnnotations` macro is invoked at compile time
2. The macro analyzes the type `MyTools.type` and finds all methods with `@Tool` annotations
3. For each annotated method, it:
   - Extracts metadata from the `@Tool` annotation (name, description)
   - Analyzes the method parameters and their `@Param` annotations
   - Generates a JSON schema string based on parameter types and annotations
   - Creates code that calls `server.registerMacroTool[MyTools.type](...)` with the extracted data
4. At runtime, `registerMacroTool` handles the actual registration:
   - It uses reflection to find the method in the object
   - Creates a handler function that:
     - Extracts and validates parameters from input maps
     - Calls the method with the extracted parameters
     - Returns the result appropriately formatted
   - Registers the tool with the MCP server

This approach gives you all the benefits of compile-time validation with minimal runtime overhead.

## Advanced Usage

### Handling Complex Types

For complex parameter types, you might need custom JSON Schema definitions. Use the `schema` parameter to provide a custom schema:

```scala
@Tool(description = Some("Complex tool"))
def complexTool(
  @Param(
    description = "Complex parameter",
    schema = Some("""{"type": "object", "properties": {...}}""")
  )
  complexParam: ComplexType
): Result = ...
```

### Customizing Input Conversion

For more advanced input conversion, you can implement custom converters in your tool objects:

```scala
object AdvancedTools:
  // Custom input converter (can be used by multiple tools)
  private def convertCoordinates(args: Map[String, Any]): (Double, Double) =
    val x = args.getOrElse("x", "0").toString.toDouble
    val y = args.getOrElse("y", "0").toString.toDouble
    (x, y)
  
  @Tool(description = Some("Calculate distance from origin"))
  def distance(
    @Param("X coordinate") x: Double,
    @Param("Y coordinate") y: Double
  ): Double =
    Math.sqrt(x*x + y*y)
```

## Implementation Details

The macro-driven approach consists of:

1. **ToolMacros.scala**: Contains the macro implementation that analyzes annotated methods
2. **MacroAnnotationProcessor.scala**: Provides a bridge between ZIO effects and macros
3. **FastMCPScala.registerMacroTool**: The runtime method called by the macro-generated code

### ToolMacros.scala

This is where the Scala 3 metaprogramming happens:

```scala
inline def processAnnotations[T](server: FastMCPScala): Unit =
  ${ processAnnotationsImpl[T]('server) }

private def processAnnotationsImpl[T: Type](serverExpr: Expr[FastMCPScala])(using Quotes): Expr[Unit] =
  // Find @Tool annotations and generate code that calls registerMacroTool
```

### MacroAnnotationProcessor.scala

Provides both ZIO-effect based methods and inline methods:

```scala
// ZIO effect version for compatibility with existing code
def processToolAnnotations[T](server: FastMCPScala): ZIO[Any, Throwable, Unit] =
  ZIO.attempt { ToolMacros.processAnnotations[T](server) }

// Inline extension method for direct macro use
extension (server: FastMCPScala)
  inline def scanAnnotations[T]: Unit =
    ToolMacros.processAnnotations[T](server)
```

### FastMCPScala.registerMacroTool

The runtime component that handles actual tool registration:

```scala
def registerMacroTool[T](
  toolName: String,
  description: Option[String],
  methodName: String,
  schemaJson: String,
  paramNames: List[String],
  paramTypes: List[String],
  required: List[Boolean]
): Unit = 
  // Create tool definition
  // Create handler function using reflection
  // Register with toolManager
```

## Future Enhancements

Future versions of the framework will include additional features:

- Resource and Prompt annotations support
- Improved case class schema generation
- Support for enum types
- Integration with ZIO Schema for more accurate type mapping

## Troubleshooting

If you encounter issues with the macro processing:

1. Ensure you're using Scala 3.x
2. Check that annotations are correctly applied
3. Make sure parameter types are supported
4. If necessary, provide manual schema definitions

For more detailed error messages, run with `-Xcheck-macros` compiler flag.