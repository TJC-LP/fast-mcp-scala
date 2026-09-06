package com.tjclp.fastmcp.server.transport

import java.nio.charset.StandardCharsets
import java.util.Base64

import scala.util.Try

import zio.json.ast.Json

import com.tjclp.fastmcp.jsonrpc.{JsonFields, JsonRpcMessage, McpError}

/** 2026-07-28 Streamable HTTP header/body consistency checks. Header lookup is supplied by the
  * platform backend so the security-sensitive comparison logic is identical on JVM and Bun.
  */
private[fastmcp] object HttpHeaderValidation:
  private val EncodedPrefix = "=?base64?"
  private val EncodedSuffix = "?="
  private val MaxSafeInteger = BigDecimal("9007199254740991")

  def validate(
      request: JsonRpcMessage.Request,
      header: String => Option[String],
      toolInputSchemas: Map[String, Json]
  ): Either[McpError, Unit] =
    for
      _ <- matchRequired(header("mcp-method"), request.method, "Mcp-Method", decode = false)
      _ <- validateName(request, header)
      _ <- validateToolHeaders(request, header, toolInputSchemas)
    yield ()

  /** Validate the standard method header for a JSON-RPC notification. Notifications do not carry
    * the per-request metadata object, but modern HTTP POSTs still require `Mcp-Method`.
    */
  def validateMethod(
      method: String,
      header: String => Option[String]
  ): Either[McpError, Unit] =
    matchRequired(header("mcp-method"), method, "Mcp-Method", decode = false)

  private def validateName(
      request: JsonRpcMessage.Request,
      header: String => Option[String]
  ): Either[McpError, Unit] =
    val sourceField = request.method match
      case "tools/call" | "prompts/get" => "name"
      case "resources/read" => "uri"
      case _ => ""
    if sourceField.isEmpty then Right(())
    else
      val expected = request.params
        .flatMap(JsonFields.get(_, sourceField))
        .collect { case Json.Str(value) => value }
      expected match
        case None =>
          Left(McpError.headerMismatch(s"Header mismatch: body is missing `$sourceField`"))
        case Some(value) => matchRequired(header("mcp-name"), value, "Mcp-Name", decode = true)

  private def validateToolHeaders(
      request: JsonRpcMessage.Request,
      header: String => Option[String],
      schemas: Map[String, Json]
  ): Either[McpError, Unit] =
    if request.method != "tools/call" then Right(())
    else
      val name = request.params
        .flatMap(JsonFields.get(_, "name"))
        .collect { case Json.Str(value) => value }
      val args = request.params
        .flatMap(JsonFields.get(_, "arguments"))
        .collect { case Json.Obj(fields) => Json.Obj(fields*) }
      name.flatMap(schemas.get) match
        case None => Right(()) // unknown tool is classified by the router as Invalid Params
        case Some(schema) =>
          collectHeaderProperties(schema).flatMap { annotations =>
            annotations.foldLeft[Either[McpError, Unit]](Right(())) { case (acc, annotation) =>
              acc.flatMap(_ => validateToolHeader(annotation, args, header))
            }
          }

  private final case class HeaderProperty(name: String, path: List[String], kind: String)

  private def collectHeaderProperties(schema: Json): Either[McpError, List[HeaderProperty]] =
    def loop(node: Json, path: List[String]): Either[McpError, List[HeaderProperty]] =
      node match
        case Json.Obj(fields) =>
          val values = fields.toMap
          val current = values.get("x-mcp-header") match
            case None => Right(Nil)
            case Some(Json.Str(name)) if name.nonEmpty && isToken(name) =>
              values.get("type") match
                case Some(Json.Str(kind @ ("string" | "integer" | "boolean"))) =>
                  Right(List(HeaderProperty(name, path, kind)))
                case _ => Left(McpError.headerMismatch("Invalid x-mcp-header parameter type"))
            case _ => Left(McpError.headerMismatch("Invalid x-mcp-header annotation"))
          val nested = values.get("properties") match
            case Some(Json.Obj(properties)) =>
              properties.toList.foldLeft[Either[McpError, List[HeaderProperty]]](Right(Nil)) {
                case (acc, (propertyName, propertySchema)) =>
                  for
                    found <- acc
                    more <- loop(propertySchema, path :+ propertyName)
                  yield found ++ more
              }
            case _ => Right(Nil)
          for
            here <- current
            children <- nested
            all = here ++ children
            _ <-
              if all.map(_.name.toLowerCase).distinct.size == all.size then Right(())
              else Left(McpError.headerMismatch("Duplicate x-mcp-header annotation"))
          yield all
        case _ => Right(Nil)
    loop(schema, Nil)

  private def validateToolHeader(
      property: HeaderProperty,
      arguments: Option[Json],
      header: String => Option[String]
  ): Either[McpError, Unit] =
    val bodyValue = property.path
      .foldLeft(arguments) {
        case (Some(Json.Obj(fields)), segment) => fields.toMap.get(segment)
        case _ => None
      }
      .filter(_ != Json.Null)
    val headerValue = header(s"mcp-param-${property.name}")
    bodyValue match
      case None =>
        if headerValue.isEmpty then Right(())
        else
          Left(McpError.headerMismatch(s"Header mismatch: unexpected Mcp-Param-${property.name}"))
      case Some(value) =>
        for
          expected <- primitiveString(value, property.kind)
          _ <-
            if property.kind == "integer" then
              matchInteger(headerValue, expected, s"Mcp-Param-${property.name}")
            else
              matchRequired(
                headerValue,
                expected,
                s"Mcp-Param-${property.name}",
                decode = true
              )
        yield ()

  private def primitiveString(value: Json, kind: String): Either[McpError, String] =
    (kind, value) match
      case ("string", Json.Str(text)) => Right(text)
      case ("boolean", Json.Bool(value)) => Right(value.toString.toLowerCase)
      case ("integer", Json.Num(value)) =>
        val number = BigDecimal(value)
        if number.isWhole && number.abs <= MaxSafeInteger then Right(number.toBigInt.toString)
        else Left(McpError.headerMismatch("Header-backed integer is outside the safe range"))
      case _ =>
        Left(McpError.headerMismatch("Header-backed argument does not match its schema type"))

  private def matchRequired(
      actual: Option[String],
      expected: String,
      name: String,
      decode: Boolean
  ): Either[McpError, Unit] =
    actual match
      case None => Left(McpError.headerMismatch(s"Header mismatch: missing required $name header"))
      case Some(raw) =>
        val value = if decode then decodeHeaderValue(raw) else validatePlainValue(raw)
        value.flatMap { decoded =>
          if decoded == expected then Right(())
          else Left(McpError.headerMismatch(s"Header mismatch: $name does not match request body"))
        }

  private def matchInteger(
      actual: Option[String],
      expected: String,
      name: String
  ): Either[McpError, Unit] =
    actual match
      case None => Left(McpError.headerMismatch(s"Header mismatch: missing required $name header"))
      case Some(raw) =>
        decodeHeaderValue(raw).flatMap { decoded =>
          Try(BigDecimal(decoded)).toEither.left
            .map(_ => McpError.headerMismatch(s"Header mismatch: $name is not an integer"))
            .flatMap { value =>
              if value.isWhole && value.abs <= MaxSafeInteger && value == BigDecimal(expected) then
                Right(())
              else
                Left(McpError.headerMismatch(s"Header mismatch: $name does not match request body"))
            }
        }

  private def decodeHeaderValue(value: String): Either[McpError, String] =
    if value.startsWith(EncodedPrefix) && value.endsWith(EncodedSuffix) then
      // `=?base64?=` (10 chars, prefix and suffix overlap on the `?`) and the empty-payload
      // `=?base64??=` (11 chars) are malformed sentinels, not plain values; the substring below
      // would throw StringIndexOutOfBounds for the first, so refuse before slicing.
      if value.length <= EncodedPrefix.length + EncodedSuffix.length then
        Left(McpError.headerMismatch("Malformed Base64 header sentinel"))
      else
        val payload = value.substring(EncodedPrefix.length, value.length - EncodedSuffix.length)
        Try(Base64.getDecoder.decode(payload)).toEither.left
          .map(_ => McpError.headerMismatch("Malformed Base64 header sentinel"))
          .flatMap { bytes =>
            val decoded = new String(bytes, StandardCharsets.UTF_8)
            if java.util.Arrays.equals(bytes, decoded.getBytes(StandardCharsets.UTF_8)) then
              Right(decoded)
            else Left(McpError.headerMismatch("Base64 header value is not valid UTF-8"))
          }
    else validatePlainValue(value)

  private def validatePlainValue(value: String): Either[McpError, String] =
    val validChars = value.forall(ch => ch == '\t' || (ch >= ' ' && ch <= '~'))
    if !validChars || value != value.trim then
      Left(McpError.headerMismatch("Header contains an invalid plain-text value"))
    else Right(value)

  private def isToken(value: String): Boolean =
    val allowed = "!#$%&'*+-.^_`|~"
    value.forall(ch =>
      (ch >= 'a' && ch <= 'z') ||
        (ch >= 'A' && ch <= 'Z') ||
        (ch >= '0' && ch <= '9') ||
        allowed.contains(ch)
    )
