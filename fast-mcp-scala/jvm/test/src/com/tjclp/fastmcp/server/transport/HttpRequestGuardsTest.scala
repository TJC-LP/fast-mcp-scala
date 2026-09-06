package com.tjclp.fastmcp
package server.transport

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import com.tjclp.fastmcp.server.{LimitSettings, McpServerSettings}
import com.tjclp.fastmcp.server.transport.HttpRequestGuards.Rejection

/** Unit coverage of the shared transport admission decisions (F1 §2, F5, F12): the POST gate order,
  * the media-type matcher, the body cap and the legacy session-cap policy.
  */
class HttpRequestGuardsTest extends AnyFunSuite with Matchers:

  private val guarded = McpServerSettings(
    allowedHosts = Some(Set("127.0.0.1", "localhost")),
    maxRequestBodyBytes = 64
  )

  private def headers(pairs: (String, String)*): String => Option[String] =
    val m = pairs.toMap
    name => m.get(name)

  private val jsonOk = headers(
    "host" -> "localhost:8000",
    "content-type" -> "application/json",
    "accept" -> "application/json, text/event-stream"
  )

  test("postGate: a request violating everything is refused 403 first") {
    val everythingWrong = headers(
      "host" -> "localhost:8000",
      "origin" -> "http://localhost:3000",
      "content-type" -> "text/plain",
      "content-length" -> "200",
      "accept" -> "text/plain"
    )
    HttpRequestGuards.postGate(everythingWrong, guarded, requireSse = true).map(_.status) shouldBe
      Some(403)
  }

  test("postGate: 415 comes before 413 and 406") {
    val h = headers(
      "host" -> "localhost:8000",
      "content-type" -> "text/plain",
      "content-length" -> "200",
      "accept" -> "text/plain"
    )
    HttpRequestGuards.postGate(h, guarded, requireSse = true) shouldBe
      Some(Rejection(415, "Content-Type must be application/json"))
    // Absent Content-Type is 415 too.
    HttpRequestGuards
      .postGate(headers("host" -> "localhost:8000"), guarded, requireSse = false)
      .map(_.status) shouldBe Some(415)
  }

  test("postGate: 413 (declared Content-Length) comes before 406") {
    val h = headers(
      "host" -> "localhost:8000",
      "content-type" -> "application/json",
      "content-length" -> "200",
      "accept" -> "text/plain"
    )
    HttpRequestGuards.postGate(h, guarded, requireSse = true) shouldBe
      Some(Rejection(413, "Request body exceeds 64 bytes"))
  }

  test("postGate: 406 for Accept excluding JSON, and for missing SSE only when required") {
    val noJson = headers(
      "host" -> "localhost:8000",
      "content-type" -> "application/json",
      "accept" -> "text/plain"
    )
    HttpRequestGuards.postGate(noJson, guarded, requireSse = false) shouldBe
      Some(Rejection(406, "Accept must allow application/json"))
    val jsonOnly = headers(
      "host" -> "localhost:8000",
      "content-type" -> "application/json",
      "accept" -> "application/json"
    )
    HttpRequestGuards.postGate(jsonOnly, guarded, requireSse = false) shouldBe None
    HttpRequestGuards.postGate(jsonOnly, guarded, requireSse = true) shouldBe
      Some(Rejection(406, "Accept must allow text/event-stream"))
  }

  test("postGate: a well-formed request passes; absent Accept passes; guard off ignores Origin") {
    HttpRequestGuards.postGate(jsonOk, guarded, requireSse = true) shouldBe None
    HttpRequestGuards.postGate(
      headers("host" -> "localhost:8000", "content-type" -> "application/json"),
      guarded,
      requireSse = true
    ) shouldBe None
    val crossPort = headers(
      "host" -> "localhost:8000",
      "origin" -> "http://localhost:3000",
      "content-type" -> "application/json"
    )
    HttpRequestGuards.postGate(crossPort, McpServerSettings(), requireSse = true) shouldBe None
    HttpRequestGuards.postGate(crossPort, guarded, requireSse = true).map(_.status) shouldBe Some(
      403
    )
  }

  test("hostGate: 403 with the DNS-rebinding message; None when allowed") {
    HttpRequestGuards.hostGate(headers("host" -> "evil.example.com"), guarded) shouldBe
      Some(Rejection(403, "Host/Origin not allowed (DNS-rebinding protection)"))
    HttpRequestGuards.hostGate(headers("host" -> "127.0.0.1:8000"), guarded) shouldBe None
    HttpRequestGuards.hostGate(headers("host" -> "evil.example.com"), McpServerSettings()) shouldBe
      None
  }

  test("isJsonContentType: application/json with parameters, case-insensitive, utf-8 charsets") {
    for ok <- List(
        "application/json",
        "application/json; charset=utf-8",
        "application/json;charset=UTF-8",
        "Application/JSON",
        "application/json;charset=\"UTF-8\"",
        "application/json; charset=utf8",
        " application/json ",
        "application/json; boundary=x"
      )
    do withClue(ok)(HttpRequestGuards.isJsonContentType(Some(ok)) shouldBe true)
  }

  test("isJsonContentType: other media types, wildcards, non-utf-8 charsets and absent → false") {
    for bad <- List(
        "text/plain",
        "text/plain; charset=utf-8",
        "application/json-patch+json",
        "application/*",
        "*/*",
        "application/json; charset=utf-16",
        "application/json; charset=iso-8859-1",
        "application/jsonx",
        "application/x-www-form-urlencoded",
        "multipart/form-data; boundary=----x",
        ""
      )
    do withClue(bad)(HttpRequestGuards.isJsonContentType(Some(bad)) shouldBe false)
    HttpRequestGuards.isJsonContentType(None) shouldBe false
  }

  test("acceptsAny: absent passes; wildcard passes; listed types match case-insensitively") {
    val types = List("application/json", "application/*")
    HttpRequestGuards.acceptsAny(None, types) shouldBe true
    HttpRequestGuards.acceptsAny(Some("*/*"), types) shouldBe true
    HttpRequestGuards.acceptsAny(Some("Application/JSON"), types) shouldBe true
    HttpRequestGuards.acceptsAny(Some("text/event-stream, application/json"), types) shouldBe true
    HttpRequestGuards.acceptsAny(Some("text/plain"), types) shouldBe false
  }

  test("declaredLengthExceeds: only a parseable length above the cap trips") {
    HttpRequestGuards.declaredLengthExceeds(Some("200"), guarded) shouldBe true
    HttpRequestGuards.declaredLengthExceeds(Some("65"), guarded) shouldBe true
    HttpRequestGuards.declaredLengthExceeds(Some("64"), guarded) shouldBe false
    HttpRequestGuards.declaredLengthExceeds(Some(" 10 "), guarded) shouldBe false
    HttpRequestGuards.declaredLengthExceeds(Some("garbage"), guarded) shouldBe false
    HttpRequestGuards.declaredLengthExceeds(None, guarded) shouldBe false
    HttpRequestGuards.declaredLengthExceeds(Some("99999999999999"), guarded) shouldBe true
  }

  test(
    "bodyTooLarge: strictly above the cap in UTF-16 code units; rejection message names the cap"
  ) {
    HttpRequestGuards.bodyTooLarge("a" * 64, guarded) shouldBe false
    HttpRequestGuards.bodyTooLarge("a" * 65, guarded) shouldBe true
    HttpRequestGuards.bodyTooLargeRejection(guarded) shouldBe
      Rejection(413, "Request body exceeds 64 bytes")
  }

  test("capReached: Some(n) caps at n; None disables") {
    val two = McpServerSettings(maxSessions = Some(2))
    HttpRequestGuards.capReached(0, two) shouldBe false
    HttpRequestGuards.capReached(1, two) shouldBe false
    HttpRequestGuards.capReached(2, two) shouldBe true
    HttpRequestGuards.capReached(3, two) shouldBe true
    val off = McpServerSettings(maxSessions = None)
    HttpRequestGuards.capReached(1_000_000, off) shouldBe false
    HttpRequestGuards.capReached(1000, McpServerSettings()) shouldBe true // default Some(1000)
    HttpRequestGuards.capReached(999, McpServerSettings()) shouldBe false
  }

  test("pickEvictable: longest-idle session without a live GET; None when all hold a GET") {
    val snapshot = List(("a", 300L, false), ("b", 100L, true), ("c", 200L, false))
    HttpRequestGuards.pickEvictable(snapshot) shouldBe Some("c") // b is oldest but live
    HttpRequestGuards.pickEvictable(List(("a", 1L, true), ("b", 2L, true))) shouldBe None
    HttpRequestGuards.pickEvictable(Nil) shouldBe None
    HttpRequestGuards.pickEvictable(List(("only", 5L, false))) shouldBe Some("only")
  }

  test("validateSettings: accepts defaults and parseable origins") {
    HttpRequestGuards.validateSettings(McpServerSettings()) shouldBe Right(())
    HttpRequestGuards.validateSettings(
      McpServerSettings(allowedOrigins =
        Some(Set("https://app.example.com", "http://localhost:3000"))
      )
    ) shouldBe Right(())
    HttpRequestGuards.validateSettings(McpServerSettings(maxSessions = None)) shouldBe Right(())
  }

  test(
    "validateSettings: rejects unparseable origins, non-positive body cap and non-positive cap"
  ) {
    val badOrigins = HttpRequestGuards.validateSettings(
      McpServerSettings(allowedOrigins = Some(Set("https//app.example.com", "app.example.com")))
    )
    badOrigins.isLeft shouldBe true
    badOrigins.left.getOrElse("") should include("app.example.com")
    badOrigins.left.getOrElse("") should include("https//app.example.com")
    HttpRequestGuards.validateSettings(McpServerSettings(maxRequestBodyBytes = 0)) shouldBe
      Left("maxRequestBodyBytes must be positive")
    HttpRequestGuards.validateSettings(McpServerSettings(maxRequestBodyBytes = -1)).isLeft shouldBe
      true
    HttpRequestGuards
      .validateSettings(McpServerSettings(maxSessions = Some(0)))
      .isLeft shouldBe true
  }

  test("validateSettings: the body cap must not exceed limits.maxFrameChars") {
    // Default 1 MiB body cap inside the default 4 MiB frame cap: fine.
    HttpRequestGuards.validateSettings(McpServerSettings()) shouldBe Right(())
    // Equal is allowed (413 still fires first on a body over the cap; the frame cap is the backstop).
    HttpRequestGuards.validateSettings(
      McpServerSettings(maxRequestBodyBytes = 4096, limits = LimitSettings(maxFrameChars = 4096))
    ) shouldBe Right(())
    HttpRequestGuards.validateSettings(
      McpServerSettings(maxRequestBodyBytes = 4097, limits = LimitSettings(maxFrameChars = 4096))
    ) shouldBe Left("maxRequestBodyBytes must not exceed limits.maxFrameChars")
  }
