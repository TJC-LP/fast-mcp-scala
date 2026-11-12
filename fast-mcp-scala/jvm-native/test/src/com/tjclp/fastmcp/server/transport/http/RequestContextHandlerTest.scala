package com.tjclp.fastmcp
package server.transport.http

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.core.Tasks
import com.tjclp.fastmcp.server.router.Session
import com.tjclp.fastmcp.server.transport.{MessageLoop, TransportBackend}

/** What a handler can learn about the HTTP request that carried its call, and when a silent
  * 2026-07-28 reply starts: allow-listed request headers (`McpServerSettings.contextHeaders`),
  * `McpContext.protocolSessionId`, and the SSE reply opening on the first heartbeat.
  *
  * Drives the shared handler through the neutral model, so it runs on the JVM and inside the Scala
  * Native test binary and covers every backend built on [[StreamableHttpHandler]] (zio-http and
  * the socket server). The Bun twin is `JsServerHttpTest`.
  */
class RequestContextHandlerTest extends AnyFunSuite with Matchers:
  import RequestContextHandlerTest.*

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private def makeHandler(settings: McpServerSettings): StreamableHttpHandler[Any] =
    val server = McpServer.typed[Any]("RequestContextT", "0.1.0", settings)
    val _ = server.scanAnnotations[Tools.type]
    runUnsafe(
      server.buildRouter.flatMap { router =>
        StreamableHttpHandler.make(router, server.settings, summon[TransportBackend].randomId())
      }
    )

  private val base = Map(
    "accept" -> "application/json, text/event-stream",
    "content-type" -> "application/json"
  )

  /** A request whose headers are given in wire case; lookups are case-insensitive, and the gate
    * lookup joins a repeated header the way every backend does.
    */
  private def post(headers: List[(String, String)], body: String): HttpRequest =
    val all = base.toList ++ headers
    def first(name: String): Option[String] =
      all.collectFirst { case (n, v) if n.equalsIgnoreCase(name) => v }
    def joined(name: String): Option[String] =
      all.collect { case (n, v) if n.equalsIgnoreCase(name) => v } match
        case Nil => None
        case values => Some(values.mkString(", "))
    HttpRequest("POST", "/mcp", first, joined, ZIO.succeed(body))

  private def legacyCall(id: Int, tool: String): String =
    s"""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$tool","arguments":{}}}"""

  private def modernCall(id: Int, tool: String): String =
    s"""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$tool","arguments":{},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}"""

  private def modernHeaders(tool: String): List[(String, String)] =
    List("mcp-protocol-version" -> "2026-07-28", "mcp-method" -> "tools/call", "mcp-name" -> tool)

  private def frames(reply: HttpReply): List[SseFrame] =
    reply match
      case HttpReply.Sse(_, stream, _) => runUnsafe(stream.runCollect).toList
      case HttpReply.SseComplete(_, frame) => List(frame)
      case other => fail(s"expected an SSE reply, got $other")

  /** The JSON-RPC payloads of an SSE reply, concatenated (heartbeats carry none). */
  private def payload(reply: HttpReply): String = frames(reply).map(_.data).mkString("\n")

  /** Open a legacy session and return its `Mcp-Session-Id`. */
  private def initialize(handler: StreamableHttpHandler[Any]): String =
    val init = runUnsafe(handler.post(post(Nil, InitFrame)))
    init.status shouldBe 200
    val sid = init.headers
      .collectFirst { case (name, value) if name.equalsIgnoreCase("mcp-session-id") => value }
      .getOrElse(fail("initialize did not mint a session"))
    val _ = frames(init)
    runUnsafe(
      handler.post(
        post(
          List("mcp-session-id" -> sid),
          """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
        )
      )
    ).status shouldBe 202
    sid

  private val identity = McpServerSettings(
    contextHeaders = Set("X-Forwarded-Email", "x-forwarded-access-token")
  )

  // --- allow-listed request headers -------------------------------------------------------------

  test("legacy session: each call sees the headers of ITS request, not the session's first") {
    val handler = makeHandler(identity)
    val sid = initialize(handler)
    def call(id: Int, token: String): String =
      payload(
        runUnsafe(
          handler.post(
            post(
              List(
                "mcp-session-id" -> sid,
                "X-Forwarded-Email" -> "ada@example.com",
                "X-Forwarded-Access-Token" -> token
              ),
              legacyCall(id, "whoami")
            )
          )
        )
      )
    val first = call(2, "token-one")
    first should include("email=ada@example.com")
    first should include("token=token-one")
    // The proxy rotated the forwarded token during the session's life.
    val second = call(3, "token-two")
    second should include("token=token-two")
    second should not include "token-one"
  }

  test("2026-07-28 request: the handler sees the allow-listed headers of that request") {
    val handler = makeHandler(identity)
    val out = payload(
      runUnsafe(
        handler.post(
          post(
            modernHeaders("whoami") ++ List(
              "x-forwarded-email" -> "grace@example.com",
              "x-forwarded-access-token" -> "modern-token"
            ),
            modernCall(4, "whoami")
          )
        )
      )
    )
    out should include("email=grace@example.com")
    out should include("token=modern-token")
  }

  test("legacy stateless mode: the handler sees the allow-listed headers of that request") {
    val handler = makeHandler(identity.copy(stateless = true))
    val reply = runUnsafe(
      handler.post(post(List("x-forwarded-email" -> "lin@example.com"), legacyCall(5, "whoami")))
    )
    reply match
      case HttpReply.Json(200, body, _) => body should include("email=lin@example.com")
      case other => fail(s"expected a JSON reply, got $other")
  }

  test("a header that is not listed is absent, whatever the request carries") {
    val handler = makeHandler(identity)
    val out = payload(
      runUnsafe(
        handler.post(
          post(
            modernHeaders("whoami") ++ List(
              "x-forwarded-email" -> "ada@example.com",
              "authorization" -> "Bearer not-listed",
              "cookie" -> "session=not-listed"
            ),
            modernCall(6, "whoami")
          )
        )
      )
    )
    out should include("names=x-forwarded-email;")
    out should include("authorization=<none>")
    out should not include "not-listed"
  }

  test("names match case-insensitively on the allow-list, the wire and the accessor") {
    // Allow-list in mixed case, header sent in upper case, handler asks in yet another case.
    val handler = makeHandler(McpServerSettings(contextHeaders = Set(" X-Forwarded-EMAIL ")))
    val out = payload(
      runUnsafe(
        handler.post(
          post(
            modernHeaders("whoami") ++ List("X-FORWARDED-EMAIL" -> "ada@example.com"),
            modernCall(7, "whoami")
          )
        )
      )
    )
    out should include("email=ada@example.com")
    out should include("names=x-forwarded-email;")
  }

  test("a header sent twice is seen joined, never as the first sender's value alone") {
    // A client-supplied header that a proxy appended to instead of replacing: the handler must
    // not see the client's value as if it were the proxy's.
    val handler = makeHandler(identity)
    val out = payload(
      runUnsafe(
        handler.post(
          post(
            modernHeaders("whoami") ++ List(
              "x-forwarded-access-token" -> "from-client",
              "x-forwarded-access-token" -> "from-proxy"
            ),
            modernCall(8, "whoami")
          )
        )
      )
    )
    out should include("token=from-client, from-proxy")
  }

  test("with no allow-list (the default) a handler sees no headers at all") {
    val handler = makeHandler(McpServerSettings())
    val out = payload(
      runUnsafe(
        handler.post(
          post(
            modernHeaders("whoami") ++ List("x-forwarded-email" -> "ada@example.com"),
            modernCall(9, "whoami")
          )
        )
      )
    )
    out should include("email=<none>")
    out should include("names=;")
  }

  test("headers do not leak from one request into the next on the same handler") {
    val handler = makeHandler(identity)
    val sid = initialize(handler)
    val withHeader = payload(
      runUnsafe(
        handler.post(
          post(
            List("mcp-session-id" -> sid, "x-forwarded-email" -> "ada@example.com"),
            legacyCall(10, "whoami")
          )
        )
      )
    )
    withHeader should include("email=ada@example.com")
    val without = payload(
      runUnsafe(
        handler.post(post(List("mcp-session-id" -> sid), legacyCall(11, "whoami")))
      )
    )
    without should include("email=<none>")
  }

  test("a task keeps the headers of the request that created it, not of the request polling it") {
    val handler = makeHandler(identity.copy(tasks = TaskSettings(enabled = true)))
    val taskMeta =
      s""""_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{"extensions":{"${Tasks.ExtensionId}":{}}}}"""
    val created = payload(
      runUnsafe(
        handler.post(
          post(
            modernHeaders("slow-whoami") ++ List("x-forwarded-email" -> "creator@example.com"),
            s"""{"jsonrpc":"2.0","id":15,"method":"tools/call","params":{"name":"slow-whoami","arguments":{},$taskMeta}}"""
          )
        )
      )
    )
    created should include(""""resultType":"task"""")
    val taskId = """"taskId":"([^"]+)"""".r
      .findFirstMatchIn(created)
      .map(_.group(1))
      .getOrElse(fail(s"no taskId in: $created"))
    // Polled by a request that carries someone else's header: the task must not pick it up.
    val poll = handler
      .post(
        post(
          List(
            "mcp-protocol-version" -> "2026-07-28",
            "mcp-method" -> "tasks/get",
            "mcp-name" -> taskId,
            "x-forwarded-email" -> "poller@example.com"
          ),
          s"""{"jsonrpc":"2.0","id":16,"method":"tasks/get","params":{"taskId":"$taskId",$taskMeta}}"""
        )
      )
      .flatMap {
        case HttpReply.Sse(_, stream, _) => stream.runCollect.map(_.map(_.data).mkString("\n"))
        case HttpReply.SseComplete(_, frame) => ZIO.succeed(frame.data)
        case HttpReply.Json(_, body, _) => ZIO.succeed(body)
        case other => ZIO.fail(new RuntimeException(s"unexpected reply $other"))
      }
    val done = runUnsafe(
      (ZIO.sleep(50.millis) *> poll)
        .repeatUntil(_.contains(""""status":"completed""""))
        .timeoutFail(new RuntimeException(s"task $taskId never completed"))(15.seconds)
    )
    done should include("email=creator@example.com")
    done should not include "poller@example.com"
  }

  // --- protocolSessionId ------------------------------------------------------------------------

  test("protocolSessionId is the Mcp-Session-Id of a durable legacy session") {
    val handler = makeHandler(McpServerSettings())
    val sid = initialize(handler)
    val out = payload(
      runUnsafe(handler.post(post(List("mcp-session-id" -> sid), legacyCall(12, "whoami"))))
    )
    out should include(s"protocolSession=$sid;")
    out should include(s"session=$sid;")
  }

  test("protocolSessionId is empty for a 2026-07-28 request, though sessionId is a stand-in") {
    val handler = makeHandler(McpServerSettings())
    val out = payload(
      runUnsafe(handler.post(post(modernHeaders("whoami"), modernCall(13, "whoami"))))
    )
    out should include("protocolSession=<none>;")
    out should include("session=request-")
  }

  test("protocolSessionId is empty in legacy stateless mode, though sessionId is a stand-in") {
    val handler = makeHandler(McpServerSettings(stateless = true))
    runUnsafe(handler.post(post(Nil, legacyCall(14, "whoami")))) match
      case HttpReply.Json(200, body, _) =>
        body should include("protocolSession=<none>;")
        body should include("session=stateless;")
      case other => fail(s"expected a JSON reply, got $other")
  }

  // --- the 2026-07-28 reply opens on the first heartbeat -----------------------------------------

  private val heartbeat = McpServerSettings(keepAliveInterval = Some(java.time.Duration.ofMillis(100)))

  /** Run `handler.post` and report how long the REPLY took to come back (not its stream). */
  private def timedPost(handler: StreamableHttpHandler[Any], req: HttpRequest): (HttpReply, Long) =
    val started = java.lang.System.nanoTime()
    val reply = runUnsafe(handler.post(req))
    (reply, (java.lang.System.nanoTime() - started) / 1000000L)

  test("a silent 2026-07-28 call opens its SSE reply at the first heartbeat, then delivers") {
    val handler = makeHandler(heartbeat)
    val (reply, elapsedMs) =
      timedPost(handler, post(modernHeaders("silent"), modernCall(20, "silent")))
    // The tool is silent for 1.5 s; the reply must not wait for it.
    elapsedMs should be < 1000L
    reply.status shouldBe 200
    reply match
      case HttpReply.Sse(_, _, keepAlive) => keepAlive shouldBe Some(100.millis)
      case other => fail(s"expected an SSE reply, got $other")
    val all = frames(reply)
    all.headOption shouldBe Some(SseFrame.Ping)
    all.lastOption.map(_.data).getOrElse("") should include("silent-done")
    all.count(_.data.contains("silent-done")) shouldBe 1
  }

  test("without keepAliveInterval a silent 2026-07-28 call still waits for its first frame") {
    val handler = makeHandler(McpServerSettings())
    val (reply, elapsedMs) =
      timedPost(handler, post(modernHeaders("silent"), modernCall(21, "silent")))
    elapsedMs should be >= 1000L
    val all = frames(reply)
    all.exists(_ == SseFrame.Ping) shouldBe false
    all.lastOption.map(_.data).getOrElse("") should include("silent-done")
  }

  test("a 2026-07-28 call that answers before the first heartbeat is not preceded by one") {
    val handler = makeHandler(heartbeat)
    // Several rounds: a late timer must never leak its marker into a reply as a message.
    (30 until 60).foreach { id =>
      val all = frames(runUnsafe(handler.post(post(modernHeaders("whoami"), modernCall(id, "whoami")))))
      all.size shouldBe 1
      all.head.event shouldBe Some("message")
      all.head.data should include("session=request-")
      all.head.data should not include "keepalive"
    }
  }

  test("an early status-mapped error keeps its HTTP status with keepAliveInterval set") {
    val handler = makeHandler(heartbeat)
    runUnsafe(handler.post(post(modernHeaders("needs-roots"), modernCall(61, "needs-roots")))) match
      case HttpReply.Json(400, body, _) => body should include("-32021")
      case other => fail(s"expected a 400 JSON reply, got $other")
  }

  test("a status-mapped error after the reply opened is delivered in-band") {
    val handler = makeHandler(heartbeat)
    val reply =
      runUnsafe(handler.post(post(modernHeaders("late-roots"), modernCall(62, "late-roots"))))
    reply.status shouldBe 200
    val all = frames(reply)
    all.headOption shouldBe Some(SseFrame.Ping)
    all.lastOption.map(_.data).getOrElse("") should include("-32021")
  }

  // --- a reply that is complete at its first frame needs no stream -------------------------------

  test("a 2026-07-28 call whose first frame is its reply is answered as one complete SSE body") {
    val handler = makeHandler(McpServerSettings())
    runUnsafe(handler.post(post(modernHeaders("whoami"), modernCall(70, "whoami")))) match
      case HttpReply.SseComplete(headers, frame) =>
        headers should contain("X-Accel-Buffering" -> "no")
        frame.event shouldBe Some("message")
        frame.data should include(""""id":70""")
        frame.data should include("session=request-")
      case other => fail(s"expected a complete SSE reply, got $other")
  }

  test("a 2026-07-28 call that emits a notification first still streams, in order") {
    val handler = makeHandler(McpServerSettings())
    val body =
      """{"jsonrpc":"2.0","id":71,"method":"tools/call","params":{"name":"progress-then-done","arguments":{},"_meta":{"progressToken":"p1","io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}"""
    val reply = runUnsafe(handler.post(post(modernHeaders("progress-then-done"), body)))
    reply match
      case _: HttpReply.Sse => ()
      case other => fail(s"expected a streamed SSE reply, got $other")
    val all = frames(reply)
    all.size shouldBe 2
    all.head.data should include("notifications/progress")
    all.last.data should include("progress-done")
  }

  test("a legacy session call is still streamed (its response head is not held back)") {
    val handler = makeHandler(McpServerSettings())
    val sid = initialize(handler)
    runUnsafe(handler.post(post(List("mcp-session-id" -> sid), legacyCall(72, "whoami")))) match
      case _: HttpReply.Sse => ()
      case other => fail(s"expected a streamed SSE reply, got $other")
  }

  // --- stdio ------------------------------------------------------------------------------------

  test("stdio: no headers and no protocol session, whatever the settings list") {
    val server = McpServer.typed[Any]("RequestContextStdio", "0.1.0", identity)
    val _ = server.scanAnnotations[Tools.type]
    // The stdio loop's own steps: one "stdio" session, each line through `MessageLoop.handleFrame`.
    val out = runUnsafe(
      for
        router <- server.buildRouter
        session <- Session.make("stdio")
        _ <- MessageLoop.handleFrame(router, session, InitFrame)
        _ <- MessageLoop.handleFrame(
          router,
          session,
          """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
        )
        reply <- MessageLoop.handleFrame(router, session, legacyCall(2, "whoami"))
      yield reply.getOrElse(fail("no reply"))
    )
    out should include("email=<none>")
    out should include("names=;")
    out should include("protocolSession=<none>;")
    out should include("session=stdio;")
  }

object RequestContextHandlerTest:

  private val InitFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""

  object Tools:

    @Tool(name = Some("whoami"), description = Some("Reports what the context says of the caller"))
    def whoami(ctx: McpContext): String =
      val email = ctx.header("X-Forwarded-eMail").getOrElse("<none>")
      val token = ctx.header("x-forwarded-access-token").getOrElse("<none>")
      val authorization = ctx.header("Authorization").getOrElse("<none>")
      val names = ctx.headers.keys.toList.sorted.mkString(",")
      s"email=$email;token=$token;authorization=$authorization;names=$names;" +
        s"protocolSession=${ctx.protocolSessionId.getOrElse("<none>")};" +
        s"session=${ctx.sessionId.getOrElse("<none>")};"

    @Tool(
      name = Some("slow-whoami"),
      description = Some("Reads the caller's email after a pause"),
      taskSupport = Some("optional")
    )
    def slowWhoami(ctx: McpContext): ZIO[Any, Throwable, String] =
      // Read AFTER the pause, i.e. when the creating request is long gone.
      ZIO.sleep(300.millis).as(s"email=${ctx.header("x-forwarded-email").getOrElse("<none>")};")

    @Tool(name = Some("progress-then-done"), description = Some("Reports progress, then answers"))
    def progressThenDone(ctx: McpContext): ZIO[Any, Throwable, String] =
      ZIO.foreachDiscard(ctx.progressToken)(t => ctx.sendProgress(t, 0.5)).as("progress-done")

    @Tool(name = Some("silent"), description = Some("Says nothing for a while, then answers"))
    def silent(): ZIO[Any, Throwable, String] = ZIO.sleep(1500.millis).as("silent-done")

    @Tool(name = Some("needs-roots"), description = Some("Asks for roots the client never declared"))
    def needsRoots(ctx: McpContext): ZIO[Any, Throwable, String] = ctx.listRoots().as("roots")

    @Tool(name = Some("late-roots"), description = Some("Is silent, then asks for undeclared roots"))
    def lateRoots(ctx: McpContext): ZIO[Any, Throwable, String] =
      ZIO.sleep(400.millis) *> ctx.listRoots().as("roots")
