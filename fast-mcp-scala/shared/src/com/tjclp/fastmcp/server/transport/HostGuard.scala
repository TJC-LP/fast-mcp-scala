package com.tjclp.fastmcp.server.transport

import com.tjclp.fastmcp.server.McpServerSettings

/** DNS-rebinding / CSRF protection shared by every HTTP backend (JVM, Bun, Native).
  *
  * Which names are allowed ([[HostGuard.policy]], resolved once per `McpServerSettings`):
  *
  * | `allowedHosts` | `allowedOrigins` | bind address (`host`) | guard                                           |
  * |:---------------|:-----------------|:----------------------|:------------------------------------------------|
  * | `Some(names)`  | any              | any                   | on, exactly `names`                             |
  * | `None`         | `None`           | loopback              | on, AUTOMATIC: the loopback names below         |
  * | `None`         | `Some(origins)`  | loopback              | AUTOMATIC `Host` names; `Origin` must be listed |
  * | `None`         | `Some(origins)`  | not loopback          | `Origin` must be listed; `Host` unchecked       |
  * | `None`         | `None`           | not loopback          | OFF, with a WARN at startup                     |
  * | `None`         | `None`           | any, opt-out set      | OFF (`dnsRebindingProtection = false`)          |
  *
  * A loopback bind address is `localhost`, any `127.0.0.0/8` literal, or `::1` (bracketed or not,
  * compressed or not, IPv4-mapped included). The automatic names are `localhost`, `127.0.0.1`,
  * `[::1]` and the bound literal itself, on ANY port: the port is not compared, because a forwarded
  * port (`ssh -L`, an IDE port-forward) reaches a loopback listener under another one, and a
  * rebinding page is told apart by its hostname, never by its port. Binding loopback is not by
  * itself a defence — a page in a local browser can still reach the listener; this guard is what
  * refuses it. A host NAME that merely resolves to loopback (an `/etc/hosts` alias) is not
  * recognised: list it in `allowedHosts`.
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
  * | both `None` (automatic, loopback bind): `Host: evil.example.com`            | 403    |
  * | both `None` on a non-loopback bind, or `dnsRebindingProtection = false`     | allow  |
  *
  * The guard closes the browser CSRF / DNS-rebinding path only; a non-browser client that omits
  * `Origin` is not authenticated by it.
  */
object HostGuard:

  /** Normalised origin: lowercase scheme and host, explicit port (default 80/443). The host keeps
    * IPv6 brackets.
    */
  private[fastmcp] final case class Origin(scheme: String, host: String, port: Int)

  /** How a [[Policy]] came about — what startup diagnostics report. */
  private[fastmcp] enum Mode:

    /** `allowedHosts` and/or `allowedOrigins` were listed. */
    case Explicit

    /** Nothing listed on a loopback bind: the loopback names are allowed (the default). */
    case AutomaticLoopback

    /** Nothing listed on a non-loopback bind: nothing is checked, and startup warns. */
    case Unprotected

    /** `dnsRebindingProtection = false`: nothing is checked, by the operator's choice. */
    case Disabled

  /** The guard as resolved from one `McpServerSettings`: lower-cased `Host` names (empty = `Host`
    * is not checked), normalised explicit origins, whether an `Origin` equal to the request's own
    * `Host` authority is admitted (the same-authority rule), and whether anything is enforced at
    * all. Immutable and built once per settings value (`McpServerSettings.hostGuardPolicy`), so a
    * request only parses its own two headers.
    */
  private[fastmcp] final class Policy private[HostGuard] (
      val hosts: Set[String],
      val origins: Set[Origin],
      val sameAuthorityOrigins: Boolean,
      val enforced: Boolean,
      val mode: Mode
  ):
    private val originHosts: Set[String] = if sameAuthorityOrigins then hosts else Set.empty

    def allows(host: Option[String], origin: Option[String]): Boolean =
      !enforced || {
        val hostOk = hosts.isEmpty || host.forall(h => hostAllowed(h, hosts))
        hostOk && origin.forall(o => originAllowed(o, host, originHosts, origins))
      }

  /** `Host` names always allowed for an automatically guarded loopback listener. */
  private[fastmcp] val LoopbackNames: Set[String] = Set("localhost", "127.0.0.1", "[::1]")

  private val Unchecked: Set[String] = Set.empty

  /** Resolve the guard for `settings` (the table in the object's documentation).
    *
    * An explicit list always applies; `dnsRebindingProtection = false` only switches the automatic
    * guard off, so the contradictory "off, but listed" combination — refused at startup by
    * `HttpRequestGuards.validateSettings` — still fails closed here.
    *
    * `allowedOrigins` without `allowedHosts` keeps its meaning — only the listed origins pass, the
    * request's own authority included — and on a loopback bind the automatic `Host` names are
    * checked on top of it. Nothing that was refused before the automatic guard is admitted by it.
    */
  private[fastmcp] def policy(settings: McpServerSettings): Policy =
    val rawHosts = settings.allowedHosts.getOrElse(Set.empty)
    val rawOrigins = settings.allowedOrigins.getOrElse(Set.empty)
    val origins = rawOrigins.flatMap(parseOrigin)
    def originsOnly = new Policy(Unchecked, origins, false, enforced = true, Mode.Explicit)
    def off(mode: Mode) = new Policy(Unchecked, origins, false, enforced = false, mode)
    if rawHosts.nonEmpty then
      new Policy(rawHosts.map(_.trim.toLowerCase), origins, true, enforced = true, Mode.Explicit)
    else if !settings.dnsRebindingProtection then
      if rawOrigins.nonEmpty then originsOnly else off(Mode.Disabled)
    else
      loopbackHostNames(settings.host) match
        case Some(names) =>
          new Policy(names, origins, rawOrigins.isEmpty, enforced = true, Mode.AutomaticLoopback)
        case None => if rawOrigins.nonEmpty then originsOnly else off(Mode.Unprotected)

  /** Backend entry point: the verdict of the guard `settings` resolve to (`allowedHosts`,
    * `allowedOrigins`, the automatic loopback default and the `dnsRebindingProtection` opt-out).
    */
  def isAllowed(
      host: Option[String],
      origin: Option[String],
      settings: McpServerSettings
  ): Boolean =
    settings.hostGuardPolicy.allows(host, origin)

  /** Raw-set core (tests): exactly these lists, no automatic default — both empty allows
    * everything. `allowedOrigins` entries are full origins; unparseable entries are ignored here —
    * `HttpRequestGuards.validateSettings` rejects them at startup.
    */
  private[fastmcp] def isAllowed(
      host: Option[String],
      origin: Option[String],
      allowedHosts: Set[String],
      allowedOrigins: Set[String]
  ): Boolean =
    new Policy(
      allowedHosts.map(_.trim.toLowerCase),
      allowedOrigins.flatMap(parseOrigin),
      sameAuthorityOrigins = true,
      enforced = allowedHosts.nonEmpty || allowedOrigins.nonEmpty,
      Mode.Explicit
    ).allows(host, origin)

  /** The `Host` names of an automatically guarded listener: `Some` iff `bindHost` is a loopback
    * bind address — `localhost`, a `127.0.0.0/8` literal, or the IPv6 loopback in any spelling
    * (`::1`, `[::1]`, `0:0:0:0:0:0:0:1`, IPv4-mapped `::ffff:127.x.y.z`, with or without a zone id)
    * — and then [[LoopbackNames]] plus the bound literal itself (IPv6 bracketed, as it appears in a
    * `Host` header). Pure string classification, identical on every platform: nothing is resolved,
    * so a host name that merely maps to loopback is `None`, like every wildcard (`0.0.0.0`, `::`),
    * LAN address or public name.
    */
  private[fastmcp] def loopbackHostNames(bindHost: String): Option[Set[String]] =
    val trimmed = bindHost.trim.toLowerCase
    val unbracketed =
      if trimmed.startsWith("[") && trimmed.endsWith("]") then
        trimmed.substring(1, trimmed.length - 1)
      else trimmed
    val zone = unbracketed.indexOf('%')
    val bare = if zone >= 0 then unbracketed.substring(0, zone) else unbracketed
    if bare == "localhost" then Some(LoopbackNames)
    else if isLoopbackV4(bare) then Some(LoopbackNames + bare)
    else if bare.startsWith("::ffff:") && isLoopbackV4(bare.substring(7)) then
      Some(LoopbackNames + bare.substring(7) + s"[$bare]")
    else if isLoopbackV6(bare) then Some(LoopbackNames + s"[$bare]")
    else None

  /** Whether `bindHost` is a loopback bind address (see [[loopbackHostNames]]). */
  private[fastmcp] def isLoopbackBind(bindHost: String): Boolean =
    loopbackHostNames(bindHost).isDefined

  /** A dotted-quad `127.a.b.c` with every part a decimal 0..255. */
  private def isLoopbackV4(s: String): Boolean =
    val parts = s.split('.')
    !s.endsWith(".") && parts.length == 4 && parts(0) == "127" && parts.forall { part =>
      part.nonEmpty && part.length <= 3 && part.forall(ch => ch >= '0' && ch <= '9') &&
      part.toInt <= 255
    }

  /** `::1` in any textual form: at most one `::`, hex groups of 1..4 digits, eight groups after
    * expansion, all zero but the last, which is one.
    */
  private def isLoopbackV6(s: String): Boolean =
    def groups(part: String): Option[List[String]] =
      if part.isEmpty then Some(Nil)
      else
        val gs = part.split(":", -1).toList
        // ASCII only (the input is already lower-cased): `Character.digit` also accepts other
        // scripts' digits, and not identically on every platform.
        val hex = (g: String) =>
          g.nonEmpty && g.length <= 4 &&
            g.forall(ch => (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f'))
        if gs.forall(hex) then Some(gs) else None
    val split = s.indexOf("::")
    val expanded: Option[List[String]] =
      if split < 0 then groups(s).filter(_.length == 8)
      else if s.indexOf("::", split + 1) >= 0 then None
      else
        for
          head <- groups(s.substring(0, split))
          tail <- groups(s.substring(split + 2))
          if head.length + tail.length <= 7
        yield head ++ List.fill(8 - head.length - tail.length)("0") ++ tail
    expanded.exists { gs =>
      gs.init.forall(g => Integer.parseInt(g, 16) == 0) && Integer.parseInt(gs.last, 16) == 1
    }

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
