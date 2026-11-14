package com.tjclp.fastmcp.server

import zio.*

/** A plain HTTP route the server author adds next to the MCP endpoint, on the same listener
  * (`McpServerSettings.httpRoutes`): a health or readiness probe, a version page. Since 1.1.0.
  *
  * {{{
  * McpServerSettings(
  *   httpRoutes = List(HttpRoute.get("/health")(_ => ZIO.succeed(HttpRouteResponse.text("ok"))))
  * )
  * }}}
  *
  * What a route is, on every HTTP backend (zio-http, the socket backend, `Bun.serve`):
  *   - '''`GET` only, on an exact path.''' The path is compared as the MCP endpoint is: exactly and
  *     case-sensitively, the query string not being part of it. Another method on a route's path is
  *     405 with `Allow: GET`.
  *   - '''Never the MCP endpoint.''' A route cannot be registered on `httpEndpoint`, so no route
  *     can answer, observe or change an MCP request. Everything the endpoint enforces stays as it
  *     is.
  *   - '''Behind the Host/Origin guard.''' A request to a route meets the same DNS-rebinding check
  *     as one to the endpoint (`allowedHosts` / `allowedOrigins`, automatic on a loopback bind) and
  *     is answered 403 before the handler runs. A probe must therefore send a `Host` the guard
  *     admits.
  *   - '''Nothing else is checked.''' The `Content-Type` and `Accept` gates, the protocol headers,
  *     the session store and the input limits belong to the MCP endpoint and do not apply. There is
  *     no authentication: a route is as public as the listener.
  *   - '''No request body.''' The handler sees the path and the request headers. A body sent with
  *     the request is never handed to it.
  *
  * A handler that dies is answered with a fixed 500; its cause is logged, never sent. Routes are
  * ignored by the stdio transport.
  */
final class HttpRoute private (
    val path: String,
    private[fastmcp] val handler: HttpRouteRequest => UIO[HttpRouteResponse]
):
  override def toString: String = s"HttpRoute(GET $path)"

object HttpRoute:

  /** The one method a route answers. */
  private[fastmcp] val Method: String = "GET"

  /** A `GET` route on `path`, which starts with `/` and is not the MCP endpoint (both are checked
    * when the HTTP transport starts, together with the rules `httpEndpoint` itself is held to).
    */
  def get(path: String)(handler: HttpRouteRequest => UIO[HttpRouteResponse]): HttpRoute =
    new HttpRoute(path, handler)

/** What a route's handler sees of the request: its path and its headers. */
final class HttpRouteRequest private[fastmcp] (
    val path: String,
    lookup: String => Option[String]
):

  /** A request header by name, case-insensitively. A header sent more than once is seen as one
    * comma-joined value. These are the client's own words: nothing here is authenticated.
    */
  def header(name: String): Option[String] =
    if HttpRouteResponse.isToken(name) then lookup(name) else None

  override def toString: String = s"HttpRouteRequest(GET $path)"

/** The reply of a route: a status, a text body and its content type, and any further headers. */
final class HttpRouteResponse private (
    val status: Int,
    val body: String,
    val contentType: String,
    val headers: List[(String, String)]
):

  /** This reply with one more header; it replaces one of the same name. The name must be an HTTP
    * field name and the value printable ASCII; `Content-Type`, `Content-Length`,
    * `Transfer-Encoding` and `Connection` are the server's.
    */
  def withHeader(name: String, value: String): HttpRouteResponse =
    require(HttpRouteResponse.isToken(name), s"'$name' is not an HTTP header name")
    require(
      !HttpRouteResponse.Reserved.contains(name.toLowerCase(java.util.Locale.ROOT)),
      s"the '$name' header is set by the server"
    )
    require(HttpRouteResponse.isFieldValue(value), s"the value of '$name' is not printable ASCII")
    new HttpRouteResponse(
      status,
      body,
      contentType,
      headers.filterNot(_._1.equalsIgnoreCase(name)) :+ (name -> value)
    )

  override def toString: String = s"HttpRouteResponse($status, $contentType)"

object HttpRouteResponse:

  private val Reserved: Set[String] =
    Set("content-type", "content-length", "transfer-encoding", "connection")

  /** RFC 9110 `token`: what a header name is made of. */
  private[fastmcp] def isToken(name: String): Boolean =
    name.nonEmpty && name.forall(ch =>
      (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') ||
        "!#$%&'*+-.^_`|~".indexOf(ch.toInt) >= 0
    )

  /** Printable ASCII and tab: no line break can end the header early. */
  private def isFieldValue(value: String): Boolean =
    value.forall(ch => ch == '\t' || (ch >= 0x20 && ch <= 0x7e))

  /** A reply with `status`, `body` and `contentType`. The status is 200 to 599 and none of 204, 205
    * and 304, which carry no body (answer 200 with an empty one).
    */
  def apply(status: Int, body: String, contentType: String): HttpRouteResponse =
    require(
      status >= 200 && status <= 599 && status != 204 && status != 205 && status != 304,
      s"HTTP status $status cannot be a route's reply (200 to 599, not 204, 205 or 304)"
    )
    require(
      contentType.nonEmpty && isFieldValue(contentType),
      "the content type is not printable ASCII"
    )
    new HttpRouteResponse(status, body, contentType, Nil)

  /** A `text/plain; charset=utf-8` reply. */
  def text(body: String, status: Int = 200): HttpRouteResponse =
    apply(status, body, "text/plain; charset=utf-8")

  /** An `application/json` reply; `body` is JSON text the caller has already encoded. */
  def json(body: String, status: Int = 200): HttpRouteResponse =
    apply(status, body, "application/json")
