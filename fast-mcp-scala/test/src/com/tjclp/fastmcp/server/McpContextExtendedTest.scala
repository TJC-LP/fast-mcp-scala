package com.tjclp.fastmcp.server

import org.scalatest.funsuite.AnyFunSuite

/** Enhanced tests for McpContext to improve coverage */
class McpContextExtendedTest extends AnyFunSuite {

  test("JvmMcpContext default constructor should create empty context") {
    val context = new JvmMcpContext()

    // Just verify it can be created
    assert(context.javaExchange.isEmpty)
    assert(context.getClientCapabilities.isEmpty)
    assert(context.getClientInfo.isEmpty)
  }

  test("McpContext.empty should create a context with no capabilities") {
    val context = McpContext.empty

    assert(context.getClientCapabilities.isEmpty)
    assert(context.getClientInfo.isEmpty)
  }
}
