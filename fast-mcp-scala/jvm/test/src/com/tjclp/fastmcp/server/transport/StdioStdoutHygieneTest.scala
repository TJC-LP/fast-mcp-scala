package com.tjclp.fastmcp
package server.transport

import java.io.{BufferedReader, InputStream, InputStreamReader, OutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Paths
import java.util.concurrent.{CopyOnWriteArrayList, TimeUnit}
import java.util.concurrent.atomic.AtomicLong

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.*
import zio.json.ast.Json

/** stdout hygiene of the stdio transport: on stdio, `System.out` IS the wire, so
  *
  *   1. nothing but JSON-RPC frames may ever be written to it — in particular ZIO log lines from
  *      handlers (`ZIO.logInfo` in a tool) must land on stderr, whether the server is an
  *      `McpServerApp[Stdio]` or a plain `runStdio()` call, while a user-installed logger stays the
  *      only logger; and
  *   2. a frame must be written in ONE `PrintStream` call, so a foreign `System.out` writer
  *      (a stray `println` from user code) can only add a stray line between frames and never split
  *      one — the D2 dogfooding stress lost one reply per 200 calls to exactly that splice.
  *
  * The servers run as real subprocesses (see [[LoggingStdioApp]] and friends) driven the way the
  * D2 `drive.py` / `stress.py` hygiene pipe drives them: frames in, stdin held open until the
  * replies arrive, then EOF.
  */
class StdioStdoutHygieneTest extends AnyFunSuite with Matchers:

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"hygiene","version":"0"}}}"""
  private val initializedFrame = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""

  private def callFrame(id: Int) =
    s"""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"add","arguments":{"a":$id,"b":1}}}"""

  /** `true` when `line` is exactly ONE JSON object and nothing else. zio-json's `fromJson` stops
    * at the end of the first value and ignores whatever follows, so on its own it would accept a
    * spliced `{...}noise` as a healthy frame — precisely the corruption this suite must catch.
    */
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private def isSingleJsonObject(line: String): Boolean =
    val s = line.trim
    var depth = 0
    var inString = false
    var escaped = false
    var end = -1
    var i = 0
    while i < s.length && end < 0 do
      val c = s.charAt(i)
      if inString then
        if escaped then escaped = false
        else if c == '\\' then escaped = true
        else if c == '"' then inString = false
      else
        c match
          case '"' => inString = true
          case '{' | '[' => depth += 1
          case '}' | ']' =>
            depth -= 1
            if depth == 0 then end = i
          case _ => ()
      i += 1
    s.startsWith("{") && end == s.length - 1

  /** `Some(id)` when `line` is exactly one JSON object carrying `"jsonrpc"` (numeric id if any). */
  private def parseFrame(line: String): Option[Option[Int]] =
    if !isSingleJsonObject(line) then None
    else
      line.fromJson[Json] match
        case Right(Json.Obj(fields)) if fields.exists(_._1 == "jsonrpc") =>
          Some(fields.collectFirst { case ("id", Json.Num(n)) => n.intValue })
        case _ => None

  /** A running fixture process with its stdout/stderr drained on background threads. */
  private final class Server(mainClass: String, ignoreStdout: String => Boolean):
    private val javaBin = Paths.get(sys.props("java.home"), "bin", "java").toString
    private val process = new ProcessBuilder(
      javaBin,
      "-XX:TieredStopAtLevel=1",
      "-cp",
      sys.props("java.class.path"),
      mainClass
    ).start()
    private val stdin = process.getOutputStream
    val stdoutLines = new CopyOnWriteArrayList[String]()
    val stderrLines = new CopyOnWriteArrayList[String]()
    private val ids = mutable.Set.empty[Int]
    private val ignored = new AtomicLong()

    private def drain(in: InputStream)(onLine: String => Unit): Thread =
      val t = new Thread(() =>
        val reader = new BufferedReader(new InputStreamReader(in, UTF_8), 1 << 16)
        Iterator.continually(reader.readLine()).takeWhile(_ != null).foreach(onLine)
      )
      t.setDaemon(true)
      t.start()
      t

    private val outThread = drain(process.getInputStream) { line =>
      if ignoreStdout(line) then { val _ = ignored.incrementAndGet() }
      else
        val _ = stdoutLines.add(line)
        parseFrame(line).flatten.foreach(id => ids.synchronized { ids += id })
    }
    private val errThread = drain(process.getErrorStream) { line =>
      val _ = stderrLines.add(line)
    }

    def ignoredStdoutLines: Long = ignored.get()

    def send(frames: String*): Unit =
      stdin.write(frames.map(_ + "\n").mkString.getBytes(UTF_8))
      stdin.flush()

    def answeredIds: Set[Int] = ids.synchronized(ids.toSet)

    /** Poll until every id in `expected` has been answered, or `timeout` elapses. */
    def awaitIds(expected: Set[Int], timeout: Duration): Boolean =
      val deadline = java.lang.System.nanoTime() + timeout.toNanos
      while !expected.subsetOf(answeredIds) && java.lang.System.nanoTime() < deadline &&
        process.isAlive
      do Thread.sleep(20)
      expected.subsetOf(answeredIds)

    /** Hold stdin open a moment (as a real host would), then EOF and wait for exit. */
    def close(): Int =
      Thread.sleep(300)
      stdin.close()
      if !process.waitFor(15, TimeUnit.SECONDS) then
        val _ = process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
      outThread.join(5000)
      errThread.join(5000)
      process.exitValue()

  private def withServer[A](mainClass: String, ignoreStdout: String => Boolean = _ => false)(
      body: Server => A
  ): A =
    val server = new Server(mainClass, ignoreStdout)
    try body(server)
    finally
      val _ = server.close()

  private def describe(lines: Iterable[String]): String =
    lines.map(l => "  " + l.take(200)).mkString("\n")

  /** initialize → initialized → one `add`; every non-empty stdout line must be a JSON-RPC frame
    * and the tool's log line must be on stderr.
    */
  private def assertLogsGoToStderr(mainClass: String): Unit =
    withServer(mainClass) { server =>
      server.send(initFrame)
      assert(server.awaitIds(Set(1), 30.seconds), "no initialize reply within 30s")
      server.send(initializedFrame, callFrame(2))
      assert(server.awaitIds(Set(2), 30.seconds), "no tools/call reply within 30s")
      // Give a late (mis-routed) log line the chance to show up before grading.
      Thread.sleep(200)
      val stdout = server.stdoutLines.asScala.toList
      val stray = stdout.filter(_.trim.nonEmpty).filter(parseFrame(_).isEmpty)
      withClue(s"non-JSON-RPC lines on stdout (the wire):\n${describe(stray)}\n") {
        stray shouldBe empty
      }
      server.answeredIds should contain allOf (1, 2)
      val stderr = server.stderrLines.asScala.toList
      withClue(s"tool log line missing from stderr; stderr was:\n${describe(stderr)}\n") {
        stderr.exists(_.contains("message=\"adding\"")) shouldBe true
      }
    }

  test("McpServerApp[Stdio]: a tool's ZIO.logInfo lands on stderr, never on stdout") {
    assertLogsGoToStderr("com.tjclp.fastmcp.server.transport.LoggingStdioApp")
  }

  test("plain runStdio(): a tool's ZIO.logInfo lands on stderr, never on stdout") {
    assertLogsGoToStderr("com.tjclp.fastmcp.server.transport.PlainRunStdioApp")
  }

  test("an overridden bootstrap logger is the only logger (no clobbering, no duplicate)") {
    withServer("com.tjclp.fastmcp.server.transport.CustomLoggerStdioApp") { server =>
      server.send(initFrame)
      assert(server.awaitIds(Set(1), 30.seconds), "no initialize reply within 30s")
      server.send(initializedFrame, callFrame(2))
      assert(server.awaitIds(Set(2), 30.seconds), "no tools/call reply within 30s")
      Thread.sleep(200)
      val stray = server.stdoutLines.asScala.toList.filter(_.trim.nonEmpty).filter(parseFrame(_).isEmpty)
      withClue(s"non-JSON-RPC lines on stdout:\n${describe(stray)}\n")(stray shouldBe empty)
      val adding = server.stderrLines.asScala.toList.filter(_.contains("message=\"adding\""))
      withClue(s"expected exactly one CUSTOM-prefixed log line; stderr log lines:\n${describe(adding)}\n") {
        adding.size shouldBe 1
        adding.head should startWith("CUSTOM ")
      }
    }
  }

  /** A WHOLE stray line on stdout: the fixture's `noise` println, or a pure ZIO log line (the
    * pre-fix default logger). Tolerated here — this test is about frame integrity, the logger has
    * its own tests above — whereas a spliced `{...}noise` / `{...}timestamp=...` is neither a
    * frame nor a whole stray line and is graded as corruption.
    */
  private def isWholeStrayLine(line: String): Boolean =
    line == NoisyStdioApp.Noise || (line.startsWith("timestamp=") && !line.contains("{"))

  test("200 concurrent tools/call under System.out println spam lose no reply") {
    val calls = (2 to 201).toList
    val expected = (1 :: calls).toSet
    withServer(
      "com.tjclp.fastmcp.server.transport.NoisyStdioApp",
      ignoreStdout = isWholeStrayLine
    ) { server =>
      // The whole burst at once, exactly like the D2 `stress.py` pipe: initialize, initialized,
      // then 200 concurrent calls; stdin stays open until every reply is in (or the deadline).
      server.send((initFrame :: initializedFrame :: calls.map(callFrame))*)
      val complete = server.awaitIds(expected, 45.seconds)
      Thread.sleep(200)
      val missing = expected -- server.answeredIds
      val stdout = server.stdoutLines.asScala.toList
      // A split frame shows up as `{...}noise` (unparseable, so its id is missing) plus the empty
      // line its orphaned newline leaves behind.
      val corrupted = stdout.filter(_.trim.nonEmpty).filter(parseFrame(_).isEmpty)
      withClue(
        s"missing replies for ids ${missing.toList.sorted} (${server.ignoredStdoutLines} whole stray " +
          s"lines ignored); corrupted stdout lines:\n${describe(corrupted)}\n"
      ) {
        corrupted shouldBe empty
        missing shouldBe empty
        complete shouldBe true
      }
    }
  }

  test("writeLine hands a frame and its newline to System.out in ONE write") {
    val frame = """{"jsonrpc":"2.0","id":7,"result":{}}"""
    val chunks = new CopyOnWriteArrayList[String]()
    val sink = new OutputStream:
      override def write(b: Int): Unit = { val _ = chunks.add(new String(Array(b.toByte), UTF_8)) }
      override def write(b: Array[Byte], off: Int, len: Int): Unit = {
        val _ = chunks.add(new String(b, off, len, UTF_8))
      }
    val original = java.lang.System.out
    java.lang.System.setOut(new PrintStream(sink, false, "UTF-8"))
    try runUnsafe(StdioLoop.writeLine(frame))
    finally java.lang.System.setOut(original)
    // Two chunks (`{...}` then `\n`) is the splice window a foreign println can land in.
    chunks.asScala.toList shouldBe List(frame + "\n")
  }
