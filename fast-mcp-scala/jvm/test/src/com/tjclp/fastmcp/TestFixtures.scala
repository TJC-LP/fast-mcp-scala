package com.tjclp.fastmcp

import com.tjclp.fastmcp.core.wire.{
  ClientCapabilities,
  ElicitationCapability,
  Implementation,
  RootsCapability,
  SamplingCapability
}
import com.tjclp.fastmcp.server.McpContext

/** Test fixtures and helpers for MCP tests.
  *
  * After the native-core rewrite there is no Java SDK exchange to mock: an [[McpContext]] simply
  * snapshots the client info/capabilities captured at `initialize`. These fixtures build such a
  * context directly via the package-private constructor.
  */
object TestFixtures {

  /** Stand-in client identity used across context tests. */
  val dummyClientInfo: Implementation = Implementation(name = "dummy", version = "0.0")

  /** Stand-in client capabilities: roots (listChanged), sampling, elicitation. */
  val dummyClientCapabilities: ClientCapabilities = ClientCapabilities(
    roots = Some(RootsCapability(listChanged = Some(true))),
    sampling = Some(SamplingCapability()),
    elicitation = Some(ElicitationCapability())
  )

  /** Dummy `McpContext` used across multiple tests.
    *
    * A `lazy val` guarantees the same reference on every access, so plain equality checks
    * (`shouldBe`) succeed without needing a custom `equals`.
    */
  lazy val dummyContext: Option[McpContext] =
    Some(new McpContext(None, Some(dummyClientInfo), Some(dummyClientCapabilities)))
}
