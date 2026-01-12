package com.tjclp.fastmcp

import io.modelcontextprotocol.json.TypeRef
import io.modelcontextprotocol.server.McpAsyncServerExchange
import io.modelcontextprotocol.spec.McpLoggableSession
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpSchema as Schema
import reactor.core.publisher.Mono

import com.tjclp.fastmcp.server.McpContext

/** Test fixtures and helpers for MCP tests.
  */
@SuppressWarnings(Array("org.wartremover.warts.Null"))
object TestFixtures {

  class NoopLoggableSession extends McpLoggableSession {
    override def setMinLoggingLevel(level: Schema.LoggingLevel): Unit = ()
    override def isNotificationForLevelAllowed(level: Schema.LoggingLevel): Boolean = false

    override def sendRequest[T](
        method: String,
        params: Object,
        typeRef: TypeRef[T]
    ): Mono[T] = Mono.empty()
    override def sendNotification(method: String): Mono[Void] = Mono.empty()
    override def sendNotification(method: String, obj: Object): Mono[Void] = Mono.empty()
    override def closeGracefully(): Mono[Void] = Mono.empty()
    override def close(): Unit = ()
  }

  /** Mock implementation of McpAsyncServerExchange for testing contexts. */
  class MockServerExchange(clientInfo: McpSchema.Implementation)
      extends McpAsyncServerExchange(
        new NoopLoggableSession(),
        new McpSchema.ClientCapabilities(
          null,
          new McpSchema.ClientCapabilities.RootCapabilities(true),
          new McpSchema.ClientCapabilities.Sampling(),
          new McpSchema.ClientCapabilities.Elicitation()
        ),
        clientInfo
      ) {
    override def getClientInfo(): McpSchema.Implementation = clientInfo

    override def getClientCapabilities(): McpSchema.ClientCapabilities =
      new McpSchema.ClientCapabilities(
        null,
        new McpSchema.ClientCapabilities.RootCapabilities(true),
        new McpSchema.ClientCapabilities.Sampling(),
        new McpSchema.ClientCapabilities.Elicitation()
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
