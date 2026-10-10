package com.tjclp.fastmcp.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*

import com.tjclp.fastmcp.server.router.Session
import com.tjclp.fastmcp.server.transport.JvmTransportBackend.given
import com.tjclp.fastmcp.server.transport.MessageLoop

/** `McpServerSettings.instructions` reaches the wire in both lifecycle shapes; the default omits the field. */
class ServerInstructionsTest extends AnyFunSuite with Matchers:

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private val initialize =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""

  // 2026-07-28 shape: stateless, version in _meta (a legacy-initialized session gets -32601 for discover)
  private val discover =
    """{"jsonrpc":"2.0","id":2,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{},"io.modelcontextprotocol/clientInfo":{"name":"t","version":"1.0"}}}}"""

  private def replies(settings: McpServerSettings): (String, String) =
    val server = McpServer("Guided", "0.1.0", settings)
    val router = runUnsafe(server.buildRouter)
    val legacy = runUnsafe(Session.make("guided-legacy"))
    val modern = runUnsafe(Session.make("guided-modern"))
    val init = runUnsafe(MessageLoop.handleFrame(router, legacy, initialize)).getOrElse("")
    val disc = runUnsafe(MessageLoop.handleFrame(router, modern, discover)).getOrElse("")
    (init, disc)

  test("instructions from the settings are returned by initialize and server/discover") {
    val (init, disc) = replies(McpServerSettings(instructions = Some("Search first, then fetch by id.")))
    init should include(""""instructions":"Search first, then fetch by id."""")
    init should include("serverInfo")
    disc should include(""""instructions":"Search first, then fetch by id."""")
  }

  test("no instructions by default: the field is omitted") {
    val (init, disc) = replies(McpServerSettings())
    init should include("serverInfo")
    init should not include "instructions"
    disc should not include "instructions"
  }
