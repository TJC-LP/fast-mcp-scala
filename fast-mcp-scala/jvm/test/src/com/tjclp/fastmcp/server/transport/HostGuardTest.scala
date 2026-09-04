package com.tjclp.fastmcp
package server.transport

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import com.tjclp.fastmcp.server.McpServerSettings

/** The [[HostGuard]] truth table (F5): `Origin` is matched as a full origin against the request's
  * `Host` authority or the explicit `allowedOrigins` list — never by port-stripped hostname.
  */
class HostGuardTest extends AnyFunSuite with Matchers:

  private val hosts = Set("127.0.0.1", "localhost")
  private val sameHost = Some("localhost:8000")

  private def allowed(host: Option[String], origin: Option[String]): Boolean =
    HostGuard.isAllowed(host, origin, hosts, Set.empty)

  test("absent Origin is allowed (non-browser client; not the guarded threat)") {
    allowed(sameHost, None) shouldBe true
    allowed(Some("127.0.0.1:8000"), None) shouldBe true
  }

  test("same authority Origin is allowed, case-insensitively") {
    allowed(sameHost, Some("http://localhost:8000")) shouldBe true
    allowed(sameHost, Some("HTTP://LocalHost:8000")) shouldBe true
    allowed(Some("LOCALHOST:8000"), Some("http://localhost:8000")) shouldBe true
  }

  test("cross-port localhost origins are refused (the F5 bypass)") {
    allowed(sameHost, Some("http://localhost:3000")) shouldBe false
    allowed(Some("127.0.0.1:8000"), Some("http://127.0.0.1:1")) shouldBe false
  }

  test("cross-scheme default port is refused: https://localhost is :443, not :8000") {
    allowed(sameHost, Some("https://localhost")) shouldBe false
    allowed(Some("localhost:8000"), Some("http://localhost")) shouldBe false // :80 != 8000
  }

  test("null and empty origins are refused (opaque origin)") {
    allowed(sameHost, Some("null")) shouldBe false
    allowed(sameHost, Some("")) shouldBe false
    allowed(sameHost, Some("   ")) shouldBe false
  }

  test("foreign hostname is refused even with a matching port") {
    allowed(sameHost, Some("http://evil.example.com")) shouldBe false
    allowed(sameHost, Some("http://evil.example.com:8000")) shouldBe false
  }

  test("invalid explicit ports are unparseable and refused (fail-closed)") {
    for bad <- List(
        "http://localhost:99999",
        "http://localhost:",
        "http://localhost:abc",
        "http://localhost:0",
        "http://localhost:-1",
        "http://localhost:8000x",
        "http://localhost:٨٠٠٠" // Unicode digits are not decimal ASCII digits
      )
    do
      withClue(bad) {
        allowed(sameHost, Some(bad)) shouldBe false
        // Even against a port-less Host (where a default port would otherwise be compared).
        allowed(Some("localhost"), Some(bad)) shouldBe false
        HostGuard.parseOrigin(bad) shouldBe None
      }
  }

  test("malformed origins and non-http schemes are refused") {
    for bad <- List(
        "http://localhost:8000/x",
        "http://user@localhost:8000",
        "http://localhost:8000?q=1",
        "http://localhost:8000#f",
        "http://local host:8000",
        "ftp://localhost:8000",
        "ws://localhost:8000",
        "localhost:8000",
        "://localhost:8000",
        "http://",
        "http://[::1",
        "http://[]:8000",
        "http://[::1]junk"
      )
    do
      withClue(bad) {
        allowed(sameHost, Some(bad)) shouldBe false
        HostGuard.parseOrigin(bad) shouldBe None
      }
  }

  test("a listed hostname on the same port but different from Host is cross-origin → refused") {
    allowed(Some("localhost:8000"), Some("http://127.0.0.1:8000")) shouldBe false
  }

  test("an Origin with an absent Host has no authority to compare → refused") {
    allowed(None, Some("http://localhost:8000")) shouldBe false
  }

  test("a port-less Host accepts only the scheme-default origin port") {
    allowed(Some("localhost"), Some("http://localhost")) shouldBe true
    allowed(Some("localhost"), Some("http://localhost:80")) shouldBe true
    allowed(Some("localhost"), Some("https://localhost")) shouldBe true // :443 vs port-less Host
    allowed(Some("localhost"), Some("http://localhost:8000")) shouldBe false
  }

  test("scheme is not compared in the same-authority rule (TLS-terminating proxy) — documented") {
    allowed(sameHost, Some("https://localhost:8000")) shouldBe true
  }

  test("bracketed IPv6 literals are handled on both sides") {
    val v6 = Set("[::1]")
    HostGuard.isAllowed(Some("[::1]:8000"), Some("http://[::1]:8000"), v6, Set.empty) shouldBe true
    HostGuard.isAllowed(Some("[::1]:8000"), Some("http://[::1]:8001"), v6, Set.empty) shouldBe false
    HostGuard.isAllowed(Some("[::1]"), Some("http://[::1]"), v6, Set.empty) shouldBe true
  }

  test("a verbatim host:port entry in allowedHosts also admits the Origin hostname") {
    HostGuard.isAllowed(
      Some("localhost:8000"),
      Some("http://localhost:8000"),
      Set("localhost:8000"),
      Set.empty
    ) shouldBe true
    HostGuard.isAllowed(
      Some("localhost:8000"),
      Some("http://localhost:3000"),
      Set("localhost:8000"),
      Set.empty
    ) shouldBe false
  }

  test("explicit allowedOrigins entries win regardless of Host, after normalisation") {
    val origins = Set("https://app.example.com:443", "http://localhost:3000")
    HostGuard.isAllowed(sameHost, Some("https://app.example.com"), hosts, origins) shouldBe true
    HostGuard.isAllowed(None, Some("https://app.example.com"), hosts, origins) shouldBe true
    HostGuard.isAllowed(
      Some("127.0.0.1:8000"),
      Some("HTTPS://App.Example.com:443"),
      hosts,
      origins
    ) shouldBe true
    HostGuard.isAllowed(sameHost, Some("http://localhost:3000"), hosts, origins) shouldBe true
    // Not listed and not same-authority → still refused.
    HostGuard.isAllowed(sameHost, Some("http://localhost:3001"), hosts, origins) shouldBe false
    HostGuard.isAllowed(sameHost, Some("http://app.example.com"), hosts, origins) shouldBe false
    // The Host allow-list still applies alongside allowedOrigins.
    HostGuard.isAllowed(
      Some("evil.example.com"),
      Some("https://app.example.com"),
      hosts,
      origins
    ) shouldBe false
  }

  test("allowedHosts None + allowedOrigins Some: Host unchecked, Origin must be listed") {
    val origins = Set("https://app.example.com")
    HostGuard.isAllowed(Some("anything.example"), None, Set.empty, origins) shouldBe true
    HostGuard.isAllowed(
      Some("anything.example"),
      Some("https://app.example.com"),
      Set.empty,
      origins
    ) shouldBe true
    HostGuard.isAllowed(
      Some("anything.example"),
      Some("http://localhost:3000"),
      Set.empty,
      origins
    ) shouldBe false
    HostGuard.isAllowed(Some("localhost:8000"), Some("null"), Set.empty, origins) shouldBe false
  }

  test("both lists empty disables the guard entirely") {
    HostGuard.isAllowed(
      Some("evil.example.com"),
      Some("http://evil.example.com"),
      Set.empty,
      Set.empty
    ) shouldBe true
    HostGuard.isAllowed(Some("x"), Some("null"), Set.empty, Set.empty) shouldBe true
  }

  test("Host matching is unchanged: hostname with port stripped, verbatim host:port, absent ok") {
    allowed(Some("127.0.0.1:65000"), None) shouldBe true
    allowed(Some("127.0.0.1"), None) shouldBe true
    allowed(None, None) shouldBe true
    allowed(Some("evil.example.com"), None) shouldBe false
    allowed(Some("evil.example.com:8000"), None) shouldBe false
  }

  test("settings overload wires allowedHosts and allowedOrigins") {
    val settings = McpServerSettings(
      allowedHosts = Some(hosts),
      allowedOrigins = Some(Set("https://app.example.com"))
    )
    HostGuard.isAllowed(sameHost, Some("https://app.example.com"), settings) shouldBe true
    HostGuard.isAllowed(sameHost, Some("http://localhost:8000"), settings) shouldBe true
    HostGuard.isAllowed(sameHost, Some("http://localhost:3000"), settings) shouldBe false
    HostGuard.isAllowed(sameHost, Some("http://localhost:3000"), McpServerSettings()) shouldBe true
  }

  test("deprecated 3-arg overload keeps Host semantics and full-origin matching, no allowedOrigins") {
    @annotation.nowarn("cat=deprecation")
    def legacy(host: Option[String], origin: Option[String]): Boolean =
      HostGuard.isAllowed(host, origin, hosts)
    legacy(sameHost, Some("http://localhost:8000")) shouldBe true
    legacy(sameHost, Some("http://localhost:3000")) shouldBe false
    legacy(Some("evil.example.com"), None) shouldBe false
  }

  test("parseOrigin normalises scheme/host case and defaults the port per scheme") {
    HostGuard.parseOrigin("HTTP://LocalHost") shouldBe Some(HostGuard.Origin("http", "localhost", 80))
    HostGuard.parseOrigin("https://App.Example.com") shouldBe
      Some(HostGuard.Origin("https", "app.example.com", 443))
    HostGuard.parseOrigin(" https://app.example.com:8443 ") shouldBe
      Some(HostGuard.Origin("https", "app.example.com", 8443))
    HostGuard.parseOrigin("http://[::1]:8000") shouldBe Some(HostGuard.Origin("http", "[::1]", 8000))
    HostGuard.parseOrigin("http://[::1]") shouldBe Some(HostGuard.Origin("http", "[::1]", 80))
    HostGuard.parseOrigin("null") shouldBe None
    HostGuard.parseOrigin("") shouldBe None
  }

  test("invalidOrigins lists exactly the unparseable allow-list entries") {
    HostGuard.invalidOrigins(
      Set("https://app.example.com", "https//app.example.com", "app.example.com", "http://x:0")
    ) shouldBe Set("https//app.example.com", "app.example.com", "http://x:0")
    HostGuard.invalidOrigins(Set("http://localhost:3000")) shouldBe Set.empty
  }
