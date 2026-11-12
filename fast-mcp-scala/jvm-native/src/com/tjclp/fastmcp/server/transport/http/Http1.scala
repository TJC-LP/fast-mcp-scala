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

    /** Every value of `name`, `", "`-joined (what Bun's `Headers.get` returns) — the lookup the
      * admission gates use, so a duplicated `Host` / `Origin` fails the origin parser closed
      * instead of only its first value being checked.
      */
    def headerJoined(name: String): Option[String] =
      val key = asciiLower(name)
      headers.collect { case (k, v) if k == key => v } match
        case Nil => None
        case single :: Nil => Some(single)
        case many => Some(many.mkString(", "))

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
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  def parseHead(bytes: Array[Byte]): Either[Rejection, Head] =
    val text = new String(bytes, StandardCharsets.ISO_8859_1)
    val length = text.length
    var pos = 0

    /** The next line without its terminator; advances `pos` past it. */
    def nextLine(): String =
      val lf = text.indexOf('\n', pos)
      val end = if lf < 0 then length else lf
      val lineEnd = if end > pos && text.charAt(end - 1) == '\r' then end - 1 else end
      val line = text.substring(pos, lineEnd)
      pos = if lf < 0 then length else lf + 1
      line

    var requestLine = ""
    while requestLine.isEmpty && pos < length do requestLine = nextLine()
    if requestLine.isEmpty then Left(Rejection(400, "Empty request"))
    else
      parseRequestLine(requestLine).flatMap { case (method, target, version) =>
        var headers: List[(String, String)] = Nil
        var failure: Option[Rejection] = None
        var done = false
        while !done && failure.isEmpty && pos < length do
          val line = nextLine()
          if line.isEmpty then done = true
          else
            parseHeaderLine(line) match
              case Left(rejection) => failure = Some(rejection)
              case Right(header) => headers = header :: headers
        failure.toLeft(Head(method, target, version, headers.reverse))
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

  private def parseHeaderLine(line: String): Either[Rejection, (String, String)] =
    if line.charAt(0) == ' ' || line.charAt(0) == '\t' then
      Left(Rejection(400, "Obsolete header line folding is not supported"))
    else
      line.indexOf(':') match
        case -1 | 0 => Left(Rejection(400, "Malformed header line"))
        case i =>
          val name = line.substring(0, i)
          if !isToken(name) then Left(Rejection(400, "Malformed header name"))
          else Right(asciiLower(name) -> line.substring(i + 1).trim)

  /** RFC 9110 `token`: one or more tchar. */
  private def isToken(s: String): Boolean =
    s.nonEmpty && s.forall { c =>
      c.isLetterOrDigit || "!#$%&'*+-.^_`|~".indexOf(c.toInt) >= 0
    }

  private val StatusLines: Map[Int, String] =
    List(100, 200, 202, 400, 403, 404, 405, 406, 409, 413, 415, 431, 500, 501, 503, 505)
      .map(status => status -> s"HTTP/1.1 $status ${reason(status)}\r\n")
      .toMap

  /** The `HTTP/1.1 <status> <reason>` line with its CRLF, precomputed for the statuses this server
    * emits.
    */
  def statusLine(status: Int): String =
    StatusLines.getOrElse(status, s"HTTP/1.1 $status ${reason(status)}\r\n")

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
      case 503 => "Service Unavailable"
      case 505 => "HTTP Version Not Supported"
      case _ => ""
