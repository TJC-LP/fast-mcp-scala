package com.tjclp.fastmcp.server.transport

import com.tjclp.fastmcp.server.McpServerSettings
import com.tjclp.fastmcp.server.router.RequestHeaders

/** Every transport-level admission decision of the HTTP backends, as pure functions over the
  * request headers and [[McpServerSettings]]. Backends (zio-http on the JVM, `Bun.serve` on
  * Scala.js) only render a [[HttpRequestGuards.Rejection]] onto their own response type, so the
  * security logic is written — and tested — once.
  *
  * Request order on every backend:
  * {{{
  * path 404 → hostGate 403 → Content-Type 415 → declared Content-Length 413 → Accept 406 →
  *   body read (platform byte cap → 413) → bodyTooLarge 413 → parseFrame → dispatch →
  *   any defect → JSON-RPC 500 (guarded)
  * }}}
  * A header-less legacy `initialize` is dispatched on a session that is not stored yet, and only a
  * successful one then meets the session cap:
  * {{{
  * initialize (over the retention budget → -32602, nothing stored) →
  *   session cap (pickVictim: evict / 503) → store
  * }}}
  */
private[fastmcp] object HttpRequestGuards:

  /** A transport-level refusal: HTTP status + message for the JSON-RPC error body (404 → -32001,
    * anything else → -32000, as every backend's `errorResponse` already maps).
    */
  final case class Rejection(status: Int, message: String)

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
    * disable the cap), and every `contextHeaders` entry must be an HTTP field name.
    */
  def validateSettings(settings: McpServerSettings): Either[String, Unit] =
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
    else Right(())

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

  /** POST gate, evaluated on headers only (before any body read or session state), in this order:
    * 403 host/origin → 415 media type → 413 declared Content-Length → 406 Accept. `requireSse`
    * additionally demands `text/event-stream` in `Accept` (streamable transport: replies stream as
    * SSE).
    */
  def postGate(
      header: String => Option[String],
      settings: McpServerSettings,
      requireSse: Boolean
  ): Option[Rejection] =
    hostGate(header, settings).orElse {
      if !isJsonContentType(header("content-type")) then
        Some(Rejection(415, "Content-Type must be application/json"))
      else if declaredLengthExceeds(header("content-length"), settings) then
        Some(bodyTooLargeRejection(settings))
      else if !acceptsAny(header("accept"), List("application/json", "application/*")) then
        Some(Rejection(406, "Accept must allow application/json"))
      else if requireSse && !acceptsAny(header("accept"), List("text/event-stream", "text/*")) then
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

  /** Absent `Accept` passes (header-less clients); present → must include one of `types` or the
    * any-type wildcard.
    */
  def acceptsAny(accept: Option[String], types: List[String]): Boolean =
    accept match
      case None => true
      case Some(a) =>
        val lower = a.toLowerCase
        lower.contains("*/*") || types.exists(lower.contains)

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
