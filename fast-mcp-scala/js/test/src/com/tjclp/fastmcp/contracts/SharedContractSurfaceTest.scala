package com.tjclp.fastmcp.contracts

import org.scalatest.funsuite.AnyFunSuite
import zio.*
import zio.json.*

import com.tjclp.fastmcp.{given, *}

class SharedContractSurfaceTest extends AnyFunSuite:

  case class AddArgs(a: Int, b: Int)
  case class AddResult(message: String)
  case class GreetingArgs(name: String)
  case class UserProfileArgs(userId: String)

  given JsonDecoder[AddArgs] = DeriveJsonDecoder.gen[AddArgs]
  given JsonEncoder[AddResult] = DeriveJsonEncoder.gen[AddResult]
  given JsonDecoder[GreetingArgs] = DeriveJsonDecoder.gen[GreetingArgs]
  given JsonDecoder[UserProfileArgs] = DeriveJsonDecoder.gen[UserProfileArgs]

  test("shared typed contracts compile on Scala.js") {
    val schema = ToolInputSchema.unsafeFromJsonString(
      """{"type":"object","properties":{"a":{"type":"integer"},"b":{"type":"integer"}}}"""
    )

    val tool = McpTool.withSchema[AddArgs, AddResult](
      name = "typed-add",
      description = Some("Add two numbers"),
      inputSchema = schema
    ) { args =>
      AddResult((args.a + args.b).toString)
    }

    val prompt = McpPrompt[GreetingArgs](
      name = "typed-prompt",
      arguments = List(PromptArgument("name", Some("The name"), required = true))
    ) { args =>
      List(Message(Role.User, TextContent(s"Hello ${args.name}!")))
    }

    val staticResource =
      McpStaticResource("static://welcome", description = Some("Welcome message"))("welcome")

    val templateResource = McpTemplateResource[UserProfileArgs](
      uriPattern = "users://{userId}/profile",
      arguments = List(ResourceArgument("userId", Some("The user id"), required = true))
    ) { args =>
      s"profile:${args.userId}"
    }

    assert(tool.definition.name == "typed-add")
    assert(prompt.definition.name == "typed-prompt")
    assert(staticResource.definition.uri == "static://welcome")
    assert(templateResource.definition.isTemplate)
  }

// GH #78: zero-boilerplate enum support must hold on Scala.js too (macro expansion runs at JS
// compile). Top-level fixtures: no hand-written givens anywhere.
enum JsMood:
  case bright, dim

case class JsMoodArgs(mood: JsMood, label: Option[String])

class EnumContractSurfaceJsTest extends AnyFunSuite:

  test("enum-field contract derives decoder and schema with zero user givens on JS") {
    val schemaJson = ToolInputSchema.derived[JsMoodArgs].toJsonString
    assert(schemaJson.contains("\"enum\""), s"missing enum constraint: $schemaJson")
    assert(schemaJson.contains("bright"), s"missing enum value: $schemaJson")

    val decoder = summon[McpDecoder[JsMoodArgs]]
    val decoded = decoder.decode(
      "args",
      Map[String, Any]("mood" -> "dim"),
      com.tjclp.fastmcp.codec.DefaultDecodeContext.default
    )
    assert(decoded == JsMoodArgs(JsMood.dim, None))

    val encoder = summon[McpEncoder[JsMoodArgs]]
    val structured = encoder.encodeStructured(JsMoodArgs(JsMood.bright, Some("x"))).map(_.toString)
    assert(structured.exists(_.contains("bright")), s"structured: $structured")
  }
