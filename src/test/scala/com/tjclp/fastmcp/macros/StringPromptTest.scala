package com.tjclp.fastmcp.macros

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.RegistrationMacro.*
import com.tjclp.fastmcp.server.FastMcpServer
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Tests that a prompt returning a String is properly converted to a List with a single User
  * message.
  */
class StringPromptTest extends AnyFlatSpec with Matchers {

  object TestPrompts {

    @Prompt(name = Some("string_prompt"))
    def stringPrompt(@PromptParam("A parameter") param: String): String =
      s"This is a prompt with parameter: $param"

    @Prompt(name = Some("list_prompt"))
    def listPrompt(@PromptParam("A parameter") param: String): List[Message] =
      List(Message(Role.User, TextContent(s"This is a message with parameter: $param")))
  }

  "String prompt processor" should "convert string returns to List with one User message" in {
    // Create server and register prompts
    val server = FastMcpServer("TestServer", "0.1.0")
    server.scanAnnotations[TestPrompts.type]

    // Get the prompt result for string prompt
    val stringResult = zio.Unsafe.unsafe { implicit unsafe =>
      zio.Runtime.default.unsafe
        .run(
          server.promptManager.getPrompt(
            "string_prompt",
            Map("param" -> "test value"),
            None
          )
        )
        .getOrThrowFiberFailure()
    }

    // Verify string result
    stringResult should have size 1
    stringResult.head.role should be(Role.User)
    stringResult.head.content.asInstanceOf[TextContent].text should include("test value")

    // Get the prompt result for list prompt
    val listResult = zio.Unsafe.unsafe { implicit unsafe =>
      zio.Runtime.default.unsafe
        .run(
          server.promptManager.getPrompt(
            "list_prompt",
            Map("param" -> "test value"),
            None
          )
        )
        .getOrThrowFiberFailure()
    }

    // Verify list result
    listResult should have size 1
    listResult.head.role should be(Role.User)
    listResult.head.content.asInstanceOf[TextContent].text should include("test value")
  }
}
