package com.tjclp.fastmcp
package server.transport

import java.nio.file.{Files, Path as FsPath, Paths}

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.stream.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.jsonrpc.JsonRpcMessage
import com.tjclp.fastmcp.macros.RegistrationMacro.*
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.router.Session
import com.tjclp.fastmcp.server.transport.JvmTransportBackend.given

/** `Session.outbound` — the channel for messages emitted outside a request's own response stream —
  * holds a fixed number of messages whether or not anything reads it. A legacy HTTP session sheds
  * the oldest without blocking or interrupting the emitter; a stdio session makes the emitter wait
  * for the writer instead, so it loses nothing; a session whose transport has no reader for the
  * channel buffers nothing at all. Sessions that do have a reader (stdio, a JVM legacy session with
  * a live GET stream) still receive task status notifications.
  */
class SessionOutboundBoundTest extends AnyFunSuite with Matchers:

  object QuickTasks:

    @Tool(name = Some("quick"), description = Some("Done at once"), taskSupport = Some("optional"))
    def quick(): String = "quick done"

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private def note(i: Int): JsonRpcMessage =
    JsonRpcMessage.Notification("notifications/test", Some(Json.Obj("n" -> Json.Num(i))))

  private def numbers(messages: Iterable[JsonRpcMessage]): List[Int] =
    messages.toList.collect { case JsonRpcMessage.Notification(_, Some(Json.Obj(fields))) =>
      fields.collectFirst { case ("n", Json.Num(n)) => n.intValue }.getOrElse(-1)
    }

  // ---------------------------------------------------------------------------------------------
  // The channel itself.
  // ---------------------------------------------------------------------------------------------

  test("legacy HTTP: with no reader the channel holds at most its bound, newest kept") {
    val session = runUnsafe(Session.makeProtocolSession("s", outboundCapacity = 8))
    // The emitter runs to completion: it is never parked on, or interrupted by, a full buffer.
    val exit = Unsafe.unsafe(implicit u =>
      Runtime.default.unsafe.run(
        ZIO
          .foreachDiscard(1 to 500)(i => session.send(note(i)))
          .timeoutFail(new RuntimeException("send blocked at the bound"))(10.seconds)
      )
    )
    exit.isSuccess shouldBe true
    runUnsafe(session.outbound.size) shouldBe 8
    numbers(runUnsafe(session.outbound.takeAll)) shouldBe (493 to 500).toList
  }

  test("concurrent emitters cannot push the channel past its bound") {
    val session = runUnsafe(Session.makeProtocolSession("s", outboundCapacity = 16))
    runUnsafe(
      ZIO
        .foreachParDiscard(1 to 32)(f =>
          ZIO.foreachDiscard(1 to 200)(i => session.send(note(f * 1000 + i)))
        )
        .timeoutFail(new RuntimeException("send blocked at the bound"))(20.seconds)
    )
    runUnsafe(session.outbound.size) shouldBe 16
  }

  test("a reader that keeps up receives every message, in order") {
    val session = runUnsafe(Session.makeProtocolSession("s", outboundCapacity = 8))
    val received = runUnsafe(
      for
        seen <- Ref.make(Vector.empty[JsonRpcMessage])
        drainer <- session.outbound.take.flatMap(m => seen.update(_ :+ m)).forever.fork
        _ <- ZIO.foreachDiscard(1 to 200)(i =>
          session.send(note(i)) *> seen.get.repeatUntil(_.size >= i)
        )
        all <- seen.get
        _ <- drainer.interrupt
      yield all
    )
    numbers(received) shouldBe (1 to 200).toList
  }

  test("a request-scoped session buffers nothing; its per-request sink still delivers") {
    val session = runUnsafe(Session.makeRequestScoped("request-1"))
    session.outboundCapacity shouldBe 0
    runUnsafe(ZIO.foreachDiscard(1 to 50)(i => session.send(note(i))))
    runUnsafe(session.outbound.size) shouldBe 0
    val viaSink = runUnsafe(
      for
        sink <- Queue.unbounded[JsonRpcMessage]
        _ <- session.runWithSink(sink)(session.send(note(7)))
        got <- sink.takeAll
      yield got
    )
    numbers(viaSink) shouldBe List(7)
    runUnsafe(session.outbound.size) shouldBe 0
  }

  test("capacities: Session.make uses the LimitSettings default; the setting must be >= 1") {
    runUnsafe(Session.make("x")).outboundCapacity shouldBe Session.DefaultOutboundCapacity
    LimitSettings().maxOutboundMessages shouldBe Session.DefaultOutboundCapacity
    an[IllegalArgumentException] should be thrownBy LimitSettings(maxOutboundMessages = 0)
  }

  // ---------------------------------------------------------------------------------------------
  // Streamable HTTP on the JVM.
  // ---------------------------------------------------------------------------------------------

  private val SessionIdHeader = "mcp-session-id"

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""

  private def buildRoutes(maxOutboundMessages: Int): Routes[Any, Response] =
    val server = McpServer.typed[Any](
      "OutboundT",
      "0.1.0",
      McpServerSettings(
        tasks = TaskSettings(enabled = true, pollIntervalMs = 20),
        limits = LimitSettings(maxOutboundMessages = maxOutboundMessages)
      )
    )
    val _ = server.scanAnnotations[QuickTasks.type]
    runUnsafe(
      server.buildRouter.flatMap(r =>
        JvmHttpBackend.httpRoutes(r, server.settings, ZEnvironment.empty)
      )
    )

  private def run(routes: Routes[Any, Response], req: Request): Response =
    runUnsafe(ZIO.scoped(routes.runZIO(req)))

  private def post(routes: Routes[Any, Response], body: String, sid: Option[String]): Response =
    val base = Request
      .post(URL(Path.root / "mcp"), Body.fromString(body))
      .addHeader(Header.Custom("content-type", "application/json"))
      .addHeader(Header.Custom("accept", "application/json, text/event-stream"))
    run(routes, sid.fold(base)(s => base.addHeader(Header.Custom(SessionIdHeader, s))))

  private def bodyOf(resp: Response): String = runUnsafe(resp.body.asString)

  private def initSession(routes: Routes[Any, Response]): String =
    val resp = post(routes, initFrame, None)
    val _ = bodyOf(resp)
    resp.rawHeader(SessionIdHeader).getOrElse(fail("initialize did not return a session id"))

  private val TaskIdPattern = """"taskId":"([^"]+)"""".r

  private def taskIdOf(body: String): String =
    TaskIdPattern.findFirstMatchIn(body).map(_.group(1)).getOrElse(fail(s"no taskId in: $body"))

  private def augmentedCall(id: Int): String =
    s"""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"quick","arguments":{},"task":{"ttl":60000}}}"""

  /** Run one task-augmented call on `sid` and wait until its task reports `completed`. */
  private def completedTask(routes: Routes[Any, Response], sid: String, id: Int): String =
    val taskId = taskIdOf(bodyOf(post(routes, augmentedCall(id), Some(sid))))
    val get = s"""{"jsonrpc":"2.0","id":9,"method":"tasks/get","params":{"taskId":"$taskId"}}"""
    runUnsafe(
      (ZIO.sleep(5.millis) *> ZIO.attempt(bodyOf(post(routes, get, Some(sid)))))
        .repeatUntil(_.contains(""""status":"completed""""))
        .timeoutFail(new RuntimeException(s"task $taskId never completed"))(15.seconds)
    )
    taskId

  private def openGet(routes: Routes[Any, Response], sid: String): Response =
    run(routes, Request.get(URL(Path.root / "mcp")).addHeader(Header.Custom(SessionIdHeader, sid)))

  private def statusEvents(text: String): Int =
    "notifications/tasks/status".r.findAllIn(text).size

  test("a legacy session with no GET stream keeps at most maxOutboundMessages task notifications") {
    val bound = 8
    val routes = buildRoutes(maxOutboundMessages = bound)
    val sid = initSession(routes)
    // Many more completed tasks than the bound, and nobody reading the standalone stream.
    val ids = (1 to 40).map(i => completedTask(routes, sid, 100 + i))
    // What the session kept is exactly what a GET opened now is handed.
    val backlog = runUnsafe(
      openGet(routes, sid).body.asStream
        .interruptAfter(1500.millis)
        .runCollect
        .map(bytes => new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8))
    )
    statusEvents(backlog) should be <= bound
    statusEvents(backlog) should be >= 1
    // Oldest shed first: the most recent task's notification is in the backlog, the first is not.
    backlog should include(ids.last)
    backlog should not include ids.head
  }

  test("a legacy session with a live GET stream still receives task status notifications") {
    val routes = buildRoutes(maxOutboundMessages = 8)
    val sid = initSession(routes)
    val stream = openGet(routes, sid).body.asStream
    val (ids, text) = runUnsafe(
      (for
        seen <- Ref.make("")
        reader <- stream
          .runForeachChunk(chunk =>
            seen.update(_ + new String(chunk.toArray, java.nio.charset.StandardCharsets.UTF_8))
          )
          .fork
        // Three times the bound, one at a time: a reader that keeps up misses none of them.
        ids <- ZIO.foreach((1 to 24).toList) { i =>
          ZIO
            .attemptBlocking(completedTask(routes, sid, 200 + i))
            .tap(id => (ZIO.sleep(5.millis) *> seen.get).repeatUntil(_.contains(id)))
        }
        text <- seen.get
        _ <- reader.interrupt
      yield (ids, text)).timeoutFail(new RuntimeException("GET stream fell behind"))(60.seconds)
    )
    statusEvents(text) shouldBe 24
    ids.foreach(id => text should include(id))
    text should include(""""status":"completed"""")
  }

  // ---------------------------------------------------------------------------------------------
  // stdio.
  // ---------------------------------------------------------------------------------------------

  test("a stdio session still receives task status notifications") {
    val server = McpServer.typed[Any](
      "OutboundStdio",
      "0.1.0",
      McpServerSettings(tasks = TaskSettings(enabled = true))
    )
    val _ = server.scanAnnotations[QuickTasks.type]
    def takeUntil(q: Queue[String], pred: String => Boolean): UIO[String] =
      q.take.flatMap(s => if pred(s) then ZIO.succeed(s) else takeUntil(q, pred))
    val status = runUnsafe(
      (for
        router <- server.buildRouter
        session <- Session.makeDrained("stdio", router.limits.maxOutboundMessages)
        inQ <- Queue.unbounded[String]
        outQ <- Queue.unbounded[String]
        loop <- StdioLoop.run(router, session, ZStream.fromQueue(inQ), s => outQ.offer(s).unit).fork
        _ <- inQ.offer(initFrame)
        _ <- takeUntil(outQ, _.contains("\"id\":1"))
        _ <- inQ.offer("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        _ <- inQ.offer(augmentedCall(2))
        line <- takeUntil(outQ, _.contains("notifications/tasks/status"))
        _ <- loop.interrupt
      yield line).timeoutFail(new RuntimeException("no task status on stdio"))(15.seconds)
    )
    status should include(""""status":"completed"""")
  }

  test("stdio applies back-pressure at the bound: the emitter waits and nothing is lost") {
    val session = runUnsafe(Session.makeDrained("stdio", outboundCapacity = 8))
    val (parked, received) = runUnsafe(
      (for
        emitter <- ZIO.foreachDiscard(1 to 40)(i => session.send(note(i))).fork
        // No reader yet: the emitter fills the buffer and waits. It neither finishes nor drops.
        _ <- ZIO.sleep(300.millis)
        parked <- emitter.poll.map(_.isEmpty)
        seen <- Ref.make(Vector.empty[JsonRpcMessage])
        drainer <- session.outbound.take.flatMap(m => seen.update(_ :+ m)).forever.fork
        _ <- emitter.join
        all <- (ZIO.sleep(5.millis) *> seen.get).repeatUntil(_.size >= 40)
        _ <- drainer.interrupt
      yield (parked, all)).timeoutFail(new RuntimeException("emitter never resumed"))(30.seconds)
    )
    parked shouldBe true
    numbers(received) shouldBe (1 to 40).toList
  }

  test("stdio: a writer slower than the emitter still writes every message, in order") {
    val server = McpServer.typed[Any]("OutboundStdioSlow", "0.1.0", McpServerSettings())
    val lines = runUnsafe(
      (for
        router <- server.buildRouter
        session <- Session.makeDrained("stdio", outboundCapacity = 8)
        inQ <- Queue.unbounded[String]
        written <- Ref.make(Vector.empty[String])
        loop <- StdioLoop
          .run(
            router,
            session,
            ZStream.fromQueue(inQ),
            line => ZIO.sleep(2.millis) *> written.update(_ :+ line)
          )
          .fork
        _ <- ZIO.foreachDiscard(1 to 100)(i => session.send(note(i)))
        all <- (ZIO.sleep(5.millis) *> written.get).repeatUntil(_.size >= 100)
        _ <- loop.interrupt
      yield all).timeoutFail(new RuntimeException("the stdio writer lost messages"))(60.seconds)
    )
    lines should have size 100
    lines.zipWithIndex.foreach((line, i) => line should include(s""""n":${i + 1}}"""))
  }

  test("after stdin EOF a stdio session neither buffers nor blocks an emitter") {
    val server = McpServer.typed[Any]("OutboundStdioEof", "0.1.0", McpServerSettings())
    val written = runUnsafe(
      (for
        router <- server.buildRouter
        session <- Session.makeDrained("stdio", outboundCapacity = 4)
        outQ <- Queue.unbounded[String]
        // A finite inbound stream IS the EOF: the loop returns and its writer is gone.
        _ <- StdioLoop.run(router, session, ZStream(initFrame), s => outQ.offer(s).unit)
        // Far more than the bound, with nobody reading: every send returns at once.
        _ <- ZIO.foreachDiscard(1 to 100)(i => session.send(note(i)))
        _ <- ZIO.sleep(100.millis)
        all <- outQ.takeAll
      yield all).timeoutFail(new RuntimeException("a send blocked after EOF"))(15.seconds)
    )
    written.count(_.contains("notifications/test")) shouldBe 0
  }

  test("closing the channel releases an emitter that was waiting for the writer") {
    val session = runUnsafe(Session.makeDrained("stdio", outboundCapacity = 4))
    val (parked, ended) = runUnsafe(
      (for
        emitter <- ZIO.foreachDiscard(1 to 20)(i => session.send(note(i))).fork
        _ <- ZIO.sleep(300.millis)
        parked <- emitter.poll.map(_.isEmpty)
        _ <- session.closeOutbound
        exit <- emitter.await
        // From now on a send keeps nothing and returns at once.
        _ <- ZIO.foreachDiscard(1 to 50)(i => session.send(note(i)))
      yield (parked, exit)).timeoutFail(new RuntimeException("emitter still parked"))(15.seconds)
    )
    parked shouldBe true
    ended.isInterrupted shouldBe true
  }

  // ---------------------------------------------------------------------------------------------
  // Wiring: which transports build which kind of session.
  // ---------------------------------------------------------------------------------------------

  private def repoRoot: FsPath =
    Iterator
      .iterate(Option(Paths.get(java.lang.System.getProperty("user.dir")).toAbsolutePath))(
        _.flatMap(p => Option(p.getParent))
      )
      .takeWhile(_.isDefined)
      .flatten
      .find(p =>
        Files.exists(p.resolve("build.mill")) && Files.isDirectory(p.resolve("fast-mcp-scala"))
      )
      .getOrElse(fail("could not locate the repository root (build.mill) from user.dir"))

  private def source(relative: String): String =
    Files.readString(repoRoot.resolve("fast-mcp-scala").resolve(relative))

  /** Whitespace-insensitive, so a call the formatter wrapped over several lines still matches. */
  private def squashed(text: String): String = text.replaceAll("\\s+", "")

  test("HTTP transports build request-scoped sessions; only a drained channel gets a buffer") {
    val handler = squashed(
      source("shared/src/com/tjclp/fastmcp/server/transport/http/StreamableHttpHandler.scala")
    )
    val bun = squashed(source("js/src/com/tjclp/fastmcp/server/transport/JsTransportBackend.scala"))
    val stdio = squashed(source("shared/src/com/tjclp/fastmcp/server/transport/StdioLoop.scala"))

    // A bare `Session.make(` would give a per-request session a buffered channel nobody reads.
    handler should not include "Session.make("
    handler should include("""Session.makeRequestScoped(s"request-""")
    handler should include("""Session.makeRequestScoped("notification")""")
    handler should include("""Session.makeRequestScoped("stateless",supportsTasks=false)""")
    // The JVM's standalone GET stream drains a legacy session's channel: bounded by the setting.
    // (The middle argument is the peer address the session store counts sessions by.)
    handler should include(
      "Session.makeProtocolSession(id,req.clientKey,settings.limits.maxOutboundMessages)"
    )

    // Bun answers the standalone GET with 405, so no HTTP session there has a reader.
    bun should not include "Session.make("
    bun should include("""Session.makeRequestScoped(s"request-""")
    bun should include("""Session.makeRequestScoped("notification")""")
    bun should include("""Session.makeRequestScoped("stateless",supportsTasks=false)""")
    bun should include("Session.makeProtocolSession(sid,peer,outboundCapacity=0)")

    // stdio always has a reader (the drainer), on every platform.
    bun should include("""Session.makeDrained("stdio",router.limits.maxOutboundMessages)""")
    stdio should include("""Session.makeDrained("stdio",router.limits.maxOutboundMessages)""")
    // ... until stdin reaches EOF: from then on the session must stop buffering for it.
    bun should include(".ensuring(drainer.interrupt*>session.closeOutbound)")
    stdio should include(".ensuring(drainer.interrupt*>session.closeOutbound)")
  }
