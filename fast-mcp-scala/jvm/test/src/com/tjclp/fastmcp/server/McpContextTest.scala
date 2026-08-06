package com.tjclp.fastmcp.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import com.tjclp.fastmcp.core.wire.{ClientCapabilities, Implementation, SamplingCapability}

/** Unit tests for the native, platform-neutral `McpContext`. */
class McpContextTest extends AnyFunSuite with Matchers {

  test("McpContext.empty exposes no session, client info, or capabilities") {
    val context = McpContext.empty

    context.sessionId shouldBe None
    context.getClientInfo shouldBe None
    context.getClientCapabilities shouldBe None
  }

  test("a context snapshots the client info / capabilities it is built with") {
    val info = Implementation("client", "1.0.0")
    val caps = ClientCapabilities(sampling = Some(SamplingCapability()))
    val context = new McpContext(None, Some(info), Some(caps))

    context.getClientInfo shouldBe Some(info)
    context.getClientCapabilities shouldBe Some(caps)
  }
}
