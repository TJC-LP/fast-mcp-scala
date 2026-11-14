package com.tjclp.fastmcp
package server.transport

import java.io.{BufferedReader, InputStream, InputStreamReader}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Paths
import java.util.concurrent.{CopyOnWriteArrayList, TimeUnit}

import scala.jdk.CollectionConverters.*

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.testkit.Await

/** The stdio lifecycle contract observed from outside a real JVM stdio server (GH #113): replies
  * owed at stdin EOF, the final frame without a newline, and SIGTERM / SIGINT while stdin is still
  * open — with nothing but JSON-RPC frames on stdout throughout. The in-process contract tests are
  * `StdioLifecycleContractTest`; this is the part that needs a process: its exit.
  */
class StdioProcessLifecycleTest extends AnyFunSuite with Matchers:

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"lifecycle","version":"0"}}}"""
  private val initializedFrame = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""

  private def call(id: Int, tool: String, arguments: String = "{}"): String =
    s"""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}"""

  /** A signal must end the server within this long. Before the fix it never did. */
  private val ExitBudgetSeconds = 10L

  private final class Server:
    private val javaBin = Paths.get(sys.props("java.home"), "bin", "java").toString
    private val process = new ProcessBuilder(
      javaBin,
      "-XX:TieredStopAtLevel=1",
      "-cp",
      sys.props("java.class.path"),
      "com.tjclp.fastmcp.server.transport.LifecycleStdioApp"
    ).start()
    private val stdin = process.getOutputStream
    val stdout = new CopyOnWriteArrayList[String]()
    val stderr = new CopyOnWriteArrayList[String]()

    private def drain(in: InputStream, into: CopyOnWriteArrayList[String]): Thread =
      val t = new Thread(() =>
        val reader = new BufferedReader(new InputStreamReader(in, UTF_8), 1 << 16)
        Iterator.continually(reader.readLine()).takeWhile(_ != null).foreach(into.add)
      )
      t.setDaemon(true)
      t.start()
      t

    private val outThread = drain(process.getInputStream, stdout)
    private val errThread = drain(process.getErrorStream, stderr)

    /** Written exactly as given: the caller decides where the newlines are. */
    def write(text: String): Unit =
      stdin.write(text.getBytes(UTF_8))
      stdin.flush()

    def closeStdin(): Unit = stdin.close()

    /** Wait for a line to arrive (a condition, not an ordering: nothing is assumed to have
      * happened merely because time passed).
      */
    def await(lines: CopyOnWriteArrayList[String], what: String)(found: String => Boolean): Unit =
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
      Await.holds(s"$what, the deadline, or the server's exit", 120_000L) {
        lines.asScala.exists(found) || System.nanoTime() >= deadline || !process.isAlive
      }
      assert(lines.asScala.exists(found), s"$what never arrived; stderr:\n${stderr.asScala.mkString("\n")}")

    def signal(name: String): Unit =
      val kill = new ProcessBuilder("kill", s"-$name", process.pid().toString).inheritIO().start()
      assert(kill.waitFor(10, TimeUnit.SECONDS) && kill.exitValue() == 0, s"kill -$name failed")

    /** Seconds until the process ended, or `None` when it outlived `seconds` (it is then killed). */
    def exitWithin(seconds: Long): Option[Double] =
      val start = System.nanoTime()
      val exited = process.waitFor(seconds, TimeUnit.SECONDS)
      val elapsed = (System.nanoTime() - start) / 1e9
      if !exited then
        val _ = process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
      outThread.join(5000)
      errThread.join(5000)
      Option.when(exited)(elapsed)

    def exitCode: Int = process.exitValue()

    def destroy(): Unit =
      if process.isAlive then
        val _ = process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)

  private def withServer[A](body: Server => A): A =
    val server = new Server
    try body(server)
    finally server.destroy()

  /** The numeric id of a line that is one JSON-RPC response; `None` for any other frame. */
  private def responseId(line: String): Option[Int] =
    line.fromJson[Json].toOption.collect { case Json.Obj(fields) => fields.toMap }.flatMap { fields =>
      Option
        .when(fields.contains("result") || fields.contains("error"))(fields.get("id"))
        .flatten
        .collect { case Json.Num(n) => n.intValue }
    }

  private def isFrame(line: String): Boolean =
    line.fromJson[Json] match
      case Right(Json.Obj(fields)) =>
        fields.exists(_._1 == "jsonrpc") && line.trim.startsWith("{") && line.trim.endsWith("}")
      case _ => false

  private def assertOnlyFrames(server: Server): Unit =
    val stray = server.stdout.asScala.toList.filter(_.trim.nonEmpty).filterNot(isFrame)
    withClue(s"lines on stdout (the wire) that are not JSON-RPC frames:\n${stray.mkString("\n")}\n") {
      stray shouldBe empty
    }

  test("a burst followed at once by EOF: every frame is answered, a slow handler included") {
    withServer { server =>
      val adds = (2 to 6).toList
      val frames = List(initFrame, initializedFrame) ++
        adds.map(id => call(id, "add", s"""{"a":$id,"b":1}""")) :+ call(7, "slow")
      server.write(frames.map(_ + "\n").mkString)
      server.closeStdin()
      val elapsed = server.exitWithin(60).getOrElse(fail("the server did not exit after stdin EOF"))
      val answered = server.stdout.asScala.toList.flatMap(responseId).toSet
      withClue(s"exit after $elapsed s; stdout:\n${server.stdout.asScala.mkString("\n")}\n") {
        answered shouldBe (1 :: adds).toSet + 7
        server.exitCode shouldBe 0
      }
      server.stdout.asScala.exists(_.contains("slow done")) shouldBe true
      assertOnlyFrames(server)
    }
  }

  test("a final frame without a newline, then EOF: it is answered") {
    withServer { server =>
      server.write(initFrame + "\n" + initializedFrame + "\n" + call(2, "add", """{"a":20,"b":22}"""))
      server.closeStdin()
      val _ = server.exitWithin(60).getOrElse(fail("the server did not exit after stdin EOF"))
      val reply = server.stdout.asScala.find(responseId(_).contains(2))
      reply.getOrElse(fail(s"the unterminated frame was not answered: ${server.stdout}")) should
        include("42")
      server.exitCode shouldBe 0
      assertOnlyFrames(server)
    }
  }

  private def exitsOnSignal(name: String, callInFlight: Boolean): Unit =
    withServer { server =>
      server.write(initFrame + "\n")
      server.await(server.stdout, "the initialize reply")(responseId(_).contains(1))
      if callInFlight then
        server.write(initializedFrame + "\n" + call(2, "hang") + "\n")
        server.await(server.stderr, "the hanging handler's log line")(_.contains("hang started"))
      // stdin is still open: the server has to act on the signal itself.
      server.signal(name)
      val elapsed = server.exitWithin(ExitBudgetSeconds)
      withClue(s"SIG$name with stdin open: the server was still alive after $ExitBudgetSeconds s. ") {
        elapsed should not be empty
      }
      info(f"exit ${elapsed.getOrElse(0.0)}%.2f s after SIG$name")
      assertOnlyFrames(server)
    }

  test("SIGTERM while stdin is open ends the server") {
    exitsOnSignal("TERM", callInFlight = false)
  }

  test("SIGINT while stdin is open ends the server") {
    exitsOnSignal("INT", callInFlight = false)
  }

  test("SIGTERM while stdin is open and a handler is running ends the server") {
    exitsOnSignal("TERM", callInFlight = true)
  }
