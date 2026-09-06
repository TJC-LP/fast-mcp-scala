package com.tjclp.fastmcp.server.transport

import zio.json.ast.Json

import com.tjclp.fastmcp.core.{ErrorCodes, Protocol}
import com.tjclp.fastmcp.jsonrpc.{JsonRpcMessage, McpError}
import com.tjclp.fastmcp.server.router.{McpRouter, RequestContext}

/** HTTP validation for the 2026-07-28 stateless POST path, shared by both platform backends.
  *
  * The platform supplies header lookup as a `String => Option[String]` (the same seam
  * [[HttpHeaderValidation]] uses) and maps the returned `Int` status codes onto its own response
  * type. Note the platform backends apply a lenient pre-2026 Accept gate first (absent header =
  * accept anything, wildcard subtypes honored); this object's Accept check is the strict 2026-07-28
  * one — header required, only the literal `*"/"*` wildcard, and both `application/json` and
  * `text/event-stream` demanded. Unifying the two is deliberately out of scope here.
  */
private[fastmcp] object ModernHttpValidation:

  /** Router failures that must not ride a 200 SSE response: answered as HTTP 400. */
  private val BadRequestCodes = Set(
    ErrorCodes.HeaderMismatch,
    ErrorCodes.MissingRequiredClientCapability,
    ErrorCodes.UnsupportedProtocolVersion
  )

  def errorStatus(message: JsonRpcMessage): Option[Int] =
    message match
      case JsonRpcMessage.Failure(_, error) if BadRequestCodes.contains(error.code) =>
        Some(400)
      case _ => None

  private def bodyDeclaredVersion(message: JsonRpcMessage): Option[String] =
    message match
      case JsonRpcMessage.Request(_, _, params) =>
        RequestContext.declaredProtocolVersion(params.getOrElse(Json.Null))
      case _ => None

  /** A POST takes the 2026-07-28 stateless path when the body declares a protocol version or the
    * header carries one that is not a known legacy version.
    */
  def isModern(header: String => Option[String], message: JsonRpcMessage): Boolean =
    bodyDeclaredVersion(message).isDefined ||
      header("mcp-protocol-version").exists(version =>
        !Protocol.LegacyProtocolVersions.contains(version)
      )

  /** The version the client asked for: header first, else the body's `_meta` declaration. */
  def requestedVersion(
      header: String => Option[String],
      message: JsonRpcMessage
  ): Option[String] =
    header("mcp-protocol-version").orElse(bodyDeclaredVersion(message))

  /** Classify the requested version BEFORE decoding `_meta`, so a request that only reached the
    * modern path through an unknown version fails `-32022` with `data.supported` instead of a
    * misleading `-32602` about a missing `_meta` object.
    */
  private def supportedVersion(
      header: String => Option[String],
      rpc: JsonRpcMessage.Request
  ): Either[(Int, McpError), Unit] =
    requestedVersion(header, rpc) match
      case Some(version) if !Protocol.SupportedProtocolVersions.contains(version) =>
        Left(
          400 -> McpError.unsupportedProtocolVersion(version, List(Protocol.LatestProtocolVersion))
        )
      case _ => Right(())

  private def acceptOk(header: String => Option[String]): Boolean =
    header("accept").exists { value =>
      val lower = value.toLowerCase
      (lower.contains("*/*") || lower.contains("application/json")) &&
      (lower.contains("*/*") || lower.contains("text/event-stream"))
    }

  private def contentTypeOk(header: String => Option[String]): Boolean =
    header("content-type").exists(_.toLowerCase.contains("application/json"))

  def validateNotification[R](
      router: McpRouter[R],
      notification: JsonRpcMessage.Notification,
      header: String => Option[String]
  ): Either[(Int, McpError), Unit] =
    for
      _ <- Either.cond(
        contentTypeOk(header),
        (),
        415 -> McpError.headerMismatch("Content-Type must be application/json")
      )
      _ <- Either.cond(
        acceptOk(header),
        (),
        406 -> McpError.headerMismatch(
          "Accept must include application/json and text/event-stream"
        )
      )
      version <- header("mcp-protocol-version").toRight(
        400 -> McpError.headerMismatch("Missing required MCP-Protocol-Version header")
      )
      _ <- Either.cond(
        version == Protocol.LatestProtocolVersion,
        (),
        400 -> McpError.unsupportedProtocolVersion(
          version,
          List(Protocol.LatestProtocolVersion)
        )
      )
      _ <- router.validateHttpMethod(notification.method, header).left.map(400 -> _)
    yield ()

  /** Same checks as [[validateRequest]], returning the decoded [[RequestContext]] so the transport
    * can hand it to `McpRouter.dispatchModern` and skip the second `RequestContext.decode`. Frames
    * reaching this point have already passed the input limits in `MessageLoop.parseFrame`.
    */
  def validateRequestContext[R](
      router: McpRouter[R],
      rpc: JsonRpcMessage.Request,
      header: String => Option[String]
  ): Either[(Int, McpError), RequestContext] =
    for
      _ <- Either.cond(
        contentTypeOk(header),
        (),
        415 -> McpError.headerMismatch("Content-Type must be application/json")
      )
      _ <- Either.cond(
        acceptOk(header),
        (),
        406 -> McpError.headerMismatch(
          "Accept must include application/json and text/event-stream"
        )
      )
      _ <- supportedVersion(header, rpc)
      context <- RequestContext
        .decode(rpc.params.getOrElse(Json.Null))
        .left
        .map(400 -> _)
      headerVersion <- header("mcp-protocol-version").toRight(
        400 -> McpError.headerMismatch("Missing required MCP-Protocol-Version header")
      )
      _ <- Either.cond(
        headerVersion == context.protocolVersion,
        (),
        400 -> McpError.headerMismatch(
          "MCP-Protocol-Version header does not match request metadata"
        )
      )
      _ <- Either.cond(
        context.protocolVersion == Protocol.LatestProtocolVersion,
        (),
        400 -> McpError.unsupportedProtocolVersion(
          context.protocolVersion,
          List(Protocol.LatestProtocolVersion)
        )
      )
      _ <- router.validateHttpHeaders(rpc, header).left.map(400 -> _)
      _ <- Either.cond(
        router.hasModernMethod(rpc.method),
        (),
        404 -> McpError.methodNotFound(rpc.method)
      )
    yield context

  /** Signature-stable form of [[validateRequestContext]] (both platform backends declare
    * `Either[(Int, McpError), Unit]` return types around it).
    */
  def validateRequest[R](
      router: McpRouter[R],
      rpc: JsonRpcMessage.Request,
      header: String => Option[String]
  ): Either[(Int, McpError), Unit] =
    validateRequestContext(router, rpc, header).map(_ => ())
