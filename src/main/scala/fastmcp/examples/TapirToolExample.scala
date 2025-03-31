package fastmcp.examples

import fastmcp.core.*
import fastmcp.server.*
import fastmcp.server.manager.*
import sttp.tapir.Schema
import sttp.tapir.{SchemaType, Schema as TapirSchema}
import sttp.tapir.generic.auto.*
import zio.*
import zio.json.*

import java.lang.System as JSystem
import java.time.Instant

/**
 * Example demonstrating tools with Tapir Schema for automatic schema generation
 *
 * This example shows how to use Tapir to automatically generate rich JSON schemas
 * for your tools, complete with documentation and validation.
 */
object TapirToolExample extends ZIOAppDefault:
  // Sample data
  private val people = List(
    Person("Alice", 30, Some("alice@example.com"), List("developer", "scala")),
    Person("Bob", 25, None, List("designer")),
    Person("Charlie", 40, Some("charlie@example.com"), List("manager", "scala")),
    Person("Diana", 35, Some("diana@example.com"), List("developer", "java")),
    Person("Eve", 28, None, List("tester", "qa"))
  )

  // JSON serialization/deserialization for our case classes
  // These are needed for handling JSON inputs/outputs
  given JsonEncoder[Person] = DeriveJsonEncoder.gen[Person]

  given JsonDecoder[Person] = DeriveJsonDecoder.gen[Person]

  given JsonEncoder[SearchParams] = DeriveJsonEncoder.gen[SearchParams]

  given JsonDecoder[SearchParams] = DeriveJsonDecoder.gen[SearchParams]

  given JsonEncoder[SearchResult] = DeriveJsonEncoder.gen[SearchResult]

  given JsonDecoder[SearchResult] = DeriveJsonDecoder.gen[SearchResult]

  given JsonEncoder[CalculatorParams] = DeriveJsonEncoder.gen[CalculatorParams]

  given JsonDecoder[CalculatorParams] = DeriveJsonDecoder.gen[CalculatorParams]

  given JsonEncoder[CalculatorResult] = DeriveJsonEncoder.gen[CalculatorResult]

  given JsonDecoder[CalculatorResult] = DeriveJsonDecoder.gen[CalculatorResult]

  // Json codecs for enums
  given JsonEncoder[SortField] = JsonEncoder.string.contramap[SortField](_.toString)

  given JsonDecoder[SortField] = JsonDecoder.string.mapOrFail {
    case "Name" => Right(SortField.Name)
    case "Age" => Right(SortField.Age)
    case other => Left(s"Invalid SortField: $other")
  }

  given JsonEncoder[Operation] = JsonEncoder.string.contramap[Operation](_.toString)

  given JsonDecoder[Operation] = JsonDecoder.string.mapOrFail {
    case "Add" => Right(Operation.Add)
    case "Subtract" => Right(Operation.Subtract)
    case "Multiply" => Right(Operation.Multiply)
    case "Divide" => Right(Operation.Divide)
    case other => Left(s"Invalid Operation: $other")
  }

  /**
   * Main entry point
   */
  override def run =
    val server = FastMCPScala("TapirSchemaExample", "1.0.0")

    for
      // Register the search tool with a Tapir-derived schema
      _ <- server.caseClassToolWithTapirSchema[SearchParams, SearchResult](
        name = "searchPeople",
        handler = searchPeople,
        description = Some("Search people by various criteria with sorting and filtering")
      )

      // Register the calculator tool with a Tapir-derived schema
      _ <- server.caseClassToolWithTapirSchema[CalculatorParams, CalculatorResult](
        name = "calculator",
        handler = calculate,
        description = Some("Perform arithmetic operations with two operands")
      )

      // Log startup information
      _ <- ZIO.attempt {
        JSystem.err.println("Tapir Schema Tool Example initialized with auto-generated JSON schemas")
        JSystem.err.println("Available tools:")
        JSystem.err.println("- searchPeople: Search people by various criteria")
        JSystem.err.println("- calculator: Perform arithmetic operations")
      }

      // Run the server with stdio transport
      _ <- server.runStdio()
    yield ()

  /**
   * Search people based on the provided criteria
   */
  def searchPeople(params: SearchParams, context: Option[McpContext]): ZIO[Any, Throwable, SearchResult] =
    ZIO.succeed {
      val startTime = JSystem.currentTimeMillis()

      // Apply filters
      val filtered = people.filter { person =>
        // Apply name filter
        val nameMatches = params.nameFilter.forall(filter =>
          person.name.toLowerCase.contains(filter.toLowerCase))

        // Apply age filters
        val ageMatches =
          params.minAge.forall(min => person.age >= min) &&
            params.maxAge.forall(max => person.age <= max)

        // Apply email filter
        val emailMatches = params.hasEmail.forall(hasEmail =>
          if (hasEmail) person.email.isDefined else person.email.isEmpty)

        // Apply tag filter (match any tag in the list)
        val tagMatches = params.tags.isEmpty ||
          params.tags.exists(tag => person.tags.contains(tag))

        nameMatches && ageMatches && emailMatches && tagMatches
      }

      // Apply sorting
      val sorted = params.sortBy match {
        case SortField.Name =>
          if (params.ascending) filtered.sortBy(_.name)
          else filtered.sortBy(_.name).reverse
        case SortField.Age =>
          if (params.ascending) filtered.sortBy(_.age)
          else filtered.sortBy(_.age).reverse
      }

      // Apply limit
      val limited = sorted.take(params.limit)

      val endTime = JSystem.currentTimeMillis()

      SearchResult(
        people = limited,
        totalResults = people.size,
        filteredCount = filtered.size,
        executionTimeMs = endTime - startTime
      )
    }

  /**
   * Perform a calculation based on the provided parameters
   */
  def calculate(params: CalculatorParams, context: Option[McpContext]): ZIO[Any, Throwable, CalculatorResult] =
    ZIO.attempt {
      val result = params.operation match {
        case Operation.Add => params.operand1 + params.operand2
        case Operation.Subtract => params.operand1 - params.operand2
        case Operation.Multiply => params.operand1 * params.operand2
        case Operation.Divide =>
          if (params.operand2 == 0) throw new ArithmeticException("Division by zero")
          params.operand1 / params.operand2
      }

      CalculatorResult(
        operand1 = params.operand1,
        operand2 = params.operand2,
        operation = params.operation.toString,
        result = result
      )
    }

  /**
   * Case class representing a person
   */
  case class Person(
                     name: String,
                     age: Int,
                     email: Option[String] = None,
                     tags: List[String] = List.empty
                   )

  /**
   * Search parameters for filtering people
   */
  case class SearchParams(
                           nameFilter: Option[String] = None,
                           minAge: Option[Int] = None,
                           maxAge: Option[Int] = None,
                           hasEmail: Option[Boolean] = None,
                           tags: List[String] = List.empty,
                           sortBy: SortField = SortField.Name,
                           ascending: Boolean = true,
                           limit: Int = 10
                         )

  /**
   * Sort field enum
   */
  enum SortField:
    case Name, Age

  /**
   * Search result for people
   */
  case class SearchResult(
                           people: List[Person],
                           totalResults: Int,
                           filteredCount: Int,
                           executionTimeMs: Long
                         )

  /**
   * Calculator parameters
   */
  case class CalculatorParams(
                               operand1: Double,
                               operand2: Double,
                               operation: Operation
                             )

  /**
   * Operation enum
   */
  enum Operation:
    case Add, Subtract, Multiply, Divide

  /**
   * Calculator result
   */
  case class CalculatorResult(
                               operand1: Double,
                               operand2: Double,
                               operation: String,
                               result: Double
                             )