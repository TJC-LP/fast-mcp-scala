package com.tjclp.fastmcp.server.transport

import com.tjclp.fastmcp.core.{ErrorCodes, Protocol}
import com.tjclp.fastmcp.server.McpServerSettings
import com.tjclp.fastmcp.server.router.RequestHeaders

/** Every transport-level admission decision of the HTTP backends, as pure functions over the
  * request headers and [[McpServerSettings]]. Backends (zio-http on the JVM, `Bun.serve` on
  * Scala.js) only render a [[HttpRequestGuards.Rejection]] onto their own response type, so the
  * security logic is written — and tested — once.
  *
  * Request order on every backend:
  * {{{
  * path 404 (pathMatches) → hostGate 403 → method 405 (nonPostGate) →
  *   POST: Content-Type 415 → declared Content-Length 413 → Accept 406 →
  *     body read (platform byte cap → 413) → bodyTooLarge 413 → parseFrame → dispatch →
  *     any defect → JSON-RPC 500 (guarded)
  *   GET / DELETE: 2026-07-28 header 405 → (GET: mcp-protocol-version 400 → Accept 406) →
  *     missing mcp-session-id 400 → unknown session 404
  * }}}
  * A header-less legacy `initialize` is dispatched on a session that is not stored yet, and only a
  * successful one then meets the session cap:
  * {{{
  * initialize (over the retention budget → -32602, nothing stored) →
  *   session cap (pickVictim: evict / 503) → store
  * }}}
  */
private[fastmcp] object HttpRequestGuards:

  /** A transport-level refusal: HTTP status + message for the JSON-RPC error body. `code` is the
    * JSON-RPC error code when the refusal has one of its own; `None` is the status mapping every
    * backend applies ([[errorCode]]: 404 → -32001, anything else → -32000). `allow`, non-empty for
    * a 405, is the `Allow` response header.
    */
  final case class Rejection(
      status: Int,
      message: String,
      code: Option[Int] = None,
      allow: List[String] = Nil
  ):

    /** The JSON-RPC error code of the reply body. */
    def errorCode: Int = code.getOrElse(HttpRequestGuards.errorCode(status))

    /** The response headers this refusal adds (`Allow` on a 405). */
    def headers: List[(String, String)] =
      if allow.isEmpty then Nil else List("allow" -> allow.mkString(", "))

  /** The JSON-RPC code of a transport-level error body: `404` carries
    * [[ErrorCodes.SessionNotFound]], every other status [[ErrorCodes.TransportError]].
    */
  def errorCode(status: Int): Int =
    if status == 404 then ErrorCodes.SessionNotFound else ErrorCodes.TransportError

  /** Compatibility header for initialization-based Streamable HTTP revisions. */
  val SessionIdHeader: String = "mcp-session-id"

  /** 400 for a GET or DELETE on the legacy adapter that names no session (spec 2025-11-25: a
    * request without the id SHOULD be answered 400; an unknown id is 404).
    */
  val SessionRequired: Rejection = Rejection(400, s"$SessionIdHeader header is required")

  /** The one Accept refusal of a 2026-07-28 POST, whichever check finds it. */
  val ModernAcceptMessage: String = "Accept must include application/json and text/event-stream"

  val InternalErrorMessage: String = "Internal server error"
  val SessionLimitMessage: String = "Session limit reached; retry later"
  val HostRefusedMessage: String = "Host/Origin not allowed (DNS-rebinding protection)"

  val OptOutWithAllowListMessage: String =
    "dnsRebindingProtection = false contradicts allowedHosts / allowedOrigins: remove the " +
      "allow-lists to turn Host/Origin validation off, or drop dnsRebindingProtection = false to " +
      "keep them enforced"

  /** Startup validation, run by every `serveHttp` before binding: each `allowedOrigins` entry must
    * parse as `scheme://host[:port]`, `dnsRebindingProtection = false` must not be combined with an
    * allow-list (which of the two was meant?), `maxRequestBodyBytes` must be positive and not
    * exceed `limits.maxFrameChars`, `maxSessions`, when set, must be positive (use `None` to
    * disable the cap), every `contextHeaders` entry must be an HTTP field name, and `httpEndpoint`
    * must be a path a request can actually name ([[invalidEndpoint]]).
    */
  def validateSettings(settings: McpServerSettings): Either[String, Unit] =
    validateSettings(settings, decodedPaths = false)

  /** [[validateSettings]] for a backend whose HTTP layer hands over the request path
    * percent-decoded (`decodedPaths`, zio-http): an endpoint with a character clients
    * percent-encode does match there, so it is not refused ([[encodedEndpointWarning]] says what to
    * log instead).
    */
  private[fastmcp] def validateSettings(
      settings: McpServerSettings,
      decodedPaths: Boolean
  ): Either[String, Unit] =
    val bad = HostGuard.invalidOrigins(settings.allowedOrigins.getOrElse(Set.empty))
    val badHeaders = RequestHeaders.invalidNames(settings.contextHeaders)
    val listed =
      settings.allowedHosts.exists(_.nonEmpty) || settings.allowedOrigins.exists(_.nonEmpty)
    if bad.nonEmpty then
      Left(
        s"allowedOrigins entries are not `scheme://host[:port]`: ${bad.toList.sorted.mkString(", ")}"
      )
    else if !settings.dnsRebindingProtection && listed then Left(OptOutWithAllowListMessage)
    // A name that is not a token can never match a header, and on Bun looking it up throws for
    // every request (`Headers.get` rejects it): refuse it once, at startup, on every backend.
    else if badHeaders.nonEmpty then
      Left(
        s"contextHeaders entries are not HTTP header names: ${badHeaders.toList.sorted.map(n => s"'$n'").mkString(", ")}"
      )
    else if settings.maxRequestBodyBytes <= 0 then Left("maxRequestBodyBytes must be positive")
    else if settings.maxSessions.exists(_ <= 0) then
      Left("maxSessions must be positive (use None to disable the cap)")
    // The body cap must sit inside the frame cap so an oversized body gets 413 before `parseFrame`
    // ever sees it; `limits.maxFrameChars` is the transport-independent backstop.
    else if settings.maxRequestBodyBytes > settings.limits.maxFrameChars then
      Left("maxRequestBodyBytes must not exceed limits.maxFrameChars")
    else
      invalidEndpoint(settings.httpEndpoint)
        .filterNot(_ => decodedPaths && structuralProblem(settings.httpEndpoint).isEmpty)
        .toLeft(())

  /** The path requests are matched against: `httpEndpoint` with a leading slash (`/mcp` for both
    * `"/mcp"` and `"mcp"`).
    */
  def endpointPath(settings: McpServerSettings): String =
    "/" + settings.httpEndpoint.stripPrefix("/")

  /** Whether a request path (query string already removed) names the MCP endpoint: an exact,
    * case-sensitive string match. `/mcp/`, `/MCP` and `/mcp/extra` are not `/mcp`. Anything else is
    * 404 on every backend, before any other gate.
    */
  def pathMatches(path: String, settings: McpServerSettings): Boolean =
    path == endpointPath(settings)

  /** Why `httpEndpoint` can never be matched by [[pathMatches]], if so. The endpoint is compared
    * with the request path as written, so a `?` or `#` (never part of a path), a `%` (an escape a
    * backend or proxy may or may not decode before the comparison), whitespace or a control
    * character (not sendable in a request line), and a `.` / `..` segment (resolved away by clients
    * and by Bun before the server sees the path) each make a route that silently answers 404 to
    * every request: refused at startup instead.
    */
  def invalidEndpoint(httpEndpoint: String): Option[String] =
    structuralProblem(httpEndpoint).orElse(encodedChar(httpEndpoint)).map { what =>
      s"httpEndpoint '$httpEndpoint' can never match a request path: it contains $what"
    }

  /** The WARN for a backend that receives decoded paths and so serves an endpoint with a character
    * clients percent-encode: the same settings fail at startup on the other backends.
    */
  private[fastmcp] def encodedEndpointWarning(httpEndpoint: String): Option[String] =
    if structuralProblem(httpEndpoint).nonEmpty then None
    else
      encodedChar(httpEndpoint).map { what =>
        s"httpEndpoint '$httpEndpoint' contains $what. This backend decodes the request path and " +
          "serves it; the socket and Bun backends refuse the same setting at startup."
      }

  private def structuralProblem(httpEndpoint: String): Option[String] =
    def refuse(what: String) = Some(what)
    if httpEndpoint.exists(ch => ch.isWhitespace || Character.isISOControl(ch)) then
      refuse("whitespace or a control character")
    else if httpEndpoint.indexOf('?') >= 0 then
      refuse("'?' (a query string is not part of the path)")
    else if httpEndpoint.indexOf('#') >= 0 then refuse("'#'")
    else if httpEndpoint.indexOf('%') >= 0 then
      refuse("'%' (percent-escapes are compared as written; write the path unescaped)")
    else if httpEndpoint.split('/').exists(seg => seg == "." || seg == "..") then
      refuse("a '.' or '..' segment")
    else None

  private def encodedChar(httpEndpoint: String): Option[String] =
    // What is left must be a character a path carries as written (RFC 3986 `pchar`, plus the
    // separator). Anything else — non-ASCII, `"`, `<`, `[`, `\\` ... — is percent-encoded by
    // clients and by Bun's URL parser, and the endpoint is compared as written.
    httpEndpoint.find(ch => !pathChar(ch)).map { ch =>
      s"'$ch' (clients percent-encode it, and the path is compared as written; use letters, " +
        "digits and -._~!$&'()*+,;=:@ only)"
    }

  private def pathChar(ch: Char): Boolean =
    (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') ||
      "/-._~!$&'()*+,;=:@".indexOf(ch.toInt) >= 0

  /** What every backend logs at WARN once, before binding. Today: a non-loopback bind address with
    * no allow-list, where the Host/Origin guard has nothing to go on and is therefore off. Startup
    * is NOT refused — a container behind an authenticating proxy binds `0.0.0.0` legitimately — but
    * the operator is told, and told how to make the warning go away either way.
    */
  def startupWarnings(settings: McpServerSettings): List[String] =
    settings.hostGuardPolicy.mode match
      case HostGuard.Mode.Unprotected => List(unprotectedBindWarning(settings.host))
      case _ => Nil

  private def unprotectedBindWarning(host: String): String =
    s"HTTP transport is bound to the non-loopback address '$host' with no Host/Origin validation: " +
      "allowedHosts and allowedOrigins are both unset, so a request with ANY Host or Origin is " +
      "served. Set allowedHosts to the host names clients use to reach this server (and " +
      "allowedOrigins for browser front-ends on another origin), and put an authenticating proxy " +
      "in front of it. To run without the check knowingly, set dnsRebindingProtection = false, " +
      "which also silences this warning."

  /** 403 when the Host/Origin guard refuses. Used for GET/DELETE and as step 1 of [[postGate]]. */
  def hostGate(header: String => Option[String], settings: McpServerSettings): Option[Rejection] =
    if HostGuard.isAllowed(header("host"), header("origin"), settings) then None
    else Some(Rejection(403, HostRefusedMessage))

  /** The `mcp-session-id` of a request on the legacy adapter. A header that is present but empty
    * (or blank) names no session and counts as absent. `header` is the gates' joined lookup, so an
    * id sent twice is one `", "`-joined value that matches no session (404) on every backend.
    */
  def sessionId(header: String => Option[String]): Option[String] =
    header(SessionIdHeader).map(_.trim).filter(_.nonEmpty)

  /** The methods the endpoint serves, in `Allow` order. POST always; GET only where a standalone
    * server→client stream exists (`pushChannel`: the zio-http and socket backends, not Bun) and
    * DELETE unless `disallowDelete` — both only on the legacy session adapter, which
    * `settings.stateless` turns off.
    */
  def allowedMethods(settings: McpServerSettings, pushChannel: Boolean): List[String] =
    if settings.stateless then List("POST")
    else
      List("POST") ++ (if pushChannel then List("GET") else Nil) ++
        (if settings.disallowDelete then Nil else List("DELETE"))

  /** The gate of every request that is not a POST, on headers only and in this order: 403
    * host/origin → 405 for a method the endpoint does not serve ([[allowedMethods]]; `Allow` lists
    * them) → 405 for a GET or DELETE that declares MCP 2026-07-28, which has neither (`Allow:
    * POST`). `None` admits a GET or DELETE to the legacy session adapter. `method` is the
    * upper-case token.
    */
  def nonPostGate(
      method: String,
      header: String => Option[String],
      settings: McpServerSettings,
      pushChannel: Boolean
  ): Option[Rejection] =
    hostGate(header, settings).orElse {
      val allow = allowedMethods(settings, pushChannel)
      def refuse(why: String) =
        Some(Rejection(405, s"$why (allowed: ${allow.mkString(", ")})", allow = allow))
      if !allow.contains(method) then
        method match
          case "GET" if settings.stateless =>
            refuse("GET is not supported: the stateless transport has no server-to-client stream")
          case "GET" =>
            refuse(
              "GET is not supported: there is no standalone server-to-client stream; " +
                "server-to-client messages ride the response to each POST"
            )
          case "DELETE" if settings.stateless =>
            refuse("DELETE is not supported: the stateless transport has no session to end")
          case "DELETE" => refuse("DELETE is disabled on this server")
          case other => refuse(s"Method $other is not allowed on the MCP endpoint")
      else if method != "POST" && header("mcp-protocol-version").exists(Protocol.isStatelessVersion)
      then
        // What this revision may use here is POST alone, whatever the legacy adapter also serves.
        Some(
          Rejection(
            405,
            s"$method is not used by MCP ${Protocol.LatestProtocolVersion}: send each message as a POST",
            allow = List("POST")
          )
        )
      else None
    }

  /** Whether the `mcp-protocol-version` header puts a POST on the 2026-07-28 path (the header half
    * of `ModernHttpValidation.isModern`; the body can only be consulted once it has been read).
    */
  def declaresModern(header: String => Option[String]): Boolean =
    header("mcp-protocol-version").exists(v => !Protocol.LegacyProtocolVersions.contains(v))

  /** POST gate, evaluated on headers only (before any body read or session state), in this order:
    * 403 host/origin → 415 media type → 413 declared Content-Length → 406 Accept.
    *
    * `Accept` on a legacy POST is lenient: an absent header passes, and a present one must allow
    * `application/json` — and `text/event-stream` too when `requireSse` (streamable transport:
    * replies stream as SSE). A POST whose `mcp-protocol-version` header declares 2026-07-28 is held
    * to that revision's rule instead: the header is required and must allow both media types,
    * whatever `requireSse` says, and the refusal carries the header-mismatch code (`-32020`) with
    * [[ModernAcceptMessage]] — the same reply `ModernHttpValidation` gives a request it finds
    * modern only after reading the body, so the 406 of a modern request has one shape.
    */
  def postGate(
      header: String => Option[String],
      settings: McpServerSettings,
      requireSse: Boolean
  ): Option[Rejection] =
    hostGate(header, settings).orElse {
      val accept = header("accept")
      if !isJsonContentType(header("content-type")) then
        Some(Rejection(415, "Content-Type must be application/json"))
      else if declaredLengthExceeds(header("content-length"), settings) then
        Some(bodyTooLargeRejection(settings))
      else if declaresModern(header) then
        if modernAcceptOk(accept) then None
        else Some(Rejection(406, ModernAcceptMessage, code = Some(ErrorCodes.HeaderMismatch)))
      else if !acceptAllows(accept, "application/json") then
        Some(Rejection(406, "Accept must allow application/json"))
      else if requireSse && !acceptAllows(accept, "text/event-stream") then
        Some(Rejection(406, "Accept must allow text/event-stream"))
      else None
    }

  /** RFC 9110 media-type match: `application/json` with any parameters; a `charset` parameter, when
    * present, must be utf-8 (JSON is UTF-8 and both backends decode the body as UTF-8). Absent or
    * any other media type (`text/plain`, `application/json-patch+json`, a wildcard) → false.
    */
  def isJsonContentType(value: Option[String]): Boolean =
    value.exists { v =>
      val semi = v.indexOf(';')
      val mediaType = (if semi < 0 then v else v.substring(0, semi)).trim
      val params = if semi < 0 then "" else v.substring(semi + 1).toLowerCase
      val charsetOk =
        params.split(';').map(_.trim).filter(_.startsWith("charset=")).forall { p =>
          val cs = p.stripPrefix("charset=").trim.stripPrefix("\"").stripSuffix("\"")
          cs == "utf-8" || cs == "utf8"
        }
      mediaType.equalsIgnoreCase("application/json") && charsetOk
    }

  /** The lenient (pre-2026-07-28) `Accept` rule: an absent or blank header passes (header-less
    * clients); a present one must make `mediaType` acceptable ([[acceptQuality]] above zero).
    */
  def acceptAllows(accept: Option[String], mediaType: String): Boolean =
    accept.map(_.trim).filter(_.nonEmpty).forall(acceptQuality(_, mediaType).exists(_ > 0.0))

  /** The 2026-07-28 `Accept` rule: the header is required and must make both `application/json` and
    * `text/event-stream` acceptable.
    */
  def modernAcceptOk(accept: Option[String]): Boolean =
    accept.exists { value =>
      acceptQuality(value, "application/json").exists(_ > 0.0) &&
      acceptQuality(value, "text/event-stream").exists(_ > 0.0)
    }

  /** RFC 9110 §12.5.1: the quality `accept` gives `mediaType` (a lower-case `type/subtype`), or
    * `None` when no media range matches it. The most specific matching range decides — the exact
    * type over its subtype wildcard (`text/` + `*`) over the any-type wildcard — so
    * `text/event-stream;q=0` next to the any-type wildcard refuses event streams while accepting
    * everything else. Ranges are compared case-insensitively and their other parameters are
    * ignored; a weight is `q=` followed by a number in 0..1, `q=0` meaning "not acceptable". A
    * weight that does not parse counts as 1 (the range was listed), and among equally specific
    * ranges the highest weight wins.
    */
  def acceptQuality(accept: String, mediaType: String): Option[Double] =
    val slash = mediaType.indexOf('/')
    val typeWildcard = if slash < 0 then mediaType else mediaType.substring(0, slash) + "/*"
    var bestRank = 0
    var best = Option.empty[Double]
    accept.split(',').foreach { element =>
      val parts = element.split(';')
      // `"".split(';')` is an empty array: an empty list element names no range.
      val range = parts.headOption.fold("")(_.trim.toLowerCase)
      val rank =
        if range == mediaType then 3
        else if range == typeWildcard then 2
        else if range == "*/*" then 1
        else 0
      if rank > 0 && rank >= bestRank then
        val q = parts.iterator
          .drop(1)
          .map(_.trim.toLowerCase)
          .collectFirst { case p if p.startsWith("q=") => p.substring(2).trim }
          .flatMap(_.toDoubleOption)
          .filter(w => w >= 0.0 && w <= 1.0)
          .getOrElse(1.0)
        if rank > bestRank || best.forall(_ < q) then best = Some(q)
        bestRank = rank
    }
    best

  /** True when a parseable `Content-Length` declares more bytes than `maxRequestBodyBytes`. An
    * absent or garbage header passes here — the platform byte cap and [[bodyTooLarge]] cover it.
    */
  def declaredLengthExceeds(contentLength: Option[String], settings: McpServerSettings): Boolean =
    contentLength.flatMap(_.trim.toLongOption).exists(_ > settings.maxRequestBodyBytes.toLong)

  /** Post-read heuristic on the decoded text: UTF-16 code units <= UTF-8 bytes, so `body.length >
    * limit` implies the byte length exceeded the limit (never a false positive; may miss a
    * multi-byte body just over the cap — the platform byte checks cover that).
    */
  def bodyTooLarge(body: String, settings: McpServerSettings): Boolean =
    body.length > settings.maxRequestBodyBytes

  def bodyTooLargeRejection(settings: McpServerSettings): Rejection =
    Rejection(413, s"Request body exceeds ${settings.maxRequestBodyBytes} bytes")

  /** Legacy session-store admission: true when the store is at (or beyond) `maxSessions`. */
  def capReached(storeSize: Int, settings: McpServerSettings): Boolean =
    settings.maxSessions.exists(storeSize >= _)

  /** Victim for cap eviction: the longest-idle session WITHOUT a live GET. Tuples are `(sessionId,
    * lastSeenMillis, hasActiveGet)`. `None` → nothing evictable → 503. Every `lastSeen` / `now` in
    * this object is a reading of the monotonic clock (`Session.monotonicMs`).
    */
  def pickEvictable(snapshot: Iterable[(String, Long, Boolean)]): Option[String] =
    snapshot.filterNot(_._3).minByOption(_._2).map(_._1)

  /** Cap-eviction victim with the idle fallback. A GET-less session is always preferred
    * ([[pickEvictable]]); only when every stored session holds a live GET does the longest-idle of
    * them qualify, and only once `now - lastSeen` exceeds `idleTimeoutMs`: a client silent that
    * long on a live stream has either abandoned it or vanished without closing the connection, and
    * terminating the session closes the stream (a server may end a GET stream at any time; clients
    * reopen it). `idleTimeoutMs = None` (`sessionIdleTimeout` off) keeps GET holders exempt, so the
    * verdict is then exactly [[pickEvictable]]'s.
    */
  def pickEvictable(
      snapshot: Iterable[(String, Long, Boolean)],
      now: Long,
      idleTimeoutMs: Option[Long]
  ): Option[String] =
    pickEvictable(snapshot).orElse {
      idleTimeoutMs.flatMap { timeout =>
        snapshot
          .filter((_, seen, live) => live && now - seen > timeout)
          .minByOption(_._2)
          .map(_._1)
      }
    }

  /** One stored legacy session as the cap-time policy sees it. `client` is the transport-supplied
    * identity of whoever opened it (`Session.clientKey`: the peer address on the shipped backends);
    * `None` when the backend cannot tell clients apart.
    */
  final case class StoredSession(
      id: String,
      lastSeen: Long,
      liveGet: Boolean,
      client: Option[String]
  )

  /** The session that makes room for a newcomer when the store is full, or `None` to refuse the
    * newcomer (503). `newcomer` is the identity of the client asking for the new session.
    *
    * Whether a session can be evicted under the idle rules ([[pickEvictable]]) depends only on what
    * its owner does: holding a GET stream open and touching the session keeps it exempt. Left at
    * that, one client could fill the store with exempt sessions and have every other client
    * refused. So the sessions each client holds are counted, and a client holding more than its
    * share is the one that gives a session up, whatever state its sessions are in:
    *
    *   1. '''The newcomer's own client already holds as many sessions as any other client''': it
    *      replaces one of its own. A client at the top can grow only at its own expense.
    *   1. '''Some client holds at least two sessions more than the newcomer's client''': the client
    *      holding the most gives one up. Afterwards it still holds at least as many as the
    *      newcomer's client does.
    *   1. '''Otherwise holdings are level''' and the idle rules decide over the whole store, as
    *      they always have: the longest-idle session without a live GET, else the longest-idle GET
    *      holder idle past `idleTimeoutMs`, else nothing — the store is full of clients that each
    *      hold their share, and the newcomer is refused.
    *
    * In cases 1 and 2 the session given up is that client's longest-idle one without a live GET,
    * else its longest-idle one outright — its GET stream is closed and the client reconnects.
    *
    * A session without an identity counts as a client of its own holding one session, so on a
    * backend that supplies no identity only case 3 ever applies. Behind a reverse proxy every
    * session carries the proxy's address: they are one client, case 1 always applies, and the store
    * behaves as a least-recently-used cache.
    */
  def pickVictim(
      snapshot: Iterable[StoredSession],
      newcomer: Option[String],
      now: Long,
      idleTimeoutMs: Option[Long]
  ): Option[String] =
    val held: Map[String, Int] = snapshot.iterator
      .flatMap(_.client)
      .foldLeft(Map.empty[String, Int])((counts, c) =>
        counts.updated(c, counts.getOrElse(c, 0) + 1)
      )
    def holdings(s: StoredSession): Int = s.client.fold(1)(held.getOrElse(_, 1))
    val most = snapshot.iterator.map(holdings).maxOption.getOrElse(0)
    val own = newcomer.fold(0)(held.getOrElse(_, 0))
    def longestIdle(pool: Iterable[StoredSession]): Option[String] =
      pool
        .filterNot(_.liveGet)
        .minByOption(_.lastSeen)
        .orElse(pool.minByOption(_.lastSeen))
        .map(_.id)
    if own > 0 && own >= most then longestIdle(snapshot.filter(_.client == newcomer))
    else if most >= own + 2 then longestIdle(snapshot.filter(holdings(_) == most))
    else pickEvictable(snapshot.map(s => (s.id, s.lastSeen, s.liveGet)), now, idleTimeoutMs)
