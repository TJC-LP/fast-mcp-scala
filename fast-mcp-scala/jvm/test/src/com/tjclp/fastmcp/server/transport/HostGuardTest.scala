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

  test(
    "a port-less Host accepts only the origin's scheme-default port (80 for http, 443 for https)"
  ) {
    allowed(Some("localhost"), Some("http://localhost")) shouldBe true
    allowed(Some("localhost"), Some("http://localhost:80")) shouldBe true
    // Documented allow row: a port-less Host admits BOTH scheme defaults (listener on 80 or 443).
    allowed(Some("localhost"), Some("https://localhost")) shouldBe true
    allowed(Some("localhost"), Some("https://localhost:443")) shouldBe true
    allowed(Some("localhost"), Some("http://localhost:8000")) shouldBe false
    allowed(Some("localhost"), Some("http://localhost:443")) shouldBe false
    allowed(Some("localhost"), Some("https://localhost:80")) shouldBe false
  }

  test("a Host with a malformed port is fail-closed, never treated as port-less") {
    for badHost <- List(
        "localhost:99999",
        "localhost:abc",
        "localhost:",
        "localhost:0",
        "localhost:-1",
        "localhost:8000x",
        "localhost:٨٠٠٠"
      )
    do
      withClue(badHost) {
        allowed(Some(badHost), Some("http://localhost")) shouldBe false
        allowed(Some(badHost), Some("https://localhost")) shouldBe false
        allowed(Some(badHost), Some("http://localhost:8000")) shouldBe false
        allowed(Some(badHost), Some("http://localhost:99999")) shouldBe false
        // Host-only matching (no Origin) is fail-closed too: a listed hostname does not rescue a
        // port that does not parse (TJC-2354; used to be admitted as "port-less").
        allowed(Some(badHost), None) shouldBe false
      }
  }

  test("a multi-valued Host (the \", \"-joined duplicate header) is refused in either order") {
    // JvmHttpBackend.gateHeader / Http1.Head.headerJoined / Bun's Headers.get join repeated headers with ", ". The joined
    // value is one malformed authority, never matched on its first hostname (TJC-2354).
    allowed(Some("localhost:8000, evil.example.com"), None) shouldBe false
    allowed(Some("evil.example.com, localhost:8000"), None) shouldBe false
    allowed(Some("localhost:8000,evil.example.com"), None) shouldBe false
    allowed(Some("localhost:8000, localhost:8000"), None) shouldBe false
    allowed(Some("localhost, evil.example.com"), None) shouldBe false
    allowed(Some("localhost:8000, evil.example.com"), Some("http://localhost:8000")) shouldBe false
    // A single well-formed authority still passes, with or without a port.
    allowed(Some("localhost:8000"), None) shouldBe true
    allowed(Some("localhost"), None) shouldBe true
  }

  test("parseAuthority: one host[:port], brackets preserved, everything else fail-closed") {
    HostGuard.parseAuthority("localhost") shouldBe Some(("localhost", None))
    HostGuard.parseAuthority("localhost:8000") shouldBe Some(("localhost", Some(8000)))
    HostGuard.parseAuthority("127.0.0.1:65535") shouldBe Some(("127.0.0.1", Some(65535)))
    HostGuard.parseAuthority("[::1]") shouldBe Some(("[::1]", None))
    HostGuard.parseAuthority("[::1]:8080") shouldBe Some(("[::1]", Some(8080)))
    HostGuard.parseAuthority("[2001:db8::1]:1") shouldBe Some(("[2001:db8::1]", Some(1)))
    for bad <- List(
        "",
        "::1", // unbracketed IPv6: the first ':' reads as the port separator
        "fe80::1",
        "2001:db8::1",
        "[::1",
        "[]",
        "[]:8000",
        "[::1]junk",
        "localhost:",
        "localhost:0",
        "localhost:65536",
        "localhost:99999",
        "localhost:abc",
        "localhost:8000x",
        "localhost:8000:9000",
        "localhost:8000, evil.example.com",
        "evil.example.com, localhost:8000",
        "localhost:8000,x",
        "local host:8000",
        "localhost :8000",
        "user@localhost:8000",
        "localhost:8000/x",
        "localhost:8000?q",
        "localhost:8000#f",
        "localhost:8000\\x"
      )
    do withClue(bad)(HostGuard.parseAuthority(bad) shouldBe None)
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
    // Nothing listed: the automatic loopback guard refuses the cross-port page; only the explicit
    // opt-out (or a non-loopback bind, which warns at startup) admits it.
    HostGuard.isAllowed(sameHost, Some("http://localhost:3000"), McpServerSettings()) shouldBe false
    HostGuard.isAllowed(
      sameHost,
      Some("http://localhost:3000"),
      McpServerSettings(dnsRebindingProtection = false)
    ) shouldBe true
    HostGuard.isAllowed(
      sameHost,
      Some("http://localhost:3000"),
      McpServerSettings(host = "0.0.0.0")
    ) shouldBe true
  }

  // ---- the guard a settings value resolves to (automatic loopback default, opt-out) ----

  private def verdict(settings: McpServerSettings, host: String, origin: Option[String] = None) =
    HostGuard.isAllowed(Some(host), origin, settings)

  test("loopbackHostNames: loopback bind addresses in every spelling, with the bound literal") {
    val base = Set("localhost", "127.0.0.1", "[::1]")
    HostGuard.loopbackHostNames("127.0.0.1") shouldBe Some(base)
    HostGuard.loopbackHostNames("localhost") shouldBe Some(base)
    HostGuard.loopbackHostNames(" LocalHost ") shouldBe Some(base)
    HostGuard.loopbackHostNames("::1") shouldBe Some(base)
    HostGuard.loopbackHostNames("[::1]") shouldBe Some(base)
    HostGuard.loopbackHostNames("::1%lo0") shouldBe Some(base)
    HostGuard.loopbackHostNames("127.0.0.2") shouldBe Some(base + "127.0.0.2")
    HostGuard.loopbackHostNames("127.255.255.254") shouldBe Some(base + "127.255.255.254")
    HostGuard.loopbackHostNames("0:0:0:0:0:0:0:1") shouldBe Some(base + "[0:0:0:0:0:0:0:1]")
    HostGuard.loopbackHostNames("0000:0000:0000:0000:0000:0000:0000:0001").isDefined shouldBe true
    HostGuard.loopbackHostNames("0::1").isDefined shouldBe true
    HostGuard.loopbackHostNames("::0:1").isDefined shouldBe true
    HostGuard.loopbackHostNames("::ffff:127.0.0.1") shouldBe
      Some(base + "[::ffff:127.0.0.1]")
  }

  test("loopbackHostNames: wildcards, LAN and public addresses, names and junk are not loopback") {
    for notLoopback <- List(
        "0.0.0.0",
        "::",
        "[::]",
        "",
        "  ",
        "192.168.1.20",
        "10.0.0.1",
        "128.0.0.1",
        "1.127.0.0",
        "127.0.0",
        "127.0.0.1.",
        "127.0.0.256",
        "127.0.0.1.evil.example.com",
        "127.0.0.x",
        "localhost.evil.example.com",
        "localhost.",
        "evil.example.com",
        "mcp.internal",
        "::2",
        "1::1",
        "::1::",
        ":::1",
        "0:0:0:0:0:0:0:0:1",
        "::ffff:10.0.0.1",
        "::10000",
        "::g",
        "::\uff11", // a fullwidth digit one is not an ASCII hex digit
        "127.0.0.\uff11"
      )
    do
      withClue(s"'$notLoopback'") {
        HostGuard.loopbackHostNames(notLoopback) shouldBe None
        HostGuard.isLoopbackBind(notLoopback) shouldBe false
      }
  }

  test("policy: default settings resolve to the automatic loopback guard") {
    val policy = McpServerSettings().hostGuardPolicy
    policy.mode shouldBe HostGuard.Mode.AutomaticLoopback
    policy.enforced shouldBe true
    policy.hosts shouldBe HostGuard.LoopbackNames
    policy.origins shouldBe Set.empty
    policy.sameAuthorityOrigins shouldBe true
    verdict(McpServerSettings(), "127.0.0.1:8000") shouldBe true
    verdict(McpServerSettings(), "localhost:54321") shouldBe true // port is not compared
    verdict(McpServerSettings(), "[::1]:8000") shouldBe true
    verdict(McpServerSettings(), "evil.example.com:8000") shouldBe false
    verdict(McpServerSettings(), "127.0.0.2:8000") shouldBe false // not this listener's address
    verdict(McpServerSettings(), "localhost:8000", Some("http://localhost:8000")) shouldBe true
    verdict(McpServerSettings(), "localhost:8000", Some("http://evil.example.com")) shouldBe false
    HostGuard.isAllowed(None, None, McpServerSettings()) shouldBe true // non-browser client
    HostGuard.isAllowed(None, Some("http://evil.example.com"), McpServerSettings()) shouldBe false
  }

  test("policy: an explicit allowedHosts replaces the automatic names, on any bind address") {
    val proxied = McpServerSettings(allowedHosts = Some(Set("mcp.example.com")))
    proxied.hostGuardPolicy.mode shouldBe HostGuard.Mode.Explicit
    verdict(proxied, "mcp.example.com") shouldBe true
    verdict(proxied, "localhost:8000") shouldBe false // exactly the listed names, as before
    val public = McpServerSettings(host = "0.0.0.0", allowedHosts = Some(Set("mcp.example.com")))
    public.hostGuardPolicy.mode shouldBe HostGuard.Mode.Explicit
    verdict(public, "mcp.example.com") shouldBe true
    verdict(public, "evil.example.com") shouldBe false
  }

  test("policy: allowedOrigins alone keeps the automatic Host names on a loopback bind") {
    val settings = McpServerSettings(allowedOrigins = Some(Set("http://localhost:5173")))
    settings.hostGuardPolicy.mode shouldBe HostGuard.Mode.AutomaticLoopback
    verdict(settings, "localhost:8000", Some("http://localhost:5173")) shouldBe true
    verdict(settings, "localhost:8000", Some("http://localhost:3000")) shouldBe false
    // As before the automatic guard: with origins alone, only the listed origins pass — the
    // request's own authority included. The automatic names add the Host check, nothing else.
    verdict(settings, "localhost:8000", Some("http://localhost:8000")) shouldBe false
    verdict(settings, "localhost:8000") shouldBe true
    verdict(settings, "evil.example.com:8000") shouldBe false
    verdict(settings, "evil.example.com:8000", Some("http://localhost:5173")) shouldBe false
    // On a non-loopback bind there are no automatic names: Origin-only, exactly as before.
    val wildcard = settings.copy(host = "0.0.0.0")
    wildcard.hostGuardPolicy.mode shouldBe HostGuard.Mode.Explicit
    verdict(wildcard, "anything.example") shouldBe true
    verdict(wildcard, "anything.example", Some("http://localhost:5173")) shouldBe true
    verdict(wildcard, "anything.example", Some("http://evil.example.com")) shouldBe false
  }

  test("policy: Some(Set.empty) is not an opt-out — it reads as None (automatic)") {
    val settings = McpServerSettings(allowedHosts = Some(Set.empty), allowedOrigins = Some(Set.empty))
    settings.hostGuardPolicy.mode shouldBe HostGuard.Mode.AutomaticLoopback
    verdict(settings, "evil.example.com") shouldBe false
    verdict(settings, "localhost:8000") shouldBe true
  }

  test("policy: a non-loopback bind with nothing listed is unprotected (and flagged as such)") {
    for bind <- List("0.0.0.0", "::", "192.168.1.20", "mcp.internal") do
      withClue(s"host = $bind") {
        val settings = McpServerSettings(host = bind)
        settings.hostGuardPolicy.mode shouldBe HostGuard.Mode.Unprotected
        settings.hostGuardPolicy.enforced shouldBe false
        verdict(settings, "evil.example.com", Some("http://evil.example.com")) shouldBe true
      }
  }

  test("policy: dnsRebindingProtection = false is the explicit opt-out") {
    val off = McpServerSettings(dnsRebindingProtection = false)
    off.hostGuardPolicy.mode shouldBe HostGuard.Mode.Disabled
    off.hostGuardPolicy.enforced shouldBe false
    verdict(off, "evil.example.com", Some("http://evil.example.com")) shouldBe true
    verdict(off, "evil.example.com", Some("null")) shouldBe true
    McpServerSettings(host = "0.0.0.0", dnsRebindingProtection = false).hostGuardPolicy.mode shouldBe
      HostGuard.Mode.Disabled
    // The default keeps the guard.
    McpServerSettings().dnsRebindingProtection shouldBe true
  }

  test("policy: the opt-out never silently drops an explicit list (startup refuses the pair)") {
    val hostsListed =
      McpServerSettings(allowedHosts = Some(Set("localhost")), dnsRebindingProtection = false)
    verdict(hostsListed, "evil.example.com") shouldBe false
    verdict(hostsListed, "localhost:8000") shouldBe true
    val originsListed = McpServerSettings(
      allowedOrigins = Some(Set("https://app.example.com")),
      dnsRebindingProtection = false
    )
    verdict(originsListed, "x.example", Some("http://evil.example.com")) shouldBe false
    verdict(originsListed, "x.example", Some("https://app.example.com")) shouldBe true
  }

  test("the resolved policy is computed once per settings value and follows copy") {
    val settings = McpServerSettings()
    settings.hostGuardPolicy should be theSameInstanceAs settings.hostGuardPolicy
    settings.copy(host = "0.0.0.0").hostGuardPolicy.mode shouldBe HostGuard.Mode.Unprotected
    settings.copy(dnsRebindingProtection = false).hostGuardPolicy.mode shouldBe
      HostGuard.Mode.Disabled
    // Not part of the case-class identity.
    settings shouldBe McpServerSettings()
  }

  test("parseOrigin normalises scheme/host case and defaults the port per scheme") {
    HostGuard.parseOrigin("HTTP://LocalHost") shouldBe Some(
      HostGuard.Origin("http", "localhost", 80)
    )
    HostGuard.parseOrigin("https://App.Example.com") shouldBe
      Some(HostGuard.Origin("https", "app.example.com", 443))
    HostGuard.parseOrigin(" https://app.example.com:8443 ") shouldBe
      Some(HostGuard.Origin("https", "app.example.com", 8443))
    HostGuard.parseOrigin("http://[::1]:8000") shouldBe Some(
      HostGuard.Origin("http", "[::1]", 8000)
    )
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
