package com.tjclp.fastmcp
package server.transport

import java.io.{BufferedInputStream, ByteArrayOutputStream, InputStream}
import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.http.{Server as ZServer}

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.RegistrationMacro.*
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.transport.JvmTransportBackend.given

/** The zio-http backend on a real port, driven by a raw socket: a routing header sent as two field
  * lines must reach the shared repeated-header check as two lines. `JvmHttpTransportTest` covers
  * the same rule in memory, where the header list never passes through netty's decoder; this suite
  * pins that the decoder does not merge or drop a repeat before the check sees it.
  */
class JvmHttpRepeatedHeaderWireTest extends AnyFunSuite with Matchers:

  object WireServer:
    @Tool(name = Some("add"), description = Some("Add two numbers"))
    def add(@Param("a") a: Int, @Param("b") b: Int): Int = a + b

  private val callFrame =
    """{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"add","arguments":{"a":40,"b":2},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}"""

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private def withServer[A](body: Int => A): A =
    val server = McpServer.typed[Any]("W", "0.1.0", McpServerSettings())
    val _ = server.scanAnnotations[WireServer.type]
    runUnsafe(
      ZIO
        .scoped {
          for
            router <- server.buildRouter
            routes <- JvmHttpBackend.httpRoutes(router, server.settings, ZEnvironment.empty)
            port <- ZServer.install(routes)
            result <- ZIO.attemptBlocking(body(port))
          yield result
        }
        .provide(ZServer.defaultWithPort(0))
        .timeoutFail(new RuntimeException("zio-http wire test timed out"))(60.seconds)
    )

  private def readLine(in: InputStream): String =
    val buf = new ByteArrayOutputStream()
    var byte = in.read()
    while byte != -1 && byte != '\n' do
      buf.write(byte)
      byte = in.read()
    buf.toString("ISO-8859-1").stripSuffix("\r")

  /** POST `body` with exactly `headers` as field lines, in order; the status and the reply body. */
  private def post(port: Int, body: String, headers: List[(String, String)]): (Int, String) =
    val socket = new Socket()
    try
      socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000)
      socket.setSoTimeout(10_000)
      val lines = List(
        "Host" -> s"127.0.0.1:$port",
        "Content-Type" -> "application/json",
        "Accept" -> "application/json, text/event-stream"
      ) ++ headers :+ ("Content-Length" -> body.getBytes(StandardCharsets.UTF_8).length.toString)
      val head = lines.map { case (name, value) => s"$name: $value\r\n" }.mkString
      socket.getOutputStream.write(
        s"POST /mcp HTTP/1.1\r\n$head\r\n$body".getBytes(StandardCharsets.UTF_8)
      )
      socket.getOutputStream.flush()
      val in = new BufferedInputStream(socket.getInputStream)
      val status = readLine(in).split(' ')(1).toInt
      val replyHeaders = Iterator
        .continually(readLine(in))
        .takeWhile(_.nonEmpty)
        .map { line =>
          val i = line.indexOf(':')
          line.substring(0, i).trim.toLowerCase -> line.substring(i + 1).trim
        }
        .toMap
      val length = replyHeaders.get("content-length").map(_.toInt).getOrElse(0)
      (status, new String(in.readNBytes(length), StandardCharsets.UTF_8))
    finally socket.close()

  private val version = "MCP-Protocol-Version" -> "2026-07-28"
  private val method = "Mcp-Method" -> "tools/call"
  private val name = "Mcp-Name" -> "add"

  test("one field line per routing header is served") {
    withServer { port =>
      val (status, body) = post(port, callFrame, List(version, method, name))
      status shouldBe 200
      body should include("42")
    }
  }

  test("a routing header sent as two field lines is a header mismatch, whatever the order") {
    withServer { port =>
      val legacy = "MCP-Protocol-Version" -> "2025-11-25"
      val repeats = List(
        List(version, method, name, "Mcp-Name" -> "slow"),
        List(version, method, "Mcp-Name" -> "slow", name),
        List(version, method, name, "mcp-name" -> "add"),
        List(version, method, "Mcp-Method" -> "tools/list", name),
        List(version, "Mcp-Method" -> "tools/list", method, name),
        List(version, method, name, "Mcp-Param-X" -> "1", "mcp-param-x" -> "2"),
        List(version, version, method, name),
        List(version, legacy, method, name),
        List(legacy, version, method, name)
      )
      repeats.foreach { headers =>
        withClue(s"headers $headers: ") {
          val (status, body) = post(port, callFrame, headers)
          status shouldBe 400
          body should include(""""code":-32020""")
        }
      }
      // A legacy-looking body: the first version value must not choose the legacy path.
      List(List(legacy, version), List(version, legacy), List(legacy, legacy)).foreach { headers =>
        withClue(s"headers $headers: ") {
          val (status, body) = post(port, initFrame, headers)
          status shouldBe 400
          body should include(""""code":-32020""")
        }
      }
      post(port, initFrame, List(legacy))._1 shouldBe 200
    }
  }
