package com.tjclp.fastmcp
package server.transport.http

import java.nio.charset.StandardCharsets

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import com.tjclp.fastmcp.server.transport.http.Http1.{Framing, Version}

/** Pure parsing rules of the socket HTTP layer. Compiled into both the JVM and the Scala Native
  * test binaries (`jvm-native/test`), so the RE2-free, `Locale`-free string handling is proven on
  * the platform that lacks them.
  *
  * The parser is strict on purpose: a head that a stricter or a more lenient HTTP parser in front
  * of this server could read differently is refused, never repaired.
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

  test("leading empty lines (CRLF) and one trailing CRLF are tolerated") {
    val head = parsed("\r\nGET /mcp HTTP/1.0\r\nHost: a\r\n")
    head.version shouldBe Version.Http10
    head.header("host") shouldBe Some("a")
    head.wantsClose shouldBe true
  }

  test("every line of a head ends in CRLF: a bare LF is refused, wherever it stands") {
    status("GET /mcp HTTP/1.1\nHost: a") shouldBe 400
    status("GET /mcp HTTP/1.1\r\nHost: a\nX-Next: b") shouldBe 400
    status("GET /mcp HTTP/1.1\r\nHost: a\n") shouldBe 400
    status("\nGET /mcp HTTP/1.1\r\nHost: a") shouldBe 400
    // A lone LF inside a value must not start a header line this server honours.
    status("POST /mcp HTTP/1.1\r\nX-A: b\nContent-Length: 5") shouldBe 400
    // LF LF and LF CRLF are not head terminators.
    status("GET /mcp HTTP/1.1\r\nHost: a\n\nGET /other HTTP/1.1") shouldBe 400
    status("GET /mcp HTTP/1.1\r\nHost: a\n\r\nGET /other HTTP/1.1") shouldBe 400
  }

  test("nothing may follow the blank line that ends a head") {
    status("GET /mcp HTTP/1.1\r\nHost: a\r\n\r\nGET /other HTTP/1.1") shouldBe 400
  }

  test("a bare CR is refused instead of being kept inside a value") {
    status("POST /mcp HTTP/1.1\r\nX-A: b\rContent-Length: 5") shouldBe 400
    status("GET /mcp HTTP/1.1\r\nHost: a\r") shouldBe 400
    status("GET /mcp\r HTTP/1.1\r\nHost: a") shouldBe 400
    status("\rGET /mcp HTTP/1.1\r\nHost: a") shouldBe 400
  }

  test("NUL, other C0 controls and DEL are refused anywhere in a head") {
    val controls = (0x00 to 0x1f).filterNot(c => c == '\t' || c == '\r' || c == '\n') :+ 0x7f
    controls.foreach { code =>
      val c = code.toChar
      withClue(f"control 0x$code%02x: ") {
        status(s"GET /mcp HTTP/1.1\r\nX-A: a${c}b") shouldBe 400
        status(s"GET /mcp HTTP/1.1\r\nX-A: ${c}") shouldBe 400
        status(s"GET /mcp HTTP/1.1\r\nX${c}A: b") shouldBe 400
        status(s"GET /m${c}cp HTTP/1.1\r\nX-A: b") shouldBe 400
      }
    }
  }

  test("HTAB is allowed inside a field value only") {
    parsed("GET /mcp HTTP/1.1\r\nX-A: a\tb").header("x-a") shouldBe Some("a\tb")
    status("GET\t/mcp HTTP/1.1") shouldBe 400
    status("GET /mcp\tHTTP/1.1") shouldBe 400
    status("GET /m\tcp HTTP/1.1") shouldBe 400
    status("GET /mcp HTTP/1.1\r\nX\tA: b") shouldBe 400
    status("GET /mcp HTTP/1.1\r\nX-A\t: b") shouldBe 400
  }

  test("only SP and HTAB are trimmed around a field value") {
    parsed("GET /mcp HTTP/1.1\r\nX-A: \t a b \t ").header("x-a") shouldBe Some("a b")
    parsed("GET /mcp HTTP/1.1\r\nX-A:").header("x-a") shouldBe Some("")
    parsed("GET /mcp HTTP/1.1\r\nX-A:  \t").header("x-a") shouldBe Some("")
    // Latin-1 bytes above 0x7f (obs-text) stay part of the value, including NBSP.
    parsed("GET /mcp HTTP/1.1\r\nX-A: \u00a0v\u00a0").header("x-a") shouldBe Some("\u00a0v\u00a0")
  }

  test("framing headers padded with control bytes are refused, not recognised") {
    List('\u0000', '\u000b', '\u000c', '\u001f', '\r').foreach { pad =>
      withClue(f"pad 0x${pad.toInt}%02x: ") {
        status(s"POST /mcp HTTP/1.1\r\nTransfer-Encoding: ${pad}chunked") shouldBe 400
        status(s"POST /mcp HTTP/1.1\r\nTransfer-Encoding: chunked${pad}") shouldBe 400
        status(s"POST /mcp HTTP/1.1\r\nContent-Length: ${pad}42") shouldBe 400
        status(s"POST /mcp HTTP/1.1\r\nContent-Length: 42${pad}") shouldBe 400
      }
    }
    // SP / HTAB padding is optional whitespace and stays accepted.
    parsed("POST /mcp HTTP/1.1\r\nTransfer-Encoding: \tchunked \t").bodyFraming(100) shouldBe
      Right(Framing.Chunked)
    parsed("POST /mcp HTTP/1.1\r\nContent-Length: \t42 ").bodyFraming(100) shouldBe
      Right(Framing.Length(42))
  }

  test("Content-Length is ASCII digits only") {
    framingStatus("POST /mcp HTTP/1.1\r\nContent-Length: +42", 100) shouldBe 400
    framingStatus("POST /mcp HTTP/1.1\r\nContent-Length: 4 2", 100) shouldBe 400
    framingStatus("POST /mcp HTTP/1.1\r\nContent-Length: 42, 42", 100) shouldBe 400
    framingStatus("POST /mcp HTTP/1.1\r\nContent-Length:", 100) shouldBe 400
    // The same length twice is one length (RFC 9110 §8.6).
    parsed("POST /mcp HTTP/1.1\r\nContent-Length: 42\r\nContent-Length: 42").bodyFraming(100) shouldBe
      Right(Framing.Length(42))
  }

  test("methods and field names are ASCII tchar only") {
    status("G\u00c9T /mcp HTTP/1.1") shouldBe 400
    status("GET /mcp HTTP/1.1\r\nN\u00e4me: x") shouldBe 400
    status("GET /mcp HTTP/1.1\r\nX-\u00aa: x") shouldBe 400
    status("GET /mcp HTTP/1.1\r\nX(A): x") shouldBe 400
    status("GET /mcp HTTP/1.1\r\nX A: x") shouldBe 400
    val head = parsed("M-SEARCH* /mcp HTTP/1.1\r\nX!#$%&'*+-.^_`|~9z: v")
    head.method shouldBe "M-SEARCH*"
    head.header("x!#$%&'*+-.^_`|~9z") shouldBe Some("v")
  }

  test("the request line is method SP target SP version, exactly") {
    status("GET  /mcp HTTP/1.1") shouldBe 400
    status("GET /mcp  HTTP/1.1") shouldBe 400
    status(" GET /mcp HTTP/1.1") shouldBe 400
    status("GET /mcp HTTP/1.1 ") shouldBe 400
    status("GET /mcp extra HTTP/1.1") shouldBe 400
  }

  private def headEnd(text: String, from: Int = 0): Int =
    val bytes = text.getBytes(StandardCharsets.ISO_8859_1)
    Http1.findHeadEnd(bytes, 0, from, bytes.length)

  test("findHeadEnd: only CRLF CRLF ends a head; the scan stops at the first one") {
    headEnd("GET / HTTP/1.1\r\nHost: a\r\n\r\n") shouldBe 23
    headEnd("GET / HTTP/1.1\r\n\r\nGET /next HTTP/1.1\r\n\r\n") shouldBe 14
    // What follows the terminator is a body or the next request: not this scan's business.
    headEnd("GET / HTTP/1.1\r\n\r\n\u0000\n\r") shouldBe 14
    headEnd("GET / HTTP/1.1\r\nHost: a\r\n") shouldBe Http1.HeadIncomplete
    headEnd("GET / HTTP/1.1\r\nHost: a\tb") shouldBe Http1.HeadIncomplete
    headEnd("") shouldBe Http1.HeadIncomplete
  }

  test("findHeadEnd: a bare LF, a bare CR or a control byte is malformed as soon as it is seen") {
    headEnd("GET / HTTP/1.1\nHost: a\n\n") shouldBe Http1.HeadMalformed
    headEnd("GET / HTTP/1.1\r\nHost: a\n\n") shouldBe Http1.HeadMalformed
    headEnd("GET / HTTP/1.1\r\nHost: a\n\r\n") shouldBe Http1.HeadMalformed
    headEnd("GET / HTTP/1.1\r\nHost: a\r\n\n") shouldBe Http1.HeadMalformed
    headEnd("\nGET / HTTP/1.1") shouldBe Http1.HeadMalformed
    headEnd("GET / HTTP/1.1\r\nX: a\rb") shouldBe Http1.HeadMalformed
    headEnd("GET / HTTP/1.1\r\rX") shouldBe Http1.HeadMalformed
    headEnd("GET / HT\u0000TP/1.1") shouldBe Http1.HeadMalformed
    headEnd("GET / HTTP/1.1\r\nX: \u000b") shouldBe Http1.HeadMalformed
    headEnd("GET / HTTP/1.1\r\nX: \u007f") shouldBe Http1.HeadMalformed
  }

  test("findHeadEnd: a scan resumed at any split point gives the verdict of a scan in one piece") {
    val heads = List(
      "GET / HTTP/1.1\r\nHost: a\r\nX: b\r\n\r\nrest",
      "GET / HTTP/1.1\r\nHost: a\n\nGET /x HTTP/1.1\r\n\r\n",
      "GET / HTTP/1.1\r\nX: a\rb\r\n\r\n",
      "GET / HTTP/1.1\r\nX: a\u0000\r\n\r\n"
    )
    heads.foreach { text =>
      val bytes = text.getBytes(StandardCharsets.ISO_8859_1)
      val whole = Http1.findHeadEnd(bytes, 0, 0, bytes.length)
      (1 until bytes.length).foreach { split =>
        withClue(s"split at $split of ${text.length}: ") {
          // First read: bytes [0, split). Second read: everything.
          val first = Http1.findHeadEnd(bytes, 0, 0, split)
          val verdict =
            if first != Http1.HeadIncomplete then first
            else Http1.findHeadEnd(bytes, 0, Http1.resumeFrom(bytes, 0, split), bytes.length)
          verdict shouldBe whole
        }
      }
    }
  }

  test("resumeFrom: a trailing CR is scanned again, everything else is not") {
    val bytes = "ab\r".getBytes(StandardCharsets.ISO_8859_1)
    Http1.resumeFrom(bytes, 0, 3) shouldBe 2
    Http1.resumeFrom(bytes, 0, 2) shouldBe 2
    Http1.resumeFrom(bytes, 0, 0) shouldBe 0
    Http1.resumeFrom(bytes, 2, 3) shouldBe 2
  }

  test("trimOws removes SP and HTAB only") {
    Http1.trimOws(" \t a b\t ") shouldBe "a b"
    Http1.trimOws("") shouldBe ""
    Http1.trimOws(" \t") shouldBe ""
    Http1.trimOws("\u000ba\u000c") shouldBe "\u000ba\u000c"
    Http1.trimOws("\u00a0a") shouldBe "\u00a0a"
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
