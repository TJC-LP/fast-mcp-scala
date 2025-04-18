package com.tjclp.fastmcp

import com.tjclp.fastmcp.server.McpContext
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.server.McpAsyncServerExchange

/** Test fixtures and helpers for MCP tests.
  */
object TestFixtures {

  /** Mock implementation of McpAsyncServerExchange for testing contexts. */
  class MockServerExchange(clientInfo: McpSchema.Implementation)
      extends McpAsyncServerExchange(null, null, clientInfo) {
    override def getClientInfo(): McpSchema.Implementation = clientInfo

    override def getClientCapabilities(): McpSchema.ClientCapabilities =
      new McpSchema.ClientCapabilities(
        null,
        new McpSchema.ClientCapabilities.RootCapabilities(true),
        new McpSchema.ClientCapabilities.Sampling()
      )
  }

  /** Dummy `McpContext` instance used across multiple tests.
    *
    * Using a `lazy val` ensures the exact same object reference is returned on every access, which
    * allows simple equality checks (e.g. `shouldBe`) to succeed without having to implement a
    * custom `equals` method for the underlying Java `McpAsyncServerExchange`.
    */
  lazy val dummyContext: Option[McpContext] = {
    val mockExchange =
      new MockServerExchange(new McpSchema.Implementation("dummy", "0.0"))
    Some(McpContext(javaExchange = Some(mockExchange)))
  }
}
