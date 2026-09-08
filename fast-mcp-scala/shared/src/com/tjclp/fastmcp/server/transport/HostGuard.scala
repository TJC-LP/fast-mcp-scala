package com.tjclp.fastmcp.server.transport

import com.tjclp.fastmcp.server.McpServerSettings

/** DNS-rebinding / CSRF protection shared by every HTTP backend (JVM, Bun, Native).
  *
  * `Host` is parsed as ONE `host[:port]` authority (fail-closed, see below) and then matched
  * case-insensitively either verbatim (a `host:port` entry) or by hostname with the port stripped —
  * so a single `127.0.0.1` entry in `allowedHosts` covers whatever port the server binds. IPv6
  * literals keep their brackets on both sides: list them as `"[::1]"`, never `"::1"`. An absent
  * `Host` is allowed (HTTP/1.1 always sends it; absence is not the rebinding threat this guards
  * against).
  *
  * `Origin`, when present, is matched as a FULL origin — `scheme://host[:port]` with the port
  * defaulting per scheme (80 / 443) — never by port-stripped hostname. It is admitted when either
  *   - its normalised form is listed in `allowedOrigins`, or
  *   - its hostname is listed in `allowedHosts` AND its `host:port` equals the request's `Host`
  *     authority (a page served by the MCP host itself). The scheme is deliberately NOT compared in
  *     this rule: the listener cannot know whether TLS is terminated upstream, so `https://h:p` is
  *     admitted for `Host: h:p`. Use `allowedOrigins` for a stricter list.
  *
  * Parsing is fail-closed on BOTH headers, each judged on its own (a malformed `Host` is refused
  * whether or not an `Origin` is present): `null`, empty, a non-http(s) scheme (Origin), a slash,
  * `?`, `#`, `@`, backslash or whitespace in the authority, a comma in the authority (the
  * comma-joined value of a header sent more than once, or any second authority — never matched on
  * its first value), an empty or unbracketed IPv6 host, or an explicit port that is not 1..65535
  * decimal digits all refuse the request; a malformed `Host` port never degrades to "port-less". A
  * genuinely port-less `Host` (a listener on 80 or 443) admits the origin whose port is ITS scheme
  * default, i.e. both `http://h` and `https://h`; use `allowedOrigins` for a stricter list.
  *
  * Truth table (server on `127.0.0.1:8000`, `allowedHosts = Some(Set("127.0.0.1", "localhost"))`,
  * `allowedOrigins = None`, request `Host: localhost:8000` unless stated):
  *
  * | Origin header                                                               | Result |
  * |:----------------------------------------------------------------------------|:-------|
  * | absent                                                                      | allow  |
  * | `http://localhost:8000`, `HTTP://LocalHost:8000`                            | allow  |
  * | `http://localhost:3000` (port differs)                                      | 403    |
  * | `https://localhost` (default 443 != 8000)                                   | 403    |
  * | `http://127.0.0.1:1`                                                        | 403    |
  * | `null` / empty                                                              | 403    |
  * | `http://evil.example.com`                                                   | 403    |
  * | `http://localhost:99999`, `http://localhost:`, `:abc`, `:0`                 | 403    |
  * | `http://localhost:8000/x`, `http://user@localhost:8000`, `ftp://...`        | 403    |
  * | `http://127.0.0.1:8000` with `Host: localhost:8000` (cross-origin)          | 403    |
  * | `http://localhost:8000` with `Host` absent                                  | 403    |
  * | `http://localhost` with `Host: localhost` (port-less Host = default)        | allow  |
  * | `https://localhost` with `Host: localhost` (port-less Host admits :443 too) | allow  |
  * | `http://localhost` with `Host: localhost:99999` (malformed Host port)       | 403    |
  * | absent, with `Host: localhost:99999` (malformed Host port, no Origin)       | 403    |
  * | absent, with `Host: localhost:8000, evil.example.com` (multi-valued Host)   | 403    |
  * | absent, with `Host: evil.example.com, localhost:8000`                       | 403    |
  * | `https://localhost:8000` with `Host: localhost:8000` (scheme not compared)  | allow  |
  * | `http://[::1]:8000` with `Host: [::1]:8000`, `[::1]` listed                 | allow  |
  * | absent, with `Host: [::1]:8000`, `::1` listed (unbracketed entry)           | 403    |
  * | listed in `allowedOrigins` (any/no Host)                                    | allow  |
  * | `allowedHosts = None`, `allowedOrigins = Some(...)`, Origin not listed      | 403    |
  * | both `None`                                                                 | allow  |
  *
  * The guard closes the browser CSRF / DNS-rebinding path only; a non-browser client that omits
  * `Origin` is not authenticated by it.
  */
object HostGuard:

  /** Normalised origin: lowercase scheme and host, explicit port (default 80/443). The host keeps
    * IPv6 brackets.
    */
  private[fastmcp] final case class Origin(scheme: String, host: String, port: Int)

  /** Backend entry point: honours both `allowedHosts` and `allowedOrigins`. */
  def isAllowed(
      host: Option[String],
      origin: Option[String],
      settings: McpServerSettings
  ): Boolean =
    isAllowed(
      host,
      origin,
      settings.allowedHosts.getOrElse(Set.empty),
      settings.allowedOrigins.getOrElse(Set.empty)
    )

  @deprecated(
    "Origin is now matched as a full origin; pass the McpServerSettings so allowedOrigins is honoured",
    "1.0.0-RC4"
  )
  def isAllowed(host: Option[String], origin: Option[String], allowed: Set[String]): Boolean =
    isAllowed(host, origin, allowed, Set.empty)

  /** Raw-set core (tests). `allowedOrigins` entries are full origins; unparseable entries are
    * ignored here — `HttpRequestGuards.validateSettings` rejects them at startup.
    */
  private[fastmcp] def isAllowed(
      host: Option[String],
      origin: Option[String],
      allowedHosts: Set[String],
      allowedOrigins: Set[String]
  ): Boolean =
    if allowedHosts.isEmpty && allowedOrigins.isEmpty then true
    else
      val hostsLower = allowedHosts.map(_.trim.toLowerCase)
      val originsNorm = allowedOrigins.flatMap(parseOrigin)
      val hostOk = hostsLower.isEmpty || host.forall(h => hostAllowed(h, hostsLower))
      hostOk && origin.forall(o => originAllowed(o, host, hostsLower, originsNorm))

  /** Entries of `allowedOrigins` that do not parse (for startup validation / diagnostics). */
  private[fastmcp] def invalidOrigins(allowedOrigins: Set[String]): Set[String] =
    allowedOrigins.filter(parseOrigin(_).isEmpty)

  /** `scheme://host[:port]` → [[Origin]]. FAIL-CLOSED: `null`, empty, scheme not in {http, https},
    * or an authority [[parseAuthority]] refuses → `None`. Only a completely absent port takes the
    * scheme default (80 / 443).
    */
  private[fastmcp] def parseOrigin(raw: String): Option[Origin] =
    val o = raw.trim.toLowerCase
    val i = o.indexOf("://")
    if o.isEmpty || o == "null" || i <= 0 then None
    else
      val scheme = o.substring(0, i)
      val default = scheme match
        case "http" => Some(80)
        case "https" => Some(443)
        case _ => None
      default.flatMap { d =>
        parseAuthority(o.substring(i + 3)).map((h, p) => Origin(scheme, h, p.getOrElse(d)))
      }

  /** One `host[:port]` authority (already trimmed and lower-cased by the caller) → `(host, explicit
    * port)`; `Some((h, None))` is a genuinely port-less authority. FAIL-CLOSED: empty; a comma (the
    * comma-joined value of a header sent more than once), slash, `?`, `#`, `@`, backslash or
    * whitespace anywhere; an empty host, `[]`, an unclosed bracket, junk after a bracketed literal,
    * an unbracketed IPv6 literal (its first `:` reads as the port separator); or an explicit port
    * that is not 1..65535 decimal digits (`:`, `:0`, `:abc`, `:99999`, `:8000:1`) → `None`.
    */
  private[fastmcp] def parseAuthority(authority: String): Option[(String, Option[Int])] =
    val badChars = authority.exists(ch => ",/?#@\\".contains(ch) || ch.isWhitespace)
    if authority.isEmpty || badChars then None
    else
      val h = hostnameOf(authority)
      val portPart = authority.substring(h.length) // "" or ":<digits>" (or junk)
      val unclosedBracket = h.startsWith("[") && !h.endsWith("]")
      if h.isEmpty || h == "[]" || unclosedBracket then None
      else if portPart.isEmpty then Some((h, None))
      else if portPart.startsWith(":") then parsePort(portPart.substring(1)).map(p => (h, Some(p)))
      else None // e.g. "[::1]junk"

  private def originAllowed(
      rawOrigin: String,
      hostHeader: Option[String],
      hostsLower: Set[String],
      originsNorm: Set[Origin]
  ): Boolean =
    parseOrigin(rawOrigin) match
      case None => false // null / empty / malformed
      case Some(o) if originsNorm.contains(o) => true // explicit allow-list
      case Some(o) =>
        hostsLower.nonEmpty &&
        (hostsLower.contains(o.host) || hostsLower.contains(s"${o.host}:${o.port}")) &&
        sameAuthority(o, hostHeader)

  /** Origin host:port must equal the request's Host authority. Host with an explicit port → both
    * equal; Host without a port → hosts equal and the origin port is the ORIGIN's scheme default
    * (so a port-less `Host: h` admits both `http://h` and `https://h`); Host that
    * [[parseAuthority]] refuses (`h:99999`, `h:abc`, `h:`, `h:p, other`) → false (fail-closed,
    * mirroring [[parseOrigin]]); Host absent → false. The SCHEME is deliberately not compared
    * (TLS-terminating proxies).
    */
  private def sameAuthority(o: Origin, hostHeader: Option[String]): Boolean =
    hostHeader.map(_.trim.toLowerCase).flatMap(parseAuthority) match
      case None => false
      case Some((hn, port)) =>
        val schemeDefault = if o.scheme == "https" then 443 else 80
        hn == o.host && port.getOrElse(schemeDefault) == o.port

  /** Fail-closed `Host` match, judged without any `Origin`: the value must parse as ONE
    * `host[:port]` authority (a `", "`-joined multi-valued header, a second authority or a
    * malformed port is refused outright — never matched on its first hostname), and then either the
    * verbatim `host:port` or the bare hostname must be listed.
    */
  private def hostAllowed(hostHeader: String, allowedLower: Set[String]): Boolean =
    val h = hostHeader.trim.toLowerCase
    parseAuthority(h).exists((hn, _) => allowedLower.contains(h) || allowedLower.contains(hn))

  /** Strip a trailing `:port`, preserving bracketed IPv6 literals (`[::1]:8080` -> `[::1]`). */
  private def hostnameOf(hostPort: String): String =
    if hostPort.startsWith("[") then
      val end = hostPort.indexOf(']')
      if end >= 0 then hostPort.substring(0, end + 1) else hostPort
    else
      val colon = hostPort.indexOf(':')
      if colon >= 0 then hostPort.substring(0, colon) else hostPort

  private def parsePort(digits: String): Option[Int] =
    if digits.nonEmpty && digits.length <= 5 && digits.forall(ch => ch >= '0' && ch <= '9') then
      digits.toIntOption.filter(p => p >= 1 && p <= 65535)
    else None
