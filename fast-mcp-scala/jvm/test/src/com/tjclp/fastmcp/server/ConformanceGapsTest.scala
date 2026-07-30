package com.tjclp.fastmcp.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.ast.Json

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.core.ProgressToken
import com.tjclp.fastmcp.server.router.Session
import com.tjclp.fastmcp.server.transport.{HostGuard, MessageLoop}

/** Regression coverage for the SDK gaps closed to reach MCP conformance (active suite): DNS-rebinding
  * `HostGuard`, inbound `_meta.progressToken` access, and the honestly-gated `logging/setLevel` +
  * `resources/subscribe` handlers/capabilities. The end-to-end proof is `scripts/conformance.sh`; these
  * lock the units in the fast `./mill ...test` suite.
  */
class ConformanceGapsTest extends AnyFunSuite with Matchers:

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""

  // ---- DNS-rebinding (gap 4) ----

  test("HostGuard allows loopback and rejects foreign Host/Origin when an allowlist is set") {
    val allowed = Set("127.0.0.1", "localhost", "[::1]")
    HostGuard.isAllowed(Some("127.0.0.1:8077"), None, allowed) shouldBe true
    HostGuard.isAllowed(Some("localhost:8077"), Some("http://localhost:8077"), allowed) shouldBe true
    HostGuard.isAllowed(None, None, allowed) shouldBe true // absent Host is not the rebinding threat
    HostGuard.isAllowed(Some("evil.example.com"), None, allowed) shouldBe false
    HostGuard.isAllowed(Some("127.0.0.1:8077"), Some("http://evil.example.com"), allowed) shouldBe false
  }

  test("HostGuard is disabled (always allows) when the allowlist is empty") {
    HostGuard.isAllowed(Some("evil.example.com"), Some("http://evil.example.com"), Set.empty) shouldBe true
  }

  // ---- inbound progress token (gap 3) ----

  test("McpContext.progressToken decodes _meta.progressToken (string or whole number)") {
    val session = runUnsafe(Session.make("progress"))
    McpContext
      .withSession(session, requestMeta = Some(Map("progressToken" -> Json.Str("p1"))))
      .progressToken shouldBe Some(ProgressToken.StringToken("p1"))
    McpContext
      .withSession(session, requestMeta = Some(Map("progressToken" -> Json.Num(7))))
      .progressToken shouldBe Some(ProgressToken.NumberToken(7))
    McpContext.withSession(session).progressToken shouldBe None
  }

  // ---- logging + resources/subscribe wiring (gaps 1 & 2) ----

  test("logging + resources.subscribe are advertised and wired when enabled in settings") {
    val server =
      McpServer("GapServer", "0.1.0", McpServerSettings(loggingEnabled = true, resourcesSubscribe = true))
    runUnsafe(server.resource(McpStaticResource("test://x", name = Some("x"))("body")))
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("gap"))

    val init = runUnsafe(MessageLoop.handleFrame(router, session, initFrame)).getOrElse(fail("no init"))
    init should include("\"logging\"")
    init should include("\"subscribe\":true")

    val sub = runUnsafe(
      MessageLoop.handleFrame(
        router,
        session,
        """{"jsonrpc":"2.0","id":2,"method":"resources/subscribe","params":{"uri":"test://x"}}"""
      )
    ).getOrElse(fail("no subscribe reply"))
    sub should include("\"result\":{}")

    val unsub = runUnsafe(
      MessageLoop.handleFrame(
        router,
        session,
        """{"jsonrpc":"2.0","id":3,"method":"resources/unsubscribe","params":{"uri":"test://x"}}"""
      )
    ).getOrElse(fail("no unsubscribe reply"))
    unsub should include("\"result\":{}")

    val setLevel = runUnsafe(
      MessageLoop.handleFrame(
        router,
        session,
        """{"jsonrpc":"2.0","id":4,"method":"logging/setLevel","params":{"level":"info"}}"""
      )
    ).getOrElse(fail("no setLevel reply"))
    setLevel should include("\"result\":{}")
  }

  test("logging + subscribe are NOT advertised by default (#56 honest capabilities)") {
    val server = McpServer("PlainServer")
    runUnsafe(server.resource(McpStaticResource("test://x", name = Some("x"))("body")))
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("plain"))
    val init = runUnsafe(MessageLoop.handleFrame(router, session, initFrame)).getOrElse(fail("no init"))
    init should not include "logging"
    init should not include "subscribe"
  }

  // Found dogfooding with MCP Inspector: it probes resources/templates/list unconditionally and
  // renders -32601 as an error. templates/list is part of the `resources` capability, so any
  // resource-bearing server must answer it — empty when exposeTemplatesEndpoint is off.
  test("resources/templates/list answers (empty) when resources exist, even with templates hidden") {
    val server = McpServer("TemplatesProbeServer")
    runUnsafe(server.resource(McpStaticResource("test://x", name = Some("x"))("body")))
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("templates-probe"))
    runUnsafe(MessageLoop.handleFrame(router, session, initFrame))

    val reply = runUnsafe(
      MessageLoop.handleFrame(
        router,
        session,
        """{"jsonrpc":"2.0","id":5,"method":"resources/templates/list"}"""
      )
    ).getOrElse(fail("no reply"))
    reply should not include "-32601"
    reply should include(""""resourceTemplates":[]""")
  }
