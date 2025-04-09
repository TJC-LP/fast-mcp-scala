package fastmcp.examples

import fastmcp.core.*
import fastmcp.server.*
import fastmcp.macros.McpToolRegistrationMacro.* // Import scanAnnotations extension method
import zio.*
import zio.json.*
import java.lang.System as JSystem

/**
 * Enhanced server example demonstrating the zero-boilerplate experience with @Tool annotations.
 * 
 * This example shows:
 * 1. Tools defined with @Tool annotations are automatically registered at compile-time
 * 2. Schema generation happens automatically using JsonSchemaMacro
 * 3. Handler mapping is done using MapToFunctionMacro
 * 4. We just call server.scanAnnotations[AnnotatedServer.type] to register everything
 */
object AnnotatedServer extends ZIOAppDefault:
  
  // Define a result type for our calculator
  case class CalculatorResult(
    operation: String,
    numbers: List[Double],
    result: Double
  )
  
  // JSON codec for the result
  given JsonEncoder[CalculatorResult] = DeriveJsonEncoder.gen[CalculatorResult]
  given JsonDecoder[CalculatorResult] = DeriveJsonDecoder.gen[CalculatorResult]
  
  /**
   * Simple tool that adds two numbers.
   * The @Tool annotation will:
   * 1. Be scanned by scanAnnotations[AnnotatedServer.type]
   * 2. Generate a JSON schema for the parameters
   * 3. Create a handler using MapToFunctionMacro
   */
  @Tool(
    name = Some("add"),
    // description = Some("Add two numbers together")
  )
  def add(
    @Param("First number") a: Int,
    @Param("Second number") b: Int
  ): Int = a + b
  
  /**
   * More complex calculator tool that handles different operations.
   */
  @Tool(
    name = Some("calculator"),
    description = Some("Perform a calculation with two numbers"),
    tags = List("math", "calculation")
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
  
  /**
   * String transformation tool.
   */
  @Tool(
    name = Some("transform"),
    description = Some("Transform text using various operations"),
    tags = List("string", "text")
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
    
  // --- Resource Examples ---
  
  /**
   * A static resource returning plain text.
   * Annotated with @Resource.
   */
  @Resource(
    uri = "static://welcome",
    name = Some("WelcomeMessage"),
    description = Some("A static welcome message."),
  )
  def welcomeResource(): String =
    "Welcome to the FastMCP-Scala Annotated Server!"
  
  /**
   * A template resource that takes a user ID from the URI.
   * Annotated with @Resource. The URI pattern {userId} matches the parameter name.
   */
  @Resource(
    uri = "users://{userId}/profile",
    name = Some("UserProfile"),
    description = Some("Dynamically generated user profile."),
    mimeType = Some("application/json")
  )
  def userProfileResource(userId: String): Map[String, String] =
    // In a real app, fetch user data based on userId
    Map("userId" -> userId, "name" -> s"User $userId", "email" -> s"user$userId@example.com")
  
  // --- Prompt Examples ---
  
  /**
   * A simple prompt with no arguments.
   * Annotated with @Prompt.
   */
  @Prompt(
    name = Some("hello_prompt"),
    description = Some("A simple hello world prompt.")
  )
  def helloPrompt(): List[Message] =
    List(
      Message(role = Role.User, content = TextContent("Say hello to the world."))
    )
  
  /**
   * A prompt with required and optional arguments.
   * Uses @PromptParam for documentation. Annotated with @Prompt.
   */
  @Prompt(
    name = Some("greeting_prompt"),
    description = Some("Generates a personalized greeting.")
  )
  def greetingPrompt(
    @PromptParam("The name of the person to greet.") name: String,
    @PromptParam("Optional title (e.g., Dr., Ms.).", required = false) title: String = ""
  ): List[Message] =
    val fullGreeting = if title.nonEmpty then s"$title $name" else name
    List(
      Message(role = Role.User, content = TextContent(s"Generate a warm greeting for $fullGreeting."))
    )
  
  override def run: ZIO[Any, Throwable, Unit] =
    for
      // Create server instance
      server <- ZIO.succeed(FastMCPScala(
        name = "MacroAnnotatedServer",
        version = "0.1.0"
      ))
      
      // Process tools using the scanAnnotations macro extension method
      _ <- ZIO.attempt {
        JSystem.err.println("[Server] Scanning for annotated tools...")
        // This macro finds all methods with @Tool annotations in AnnotatedServer
        // and registers them with the server
        server.scanAnnotations[AnnotatedServer.type]
      }

      // Run the server
      _ <- server.runStdio()
    yield ()