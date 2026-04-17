package com.tjclp.fastmcp
package server

import scala.scalajs.js

import com.tjclp.fastmcp.facades.server as tsdk

/** JS-platform [[McpContext]] subclass. Carries the TS SDK's `Server` and the per-request
  * `RequestHandlerExtra` as opaque tokens — extension methods in this file surface the data a
  * typed-contract handler might need (client name/version, declared capabilities).
  *
  * Mirrors [[com.tjclp.fastmcp.server.JvmMcpContext]] on the JVM side.
  */
final class JsMcpContext private[server] (
    private[fastmcp] val jsServerToken: Option[tsdk.Server],
    private[fastmcp] val jsRequestExtraToken: Option[tsdk.RequestHandlerExtra]
) extends McpContext:

  private[fastmcp] def jsServer: Option[tsdk.Server] = jsServerToken
  private[fastmcp] def jsRequestExtra: Option[tsdk.RequestHandlerExtra] = jsRequestExtraToken

object JsMcpContext:

  def apply(
      jsServer: tsdk.Server,
      jsRequestExtra: tsdk.RequestHandlerExtra
  ): JsMcpContext =
    new JsMcpContext(Some(jsServer), Some(jsRequestExtra))

  /** Extension API that surfaces TS-SDK client info from any `McpContext`. Non-matching JVM
    * contexts fall through to `None`.
    */
  extension (ctx: McpContext)

    def jsServer: Option[tsdk.Server] =
      ctx match
        case js: JsMcpContext => js.jsServerToken
        case _ => None

    def jsRequestExtra: Option[tsdk.RequestHandlerExtra] =
      ctx match
        case js: JsMcpContext => js.jsRequestExtraToken
        case _ => None

    /** Client name and version as declared during `initialize`. */
    def getClientInfo: Option[ClientInfo] =
      ctx.jsServer.flatMap { server =>
        server.getClientVersion().toOption.map(impl => ClientInfo(impl.name, impl.version))
      }

    /** Raw client capabilities object as declared during `initialize`. */
    def getClientCapabilities: Option[js.Object] =
      ctx.jsServer.flatMap(_.getClientCapabilities().toOption)

    /** Session id, if the transport assigns one (streamable HTTP does; stdio does not). */
    def getSessionId: Option[String] =
      ctx.jsRequestExtra.flatMap(_.sessionId.toOption)

  /** Light Scala mirror of the TS SDK's `Implementation` — just `{name, version}`. */
  final case class ClientInfo(name: String, version: String)
