package com.tjclp.fastmcp
package server.transport

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.RegistrationMacro.*
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.router.Session
import com.tjclp.fastmcp.server.transport.JvmTransportBackend.given

/** DRAFT (C3 step 10, TJC-2328) — JVM `sessionIdleTimeout` eviction, the JVM twin of
  * JsServerHttpTest.scala:851 (unit sweeper) and :869 (route-level 404 after idle). The coverage
  * map (S1.8) found no JVM test referencing `sessionIdleTimeout` / `evictIdleSessions`; CLAUDE.md's
  * "eviction" tests are the `maxSessions` cap (JvmHttpTransportTest:598-668).
  *
  * The sweeper is forked by `serveHttp`, NOT by `httpRoutes` (JvmHttpBackend.scala:45-52), so the
  * route-level cases here boot a real server on an ephemeral loopback port (`ServerSocket(0)`) and
  * drive it with `java.net.http.HttpClient`; the unit case drives `evictIdleSessions` against its
  * own store, polling rather than sleeping-then-asserting.
  *
  * The last case is RED on main 8b2fede by construction (D6 step 10, P2, confirmed x2 V1/V2): an
  * in-flight legacy POST does not count as activity (`JvmHttpBackend.scala:74-75` reads only
  * `lastSeen` / `hasActiveGet`), so the sweeper terminates its session mid-request,
  * `Session.terminate` interruptForks the request fiber (Session.scala:136-138), `McpRouter.scala:293`
  * emits no response for an interrupt-only exit, and the SSE stream closes with ZERO frames. The
  * client waits for its own timeout. Either fix shape makes it green: (a) answer in-flight requests
  * of a terminated session with `-32001 Session terminated`, or (b) skip sessions with in-flight
  * requests in the sweeper predicate (then the tool's own result arrives).
  */
class JvmIdleEvictionTest extends AnyFunSuite with Matchers with BeforeAndAfterAll:

  object IdleServer:
    @Tool(name = Some("add"), description = Some("Add two numbers"))
    def add(@Param("a") a: Int, @Param("b") b: Int): Int = a + b

    @Tool(name = Some("nap"), description = Some("Sleeps 3 s, then answers"))
    def nap(): ZIO[Any, Throwable, String] = ZIO.sleep(3.seconds).as("rested")

  private val SessionIdHeader = "mcp-session-id"

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""
  private val napFrame =
    """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"nap","arguments":{}}}"""
  private val listFrame = """{"jsonrpc":"2.0","id":3,"method":"tools/list"}"""

  /** 500 ms idle timeout -> the sweeper ticks every `max(timeout/4, 1 s)` = 1 s. */
  private val RouteIdleTimeout = java.time.Duration.ofMillis(500)

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  // ---- unit sweeper (no port) ----

  test("evictIdleSessions: an idle session without a live GET is terminated and dropped; live-GET and recently-touched sessions survive the tick") {
    // 2 s timeout -> 1 s ticks; the "fresh" session is touched right before the first tick so it
    // has a full second of margin before the second tick could evict it.
    val settings = McpServerSettings(sessionIdleTimeout = Some(java.time.Duration.ofMillis(2000)))
    val (remaining, idleTerminated, heldTerminated, freshTerminated) = runUnsafe(
      for
        idle <- Session.make("idle")
        held <- Session.make("held")
        _ <- held.tryAcquireGet // a live GET stream exempts the session (push-only consumers)
        fresh <- Session.make("fresh")
        store <- Ref.make(Map("idle" -> idle, "held" -> held, "fresh" -> fresh))
        _ <- ZIO.sleep(2300.millis) // idle + held are now past the timeout
        _ <- fresh.touch
        sweeper <- JvmHttpBackend.evictIdleSessions(store, settings).fork
        _ <- (ZIO.sleep(25.millis) *> store.get)
          .repeatUntil(!_.contains("idle"))
          .timeoutFail(new RuntimeException("the idle session was never evicted"))(10.seconds)
        snapshot <- store.get
        a <- idle.isTerminated
        b <- held.isTerminated
        c <- fresh.isTerminated
        _ <- sweeper.interrupt
      yield (snapshot.keySet, a, b, c)
    )
    remaining shouldBe Set("held", "fresh")
    idleTerminated shouldBe true
    heldTerminated shouldBe false
    freshTerminated shouldBe false
  }

  // ---- route level: real server on an ephemeral loopback port ----

  private final case class Running(port: Int, fiber: Fiber.Runtime[Throwable, Unit])
  private val running = new java.util.concurrent.atomic.AtomicReference[Option[Running]](None)

  private def freeLoopbackPort(): Int =
    val socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress)
    try socket.getLocalPort
    finally socket.close()

  private def awaitListening(port: Int, attemptsLeft: Int): Unit =
    val up =
      try
        val s = new java.net.Socket()
        try
          s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 200)
          true
        finally s.close()
      catch case _: java.io.IOException => false
    if !up then
      if attemptsLeft <= 0 then fail(s"server on 127.0.0.1:$port never started listening")
      Thread.sleep(100)
      awaitListening(port, attemptsLeft - 1)

  override def beforeAll(): Unit =
    val port = freeLoopbackPort()
    val server = McpServer.typed[Any](
      "IdleT",
      "0.1.0",
      McpServerSettings(host = "127.0.0.1", port = port, sessionIdleTimeout = Some(RouteIdleTimeout))
    )
    val _ = server.scanAnnotations[IdleServer.type]
    val fiber = Unsafe.unsafe(implicit u =>
      Runtime.default.unsafe.fork(
        server.buildRouter.flatMap(r => JvmHttpBackend.serveHttp(r, server.settings))
      )
    )
    awaitListening(port, attemptsLeft = 150)
    running.set(Some(Running(port, fiber)))

  override def afterAll(): Unit =
    running.getAndSet(None).foreach { r =>
      // zio-http's graceful shutdown waits up to 10 s for open streams; bound the wait.
      runUnsafe(r.fiber.interrupt.timeout(20.seconds).unit)
    }

  private def port: Int = running.get().map(_.port).getOrElse(fail("server not started"))

  private val client = HttpClient
    .newBuilder()
    .version(HttpClient.Version.HTTP_1_1)
    .connectTimeout(java.time.Duration.ofSeconds(5))
    .build()

  private def post(body: String, sid: Option[String]): HttpResponse[String] =
    val base = HttpRequest
      .newBuilder(URI.create(s"http://127.0.0.1:$port/mcp"))
      .timeout(java.time.Duration.ofSeconds(30))
      .header("content-type", "application/json")
      .header("accept", "application/json, text/event-stream")
      .POST(HttpRequest.BodyPublishers.ofString(body))
    val req = sid.fold(base)(s => base.header(SessionIdHeader, s)).build()
    // ofString waits for the SSE body to END: the streamable reply stream closes after the final
    // frame (or, on main, when eviction interrupts the request replyless).
    client.send(req, HttpResponse.BodyHandlers.ofString())

  private def initialize(): String =
    val resp = post(initFrame, None)
    withClue(s"initialize: ${resp.statusCode()} ${resp.body()}") {
      resp.statusCode() shouldBe 200
    }
    resp.headers().firstValue(SessionIdHeader).orElseGet(() => fail("no session id minted"))

  test("streamable HTTP: a session idle past sessionIdleTimeout is evicted by the sweeper and later answers 404 -32001") {
    val sid = initialize()
    post(listFrame, Some(sid)).statusCode() shouldBe 200 // alive right away
    // > timeout (500 ms) + one sweep interval (1 s) + slack.
    Thread.sleep(2500)
    val after = post(listFrame, Some(sid))
    withClue(s"after idling: ${after.statusCode()} ${after.body()}") {
      after.statusCode() shouldBe 404
      after.body() should include("-32001")
    }
  }

  test("DRAFT RED (D6 step 10 P2): an in-flight legacy request whose session is evicted for idleness still receives a JSON-RPC frame") {
    val sid = initialize()
    // `nap` runs 3 s; the sweeper evicts the (POST-touched, then quiet) session within ~1.5 s of the
    // POST, i.e. while the request is in flight. On main the SSE body ends with no frame at all.
    val started = java.lang.System.nanoTime()
    val resp = post(napFrame, Some(sid))
    val elapsedMs = (java.lang.System.nanoTime() - started) / 1_000_000L
    resp.statusCode() shouldBe 200
    withClue(
      s"in-flight request ended after ${elapsedMs} ms with body <<${resp.body()}>> " +
        "(expected either the tool's result or a -32001 'Session terminated' error for id 2) "
    ) {
      resp.body() should include(""""id":2""")
    }
  }
