package com.tjclp.fastmcp.server.transport

import com.tjclp.fastmcp.server.McpServerSettings

/** Every transport-level admission decision of the HTTP backends, as pure functions over the
  * request headers and [[McpServerSettings]]. Backends (zio-http on the JVM, `Bun.serve` on
  * Scala.js) only render a [[HttpRequestGuards.Rejection]] onto their own response type, so the
  * security logic is written — and tested — once.
  *
  * Request order on every backend:
  * {{{
  * path 404 → hostGate 403 → Content-Type 415 → declared Content-Length 413 → Accept 406 →
  *   body read (platform byte cap → 413) → bodyTooLarge 413 → parseFrame → dispatch →
  *   session cap (evict oldest idle / 503) → any defect → JSON-RPC 500 (guarded)
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

  /** Startup validation, run by every `serveHttp` before binding: each `allowedOrigins` entry must
    * parse as `scheme://host[:port]`, `maxRequestBodyBytes` must be positive and not exceed
    * `limits.maxFrameChars`, and `maxSessions`, when set, must be positive (use `None` to disable
    * the cap).
    */
  def validateSettings(settings: McpServerSettings): Either[String, Unit] =
    val bad = HostGuard.invalidOrigins(settings.allowedOrigins.getOrElse(Set.empty))
    if bad.nonEmpty then
      Left(
        s"allowedOrigins entries are not `scheme://host[:port]`: ${bad.toList.sorted.mkString(", ")}"
      )
    else if settings.maxRequestBodyBytes <= 0 then Left("maxRequestBodyBytes must be positive")
    else if settings.maxSessions.exists(_ <= 0) then
      Left("maxSessions must be positive (use None to disable the cap)")
    // The body cap must sit inside the frame cap so an oversized body gets 413 before `parseFrame`
    // ever sees it; `limits.maxFrameChars` is the transport-independent backstop.
    else if settings.maxRequestBodyBytes > settings.limits.maxFrameChars then
      Left("maxRequestBodyBytes must not exceed limits.maxFrameChars")
    else Right(())

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
    * lastSeenMillis, hasActiveGet)`. `None` → nothing evictable → 503.
    */
  def pickEvictable(snapshot: Iterable[(String, Long, Boolean)]): Option[String] =
    snapshot.filterNot(_._3).minByOption(_._2).map(_._1)
