package com.tjclp.fastmcp.server.router

import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.LoggingLevel
import com.tjclp.fastmcp.core.wire.{ClientCapabilities, Implementation}
import com.tjclp.fastmcp.jsonrpc.McpError

/** The 2026-07-28 metadata and retry payload bound to one request fiber. It is deliberately
  * fiber-local: the protocol is stateless and concurrent calls from one stdio connection must never
  * observe one another's client identity, capabilities, log level, or MRTR inputs.
  */
final case class RequestContext(
    protocolVersion: String,
    clientCapabilities: ClientCapabilities,
    clientInfo: Option[Implementation],
    logLevel: Option[LoggingLevel],
    meta: Map[String, Json],
    inputResponses: Map[String, Json],
    requestState: Option[String]
)

object RequestContext:
  val ProtocolVersionKey = "io.modelcontextprotocol/protocolVersion"
  val ClientCapabilitiesKey = "io.modelcontextprotocol/clientCapabilities"
  val ClientInfoKey = "io.modelcontextprotocol/clientInfo"
  val LogLevelKey = "io.modelcontextprotocol/logLevel"

  def declaredProtocolVersion(params: Json): Option[String] =
    params match
      case Json.Obj(fields) =>
        fields.toMap.get("_meta") match
          case Some(Json.Obj(meta)) =>
            meta.toMap.get(ProtocolVersionKey).collect { case Json.Str(value) => value }
          case _ => None
      case _ => None

  def decode(params: Json): Either[McpError, RequestContext] =
    for
      fields <- params match
        case Json.Obj(value) => Right(value.toMap)
        case _ => Left(McpError.invalidParams("request params must be an object"))
      meta <- fields.get("_meta") match
        case Some(Json.Obj(value)) => Right(value.toMap)
        case _ => Left(McpError.invalidParams("request params must include an object `_meta`"))
      version <- meta.get(ProtocolVersionKey) match
        case Some(Json.Str(value)) => Right(value)
        case _ =>
          Left(McpError.invalidParams(s"request `_meta` must include `$ProtocolVersionKey`"))
      capabilities <- decodeRequired[ClientCapabilities](meta, ClientCapabilitiesKey)
      clientInfo <- decodeOptional[Implementation](meta, ClientInfoKey)
      logLevel <- decodeOptional[LoggingLevel](meta, LogLevelKey)
      inputResponses <- fields.get("inputResponses") match
        case None => Right(Map.empty[String, Json])
        case Some(Json.Obj(values)) => Right(values.toMap)
        case Some(_) => Left(McpError.invalidParams("`inputResponses` must be an object"))
      requestState <- fields.get("requestState") match
        case None => Right(None)
        case Some(Json.Str(value)) => Right(Some(value))
        case Some(_) => Left(McpError.invalidParams("`requestState` must be a string"))
    yield RequestContext(
      version,
      capabilities,
      clientInfo,
      logLevel,
      meta,
      inputResponses,
      requestState
    )

  private def decodeRequired[A: JsonDecoder](
      meta: Map[String, Json],
      key: String
  ): Either[McpError, A] =
    meta
      .get(key)
      .toRight(McpError.invalidParams(s"request `_meta` must include `$key`"))
      .flatMap(_.as[A].left.map(err => McpError.invalidParams(s"invalid `$key`: $err")))

  private def decodeOptional[A: JsonDecoder](
      meta: Map[String, Json],
      key: String
  ): Either[McpError, Option[A]] =
    meta.get(key) match
      case None => Right(None)
      case Some(value) =>
        value.as[A].left.map(err => McpError.invalidParams(s"invalid `$key`: $err")).map(Some(_))
