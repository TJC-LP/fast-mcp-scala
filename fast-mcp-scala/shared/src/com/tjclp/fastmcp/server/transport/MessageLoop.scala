package com.tjclp.fastmcp.server.transport

import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.codec.JsonLimits
import com.tjclp.fastmcp.jsonrpc.*
import com.tjclp.fastmcp.jsonrpc.JsonRpcMessage.*
import com.tjclp.fastmcp.server.LimitSettings
import com.tjclp.fastmcp.server.router.{McpRouter, Methods, Session}

/** Platform-neutral wire ↔ dispatch bridge. Every transport — JVM, Scala.js, Scala Native — reduces
  * to "get bytes, call [[handleFrame]], write bytes"; all parsing, dispatch, and error framing
  * lives here so the platform code stays a thin I/O adapter. The stdio *lifecycle* around this
  * (drainer fiber, forked dispatch, EOF teardown) lives in [[StdioLoop]].
  */
object MessageLoop:

  /** Ignore blank stdio lines only when they fit the frame limit. An oversized prefix retained by
    * the line splitter must reach `parseFrame` unchanged, even if it is entirely whitespace;
    * trimming it could erase the overflow or turn a truncated invalid line into a valid request.
    */
  private[fastmcp] def shouldDispatchStdioFrame(frame: String, limits: LimitSettings): Boolean =
    frame.length > limits.maxFrameChars || frame.trim.nonEmpty

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
    dispatchParsed(router, session, parseFrame(frame, router.limits))

  /** The second half of [[handleFrame]], for a transport that has to look at the parsed frame
    * before it decides how to run it (stdio answers `initialize` before it reads on).
    */
  private[fastmcp] def dispatchParsed[R](
      router: McpRouter[R],
      session: Session,
      parsed: Either[JsonRpcMessage, JsonRpcMessage]
  ): URIO[R, Option[String]] =
    parsed match
      case Right(message) =>
        router.dispatch(session, message).map(_.map(_.toJson))
      case Left(parseFailure) =>
        ZIO.some(parseFailure.toJson)

  /** True when stdio must finish this frame before it reads the next one: the legacy `initialize`
    * request. Every other frame is dispatched concurrently, so a pipelined client — `initialize`,
    * `notifications/initialized` and a first request written in one burst — would otherwise race
    * its own handshake and be told `-32600 Server not initialized`. A request that carries its own
    * protocol version (MCP 2026-07-28) never waits for a handshake; one named `initialize` is
    * answered `-32601` and costs nothing to run in line.
    */
  private[fastmcp] def isOrderedStdioFrame(
      parsed: Either[JsonRpcMessage, JsonRpcMessage]
  ): Boolean =
    parsed.exists(message => isInitialize(message) || isClientResponse(message))

  /** The client's answer to a server→client request (sampling, roots, elicitation). It only
    * completes a waiting promise, so it runs in line: at stdin EOF every answer already read has
    * reached its handler before the session stops accepting answers ([[Session.closeInbound]]).
    */
  private def isClientResponse(message: JsonRpcMessage): Boolean =
    message match
      case _: Success | Failure(Some(_), _) => true
      case _ => false

  /** Parse one frame under `limits`. `Left` carries the ready-to-send JSON-RPC parse-error response
    * (null id, per JSON-RPC).
    *
    * This is the single choke point for the inbound input limits on every transport: frame length,
    * nesting depth, per-object member count, per-array element count, total value count and the
    * digits / exponent of every number are checked on the raw text ([[JsonLimits.preScan]]) BEFORE
    * zio-json allocates a node, and re-checked iteratively on the AST ([[JsonLimits.validate]])
    * before the envelope is decoded. Limit violations are parser constraints and answer `-32700`
    * like any other unparseable frame (JSON-RPC 2.0 §5.1 — the same mapping Jackson's
    * `StreamReadConstraints` get in the MCP Java SDK); we deliberately do not echo an id taken from
    * a frame we refused to parse.
    *
    * HTTP transports parse — and therefore enforce limits — *before* touching session state, so a
    * malformed or oversized body can never mint a session; stdio just feeds both arms to the
    * writer. The 1-arg form keeps default limits for callers without a router; transports pass
    * `router.limits`.
    */
  def parseFrame(
      frame: String,
      limits: LimitSettings = LimitSettings()
  ): Either[JsonRpcMessage, JsonRpcMessage] =
    JsonLimits.preScan(frame, limits.maxFrameChars, limits.jsonBounds) match
      case Some(violation) => Left(parseFailure(violation.message))
      case None =>
        frame.fromJson[Json].left.map(parseFailure).flatMap { json =>
          JsonLimits.validate(json, limits.jsonBounds) match
            case Some(violation) => Left(parseFailure(violation.message))
            // AST path: the envelope decoder is `mapOrFail`, whose `unsafeFromJsonAST` override
            // applies straight to the node — no re-encode.
            case None => json.as[JsonRpcMessage].left.map(parseFailure)
        }

  private def parseFailure(detail: String): JsonRpcMessage =
    Failure(None, McpError.parseError(s"Parse error: $detail").toErrorObject)

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
    * the transports' `ensuring` on the dispatch fiber and filtered out of the stream. The stdio
    * loops offer it to the session's outbound channel once, after the last reply at stdin EOF, to
    * tell the stdout writer that everything queued before it has been written. It never reaches the
    * wire.
    */
  private[fastmcp] val CloseSentinel: JsonRpcMessage =
    Notification("$fastmcp/internal/close", None)

  private[fastmcp] def isCloseSentinel(message: JsonRpcMessage): Boolean =
    message == CloseSentinel

  /** Heartbeat marker a timer fiber offers into a per-request queue. Like [[CloseSentinel]] it
    * never reaches the wire as a JSON-RPC message: the transports turn it into an SSE heartbeat or
    * drop it. Offering it — instead of timing out the `take` that waits for the next frame — is
    * what keeps a heartbeat from ever discarding a reply (TJC-2337: an interrupted `take` whose
    * promise already held the reply lost it).
    */
  private[fastmcp] val KeepAliveSentinel: JsonRpcMessage =
    Notification("$fastmcp/internal/keepalive", None)

  private[fastmcp] def isKeepAliveSentinel(message: JsonRpcMessage): Boolean =
    message == KeepAliveSentinel
