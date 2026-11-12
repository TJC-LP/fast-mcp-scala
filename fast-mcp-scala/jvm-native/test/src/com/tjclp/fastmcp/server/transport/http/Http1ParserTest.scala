package com.tjclp.fastmcp
package server.transport.http

import java.nio.charset.StandardCharsets

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import com.tjclp.fastmcp.server.transport.http.Http1.{Framing, Version}

/** Pure parsing rules of the socket HTTP layer. Compiled into both the JVM and the Scala Native
  * test binaries (`jvm-native/test`), so the RE2-free, `Locale`-free string handling is proven on
  * the platform that lacks them.
  */
class Http1ParserTest extends AnyFunSuite with Matchers:

  private def parse(head: String) =
    Http1.parseHead(head.getBytes(StandardCharsets.ISO_8859_1))

  private def parsed(head: String) =
    parse(head).getOrElse(fail(s"expected a parsed head for: $head"))

  private def status(head: String): Int =
    parse(head).left.map(_.status).swap.getOrElse(fail(s"expected a rejection for: $head"))

  private def framingStatus(head: String, max: Int): Int =
    parsed(head)
      .bodyFraming(max)
      .left
      .map(_.status)
      .swap
      .getOrElse(fail(s"expected a framing rejection for: $head"))

  test("request line + headers: names lowercased, values trimmed, first value wins") {
    val head = parsed(
      "POST /mcp?x=1 HTTP/1.1\r\nHost: localhost:8000\r\nAccept:  application/json \r\n" +
        "X-Dup: first\r\nx-dup: second"
    )
    head.method shouldBe "POST"
    head.target shouldBe "/mcp?x=1"
    head.path shouldBe "/mcp"
    head.version shouldBe Version.Http11
    head.header("HOST") shouldBe Some("localhost:8000")
    head.header("accept") shouldBe Some("application/json")
    head.header("x-dup") shouldBe Some("first")
    head.header("missing") shouldBe None
  }

  test("bare LF line endings and leading empty lines are tolerated") {
    val head = parsed("\r\nGET /mcp HTTP/1.0\nHost: a\n")
    head.version shouldBe Version.Http10
    head.header("host") shouldBe Some("a")
    head.wantsClose shouldBe true
  }

  test("absolute-form targets reduce to their path") {
    parsed("GET http://example.com:8000/mcp?q HTTP/1.1").path shouldBe "/mcp"
    parsed("GET http://example.com HTTP/1.1").path shouldBe "/"
  }

  test("malformed request lines, versions and headers are rejected") {
    status("GARBAGE") shouldBe 400
    status("GET /mcp") shouldBe 400
    status("GET /mcp HTTP/2.0") shouldBe 505
    status("GET /mcp HTTP/1.1\r\nNoColonHere") shouldBe 400
    status("GET /mcp HTTP/1.1\r\n: empty-name") shouldBe 400
    status("GET /mcp HTTP/1.1\r\nA: b\r\n  folded") shouldBe 400
    status("GET /mcp HTTP/1.1\r\nBad Name: x") shouldBe 400
  }

  test("body framing: none, content-length, chunked, and the contradictory cases") {
    parsed("GET /mcp HTTP/1.1").bodyFraming(100) shouldBe Right(Framing.Empty)
    parsed("POST /mcp HTTP/1.1\r\nContent-Length: 42").bodyFraming(100) shouldBe
      Right(Framing.Length(42))
    parsed("POST /mcp HTTP/1.1\r\nTransfer-Encoding: Chunked").bodyFraming(100) shouldBe
      Right(Framing.Chunked)
    framingStatus("POST /mcp HTTP/1.1\r\nContent-Length: 101", 100) shouldBe 413
    framingStatus("POST /mcp HTTP/1.1\r\nContent-Length: abc", 100) shouldBe 400
    framingStatus("POST /mcp HTTP/1.1\r\nContent-Length: 1\r\nContent-Length: 2", 100) shouldBe 400
    framingStatus(
      "POST /mcp HTTP/1.1\r\nContent-Length: 1\r\nTransfer-Encoding: chunked",
      100
    ) shouldBe 400
    framingStatus("POST /mcp HTTP/1.1\r\nTransfer-Encoding: gzip", 100) shouldBe 501
  }

  test("connection semantics: Expect and Connection headers") {
    val expecting = parsed("POST /mcp HTTP/1.1\r\nExpect: 100-Continue")
    expecting.expectsContinue shouldBe true
    expecting.wantsClose shouldBe false
    parsed("POST /mcp HTTP/1.1\r\nConnection: keep-alive, Close").wantsClose shouldBe true
    parsed("POST /mcp HTTP/1.1\r\nConnection: keep-alive").wantsClose shouldBe false
  }

  test("asciiLower folds only A-Z and leaves other characters alone") {
    Http1.asciiLower("Mcp-Session-Id") shouldBe "mcp-session-id"
    Http1.asciiLower("already-lower") shouldBe "already-lower"
    Http1.asciiLower("ÄÖ-Ünchanged") shouldBe "ÄÖ-Ünchanged"
  }

  test("SseFrame encoding matches the event/data/blank-line wire form") {
    SseFrame(Some("message"), """{"a":1}""").encode shouldBe "event: message\ndata: {\"a\":1}\n\n"
    SseFrame(None, "one\ntwo").encode shouldBe "data: one\ndata: two\n\n"
    SseFrame.Ping.encode shouldBe "event: ping\n\n"
  }
