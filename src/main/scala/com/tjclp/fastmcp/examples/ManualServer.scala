package com.tjclp.fastmcp
package examples

import com.tjclp.fastmcp.macros.JsonSchemaMacro
import com.tjclp.fastmcp.macros.MapToFunctionMacro
import com.tjclp.fastmcp.server.*
import sttp.tapir.*
import zio.*
import zio.json.*

import java.lang.System as JSystem

/** Example server using annotation-based tool definitions with macro processing
  *
  * This example demonstrates how to use @Tool annotations to define MCP tools with automatic schema
  * generation through Scala 3 macros.
  */
object ManualServer extends ZIOAppDefault:

  // JSON codecs for our custom types
  given JsonEncoder[CalculatorResult] = DeriveJsonEncoder.gen[CalculatorResult]

  given JsonDecoder[CalculatorResult] = DeriveJsonDecoder.gen[CalculatorResult]

  /** Main entry point
    */
  override def run: ZIO[Any, Throwable, Unit] =
    for
      // Create MCP server
      server <- ZIO.succeed(
        FastMcpServer(
          name = "MacroAnnotatedServer",
          version = "0.1.0"
        )
      )

      // Start registering tools from CalculatorTools directly in the for-comprehension
      _ <- ZIO.succeed(
        JSystem.err.println("[AnnotatedServer] Directly registering tools from CalculatorTools...")
      )

      // Register CalculatorTools.add
      addSchema = Right(JsonSchemaMacro.schemaForFunctionArgs(CalculatorTools.add).spaces2)
      _ <- server.tool(
        name = "add",
        description = Some("Simple calculator that adds two numbers"),
        handler = (args, _) => ZIO.succeed(MapToFunctionMacro.callByMap(CalculatorTools.add)(args)),
        inputSchema = addSchema
      )

      // Register CalculatorTools.addString
      addStringSchema = Right(
        JsonSchemaMacro.schemaForFunctionArgs(CalculatorTools.addString).spaces2
      )
      _ <- server.tool(
        name = "addString",
        description = Some("Adds two numbers provided as strings"),
        handler =
          (args, _) => ZIO.succeed(MapToFunctionMacro.callByMap(CalculatorTools.addString)(args)),
        inputSchema = addStringSchema
      )

      // Register CalculatorTools.multiply
      multiplySchema = Right(
        JsonSchemaMacro.schemaForFunctionArgs(CalculatorTools.multiply).spaces2
      )
      _ <- server.tool(
        name = "multiply",
        description = Some("Multiplies two numbers together"),
        handler =
          (args, _) => ZIO.succeed(MapToFunctionMacro.callByMap(CalculatorTools.multiply)(args)),
        inputSchema = multiplySchema
      )

      // Register CalculatorTools.calculate
      calculateSchema = Right(
        JsonSchemaMacro.schemaForFunctionArgs(CalculatorTools.calculate).spaces2
      )
      _ <- server.tool(
        name = "calculator",
        description = Some("Perform a calculation with two numbers and a specified operation"),
        handler =
          (args, _) => ZIO.succeed(MapToFunctionMacro.callByMap(CalculatorTools.calculate)(args)),
        inputSchema = calculateSchema
      )

      // Register tools from StringTools
      _ <- ZIO.succeed(
        JSystem.err.println("[AnnotatedServer] Directly registering tools from StringTools...")
      )

      // Register StringTools.greet
      greetSchema = Right(JsonSchemaMacro.schemaForFunctionArgs(StringTools.greet).spaces2)
      _ <- server.tool(
        name = "greet",
        description = Some("Generates a friendly greeting message"),
        handler = (args, _) => ZIO.succeed(MapToFunctionMacro.callByMap(StringTools.greet)(args)),
        inputSchema = greetSchema
      )

      // Register StringTools.transformText with enum parameter
      transformSchema = Right(
        JsonSchemaMacro.schemaForFunctionArgs(StringTools.transformText).spaces2
      )
      _ <- server.tool(
        name = "transform",
        description = Some("Transforms text using enum-based transformation operations"),
        handler =
          (args, _) => ZIO.succeed(MapToFunctionMacro.callByMap(StringTools.transformText)(args)),
        inputSchema = transformSchema
      )

      // Register the complex formatting tool with multiple enum parameters
      formatTextSchema = Right(
        JsonSchemaMacro.schemaForFunctionArgs(TextFormatTools.formatText).spaces2
      )
      _ <- server.tool(
        name = "format-text",
        description =
          Some("Complex text formatting with transformation, style, and output format options"),
        // No special handling needed - everything is handled at the macro level
        handler =
          (args, _) => ZIO.succeed(MapToFunctionMacro.callByMap(TextFormatTools.formatText)(args)),
        inputSchema = formatTextSchema
      )

      // For demonstration purposes, also register a tool manually
      addManualSchema = Right(JsonSchemaMacro.schemaForFunctionArgs(add).spaces2)
      _ <- server.tool(
        name = "add-manual",
        description = Some("Manual version of the add tool"),
        handler = (args, _) => ZIO.succeed(MapToFunctionMacro.callByMap(add)(args)),
        inputSchema = addManualSchema
      )

      // Run the server with stdio transport
      _ <- server.runStdio()
    yield ()

  def add(
      a: Int,
      b: Int
  ): Int = a + b

  // Define TransformationType enum
  enum TransformationType:
    case uppercase, lowercase, reverse, capitalize

  // Define TextStyle enum for more complex formatting
  enum TextStyle:
    case plain, bold, italic, code, heading

  // Define OutputFormat enum
  enum OutputFormat:
    case text, html, markdown, json

  /** Output type for calculator tool
    */
  case class CalculatorResult(
      operation: String,
      numbers: List[Double],
      result: Double
  )

  object TransformationType:

    // JSON codec for the enum
    given JsonEncoder[TransformationType] =
      JsonEncoder[String].contramap[TransformationType](_.toString)

    given JsonDecoder[TransformationType] = JsonDecoder[String].mapOrFail { str =>
      try Right(TransformationType.valueOf(str))
      catch case _: IllegalArgumentException => Left(s"Invalid transformation type: $str")
    }

  object TextStyle:
    // JSON codec for the enum
    given JsonEncoder[TextStyle] = JsonEncoder.derived[TextStyle]

    given JsonDecoder[TextStyle] = JsonDecoder[String].mapOrFail { str =>
      try Right(TextStyle.valueOf(str))
      catch case _: IllegalArgumentException => Left(s"Invalid text style: $str")
    }

  object OutputFormat:
    // JSON codec for the enum
    given JsonEncoder[OutputFormat] = JsonEncoder[String].contramap[OutputFormat](_.toString)

    given JsonDecoder[OutputFormat] = JsonDecoder[String].mapOrFail { str =>
      try Right(OutputFormat.valueOf(str))
      catch case _: IllegalArgumentException => Left(s"Invalid output format: $str")
    }

  /** Calculator tools with various arithmetic operations
    */
  object CalculatorTools:

    /** Simple addition tool
      */
    def add(
        a: Int,
        b: Int
    ): Int = a + b

    /** Alternative addition tool that takes strings and converts them to ints
      */
    def addString(
        a: String,
        b: String
    ): Int = a.toInt + b.toInt

    /** Multiplication tool
      */
    def multiply(
        a: Int,
        b: Int
    ): Int = a * b

    /** Advanced calculator with operation selection - uses standard schema building
      */
    def calculate(
        a: Double,
        b: Double,
        operation: String = "add"
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

    /** Advanced calculator that uses advanced schema generation with @InferSchema This allows for
      * proper handling of complex types like CalculatorResult
      */
    def advancedCalculate(
        a: Double,
        b: Double,
        operation: String = "add"
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

  /** String manipulation tools
    */
  object StringTools:

    /** Greeting generator
      */
    def greet(
        name: String,
        language: String = "en"
    ): String =
      val greeting = language.toLowerCase match
        case "en" => "Hello"
        case "es" => "Hola"
        case "fr" => "Bonjour"
        case "de" => "Hallo"
        case _ => "Hello"

      s"$greeting, $name!"

    /** Text transformation tool using enum type
      */
    def transformText(
        text: String,
        transformation: TransformationType = TransformationType.uppercase
    ): String =
      transformation match
        case TransformationType.uppercase => text.toUpperCase
        case TransformationType.lowercase => text.toLowerCase
        case TransformationType.capitalize => text.split(" ").map(_.capitalize).mkString(" ")
        case TransformationType.reverse => text.reverse

  /** Advanced text formatting tools using multiple enums
    */
  object TextFormatTools:
    given JsonEncoder[FormattedOutput] = DeriveJsonEncoder.gen[FormattedOutput]

    given JsonDecoder[FormattedOutput] = DeriveJsonDecoder.gen[FormattedOutput]

    /** Complex formatting tool that uses multiple enum parameters
      */
    def formatText(
        text: String,
        transformation: TransformationType = TransformationType.uppercase,
        style: TextStyle = TextStyle.plain,
        outputFormat: OutputFormat = OutputFormat.text
    ): String =
      // First apply the transformation
      val transformedText = transformation match
        case TransformationType.uppercase => text.toUpperCase
        case TransformationType.lowercase => text.toLowerCase
        case TransformationType.capitalize => text.split(" ").map(_.capitalize).mkString(" ")
        case TransformationType.reverse => text.reverse

      // Then apply the style based on output format
      val styledText = (style, outputFormat) match
        case (TextStyle.plain, _) => transformedText
        case (TextStyle.bold, OutputFormat.html) => s"<strong>$transformedText</strong>"
        case (TextStyle.bold, OutputFormat.markdown) => s"**$transformedText**"
        case (TextStyle.italic, OutputFormat.html) => s"<em>$transformedText</em>"
        case (TextStyle.italic, OutputFormat.markdown) => s"*$transformedText*"
        case (TextStyle.code, OutputFormat.html) => s"<code>$transformedText</code>"
        case (TextStyle.code, OutputFormat.markdown) => s"`$transformedText`"
        case (TextStyle.heading, OutputFormat.html) => s"<h1>$transformedText</h1>"
        case (TextStyle.heading, OutputFormat.markdown) => s"# $transformedText"
        case (TextStyle.heading, OutputFormat.json) => transformedText.toUpperCase
        case (style, OutputFormat.json) => transformedText // In JSON mode, we ignore styling
        case (style, _) => transformedText // Default for other combinations

      FormattedOutput(
        originalText = text,
        formattedText = styledText,
        transformation = transformation,
        style = style,
        format = outputFormat
      ).toJsonPretty

    case class FormattedOutput(
        originalText: String,
        formattedText: String,
        transformation: TransformationType,
        style: TextStyle,
        format: OutputFormat
    )

    case class FormatText(
        text: String,
        transformation: TransformationType = TransformationType.uppercase,
        style: TextStyle = TextStyle.plain,
        outputFormat: OutputFormat = OutputFormat.text
    )
