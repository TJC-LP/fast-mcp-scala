package com.tjclp.fastmcp
package server.transport

import org.scalacheck.{Gen, Prop, Test as SCTest}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import com.tjclp.fastmcp.server.McpServerSettings

/** Property coverage of [[HostGuard]] and [[HttpRequestGuards.postGate]] with ScalaCheck 1.18.1
  * (declared in `package.mill`; plain `org.scalacheck.Test.check` inside ScalaTest cases — the build
  * declares no `scalatestplus-scalacheck` bridge). Drafted in the pre-1.0.0 dogfooding session
  * (C3 step 10) and landed with TJC-2354.
  *
  * Invariants pinned here: fail-closed origin parsing (userinfo, path, query, fragment,
  * whitespace, bad ports 0 / 65536 / non-digits), case insensitivity of both headers, the `[::1]`
  * bracket rule, the `allowedOrigins` normalisation, `postGate` ordering (403 before everything),
  * charset parameters and Accept wildcards.
  *
  * Two cases are RED on main by construction (TJC-2354 turns them green) — each says so in its
  * name:
  *   - a duplicated `Host` header (joined value) is admitted when its FIRST hostname is listed
  *     (D3.7): `hostnameOf` truncates `"127.0.0.1:8000, evil.example.com"` at the first `:`;
  *   - a malformed `Host` port with no `Origin` degrades to "port-less" and is admitted although
  *     the HostGuard scaladoc and the CHANGELOG claim fail-closed on the Host side (D4.6).
  */
class HostGuardPropertyTest extends AnyFunSuite with Matchers:

  private val params = SCTest.Parameters.default.withMinSuccessfulTests(300)

  /** Run a property and turn its result into a ScalaTest assertion with the shrunk args as clue. */
  private def check(clue: String)(prop: Prop): Unit =
    val res = SCTest.check(params, prop)
    withClue(s"$clue -> ${res.status} ") {
      res.passed shouldBe true
    }

  // ---- generators ----

  private val genLabel: Gen[String] =
    for
      head <- Gen.alphaLowerChar
      n <- Gen.choose(0, 7)
      tail <- Gen.listOfN(n, Gen.frequency(8 -> Gen.alphaLowerChar, 2 -> Gen.numChar))
    yield (head :: tail).mkString

  private val genDnsHost: Gen[String] =
    Gen.choose(1, 3).flatMap(n => Gen.listOfN(n, genLabel)).map(_.mkString("."))

  private val genIpv4: Gen[String] =
    Gen.listOfN(4, Gen.choose(0, 255)).map(_.mkString("."))

  private val genIpv6: Gen[String] =
    Gen.oneOf("[::1]", "[2001:db8::1]", "[fe80::1]", "[::ffff:127.0.0.1]")

  /** Hostnames as they appear in `allowedHosts` and in a `Host` header (no port). */
  private val genHost: Gen[String] =
    Gen.frequency(
      4 -> genDnsHost,
      3 -> genIpv4,
      1 -> Gen.const("localhost"),
      1 -> Gen.const("127.0.0.1"),
      1 -> genIpv6
    )

  private val genPort: Gen[Int] = Gen.choose(1, 65535)

  private val genScheme: Gen[String] = Gen.oneOf("http", "https")

  /** Explicit ports [[HostGuard.parseOrigin]] must refuse: out of range, non-decimal, empty. */
  private val genBadPort: Gen[String] =
    Gen.oneOf("0", "65536", "99999", "-1", "abc", "8000x", "", "٨٠٠٠", "1e3", "+80", "08000000")

  /** Randomly flip the case of every letter. */
  private def mixCase(s: String): Gen[String] =
    Gen.listOfN(s.length, Gen.oneOf(true, false)).map { flags =>
      s.zip(flags).map((c, up) => if up then c.toUpper else c.toLower).mkString
    }

  /** Two distinct hostnames: the listed one and a foreign one. */
  private val genHostPair: Gen[(String, String)] =
    for
      h <- genHost
      f <- genDnsHost.suchThat(_ != h)
    yield (h, f)

  private def headers(pairs: (String, String)*): String => Option[String] =
    val m = pairs.toMap
    name => m.get(name)

  private def guardedOn(host: String): McpServerSettings =
    McpServerSettings(allowedHosts = Some(Set(host)), maxRequestBodyBytes = 64)

  // ---- HostGuard: allow side ----

  test("same-authority Origin is allowed for every listed host, port and scheme, in any letter case") {
    check("same authority allow")(Prop.forAll(genHost, genPort, genScheme) { (h, p, scheme) =>
      val hostHeader = s"$h:$p"
      val origin = s"$scheme://$h:$p"
      val plain =
        HostGuard.isAllowed(Some(hostHeader), Some(origin), Set(h), Set.empty) &&
          HostGuard.isAllowed(Some(hostHeader), None, Set(h), Set.empty)
      plain
    })
    check("same authority allow, mixed case")(
      Prop.forAll(genHost, genPort, genScheme) { (h, p, scheme) =>
        Prop.forAll(mixCase(s"$h:$p"), mixCase(s"$scheme://$h:$p"), mixCase(h)) {
          (hostHeader, origin, listed) =>
            HostGuard.isAllowed(Some(hostHeader), Some(origin), Set(listed), Set.empty)
        }
      }
    )
  }

  test("the verdict is invariant under letter case of Host, Origin and the allow-list entry") {
    check("case invariance")(
      Prop.forAll(genHostPair, genPort, genPort, genScheme) { case ((h, f), p1, p2, scheme) =>
        // A mix of matching and non-matching shapes so both outcomes are exercised.
        val hostHeader = s"$h:$p1"
        Prop.forAll(Gen.oneOf(s"$scheme://$h:$p1", s"$scheme://$h:$p2", s"$scheme://$f:$p1")) {
          origin =>
            Prop.forAll(mixCase(hostHeader), mixCase(origin), mixCase(h)) { (hh, oo, listed) =>
              HostGuard.isAllowed(Some(hh), Some(oo), Set(listed), Set.empty) ==
                HostGuard.isAllowed(Some(hostHeader), Some(origin), Set(h), Set.empty)
            }
        }
      }
    )
  }

  test("allowedOrigins entries are matched as normalised full origins (default ports, any case)") {
    check("allowedOrigins normalisation")(Prop.forAll(genHost, genScheme) { (h, scheme) =>
      val default = if scheme == "https" then 443 else 80
      Prop.forAll(mixCase(s"$scheme://$h:$default"), mixCase(s"$scheme://$h")) {
        (listedForm, sentForm) =>
          // Listed with the explicit default port, sent without it (and vice versa): one origin.
          HostGuard.isAllowed(Some("elsewhere.example:1"), Some(sentForm), Set.empty, Set(listedForm)) &&
          HostGuard.isAllowed(Some("elsewhere.example:1"), Some(listedForm), Set.empty, Set(sentForm))
      }
    })
  }

  test("IPv6 literals: a bracketed allowedHosts entry matches a bracketed Host/Origin authority") {
    check("[::1] bracket rule")(Prop.forAll(genIpv6, genPort, genScheme) { (h6, p, scheme) =>
      HostGuard.isAllowed(Some(s"$h6:$p"), Some(s"$scheme://$h6:$p"), Set(h6), Set.empty) &&
      HostGuard.isAllowed(Some(s"$h6:$p"), None, Set(h6), Set.empty) &&
      HostGuard.isAllowed(Some(h6), None, Set(h6), Set.empty)
    })
  }

  test("IPv6 literals: an UNbracketed allowedHosts entry never matches a bracketed Host (current rule; C3 soak D doc drift)") {
    // Documents today's behaviour (HostGuard.scala:167-174 keeps the brackets): operators must
    // spell IPv6 entries as "[::1]". If 1.0.1 normalises unbracketed entries, flip this case.
    check("unbracketed IPv6 entry")(Prop.forAll(genIpv6, genPort) { (h6, p) =>
      val unbracketed = h6.stripPrefix("[").stripSuffix("]")
      !HostGuard.isAllowed(Some(s"$h6:$p"), None, Set(unbracketed), Set.empty)
    })
  }

  // ---- HostGuard: refuse side ----

  test("cross-port and foreign-host origins are refused whatever the scheme") {
    check("cross-port refused")(
      Prop.forAll(genHost, genPort, genPort, genScheme) { (h, p1, p2, scheme) =>
        (p1 == p2) || !HostGuard.isAllowed(Some(s"$h:$p1"), Some(s"$scheme://$h:$p2"), Set(h), Set.empty)
      }
    )
    check("foreign host refused")(
      Prop.forAll(genHostPair, genPort, genScheme) { case ((h, f), p, scheme) =>
        !HostGuard.isAllowed(Some(s"$h:$p"), Some(s"$scheme://$f:$p"), Set(h), Set.empty)
      }
    )
  }

  test("Origin ports outside 1..65535 or non-decimal are unparseable and refused (fail-closed)") {
    check("bad origin port")(Prop.forAll(genHost, genPort, genBadPort, genScheme) {
      (h, hostPort, bad, scheme) =>
        val origin = s"$scheme://$h:$bad"
        HostGuard.parseOrigin(origin).isEmpty &&
        !HostGuard.isAllowed(Some(s"$h:$hostPort"), Some(origin), Set(h), Set.empty) &&
        // Even against a port-less Host, where a default port would otherwise be compared.
        !HostGuard.isAllowed(Some(h), Some(origin), Set(h), Set.empty)
    })
  }

  test("a malformed Host port is refused when an Origin is present (sameAuthority fails closed)") {
    check("bad host port with origin")(Prop.forAll(genHost, genBadPort, genScheme) {
      (h, bad, scheme) =>
        !HostGuard.isAllowed(Some(s"$h:$bad"), Some(s"$scheme://$h"), Set(h), Set.empty)
    })
  }

  test("DRAFT RED (D4.6): a malformed Host port with NO Origin must not degrade to port-less (HostGuard.scala:20-22 claim)") {
    // On main `hostAllowed` strips everything after the first ':' (HostGuard.scala:163-174), so
    // `Host: localhost:99999` is admitted (D4 row 23: JVM 200, Bun 400 from its URL parser).
    // Either fix the code (reject a Host whose explicit port does not parse) or reword the
    // scaladoc and CHANGELOG.md:68 and delete this case.
    check("bad host port without origin")(Prop.forAll(genHost, genBadPort.suchThat(_.nonEmpty)) {
      (h, bad) => !HostGuard.isAllowed(Some(s"$h:$bad"), None, Set(h), Set.empty)
    })
  }

  test("userinfo, path, query, fragment, whitespace, backslash and non-http schemes in Origin are refused") {
    val genMalformed: Gen[(String, Int) => String] = Gen.oneOf[(String, Int) => String](
      (h, p) => s"http://user@$h:$p",
      (h, p) => s"http://user:pw@$h:$p",
      (h, p) => s"http://$h:$p/x",
      (h, p) => s"http://$h:$p/",
      (h, p) => s"http://$h:$p?q=1",
      (h, p) => s"http://$h:$p#f",
      (h, p) => s"http://$h :$p",
      (h, p) => s"http://$h:$p\\x",
      (h, p) => s"ftp://$h:$p",
      (h, p) => s"ws://$h:$p",
      (h, p) => s"$h:$p",
      (_, _) => "null",
      (_, _) => "",
      (_, _) => "   "
    )
    check("malformed origin refused")(Prop.forAll(genHost, genPort, genMalformed) { (h, p, mk) =>
      val origin = mk(h, p)
      !HostGuard.isAllowed(Some(s"$h:$p"), Some(origin), Set(h), Set.empty) &&
      // ... and an explicit allow-list entry with the same text cannot rescue it either.
      !HostGuard.isAllowed(Some(s"$h:$p"), Some(origin), Set.empty, Set(origin))
    })
  }

  test("DRAFT RED (D3.7): a duplicated Host header (joined value) carrying a foreign hostname is refused in either order") {
    // JvmHttpBackend.gateHeader / Bun's Headers.get join repeated headers with ", ". Today
    // `hostnameOf("127.0.0.1:8000, evil.example.com")` yields "127.0.0.1" (truncated at the first
    // ':') so the listed-first order passes the guard (D3 rows 35; refuted claim CHANGELOG.md:66-68).
    check("duplicated Host refused")(Prop.forAll(genHostPair, genPort) { case ((h, f), p) =>
      val settings = guardedOn(h)
      val listedFirst = headers("host" -> s"$h:$p, $f")
      val foreignFirst = headers("host" -> s"$f, $h:$p")
      HttpRequestGuards.hostGate(listedFirst, settings).map(_.status).contains(403) &&
      HttpRequestGuards.hostGate(foreignFirst, settings).map(_.status).contains(403)
    })
  }

  // ---- postGate ----

  private val genContentType: Gen[Option[String]] = Gen.frequency(
    5 -> Gen.some(Gen.const("application/json")),
    3 -> Gen.some(Gen.const("application/json; charset=utf-8")),
    2 -> Gen.some(Gen.const("text/plain")),
    1 -> Gen.some(Gen.const("application/json; charset=iso-8859-1")),
    1 -> Gen.const(None)
  )

  private val genAccept: Gen[Option[String]] = Gen.frequency(
    4 -> Gen.some(Gen.const("application/json, text/event-stream")),
    2 -> Gen.some(Gen.const("application/json")),
    2 -> Gen.some(Gen.const("*/*")),
    1 -> Gen.some(Gen.const("text/plain")),
    1 -> Gen.const(None)
  )

  private val genContentLength: Gen[Option[String]] = Gen.frequency(
    3 -> Gen.const(None),
    3 -> Gen.some(Gen.choose(0L, 64L).map(_.toString)),
    2 -> Gen.some(Gen.choose(65L, 10_000_000L).map(_.toString)),
    1 -> Gen.some(Gen.const("garbage"))
  )

  private def gateHeaders(
      host: String,
      origin: Option[String],
      ct: Option[String],
      accept: Option[String],
      cl: Option[String]
  ): String => Option[String] =
    headers(
      (List("host" -> host) ++ origin.map("origin" -> _) ++ ct.map("content-type" -> _) ++
        accept.map("accept" -> _) ++ cl.map("content-length" -> _))*
    )

  test("postGate: a refused Host/Origin answers 403 regardless of every other header") {
    check("403 first")(
      Prop.forAll(genHostPair, genPort, genContentType, genAccept, genContentLength, Gen.oneOf(true, false)) {
        case ((h, f), p, ct, accept, cl, requireSse) =>
          val foreignHost = gateHeaders(s"$f:$p", None, ct, accept, cl)
          val foreignOrigin = gateHeaders(s"$h:$p", Some(s"http://$f:$p"), ct, accept, cl)
          HttpRequestGuards.postGate(foreignHost, guardedOn(h), requireSse).map(_.status).contains(403) &&
          HttpRequestGuards.postGate(foreignOrigin, guardedOn(h), requireSse).map(_.status).contains(403)
      }
    )
  }

  test("postGate: with the guard off no Host/Origin combination is refused") {
    check("guard off")(
      Prop.forAll(genHostPair, genPort, genAccept, Gen.oneOf(true, false)) {
        case ((h, f), p, _, requireSse) =>
          val h1 = gateHeaders(s"$f:$p", Some(s"http://$h:1"), Some("application/json"), None, None)
          HttpRequestGuards.postGate(h1, McpServerSettings(), requireSse).forall(_.status != 403)
      }
    )
  }

  test("isJsonContentType: utf-8 charset spellings pass, other charsets and media types are refused") {
    val genUtf8Param: Gen[String] =
      Gen.oneOf("charset=utf-8", "charset=UTF-8", "charset=utf8", "charset=\"UTF-8\"", "CHARSET=Utf-8")
    val genOtherCharset: Gen[String] =
      Gen.oneOf("charset=iso-8859-1", "charset=utf-16", "charset=latin1", "charset=us-ascii")
    val genSpaces: Gen[String] = Gen.oneOf("", " ", "  ")
    check("utf-8 charset passes")(
      Prop.forAll(mixCase("application/json"), genUtf8Param, genSpaces, genSpaces) {
        (mediaType, param, s1, s2) =>
          val value = s"$s1$mediaType$s2;$s1$param$s2"
          HttpRequestGuards.isJsonContentType(Some(value)) &&
          HttpRequestGuards
            .postGate(
              headers("host" -> "localhost:8000", "content-type" -> value),
              guardedOn("localhost"),
              requireSse = false
            )
            .isEmpty
      }
    )
    check("other charset is 415")(
      Prop.forAll(mixCase("application/json"), genOtherCharset) { (mediaType, param) =>
        val value = s"$mediaType; $param"
        !HttpRequestGuards.isJsonContentType(Some(value)) &&
        HttpRequestGuards
          .postGate(
            headers("host" -> "localhost:8000", "content-type" -> value),
            guardedOn("localhost"),
            requireSse = false
          )
          .map(_.status)
          .contains(415)
      }
    )
    check("other media types are 415")(
      Prop.forAll(Gen.oneOf("text/plain", "application/json-patch+json", "*/*", "application/*", "")) {
        mt =>
          !HttpRequestGuards.isJsonContentType(Some(mt)) &&
          HttpRequestGuards
            .postGate(
              headers("host" -> "localhost:8000", "content-type" -> mt),
              guardedOn("localhost"),
              requireSse = false
            )
            .map(_.status)
            .contains(415)
      }
    )
  }

  test("Accept: wildcards and listed types pass in any case; json-only fails the SSE requirement; absent passes") {
    val genJsonOk: Gen[String] = Gen.oneOf(
      "*/*",
      "application/*",
      "application/json",
      "application/json, text/event-stream",
      "text/event-stream, application/json;q=0.9"
    )
    // The JSON requirement is judged FIRST (postGate order), so an SSE-only Accept is 406 for
    // application/json before the SSE check is reached; SSE-ok values must also allow JSON.
    val genSseOk: Gen[String] = Gen.oneOf(
      "*/*",
      "application/json, text/*",
      "application/*, text/event-stream",
      "text/event-stream, application/json;q=0.9"
    )
    val jsonCt = "content-type" -> "application/json"
    for sseOnly <- List("text/event-stream", "text/*") do
      HttpRequestGuards
        .postGate(headers("host" -> "localhost:8000", jsonCt, "accept" -> sseOnly), guardedOn("localhost"), requireSse = true) shouldBe
        Some(HttpRequestGuards.Rejection(406, "Accept must allow application/json"))
    check("json accept passes")(Prop.forAll(genJsonOk.flatMap(mixCase)) { accept =>
      HttpRequestGuards
        .postGate(headers("host" -> "localhost:8000", jsonCt, "accept" -> accept), guardedOn("localhost"), requireSse = false)
        .isEmpty
    })
    check("sse accept passes")(Prop.forAll(genSseOk.flatMap(mixCase)) { accept =>
      HttpRequestGuards
        .postGate(headers("host" -> "localhost:8000", jsonCt, "accept" -> accept), guardedOn("localhost"), requireSse = true)
        .isEmpty
    })
    check("json-only accept fails the SSE requirement with 406")(
      Prop.forAll(Gen.oneOf("application/json", "application/*").flatMap(mixCase)) { accept =>
        HttpRequestGuards
          .postGate(headers("host" -> "localhost:8000", jsonCt, "accept" -> accept), guardedOn("localhost"), requireSse = true) ==
          Some(HttpRequestGuards.Rejection(406, "Accept must allow text/event-stream"))
      }
    )
    HttpRequestGuards.postGate(headers("host" -> "localhost:8000", jsonCt), guardedOn("localhost"), requireSse = true) shouldBe None
  }

  test("declared Content-Length above the cap is 413 before Accept is judged; garbage passes to the body checks") {
    check("413 before 406")(
      Prop.forAll(Gen.choose(65L, Long.MaxValue / 2), genAccept) { (declared, accept) =>
        val h = gateHeaders("localhost:8000", None, Some("application/json"), accept, Some(declared.toString))
        HttpRequestGuards.postGate(h, guardedOn("localhost"), requireSse = true) ==
          Some(HttpRequestGuards.Rejection(413, "Request body exceeds 64 bytes"))
      }
    )
    check("in-range or garbage Content-Length is never 413")(
      Prop.forAll(Gen.oneOf(Gen.choose(0L, 64L).map(_.toString), Gen.const("garbage"), Gen.const(""))) {
        cl =>
          val h = gateHeaders("localhost:8000", None, Some("application/json"), None, Some(cl))
          HttpRequestGuards.postGate(h, guardedOn("localhost"), requireSse = true).forall(_.status != 413)
      }
    )
  }
