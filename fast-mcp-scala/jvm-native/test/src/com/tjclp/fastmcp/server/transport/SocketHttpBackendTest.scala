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

  case class BigArgs(megabytes: Int)

  /** A reply far larger than any socket buffer, so a client that does not read it blocks the
    * server's write.
    */
  private val bigTool =
    McpTool[BigArgs, String](name = "big", description = Some("Returns a large string")) { args =>
      "x" * (args.megabytes * 1024 * 1024)
    }

  case class RegionArgs(region: String)
  given JsonDecoder[RegionArgs] = DeriveJsonDecoder.gen[RegionArgs]

  /** A tool whose `region` argument is mirrored in the `Mcp-Param-Region` header. */
  private val regionTool = McpTool.withSchema[RegionArgs, String](
    name = "region",
    inputSchema = ToolInputSchema.unsafeFromJsonString(
      """{"type":"object","properties":{"region":{"type":"string","x-mcp-header":"Region"}},"required":["region"]}"""
    ),
    description = Some("Echoes its header-backed argument")
  )(args => args.region)

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
  private def withServer[A](
      settings: McpServerSettings = baseSettings,
      backend: SocketHttpBackend = new SocketHttpBackend(summon[TransportBackend]),
      blockingExecutor: Option[Executor] = None
  )(body: Int => A): A =
    val server = McpServer.typed[Any]("SocketT", "0.1.0", settings)
    runUnsafe(
      server.tool(addTool) *> server.tool(chattyTool) *> server.tool(bigTool) *>
        server.tool(regionTool)
    )
    runUnsafe(
      ZIO
        .scoped {
          for
            router <- server.buildRouter
            // The server takes its connection threads from ZIO's blocking executor; a test can
            // substitute one to make the hand-off fail.
            _ <- ZIO.foreachDiscard(blockingExecutor)(e => Runtime.setBlockingExecutor(e).build)
            started <- backend.start(router, settings)
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

  /** Control bytes made visible, for failure messages. */
  private def visible(raw: String): String =
    raw.flatMap {
      case '\r' => "\\r"
      case '\n' => "\\n"
      case '\t' => "\\t"
      case c if c < ' ' || c == '\u007f' => f"\\x${c.toInt}%02x"
      case c => c.toString
    }

  /** `raw` must be refused by the socket layer itself: a `400` transport error with
    * `Connection: close`, after which the server closes without answering anything else on the
    * connection — so no byte of `raw` is ever served as a further request.
    */
  private def refused(port: Int, raw: String): Unit =
    val client = new RawClient(port)
    try
      client.send(raw)
      withClue(s"request ${visible(raw)}: ") {
        val reply = client.readReply()
        reply.status shouldBe 400
        reply.header("connection") shouldBe Some("close")
        reply.body should include(""""code":-32000""")
        client.atEof() shouldBe true
      }
    finally client.close()

  private val chunkedHead =
    "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n" +
      "Accept: application/json, text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n"

  /** `initFrame` is ASCII, so its length in chars is its length in bytes. */
  private val initHex = Integer.toHexString(initFrame.length)

  private val modernMeta =
    """"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}"""

  private val modernAddFrame =
    s"""{"jsonrpc":"2.0","id":20,"method":"tools/call","params":{"name":"add","arguments":{"a":2,"b":40},$modernMeta}}"""

  private val modernRegionFrame =
    s"""{"jsonrpc":"2.0","id":21,"method":"tools/call","params":{"name":"region","arguments":{"region":"eu"},$modernMeta}}"""

  private val modernVersion = "MCP-Protocol-Version" -> "2026-07-28"
  private val callMethod = "Mcp-Method" -> "tools/call"

  /** The reply must be the header-mismatch error: HTTP 400 carrying JSON-RPC `-32020`. */
  private def headerMismatch(port: Int, body: String, headers: List[(String, String)]): Unit =
    withClue(s"headers $headers: ") {
      val reply = request(port, post(body, extra = headers))
      reply.status shouldBe 400
      reply.header("content-type") shouldBe Some("application/json")
      reply.body should include(""""code":-32020""")
    }

  // --- tests --------------------------------------------------------------------------------------

  test("initialize mints a session; its reply, complete at once, is a fixed-length SSE body") {
    withServer() { port =>
      val reply = request(port, post(initFrame))
      reply.status shouldBe 200
      reply.header("content-type") shouldBe Some("text/event-stream")
      reply.header("transfer-encoding") shouldBe None
      reply.header("content-length").map(_.toInt) shouldBe
        Some(reply.body.getBytes(StandardCharsets.UTF_8).length)
      reply.header("connection") shouldBe Some("keep-alive")
      reply.header("mcp-session-id").exists(_.nonEmpty) shouldBe true
      reply.body should startWith("event: message\ndata: ")
      reply.body should include(""""protocolVersion":"2025-11-25"""")
      reply.body should endWith("\n\n")
    }
  }

  test("a call that emits notifications before its reply is streamed as chunked SSE, in order") {
    withServer() { port =>
      val sid = initSession(port)
      request(port, post(initializedFrame, Some(sid))).status shouldBe 202
      // Not task-augmented: the progress and the log travel on this request's own stream.
      val chatty =
        """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"chatty","arguments":{}}}"""
      val reply = request(port, post(chatty, Some(sid)))
      reply.status shouldBe 200
      reply.header("content-type") shouldBe Some("text/event-stream")
      reply.header("transfer-encoding") shouldBe Some("chunked")
      reply.header("content-length") shouldBe None
      val progress = reply.body.indexOf("notifications/progress")
      val result = reply.body.indexOf("chatty done")
      progress should be >= 0
      result should be > progress
      reply.body should endWith("\n\n")
    }
  }

  test("a 2026-07-28 reply that is complete at once is a fixed-length SSE body; the connection stays usable") {
    withServer() { port =>
      def modernAdd(id: Int): String =
        post(
          s"""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"add","arguments":{"a":2,"b":40},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}""",
          extra = List(
            "Mcp-Protocol-Version" -> "2026-07-28",
            "Mcp-Method" -> "tools/call",
            "Mcp-Name" -> "add"
          )
        )
      val client = new RawClient(port)
      try
        // Two requests on ONE connection: a wrong Content-Length would desynchronise the second.
        List(10, 11).foreach { id =>
          client.send(modernAdd(id))
          val reply = client.readReply()
          reply.status shouldBe 200
          reply.header("content-type") shouldBe Some("text/event-stream")
          reply.header("cache-control") shouldBe Some("no-cache")
          reply.header("x-accel-buffering") shouldBe Some("no")
          reply.header("transfer-encoding") shouldBe None
          reply.header("connection") shouldBe Some("keep-alive")
          reply.header("content-length").map(_.toInt) shouldBe
            Some(reply.body.getBytes(StandardCharsets.UTF_8).length)
          reply.body should startWith("event: message\ndata: ")
          reply.body should include(s""""id":$id""")
          reply.body should include("""{\"sum\":42}""")
          reply.body should endWith("\n\n")
        }
      finally client.close()
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
      // The body is never sent: the shared gates judge the declared length on the head alone.
      client.send(
        "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n" +
          s"Content-Length: ${baseSettings.maxRequestBodyBytes + 1}\r\n\r\n"
      )
      val reply = client.readReply()
      reply.status shouldBe 413
      reply.header("connection") shouldBe Some("close")
      client.close()

      // Gate order is the shared one (403 → 415 → 413 → 406): without a JSON Content-Type the
      // same oversized declaration is refused as 415 first.
      val untyped = new RawClient(port)
      untyped.send(
        s"POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: ${baseSettings.maxRequestBodyBytes + 1}\r\n\r\n"
      )
      untyped.readReply().status shouldBe 415
      untyped.close()
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

  // --- strict request-head parsing ---------------------------------------------------------------

  test("a request head must end every line in CRLF: a bare LF is refused with 400 and a close") {
    withServer() { port =>
      request(port, get(None)).status shouldBe 405 // the well-formed twin is served
      refused(port, "GET /mcp HTTP/1.1\nHost: 127.0.0.1\n\n")
      refused(port, "GET /mcp HTTP/1.1\r\nHost: 127.0.0.1\n\r\n")
      refused(port, "GET /mcp HTTP/1.1\nHost: 127.0.0.1\r\n\r\n")
      refused(port, "\nGET /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
      refused(port, "\r\n\nGET /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
    }
  }

  test("LF LF or LF CRLF inside a head does not end it: the bytes after are never a second request") {
    withServer() { port =>
      val second = "GET /other HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
      // `refused` also proves the connection closes with ONE reply: nothing answers `second`.
      refused(port, "GET /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nX-A: b\n\n" + second)
      refused(port, "GET /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nX-A: b\n\r\n" + second)
    }
  }

  test("a lone LF or CR inside a field value cannot introduce a header") {
    withServer() { port =>
      val length = s"Content-Length: ${initFrame.length}"
      def smuggled(separator: String): String =
        "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n" +
          "Accept: application/json, text/event-stream\r\n" +
          s"X-A: b$separator$length\r\n\r\n$initFrame"
      refused(port, smuggled("\n"))
      refused(port, smuggled("\r"))
    }
  }

  test("a bare CR, NUL or another control byte in a request head is refused with 400 and a close") {
    withServer() { port =>
      def withValue(value: String): String =
        s"GET /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nX-A: $value\r\n\r\n"
      // HTAB inside a value is the one control byte a head may carry.
      request(port, withValue("a\tb")).status shouldBe 405
      List("a\rb", "a\u0000b", "\u0000", "a\u000bb", "a\u000c", "\u001fa", "a\u007fb").foreach { v =>
        refused(port, withValue(v))
      }
      refused(port, "\rGET /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
      refused(port, "GET /mcp\u0000 HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
      refused(port, "GET\t/mcp HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
      refused(port, "GET /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nX\u0000A: b\r\n\r\n")
    }
  }

  test("Transfer-Encoding / Content-Length padded with control bytes are refused, not recognised") {
    withServer() { port =>
      val head =
        "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n" +
          "Accept: application/json, text/event-stream\r\n"
      val chunkedBody = s"$initHex\r\n$initFrame\r\n0\r\n\r\n"
      List("\u000b", "\u000c", "\u0000", "\r").foreach { pad =>
        refused(port, s"${head}Transfer-Encoding: ${pad}chunked\r\n\r\n$chunkedBody")
        refused(port, s"${head}Transfer-Encoding: chunked$pad\r\n\r\n$chunkedBody")
        refused(port, s"${head}Content-Length: $pad${initFrame.length}\r\n\r\n$initFrame")
        refused(port, s"${head}Content-Length: ${initFrame.length}$pad\r\n\r\n$initFrame")
      }
      // Optional whitespace (SP / HTAB) around the value is still just whitespace.
      val ok = request(port, s"${head}Content-Length: \t${initFrame.length} \r\n\r\n$initFrame")
      ok.status shouldBe 200
    }
  }

  test("a well-formed request delivered one byte at a time is still parsed") {
    withServer() { port =>
      def dribble(raw: String): Reply =
        val client = new RawClient(port)
        try
          raw.foreach { c =>
            client.send(c.toString)
            Thread.sleep(1)
          }
          client.readReply()
        finally client.close()
      // Every CR arrives without its LF, so each line end is judged across two reads.
      dribble("\r\n" + get(None)).status shouldBe 405
      val chunked = dribble(
        chunkedHead + s"$initHex;ext=1\r\n$initFrame\r\n0\r\nX-Trailer: v\r\n\r\n"
      )
      chunked.status shouldBe 200
      chunked.header("mcp-session-id").isDefined shouldBe true
    }
  }

  // --- strict chunked framing ---------------------------------------------------------------------

  test("well-formed chunked bodies decode: extensions, trailers, and the connection stays usable") {
    withServer() { port =>
      val (a, b) = initFrame.splitAt(40)
      def chunk(s: String, ext: String = "") = s"${Integer.toHexString(s.length)}$ext\r\n$s\r\n"
      val client = new RawClient(port)
      try
        client.send(
          chunkedHead + chunk(a, ";name=value") + chunk(b, " ; q=\"quoted text\"") +
            "0;last\r\nX-Trailer: one\r\nX-Other:\ttwo\r\n\r\n"
        )
        val first = client.readReply()
        first.status shouldBe 200
        first.header("connection") shouldBe Some("keep-alive")
        // Upper-case hex digits and leading zeros (within the digit bound) are sizes too.
        val upper = "%07X".format(initFrame.length)
        client.send(chunkedHead + s"$upper\r\n$initFrame\r\n0\r\n\r\n")
        client.readReply().status shouldBe 200
      finally client.close()
    }
  }

  test("chunk-size lines, the terminator after chunk data, and trailers must end in CRLF") {
    withServer() { port =>
      val data = s"$initFrame\r\n"
      val end = "0\r\n\r\n"
      // A chunk header ending in a bare LF, with or without a chunk extension.
      refused(port, chunkedHead + s"$initHex\n$data$end")
      refused(port, chunkedHead + s"$initHex;a=b\n$data$end")
      // Chunk data followed by a single LF, or by nothing.
      refused(port, chunkedHead + s"$initHex\r\n$initFrame\n$end")
      refused(port, chunkedHead + s"$initHex\r\n$initFrame$end")
      // The last chunk and the trailer section.
      refused(port, chunkedHead + s"$initHex\r\n${data}0\n\r\n")
      refused(port, chunkedHead + s"$initHex\r\n${data}0\r\n\n")
      refused(port, chunkedHead + s"$initHex\r\n${data}0\r\nX-Trailer: v\n\r\n")
      refused(port, chunkedHead + s"$initHex\r\n${data}0\r\nX-Trailer: v\r\n\n")
      // A CR that is not followed by LF.
      refused(port, chunkedHead + s"$initHex\r$data$end")
      refused(port, chunkedHead + s"$initHex\r\n$initFrame\r$end")
      refused(port, chunkedHead + s"$initHex\r\n${data}0\r\nX-Trailer: a\rb\r\n\r\n")
    }
  }

  test("a chunk size is hex digits from the first byte; padding and control bytes are refused") {
    withServer() { port =>
      val rest = s"\r\n$initFrame\r\n0\r\n\r\n"
      List(" ", "\t", "\u000b", "\u000c", "\u0000", "\u00a0").foreach { pad =>
        refused(port, chunkedHead + s"$pad$initHex$rest")
        refused(port, chunkedHead + s"$initHex$pad$rest")
      }
      refused(port, chunkedHead + s"0x$initHex$rest")
      refused(port, chunkedHead + s"+$initHex$rest")
      refused(port, chunkedHead + s"$rest") // no size at all
      refused(port, chunkedHead + s"0000000$initHex$rest") // past the digit bound
      // Something after the size that is not a chunk extension.
      refused(port, chunkedHead + s"$initHex junk$rest")
      refused(port, chunkedHead + s"$initHex=1$rest")
      // A chunk extension carrying control bytes.
      refused(port, chunkedHead + s"$initHex;a=\u0000b$rest")
      refused(port, chunkedHead + s"$initHex;a\rb$rest")
      refused(port, chunkedHead + s"$initHex;a=\u007f$rest")
      refused(port, chunkedHead + s"$initHex\r\n$initFrame\r\n0;\u000b\r\n\r\n")
    }
  }

  test("a chunked trailer section holds field lines only") {
    withServer() { port =>
      val body = s"$initHex\r\n$initFrame\r\n0\r\n"
      refused(port, chunkedHead + body + "not a field line\r\n\r\n")
      refused(port, chunkedHead + body + "X-Trailer: v\r\n folded\r\n\r\n")
      refused(port, chunkedHead + body + "X-Trailer: a\u0000b\r\n\r\n")
    }
  }

  // --- repeated routing headers on a 2026-07-28 POST ----------------------------------------------

  test("2026-07-28 POST: single routing headers are served as before") {
    withServer() { port =>
      val add = request(
        port,
        post(modernAddFrame, extra = List(modernVersion, callMethod, "Mcp-Name" -> "add"))
      )
      add.status shouldBe 200
      add.body should include("""{\"sum\":42}""")
      val region = request(
        port,
        post(
          modernRegionFrame,
          extra = List(modernVersion, callMethod, "Mcp-Name" -> "region", "Mcp-Param-Region" -> "eu")
        )
      )
      region.status shouldBe 200
      region.body should include("eu")
    }
  }

  test("2026-07-28 POST: a repeated Mcp-Name or Mcp-Method is a header mismatch, whatever the order") {
    withServer() { port =>
      val name = "Mcp-Name" -> "add"
      // Two different names are refused whichever of them matches the body.
      headerMismatch(port, modernAddFrame, List(modernVersion, callMethod, name, "Mcp-Name" -> "big"))
      headerMismatch(port, modernAddFrame, List(modernVersion, callMethod, "Mcp-Name" -> "big", name))
      headerMismatch(port, modernAddFrame, List(modernVersion, name, callMethod, "mcp-name" -> "big"))
      // More than one occurrence is refused even when they agree.
      headerMismatch(port, modernAddFrame, List(modernVersion, callMethod, name, name))
      headerMismatch(port, modernAddFrame, List(modernVersion, callMethod, name, "MCP-NAME" -> "add"))

      val list = "Mcp-Method" -> "tools/list"
      headerMismatch(port, modernAddFrame, List(modernVersion, callMethod, list, name))
      headerMismatch(port, modernAddFrame, List(modernVersion, list, callMethod, name))
      headerMismatch(port, modernAddFrame, List(modernVersion, callMethod, name, callMethod))
    }
  }

  test("2026-07-28 POST: a repeated Mcp-Param-* header is a header mismatch, whatever the order") {
    withServer() { port =>
      val base = List(modernVersion, callMethod, "Mcp-Name" -> "region")
      val eu = "Mcp-Param-Region" -> "eu"
      headerMismatch(port, modernRegionFrame, base ++ List(eu, "Mcp-Param-Region" -> "us"))
      headerMismatch(port, modernRegionFrame, base ++ List("Mcp-Param-Region" -> "us", eu))
      headerMismatch(port, modernRegionFrame, base ++ List(eu, "mcp-param-region" -> "eu"))
      headerMismatch(port, modernRegionFrame, ("MCP-PARAM-REGION" -> "us") :: base ++ List(eu))
      // Also for a parameter header the called tool does not declare.
      headerMismatch(
        port,
        modernAddFrame,
        List(modernVersion, callMethod, "Mcp-Name" -> "add", "Mcp-Param-X" -> "1", "Mcp-Param-X" -> "2")
      )
      // Different parameter headers are not a repeat.
      val distinct = request(
        port,
        post(modernRegionFrame, extra = base ++ List(eu, "Mcp-Param-Other" -> "x"))
      )
      distinct.status shouldBe 200
    }
  }

  test("a repeated MCP-Protocol-Version is a header mismatch, whatever the order and the body") {
    withServer() { port =>
      val routing = List(callMethod, "Mcp-Name" -> "add")
      val legacy = "Mcp-Protocol-Version" -> "2025-11-25"
      headerMismatch(port, modernAddFrame, modernVersion :: modernVersion :: routing)
      headerMismatch(port, modernAddFrame, modernVersion :: legacy :: routing)
      headerMismatch(port, modernAddFrame, legacy :: modernVersion :: routing)
      // A body without the 2026-07-28 metadata: the first value must not pick the legacy path
      // while a later one says 2026-07-28 (nor the other way round).
      headerMismatch(port, initFrame, List(legacy, modernVersion))
      headerMismatch(port, initFrame, List(modernVersion, legacy))
      headerMismatch(port, initFrame, List(legacy, legacy))
      // One legacy version header is an ordinary legacy request.
      request(port, post(initFrame, extra = List(legacy))).status shouldBe 200
    }
  }

  test("2026-07-28 notification: a repeated Mcp-Method is a header mismatch") {
    withServer() { port =>
      val cancelled =
        """{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":99}}"""
      val method = "Mcp-Method" -> "notifications/cancelled"
      request(port, post(cancelled, extra = List(modernVersion, method))).status shouldBe 202
      headerMismatch(port, cancelled, List(modernVersion, method, "Mcp-Method" -> "tools/call"))
      headerMismatch(port, cancelled, List(modernVersion, "Mcp-Method" -> "tools/call", method))
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

  test("empty lines before the request line are ignored (RFC 9112 §2.2)") {
    withServer() { port =>
      // A stray extra CRLF ahead of the request, as some proxies emit: not an empty head.
      request(port, "\r\n\r\n" + get(None)).status shouldBe 405
    }
  }

  test("header guards answer before the body is read, and the unread body closes the connection") {
    withServer(baseSettings.copy(allowedHosts = Some(Set("localhost")))) { port =>
      val client = new RawClient(port)
      // Spoofed Host with a large declared body that is never sent: the DNS-rebinding guard must
      // reject on the head alone instead of blocking on (or buffering) the body first.
      client.send(
        "POST /mcp HTTP/1.1\r\nHost: evil.example\r\nContent-Type: application/json\r\n" +
          "Accept: application/json, text/event-stream\r\nContent-Length: 100000\r\n\r\n"
      )
      val reply = client.readReply()
      reply.status shouldBe 403
      reply.header("connection") shouldBe Some("close")
      client.atEof() shouldBe true
      client.close()
    }
  }

  test("a chunked body over the cap is rejected with 413 as soon as the chunk size says so") {
    withServer() { port =>
      val client = new RawClient(port)
      client.send(
        "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n" +
          "Accept: application/json, text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n" +
          // maxRequestBodyBytes + 1, no data follows
          s"${Integer.toHexString(baseSettings.maxRequestBodyBytes + 1)}\r\n"
      )
      val reply = client.readReply()
      reply.status shouldBe 413
      reply.header("connection") shouldBe Some("close")
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

  test("default settings on a loopback bind refuse a non-loopback Host and a foreign Origin (403)") {
    // No allow-list configured: the guard is automatic for a loopback listener.
    withServer() { port =>
      request(port, post(initFrame, host = "evil.example")).status shouldBe 403
      request(port, post(initFrame, host = s"evil.example:$port")).status shouldBe 403
      val foreignOrigin = List("Origin" -> "http://evil.example")
      request(
        port,
        post(initFrame, host = s"127.0.0.1:$port", extra = foreignOrigin)
      ).status shouldBe 403
      request(port, get(None, extra = foreignOrigin)).status shouldBe 403
      // Loopback names, on the bound port or a forwarded one, and a same-origin page pass.
      request(port, post(initFrame, host = s"127.0.0.1:$port")).status shouldBe 200
      request(port, post(initFrame, host = "localhost:1234")).status shouldBe 200
      val sameOrigin = List("Origin" -> s"http://localhost:$port")
      request(
        port,
        post(initFrame, host = s"localhost:$port", extra = sameOrigin)
      ).status shouldBe 200
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

  test("stray bytes on an open GET stream do not blind the disconnect watch") {
    withServer() { port =>
      val sid = initSession(port)
      val stream = new RawClient(port)
      stream.send(get(Some(sid)))
      stream.readHead()._1 shouldBe 200
      // Unsolicited bytes on the stream's connection (not a request), then vanish. The watcher
      // must keep watching past them: otherwise the connection fiber parks forever, holding a
      // connection permit and the session's GET slot, and the session can never be evicted.
      stream.send("\r\n")
      Thread.sleep(200)
      stream.abort()

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

  test("a request pipelined behind an open GET stream is served once the stream ends") {
    withServer() { port =>
      val sid = initSession(port)
      val stream = new RawClient(port)
      stream.send(get(Some(sid)))
      stream.readHead()._1 shouldBe 200
      // Pipeline the next request on the same connection while the stream is open ...
      stream.send(post(initFrame))
      // ... end the stream from elsewhere ...
      request(port, delete(sid)).status shouldBe 200
      stream.readChunk() shouldBe None
      // ... and the buffered request is answered on the same connection.
      val next = stream.readReply()
      next.status shouldBe 200
      next.header("mcp-session-id").isDefined shouldBe true
      stream.close()
    }
  }

  test("a client that stops reading a reply is disconnected, which frees its connection slot") {
    // One slot only: while the stalled client holds it, nobody else can be served.
    val backend = new SocketHttpBackend(
      summon[TransportBackend],
      maxConnections = 1,
      writeStallTimeout = 500.millis
    )
    withServer(baseSettings.copy(stateless = true), backend) { port =>
      val stalled = new RawClient(port, readTimeoutMs = 30_000)
      val bigFrame =
        """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"big","arguments":{"megabytes":8}}}"""
      // Ask for 8 MiB and never read a byte of it: the server's write blocks once the socket
      // buffers fill, and only the write-stall sweep can end it.
      stalled.send(post(bigFrame, accept = "application/json"))

      // Queued in the backlog behind the stalled connection; served once the sweep closes it.
      val next = new RawClient(port, readTimeoutMs = 20_000)
      next.send(post(addFrame, accept = "application/json"))
      val reply = next.readReply()
      reply.status shouldBe 200
      reply.body should include(""""id":3""")
      reply.body should include("sum")
      next.close()
      stalled.close()
    }
  }

  test("a slow client that keeps reading gets its whole large reply, however long it takes") {
    // The write-stall clock measures time without progress, not the length of the transfer: this
    // download takes about three times the stall timeout, but bytes keep flowing. (The timeout
    // is 2 s rather than something tighter because progress on the wire is lumpy: a receiver
    // only reopens its TCP window after it has drained a good part of its buffer.)
    val backend = new SocketHttpBackend(summon[TransportBackend], writeStallTimeout = 2.seconds)
    withServer(baseSettings.copy(stateless = true), backend) { port =>
      val client = new RawClient(port, readTimeoutMs = 60_000)
      val bigFrame =
        """{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"big","arguments":{"megabytes":8}}}"""
      client.send(post(bigFrame, accept = "application/json"))
      val (status, headers) = client.readHead()
      status shouldBe 200
      val total = headers.getOrElse("content-length", fail("no content-length")).toInt
      total should be > 8 * 1024 * 1024
      val slice = 128 * 1024
      var received = 0
      while received < total do
        val n = math.min(slice, total - received)
        client.readExactly(n).length shouldBe n
        received += n
        Thread.sleep(100) // 64+ slices x 100 ms: over 6 s, three times the stall timeout
      received shouldBe total
      client.close()
    }
  }

  test("a body over the cap that the client is still sending gets its 413, not a reset") {
    withServer() { port =>
      val client = new RawClient(port)
      val declared = baseSettings.maxRequestBodyBytes + 512 * 1024
      // No `Expect: 100-continue`: the client streams the whole body regardless. The server
      // refuses on the head, then must keep reading (a lingering close) so its reply survives.
      client.send(
        "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n" +
          s"Accept: application/json, text/event-stream\r\nContent-Length: $declared\r\n\r\n"
      )
      val chunk = "x" * (64 * 1024)
      var sent = 0
      while sent < declared do
        client.send(chunk)
        sent += chunk.length
      val reply = client.readReply()
      reply.status shouldBe 413
      reply.header("connection") shouldBe Some("close")
      client.close()
    }
  }

  test("a client that sends only blank lines cannot outlast the request-read deadline") {
    val backend =
      new SocketHttpBackend(summon[TransportBackend], requestReadTimeout = 400.millis)
    withServer(backend = backend) { port =>
      val client = new RawClient(port)
      // Each CRLF is ignorable and arrives well inside the per-read timeout; without a clock on
      // blank lines this connection would hold its slot for as long as the client cared to.
      var i = 0
      while i < 6 do
        try client.send("\r\n")
        catch case _: java.io.IOException => () // already closed by the server: what we want
        Thread.sleep(150)
        i += 1
      val reply = client.readReply()
      reply.status shouldBe 408
      client.atEof() shouldBe true
      client.close()
    }
  }

  test("a failed hand-off to a connection thread is retried; the listener survives it") {
    // What a JVM at its thread limit does: starting the connection's thread throws
    // `OutOfMemoryError: unable to create native thread` out of the executor. That must cost the
    // one connection, not the listener.
    val failuresLeft = new java.util.concurrent.atomic.AtomicInteger(3)
    val injected = new java.util.concurrent.atomic.AtomicInteger(0)
    val real = runUnsafe(ZIO.blockingExecutor)
    val failingForConnections = new Executor:
      def metrics(implicit unsafe: Unsafe): Option[zio.internal.ExecutionMetrics] = None
      def submit(runnable: Runnable)(implicit unsafe: Unsafe): Boolean =
        if runnable.getClass.getName.contains("SocketHttpServer$Connection") &&
          failuresLeft.getAndDecrement() > 0
        then
          val _ = injected.incrementAndGet()
          throw new OutOfMemoryError("unable to create native thread: test")
        else real.submit(runnable)
    withServer(blockingExecutor = Some(failingForConnections)) { port =>
      // The first three connections are accepted and then dropped; a later one is served. The
      // accept loop dying would fail this test at once (`withServer` races the body against it).
      def attempt(): Option[Int] =
        val client = new RawClient(port, readTimeoutMs = 5_000)
        try
          client.send(post(initFrame))
          Some(client.readReply().status)
        catch case _: Throwable => None
        finally client.close()
      val outcomes = (1 to 8).map(_ => attempt())
      injected.get shouldBe 3
      outcomes.count(_.isEmpty) shouldBe 3
      outcomes.drop(3).foreach(_ shouldBe Some(200))
    }
  }

  test("filler bytes cannot extend the request-read deadline past its ceiling") {
    // Blank lines at ~80 KiB/s earn time like any other bytes, but the credit is capped at what a
    // full head plus a full body could earn: here 0.4 s + (8 KiB + 4 KiB) / 4 KiB/s = 3.4 s.
    val backend =
      new SocketHttpBackend(summon[TransportBackend], requestReadTimeout = 400.millis)
    withServer(baseSettings.copy(maxRequestBodyBytes = 4 * 1024), backend) { port =>
      val client = new RawClient(port, readTimeoutMs = 40_000)
      val filler = "\r\n" * (4 * 1024)
      val started = java.lang.System.nanoTime()
      var open = true
      while open && (java.lang.System.nanoTime() - started) < 25_000_000_000L do
        try
          client.send(filler)
          Thread.sleep(100)
        catch case _: java.io.IOException => open = false // closed by the server: the point
      val elapsedMs = (java.lang.System.nanoTime() - started) / 1_000_000L
      open shouldBe false
      // 3.4 s of deadline, then up to 5 s of lingering close while the filler keeps coming.
      elapsedMs should be < 18_000L
      client.close()
    }
  }

  test("a slow but steady upload outlives the request-read allowance") {
    // 48 KiB in 8 KiB pieces 150 ms apart: about 0.9 s in all, more than twice the 400 ms
    // allowance, but at ~53 KiB/s — far above the 4 KiB/s floor — every piece extends the deadline.
    val backend =
      new SocketHttpBackend(summon[TransportBackend], requestReadTimeout = 400.millis)
    withServer(baseSettings.copy(stateless = true), backend) { port =>
      val client = new RawClient(port)
      val pad = "x" * (48 * 1024 - 100)
      val frame =
        s"""{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"add","arguments":{"a":2,"b":40,"pad":"$pad"}}}"""
      val bytes = frame.getBytes(StandardCharsets.UTF_8)
      client.send(
        "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n" +
          s"Accept: application/json\r\nContent-Length: ${bytes.length}\r\n\r\n"
      )
      var offset = 0
      while offset < frame.length do
        val end = math.min(frame.length, offset + 8 * 1024)
        client.send(frame.substring(offset, end))
        offset = end
        Thread.sleep(150)
      val reply = client.readReply()
      reply.status shouldBe 200
      reply.body should include(""""id":9""")
      client.close()
    }
  }

  test("a request dribbled in past the request-read deadline is answered 408") {
    val backend =
      new SocketHttpBackend(summon[TransportBackend], requestReadTimeout = 400.millis)
    withServer(backend = backend) { port =>
      val client = new RawClient(port)
      // Each fragment arrives well inside the 60 s per-read timeout; only the whole-request
      // deadline can stop a client that never finishes its head.
      client.send("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\n")
      Thread.sleep(250)
      client.send("X-Slow-A: 1\r\n")
      Thread.sleep(250)
      client.send("X-Slow-B: 2\r\n")
      val reply = client.readReply()
      reply.status shouldBe 408
      reply.header("connection") shouldBe Some("close")
      client.atEof() shouldBe true
      client.close()
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
