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

  // ---- the legacy session store: what a session retains, when it is stored, who can fill it ----

  private def storeHandler(
      settings: McpServerSettings = McpServerSettings()
  ): StreamableHttpHandler[Any] =
    val server = McpServer.typed[Any]("StoreT", "0.1.0", settings)
    runUnsafe(
      server.buildRouter.flatMap { router =>
        StreamableHttpHandler.make(router, server.settings, summon[TransportBackend].randomId())
      }
    )

  private val PostHeaders = Map(
    "accept" -> "application/json, text/event-stream",
    "content-type" -> "application/json"
  )

  private val pingFrame = """{"jsonrpc":"2.0","id":7,"method":"ping"}"""

  private def initFrameWith(capabilities: String, clientInfo: String): String =
    s"""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":$capabilities,"clientInfo":$clientInfo}}"""

  private val plainClientInfo = """{"name":"t","version":"1.0"}"""

  /** One POST, optionally on a session and from a given peer address. */
  private def post(
      handler: StreamableHttpHandler[Any],
      body: String,
      sid: Option[String] = None,
      peer: Option[String] = None
  ): HttpReply =
    val headers = sid.fold(PostHeaders)(id => PostHeaders + ("mcp-session-id" -> id))
    val lookup: String => Option[String] = name => headers.get(name.toLowerCase)
    runUnsafe(handler.post(HttpRequest("POST", "/mcp", lookup, lookup, ZIO.succeed(body), peer)))

  private def sessionIdOf(reply: HttpReply): Option[String] =
    reply.headers.collectFirst {
      case (name, value) if name.equalsIgnoreCase("mcp-session-id") => value
    }

  /** The whole reply body as text: the JSON body, or the `data:` payloads of an SSE reply. */
  private def textOf(reply: HttpReply): String =
    reply match
      case HttpReply.Json(_, body, _) => body
      case HttpReply.Empty(_, _) => ""
      case sse => runUnsafe(framesOf(sse).runCollect).map(_.data).mkString("\n")

  private def initialize(handler: StreamableHttpHandler[Any], peer: Option[String] = None): String =
    val reply = post(handler, initFrame, peer = peer)
    withClue(s"initialize from $peer: ${reply.status} ") { reply.status shouldBe 200 }
    val _ = textOf(reply)
    sessionIdOf(reply).getOrElse(fail(s"initialize from $peer minted no session"))

  /** True while the session is stored (an unknown session id answers 404). */
  private def alive(handler: StreamableHttpHandler[Any], sid: String): Boolean =
    post(handler, pingFrame, Some(sid)).status != 404

  /** Open the session's GET stream and never drain it: the session holds a live GET from here on. */
  private def openGet(handler: StreamableHttpHandler[Any], sid: String): Unit =
    val reply = runUnsafe(
      handler.get(request("GET", Map("accept" -> "text/event-stream", "mcp-session-id" -> sid)))
    )
    reply.status shouldBe 200

  test("an initialize within the retention budget is accepted with icons and open capability members") {
    val handler = storeHandler()
    val capabilities =
      """{"roots":{"listChanged":true},"sampling":{},"elicitation":{"form":{},"url":{}},"experimental":{"x":{"a":[1,2,3]}},"extensions":{"io.modelcontextprotocol/ui":{"mimeTypes":["text/html;profile=mcp-app"]}}}"""
    val clientInfo =
      """{"name":"client","version":"1.2.3","title":"A client","websiteUrl":"https://example.com","icons":[{"src":"https://example.com/icon.png","mimeType":"image/png","sizes":["48x48"]}]}"""
    val reply = post(handler, initFrameWith(capabilities, clientInfo))
    reply.status shouldBe 200
    textOf(reply) should include("\"serverInfo\"")
    sessionIdOf(reply) should not be empty
  }

  test("an initialize whose capabilities hold too many values is rejected with -32602 and mints no session") {
    val handler = storeHandler()
    val manyValues = List.fill(300)("0").mkString("[", ",", "]")
    val reply = post(handler, initFrameWith(s"""{"experimental":{"x":$manyValues}}""", plainClientInfo))
    val text = textOf(reply)
    text should include("-32602")
    text should not include "serverInfo"
    sessionIdOf(reply) shouldBe None
  }

  test("an initialize whose clientInfo holds too much text is rejected with -32602 and mints no session") {
    val handler = storeHandler()
    val longName = "n" * (32 * 1024)
    val reply = post(handler, initFrameWith("{}", s"""{"name":"$longName","version":"1.0"}"""))
    val text = textOf(reply)
    text should include("-32602")
    text should not include "serverInfo"
    sessionIdOf(reply) shouldBe None
  }

  test("a body-cap-sized initialize holding one very long capability array is rejected and mints no session") {
    val handler = storeHandler()
    val cap = McpServerSettings().maxRequestBodyBytes
    val head =
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","clientInfo":{"name":"t","version":"1.0"},"capabilities":{"experimental":{"x":[0"""
    val tail = "]}}}}"
    val body = head + ",0" * ((cap - head.length - tail.length) / 2) + tail
    body.length should be > cap - 4
    body.length should be <= cap
    val reply = post(handler, body)
    val text = textOf(reply)
    // -32602 from the retention budget; a frame-level bound on array length, where configured
    // below this size, answers -32700 first. Either way the request is refused outright.
    text should (include("-32602") or include("-32700"))
    text should not include "serverInfo"
    sessionIdOf(reply) shouldBe None
  }

  test("a failed initialize leaves the store unchanged: no session id, and no stored session is evicted for it") {
    // One slot, taken by an idle session without a GET stream — the first eviction candidate.
    val handler = storeHandler(McpServerSettings(maxSessions = Some(1)))
    val stored = initialize(handler)

    val undecodable =
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{}}}"""
    val overBudget =
      initFrameWith(s"""{"experimental":{"x":${List.fill(300)("0").mkString("[", ",", "]")}}}""", plainClientInfo)
    for body <- List(undecodable, overBudget) do
      val reply = post(handler, body)
      textOf(reply) should include("-32602")
      sessionIdOf(reply) shouldBe None
      withClue("the stored session must survive a failed initialize: ") {
        alive(handler, stored) shouldBe true
      }

    // A successful initialize is what takes the slot.
    val next = initialize(handler)
    alive(handler, next) shouldBe true
    alive(handler, stored) shouldBe false
  }

  test("one peer holding every slot with live, freshly touched GET streams cannot lock another peer out") {
    val cap = 4
    // No idle timeout: nothing ever becomes evictable through idleness.
    val handler =
      storeHandler(McpServerSettings(maxSessions = Some(cap), sessionIdleTimeout = None))
    val hog = Some("203.0.113.7")
    val other = Some("198.51.100.20")

    val hogged = List.fill(cap)(initialize(handler, hog))
    hogged.foreach(openGet(handler, _))
    def touchAll(): Unit = hogged.foreach(sid => post(handler, pingFrame, Some(sid), hog))
    touchAll()
    hogged.count(alive(handler, _)) shouldBe cap

    val admitted = post(handler, initFrame, peer = other)
    withClue(s"initialize from another peer at the cap: ${textOf(admitted)} ") {
      admitted.status shouldBe 200
    }
    val newcomer = sessionIdOf(admitted).getOrElse(fail("the other peer was given no session"))
    alive(handler, newcomer) shouldBe true
    // The store never exceeds the cap: exactly one of the hog's sessions made room.
    hogged.count(alive(handler, _)) shouldBe cap - 1

    // The hog keeps touching and keeps initializing: it only ever displaces its own sessions.
    val more = (1 to 3).toList.map { _ =>
      touchAll()
      initialize(handler, hog)
    }
    alive(handler, newcomer) shouldBe true
    (hogged ++ more).count(alive(handler, _)) shouldBe cap - 1

    // And a third peer still gets in, again at the hog's expense.
    val third = initialize(handler, Some("192.0.2.33"))
    alive(handler, third) shouldBe true
    alive(handler, newcomer) shouldBe true
    (hogged ++ more).count(alive(handler, _)) shouldBe cap - 2
  }

  test("a peer that already holds the most sessions replaces its own at the cap, not an idle session of another peer") {
    val handler = storeHandler(McpServerSettings(maxSessions = Some(3)))
    val small = initialize(handler, Some("198.51.100.20")) // the longest-idle session, no GET
    Thread.sleep(5)
    val big = List.fill(2)(initialize(handler, Some("203.0.113.7")))
    Thread.sleep(5)

    val next = initialize(handler, Some("203.0.113.7"))
    alive(handler, small) shouldBe true
    alive(handler, next) shouldBe true
    big.count(alive(handler, _)) shouldBe 1
  }

  test("without a peer address the cap keeps its rules: live GET holders are exempt and the newcomer gets 503") {
    val handler =
      storeHandler(McpServerSettings(maxSessions = Some(2), sessionIdleTimeout = None))
    val held = List.fill(2)(initialize(handler))
    held.foreach(openGet(handler, _))

    val refused = post(handler, initFrame)
    refused.status shouldBe 503
    sessionIdOf(refused) shouldBe None
    held.count(alive(handler, _)) shouldBe 2
  }

  test("concurrent initializes from many peers at a hogged cap are all admitted and never grow the store past it") {
    val cap = 4
    val handler =
      storeHandler(McpServerSettings(maxSessions = Some(cap), sessionIdleTimeout = None))
    val hog = Some("203.0.113.7")
    val hogged = List.fill(cap)(initialize(handler, hog))
    hogged.foreach(openGet(handler, _))

    // Three other peers arrive at once, twice each. Snapshot, decision and insert are one atomic
    // step, so each is admitted against the store as the one before it left it.
    val peers = List("198.51.100.1", "198.51.100.2", "198.51.100.3")
    val replies = runUnsafe(
      ZIO.foreachPar(peers ++ peers) { peer =>
        val lookup: String => Option[String] = name => PostHeaders.get(name.toLowerCase)
        handler.post(HttpRequest("POST", "/mcp", lookup, lookup, ZIO.succeed(initFrame), Some(peer)))
      }
    )
    replies.map(_.status).distinct shouldBe List(200)
    val minted = replies.flatMap(sessionIdOf)
    minted.distinct.size shouldBe 6
    (hogged ++ minted).count(alive(handler, _)) shouldBe cap
  }

