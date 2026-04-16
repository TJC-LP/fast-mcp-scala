package com.tjclp.fastmcp.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class McpContextTest extends AnyFunSuite with Matchers {
  test("McpContext() compatibility constructor should create an empty JVM context") {
    val context = McpContext()

    context.javaExchange shouldBe None
    context.transportContext shouldBe None
  }

  test("getClientCapabilities should return None when javaExchange is None") {
    val context = new JvmMcpContext()

    assert(context.getClientCapabilities.isEmpty)
  }

  test("getClientInfo should return None when javaExchange is None") {
    val context = new JvmMcpContext()

    assert(context.getClientInfo.isEmpty)
  }

  test("getClientCapabilities should return None for base McpContext") {
    val context = McpContext.empty

    assert(context.getClientCapabilities.isEmpty)
  }

  test("copy should preserve McpContext JVM compatibility shape") {
    val context = McpContext()
    val copied = context.copy()

    copied.javaExchange shouldBe None
    copied.transportContext shouldBe None
  }

  // Note: We're skipping the tests with mocked exchange instances due to
  // compatibility issues with Mockito on Java 23. Those tests were validating
  // that the methods correctly delegated to the underlying exchange.
}
