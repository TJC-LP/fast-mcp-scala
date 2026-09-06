package com.tjclp.fastmcp.server.router

import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.LoggingLevel
import com.tjclp.fastmcp.core.wire.{ClientCapabilities, Implementation}
import com.tjclp.fastmcp.jsonrpc.{JsonFields, McpError}

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

  /** Linear lookups ([[JsonFields]]) — this runs on every request before dispatch, so it must never
    * build a map from client-chosen keys.
    */
  def declaredProtocolVersion(params: Json): Option[String] =
    JsonFields
      .get(params, "_meta")
      .flatMap(JsonFields.get(_, ProtocolVersionKey))
      .collect { case Json.Str(value) => value }

  /** Top-level lookups are linear; the `meta` / `inputResponses` maps (public fields) are built
    * with `toMap` over objects already bounded by `limits.maxObjectFields` at the transport choke
    * point (`MessageLoop.parseFrame`).
    */
  def decode(params: Json): Either[McpError, RequestContext] =
    for
      fields <- params match
        case Json.Obj(value) => Right(value)
        case _ => Left(McpError.invalidParams("request params must be an object"))
      meta <- JsonFields.get(fields, "_meta") match
        case Some(Json.Obj(value)) => Right(value.toMap)
        case _ => Left(McpError.invalidParams("request params must include an object `_meta`"))
      version <- meta.get(ProtocolVersionKey) match
        case Some(Json.Str(value)) => Right(value)
        case _ =>
          Left(McpError.invalidParams(s"request `_meta` must include `$ProtocolVersionKey`"))
      capabilities <- decodeRequired[ClientCapabilities](meta, ClientCapabilitiesKey)
      clientInfo <- decodeOptional[Implementation](meta, ClientInfoKey)
      logLevel <- decodeOptional[LoggingLevel](meta, LogLevelKey)
      inputResponses <- JsonFields.get(fields, "inputResponses") match
        case None => Right(Map.empty[String, Json])
        case Some(Json.Obj(values)) => Right(values.toMap)
        case Some(_) => Left(McpError.invalidParams("`inputResponses` must be an object"))
      requestState <- JsonFields.get(fields, "requestState") match
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
