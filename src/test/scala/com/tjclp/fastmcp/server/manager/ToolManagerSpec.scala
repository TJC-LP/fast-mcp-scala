package com.tjclp.fastmcp.server.manager

import com.tjclp.fastmcp.TestFixtures.*
import com.tjclp.fastmcp.core.ToolDefinition
import com.tjclp.fastmcp.server.McpContext
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.*

/** Tests for ToolManager registration, duplicate handling, and callTool behavior.
  */
class ToolManagerSpec extends AnyFlatSpec with Matchers {

  "addTool" should "overwrite existing tool by default and update definition" in {
    val manager = new ToolManager
    val def1 = ToolDefinition("t1", Some("first"), Right("schema1"))
    val def2 = ToolDefinition("t1", Some("second"), Right("schema2"))
    // First registration
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe
        .run(manager.addTool("t1", (_: Map[String, Any], _) => ZIO.succeed("ok"), def1))
        .getOrThrowFiberFailure()
    }
    // Second registration should overwrite without error
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe
        .run(manager.addTool("t1", (_: Map[String, Any], _) => ZIO.succeed("ok"), def2))
        .getOrThrowFiberFailure()
    }
    // Definition should be updated
    manager.getToolDefinition("t1").get.description shouldBe Some("second")
  }

  it should "fail when duplicates are disallowed and warnOnDuplicates is false" in {
    val manager = new ToolManager
    val options = ToolRegistrationOptions(warnOnDuplicates = false)
    val def1 = ToolDefinition("t2", None, Right("s1"))
    val def2 = ToolDefinition("t2", None, Right("s2"))
    // First registration succeeds
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe
        .run(manager.addTool("t2", (_, _) => ZIO.succeed(()), def1, options))
        .getOrThrowFiberFailure()
    }
    // Second registration should fail
    val ex = intercept[Throwable] {
      Unsafe.unsafe { implicit u =>
        Runtime.default.unsafe
          .run(manager.addTool("t2", (_, _) => ZIO.succeed(()), def2, options))
          .getOrThrowFiberFailure()
      }
    }
    ex.getMessage should include("already exists")
  }

  "callTool" should "fail when tool not found" in {
    val manager = new ToolManager
    val ex = intercept[Throwable] {
      Unsafe.unsafe { implicit u =>
        Runtime.default.unsafe
          .run(manager.callTool("missing", Map.empty, None))
          .getOrThrowFiberFailure()
      }
    }
    ex.getMessage should include("not found")
  }

  it should "execute handler and return result" in {
    val manager = new ToolManager
    val defn = ToolDefinition("t3", None, Right("{}"))
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe
        .run(manager.addTool("t3", (_, _) => ZIO.succeed(123), defn))
        .getOrThrowFiberFailure()
    }
    val result = Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run(manager.callTool("t3", Map.empty, None)).getOrThrowFiberFailure()
    }
    result shouldBe 123
  }

  it should "pass context to handler" in {
    val manager = new ToolManager
    val captured = new java.util.concurrent.atomic.AtomicReference[Option[McpContext]](None)
    val handler: ContextualToolHandler = (_, ctx) => ZIO.succeed { captured.set(ctx); "ctx-ok" }
    val defn = ToolDefinition("t4", None, Right("{}"))
    // Register with context-aware handler
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run(manager.addTool("t4", handler, defn)).getOrThrowFiberFailure()
    }
    // Call with dummy context
    val result = Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe
        .run(manager.callTool("t4", Map.empty, dummyContext))
        .getOrThrowFiberFailure()
    }
    result shouldBe "ctx-ok"
    captured.get() shouldBe dummyContext
  }
}
