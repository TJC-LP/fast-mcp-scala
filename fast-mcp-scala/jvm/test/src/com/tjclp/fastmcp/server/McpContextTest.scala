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
    context.protocolSessionId shouldBe None
    context.headers shouldBe empty
    context.header("x-forwarded-email") shouldBe None
  }

  test("a context built for a session without an HTTP request has no headers") {
    val session = zio.Unsafe.unsafe { implicit u =>
      zio.Runtime.default.unsafe
        .run(com.tjclp.fastmcp.server.router.Session.make("stdio"))
        .getOrThrowFiberFailure()
    }
    val context = McpContext.withSession(session)

    context.sessionId shouldBe Some("stdio")
    context.protocolSessionId shouldBe None
    context.headers shouldBe empty
  }

  test("a context snapshots the client info / capabilities it is built with") {
    val info = Implementation("client", "1.0.0")
    val caps = ClientCapabilities(sampling = Some(SamplingCapability()))
    val context = new McpContext(None, Some(info), Some(caps))

    context.getClientInfo shouldBe Some(info)
    context.getClientCapabilities shouldBe Some(caps)
  }
}
