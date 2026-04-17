package com.tjclp.fastmcp
package examples

import java.lang.System as JSystem

import sttp.tapir.*
import sttp.tapir.Schema.annotations.*
import sttp.tapir.generic.auto.*
import zio.*
import zio.json.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.RegistrationMacro.*
import com.tjclp.fastmcp.server.*

/** Flagship annotation-driven server.
  *
  * `@Tool`, `@Resource`, `@Prompt` methods are discovered by `scanAnnotations[...]` and registered
  * with zero boilerplate — parameter schemas, handler wiring, and MCP metadata are all derived at
  * compile time from the method signatures and `@Param` annotations.
  *
  * The tools below show the spectrum:
  *   - `add` — primitives with behavioral hints (`readOnlyHint`, `idempotentHint`)
  *   - `calculator` — optional parameters with defaults, hints, and a domain-specific failure mode
  *   - `transform` — Scala 3 enums as parameters (schema derivation picks up the cases)
  *   - `description` — nested case classes as parameters
  *   - `search` — the full `@Param` feature set: examples, required-override, and custom JSON
  *     Schema fragments
  *
  * Run with `./mill fast-mcp-scala.runMain com.tjclp.fastmcp.examples.AnnotatedServer` or attach an
  * MCP Inspector: `npx @modelcontextprotocol/inspector scala-cli scripts/quickstart.sc`.
  */
object AnnotatedServer extends ZIOAppDefault:

  // JSON codec for the result
  given JsonEncoder[CalculatorResult] = DeriveJsonEncoder.gen[CalculatorResult]

  given JsonDecoder[CalculatorResult] = DeriveJsonDecoder.gen[CalculatorResult]

  // Case classes are supported as tool params
  case class Description(
      @description("The text to describe") text: String,
      @description("Whether to set the text to uppercase") isUpper: Boolean = false
  )

  /** Nested-case-class argument: FastMCP-Scala derives the JSON schema from the `Description`
    * fields, including the `@description` annotations from Tapir.
    */
  @Tool(name = Some("description"), readOnlyHint = Some(true), idempotentHint = Some(true))
  def generateDescription(
      @Param("A description to generate") description: Description
  ): String =
    if description.isUpper then description.text.toUpperCase else description.text

  /** Pure arithmetic — the hints tell the client this tool never mutates state and is safe to
    * retry.
    */
  @Tool(
    name = Some("add"),
    description = Some("Add two numbers"),
    readOnlyHint = Some(true),
    idempotentHint = Some(true)
  )
  def add(
      @Param("First number") a: Int,
      @Param("Second number") b: Int
  ): Int = a + b

  /** Richer calculator showing optional parameters (`operation` defaults to `"add"`) and the
    * library's support for throwing — exceptions are surfaced to the client as tool errors.
    */
  @Tool(
    name = Some("calculator"),
    description = Some("Perform a calculation with two numbers"),
    tags = List("math", "calculation"),
    readOnlyHint = Some(true),
    idempotentHint = Some(true)
  )
  def calculate(
      @Param("First number") a: Double,
      @Param("Second number") b: Double,
      @Param(
        "Operation to perform (add, subtract, multiply, divide)",
        required = false
      ) operation: String = "add"
  ): String =
    val result = operation.toLowerCase match
      case "add" | "+" => a + b
      case "subtract" | "-" => a - b
      case "multiply" | "*" => a * b
      case "divide" | "/" =>
        if (b == 0) throw new IllegalArgumentException("Cannot divide by zero")
        else a / b
      case _ => throw new IllegalArgumentException(s"Unknown operation: $operation")

    CalculatorResult(operation, List(a, b), result).toJsonPretty

  /** Enum parameters — the Scala 3 enum is reflected into the JSON schema as a `string` with an
    * `enum` constraint.
    */
  @Tool(
    name = Some("transform"),
    description = Some("Transform text using various operations"),
    readOnlyHint = Some(true),
    idempotentHint = Some(true)
  )
  def transformText(
      text: String,
      transformation: TransformationType
  ): String =
    transformation match
      case TransformationType.uppercase => text.toUpperCase
      case TransformationType.lowercase => text.toLowerCase
      case TransformationType.capitalize => text.split(" ").map(_.capitalize).mkString(" ")
      case TransformationType.reverse => text.reverse

  /** Full `@Param` feature set:
    *   - `examples` — populates the schema's `examples` array so clients can show suggestions
    *   - `required = false` + `Option[...]` — makes parameters optional with explicit `None`
    *   - `schema` — pastes a raw JSON Schema fragment, overriding the derived schema (useful for
    *     enum constraints, regex patterns, or numeric bounds that Scala types can't express)
    */
  @Tool(
    name = Some("search"),
    description = Some("Search with examples, optional filters, and a custom sort schema"),
    readOnlyHint = Some(true),
    idempotentHint = Some(true)
  )
  def search(
      @Param(
        description = "Search query",
        examples = List("scala functional programming", "mcp protocol spec")
      )
      query: String,
      @Param(
        description = "Maximum number of results",
        examples = List("10", "25", "50"),
        required = false
      )
      limit: Option[Int],
      @Param(
        description = "Sort order for results",
        schema = Some(
          """{"type": "string", "enum": ["relevance", "date", "popularity"], "default": "relevance"}"""
        ),
        required = false
      )
      sortBy: Option[String]
  ): String =
    val limitStr = limit.map(l => s"limit=$l").getOrElse("no limit")
    val sortStr = sortBy.getOrElse("relevance")
    s"Searching for '$query' with $limitStr, sorted by $sortStr"

  /** Static resource — no URI placeholders, no parameters. */
  @Resource(
    uri = "static://welcome",
    name = Some("WelcomeMessage"),
    description = Some("A static welcome message.")
  )
  def welcomeResource(): String =
    "Welcome to the FastMCP-Scala Annotated Server!"

  /** Templated resource — the URI contains `{userId}` and the method parameter of the same name is
    * bound to it.
    */
  @Resource(
    uri = "users://{userId}/profile",
    name = Some("UserProfile"),
    description = Some("Dynamically generated user profile based on user ID."),
    mimeType = Some("application/json")
  )
  def userProfileResource(
      @Param("The unique identifier of the user") userId: String
  ): String =
    Map(
      "userId" -> userId,
      "name" -> s"User $userId",
      "email" -> s"user$userId@example.com",
      "joined" -> "2024-01-15"
    ).toJsonPretty

  /** Prompt with no arguments. */
  @Prompt(name = Some("hello_prompt"), description = Some("A simple hello world prompt."))
  def helloPrompt(): List[Message] =
    List(Message(role = Role.User, content = TextContent("Say hello to the world.")))

  /** Prompt with a required argument and an optional one. */
  @Prompt(
    name = Some("greeting_prompt"),
    description = Some("Generates a personalized greeting.")
  )
  def greetingPrompt(
      @Param("The name of the person to greet.") name: String,
      @Param("Optional title (e.g., Dr., Ms.).", required = false) title: String = ""
  ): List[Message] =
    val fullGreeting = if title.nonEmpty then s"$title $name" else name
    List(
      Message(
        role = Role.User,
        content = TextContent(s"Generate a warm greeting for $fullGreeting.")
      )
    )

  override def run: ZIO[Any, Throwable, Unit] =
    for
      server <- ZIO.succeed(FastMcpServer(name = "MacroAnnotatedServer", version = "0.1.0"))
      _ <- ZIO.attempt {
        JSystem.err.println("[AnnotatedServer] Scanning for annotated tools...")
        server.scanAnnotations[AnnotatedServer.type]
      }
      _ <- server.runStdio()
    yield ()

  case class CalculatorResult(
      operation: String,
      numbers: List[Double],
      result: Double
  )

  enum TransformationType:
    case uppercase, lowercase, reverse, capitalize
