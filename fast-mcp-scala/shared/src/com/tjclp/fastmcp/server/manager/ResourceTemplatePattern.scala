package com.tjclp.fastmcp.server.manager

import scala.collection.mutable.ArrayBuffer

/** A compiled resource URI template such as `users://{id}/profile` or `files://{name}.{ext}`.
  *
  * Regex-free: literal text is matched verbatim (a `.` is a dot, not a wildcard) and every
  * `{placeholder}` matches one non-empty run of non-`/` characters, in time linear in the URI
  * length (`O(|uri| × longest literal)`, where the literal length is registration-time,
  * server-controlled input) — no backtracking on any engine (JVM `java.util.regex`, the Scala.js
  * `RegExp` shim, Scala Native's RE2 shim are all bypassed). Results are identical to the former
  * greedy-regex implementation for every template whose literal text contained no regex
  * metacharacters: within a segment each separator binds to its LAST admissible occurrence, so
  * `{name}.{ext}` on `archive.tar.gz` yields `name = archive.tar`, `ext = gz` — exactly the
  * leftmost-longest solution the old `([^/]+)\\.([^/]+)` produced.
  *
  * Registration-time validation (see [[ResourceTemplatePattern.parse]]): placeholders in the same
  * segment must be separated by literal text (`{a}{b}` is ambiguous), placeholder names must be
  * unique and must not contain `/`, braces must balance and not nest. Percent-encoding is not
  * decoded (unchanged).
  */
final class ResourceTemplatePattern private (
    val pattern: String,
    private[fastmcp] val segments: Vector[Vector[ResourceTemplatePattern.Part]],
    val paramNames: List[String]
):
  import ResourceTemplatePattern.Part

  def isTemplate: Boolean = paramNames.nonEmpty

  /** Match a client URI against this template; `Some(placeholder -> value)` on success. Linear in
    * the URI length and free of backtracking (see the class doc).
    */
  @SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.Return"))
  def matches(uri: String): Option[Map[String, String]] =
    val pieces = ResourceTemplatePattern.splitSlashes(uri)
    if pieces.length != segments.length then return None
    val bindings = Map.newBuilder[String, String]
    var i = 0
    while i < segments.length do
      if !matchSegment(segments(i), pieces(i), bindings) then return None
      i += 1
    Some(bindings.result())

  /** Match one `/`-delimited segment `[L0] V1 L1 V2 L2 … Vk [Lk]` against `seg`: anchor L0 / Lk
    * with `startsWith` / `endsWith`, then bind the separators L(k-1) … L1 right-to-left with
    * `lastIndexOf` over disjoint leftward windows (each variable non-empty). Each `lastIndexOf`
    * scans a window no other iteration revisits, so the whole segment costs O(|seg| × |L_i|max).
    */
  @SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.Return"))
  private def matchSegment(
      parts: Vector[Part],
      seg: String,
      out: scala.collection.mutable.Builder[(String, String), Map[String, String]]
  ): Boolean =
    parts match
      case Vector() => seg.isEmpty
      case Vector(Part.Literal(text)) => seg == text
      case _ =>
        val l0 = parts.head match
          case Part.Literal(t) => t
          case _ => ""
        val lk = parts.last match
          case Part.Literal(t) if parts.length > 1 => t
          case _ => ""
        if !(seg.startsWith(l0) && seg.endsWith(lk) && l0.length + lk.length < seg.length) then
          return false
        val inner = seg.substring(l0.length, seg.length - lk.length)
        // Variables in order; interior literals (never first/last) are exactly the separators:
        // consecutive literal text is merged at parse time and adjacent placeholders are rejected,
        // so every interior literal sits between two variables and seps.length == vars.length - 1.
        // seps(i - 1) is L_i, the separator between V_i and V_{i+1}.
        val vars = parts.collect { case Part.Variable(name) => name }
        val seps = ArrayBuffer.empty[String]
        var p = 1
        while p < parts.length - 1 do
          parts(p) match
            case Part.Literal(t) => seps += t
            case _ => ()
          p += 1
        val values = new Array[String](vars.length)
        var end = inner.length
        var i = vars.length - 1
        while i >= 1 do
          val sep = seps(i - 1)
          val idx = inner.lastIndexOf(sep, end - sep.length - 1)
          if idx < 1 then return false // V_i must be non-empty
          values(i) = inner.substring(idx + sep.length, end) // V_{i+1}, non-empty by construction
          end = idx
          i -= 1
        values(0) = inner.substring(0, end)
        if values(0).isEmpty then return false
        var v = 0
        while v < vars.length do
          out += vars(v) -> values(v)
          v += 1
        true

  override def equals(o: Any): Boolean = o match
    case p: ResourceTemplatePattern => p.pattern == pattern
    case _ => false

  override def hashCode: Int = pattern.hashCode

  override def toString: String = s"ResourceTemplatePattern($pattern)"

object ResourceTemplatePattern:

  private[fastmcp] enum Part:
    case Literal(text: String)
    case Variable(name: String)

  /** Compile `pattern`; `Left(reason)` for an invalid template. */
  @SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.Return"))
  def parse(pattern: String): Either[String, ResourceTemplatePattern] =
    val segments = ArrayBuffer.empty[Vector[Part]]
    val parts = ArrayBuffer.empty[Part]
    val literal = new StringBuilder
    val names = ArrayBuffer.empty[String]

    def flushLiteral(): Unit =
      if literal.nonEmpty then
        parts += Part.Literal(literal.result())
        literal.clear()

    var i = 0
    val n = pattern.length
    while i < n do
      pattern.charAt(i) match
        case '{' =>
          val close = pattern.indexOf('}', i + 1)
          if close < 0 then return Left(s"template '$pattern' has an unclosed '{'")
          val name = pattern.substring(i + 1, close)
          if name.isEmpty then return Left(s"template '$pattern' has an empty placeholder '{}'")
          if name.contains('{') then
            return Left(s"template '$pattern' nests a '{' inside placeholder '{$name}'")
          if name.contains('/') then
            return Left(s"template '$pattern' has a '/' inside placeholder '{$name}'")
          if names.contains(name) then
            return Left(
              s"template '$pattern' uses placeholder '{$name}' more than once"
            )
          val afterVariable = parts.lastOption match
            case Some(Part.Variable(_)) => true
            case _ => false
          if literal.isEmpty && afterVariable then
            return Left(
              s"template '$pattern' has adjacent placeholders before '{$name}' — ambiguous; " +
                "separate placeholders with literal text"
            )
          flushLiteral()
          names += name
          parts += Part.Variable(name)
          i = close + 1
        case '}' =>
          return Left(s"template '$pattern' has an unmatched '}'")
        case '/' =>
          flushLiteral()
          segments += parts.toVector
          parts.clear()
          i += 1
        case c =>
          literal += c
          i += 1
    flushLiteral()
    segments += parts.toVector
    Right(new ResourceTemplatePattern(pattern, segments.toVector, names.toList))

  /** Compile or throw `IllegalArgumentException` (registration time; `ResourceManager` wraps it in
    * `ResourceRegistrationError`).
    */
  def apply(pattern: String): ResourceTemplatePattern =
    parse(pattern) match
      case Right(compiled) => compiled
      case Left(reason) => throw new IllegalArgumentException(reason)

  /** Split on `/` with a manual `indexOf` loop — NOT `String.split`, which routes through the
    * platform regex shim on Scala.js / Scala Native.
    */
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private[fastmcp] def splitSlashes(s: String): ArrayBuffer[String] =
    val out = ArrayBuffer.empty[String]
    var from = 0
    var idx = s.indexOf('/')
    while idx >= 0 do
      out += s.substring(from, idx)
      from = idx + 1
      idx = s.indexOf('/', from)
    out += s.substring(from)
    out
