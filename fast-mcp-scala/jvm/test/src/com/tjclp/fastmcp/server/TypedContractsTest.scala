package com.tjclp.fastmcp.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import io.circe.parser.parse
import sttp.tapir.generic.auto.*
import zio.*
import zio.json.*

import com.tjclp.fastmcp.{given, *}

class TypedContractsTest extends AnyFunSuite with Matchers:

  case class AddArgs(a: Int, b: Int)
  case class AddResult(sum: Int)
  case class GreetingArgs(name: String)
  case class UserProfileArgs(userId: String)
  case class AddressArgs(
      @Param(description = "Street name", examples = List("Main St"))
      street: String,
      @Param(description = "Postal code", required = false)
      postalCode: Option[String] = None
  )
  case class ProfileArgs(
      @Param(description = "Display name")
      name: String,
      @Param(description = "Primary address")
      address: AddressArgs,
      @Param(
        description = "Current account status",
        schema = Some("""{"type":"string","enum":["active","disabled"],"description":"Current account status"}""")
      )
      status: String
  )

  given JsonEncoder[AddResult] = DeriveJsonEncoder.gen[AddResult]

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }

  test("typed tool contracts decode request types and encode structured results") {
    val server = McpServer("TypedToolServer")

    runUnsafe(
      server.tool(
        McpTool[AddArgs, AddResult](
          name = "typed-add",
          description = Some("Add two numbers")
        ) { args =>
          AddResult(args.a + args.b)
        }
      )
    )

    val schema = server.toolManager.getToolDefinition("typed-add").get.inputSchema.toJsonString
    schema should include(""""a"""")
    schema should include(""""b"""")

    val result = runUnsafe(
      server.toolManager.callTool("typed-add", Map("a" -> 2, "b" -> 5), None)
    )

    result match
      case List(TextContent(text, _, _)) =>
        text shouldBe """{"sum":7}"""
      case other =>
        fail(s"Unexpected typed tool result: $other")
  }

  test("typed contextual tool contracts receive McpContext") {
    val server = McpServer("TypedContextServer")

    runUnsafe(
      server.tool(
        McpTool[AddArgs, String](
          name = "typed-context",
          description = Some("Context-aware typed tool")
        ).contextual { (args, ctxOpt) =>
          val suffix = if ctxOpt.isDefined then "ctx" else "missing"
          s"${args.a + args.b}:$suffix"
        }
      )
    )

    val result = runUnsafe(
      server.toolManager.callTool(
        "typed-context",
        Map("a" -> 1, "b" -> 4),
        Some(McpContext.empty)
      )
    )

    result shouldBe List(TextContent("5:ctx"))
  }

  test("typed prompt and resource contracts mount through the existing managers") {
    val server = McpServer("TypedSupportServer")

    runUnsafe(
      server.prompt(
        McpPrompt[GreetingArgs](
          name = "typed-prompt",
          arguments = List(PromptArgument("name", Some("The name to greet"), required = true))
        ) { args =>
          List(Message(Role.User, TextContent(s"Hello ${args.name}!")))
        }
      )
    )

    runUnsafe(
      server.resource(
        McpStaticResource(
          uri = "static://welcome",
          description = Some("Welcome message")
        )("welcome")
      )
    )

    runUnsafe(
      server.resource(
        McpTemplateResource[UserProfileArgs](
          uriPattern = "users://{userId}/profile",
          description = Some("User profile"),
          arguments = List(ResourceArgument("userId", Some("The user id"), required = true))
        ) { args =>
          s"profile:${args.userId}"
        }
      )
    )

    val promptResult =
      runUnsafe(server.promptManager.getPrompt("typed-prompt", Map("name" -> "Ada"), None))
    promptResult shouldBe List(Message(Role.User, TextContent("Hello Ada!")))

    val staticResult = runUnsafe(server.resourceManager.readResource("static://welcome", None))
    staticResult shouldBe "welcome"

    val templateResult =
      runUnsafe(server.resourceManager.readResource("users://42/profile", None))
    templateResult shouldBe "profile:42"
  }

  test("typed request schemas include @Param metadata on fields and nested fields") {
    val schema = parse(ToolInputSchema.derived[ProfileArgs].toJsonString).toOption.get

    val nameDesc =
      schema.hcursor.downField("properties").downField("name").downField("description").as[String]
    nameDesc shouldBe Right("Display name")

    val addressDesc =
      schema.hcursor.downField("properties").downField("address").downField("description").as[String]
    addressDesc shouldBe Right("Primary address")

    val nestedStreetDesc =
      schema.hcursor
        .downField("properties")
        .downField("address")
        .downField("properties")
        .downField("street")
        .downField("description")
        .as[String]
    nestedStreetDesc shouldBe Right("Street name")

    val nestedStreetExamples =
      schema.hcursor
        .downField("properties")
        .downField("address")
        .downField("properties")
        .downField("street")
        .downField("examples")
        .as[List[String]]
    nestedStreetExamples shouldBe Right(List("Main St"))

    val addressRequired =
      schema.hcursor
        .downField("properties")
        .downField("address")
        .downField("required")
        .as[List[String]]
        .getOrElse(Nil)
    addressRequired should not contain "postalCode"

    val statusEnum =
      schema.hcursor.downField("properties").downField("status").downField("enum").as[List[String]]
    statusEnum shouldBe Right(List("active", "disabled"))
  }
