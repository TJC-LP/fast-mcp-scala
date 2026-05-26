package com.tjclp.fastmcp.examples

import sttp.tapir.generic.auto.*
import zio.json.*

import com.tjclp.fastmcp.{*, given}

/** Typed-contract server — the explicit, macro-free counterpart to [[AnnotatedServer]]. Handlers
  * return plain values (or `ZIO` / `Either[Throwable, _]` / `Try` — the `ToHandlerEffect` typeclass
  * lifts them).
  *
  * The same typed contracts compile unchanged on Scala.js — on the JVM they mount onto the Java MCP
  * SDK backend, on JS onto the TS SDK backend. Put the definitions in a cross-platform module and
  * share them.
  */
object ContractServer extends McpServerApp[Stdio, ContractServer.type]:

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

  override def name: String = "ContractServer"

  override val tools: List[McpTool[?, ?, Any]] = List(
    McpTool[AddArgs, AddResult, Any](
      name = "typed-add",
      description = Some("Add two numbers using a typed request/response contract")
    ) { args =>
      AddResult(args.a + args.b)
    }
  )

  override val prompts: List[McpPrompt[?, Any]] = List(
    McpPrompt[GreetingArgs, Any](
      name = "typed-greeting",
      description = Some("Render a greeting prompt from a typed request"),
      arguments = List(PromptArgument("name", Some("The name to greet"), required = true))
    ) { args =>
      List(Message(Role.User, TextContent(s"Hello ${args.name}!")))
    }
  )

  override val staticResources: List[McpStaticResource[Any]] = List(
    McpStaticResource[Any](
      uri = "static://welcome",
      description = Some("A static welcome message")
    )("Welcome to typed fast-mcp-scala")
  )

  override val templateResources: List[McpTemplateResource[?, Any]] = List(
    McpTemplateResource[UserProfileArgs, Any](
      uriPattern = "users://{userId}/profile",
      description = Some("A typed resource template"),
      arguments = List(ResourceArgument("userId", Some("The user id"), required = true))
    ) { args =>
      s"Profile for ${args.userId}"
    }
  )
