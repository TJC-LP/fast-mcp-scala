package com.tjclp.fastmcp.core.skills

import scala.annotation.tailrec

import org.virtuslab.yaml.{CoreSchemaTag, Tag, YamlError}
import org.virtuslab.yaml.internal.load.parse.{Event, EventKind, ParserImpl}
import org.virtuslab.yaml.internal.load.reader.Tokenizer
import org.virtuslab.yaml.internal.load.reader.token.ScalarStyle
import zio.Chunk
import zio.json.ast.Json

import com.tjclp.fastmcp.core.wire.SkillFrontmatter

/** Reads the YAML frontmatter of a `SKILL.md` into a [[SkillFrontmatter]] (a verbatim JSON object),
  * applying the Agent Skills rules on `name`, `description`, `compatibility`, `metadata`, `license`
  * and `allowed-tools`. Everything else the author wrote passes through unchanged.
  *
  * '''Bytes.''' The input is validated as strict UTF-8 ([[Utf8.decodeStrict]]) — a `SKILL.md` that
  * is not valid UTF-8 is rejected, never repaired. A leading UTF-8 BOM (`EF BB BF`) is skipped for
  * parsing only; the served bytes (and therefore the digest and `size`) always include it. Line
  * endings may be `LF` or `CRLF`; the frontmatter text is normalised to `LF` before the YAML parser
  * sees it (YAML 1.2 §5.4 normalises line breaks inside scalars anyway), the body is left
  * untouched.
  *
  * '''Frontmatter block.''' The file MUST start (after the optional BOM) with a line that is
  * exactly `---`, and the block ends at the next line that is exactly `---`. Nothing else is
  * accepted as a delimiter. The YAML text is the lines between the two delimiter lines WITHOUT the
  * line break that precedes the closing `---` — the same extraction the regex-based readers hosts
  * commonly use perform (`^---\r?\n([\s\S]*?)\r?\n---`), so a literal block scalar that ends the
  * frontmatter has no trailing newline on both sides of a field-by-field comparison.
  *
  * '''YAML → JSON.''' The parser is scala-yaml, driven at the event level (its `Node` API stores
  * mappings in a `Map`, which would silently collapse duplicate keys). The composer here:
  *
  *   - requires exactly one document whose root is a mapping;
  *   - rejects duplicate keys, non-scalar keys, anchors, aliases and every explicit tag (`!!str`,
  *     `!custom`, ...): frontmatter is data, never a graph and never a type hint;
  *   - bounds nesting depth and node count ([[SkillFrontmatterReader.Limits]]) and the raw block
  *     size before the parser runs;
  *   - converts scalars with the YAML 1.2 core schema: quoted, literal (`|`) and folded (`>`)
  *     scalars are always strings; plain scalars resolve `null`/`~`/empty → `null`, `true`/`false`
  *     → booleans, decimal/`0o`/`0x` integers and decimal floats → numbers, `.inf`/`.nan` →
  *     rejected (not representable in JSON), anything else → string.
  */
object SkillFrontmatterReader:

  /** Resource bounds on the frontmatter block. */
  final case class Limits(
      maxBytes: Int = 64 * 1024,
      maxDepth: Int = 32,
      maxNodes: Int = 4096
  ):
    require(maxBytes >= 1, "maxBytes must be >= 1")
    require(maxDepth >= 1, "maxDepth must be >= 1")
    require(maxNodes >= 1, "maxNodes must be >= 1")

  object Limits:
    val default: Limits = Limits()

  private val Bom = "\uFEFF"
  private val Delimiter = "---"

  /** The frontmatter and the byte offset where the Markdown body starts. */
  final case class Parsed(frontmatter: SkillFrontmatter, rawFrontmatter: String)

  /** Parse a `SKILL.md`. */
  def parse(bytes: Array[Byte], limits: Limits = Limits.default): Either[String, Parsed] =
    Utf8
      .decodeStrict(bytes)
      .left
      .map(off => s"SKILL.md is not valid UTF-8 (byte offset $off)")
      .flatMap(text => parseText(text, limits))

  /** Parse an already-decoded `SKILL.md`. */
  def parseText(text: String, limits: Limits = Limits.default): Either[String, Parsed] =
    val body = if text.startsWith(Bom) then text.substring(Bom.length) else text
    for
      block <- extractBlock(body)
      _ <-
        if Utf8.encode(block).length > limits.maxBytes then
          Left(s"frontmatter exceeds ${limits.maxBytes} bytes")
        else Right(())
      json <- yamlToJson(block.replace("\r\n", "\n"), limits)
      fm <- SkillFrontmatter.fromJson(json)
      _ <- validateRules(fm)
    yield Parsed(fm, block)

  /** Text between the opening `---` line and the closing `---` line. */
  private def extractBlock(text: String): Either[String, String] =
    val firstEol = lineEnd(text, 0)
    if text.isEmpty || text.substring(0, firstEol) != Delimiter then
      Left("SKILL.md must begin with a `---` frontmatter line")
    else
      @tailrec
      def find(from: Int): Option[(Int, Int)] =
        if from > text.length then None
        else
          val end = lineEnd(text, from)
          if text.substring(from, end) == Delimiter then Some((from, end))
          else if end >= text.length then None
          else find(nextLine(text, end))
      val contentStart = nextLine(text, firstEol)
      if contentStart > text.length then Left("SKILL.md frontmatter is not closed by a `---` line")
      else
        find(contentStart) match
          case None => Left("SKILL.md frontmatter is not closed by a `---` line")
          case Some((closeStart, _)) =>
            // Drop the line break that precedes the closing delimiter.
            val raw = text.substring(contentStart, closeStart)
            Right(if raw.endsWith("\r\n") then raw.dropRight(2) else raw.stripSuffix("\n"))

  /** Index of the `\n` or `\r` that ends the line starting at `from`, or `text.length`. */
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private def lineEnd(text: String, from: Int): Int =
    var i = from
    while i < text.length && text.charAt(i) != '\n' && text.charAt(i) != '\r' do i += 1
    i

  private def nextLine(text: String, eol: Int): Int =
    if eol >= text.length then text.length + 1
    else if text.charAt(eol) == '\r' && eol + 1 < text.length && text.charAt(eol + 1) == '\n' then
      eol + 2
    else eol + 1

  // ---- YAML events → JSON ----

  /** Single-pass event cursor: `rest` advances as events are consumed, `nodes` counts them. */
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private final class Composer(events: List[Event], limits: Limits):
    private var rest: List[Event] = events
    private var nodes: Int = 0

    private def next(): Either[String, Event] = rest match
      case e :: tail =>
        rest = tail
        Right(e)
      case Nil => Left("unexpected end of YAML event stream")

    private def peek: Option[EventKind] = rest.headOption.map(_.kind)

    private def countNode(): Either[String, Unit] =
      nodes += 1
      if nodes > limits.maxNodes then
        Left(s"frontmatter has more than ${limits.maxNodes} YAML nodes")
      else Right(())

    def document(): Either[String, Json] =
      for
        _ <- expect(_ == EventKind.StreamStart, "stream start")
        _ <- expect({ case _: EventKind.DocumentStart => true; case _ => false }, "document start")
        root <- node(depth = 1)
        _ <- expect({ case _: EventKind.DocumentEnd => true; case _ => false }, "document end")
        _ <- peek match
          case Some(EventKind.StreamEnd) => Right(())
          case Some(_: EventKind.DocumentStart) =>
            Left("frontmatter must contain exactly one YAML document")
          case other => Left(s"unexpected YAML event after the document: $other")
      yield root

    private def expect(p: EventKind => Boolean, what: String): Either[String, Unit] =
      next().flatMap(e =>
        if p(e.kind) then Right(()) else Left(s"expected YAML $what, got ${e.kind}")
      )

    private def node(depth: Int): Either[String, Json] =
      if depth > limits.maxDepth then Left(s"frontmatter nesting exceeds ${limits.maxDepth} levels")
      else
        countNode().flatMap(_ => next()).flatMap { e =>
          e.kind match
            case EventKind.Scalar(value, style, meta) =>
              if meta.anchor.isDefined then Left("YAML anchors are not allowed in frontmatter")
              else
                meta.tag match
                  // The parser itself stamps `!!null` on a key with no value (`empty:`); that is
                  // data, not an author-written tag.
                  case Some(t) if t == Tag.nullTag && value.isEmpty && style == ScalarStyle.Plain =>
                    Right(Json.Null)
                  case Some(t) =>
                    Left(s"explicit YAML tags are not allowed in frontmatter (${t.value})")
                  case None => scalar(value, style)
            case EventKind.SequenceStart(meta) =>
              if meta.anchor.isDefined then Left("YAML anchors are not allowed in frontmatter")
              else if meta.tag.isDefined then
                Left("explicit YAML tags are not allowed in frontmatter")
              else sequence(depth, Vector.empty)
            case EventKind.MappingStart(meta) =>
              if meta.anchor.isDefined then Left("YAML anchors are not allowed in frontmatter")
              else if meta.tag.isDefined then
                Left("explicit YAML tags are not allowed in frontmatter")
              else mapping(depth, Vector.empty, Set.empty)
            case _: EventKind.Alias => Left("YAML aliases are not allowed in frontmatter")
            case other => Left(s"unexpected YAML event: $other")
        }

    @tailrec
    private def sequence(depth: Int, acc: Vector[Json]): Either[String, Json] =
      peek match
        case Some(EventKind.SequenceEnd) =>
          rest = rest.tail
          Right(Json.Arr(Chunk.fromIterable(acc)))
        case None => Left("unterminated YAML sequence")
        case Some(_) =>
          node(depth + 1) match
            case Left(err) => Left(err)
            case Right(item) => sequence(depth, acc :+ item)

    @tailrec
    private def mapping(
        depth: Int,
        acc: Vector[(String, Json)],
        seen: Set[String]
    ): Either[String, Json] =
      peek match
        case Some(EventKind.MappingEnd) =>
          rest = rest.tail
          Right(Json.Obj(Chunk.fromIterable(acc)))
        case None => Left("unterminated YAML mapping")
        case Some(_) =>
          key() match
            case Left(err) => Left(err)
            case Right(k) if seen.contains(k) => Left(s"duplicate frontmatter key '$k'")
            case Right(k) =>
              node(depth + 1) match
                case Left(err) => Left(err)
                case Right(v) => mapping(depth, acc :+ (k -> v), seen + k)

    /** Mapping keys must be plain or quoted scalars without anchors/tags; their text is the key. */
    private def key(): Either[String, String] =
      countNode().flatMap(_ => next()).flatMap { e =>
        e.kind match
          case EventKind.Scalar(value, style, meta) if meta.anchor.isEmpty && meta.tag.isEmpty =>
            if value.isEmpty then Left("empty frontmatter keys are not allowed")
            else Right(unescapeTokenizer(value, style))
          case EventKind.Scalar(_, _, _) =>
            Left("anchors and tags are not allowed on frontmatter keys")
          case _: EventKind.Alias => Left("YAML aliases are not allowed in frontmatter")
          case _ => Left("frontmatter keys must be scalars")
      }

    private def scalar(rawValue: String, style: ScalarStyle): Either[String, Json] =
      val value = unescapeTokenizer(rawValue, style)
      style match
        case ScalarStyle.DoubleQuoted | ScalarStyle.SingleQuoted | ScalarStyle.Literal |
            ScalarStyle.Folded =>
          Right(Json.Str(value))
        case ScalarStyle.Plain =>
          Tag.resolveTag(value, Some(ScalarStyle.Plain)) match
            case t if t == Tag.nullTag => Right(Json.Null)
            case t if t == Tag.boolean =>
              Right(Json.Bool(value == "true" || value == "True" || value == "TRUE"))
            case t if t == Tag.int => integer(value)
            case t if t == Tag.float => float(value)
            case CoreSchemaTag(_) => Right(Json.Str(value))
            case _ => Right(Json.Str(value))

    private def integer(value: String): Either[String, Json] =
      try
        val n =
          if value.startsWith("0x") then BigInt(value.substring(2), 16)
          else if value.startsWith("0o") then BigInt(value.substring(2), 8)
          else BigInt(value.stripPrefix("+"))
        Right(Json.Num(new java.math.BigDecimal(n.bigInteger)))
      catch case _: NumberFormatException => Left(s"invalid YAML integer '$value'")

    private def float(value: String): Either[String, Json] =
      val lower = value.toLowerCase
      if lower.contains("inf") || lower.contains("nan") then
        Left(s"YAML value '$value' has no JSON representation")
      else
        try Right(Json.Num(new java.math.BigDecimal(value.stripPrefix("+"))))
        catch case _: NumberFormatException => Left(s"invalid YAML float '$value'")

  /** scala-yaml 0.3.x's tokenizer (`Token.Scalar.apply`) rewrites `\\` to `\\\\` and a line break
    * to the two characters `\\n` in PLAIN and FOLDED scalars — a presenter round-trip convenience
    * that would otherwise leak into the frontmatter JSON (a folded description would carry a
    * literal backslash-n). The escaping is prefix-free (every backslash it emits is followed by
    * `\\` or `n`), so a left-to-right decode inverts it exactly.
    */
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private[skills] def unescapeTokenizer(value: String, style: ScalarStyle): String =
    style match
      case ScalarStyle.Plain | ScalarStyle.Folded if value.indexOf('\\') >= 0 =>
        val sb = new StringBuilder(value.length)
        var i = 0
        while i < value.length do
          val c = value.charAt(i)
          if c == '\\' && i + 1 < value.length then
            value.charAt(i + 1) match
              case 'n' =>
                sb.append('\n')
                i += 2
              case '\\' =>
                sb.append('\\')
                i += 2
              case _ =>
                sb.append(c)
                i += 1
          else
            sb.append(c)
            i += 1
        sb.result()
      case _ => value

  private def yamlToJson(yaml: String, limits: Limits): Either[String, Json] =
    if yaml.trim.isEmpty then Left("frontmatter is empty")
    else
      val events: Either[YamlError, List[Event]] =
        try ParserImpl(Tokenizer.make(yaml)).getEvents()
        catch
          case e: YamlError => Left(e)
          case e: StackOverflowError =>
            Left(org.virtuslab.yaml.ComposerError("frontmatter nesting too deep"))
      events.left.map(e => s"frontmatter is not valid YAML: ${firstLine(e.msg)}").flatMap { evs =>
        new Composer(evs, limits).document().flatMap {
          case obj: Json.Obj => Right(obj)
          case _ => Left("frontmatter must be a YAML mapping")
        }
      }

  private def firstLine(s: String): String =
    val i = s.indexOf('\n')
    (if i < 0 then s else s.substring(0, i)).trim

  // ---- Agent Skills field rules ----

  private def validateRules(fm: SkillFrontmatter): Either[String, Unit] =
    for
      _ <- SkillNames.validateName(fm.name).left.map(e => s"frontmatter.name: $e")
      _ <- SkillNames
        .validateDescription(fm.description)
        .left
        .map(e => s"frontmatter.description: $e")
      _ <- fm.get("compatibility") match
        case None => Right(())
        case Some(Json.Str(c)) =>
          SkillNames.validateCompatibility(c).left.map(e => s"frontmatter.$e").map(_ => ())
        case Some(_) => Left("frontmatter.compatibility must be a string")
      _ <- fm.get("license") match
        case None | Some(Json.Str(_)) => Right(())
        case Some(_) => Left("frontmatter.license must be a string")
      _ <- fm.get("allowed-tools") match
        case None | Some(Json.Str(_)) => Right(())
        case Some(_) => Left("frontmatter.allowed-tools must be a string")
      _ <- fm.get("metadata") match
        case None => Right(())
        case Some(Json.Obj(fields)) =>
          fields.find { case (_, v) => v match { case Json.Str(_) => false; case _ => true } } match
            case Some((k, _)) =>
              Left(s"frontmatter.metadata.$k must be a string (metadata is a string-to-string map)")
            case None => Right(())
        case Some(_) => Left("frontmatter.metadata must be a mapping")
    yield ()
