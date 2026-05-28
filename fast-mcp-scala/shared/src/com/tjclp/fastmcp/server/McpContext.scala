package com.tjclp.fastmcp
package server

import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.{LoggingLevel, LoggingMessageNotificationParams, ProgressToken}
import com.tjclp.fastmcp.core.wire.{NotificationMethods, ProgressNotificationParams}
import com.tjclp.fastmcp.jsonrpc.JsonRpcMessage
import com.tjclp.fastmcp.server.router.Session

/** Platform-independent MCP request context handed to handlers as `Option[McpContext]`.
  *
  * In the native core there is no per-platform subclass: the context simply carries the request's
  * [[Session]], through which a handler can read the connection id and push server→client messages
  * (log notifications, progress) over the session's outbound channel. The transport drains that
  * channel to the wire.
  */
open class McpContext private[fastmcp] (
    private[fastmcp] val session: Option[Session] = None
):

  /** The connection/session id, if this request arrived over a session-bearing transport. */
  def sessionId: Option[String] = session.map(_.sessionId)

  /** Emit a `notifications/message` log to the client, honoring the client's `logging/setLevel`
    * threshold. No-op if no session (e.g. a direct in-process call) or below the set level.
    */
  def sendLogMessage(
      level: LoggingLevel,
      data: Json,
      logger: Option[String] = None
  ): UIO[Unit] =
    session match
      case None => ZIO.unit
      case Some(s) =>
        s.logLevel.flatMap {
          case Some(min) if level.severity < min.severity => ZIO.unit
          case _ =>
            val params = LoggingMessageNotificationParams(level, data, logger)
            s.send(JsonRpcMessage.Notification(NotificationMethods.Message, params.toJsonAST.toOption))
        }

  /** Emit a `notifications/progress` update for the given progress token. No-op without a session. */
  def sendProgress(
      progressToken: ProgressToken,
      progress: Double,
      total: Option[Double] = None,
      message: Option[String] = None
  ): UIO[Unit] =
    session match
      case None => ZIO.unit
      case Some(s) =>
        val params = ProgressNotificationParams(progressToken, progress, total, message)
        s.send(JsonRpcMessage.Notification(NotificationMethods.Progress, params.toJsonAST.toOption))

object McpContext:
  /** Default empty context — used by macros / direct calls when no session is available. */
  def empty: McpContext = new McpContext

  /** Context bound to a request's session (used by the built-in handlers). */
  def withSession(session: Session): McpContext = new McpContext(Some(session))
