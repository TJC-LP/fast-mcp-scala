package fastmcp.examples

import fastmcp.core.*
import fastmcp.macros.*
import fastmcp.server.*
import zio.*
import zio.json.*
import zio.schema.*
import zio.schema.annotation.*

import java.lang.System as JSystem

/**
 * Example demonstrating the use of ZIO Schema for automatic schema generation
 *
 * This example shows how to use ZIO Schema to automatically generate JSON schemas
 * for tool input and output types, enabling rich documentation and type safety.
 */
object ZioSchemaToolExample extends ZIOAppDefault:
  /**
   * Tool with complex parameter types and ZIO Schema integration
   *
   * This tool performs calculations on a list of numbers.
   */
  @Tool(
    name = Some("statsCalculator"),
    description = Some("Calculate statistics for a list of numbers"),
    examples = List("statsCalculator([1, 2, 3, 4, 5])")
  )
  def calculateStats(
                      @Param(description = "List of numbers to analyze")
                      numbers: List[Double]
                    ): Stats =
    val sum = numbers.sum
    val count = numbers.size
    val avg = if count > 0 then sum / count else 0

    Stats(
      min = if numbers.isEmpty then 0 else numbers.min,
      max = if numbers.isEmpty then 0 else numbers.max,
      average = avg,
      count = count,
      sum = sum
    )

  /**
   * Tool using a more complex input type with ZIO Schema
   */
  @Tool(
    name = Some("filterData"),
    description = Some("Filter and transform a dataset based on criteria")
  )
  def filterData(
                  @Param(description = "Dataset filter configuration")
                  config: FilterConfig
                ): FilterResult =
    // Generate some sample data
    val data = (1 to 10).map(_.toDouble).toList

    // Apply filters
    val filtered = data.filter { value =>
      (config.minValue.forall(min => value >= min)) &&
        (config.maxValue.forall(max => value <= max))
    }

    // Apply transformations
    val transformed = filtered.map { value =>
      config.transform match
        case Some("square") => value * value
        case Some("sqrt") => Math.sqrt(value)
        case Some("double") => value * 2
        case Some("half") => value / 2
        case _ => value
    }

    FilterResult(
      originalCount = data.size,
      filteredCount = filtered.size,
      results = transformed,
      transformType = config.transform.getOrElse("none")
    )

  // Derive ZIO Schema instances for our custom types
  // This enables automatic schema generation
  given Schema[Stats] = DeriveSchema.gen[Stats]

  given Schema[FilterConfig] = DeriveSchema.gen[FilterConfig]

  given Schema[FilterResult] = DeriveSchema.gen[FilterResult]

  // Derive JSON codecs for our custom types
  // These are needed for JSON serialization/deserialization
  given JsonEncoder[Stats] = DeriveJsonEncoder.gen[Stats]

  given JsonDecoder[Stats] = DeriveJsonDecoder.gen[Stats]

  given JsonEncoder[FilterConfig] = DeriveJsonEncoder.gen[FilterConfig]

  given JsonDecoder[FilterConfig] = DeriveJsonDecoder.gen[FilterConfig]

  given JsonEncoder[FilterResult] = DeriveJsonEncoder.gen[FilterResult]

  given JsonDecoder[FilterResult] = DeriveJsonDecoder.gen[FilterResult]

  /**
   * Main entry point
   */
  override def run =
    val server = FastMCPScala("ZioSchemaToolExample", "1.0.0")

    for
      // Process annotations to register tools with auto-generated schemas
      _ <- server.processAnnotations[ZioSchemaToolExample.type]

      // Log startup information
      _ <- ZIO.attempt {
        JSystem.err.println("ZIO Schema Tool Example initialized with auto-generated JSON schemas")
        JSystem.err.println("Available tools:")
        JSystem.err.println("- statsCalculator: Calculate statistics for a list of numbers")
        JSystem.err.println("- filterData: Filter and transform a dataset based on criteria")
      }

      // Run the server with stdio transport
      _ <- server.runStdio()
    yield ()

  /**
   * Output type for statistics calculator with ZIO Schema annotations
   */
  case class Stats(
                    @description("Minimum value in the dataset")
                    min: Double,

                    @description("Maximum value in the dataset")
                    max: Double,

                    @description("Average (mean) of all values")
                    average: Double,

                    @description("Number of values in the dataset")
                    count: Int,

                    @description("Sum of all values")
                    sum: Double
                  )

  /**
   * Input configuration for the filter tool with ZIO Schema annotations
   */
  case class FilterConfig(
                           @description("Minimum value to include (inclusive)")
                           minValue: Option[Double],

                           @description("Maximum value to include (inclusive)")
                           maxValue: Option[Double],

                           @description("Transformation to apply: square, sqrt, double, half")
                           transform: Option[String],

                           @description("Sort order: asc, desc, none")
                           sortOrder: Option[String] = None
                         )
  
  /**
   * Output type for the filter tool with ZIO Schema annotations
   */
  case class FilterResult(
                           @description("Number of items in the original dataset")
                           originalCount: Int,

                           @description("Number of items after filtering")
                           filteredCount: Int,

                           @description("Filtered and transformed results")
                           results: List[Double],

                           @description("Transformation type that was applied")
                           transformType: String
                         )