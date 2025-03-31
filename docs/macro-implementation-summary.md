# FastMCP-Scala Macro Implementation Summary

This document provides a technical overview of the Scala 3 macro-based implementation for automatic MCP tool registration in FastMCP-Scala.

## Core Components

1. **Annotations**: `@Tool` and `@Param` for marking methods and parameters
2. **Macro Processor**: `ToolMacros` that analyzes annotated methods at compile time
3. **Runtime Support**: `FastMCPScala.registerMacroTool` that handles the actual tool registration

## Implementation Overview

### 1. Annotations (Annotations.scala)

- `@Tool` annotation marks methods that should be registered as MCP tools
- `@Param` annotation provides metadata for method parameters
- Annotations store metadata like names, descriptions, and schema information

### 2. Macro Implementation (ToolMacros.scala)

The main macro implementation uses Scala 3's metaprogramming capabilities:

```scala
inline def processAnnotations[T](server: FastMCPScala): Unit =
  ${ processAnnotationsImpl[T]('server) }
```

The implementation:
1. Uses `quotes.reflect` to inspect classes and methods at compile time
2. Finds methods with `@Tool` annotations
3. Extracts method names, parameter types, and annotations
4. Generates JSON schemas based on parameter types
5. Produces code that calls `server.registerMacroTool` for each tool

Key components:
- `processAnnotationsImpl`: Entry point for the macro processing
- `parseToolAnnotation`: Extracts data from `@Tool` annotations
- `parseParamAnnotation`: Extracts data from `@Param` annotations
- `makeJsonSchema`: Generates JSON schema strings from parameters
- `generateRegistrationCode`: Creates the actual code to register tools

### 3. Macro Annotation Processor (MacroAnnotationProcessor.scala)

Provides a bridge between ZIO effects and macros:

- `processToolAnnotations[T]`: ZIO effect wrapper for legacy code
- `scanAnnotations[T]`: Inline extension method for direct macro use

### 4. Runtime Registration (FastMCPScala.registerMacroTool)

Handles the actual tool registration at runtime:

1. Receives tool metadata and schema from the macro
2. Creates a `ToolDefinition` with the generated schema
3. Creates a handler function using reflection to:
   - Extract parameter values from input maps
   - Cast them to the appropriate types
   - Call the annotated method
   - Return the result
4. Registers the tool with the MCP server

## Type Mapping

The implementation maps Scala types to JSON Schema types:

| Scala Type               | JSON Schema Type    |
|--------------------------|---------------------|
| Int, Long, Short, Byte   | "integer"           |
| Double, Float            | "number"            |
| Boolean                  | "boolean"           |
| String, Char             | "string"            |
| List, Seq, Array         | "array"             |
| Map, case class          | "object"            |

## Example Usage

```scala
object CalculatorTools:
  @Tool(
    name = Some("add"),
    description = Some("Adds two numbers")
  )
  def add(
    @Param("First number") a: Int,
    @Param("Second number") b: Int
  ): Int = a + b

// Register the tools
server.scanAnnotations[CalculatorTools.type]
```

## Technical Challenges and Solutions

1. **Parsing Annotations**: Used pattern matching on Scala 3's AST to extract annotation parameters.
2. **Type Extraction**: Implemented string-based type analysis for JSON schema generation.
3. **Parameter Handling**: Added support for required vs. optional parameters based on default values and annotations.
4. **Method Invocation**: Used Java reflection to invoke methods dynamically at runtime.
5. **Schema Generation**: Created a simple but effective JSON schema generator based on Scala type information.

## Future Enhancements

1. **Case Class Support**: Improve schema generation for nested case classes
2. **ZIO Schema Integration**: Use ZIO Schema for more accurate type information
3. **Resource Annotations**: Add support for `@Resource` annotation processing
4. **Prompt Annotations**: Add support for `@Prompt` annotation processing
5. **Validation Annotations**: Add validation annotations (min, max, pattern, etc.)
6. **Documentation Generation**: Auto-generate documentation from annotations

## Technical Design Decisions

1. **Compile-Time Approach**: Using macros enables compile-time validation and reduces runtime overhead
2. **String-Based Schema Generation**: Simple initial approach that can be enhanced later
3. **Reflection for Method Invocation**: Provides flexibility at runtime with minimal compilation dependencies
4. **ZIO Integration**: Ensures compatibility with existing ZIO-based codebase
5. **Dual API**: Both ZIO effect-based and inline macro methods for flexibility