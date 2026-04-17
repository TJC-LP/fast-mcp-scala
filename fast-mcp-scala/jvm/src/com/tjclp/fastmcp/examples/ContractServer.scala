package com.tjclp.fastmcp.examples

import sttp.tapir.generic.auto.*
import zio.*
import zio.json.*

import com.tjclp.fastmcp.{*, given}

/** Typed-contract server — the explicit, macro-free counterpart to [[AnnotatedServer]].
  *
  * Instead of reflecting methods at compile time, this style defines tools, prompts, and resources
  * as first-class values using `McpTool.derived`, `McpPrompt`, `McpStaticResource`, and
  * `McpTemplateResource`. The same values work unchanged on Scala.js — the JVM server still runs on
  * the Java MCP SDK, but the *definitions* (names, schemas, handlers) are cross-platform.
  *
  * Why you might prefer this path:
  *
  *   - **Testability**: contracts are plain values, easy to unit-test without standing up a server.
  *   - **Composability**: store them in a `List`, derive them from configuration, or mix across
  *     modules.
  *   - **Cross-platform sharing**: put your `McpTool.derived[...]` definitions in a module that
  *     cross-compiles to Scala.js, and a client (or test harness) can reuse the same schema and
  *     request types. See `fast-mcp-scala/js/test/.../SharedContractSurfaceTest.scala` for proof
  *     that these exact types compile under Scala.js.
  *
  * What Scala.js buys you: typed definitions shared across JVM and JS. The server *runtime* is
  * JVM-only — there is no Bun/Node MCP server transport in this project.
  */
object ContractServer extends ZIOAppDefault:

  // 1️⃣  Argument shapes — `@Param` metadata flows into the derived JSON schema.
  case class AddArgs(
      @Param(description = "The first number to add", examples = List("2"))
      a: Int,
      @Param(description = "The second number to add", examples = List("3"))
      b: Int
  )
  case class AddResult(sum: Int)

  case class GreetingArgs(
      @Param(description = "The name to greet", examples = List("Ada"))
      name: String
  )

  case class UserProfileArgs(
      @Param(description = "The user id from the resource URI")
      userId: String
  )

  // 2️⃣  `McpEncoder` falls back to any `JsonEncoder[A]` — provide one for your return type and
  //     FastMCP-Scala will serialize the result as `TextContent` automatically.
  given JsonEncoder[AddResult] = DeriveJsonEncoder.gen[AddResult]

  // 3️⃣  Contracts as values.
  private val addTool = McpTool.derived[AddArgs, AddResult](
    name = "typed-add",
    description = Some("Add two numbers using a typed request/response contract")
  ) { args =>
    ZIO.succeed(AddResult(args.a + args.b))
  }

  private val greetingPrompt = McpPrompt[GreetingArgs](
    name = "typed-greeting",
    description = Some("Render a greeting prompt from a typed request"),
    arguments = List(PromptArgument("name", Some("The name to greet"), required = true))
  ) { args =>
    ZIO.succeed(List(Message(Role.User, TextContent(s"Hello ${args.name}!"))))
  }

  private val welcomeResource = McpStaticResource(
    uri = "static://welcome",
    description = Some("A static welcome message")
  ) {
    ZIO.succeed("Welcome to typed FastMCP-Scala")
  }

  private val userProfileResource = McpTemplateResource[UserProfileArgs](
    uriPattern = "users://{userId}/profile",
    description = Some("A typed resource template"),
    arguments = List(ResourceArgument("userId", Some("The user id"), required = true))
  ) { args =>
    ZIO.succeed(s"Profile for ${args.userId}")
  }

  // 4️⃣  Mount them on a server — `server.tool(...)`, `server.prompt(...)`, `server.resource(...)`
  //     accept the typed contracts directly.
  override def run: ZIO[Any, Throwable, Unit] =
    for
      server <- ZIO.succeed(McpServer(name = "ContractServer", version = "0.1.0"))
      _ <- server.tool(addTool)
      _ <- server.prompt(greetingPrompt)
      _ <- server.resource(welcomeResource)
      _ <- server.resource(userProfileResource)
      _ <- server.runStdio()
    yield ()
