package com.tjclp.fastmcp
package server.transport.http

import zio.*
import zio.stream.*

import com.tjclp.fastmcp.jsonrpc.JsonRpcMessage
import com.tjclp.fastmcp.server.transport.MessageLoop

/** The platform-neutral view of one HTTP request that [[StreamableHttpHandler]] consumes. Each HTTP
  * backend adapts its own request type into this (zio-http `Request`, a parsed socket request, …)
  * so the streamable-HTTP MCP semantics are written exactly once.
  *
  * @param method
  *   upper-case HTTP method token (`POST`, `GET`, `DELETE`, …)
  * @param path
  *   the request-target path with any query string stripped
  * @param header
  *   case-insensitive header lookup by name, first value wins (the same seam
  *   [[com.tjclp.fastmcp.server.transport.ModernHttpValidation]] already uses)
  * @param gateHeader
  *   header lookup for the admission gates
  *   ([[com.tjclp.fastmcp.server.transport.HttpRequestGuards]]): a header sent more than once is
  *   seen as its `", "`-joined value, so a duplicated `Host` / `Origin` fails the origin parser
  *   closed (403) instead of only its first value being checked
  * @param body
  *   reads the request body as UTF-8 text; failures surface as a `400` transport error. Backends
  *   make this lazy so a request the header gates reject never costs the read
  * @param clientKey
  *   the TCP peer address, when the backend knows it — the default owner key for bearer-task
  *   buckets (`TaskOwnerKey.Transport`). Never derived from request content
  * @param headerNames
  *   every header name of the request as received, in any case, one entry per field line — a header
  *   sent twice appears twice. Read once per POST by
  *   [[com.tjclp.fastmcp.server.transport.HttpHeaderValidation.repeatedHeader]], which refuses a
  *   repeated routing header instead of letting `header` pick its first value. A backend that can
  *   see repeats MUST supply it: the default (no names) disables that check
  */
private[fastmcp] final case class HttpRequest(
    method: String,
    path: String,
    header: String => Option[String],
    gateHeader: String => Option[String],
    body: IO[Throwable, String],
    clientKey: Option[String] = None,
    headerNames: () => Iterator[String] = HttpRequest.NoHeaderNames
)

private[fastmcp] object HttpRequest:

  /** For requests built without a wire behind them (tests driving the handler directly). */
  val NoHeaderNames: () => Iterator[String] = () => Iterator.empty

/** One server-sent event: an optional `event:` type, the `data:` payload, and an optional `retry:`
  * field (the reconnection delay in milliseconds, `McpServerSettings.sseRetryInterval`). A frame
  * with neither type nor payload and only `retry` is how a stream announces the delay when it opens
  * ([[HttpReply.Sse.opening]]): clients store the value and dispatch no event for it.
  */
private[fastmcp] final case class SseFrame(
    event: Option[String],
    data: String,
    retry: Option[Long] = None
):

  /** Wire form per the SSE spec: `retry: <ms>` (when present), `event: <type>` (when present), one
    * `data:` line per payload line, then the blank line that dispatches the event. Mirrors
    * zio-http's `ServerSentEvent` encoding so the socket and Netty backends emit identical bytes
    * for a message. A frame that carries `retry` is written with this encoder on every backend: it
    * is either the one frame of an [[HttpReply.SseComplete]] or the [[HttpReply.Sse.opening]] of a
    * stream, and neither goes through zio-http's encoder.
    */
  def encode: String =
    val sb = new java.lang.StringBuilder(data.length + 32)
    retry.foreach(ms => sb.append("retry: ").append(ms).append('\n'))
    event.foreach(e => sb.append("event: ").append(e).append('\n'))
    // An empty payload produces no data line. (zio-http 3.11.4 writes an empty `data:` line for an
    // event without payload, so its keepalive ping is `event: ping`, `data: `, blank line where
    // this encoder's is `event: ping`, blank line; clients skip the `ping` type either way.)
    // JSON-RPC payloads are single-line (the encoder never emits a raw newline), so the common case
    // is one append with no line splitting.
    if data.nonEmpty then
      if data.indexOf('\n') < 0 && data.indexOf('\r') < 0 then
        sb.append("data: ").append(data).append('\n')
      else data.linesIterator.foreach(line => sb.append("data: ").append(line).append('\n'))
    sb.append('\n')
    sb.toString

private[fastmcp] object SseFrame:

  /** A JSON-RPC message as the `message` event every MCP client parses. */
  def message(m: JsonRpcMessage): SseFrame =
    SseFrame(Some("message"), MessageLoop.encodeOutbound(m))

  /** Keepalive heartbeat. The `ping` event type is ignored by conforming clients (the TS SDK only
    * parses `message` events).
    */
  val Ping: SseFrame = SseFrame(Some("ping"), "")

  /** The frame that opens a stream with the reconnection delay: `retry: <ms>` and a blank line. */
  def retry(millis: Long): SseFrame = SseFrame(None, "", Some(millis))

/** The platform-neutral reply [[StreamableHttpHandler]] produces. Backends render it onto their own
  * response type. Header names are given in their canonical wire form; HTTP header matching is
  * case-insensitive.
  */
private[fastmcp] sealed trait HttpReply:
  def status: Int
  def headers: List[(String, String)]

private[fastmcp] object HttpReply:

  /** Headers only — `202 Accepted`, `405`, `200` on DELETE, … */
  final case class Empty(status: Int, headers: List[(String, String)] = Nil) extends HttpReply

  /** An `application/json` body (a JSON-RPC reply or a transport-level JSON-RPC error). */
  final case class Json(status: Int, body: String, headers: List[(String, String)] = Nil)
      extends HttpReply

  /** The reply of a route the server author registered (`McpServerSettings.httpRoutes`): a text
    * body with the content type its handler chose. Never an MCP reply.
    *
    * `headOnly` marks the reply to a `HEAD`: the backend sends the status, the headers and the
    * `Content-Length` of `body` — what the `GET` would have sent (RFC 9110 §9.3.2) — and not the
    * body itself.
    */
  final case class Body(
      status: Int,
      body: String,
      contentType: String,
      headers: List[(String, String)] = Nil,
      headOnly: Boolean = false
  ) extends HttpReply

  /** A `200 text/event-stream` response whose body is the frame stream; the response ends when the
    * stream does (or the client disconnects, which runs the stream's finalizers).
    *
    * `frames` carries data frames only. `keepAlive`, when set, asks the backend to emit an
    * [[SseFrame.Ping]] whenever the stream has been quiet that long — a transport concern each
    * backend implements with what it has (a merged schedule on zio-http, the connection thread's
    * wait loop on the socket server), so no backend has to race a timer against the data stream.
    *
    * A streamed reply holds resources of the request it answers (its dispatch fiber and queue, a
    * session's GET slot). They are released exactly once, by whichever comes first:
    *   - `frames` ending or being interrupted, once a backend has started it (`frames` is run at
    *     most once);
    *   - `abandon`, which a backend that knows it will not run `frames` calls to release them at
    *     once. It does nothing when the stream was started or the reply was already released, so it
    *     is safe to call on every path that drops a reply;
    *   - [[StreamableHttpHandler]]'s own bound on how long a reply may sit unstarted, which covers
    *     every path that neither runs `frames` nor calls `abandon` (a request interrupted between
    *     the handler and the backend, a backend that drops the reply).
    * After a release by the last two, `frames` is an empty stream.
    *
    * `abandon` sits in its own parameter list: it is a handle on the reply's resources, not part of
    * the reply's value, and pattern matches on the reply stay as they were.
    *
    * `opening`, when set, is an event the backend writes as soon as the response body starts,
    * before it runs `frames` and before any heartbeat: the `retry` announcement of a legacy stream
    * (`McpServerSettings.sseRetryInterval`). It is not an element of `frames` because a backend
    * merges its heartbeat into those, and the first heartbeat of a stream is due at once: only
    * something written ahead of the merge is certain to come first. It is encoded with
    * [[SseFrame.encode]] on every backend. It shares the second parameter list so that the existing
    * matches on the three fields above keep their shape.
    */
  final case class Sse(
      headers: List[(String, String)],
      frames: ZStream[Any, Nothing, SseFrame],
      keepAlive: Option[Duration] = None
  )(val abandon: UIO[Unit], val opening: Option[SseFrame] = None)
      extends HttpReply:
    def status: Int = 200

  /** A `200 text/event-stream` response whose whole body is known before it starts: the reply to a
    * request whose first frame was already its final one, which is the common case. The event bytes
    * are those an [[Sse]] reply carrying that one frame would send; backends write them with a
    * `Content-Length` instead of running a stream, so nothing is pulled, chunked or finalized.
    */
  final case class SseComplete(headers: List[(String, String)], frame: SseFrame) extends HttpReply:
    def status: Int = 200
