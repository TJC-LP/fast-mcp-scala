package com.tjclp.fastmcp.server.transport

import zio.*
import zio.json.*

import com.tjclp.fastmcp.jsonrpc.*
import com.tjclp.fastmcp.jsonrpc.JsonRpcMessage.*
import com.tjclp.fastmcp.server.router.{McpRouter, Session}

/** Platform-neutral wire ↔ dispatch bridge. Both the JVM and JS transports reduce to "get bytes,
  * call [[handleFrame]], write bytes" — all parsing, dispatch, and error framing lives here so the
  * platform code stays a thin I/O adapter.
  */
object MessageLoop:

  /** Process one inbound JSON-RPC frame (a single message — batching was dropped from the spec).
    *
    * Returns the serialized response frame, or `None` for notifications / cancelled requests /
    * inbound responses (which produce no reply). Never fails: malformed JSON becomes a JSON-RPC
    * parse-error response; dispatch failures are already mapped to error responses by the router.
    */
  def handleFrame[R](router: McpRouter[R], session: Session, frame: String): URIO[R, Option[String]] =
    frame.fromJson[JsonRpcMessage] match
      case Right(message) =>
        router.dispatch(session, message).map(_.map(_.toJson))
      case Left(parseError) =>
        // Per JSON-RPC, a parse error gets an error response with a null id.
        val failure: JsonRpcMessage =
          Failure(None, McpError.parseError(s"Parse error: $parseError").toErrorObject)
        ZIO.some(failure.toJson)

  /** Serialize a server-initiated outbound message (log notification, progress, sampling/elicit
    * request) drained from [[Session.outbound]].
    */
  def encodeOutbound(message: JsonRpcMessage): String = message.toJson
