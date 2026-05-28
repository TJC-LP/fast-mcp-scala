package com.tjclp.fastmcp.server

import org.scalatest.funsuite.AnyFunSuite

import com.tjclp.fastmcp.core.wire.{ClientCapabilities, Implementation, RootsCapability}

/** Enhanced tests for McpContext to improve coverage. */
class McpContextExtendedTest extends AnyFunSuite {

  test("McpContext.empty should create a context with no capabilities") {
    val context = McpContext.empty

    assert(context.getClientCapabilities.isEmpty)
    assert(context.getClientInfo.isEmpty)
    assert(context.sessionId.isEmpty)
  }

  test("getClientCapabilities reflects the roots capability snapshot") {
    val caps = ClientCapabilities(roots = Some(RootsCapability(listChanged = Some(true))))
    val context = new McpContext(None, Some(Implementation("c", "0.1")), Some(caps))

    assert(context.getClientCapabilities.flatMap(_.roots).flatMap(_.listChanged).contains(true))
  }
}
