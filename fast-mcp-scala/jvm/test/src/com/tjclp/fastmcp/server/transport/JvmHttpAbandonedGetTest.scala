package com.tjclp.fastmcp
package server.transport

import java.net.{InetSocketAddress, ServerSocket, Socket}
import java.nio.charset.StandardCharsets

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*

import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.transport.JvmTransportBackend.given
import com.tjclp.fastmcp.server.transport.http.StreamableHttpHandler

/** The legacy GET slot over a real zio-http (netty) listener: a client that closes its GET
  * connection as soon as it has read the response head must not leave the session answering 409 to
  * every later GET. In-memory route tests cannot show this — whether the reply's stream is ever
  * started is decided inside the server.
  */
class JvmHttpAbandonedGetTest extends AnyFunSuite with Matchers:

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private def freePort(): Int =
    val probe = new ServerSocket(0)
    try probe.getLocalPort
    finally probe.close()

  private def connect(port: Int): Socket =
    val socket = new Socket()
    socket.connect(new InetSocketAddress("127.0.0.1", port), 5000)
    socket.setSoTimeout(10000)
    socket

  /** Read up to and including the blank line that ends the response head. */
  private def readHead(socket: Socket): String =
    val in = socket.getInputStream
    val sb = new java.lang.StringBuilder
    var done = false
    while !done do
      val b = in.read()
      if b < 0 then done = true
      else
        sb.append(b.toChar)
        if sb.length >= 4 && sb.substring(sb.length - 4) == "\r\n\r\n" then done = true
    sb.toString

  private def statusOf(head: String): Int =
    head.takeWhile(_ != '\r').split(' ').lift(1).flatMap(_.toIntOption).getOrElse(-1)

  private def send(socket: Socket, raw: String): Unit =
    socket.getOutputStream.write(raw.getBytes(StandardCharsets.UTF_8))
    socket.getOutputStream.flush()

  private def initialize(port: Int): String =
    val socket = connect(port)
    try
      send(
        socket,
        s"POST /mcp HTTP/1.1\r\nhost: 127.0.0.1:$port\r\ncontent-type: application/json\r\n" +
          s"accept: application/json, text/event-stream\r\nconnection: close\r\n" +
          s"content-length: ${initFrame.length}\r\n\r\n$initFrame"
      )
      val head = readHead(socket)
      withClue(head)(statusOf(head) shouldBe 200)
      head.linesIterator
        .map(_.split(":", 2))
        .collectFirst {
          case Array(name, value) if name.trim.equalsIgnoreCase("mcp-session-id") => value.trim
        }
        .getOrElse(fail(s"initialize minted no session: $head"))
    finally socket.close()

  /** Open a GET for the session and return the socket once the response head has been read. */
  private def openGet(port: Int, sid: String): (Socket, Int) =
    val socket = connect(port)
    send(
      socket,
      s"GET /mcp HTTP/1.1\r\nhost: 127.0.0.1:$port\r\naccept: text/event-stream\r\n" +
        s"mcp-session-id: $sid\r\n\r\n"
    )
    (socket, statusOf(readHead(socket)))

  private def awaitListening(port: Int): Unit =
    val deadline = java.lang.System.nanoTime() + 20_000_000_000L
    var up = false
    while !up && java.lang.System.nanoTime() < deadline do
      try
        connect(port).close()
        up = true
      catch case _: java.io.IOException => Thread.sleep(50)
    if !up then fail(s"the server never listened on port $port")

  test("a GET closed right after its response head frees the session's GET slot") {
    val port = freePort()
    val settings = McpServerSettings(host = "127.0.0.1", port = port)
    val server = McpServer.typed[Any]("AbandonedGet", "0.1.0", settings)
    val listener = runUnsafe(
      server.buildRouter.flatMap(router => JvmHttpBackend.serveHttp(router, settings).forkDaemon)
    )
    try
      awaitListening(port)
      val sid = initialize(port)
      // Each round opens the session's GET stream and closes the connection the moment the
      // response head has been read. The next round can only get its 200 once the server has
      // given the slot back, so every round after the first proves the one before it was
      // released — whether the close landed before the server started the reply's stream (the
      // first, cold round always does) or after.
      val perRoundMs = StreamableHttpHandler.UnstartedReplyGrace.toMillis + 15000L
      (1 to 12).foreach { round =>
        val deadline = java.lang.System.nanoTime() + perRoundMs * 1_000_000L
        var status = 409
        while status == 409 && java.lang.System.nanoTime() < deadline do
          val (socket, code) = openGet(port, sid)
          socket.close()
          status = code
          if code == 409 then Thread.sleep(50)
        withClue(s"round $round: the GET slot was still taken after the previous client left: ") {
          status shouldBe 200
        }
      }
    finally runUnsafe(listener.interrupt.unit)
  }
