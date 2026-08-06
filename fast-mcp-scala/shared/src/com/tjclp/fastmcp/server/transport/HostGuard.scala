package com.tjclp.fastmcp.server.transport

/** DNS-rebinding protection shared by the JVM and JS HTTP transports.
  *
  * When `allowed` is non-empty, an inbound request is permitted only if its `Host` header — and its
  * `Origin` header when present — names an allowed host. Comparison is case-insensitive and by
  * hostname with the port stripped, so a single `127.0.0.1` entry covers any bound port; the
  * verbatim `host:port` is also accepted. An absent `Host`/`Origin` is treated as allowed (HTTP/1.1
  * always sends `Host`; absence is not the rebinding threat this guards against). `allowed.isEmpty`
  * disables the check entirely (the default when `McpServerSettings.allowedHosts` is `None`).
  */
object HostGuard:

  /** True if the request may proceed. */
  def isAllowed(host: Option[String], origin: Option[String], allowed: Set[String]): Boolean =
    if allowed.isEmpty then true
    else
      val a = allowed.map(_.trim.toLowerCase)
      host.forall(h => hostAllowed(h, a)) && origin.forall(o => originAllowed(o, a))

  private def hostAllowed(hostHeader: String, allowedLower: Set[String]): Boolean =
    val h = hostHeader.trim.toLowerCase
    allowedLower.contains(h) || allowedLower.contains(hostnameOf(h))

  /** `Origin` is `scheme://host[:port]` (or the literal `null`); reduce to `host[:port]`. */
  private def originAllowed(originHeader: String, allowedLower: Set[String]): Boolean =
    val o = originHeader.trim.toLowerCase
    if o == "null" || o.isEmpty then false
    else
      val hostPort = o.indexOf("://") match
        case -1 => o
        case i => o.substring(i + 3)
      hostAllowed(hostPort, allowedLower)

  /** Strip a trailing `:port`, preserving bracketed IPv6 literals (`[::1]:8080` -> `[::1]`). */
  private def hostnameOf(hostPort: String): String =
    if hostPort.startsWith("[") then
      val end = hostPort.indexOf(']')
      if end >= 0 then hostPort.substring(0, end + 1) else hostPort
    else
      val colon = hostPort.indexOf(':')
      if colon >= 0 then hostPort.substring(0, colon) else hostPort
