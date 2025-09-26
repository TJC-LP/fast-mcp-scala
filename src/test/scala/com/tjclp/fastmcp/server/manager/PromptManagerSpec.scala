package com.tjclp.fastmcp.server.manager

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.*

import com.tjclp.fastmcp.core.Message
import com.tjclp.fastmcp.core.PromptArgument
import com.tjclp.fastmcp.core.PromptDefinition
import com.tjclp.fastmcp.core.Role
import com.tjclp.fastmcp.core.TextContent

/** Tests for PromptManager argument validation and rendering behavior.
  */
class PromptManagerSpec extends AnyFlatSpec with Matchers {

  "getPrompt" should "fail when required arguments are missing" in {
    val pm = new PromptManager
    val promptDef = PromptDefinition(
      "p1",
      Some("desc"),
      Some(List(PromptArgument("a", Some("arg a"), required = true)))
    )
    // Register prompt with simple handler
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe
        .run(
          pm.addPrompt(
            "p1",
            (_: Map[String, Any]) => ZIO.succeed(List(Message(Role.User, TextContent("ok")))),
            promptDef
          )
        )
        .getOrThrowFiberFailure()
    }
    // Missing required arg 'a'
    val ex = intercept[Throwable] {
      Unsafe.unsafe { implicit u =>
        Runtime.default.unsafe.run(pm.getPrompt("p1", Map.empty, None)).getOrThrowFiberFailure()
      }
    }
    ex.getMessage should include("Missing required arguments")
  }

  it should "return handler result when required arguments provided" in {
    val pm = new PromptManager
    val promptDef = PromptDefinition(
      "p2",
      None,
      Some(List(PromptArgument("x", None, required = true)))
    )
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe
        .run(
          pm.addPrompt(
            "p2",
            (_: Map[String, Any]) =>
              ZIO.succeed(List(Message(Role.Assistant, TextContent("done")))),
            promptDef
          )
        )
        .getOrThrowFiberFailure()
    }
    val result = Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run(pm.getPrompt("p2", Map("x" -> 42), None)).getOrThrowFiberFailure()
    }
    result shouldBe List(Message(Role.Assistant, TextContent("done")))
  }

  it should "fail when prompt not found" in {
    val pm = new PromptManager
    val ex = intercept[Throwable] {
      Unsafe.unsafe { implicit u =>
        Runtime.default.unsafe
          .run(pm.getPrompt("missing", Map.empty, None))
          .getOrThrowFiberFailure()
      }
    }
    ex.getMessage should include("not found")
  }

  "listDefinitions" should "return empty list when no prompts and populated after registration" in {
    val pm = new PromptManager
    pm.listDefinitions() shouldBe Nil

    val defn1 = PromptDefinition("p", None, None)
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe
        .run(pm.addPrompt("p", _ => ZIO.succeed(Nil), defn1))
        .getOrThrowFiberFailure()
    }
    pm.listDefinitions() shouldBe List(defn1)
  }
}
