package com.tjclp.fastmcp
package server.transport

import java.io.{BufferedInputStream, ByteArrayOutputStream, InputStream, OutputStream}
import java.net.{InetSocketAddress, ServerSocket, Socket, SocketTimeoutException}
import java.nio.charset.StandardCharsets

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.core.{LoggingLevel, ProgressToken, TaskSupport}
import com.tjclp.fastmcp.server.TaskSettings
import com.tjclp.fastmcp.server.transport.http.SocketHttpServer

/** Wire-level contract of the `java.net.ServerSocket` HTTP backend, driven by a hand-rolled
  * blocking HTTP/1.1 client over `java.net.Socket` (there is no `java.net.http` on Scala Native).
  * Compiled into both the JVM and the Scala Native test binaries (`jvm-native/test`): the MCP
  * semantics are the shared handler's and are pinned by `JvmHttpTransportTest`; what this suite
  * pins is the socket layer — framing, keep-alive, chunked SSE, disconnect detection, the caps —
  * on both platforms' thread and socket runtimes.
  */
class SocketHttpBackendTest extends AnyFunSuite with Matchers:

  // --- server under test -------------------------------------------------------------------

  case class AddArgs(a: Int, b: Int)
  case class AddResult(sum: Int)

  private val addTool =
    McpTool[AddArgs, AddResult](name = "add", description = Some("Add two numbers")) { args =>
      AddResult(args.a + args.b)
    }

  case class ChattyArgs(msg: Option[String] = None)
  given JsonDecoder[ChattyArgs] = DeriveJsonDecoder.gen[ChattyArgs]

  /** Emits progress + a log after a beat. Run as a task, those pushes land on the session's
    * outbound queue — i.e. the standalone GET SSE channel.
    */
  private val chattyTool = McpTool
    .withSchema[ChattyArgs, String](
      name = "chatty",
      inputSchema = ToolInputSchema.unsafeFromJsonString("""{"type":"object","properties":{}}"""),
      description = Some("Emits progress and a log mid-task")
    )
    .contextual { (_, ctx) =>
      ZIO.sleep(200.millis) *>
        ctx.get.sendProgress(ProgressToken.StringToken("t"), 0.5) *>
        ctx.get.sendLogMessage(LoggingLevel.Info, Json.Str("mid-task")).as("chatty done")
    }
    .withTaskSupport(TaskSupport.Optional)

  private def baseSettings: McpServerSettings =
    McpServerSettings(
      host = "127.0.0.1",
      port = 0,
      loggingEnabled = true,
      tasks = TaskSettings(enabled = true, pollIntervalMs = 50)
    )

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  /** Start the socket backend on a free port for the duration of `body`, then tear it down. */
  private def withServer[A](settings: McpServerSettings = baseSettings)(body: Int => A): A =
    val server = McpServer.typed[Any]("SocketT", "0.1.0", settings)
    runUnsafe(server.tool(addTool) *> server.tool(chattyTool))
    runUnsafe(
      ZIO
        .scoped {
          for
            router <- server.buildRouter
            started <- new SocketHttpBackend(summon[TransportBackend]).start(router, settings)
            // Blocking client reads cannot be interrupted, so race the body against the accept
            // loop: a loop that dies (a platform surprise in `accept`) fails the test at once
            // instead of leaving the client waiting on a listener that never answers.
            result <- ZIO.attemptBlocking(body(started.bound.port)).raceFirst(started.loop.join)
          yield result
        }
        .timeoutFail(new RuntimeException("socket backend test timed out"))(60.seconds)
    )

  // --- raw HTTP/1.1 client ---------------------------------------------------------------------

  final case class Reply(status: Int, headers: Map[String, String], body: String):
    def header(name: String): Option[String] = headers.get(name.toLowerCase)

  /** One blocking HTTP/1.1 connection; reads heads, fixed bodies, and SSE chunks byte-exactly.
    * A watchdog thread closes the socket after `readTimeoutMs` of total lifetime so a server that
    * never answers fails the test instead of hanging it — `setSoTimeout` is not honoured for reads
    * on every platform, and a blocked socket read cannot be interrupted.
    */
  final class RawClient(port: Int, readTimeoutMs: Int = 10_000):
    private val socket = new Socket()
    socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000)
    socket.setSoTimeout(readTimeoutMs)
    private val in: InputStream = new BufferedInputStream(socket.getInputStream)
    private val out: OutputStream = socket.getOutputStream
    private val watchdog = new Thread(() =>
      try
        Thread.sleep(readTimeoutMs.toLong)
        java.lang.System.err.println(s"[raw-client] watchdog closing socket on port $port")
        socket.close()
      catch case _: InterruptedException => ()
    )
    watchdog.setDaemon(true)
    watchdog.start()

    def send(raw: String): Unit =
      out.write(raw.getBytes(StandardCharsets.UTF_8))
      out.flush()

    def close(): Unit =
      watchdog.interrupt()
      socket.close()

    /** Abrupt teardown as a vanished client would do it. */
    def abort(): Unit =
      watchdog.interrupt()
      socket.setSoLinger(true, 0)
      socket.close()

    /** Next line without its CR/LF; `None` at EOF. */
    def readLine(): Option[String] =
      val buf = new ByteArrayOutputStream()
      var byte = in.read()
      while byte != -1 && byte != '\n' do
        buf.write(byte)
        byte = in.read()
      if byte == -1 && buf.size() == 0 then None
      else
        val line = buf.toString("ISO-8859-1")
        Some(if line.endsWith("\r") then line.dropRight(1) else line)

    /** Status line + headers (names lowercased). Fails at EOF. */
    def readHead(): (Int, Map[String, String]) =
      val statusLine = readLine().getOrElse(fail("EOF before status line"))
      statusLine should startWith("HTTP/1.1 ")
      val status = statusLine.substring(9, 12).toInt
      val headers = Iterator
        .continually(readLine().getOrElse(fail("EOF inside headers")))
        .takeWhile(_.nonEmpty)
        .map { line =>
          val i = line.indexOf(':')
          line.substring(0, i).trim.toLowerCase -> line.substring(i + 1).trim
        }
        .toMap
      (status, headers)

    def readExactly(n: Int): Array[Byte] =
      val bytes = new Array[Byte](n)
      var off = 0
      while off < n do
        val read = in.read(bytes, off, n - off)
        if read < 0 then fail(s"EOF after $off of $n body bytes")
        off += read
      bytes

    /** One chunk of a chunked body as text, or `None` at the terminating zero chunk. */
    def readChunk(): Option[String] =
      val sizeLine = readLine().getOrElse(fail("EOF inside chunked body"))
      val size = Integer.parseInt(sizeLine.trim, 16)
      if size == 0 then
        // trailers (none expected) up to the blank line
        Iterator.continually(readLine().getOrElse("")).takeWhile(_.nonEmpty).foreach(_ => ())
        None
      else
        val data = new String(readExactly(size), StandardCharsets.UTF_8)
        readLine() shouldBe Some("")
        Some(data)

    /** Read a whole reply: fixed-length, chunked (all chunks), or nothing. */
    def readReply(): Reply =
      val (status, headers) = readHead()
      val body =
        headers.get("transfer-encoding") match
          case Some(te) if te.equalsIgnoreCase("chunked") =>
            Iterator.continually(readChunk()).takeWhile(_.isDefined).flatten.mkString
          case _ =>
            headers.get("content-length").map(_.toInt).filter(_ > 0) match
              case Some(n) => new String(readExactly(n), StandardCharsets.UTF_8)
              case None => ""
      Reply(status, headers, body)

    /** `true` once the server has closed its side (read returns EOF). */
    def atEof(): Boolean =
      try in.read() == -1
      catch case _: SocketTimeoutException => false

  /** One-shot request on a fresh connection. */
  private def request(port: Int, raw: String): Reply =
    val client = new RawClient(port)
    try
      client.send(raw)
      client.readReply()
    finally client.close()

  // --- request builders -----------------------------------------------------------------------

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""
  private val initializedFrame = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
  private val listFrame = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""
  private val addFrame =
    """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"add","arguments":{"a":2,"b":40}}}"""
  private val chattyTaskFrame =
    """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"chatty","arguments":{},"task":{"ttl":60000}}}"""

  private def headerLines(extra: List[(String, String)]): String =
    extra.map { case (k, v) => s"$k: $v\r\n" }.mkString

  private def post(
      body: String,
      sid: Option[String] = None,
      extra: List[(String, String)] = Nil,
      accept: String = "application/json, text/event-stream",
      host: String = "127.0.0.1"
  ): String =
    val bytes = body.getBytes(StandardCharsets.UTF_8).length
    s"POST /mcp HTTP/1.1\r\nHost: $host\r\nContent-Type: application/json\r\nAccept: $accept\r\n" +
      sid.fold("")(s => s"Mcp-Session-Id: $s\r\n") + headerLines(extra) +
      s"Content-Length: $bytes\r\n\r\n$body"

  private def get(sid: Option[String], extra: List[(String, String)] = Nil): String =
    s"GET /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nAccept: text/event-stream\r\n" +
      sid.fold("")(s => s"Mcp-Session-Id: $s\r\n") + headerLines(extra) + "\r\n"

  private def delete(sid: String): String =
    s"DELETE /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nMcp-Session-Id: $sid\r\n\r\n"

  private def initSession(port: Int): String =
    val reply = request(port, post(initFrame))
    reply.status shouldBe 200
    reply.header("mcp-session-id").getOrElse(fail("initialize did not mint a session"))

  // --- tests --------------------------------------------------------------------------------------

  test("initialize mints a session and streams the reply as chunked SSE") {
    withServer() { port =>
      val reply = request(port, post(initFrame))
      reply.status shouldBe 200
      reply.header("content-type") shouldBe Some("text/event-stream")
      reply.header("transfer-encoding") shouldBe Some("chunked")
      reply.header("connection") shouldBe Some("keep-alive")
      reply.header("mcp-session-id").exists(_.nonEmpty) shouldBe true
      reply.body should startWith("event: message\ndata: ")
      reply.body should include(""""protocolVersion":"2025-11-25"""")
      reply.body should endWith("\n\n")
    }
  }

  test("a request reply carries the tool result; a notification gets 202 with no body") {
    withServer() { port =>
      val sid = initSession(port)
      val call = request(port, post(addFrame, Some(sid)))
      call.status shouldBe 200
      call.body should include("""{\"sum\":42}""")
      val ack = request(port, post(initializedFrame, Some(sid)))
      ack.status shouldBe 202
      ack.header("content-length") shouldBe Some("0")
      ack.body shouldBe ""
    }
  }

  test("unknown session → 404 with the SessionNotFound code; headerless non-initialize → 400") {
    withServer() { port =>
      val unknown = request(port, post(listFrame, Some("nope")))
      unknown.status shouldBe 404
      unknown.header("content-type") shouldBe Some("application/json")
      unknown.body should include(""""code":-32001""")
      val headerless = request(port, post(listFrame))
      headerless.status shouldBe 400
      headerless.body should include("mcp-session-id")
    }
  }

  test("GET channel: 405 without a session, 200 stream, 409 on a second stream, DELETE ends it") {
    withServer() { port =>
      request(port, get(None)).status shouldBe 405
      val sid = initSession(port)

      val stream = new RawClient(port)
      stream.send(get(Some(sid)))
      val (status, headers) = stream.readHead()
      status shouldBe 200
      headers.get("content-type") shouldBe Some("text/event-stream")
      headers.get("transfer-encoding") shouldBe Some("chunked")

      val second = request(port, get(Some(sid)))
      second.status shouldBe 409

      val deleted = request(port, delete(sid))
      deleted.status shouldBe 200
      // The DELETE shut the outbound queue: the open stream ends with the terminating chunk.
      stream.readChunk() shouldBe None
      stream.close()

      request(port, get(Some(sid))).status shouldBe 404
    }
  }

  test("server-initiated pushes (task progress/log) arrive on the GET channel") {
    withServer() { port =>
      val sid = initSession(port)
      val stream = new RawClient(port)
      stream.send(get(Some(sid)))
      stream.readHead()._1 shouldBe 200

      val created = request(port, post(chattyTaskFrame, Some(sid)))
      created.status shouldBe 200
      created.body should include(""""taskId"""")

      // The task's pushes are emitted after its creating POST completed; they can only travel
      // over the standalone stream. Collect until the log line shows up.
      val events = scala.collection.mutable.ListBuffer.empty[String]
      while !events.exists(_.contains("mid-task")) && events.size < 8 do
        events += stream.readChunk().getOrElse(fail("GET stream ended early"))
      events.exists(_.contains("mid-task")) shouldBe true
      events.exists(_.contains("notifications/")) shouldBe true
      stream.close()
    }
  }

  test("keepalive pings flow on a quiet GET stream") {
    withServer(baseSettings.copy(keepAliveInterval = Some(java.time.Duration.ofMillis(100)))) {
      port =>
        val sid = initSession(port)
        val stream = new RawClient(port)
        stream.send(get(Some(sid)))
        stream.readHead()._1 shouldBe 200
        val first = stream.readChunk().getOrElse(fail("stream ended"))
        val secondPing = stream.readChunk().getOrElse(fail("stream ended"))
        first shouldBe "event: ping\n\n"
        secondPing shouldBe "event: ping\n\n"
        stream.close()
    }
  }

  test("keep-alive: several requests on one connection, including an SSE reply in the middle") {
    withServer() { port =>
      val client = new RawClient(port)
      client.send(post(initFrame))
      val init = client.readReply()
      init.status shouldBe 200
      val sid = init.header("mcp-session-id").getOrElse(fail("no session"))
      client.send(post(addFrame, Some(sid)))
      val call = client.readReply()
      call.status shouldBe 200
      call.body should include("""{\"sum\":42}""")
      client.send(post(initializedFrame, Some(sid)))
      client.readReply().status shouldBe 202
      client.send(post(listFrame, Some(sid)))
      client.readReply().body should include(""""name":"add"""")
      client.close()
    }
  }

  test("malformed request line → 400 JSON-RPC error and the connection closes") {
    withServer() { port =>
      val client = new RawClient(port)
      client.send("GARBAGE\r\n\r\n")
      val reply = client.readReply()
      reply.status shouldBe 400
      reply.header("connection") shouldBe Some("close")
      reply.body should include(""""code":-32000""")
      client.atEof() shouldBe true
      client.close()
    }
  }

  test("oversized head → 431; declared body over the cap → 413 before reading it") {
    withServer() { port =>
      val huge = "x" * (SocketHttpServer.MaxHeadBytes + 512)
      val big = request(port, get(None, extra = List("X-Pad" -> huge)))
      big.status shouldBe 431

      val client = new RawClient(port)
      client.send(
        s"POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: ${SocketHttpServer.MaxBodyBytes + 1}\r\n\r\n"
      )
      val reply = client.readReply()
      reply.status shouldBe 413
      reply.header("connection") shouldBe Some("close")
      client.close()
    }
  }

  test("chunked request bodies are decoded") {
    withServer() { port =>
      val (a, b) = initFrame.splitAt(40)
      def chunk(s: String) = s"${Integer.toHexString(s.length)}\r\n$s\r\n"
      val raw =
        "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n" +
          "Accept: application/json, text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n" +
          chunk(a) + chunk(b) + "0\r\n\r\n"
      val reply = request(port, raw)
      reply.status shouldBe 200
      reply.header("mcp-session-id").isDefined shouldBe true
    }
  }

  test("Expect: 100-continue receives the interim response before the body is sent") {
    withServer() { port =>
      val client = new RawClient(port)
      val bytes = initFrame.getBytes(StandardCharsets.UTF_8).length
      client.send(
        "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n" +
          "Accept: application/json, text/event-stream\r\nExpect: 100-continue\r\n" +
          s"Content-Length: $bytes\r\n\r\n"
      )
      client.readLine() shouldBe Some("HTTP/1.1 100 Continue")
      client.readLine() shouldBe Some("")
      client.send(initFrame)
      client.readReply().status shouldBe 200
      client.close()
    }
  }

  test("HTTP/1.0 requests get close-delimited responses") {
    withServer() { port =>
      val client = new RawClient(port)
      client.send("GET /mcp HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n")
      val reply = client.readReply()
      reply.status shouldBe 405
      reply.header("connection") shouldBe Some("close")
      client.atEof() shouldBe true
      client.close()
    }
  }

  test("unknown path → 404; unsupported method → 405 with Allow") {
    withServer() { port =>
      request(port, "GET /other HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").status shouldBe 404
      val put = request(port, "PUT /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 0\r\n\r\n")
      put.status shouldBe 405
      put.header("allow") shouldBe Some("POST, GET, DELETE")
    }
  }

  test("Host guard (403) and Accept negotiation (406) reach the wire") {
    withServer(baseSettings.copy(allowedHosts = Some(Set("localhost")))) { port =>
      request(port, post(initFrame, host = "evil.example")).status shouldBe 403
      val ok = request(port, post(initFrame, host = "localhost:1234"))
      ok.status shouldBe 200
      request(port, post(initFrame, host = "localhost", accept = "text/plain")).status shouldBe 406
    }
  }

  test("stateless mode answers with plain JSON and refuses GET") {
    withServer(baseSettings.copy(stateless = true)) { port =>
      val reply = request(port, post(initFrame, accept = "application/json"))
      reply.status shouldBe 200
      reply.header("content-type") shouldBe Some("application/json")
      reply.header("content-length").isDefined shouldBe true
      reply.header("mcp-session-id") shouldBe None
      reply.body should include(""""protocolVersion"""")
      request(port, get(None)).status shouldBe 405
    }
  }

  test("a client that vanishes mid-GET releases the session's stream slot") {
    withServer() { port =>
      val sid = initSession(port)
      val stream = new RawClient(port)
      stream.send(get(Some(sid)))
      stream.readHead()._1 shouldBe 200
      stream.abort()

      // The server notices the disconnect on its read side (no writes are pending) and runs the
      // stream's finalizer, so a replacement GET is accepted instead of answering 409.
      val deadline = java.lang.System.currentTimeMillis() + 5_000
      var status = 409
      while status == 409 && java.lang.System.currentTimeMillis() < deadline do
        Thread.sleep(100)
        val retry = new RawClient(port)
        retry.send(get(Some(sid)))
        status = retry.readHead()._1
        retry.close()
      status shouldBe 200
    }
  }

  test("concurrent connections are served independently") {
    withServer() { port =>
      val results = (1 to 8).map { _ =>
        ZIO.attemptBlocking {
          val sid = initSession(port)
          request(port, post(addFrame, Some(sid)))
        }
      }
      val replies = runUnsafe(ZIO.collectAllPar(results))
      replies.foreach { reply =>
        reply.status shouldBe 200
        reply.body should include("""{\"sum\":42}""")
      }
    }
  }

  test("closing the server scope frees the port") {
    val port = withServer()(identity)
    val probe = new ServerSocket()
    probe.setReuseAddress(true)
    probe.bind(new InetSocketAddress("127.0.0.1", port))
    probe.close()
  }
