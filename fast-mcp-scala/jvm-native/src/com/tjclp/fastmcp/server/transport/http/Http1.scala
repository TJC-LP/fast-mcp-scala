package com.tjclp.fastmcp
package server.transport.http

import java.nio.charset.StandardCharsets

/** HTTP/1.x request-head model and parser for [[SocketHttpServer]]. Pure functions over bytes, so
  * the parsing rules are unit-testable without a socket. Shared by the JVM and Scala Native builds
  * (`jvm-native/`), so it uses only the javalib subset both platforms link.
  *
  * The parser is strict where RFC 9112 lets a recipient be lenient: every line ends in CRLF, a bare
  * CR, a bare LF or a control byte is an error, and only SP / HTAB count as whitespace. A server
  * that repairs such input reads a different message than a parser in front of it that does not (or
  * that repairs it another way), so anything the two could disagree on is refused instead.
  */
private[fastmcp] object Http1:

  enum Version:
    case Http10, Http11

  /** A transport-level rejection the server answers before the MCP layer ever sees the request: a
    * JSON-RPC error body with this status, then `Connection: close`.
    */
  final case class Rejection(status: Int, message: String)

  /** The parsed request line + headers. Header names are ASCII-lowercased; values have their
    * optional whitespace (SP / HTAB) removed; lookup returns the first occurrence (the same rule
    * zio-http's `rawHeader` follows).
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

    /** Every header name as received (lower-cased), one entry per field line — so a header sent
      * twice appears twice. What the repeated-header check of a 2026-07-28 POST reads.
      */
    def headerNames: Iterator[String] = headers.iterator.map(_._1)

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
      // Values arrive with their optional whitespace already removed and free of control bytes
      // (`parseHead`), so they are matched as they are: nothing is normalised here that a parser
      // in front of this server would not normalise too.
      val lengths = headers.collect { case ("content-length", v) => v }.distinct
      val encodings = headers.collect { case ("transfer-encoding", v) => asciiLower(v) }
      if lengths.nonEmpty && encodings.nonEmpty then
        Left(Rejection(400, "Both Content-Length and Transfer-Encoding present"))
      else if encodings.nonEmpty then
        if encodings == List("chunked") then Right(Framing.Chunked)
        else Left(Rejection(501, s"Unsupported Transfer-Encoding: ${encodings.mkString(", ")}"))
      else
        lengths match
          case Nil => Right(Framing.Empty)
          case single :: Nil =>
            if single.nonEmpty && single.forall(c => c >= '0' && c <= '9') then
              val n = single.toLongOption.getOrElse(Long.MaxValue)
              if n > maxBody then Left(Rejection(413, s"Request body exceeds $maxBody bytes"))
              else Right(Framing.Length(n.toInt))
            else Left(Rejection(400, "Malformed Content-Length"))
          case _ => Left(Rejection(400, "Conflicting Content-Length headers"))

    def expectsContinue: Boolean =
      header("expect").exists(v => asciiLower(v) == "100-continue")

    /** `true` when the client asked for the connection to close after this exchange. */
    def wantsClose: Boolean =
      version == Version.Http10 ||
        header("connection").exists(v => asciiLower(v).split(',').exists(trimOws(_) == "close"))

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

  /** `findHeadEnd`: the terminator has not arrived yet. */
  val HeadIncomplete = -1

  /** `findHeadEnd`: the bytes cannot be (the start of) a request head. */
  val HeadMalformed = -2

  /** `true` for the bytes a request head may never carry: NUL and the other C0 controls except HTAB
    * (CR and LF are judged by their position, separately), and DEL.
    */
  private def isForbiddenControl(b: Byte): Boolean =
    (b >= 0 && b < ' ' && b != '\t' && b != '\r' && b != '\n') || b == 0x7f

  /** Look for the blank line that ends a request head starting at `start`, scanning `buf` from
    * `from` (bytes before it were scanned by an earlier call) to `end`. Returns the index of the
    * `CRLF CRLF` terminator — the head is `[start, index)` and the next message begins four bytes
    * later — or [[HeadIncomplete]], or [[HeadMalformed]] at the first bare LF, bare CR or control
    * byte. `LF LF` and `LF CRLF` are therefore never terminators: they are errors.
    *
    * A CR in the last scanned position is undecided (its LF may be in the next read): the scan
    * reports [[HeadIncomplete]] and the caller resumes AT that CR, see [[resumeFrom]].
    */
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  def findHeadEnd(buf: Array[Byte], start: Int, from: Int, end: Int): Int =
    var i = math.max(from, start)
    var result = HeadIncomplete
    while result == HeadIncomplete && i < end do
      val b = buf(i)
      if b == '\n' then
        if i == start || buf(i - 1) != '\r' then result = HeadMalformed
        else if i - 3 >= start && buf(i - 2) == '\n' && buf(i - 3) == '\r' then result = i - 3
      else if b == '\r' then
        if i + 1 < end && buf(i + 1) != '\n' then result = HeadMalformed
      else if isForbiddenControl(b) then result = HeadMalformed
      i += 1
    result

  /** Where the next [[findHeadEnd]] / line scan over `[start, end)` resumes after an incomplete
    * one: at a trailing CR (still undecided), otherwise past everything scanned.
    */
  def resumeFrom(buf: Array[Byte], start: Int, end: Int): Int =
    if end > start && buf(end - 1) == '\r' then end - 1 else end

  private val MalformedHead =
    Rejection(400, "Request head contains a bare CR, a bare LF or a control character")

  /** Parse a request head (request line + headers, up to and excluding the blank line). Every line
    * ends in CRLF; empty lines before the request line are ignored (RFC 9112 §2.2) and one CRLF may
    * close the last line, but nothing may follow a blank line. A bare CR, a bare LF, NUL, another
    * C0 control other than HTAB, or DEL anywhere is a `400`; so is obsolete line folding.
    */
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  def parseHead(bytes: Array[Byte]): Either[Rejection, Head] =
    if !wellFormedLines(bytes) then Left(MalformedHead)
    else
      // Every LF is now known to follow a CR and every CR to precede an LF, so splitting at LF and
      // dropping the CR before it splits at CRLF exactly.
      val text = new String(bytes, StandardCharsets.ISO_8859_1)
      val length = text.length
      var pos = 0

      /** The next line without its CRLF; advances `pos` past it. */
      def nextLine(): String =
        val lf = text.indexOf('\n', pos)
        val line = if lf < 0 then text.substring(pos) else text.substring(pos, lf - 1)
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
          // A blank line ends the head; bytes after it belong to no head this parser was given.
          if failure.isEmpty && pos < length then failure = Some(MalformedHead)
          failure.toLeft(Head(method, target, version, headers.reverse))
        }

  /** `true` when every CR is followed by LF, every LF follows a CR, and no other control byte (HTAB
    * aside) or DEL occurs.
    */
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private def wellFormedLines(bytes: Array[Byte]): Boolean =
    var i = 0
    var ok = true
    while ok && i < bytes.length do
      val b = bytes(i)
      if b == '\r' then if i + 1 < bytes.length && bytes(i + 1) == '\n' then i += 2 else ok = false
      else if b == '\n' || isForbiddenControl(b) then ok = false
      else i += 1
    ok

  /** `method SP request-target SP HTTP-version`, exactly (RFC 9112 §3): one SP between the parts,
    * none around them, and no HTAB.
    */
  private def parseRequestLine(line: String): Either[Rejection, (String, String, Version)] =
    val malformed = Left(Rejection(400, "Malformed request line"))
    val first = line.indexOf(' ')
    val last = line.lastIndexOf(' ')
    if first <= 0 || last <= first + 1 || last == line.length - 1 then malformed
    else
      val method = line.substring(0, first)
      val target = line.substring(first + 1, last)
      val versionToken = line.substring(last + 1)
      if !isToken(method) || target.indexOf(' ') >= 0 || target.indexOf('\t') >= 0 then malformed
      else
        versionToken match
          case "HTTP/1.1" => Right((method, target, Version.Http11))
          case "HTTP/1.0" => Right((method, target, Version.Http10))
          case v if v.startsWith("HTTP/") && v.indexOf('\t') < 0 =>
            Left(Rejection(505, s"Unsupported HTTP version: $v"))
          case _ => malformed

  /** One `field-name ":" OWS field-value OWS` line (no line terminator). Also the shape of a
    * chunked trailer line.
    */
  def parseHeaderLine(line: String): Either[Rejection, (String, String)] =
    if line.charAt(0) == ' ' || line.charAt(0) == '\t' then
      Left(Rejection(400, "Obsolete header line folding is not supported"))
    else
      line.indexOf(':') match
        case -1 | 0 => Left(Rejection(400, "Malformed header line"))
        case i =>
          val name = line.substring(0, i)
          if !isToken(name) then Left(Rejection(400, "Malformed header name"))
          else Right(asciiLower(name) -> trimOws(line.substring(i + 1)))

  /** Remove optional whitespace — SP and HTAB, nothing else — from both ends. `String.trim` would
    * also strip every control character, turning a value padded with them into one a stricter
    * parser never saw.
    */
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  def trimOws(s: String): String =
    var from = 0
    var until = s.length
    while from < until && isOws(s.charAt(from)) do from += 1
    while until > from && isOws(s.charAt(until - 1)) do until -= 1
    if from == 0 && until == s.length then s else s.substring(from, until)

  private def isOws(c: Char): Boolean = c == ' ' || c == '\t'

  /** RFC 9110 `token`: one or more `tchar`, which is ASCII only — a letter or digit of another
    * script is not a token character.
    */
  private def isToken(s: String): Boolean =
    s.nonEmpty && s.forall { c =>
      (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
      "!#$%&'*+-.^_`|~".indexOf(c.toInt) >= 0
    }

  private val StatusLines: Map[Int, String] =
    List(100, 200, 202, 400, 403, 404, 405, 406, 408, 409, 413, 415, 431, 500, 501, 503, 505)
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
      case 408 => "Request Timeout"
      case 409 => "Conflict"
      case 413 => "Content Too Large"
      case 415 => "Unsupported Media Type"
      case 431 => "Request Header Fields Too Large"
      case 500 => "Internal Server Error"
      case 501 => "Not Implemented"
      case 503 => "Service Unavailable"
      case 505 => "HTTP Version Not Supported"
      case _ => ""
