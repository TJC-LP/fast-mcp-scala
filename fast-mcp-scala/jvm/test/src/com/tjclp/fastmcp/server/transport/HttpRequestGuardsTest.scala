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
    HttpRequestGuards.postGate(
      crossPort,
      McpServerSettings(dnsRebindingProtection = false),
      requireSse = true
    ) shouldBe None
    // Default settings bind loopback, so the guard is on without any allow-list.
    HttpRequestGuards.postGate(crossPort, McpServerSettings(), requireSse = true).map(_.status) shouldBe
      Some(403)
    HttpRequestGuards.postGate(crossPort, guarded, requireSse = true).map(_.status) shouldBe Some(
      403
    )
  }

  test("hostGate: 403 with the DNS-rebinding message; None when allowed") {
    HttpRequestGuards.hostGate(headers("host" -> "evil.example.com"), guarded) shouldBe
      Some(Rejection(403, "Host/Origin not allowed (DNS-rebinding protection)"))
    HttpRequestGuards.hostGate(headers("host" -> "127.0.0.1:8000"), guarded) shouldBe None
    // Default settings: the automatic loopback guard refuses it too...
    HttpRequestGuards.hostGate(headers("host" -> "evil.example.com"), McpServerSettings()) shouldBe
      Some(Rejection(403, "Host/Origin not allowed (DNS-rebinding protection)"))
    HttpRequestGuards.hostGate(headers("host" -> "localhost:8000"), McpServerSettings()) shouldBe None
    // ...and only the explicit opt-out lets it through.
    HttpRequestGuards.hostGate(
      headers("host" -> "evil.example.com"),
      McpServerSettings(dnsRebindingProtection = false)
    ) shouldBe None
  }

  test("startupWarnings: only a non-loopback bind with nothing listed and no opt-out warns") {
    val warned = HttpRequestGuards.startupWarnings(McpServerSettings(host = "0.0.0.0"))
    warned should have size 1
    warned.head should include("'0.0.0.0'")
    warned.head should include("allowedHosts")
    warned.head should include("allowedOrigins")
    warned.head should include("dnsRebindingProtection = false")
    HttpRequestGuards.startupWarnings(McpServerSettings()) shouldBe Nil
    HttpRequestGuards.startupWarnings(McpServerSettings(host = "localhost")) shouldBe Nil
    HttpRequestGuards.startupWarnings(
      McpServerSettings(host = "0.0.0.0", allowedHosts = Some(Set("mcp.example.com")))
    ) shouldBe Nil
    HttpRequestGuards.startupWarnings(
      McpServerSettings(host = "0.0.0.0", allowedOrigins = Some(Set("https://app.example.com")))
    ) shouldBe Nil
    HttpRequestGuards.startupWarnings(
      McpServerSettings(host = "0.0.0.0", dnsRebindingProtection = false)
    ) shouldBe Nil
  }

  test("validateSettings: the opt-out is accepted alone and refused next to an allow-list") {
    HttpRequestGuards.validateSettings(McpServerSettings(dnsRebindingProtection = false)) shouldBe
      Right(())
    HttpRequestGuards.validateSettings(
      McpServerSettings(host = "0.0.0.0", dnsRebindingProtection = false)
    ) shouldBe Right(())
    // An empty list is "nothing listed": no contradiction.
    HttpRequestGuards.validateSettings(
      McpServerSettings(allowedHosts = Some(Set.empty), dnsRebindingProtection = false)
    ) shouldBe Right(())
    val withHosts = HttpRequestGuards.validateSettings(
      McpServerSettings(allowedHosts = Some(Set("localhost")), dnsRebindingProtection = false)
    )
    withHosts shouldBe Left(HttpRequestGuards.OptOutWithAllowListMessage)
    withHosts.left.getOrElse("") should include("dnsRebindingProtection")
    HttpRequestGuards.validateSettings(
      McpServerSettings(
        allowedOrigins = Some(Set("https://app.example.com")),
        dnsRebindingProtection = false
      )
    ) shouldBe Left(HttpRequestGuards.OptOutWithAllowListMessage)
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

  test("acceptAllows: absent or blank passes; wildcards pass; ranges match case-insensitively") {
    val json = "application/json"
    HttpRequestGuards.acceptAllows(None, json) shouldBe true
    HttpRequestGuards.acceptAllows(Some(""), json) shouldBe true
    HttpRequestGuards.acceptAllows(Some("  "), json) shouldBe true
    HttpRequestGuards.acceptAllows(Some("*/*"), json) shouldBe true
    HttpRequestGuards.acceptAllows(Some("application/*"), json) shouldBe true
    HttpRequestGuards.acceptAllows(Some("Application/JSON"), json) shouldBe true
    HttpRequestGuards.acceptAllows(Some("text/event-stream, application/json"), json) shouldBe true
    HttpRequestGuards.acceptAllows(Some("application/json; charset=utf-8"), json) shouldBe true
    HttpRequestGuards.acceptAllows(Some("text/plain"), json) shouldBe false
    HttpRequestGuards.acceptAllows(Some("text/*"), json) shouldBe false
  }

  test("acceptAllows (D3.19): a range is not matched by substring") {
    // Each of these CONTAINS `application/json` or the any-type wildcard without listing it.
    val json = "application/json"
    HttpRequestGuards.acceptAllows(Some("application/json-patch+json"), json) shouldBe false
    HttpRequestGuards.acceptAllows(Some("application/jsonl"), json) shouldBe false
    HttpRequestGuards.acceptAllows(Some("x-application/json"), json) shouldBe false
    HttpRequestGuards.acceptAllows(Some("text/plain; note=\"*/*\""), json) shouldBe false
  }

  test("acceptAllows (D3.19): q=0 means not acceptable, and the most specific range decides") {
    val json = "application/json"
    val sse = "text/event-stream"
    HttpRequestGuards.acceptAllows(Some("*/*;q=0"), json) shouldBe false
    HttpRequestGuards.acceptAllows(Some("*/*; q=0.0"), json) shouldBe false
    HttpRequestGuards.acceptAllows(Some("*/*;Q=0.000"), json) shouldBe false
    HttpRequestGuards.acceptAllows(Some("application/json;q=0"), json) shouldBe false
    HttpRequestGuards.acceptAllows(Some("application/json;q=0.001"), json) shouldBe true
    // The exact type outranks both wildcards, whatever the order of the ranges.
    HttpRequestGuards.acceptAllows(Some("application/json;q=0, */*"), json) shouldBe false
    HttpRequestGuards.acceptAllows(Some("*/*, application/json;q=0"), json) shouldBe false
    HttpRequestGuards.acceptAllows(Some("*/*;q=0, application/json"), json) shouldBe true
    HttpRequestGuards.acceptAllows(Some("application/*;q=0, */*"), json) shouldBe false
    HttpRequestGuards.acceptAllows(Some("application/*;q=0, application/json"), json) shouldBe true
    HttpRequestGuards.acceptAllows(Some("text/event-stream;q=0, */*"), sse) shouldBe false
    HttpRequestGuards.acceptAllows(Some("text/event-stream;q=0, */*"), json) shouldBe true
    // A weight that does not parse counts as listed; among equal ranges the highest weight wins.
    HttpRequestGuards.acceptAllows(Some("application/json;q=abc"), json) shouldBe true
    HttpRequestGuards.acceptAllows(Some("application/json;q=0, application/json"), json) shouldBe true
  }

  test("acceptQuality: the weight of the most specific matching range; None when nothing matches") {
    HttpRequestGuards.acceptQuality("application/json;q=0.5, */*;q=0.1", "application/json") shouldBe
      Some(0.5)
    HttpRequestGuards.acceptQuality("application/json;q=0.5, */*;q=0.1", "text/event-stream") shouldBe
      Some(0.1)
    HttpRequestGuards.acceptQuality("text/*;q=0.3", "text/event-stream") shouldBe Some(0.3)
    HttpRequestGuards.acceptQuality("text/html", "text/event-stream") shouldBe None
    HttpRequestGuards.acceptQuality("", "text/event-stream") shouldBe None
    HttpRequestGuards.acceptQuality(",;,", "text/event-stream") shouldBe None
  }

  test("modernAcceptOk: the header is required and must allow both media types") {
    HttpRequestGuards.modernAcceptOk(None) shouldBe false
    HttpRequestGuards.modernAcceptOk(Some("")) shouldBe false
    HttpRequestGuards.modernAcceptOk(Some("application/json")) shouldBe false
    HttpRequestGuards.modernAcceptOk(Some("text/event-stream")) shouldBe false
    HttpRequestGuards.modernAcceptOk(Some("application/json, text/event-stream")) shouldBe true
    HttpRequestGuards.modernAcceptOk(Some("*/*")) shouldBe true
    HttpRequestGuards.modernAcceptOk(Some("application/*, text/*")) shouldBe true
    HttpRequestGuards.modernAcceptOk(Some("*/*;q=0")) shouldBe false
    HttpRequestGuards.modernAcceptOk(Some("application/json, text/event-stream;q=0")) shouldBe false
    HttpRequestGuards.modernAcceptOk(Some("*/*, text/event-stream;q=0")) shouldBe false
  }

  test("postGate (D3.20): a 2026-07-28 POST has ONE 406 — -32020 and one message — for every Accept failure") {
    def modern(accept: Option[String]) = headers(
      (List(
        "host" -> "localhost:8000",
        "content-type" -> "application/json",
        "mcp-protocol-version" -> "2026-07-28"
      ) ++ accept.map("accept" -> _))*
    )
    val refusal = Some(
      Rejection(
        406,
        "Accept must include application/json and text/event-stream",
        code = Some(-32020)
      )
    )
    for
      requireSse <- List(true, false)
      accept <- List(
        None,
        Some("application/json"),
        Some("text/event-stream"),
        Some("text/html"),
        Some("*/*;q=0"),
        Some("application/json, text/event-stream;q=0")
      )
    do
      withClue(s"requireSse=$requireSse accept=$accept: ") {
        HttpRequestGuards.postGate(modern(accept), guarded, requireSse) shouldBe refusal
      }
    refusal.map(_.errorCode) shouldBe Some(-32020)
    HttpRequestGuards.postGate(
      modern(Some("application/json, text/event-stream")),
      guarded,
      requireSse = true
    ) shouldBe None
    // An unknown version is on the modern path too (it is answered -32022 there).
    val unknown = headers(
      "host" -> "localhost:8000",
      "content-type" -> "application/json",
      "mcp-protocol-version" -> "2099-01-01",
      "accept" -> "application/json"
    )
    HttpRequestGuards.postGate(unknown, guarded, requireSse = false) shouldBe refusal
  }

  test("postGate: a legacy protocol version keeps the lenient Accept rule and the -32000 code") {
    def legacy(accept: Option[String]) = headers(
      (List(
        "host" -> "localhost:8000",
        "content-type" -> "application/json",
        "mcp-protocol-version" -> "2025-11-25"
      ) ++ accept.map("accept" -> _))*
    )
    HttpRequestGuards.postGate(legacy(None), guarded, requireSse = true) shouldBe None
    val jsonOnly = HttpRequestGuards.postGate(legacy(Some("application/json")), guarded, true)
    jsonOnly shouldBe Some(Rejection(406, "Accept must allow text/event-stream"))
    jsonOnly.map(_.errorCode) shouldBe Some(-32000)
    HttpRequestGuards.postGate(legacy(Some("application/json")), guarded, false) shouldBe None
    HttpRequestGuards.postGate(legacy(Some("*/*;q=0")), guarded, false) shouldBe
      Some(Rejection(406, "Accept must allow application/json"))
  }

  test("sessionId (C3.6): an empty or blank mcp-session-id counts as absent; a value is trimmed") {
    HttpRequestGuards.sessionId(headers()) shouldBe None
    HttpRequestGuards.sessionId(headers("mcp-session-id" -> "")) shouldBe None
    HttpRequestGuards.sessionId(headers("mcp-session-id" -> "   ")) shouldBe None
    HttpRequestGuards.sessionId(headers("mcp-session-id" -> " abc ")) shouldBe Some("abc")
    // A header sent twice reaches the gates joined: one value that names no session.
    HttpRequestGuards.sessionId(headers("mcp-session-id" -> "a, b")) shouldBe Some("a, b")
  }

  test("pathMatches (D3.16): the endpoint is an exact, case-sensitive path") {
    val default = McpServerSettings()
    HttpRequestGuards.endpointPath(default) shouldBe "/mcp"
    HttpRequestGuards.endpointPath(McpServerSettings(httpEndpoint = "mcp")) shouldBe "/mcp"
    HttpRequestGuards.endpointPath(McpServerSettings(httpEndpoint = "/api/mcp")) shouldBe "/api/mcp"
    HttpRequestGuards.pathMatches("/mcp", default) shouldBe true
    for other <- List("/mcp/", "/MCP", "/mcp/extra", "/api/mcp", "/xmcpx", "mcp", "/", "", "//mcp")
    do withClue(other)(HttpRequestGuards.pathMatches(other, default) shouldBe false)
    val nested = McpServerSettings(httpEndpoint = "/api/v1/mcp")
    HttpRequestGuards.pathMatches("/api/v1/mcp", nested) shouldBe true
    HttpRequestGuards.pathMatches("/mcp", nested) shouldBe false
    // A trailing slash in the setting is part of the endpoint.
    val slashed = McpServerSettings(httpEndpoint = "/mcp/")
    HttpRequestGuards.pathMatches("/mcp/", slashed) shouldBe true
    HttpRequestGuards.pathMatches("/mcp", slashed) shouldBe false
  }

  test("a backend that decodes paths serves an endpoint clients percent-encode, with a WARN") {
    for encoded <- List("/mcp-ü", "/a{b}", "/a^b")
    do
      withClue(s"'$encoded': ") {
        val settings = McpServerSettings(httpEndpoint = encoded)
        HttpRequestGuards.validateSettings(settings).isLeft shouldBe true
        HttpRequestGuards.validateSettings(settings, decodedPaths = true) shouldBe Right(())
        HttpRequestGuards.encodedEndpointWarning(encoded).isDefined shouldBe true
      }
    // What no backend can match stays refused, and a plain endpoint has no warning.
    for bad <- List("/mcp?x=1", "/m%63p", "/my mcp", "/a/../mcp")
    do
      withClue(s"'$bad': ") {
        HttpRequestGuards
          .validateSettings(McpServerSettings(httpEndpoint = bad), decodedPaths = true)
          .isLeft shouldBe true
        HttpRequestGuards.encodedEndpointWarning(bad) shouldBe None
      }
    HttpRequestGuards.encodedEndpointWarning("/mcp") shouldBe None
  }

  test("validateSettings (C3.11): an httpEndpoint no request path can equal is refused at startup") {
    for bad <- List("/mcp?x=1", "/mcp#frag", "/m%63p", "/a%2Fb", "/my mcp", "/mcp\t", "/mcp\n", "/./mcp", "/a/../mcp", "/mcp/.", "/mcp-ü", "/mcp\"x", "/a<b>", "/a\\b", "/a{b}", "/a^b", "/a`b")
    do
      withClue(s"'$bad': ") {
        val verdict = HttpRequestGuards.validateSettings(McpServerSettings(httpEndpoint = bad))
        verdict.isLeft shouldBe true
        verdict.left.getOrElse("") should include("httpEndpoint")
      }
    for good <- List("/mcp", "mcp", "/api/v1/mcp", "/mcp/", "/", "/m.c.p", "/mcp-2_x~y", "/..mcp", "/a:b@c", "/a!$&'()*+,;=b", "/a[1]", "/a|b")
    do
      withClue(s"'$good': ") {
        HttpRequestGuards.validateSettings(McpServerSettings(httpEndpoint = good)) shouldBe
          Right(())
      }
  }

  test("allowedMethods: POST always; GET with a push channel and DELETE only on the session adapter") {
    val streamable = McpServerSettings()
    HttpRequestGuards.allowedMethods(streamable, pushChannel = true) shouldBe
      List("POST", "GET", "DELETE")
    HttpRequestGuards.allowedMethods(streamable, pushChannel = false) shouldBe List("POST", "DELETE")
    HttpRequestGuards.allowedMethods(streamable.copy(disallowDelete = true), true) shouldBe
      List("POST", "GET")
    HttpRequestGuards.allowedMethods(streamable.copy(stateless = true), true) shouldBe List("POST")
    HttpRequestGuards.allowedMethods(streamable.copy(stateless = true), false) shouldBe List("POST")
  }

  test("nonPostGate (C3.7): the Host/Origin guard runs before the method is judged") {
    val evil = headers("host" -> "evil.example.com")
    for
      method <- List("GET", "DELETE", "PUT", "PATCH", "OPTIONS", "HEAD", "TRACE")
      settings <- List(guarded, guarded.copy(stateless = true))
      pushChannel <- List(true, false)
    do
      withClue(s"$method stateless=${settings.stateless} push=$pushChannel: ") {
        HttpRequestGuards.nonPostGate(method, evil, settings, pushChannel) shouldBe
          Some(Rejection(403, "Host/Origin not allowed (DNS-rebinding protection)"))
      }
  }

  test("nonPostGate (C3.7): 405 with an Allow header and a message that names the method") {
    val ok = headers("host" -> "localhost:8000")
    for method <- List("PUT", "PATCH", "OPTIONS", "HEAD", "TRACE", "get") do
      val refusal = HttpRequestGuards.nonPostGate(method, ok, guarded, pushChannel = true)
      withClue(s"$method: ") {
        refusal.map(_.status) shouldBe Some(405)
        refusal.map(_.allow) shouldBe Some(List("POST", "GET", "DELETE"))
        refusal.map(_.headers) shouldBe Some(List("allow" -> "POST, GET, DELETE"))
        refusal.map(_.message).getOrElse("") should include(s"Method $method is not allowed")
        refusal.map(_.errorCode) shouldBe Some(-32000)
      }
    // GET and DELETE are admitted where the legacy session adapter serves them...
    HttpRequestGuards.nonPostGate("GET", ok, guarded, pushChannel = true) shouldBe None
    HttpRequestGuards.nonPostGate("DELETE", ok, guarded, pushChannel = true) shouldBe None
    HttpRequestGuards.nonPostGate("DELETE", ok, guarded, pushChannel = false) shouldBe None
    // ...and refused, each with its own reason, where they are not.
    val noPush = HttpRequestGuards.nonPostGate("GET", ok, guarded, pushChannel = false)
    noPush.map(_.status) shouldBe Some(405)
    noPush.map(_.allow) shouldBe Some(List("POST", "DELETE"))
    noPush.map(_.message).getOrElse("") should include("no standalone server-to-client stream")
    val statelessGet =
      HttpRequestGuards.nonPostGate("GET", ok, guarded.copy(stateless = true), pushChannel = true)
    statelessGet.map(_.allow) shouldBe Some(List("POST"))
    statelessGet.map(_.message).getOrElse("") should include("stateless transport")
    val statelessDelete =
      HttpRequestGuards.nonPostGate("DELETE", ok, guarded.copy(stateless = true), true)
    statelessDelete.map(_.status) shouldBe Some(405)
    statelessDelete.map(_.message).getOrElse("") should include("no session to end")
    val disabled =
      HttpRequestGuards.nonPostGate("DELETE", ok, guarded.copy(disallowDelete = true), true)
    disabled.map(_.allow) shouldBe Some(List("POST", "GET"))
    disabled.map(_.message).getOrElse("") should include("DELETE is disabled")
  }

  test("nonPostGate: a GET or DELETE that declares 2026-07-28 is 405 with Allow: POST") {
    val modern = headers("host" -> "localhost:8000", "mcp-protocol-version" -> "2026-07-28")
    for method <- List("GET", "DELETE") do
      val refusal = HttpRequestGuards.nonPostGate(method, modern, guarded, pushChannel = true)
      withClue(s"$method: ") {
        refusal.map(_.status) shouldBe Some(405)
        refusal.map(_.allow) shouldBe Some(List("POST"))
        refusal.map(_.message).getOrElse("") should include(s"$method is not used by MCP 2026-07-28")
      }
  }

  test("SessionRequired (D3.14): 400 with the transport code, naming the header") {
    HttpRequestGuards.SessionRequired shouldBe Rejection(400, "mcp-session-id header is required")
    HttpRequestGuards.SessionRequired.errorCode shouldBe -32000
    HttpRequestGuards.errorCode(404) shouldBe -32001
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
