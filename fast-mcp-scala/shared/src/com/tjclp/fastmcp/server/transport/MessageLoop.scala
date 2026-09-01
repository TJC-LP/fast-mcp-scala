package com.tjclp.fastmcp.server.transport

import zio.*
import zio.json.*

import com.tjclp.fastmcp.jsonrpc.*
import com.tjclp.fastmcp.jsonrpc.JsonRpcMessage.*
import com.tjclp.fastmcp.server.router.{McpRouter, Methods, Session}

/** Platform-neutral wire ↔ dispatch bridge. Every transport — JVM, Scala.js, Scala Native — reduces
  * to "get bytes, call [[handleFrame]], write bytes"; all parsing, dispatch, and error framing
  * lives here so the platform code stays a thin I/O adapter. The stdio *lifecycle* around this
  * (drainer fiber, forked dispatch, EOF teardown) lives in [[StdioLoop]].
  */
object MessageLoop:

  /** Process one inbound JSON-RPC frame (a single message — batching was dropped from the spec).
    *
    * Returns the serialized response frame, or `None` for notifications / cancelled requests /
    * inbound responses (which produce no reply). Never fails: malformed JSON becomes a JSON-RPC
    * parse-error response; dispatch failures are already mapped to error responses by the router.
    */
  def handleFrame[R](
      router: McpRouter[R],
      session: Session,
      frame: String
  ): URIO[R, Option[String]] =
    parseFrame(frame) match
      case Right(message) =>
        router.dispatch(session, message).map(_.map(_.toJson))
      case Left(parseFailure) =>
        ZIO.some(parseFailure.toJson)

  /** Parse one frame. `Left` carries the ready-to-send JSON-RPC parse-error response (null id, per
    * JSON-RPC). HTTP transports parse *before* touching session state so a malformed body can never
    * mint a session; stdio just feeds both arms to the writer.
    */
  def parseFrame(frame: String): Either[JsonRpcMessage, JsonRpcMessage] =
    frame
      .fromJson[JsonRpcMessage]
      .left
      .map(parseError =>
        Failure(None, McpError.parseError(s"Parse error: $parseError").toErrorObject)
      )

  /** True for the legacy `initialize` request — the only compatibility frame allowed to mint an
    * initialization-era Streamable HTTP session.
    */
  def isInitialize(message: JsonRpcMessage): Boolean =
    message match
      case Request(_, Methods.Initialize, _) => true
      case _ => false

  /** Serialize a server-initiated outbound message (log notification, progress, sampling/elicit
    * request) drained from [[Session.outbound]].
    */
  def encodeOutbound(message: JsonRpcMessage): String = message.toJson

  /** Internal frame offered to a per-request SSE queue when its dispatch ends without a final reply
    * — a `notifications/cancelled` interruption emits no response, and the stream's
    * `takeUntil(isFinalReply)` would otherwise hold the HTTP connection open forever. Offered by
    * the transports' `ensuring` on the dispatch fiber and filtered out of the stream; only
    * per-request queues ever carry it (stdio's outbound path cannot see it).
    */
  private[fastmcp] val CloseSentinel: JsonRpcMessage =
    Notification("$fastmcp/internal/close", None)

  private[fastmcp] def isCloseSentinel(message: JsonRpcMessage): Boolean =
    message == CloseSentinel
