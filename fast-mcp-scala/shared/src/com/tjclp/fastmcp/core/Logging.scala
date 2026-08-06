package com.tjclp.fastmcp.core

import zio.json.*
import zio.json.ast.Json

/** MCP log severity. Maps to RFC-5424 syslog severities (debug → emergency). Lower-cased on the
  * wire — codec normalizes case on decode.
  */
enum LoggingLevel:
  case Debug, Info, Notice, Warning, Error, Critical, Alert, Emergency

object LoggingLevel:

  /** Numerical severity used by middleware comparisons. Lower = more verbose, higher = more severe.
    * Matches RFC-5424 ordering.
    */
  extension (level: LoggingLevel)

    def severity: Int = level match
      case Debug => 0
      case Info => 1
      case Notice => 2
      case Warning => 3
      case Error => 4
      case Critical => 5
      case Alert => 6
      case Emergency => 7

  given JsonCodec[LoggingLevel] = JsonCodec.string.transformOrFail(
    {
      case s if s.equalsIgnoreCase("debug") => Right(Debug)
      case s if s.equalsIgnoreCase("info") => Right(Info)
      case s if s.equalsIgnoreCase("notice") => Right(Notice)
      case s if s.equalsIgnoreCase("warning") => Right(Warning)
      case s if s.equalsIgnoreCase("error") => Right(Error)
      case s if s.equalsIgnoreCase("critical") => Right(Critical)
      case s if s.equalsIgnoreCase("alert") => Right(Alert)
      case s if s.equalsIgnoreCase("emergency") => Right(Emergency)
      case s => Left(s"Invalid LoggingLevel: $s")
    },
    _.toString.toLowerCase
  )

/** Params for `logging/setLevel` requests. Client sets the minimum severity to receive via
  * `notifications/message`.
  */
case class SetLevelRequestParams(level: LoggingLevel)

object SetLevelRequestParams:
  given JsonCodec[SetLevelRequestParams] = DeriveJsonCodec.gen[SetLevelRequestParams]

/** Params for `notifications/message` notifications. `data` is intentionally `Json` so any
  * structured payload survives — string log lines round-trip too.
  */
case class LoggingMessageNotificationParams(
    level: LoggingLevel,
    data: Json,
    logger: Option[String] = None
)

object LoggingMessageNotificationParams:

  given JsonCodec[LoggingMessageNotificationParams] =
    DeriveJsonCodec.gen[LoggingMessageNotificationParams]
