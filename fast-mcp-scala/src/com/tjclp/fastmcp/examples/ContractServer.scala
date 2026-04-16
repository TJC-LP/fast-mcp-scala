package com.tjclp.fastmcp.examples

import sttp.tapir.generic.auto.*
import zio.*
import zio.json.*

import com.tjclp.fastmcp.{*, given}

/** Example server using the shared typed contract layer instead of raw map handlers.
  */
object ContractServer extends ZIOAppDefault:

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

  given JsonEncoder[AddResult] = DeriveJsonEncoder.gen[AddResult]

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

  override def run: ZIO[Any, Throwable, Unit] =
    for
      server <- ZIO.succeed(McpServer(name = "ContractServer", version = "0.1.0"))
      _ <- server.tool(addTool)
      _ <- server.prompt(greetingPrompt)
      _ <- server.resource(welcomeResource)
      _ <- server.resource(userProfileResource)
      _ <- server.runStdio()
    yield ()
