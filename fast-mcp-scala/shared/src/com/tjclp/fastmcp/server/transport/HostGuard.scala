package com.tjclp.fastmcp.server.transport

import com.tjclp.fastmcp.server.McpServerSettings

/** DNS-rebinding / CSRF protection shared by every HTTP backend (JVM, Bun, Native).
  *
  * `Host` is matched by hostname, case-insensitively, with the port stripped — so a single
  * `127.0.0.1` entry in `allowedHosts` covers whatever port the server binds; a verbatim
  * `host:port` entry also matches. An absent `Host` is allowed (HTTP/1.1 always sends it; absence
  * is not the rebinding threat this guards against).
  *
  * `Origin`, when present, is matched as a FULL origin — `scheme://host[:port]` with the port
  * defaulting per scheme (80 / 443) — never by port-stripped hostname. It is admitted when either
  *   - its normalised form is listed in `allowedOrigins`, or
  *   - its hostname is listed in `allowedHosts` AND its `host:port` equals the request's `Host`
  *     authority (a page served by the MCP host itself). The scheme is deliberately NOT compared in
  *     this rule: the listener cannot know whether TLS is terminated upstream, so `https://h:p` is
  *     admitted for `Host: h:p`. Use `allowedOrigins` for a stricter list.
  *
  * Parsing is fail-closed: `null`, empty, non-http(s) schemes, userinfo/path/query characters, an
  * empty host, or an explicit port that is not 1..65535 decimal digits all refuse the request.
  *
  * Truth table (server on `127.0.0.1:8000`, `allowedHosts = Some(Set("127.0.0.1", "localhost"))`,
  * `allowedOrigins = None`, request `Host: localhost:8000` unless stated):
  *
  * | Origin header                                                              | Result |
  * |:---------------------------------------------------------------------------|:-------|
  * | absent                                                                     | allow  |
  * | `http://localhost:8000`, `HTTP://LocalHost:8000`                           | allow  |
  * | `http://localhost:3000` (port differs)                                     | 403    |
  * | `https://localhost` (default 443 != 8000)                                  | 403    |
  * | `http://127.0.0.1:1`                                                       | 403    |
  * | `null` / empty                                                             | 403    |
  * | `http://evil.example.com`                                                  | 403    |
  * | `http://localhost:99999`, `http://localhost:`, `:abc`, `:0`                | 403    |
  * | `http://localhost:8000/x`, `http://user@localhost:8000`, `ftp://...`       | 403    |
  * | `http://127.0.0.1:8000` with `Host: localhost:8000` (cross-origin)         | 403    |
  * | `http://localhost:8000` with `Host` absent                                 | 403    |
  * | `http://localhost` with `Host: localhost` (port-less Host = default)       | allow  |
  * | `https://localhost:8000` with `Host: localhost:8000` (scheme not compared) | allow  |
  * | `http://[::1]:8000` with `Host: [::1]:8000`, `[::1]` listed                | allow  |
  * | listed in `allowedOrigins` (any/no Host)                                   | allow  |
  * | `allowedHosts = None`, `allowedOrigins = Some(...)`, Origin not listed     | 403    |
  * | both `None`                                                                | allow  |
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
    * any of `/ ? # @ \` or whitespace in the authority, an empty host, or an explicit port that is
    * not 1..65535 decimal digits (`:`, `:0`, `:abc`, `:99999`) → `None`. Only a completely absent
    * port takes the scheme default (80 / 443).
    */
  private[fastmcp] def parseOrigin(raw: String): Option[Origin] =
    val o = raw.trim.toLowerCase
    val i = o.indexOf("://")
    if o.isEmpty || o == "null" || i <= 0 then None
    else
      val scheme = o.substring(0, i)
      val authority = o.substring(i + 3)
      val default = scheme match
        case "http" => Some(80)
        case "https" => Some(443)
        case _ => None
      val badChars = authority.exists(ch => "/?#@\\".contains(ch) || ch.isWhitespace)
      if default.isEmpty || badChars || authority.isEmpty then None
      else
        val h = hostnameOf(authority)
        val portPart = authority.substring(h.length) // "" or ":<digits>" (or junk)
        val port: Option[Int] =
          if portPart.isEmpty then default
          else if portPart.startsWith(":") then parsePort(portPart.substring(1))
          else None // e.g. "[::1]junk"
        val unclosedBracket = h.startsWith("[") && !h.endsWith("]")
        if h.isEmpty || h == "[]" || unclosedBracket then None
        else port.map(p => Origin(scheme, h, p))

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
    * equal; Host without a port → hosts equal and the origin port is the scheme default; Host
    * absent → false. The SCHEME is deliberately not compared (TLS-terminating proxies).
    */
  private def sameAuthority(o: Origin, hostHeader: Option[String]): Boolean =
    hostHeader.map(_.trim.toLowerCase) match
      case None => false
      case Some(h) =>
        val hn = hostnameOf(h)
        val hp = portOf(h) // None when Host has no (valid) port
        val schemeDefault = if o.scheme == "https" then 443 else 80
        hn == o.host && hp.forall(_ == o.port) && (hp.nonEmpty || o.port == schemeDefault)

  private def hostAllowed(hostHeader: String, allowedLower: Set[String]): Boolean =
    val h = hostHeader.trim.toLowerCase
    allowedLower.contains(h) || allowedLower.contains(hostnameOf(h))

  /** Strip a trailing `:port`, preserving bracketed IPv6 literals (`[::1]:8080` -> `[::1]`). */
  private def hostnameOf(hostPort: String): String =
    if hostPort.startsWith("[") then
      val end = hostPort.indexOf(']')
      if end >= 0 then hostPort.substring(0, end + 1) else hostPort
    else
      val colon = hostPort.indexOf(':')
      if colon >= 0 then hostPort.substring(0, colon) else hostPort

  /** Explicit port of a `host[:port]` authority, bracket-aware; `None` when absent or not 1..65535
    * decimal digits.
    */
  private def portOf(hostPort: String): Option[Int] =
    val rest = hostPort.substring(hostnameOf(hostPort).length)
    if rest.startsWith(":") then parsePort(rest.substring(1)) else None

  private def parsePort(digits: String): Option[Int] =
    if digits.nonEmpty && digits.length <= 5 && digits.forall(ch => ch >= '0' && ch <= '9') then
      digits.toIntOption.filter(p => p >= 1 && p <= 65535)
    else None
