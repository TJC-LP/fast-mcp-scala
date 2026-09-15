package com.tjclp.fastmcp.core.skills

import com.tjclp.fastmcp.core.wire.Skills

/** A skill-namespace URI in canonical form: `<scheme>://<segment>/<segment>/...`.
  *
  * This is the ONE place skill URIs are constructed, parsed, resolved and compared. The policy is
  * deliberately literal (see [[SkillUri.parse]]):
  *
  *   - a URI is a scheme plus one or more `/`-separated segments; the first segment occupies the
  *     RFC 3986 authority position and carries no network semantics — it is never resolved;
  *   - segments are matched **byte-for-byte**. Percent-encoding is never applied and never decoded,
  *     so no two distinct strings name the same resource (`%2F`, `%2E%2E`, `%25` and double
  *     encodings are all simply rejected). Raw non-ASCII (IRI-style) is allowed in path segments;
  *   - `.` and `..` segments, empty segments, trailing slashes, backslashes, `?`, `#`, `%`,
  *     whitespace and control characters (NUL included) are rejected;
  *   - the scheme is lowercase and the authority segment is restricted to RFC 3986 unreserved
  *     characters, so case-insensitive aliases do not exist.
  *
  * Containment is segment-aware: `skill://a/demo-other/x` is not within `skill://a/demo`. Relative
  * references resolve against a directory the way filesystem paths do, with `..` refused once it
  * would climb above the skill root ([[SkillUri.resolveRelative]]).
  */
final case class SkillUri private (scheme: String, segments: Vector[String]):

  /** The canonical string form. */
  def render: String = s"$scheme://${segments.mkString("/")}"

  override def toString: String = render

  def lastSegment: String = segments.last

  /** The enclosing directory, if this is not an authority-only root. */
  def parent: Option[SkillUri] =
    if segments.length > 1 then Some(new SkillUri(scheme, segments.init)) else None

  /** Append one validated segment. */
  def child(segment: String): Either[SkillUriError, SkillUri] =
    SkillUri.validateSegment(segment).map(s => new SkillUri(scheme, segments :+ s))

  /** Append a validated relative path (`a/b/c`) — no `.`/`..` normalisation, every segment must be
    * a plain name; use [[SkillUri.resolveRelative]] for author-written references.
    */
  def descend(relativePath: String): Either[SkillUriError, SkillUri] =
    SkillUri.splitRelative(relativePath).map(parts => new SkillUri(scheme, segments ++ parts))

  /** Strict descendant of `root` (same scheme, longer path sharing every root segment). */
  def isWithin(root: SkillUri): Boolean =
    scheme == root.scheme && segments.length > root.segments.length &&
      segments.startsWith(root.segments)

  /** Path segments below `root`, when this is a strict descendant of it. */
  def relativeTo(root: SkillUri): Option[Vector[String]] =
    if isWithin(root) then Some(segments.drop(root.segments.length)) else None

object SkillUri:

  /** Default scheme; no scheme is privileged by the extension. */
  val DefaultScheme = "skill"

  private def isUnreserved(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~'

  private def isSchemeChar(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.'

  /** Characters refused in every segment: separators and delimiters that would make the string
    * ambiguous as a URI, plus `%` (no percent-encoding policy at all), whitespace and controls.
    */
  private def isForbiddenInSegment(c: Char): Boolean =
    c == '/' || c == '\\' || c == '%' || c == '?' || c == '#' || c < 0x20 || c == 0x7f ||
      Character.isWhitespace(c) || Character.isSpaceChar(c)

  def validateScheme(scheme: String): Either[SkillUriError, String] =
    if scheme.isEmpty then Left(SkillUriError("scheme must be non-empty"))
    else if !(scheme.head >= 'a' && scheme.head <= 'z') || !scheme.forall(isSchemeChar) then
      Left(SkillUriError(s"scheme '$scheme' must be lowercase and match [a-z][a-z0-9+.-]*"))
    else Right(scheme)

  /** The first path segment (authority position): RFC 3986 unreserved characters only, lowercase.
    */
  def validateAuthority(segment: String): Either[SkillUriError, String] =
    if segment.isEmpty then Left(SkillUriError("authority segment must be non-empty"))
    else if segment == "." || segment == ".." then
      Left(SkillUriError(s"authority segment '$segment' is a dot segment"))
    else if !segment.forall(isUnreserved) then
      Left(
        SkillUriError(
          s"authority segment '$segment' must contain only lowercase letters, digits, '-', '.', '_' or '~'"
        )
      )
    else Right(segment)

  /** Any later path segment: non-empty, not a dot segment, none of the forbidden characters. */
  def validateSegment(segment: String): Either[SkillUriError, String] =
    if segment.isEmpty then Left(SkillUriError("empty path segment"))
    else if segment == "." || segment == ".." then
      Left(SkillUriError(s"'$segment' is a dot segment"))
    else
      segment.find(isForbiddenInSegment) match
        case Some(c) =>
          Left(
            SkillUriError(
              s"segment '$segment' contains a forbidden character (U+${Integer.toHexString(c.toInt).toUpperCase.reverse.padTo(4, '0').reverse})"
            )
          )
        case None => Right(segment)

  /** Split a relative path on `/` into validated plain segments (no normalisation). */
  def splitRelative(relativePath: String): Either[SkillUriError, Vector[String]] =
    if relativePath.isEmpty then Left(SkillUriError("relative path must be non-empty"))
    else if relativePath.startsWith("/") then
      Left(SkillUriError(s"'$relativePath' is absolute, not relative to the skill root"))
    else
      relativePath
        .split("/", -1)
        .toVector
        .foldLeft[Either[SkillUriError, Vector[String]]](Right(Vector.empty)) {
          case (Left(e), _) => Left(e)
          case (Right(acc), seg) => validateSegment(seg).map(acc :+ _)
        }

  /** Build from a scheme and a skill path such as `acme/billing/refunds`. */
  def fromPath(scheme: String, path: String): Either[SkillUriError, SkillUri] =
    for
      s <- validateScheme(scheme)
      parts <- splitRelative(path)
      _ <- validateAuthority(parts.head)
    yield new SkillUri(s, parts)

  /** Parse a canonical URI string. Rejects anything that is not already in canonical form (see the
    * class documentation) — callers that receive an inbound URI treat a `Left` as "not served".
    */
  def parse(uri: String): Either[SkillUriError, SkillUri] =
    val sep = uri.indexOf("://")
    if sep <= 0 then Left(SkillUriError(s"'$uri' is not a <scheme>://<path> URI"))
    else
      val scheme = uri.substring(0, sep)
      val rest = uri.substring(sep + 3)
      if rest.isEmpty then Left(SkillUriError(s"'$uri' has no path"))
      else fromPath(scheme, rest)

  def unsafeParse(uri: String): SkillUri =
    parse(uri).fold(e => throw new IllegalArgumentException(e.message), identity)

  /** Resolve an author-written relative reference (`references/GUIDE.md`, `../assets/x.bin`)
    * against `directory`, refusing to climb above `root`. `.` segments are dropped, `..` pops one
    * directory, an empty final segment (trailing slash) is refused, and the result must stay a
    * strict descendant of `root`.
    */
  def resolveRelative(
      root: SkillUri,
      directory: SkillUri,
      reference: String
  ): Either[SkillUriError, SkillUri] =
    if reference.isEmpty then Left(SkillUriError("relative reference must be non-empty"))
    else if reference.startsWith("/") || reference.contains("\\") || reference.contains("://") then
      Left(SkillUriError(s"'$reference' is not a relative reference"))
    else if directory.scheme != root.scheme || !(directory == root || directory.isWithin(root)) then
      Left(SkillUriError(s"'${directory.render}' is not within '${root.render}'"))
    else
      val parts = reference.split("/", -1).toVector
      parts.zipWithIndex
        .foldLeft[Either[SkillUriError, Vector[String]]](Right(directory.segments)) {
          case (Left(e), _) => Left(e)
          case (Right(acc), (seg, idx)) =>
            if seg == "." then Right(acc)
            else if seg.isEmpty then
              if idx == parts.length - 1 then Left(SkillUriError(s"'$reference' names a directory"))
              else Left(SkillUriError(s"'$reference' contains an empty segment"))
            else if seg == ".." then
              if acc.length <= root.segments.length then
                Left(SkillUriError(s"'$reference' escapes the skill root '${root.render}'"))
              else Right(acc.init)
            else validateSegment(seg).map(acc :+ _)
        }
        .flatMap { segs =>
          val resolved = new SkillUri(root.scheme, segs)
          if resolved.isWithin(root) then Right(resolved)
          else
            Left(SkillUriError(s"'$reference' does not resolve to a file within '${root.render}'"))
        }

  /** The skill root for a `SKILL.md` URI (`skill://a/b/SKILL.md` → `skill://a/b`), when the URI is
    * one.
    */
  def skillRootOf(skillMd: SkillUri): Option[SkillUri] =
    if skillMd.lastSegment == Skills.SkillFileName then skillMd.parent else None

final case class SkillUriError(message: String)
