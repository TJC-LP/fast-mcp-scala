package com.tjclp.fastmcp
package server.transport.http

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.server.transport.TransportBackend

/** Contracts of the shared streamable-HTTP handler that the socket backend leans on, run on both
  * the JVM and Scala Native (`jvm-native/test`). Drives the handler directly through the neutral
  * model — no port, no zio-http.
  */
class StreamableHttpHandlerTest extends AnyFunSuite with Matchers:

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""

  private def makeHandler(): StreamableHttpHandler[Any] =
    val server = McpServer.typed[Any]("HandlerT", "0.1.0", McpServerSettings())
    runUnsafe(
      server.buildRouter.flatMap { router =>
        StreamableHttpHandler.make(router, server.settings, summon[TransportBackend].randomId())
      }
    )

  private def request(method: String, headers: Map[String, String], body: String = ""): HttpRequest =
    val lookup: String => Option[String] = name => headers.get(name.toLowerCase)
    HttpRequest(method, "/mcp", lookup, lookup, ZIO.succeed(body))

  private def framesOf(reply: HttpReply) =
    reply match
      case HttpReply.Sse(_, frames, _) => frames
      case HttpReply.SseComplete(_, frame) => zio.stream.ZStream.succeed(frame)
      case other => fail(s"expected an SSE reply, got $other")

  test("a reply stream halted before its first pull still runs the handler's finalizers") {
    val handler = makeHandler()
    val init = runUnsafe(
      handler.post(
        request(
          "POST",
          Map(
            "accept" -> "application/json, text/event-stream",
            "content-type" -> "application/json"
          ),
          initFrame
        )
      )
    )
    init.status shouldBe 200
    val sid = init.headers
      .collectFirst { case (name, value) if name.equalsIgnoreCase("mcp-session-id") => value }
      .getOrElse(fail("initialize did not mint a session"))
    runUnsafe(framesOf(init).runDrain)

    val getHeaders = Map("accept" -> "text/event-stream", "mcp-session-id" -> sid)
    val first = runUnsafe(handler.get(request("GET", getHeaders)))
    first.status shouldBe 200

    // What the socket layer does when the head write fails: complete `dead` up front and run the
    // stream through interruptWhen so it halts immediately. Its `ensuring(session.releaseGet)`
    // must still fire — otherwise this session answers 409 to every later GET, forever.
    runUnsafe(
      Promise.make[Nothing, Unit].flatMap { dead =>
        dead.succeed(()) *> framesOf(first).interruptWhen(dead).runDrain
      }
    )

    val second = runUnsafe(handler.get(request("GET", getHeaders)))
    second.status shouldBe 200
  }

  test("a POST whose header names show a repeated routing header is a header mismatch (400, -32020)") {
    val handler = makeHandler()
    val discover =
      """{"jsonrpc":"2.0","id":7,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}"""
    val headers = Map(
      "accept" -> "application/json, text/event-stream",
      "content-type" -> "application/json",
      "mcp-protocol-version" -> "2026-07-28",
      "mcp-method" -> "server/discover"
    )
    def post(body: String, lookup: Map[String, String], names: List[String]): HttpReply =
      runUnsafe(
        handler.post(
          HttpRequest(
            "POST",
            "/mcp",
            name => lookup.get(name.toLowerCase),
            name => lookup.get(name.toLowerCase),
            ZIO.succeed(body),
            None,
            () => names.iterator
          )
        )
      )
    def mismatch(reply: HttpReply): Unit =
      reply match
        case HttpReply.Json(status, body, _) =>
          status shouldBe 400
          body should include(""""code":-32020""")
          body should include(""""id":7""")
        case other => fail(s"expected a JSON error reply, got $other")

    // The lookups see one value (whichever the backend picks); the names show the repeat.
    post(discover, headers, headers.keys.toList).status shouldBe 200
    mismatch(post(discover, headers, "Mcp-Method" :: headers.keys.toList))
    mismatch(post(discover, headers, headers.keys.toList :+ "MCP-Protocol-Version"))
    // A request built without names (no wire behind it) is judged on its lookups alone.
    runUnsafe(handler.post(request("POST", headers, discover))).status shouldBe 200

    // A repeated version header is refused on a legacy-looking request too: its first value must
    // not choose the path while another one says otherwise.
    val legacy = headers - "mcp-method" + ("mcp-protocol-version" -> "2025-11-25")
    post(initFrame, legacy, legacy.keys.toList).status shouldBe 200
    post(initFrame, legacy, "mcp-protocol-version" :: legacy.keys.toList) match
      case HttpReply.Json(status, body, _) =>
        status shouldBe 400
        body should include(""""code":-32020""")
      case other => fail(s"expected a JSON error reply, got $other")
    // The other routing headers mean nothing to a legacy request and are left alone there.
    post(initFrame, legacy, "mcp-name" :: "mcp-name" :: legacy.keys.toList).status shouldBe 200
  }

  test("a request that never forces its body is answered without the body being read") {
    val handler = makeHandler()
    val touched = new java.util.concurrent.atomic.AtomicBoolean(false)
    val body: IO[Throwable, String] = ZIO.succeed { touched.set(true); initFrame }
    // Accept without text/event-stream: rejected by the guard before the body is needed.
    val reply = runUnsafe(
      handler.post(
        HttpRequest(
          "POST",
          "/mcp",
          Map("accept" -> "text/plain", "content-type" -> "application/json").get,
          Map("accept" -> "text/plain", "content-type" -> "application/json").get,
          body
        )
      )
    )
    reply.status shouldBe 406
    touched.get shouldBe false
  }
