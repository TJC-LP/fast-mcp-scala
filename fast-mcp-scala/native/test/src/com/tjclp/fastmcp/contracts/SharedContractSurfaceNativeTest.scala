package com.tjclp.fastmcp.contracts

import org.scalatest.funsuite.AnyFunSuite
import zio.*
import zio.json.*

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.core.StructuredToolResult

class SharedContractSurfaceNativeTest extends AnyFunSuite:

  case class AddArgs(a: Int, b: Int)
  case class AddResult(message: String)
  case class UserProfileArgs(userId: String)

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }

  test("shared typed contracts mount and execute on Scala Native") {
    val server = McpServer("NativeContractServer")

    val tool = McpTool[AddArgs, AddResult](
      name = "typed-add",
      description = Some("Add two numbers")
    ) { args =>
      AddResult((args.a + args.b).toString)
    }
    runUnsafe(server.tool(tool))

    val result = runUnsafe(
      server.toolManager.callTool("typed-add", Map("a" -> 19, "b" -> 23), None)
    )
    result match
      case StructuredToolResult(List(TextContent(text, _, _)), _) => assert(text.contains("42"))
      case other => fail(s"unexpected: $other")
  }

  test("template resource matches and extracts params at runtime (java.util.regex on SN)") {
    // RE2 canary: Scala Native's java.util.regex is RE2-backed (no lookaheads); the template
    // matcher in ResourceManager builds plain anchored groups, which RE2 must accept — this test
    // makes that assumption executable.
    val server = McpServer("NativeTemplateServer")
    val templateResource = McpTemplateResource[UserProfileArgs](
      uriPattern = "users://{userId}/profile",
      arguments = List(ResourceArgument("userId", Some("The user id"), required = true))
    ) { args =>
      s"profile:${args.userId}"
    }
    runUnsafe(server.resource(templateResource))
    val body = runUnsafe(server.resourceManager.readResource("users://42/profile", None))
    assert(body == "profile:42")
  }

  test("custom input codecs participate in decode and schema on Scala Native") {
    case class WrappedId(value: String)
    object WrappedId:
      given McpInputCodec[WrappedId] = McpInputCodec.string(
        """{"type":"string","pattern":"^id_[a-z0-9]+$"}"""
      ) { raw =>
        Either.cond(raw.startsWith("id_"), WrappedId(raw), s"Invalid id '$raw'")
      }
    case class LookupArgs(id: WrappedId)

    val schema = ToolInputSchema.derived[LookupArgs]
    assert(schema.toJsonString.contains("^id_[a-z0-9]+$"))

    val server = McpServer("NativeCodecServer")
    runUnsafe(
      server.tool(
        McpTool[LookupArgs, String](name = "find")(_.id.value)
      )
    )
    val r = runUnsafe(server.toolManager.callTool("find", Map("id" -> "id_abc"), None))
    r match
      case StructuredToolResult(List(TextContent(text, _, _)), _) => assert(text == "id_abc")
      case other => fail(s"unexpected: $other")
  }
