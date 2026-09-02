package com.tjclp.fastmcp
package server.transport.http

import java.nio.charset.StandardCharsets

/** HTTP/1.x request-head model and parser for [[SocketHttpServer]]. Pure functions over bytes, so
  * the parsing rules are unit-testable without a socket. Shared by the JVM and Scala Native builds
  * (`jvm-native/`), so it uses only the javalib subset both platforms link.
  */
private[fastmcp] object Http1:

  enum Version:
    case Http10, Http11

  /** A transport-level rejection the server answers before the MCP layer ever sees the request: a
    * JSON-RPC error body with this status, then `Connection: close`.
    */
  final case class Rejection(status: Int, message: String)

  /** The parsed request line + headers. Header names are ASCII-lowercased; values are trimmed;
    * lookup returns the first occurrence (the same rule zio-http's `rawHeader` follows).
    */
  final case class Head(
      method: String,
      target: String,
      version: Version,
      headers: List[(String, String)]
  ):

    def header(name: String): Option[String] =
      val key = asciiLower(name)
      headers.collectFirst { case (k, v) if k == key => v }

    /** The request-target with any query string removed; absolute-form targets are reduced to their
      * path.
      */
    def path: String =
      val noQuery = target.indexOf('?') match
        case -1 => target
        case i => target.substring(0, i)
      if noQuery.startsWith("http://") || noQuery.startsWith("https://") then
        val afterScheme = noQuery.indexOf("://") + 3
        noQuery.indexOf('/', afterScheme) match
          case -1 => "/"
          case i => noQuery.substring(i)
      else noQuery

    /** How the request body is delimited, or a [[Rejection]] when the framing headers are
      * contradictory / unsupported / over the caps.
      */
    def bodyFraming(maxBody: Int): Either[Rejection, Framing] =
      val lengths = headers.collect { case ("content-length", v) => v.trim }.distinct
      val encodings = headers.collect { case ("transfer-encoding", v) => asciiLower(v.trim) }
      if lengths.nonEmpty && encodings.nonEmpty then
        Left(Rejection(400, "Both Content-Length and Transfer-Encoding present"))
      else if encodings.nonEmpty then
        if encodings == List("chunked") then Right(Framing.Chunked)
        else Left(Rejection(501, s"Unsupported Transfer-Encoding: ${encodings.mkString(", ")}"))
      else
        lengths match
          case Nil => Right(Framing.Empty)
          case single :: Nil =>
            if single.nonEmpty && single.forall(_.isDigit) then
              val n = single.toLongOption.getOrElse(Long.MaxValue)
              if n > maxBody then Left(Rejection(413, s"Request body exceeds $maxBody bytes"))
              else Right(Framing.Length(n.toInt))
            else Left(Rejection(400, "Malformed Content-Length"))
          case _ => Left(Rejection(400, "Conflicting Content-Length headers"))

    def expectsContinue: Boolean =
      header("expect").exists(v => asciiLower(v.trim) == "100-continue")

    /** `true` when the client asked for the connection to close after this exchange. */
    def wantsClose: Boolean =
      version == Version.Http10 ||
        header("connection").exists(v => asciiLower(v).split(',').exists(_.trim == "close"))

  enum Framing:
    case Empty
    case Length(n: Int)
    case Chunked

  /** ASCII-only lowercase — `java.util.Locale` is absent from the Scala Native javalib, and HTTP
    * tokens are ASCII by definition, so this is also the correct fold (no Turkish-i surprises).
    */
  def asciiLower(s: String): String =
    if s.forall(c => c < 'A' || c > 'Z') then s
    else s.map(c => if c >= 'A' && c <= 'Z' then (c + 32).toChar else c)

  /** Parse a request head (request line + headers, up to and excluding the blank line). Lines may
    * end in CRLF or bare LF. Obsolete line folding is rejected.
    */
  def parseHead(bytes: Array[Byte]): Either[Rejection, Head] =
    val text = new String(bytes, StandardCharsets.ISO_8859_1)
    val lines = text.split("\r?\n", -1).toList.dropWhile(_.isEmpty)
    lines match
      case Nil => Left(Rejection(400, "Empty request"))
      case requestLine :: rest =>
        parseRequestLine(requestLine).flatMap { case (method, target, version) =>
          parseHeaders(rest.takeWhile(_.nonEmpty), Nil).map(hs => Head(method, target, version, hs))
        }

  private def parseRequestLine(line: String): Either[Rejection, (String, String, Version)] =
    line.split(' ').toList.filter(_.nonEmpty) match
      case method :: target :: versionToken :: Nil if isToken(method) =>
        versionToken match
          case "HTTP/1.1" => Right((method, target, Version.Http11))
          case "HTTP/1.0" => Right((method, target, Version.Http10))
          case v if v.startsWith("HTTP/") => Left(Rejection(505, s"Unsupported HTTP version: $v"))
          case _ => Left(Rejection(400, "Malformed request line"))
      case _ => Left(Rejection(400, "Malformed request line"))

  private def parseHeaders(
      lines: List[String],
      acc: List[(String, String)]
  ): Either[Rejection, List[(String, String)]] =
    lines match
      case Nil => Right(acc.reverse)
      case line :: rest =>
        if line.charAt(0) == ' ' || line.charAt(0) == '\t' then
          Left(Rejection(400, "Obsolete header line folding is not supported"))
        else
          line.indexOf(':') match
            case -1 | 0 => Left(Rejection(400, "Malformed header line"))
            case i =>
              val name = line.substring(0, i)
              if !isToken(name) then Left(Rejection(400, "Malformed header name"))
              else parseHeaders(rest, (asciiLower(name) -> line.substring(i + 1).trim) :: acc)

  /** RFC 9110 `token`: one or more tchar. */
  private def isToken(s: String): Boolean =
    s.nonEmpty && s.forall { c =>
      c.isLetterOrDigit || "!#$%&'*+-.^_`|~".indexOf(c.toInt) >= 0
    }

  /** Reason phrases for the statuses this server emits; unknown codes get an empty phrase. */
  def reason(status: Int): String =
    status match
      case 100 => "Continue"
      case 200 => "OK"
      case 202 => "Accepted"
      case 400 => "Bad Request"
      case 403 => "Forbidden"
      case 404 => "Not Found"
      case 405 => "Method Not Allowed"
      case 406 => "Not Acceptable"
      case 409 => "Conflict"
      case 413 => "Content Too Large"
      case 415 => "Unsupported Media Type"
      case 431 => "Request Header Fields Too Large"
      case 500 => "Internal Server Error"
      case 501 => "Not Implemented"
      case 505 => "HTTP Version Not Supported"
      case _ => ""
