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
  * @param body
  *   reads the request body as UTF-8 text; failures surface as a `400` transport error
  */
private[fastmcp] final case class HttpRequest(
    method: String,
    path: String,
    header: String => Option[String],
    body: IO[Throwable, String]
)

/** One server-sent event: an optional `event:` type and the `data:` payload. */
private[fastmcp] final case class SseFrame(event: Option[String], data: String):

  /** Wire form per the SSE spec: `event: <type>` (when present), one `data:` line per payload line,
    * then the blank line that dispatches the event. Mirrors zio-http's `ServerSentEvent` encoding
    * so the socket and Netty backends emit identical bytes.
    */
  def encode: String =
    val sb = new StringBuilder
    event.foreach(e => sb.append("event: ").append(e).append('\n'))
    // `linesIterator` yields nothing for "", so an empty payload produces no data line — exactly
    // what zio-http emits for its keepalive ping.
    data.linesIterator.foreach(line => sb.append("data: ").append(line).append('\n'))
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

  /** A `200 text/event-stream` response whose body is the frame stream; the response ends when the
    * stream does (or the client disconnects, which runs the stream's finalizers).
    */
  final case class Sse(headers: List[(String, String)], frames: ZStream[Any, Nothing, SseFrame])
      extends HttpReply:
    def status: Int = 200
