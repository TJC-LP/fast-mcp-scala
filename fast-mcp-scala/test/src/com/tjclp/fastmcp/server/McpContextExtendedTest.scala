package com.tjclp.fastmcp.server

import org.scalatest.funsuite.AnyFunSuite

/** Enhanced tests for McpContext to improve coverage */
class McpContextExtendedTest extends AnyFunSuite {

  test("McpContext default constructor should create empty context") {
    val context = McpContext()

    // Just verify it can be created
    assert(context.javaExchange.isEmpty)
    assert(context.getClientCapabilities.isEmpty)
    assert(context.getClientInfo.isEmpty)
  }
}
