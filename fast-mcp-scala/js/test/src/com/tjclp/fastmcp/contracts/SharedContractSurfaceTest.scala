package com.tjclp.fastmcp.contracts

import org.scalatest.funsuite.AnyFunSuite
import zio.*
import zio.json.*

import com.tjclp.fastmcp.*

class SharedContractSurfaceTest extends AnyFunSuite:

  case class AddArgs(a: Int, b: Int)
  case class AddResult(message: String)
  case class GreetingArgs(name: String)
  case class UserProfileArgs(userId: String)

  given JsonEncoder[AddResult] = DeriveJsonEncoder.gen[AddResult]

  test("shared typed contracts compile on Scala.js") {
    val schema = ToolInputSchema.unsafeFromJsonString(
      """{"type":"object","properties":{"a":{"type":"integer"},"b":{"type":"integer"}}}"""
    )

    val tool = McpTool[AddArgs, AddResult](
      name = "typed-add",
      description = Some("Add two numbers"),
      inputSchema = schema
    ) { args =>
      ZIO.succeed(AddResult((args.a + args.b).toString))
    }

    val prompt = McpPrompt[GreetingArgs](
      name = "typed-prompt",
      arguments = List(PromptArgument("name", Some("The name"), required = true))
    ) { args =>
      ZIO.succeed(List(Message(Role.User, TextContent(s"Hello ${args.name}!"))))
    }

    val staticResource =
      McpStaticResource("static://welcome", description = Some("Welcome message"))(
        ZIO.succeed("welcome")
      )

    val templateResource = McpTemplateResource[UserProfileArgs](
      uriPattern = "users://{userId}/profile",
      arguments = List(ResourceArgument("userId", Some("The user id"), required = true))
    ) { args =>
      ZIO.succeed(s"profile:${args.userId}")
    }

    assert(tool.definition.name == "typed-add")
    assert(prompt.definition.name == "typed-prompt")
    assert(staticResource.definition.uri == "static://welcome")
    assert(templateResource.definition.isTemplate)
  }
