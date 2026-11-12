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

  test("pickEvictable with idle fallback: GET-less first; else the longest-idle GET holder past the timeout; None keeps GET holders exempt") {
    val now = 10_000L
    val allHeld = List(("a", 1_000L, true), ("b", 500L, true), ("c", 9_900L, true))
    // Every session holds a GET: the longest-idle one past a 5 s timeout is the victim.
    HttpRequestGuards.pickEvictable(allHeld, now, Some(5_000L)) shouldBe Some("b")
    // Nobody has been idle long enough → still nothing evictable (503).
    HttpRequestGuards.pickEvictable(allHeld, now, Some(9_600L)) shouldBe None
    // sessionIdleTimeout = None: GET holders stay exempt regardless of age.
    HttpRequestGuards.pickEvictable(allHeld, now, None) shouldBe None
    // A GET-less session is preferred even when it is younger than an over-idle GET holder.
    val mixed = List(("held", 100L, true), ("plain", 9_000L, false))
    HttpRequestGuards.pickEvictable(mixed, now, Some(5_000L)) shouldBe Some("plain")
    HttpRequestGuards.pickEvictable(Nil, now, Some(1L)) shouldBe None
  }

  // ---- pickVictim: who makes room at the cap, counted per client ----

  private def stored(id: String, seen: Long, live: Boolean, client: String = "") =
    HttpRequestGuards.StoredSession(id, seen, live, Option(client).filter(_.nonEmpty))

  private val A = "203.0.113.7"
  private val B = "198.51.100.20"
  private val C = "192.0.2.33"

  test("pickVictim: a client holding every slot with live, fresh GETs gives one up to another client") {
    val now = 10_000L
    val hogged = List(stored("a1", 9_990L, live = true, A), stored("a2", 9_980L, live = true, A))
    // The idle rules alone find nobody, with or without a timeout…
    HttpRequestGuards.pickEvictable(hogged.map(s => (s.id, s.lastSeen, s.liveGet)), now, Some(5_000L)) shouldBe None
    // …but the client holding two gives up its longest-idle session to a client holding none.
    HttpRequestGuards.pickVictim(hogged, Some(B), now, Some(5_000L)) shouldBe Some("a2")
    HttpRequestGuards.pickVictim(hogged, Some(B), now, None) shouldBe Some("a2")
    // A newcomer the backend cannot identify is a client holding none, too.
    HttpRequestGuards.pickVictim(hogged, None, now, None) shouldBe Some("a2")
  }

  test("pickVictim: the client holding the most gives up a GET-less session before a live one") {
    val snapshot = List(
      stored("a-live", 100L, live = true, A),
      stored("a-plain", 900L, live = false, A),
      stored("a-live2", 200L, live = true, A),
      stored("b", 50L, live = false, B) // the longest-idle session overall, and GET-less
    )
    // B holds one, A holds three: A gives one up, and B's idle session is left alone.
    HttpRequestGuards.pickVictim(snapshot, Some(C), 1_000L, None) shouldBe Some("a-plain")
  }

  test("pickVictim: a client that holds as many sessions as anyone replaces one of its own") {
    val snapshot = List(
      stored("b", 50L, live = false, B), // would be the idle rules' choice
      stored("a1", 300L, live = true, A),
      stored("a2", 200L, live = true, A)
    )
    HttpRequestGuards.pickVictim(snapshot, Some(A), 1_000L, None) shouldBe Some("a2")
    // Also when every client holds exactly one: the returning client's own session goes.
    val level = List(stored("b", 50L, live = false, B), stored("a", 900L, live = true, A))
    HttpRequestGuards.pickVictim(level, Some(A), 1_000L, None) shouldBe Some("a")
  }

  test("pickVictim: when holdings are level the idle rules decide, 503 included") {
    val now = 10_000L
    // One session each, the newcomer holds none: nobody holds two more than the newcomer.
    val level = List(stored("a", 9_000L, live = true, A), stored("b", 500L, live = true, B))
    HttpRequestGuards.pickVictim(level, Some(C), now, None) shouldBe None
    HttpRequestGuards.pickVictim(level, Some(C), now, Some(20_000L)) shouldBe None
    HttpRequestGuards.pickVictim(level, Some(C), now, Some(5_000L)) shouldBe Some("b") // idle past the timeout
    val withPlain = stored("c", 9_500L, live = false, "198.51.100.99") :: level
    HttpRequestGuards.pickVictim(withPlain, Some(C), now, None) shouldBe Some("c") // GET-less first
    // The newcomer's client holds one, another holds two: one apart is still level.
    val oneApart = List(
      stored("a1", 9_000L, live = true, A),
      stored("a2", 9_100L, live = true, A),
      stored("b", 9_200L, live = true, B)
    )
    HttpRequestGuards.pickVictim(oneApart, Some(B), now, None) shouldBe None
  }

  test("pickVictim: sessions without an identity each count as their own client (the rules before per-client counting)") {
    val now = 10_000L
    val held = List(stored("x", 1_000L, live = true), stored("y", 500L, live = true), stored("z", 9_900L, live = true))
    HttpRequestGuards.pickVictim(held, None, now, None) shouldBe None
    HttpRequestGuards.pickVictim(held, None, now, Some(5_000L)) shouldBe Some("y")
    HttpRequestGuards.pickVictim(held, Some(A), now, None) shouldBe None
    val mixed = stored("plain", 9_000L, live = false) :: held
    HttpRequestGuards.pickVictim(mixed, None, now, Some(5_000L)) shouldBe Some("plain")
    HttpRequestGuards.pickVictim(Nil, Some(A), now, Some(1L)) shouldBe None
  }

  test("pickVictim: one shared identity (a reverse proxy) makes the store least-recently-used") {
    val now = 10_000L
    val proxied = List(stored("p1", 9_000L, live = true, A), stored("p2", 8_000L, live = true, A))
    HttpRequestGuards.pickVictim(proxied, Some(A), now, None) shouldBe Some("p2")
    val withPlain = stored("p3", 9_900L, live = false, A) :: proxied
    HttpRequestGuards.pickVictim(withPlain, Some(A), now, None) shouldBe Some("p3")
  }

  test("pickVictim: several clients tied for the most share the pool") {
    val snapshot = List(
      stored("a1", 300L, live = true, A),
      stored("a2", 400L, live = true, A),
      stored("b1", 100L, live = true, B),
      stored("b2", 500L, live = true, B)
    )
    HttpRequestGuards.pickVictim(snapshot, Some(C), 1_000L, None) shouldBe Some("b1")
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

  test("validateSettings: contextHeaders entries must be HTTP header names") {
    // Surrounding whitespace is trimmed; case does not matter.
    HttpRequestGuards.validateSettings(
      McpServerSettings(contextHeaders = Set("X-Forwarded-Email", " x-forwarded-access-token "))
    ) shouldBe Right(())
    // A trailing colon, an inner space, an empty entry: none can ever match a header, and on Bun
    // looking one up throws on every request.
    val bad = HttpRequestGuards.validateSettings(
      McpServerSettings(contextHeaders = Set("x-forwarded-email:", "x forwarded user", "", "ok"))
    )
    bad.isLeft shouldBe true
    bad.left.getOrElse("") should include("contextHeaders")
    bad.left.getOrElse("") should include("'x-forwarded-email:'")
    bad.left.getOrElse("") should include("'x forwarded user'")
    bad.left.getOrElse("") should not include "'ok'"
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
