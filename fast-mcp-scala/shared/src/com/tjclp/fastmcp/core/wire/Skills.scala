package com.tjclp.fastmcp.core.wire

import zio.Chunk
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.Cursor

/** Wire shapes of the **Skills extension** (`io.modelcontextprotocol/skills`, SEP-2640), pinned to
  * the stable specification page `ext-skills/specification/stable/skills.mdx` (blob `e65b881c…`,
  * repository commit `d866efdb…`, 2026-09-10) against base protocol 2026-07-28.
  *
  * The extension adds three methods — `skills/list`, `skills/get` and the optional
  * `resources/directory/read` — and one entry shape, [[Skill]]. Every shape here is validated at
  * decode time: a `resources` value that is neither an array nor the string `"dynamic"`, a digest
  * that is not `sha256:` + 64 lowercase hex, or a `size` that is negative, fractional or past
  * `Long` all fail decoding. Unknown frontmatter fields are preserved verbatim.
  */
object Skills:

  /** Extension identifier under `capabilities.extensions`. */
  val ExtensionId = "io.modelcontextprotocol/skills"

  val MethodSkillsList = "skills/list"
  val MethodSkillsGet = "skills/get"
  val MethodResourcesDirectoryRead = "resources/directory/read"

  /** The file every skill root must contain. */
  val SkillFileName = "SKILL.md"

  /** `mimeType` of a directory resource. */
  val DirectoryMimeType = "inode/directory"

  /** `mimeType` the spec recommends for `SKILL.md`. */
  val MarkdownMimeType = "text/markdown"

  /** Reserved `_meta` key prefix for skill resources. */
  val MetaPrefix = "io.modelcontextprotocol.skills/"

  /** Reserved prefix for `frontmatter.metadata` keys defined by MCP extensions. */
  val ReservedMetadataPrefix = "io.modelcontextprotocol/"

  /** The two per-skill interoperability limits (§Limits). Every conforming host accepts a skill up
    * to and including them; servers SHOULD NOT exceed them.
    */
  val MaxResourcesPerSkill: Int = 512
  val MaxTotalBytesPerSkill: Long = 16L * 1024L * 1024L

  val DigestPrefix = "sha256:"
  val DigestHexLength = 64

  /** `sha256:` followed by exactly 64 lowercase hexadecimal characters. */
  def isValidDigest(digest: String): Boolean =
    digest.length == DigestPrefix.length + DigestHexLength &&
      digest.startsWith(DigestPrefix) &&
      digest
        .substring(DigestPrefix.length)
        .forall(c => (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))

/** One file of a skill: its resource URI plus the SHA-256 digest and byte length of the raw bytes
  * served by `resources/read` (decoded bytes for a blob, UTF-8 bytes for text).
  */
final case class SkillResource(uri: String, digest: String, size: Long)

object SkillResource:

  private def sizeFrom(n: java.math.BigDecimal): Either[String, Long] =
    if n.signum < 0 then Left("size must be a non-negative integer")
    else if n.scale < -19 then Left("size exceeds the 64-bit range")
    else
      try
        val big = n.stripTrailingZeros
        if big.scale > 0 then Left("size must be an integer") else Right(big.longValueExact)
      catch case _: ArithmeticException => Left("size exceeds the 64-bit range")

  given JsonEncoder[SkillResource] = JsonEncoder[Json].contramap { r =>
    Json.Obj(
      "uri" -> Json.Str(r.uri),
      "digest" -> Json.Str(r.digest),
      "size" -> Json.Num(java.math.BigDecimal.valueOf(r.size))
    )
  }

  given JsonDecoder[SkillResource] = JsonDecoder[Json].mapOrFail {
    case Json.Obj(fields) =>
      def field(key: String): Option[Json] =
        val idx = fields.lastIndexWhere(_._1 == key)
        if idx < 0 then None else Some(fields(idx)._2)
      for
        uri <- field("uri") match
          case Some(Json.Str(u)) if u.nonEmpty => Right(u)
          case _ => Left("SkillResource.uri must be a non-empty string")
        digest <- field("digest") match
          case Some(Json.Str(d)) if Skills.isValidDigest(d) => Right(d)
          case Some(Json.Str(d)) =>
            Left(s"SkillResource.digest must be sha256:<64 lowercase hex>, got: $d")
          case _ => Left("SkillResource.digest must be a string")
        size <- field("size") match
          case Some(Json.Num(n)) => sizeFrom(n).left.map(e => s"SkillResource.size: $e")
          case _ => Left("SkillResource.size must be a number")
      yield SkillResource(uri, digest, size)
    case other => Left(s"SkillResource must be a JSON object, got: $other")
  }

  given JsonCodec[SkillResource] =
    JsonCodec(summon[JsonEncoder[SkillResource]], summon[JsonDecoder[SkillResource]])

/** The `resources` member of a [[Skill]] entry: a complete file manifest, or the `"dynamic"` marker
  * for content generated such that stable digests cannot be published.
  */
enum SkillResources:
  case Static(entries: List[SkillResource])
  case Dynamic

  def isDynamic: Boolean = this match
    case Dynamic => true
    case _ => false

  /** The manifest entries, empty for a dynamic skill. */
  def staticEntries: List[SkillResource] = this match
    case Static(es) => es
    case Dynamic => Nil

object SkillResources:
  val DynamicMarker = "dynamic"

  given JsonEncoder[SkillResources] = JsonEncoder[Json].contramap {
    case Static(entries) =>
      Json.Arr(Chunk.fromIterable(entries.map(_.toJsonAST.getOrElse(Json.Obj()))))
    case Dynamic => Json.Str(DynamicMarker)
  }

  given JsonDecoder[SkillResources] = JsonDecoder[Json].mapOrFail {
    case Json.Str(DynamicMarker) => Right(Dynamic)
    case Json.Arr(items) =>
      items.toList.zipWithIndex
        .foldLeft[Either[String, List[SkillResource]]](Right(Nil)) {
          case (Left(err), _) => Left(err)
          case (Right(acc), (item, i)) =>
            JsonDecoder[SkillResource]
              .fromJsonAST(item)
              .left
              .map(e => s"resources[$i]: $e")
              .map(_ :: acc)
        }
        .map(rs => Static(rs.reverse))
    case Json.Str(other) =>
      Left(s"""Skill.resources must be an array or the string "dynamic", got the string "$other"""")
    case other =>
      Left(
        s"""Skill.resources must be an array or the string "dynamic", got: ${other.getClass.getSimpleName}"""
      )
  }

  given JsonCodec[SkillResources] =
    JsonCodec(summon[JsonEncoder[SkillResources]], summon[JsonDecoder[SkillResources]])

/** The verbatim `SKILL.md` frontmatter as a JSON object. `name` and `description` are validated at
  * construction / decode time; every other field the author wrote is carried in `fields` unchanged
  * (in source order).
  */
final case class SkillFrontmatter private (fields: Chunk[(String, Json)]):

  def get(key: String): Option[Json] =
    val idx = fields.lastIndexWhere(_._1 == key)
    if idx < 0 then None else Some(fields(idx)._2)

  def name: String = get("name") match
    case Some(Json.Str(n)) => n
    case _ => "" // unreachable: validated at construction

  def description: String = get("description") match
    case Some(Json.Str(d)) => d
    case _ => ""

  def toJson: Json.Obj = Json.Obj(fields)

  /** Field-by-field content equality (key order is not content). */
  def sameContentAs(other: SkillFrontmatter): Boolean =
    fields.toMap == other.fields.toMap

object SkillFrontmatter:

  /** Build from a JSON object; requires non-empty string `name` and `description`. Does not apply
    * the Agent Skills naming rules — `core.skills.SkillNames` does, at authoring time.
    */
  def fromFields(fields: Chunk[(String, Json)]): Either[String, SkillFrontmatter] =
    val keys = fields.map(_._1)
    if keys.distinct.length != keys.length then Left("frontmatter has duplicate keys")
    else
      val fm = new SkillFrontmatter(fields)
      (fm.get("name"), fm.get("description")) match
        case (Some(Json.Str(n)), Some(Json.Str(d))) if n.nonEmpty && d.nonEmpty => Right(fm)
        case (Some(Json.Str(n)), _) if n.isEmpty => Left("frontmatter.name must be non-empty")
        case (Some(Json.Str(_)), Some(Json.Str(_))) =>
          Left("frontmatter.description must be non-empty")
        case (Some(Json.Str(_)), _) => Left("frontmatter.description must be a string")
        case _ => Left("frontmatter.name must be a string")

  def fromJson(json: Json): Either[String, SkillFrontmatter] = json match
    case Json.Obj(fields) => fromFields(fields)
    case other => Left(s"frontmatter must be a JSON object, got: ${other.getClass.getSimpleName}")

  given JsonEncoder[SkillFrontmatter] = JsonEncoder[Json].contramap(_.toJson)
  given JsonDecoder[SkillFrontmatter] = JsonDecoder[Json].mapOrFail(fromJson)

  given JsonCodec[SkillFrontmatter] =
    JsonCodec(summon[JsonEncoder[SkillFrontmatter]], summon[JsonDecoder[SkillFrontmatter]])

/** The entry for one skill, returned by `skills/list` (in `skills[]`) and by `skills/get` (as
  * `skill`) with identical shape and meaning.
  */
final case class Skill(uri: String, frontmatter: SkillFrontmatter, resources: SkillResources)

object Skill:
  given JsonCodec[Skill] = DeriveJsonCodec.gen[Skill]

// ---------- skills/list ----------

/** `ListSkillsResult extends PaginatedResult, CacheableResult`. `ttlMs` / `cacheScope` are REQUIRED
  * at 2026-07-28; the router strips them for pre-2026 sessions, like every other list.
  */
final case class ListSkillsResult(
    skills: List[Skill],
    nextCursor: Option[Cursor] = None,
    ttlMs: Long = CacheHints.TtlMs,
    cacheScope: CacheScope = CacheHints.Scope,
    _meta: Option[Map[String, Json]] = None
)

object ListSkillsResult:
  given JsonCodec[ListSkillsResult] = DeriveJsonCodec.gen[ListSkillsResult]

// ---------- skills/get ----------

final case class GetSkillRequestParams(uri: String, _meta: Option[Map[String, Json]] = None)

object GetSkillRequestParams:
  given JsonCodec[GetSkillRequestParams] = DeriveJsonCodec.gen[GetSkillRequestParams]

/** `GetSkillResult extends CacheableResult` — no pagination cursor. */
final case class GetSkillResult(
    skill: Skill,
    ttlMs: Long = CacheHints.TtlMs,
    cacheScope: CacheScope = CacheHints.Scope,
    _meta: Option[Map[String, Json]] = None
)

object GetSkillResult:
  given JsonCodec[GetSkillResult] = DeriveJsonCodec.gen[GetSkillResult]

// ---------- resources/directory/read ----------

final case class ReadResourceDirectoryRequestParams(
    uri: String,
    cursor: Option[Cursor] = None,
    _meta: Option[Map[String, Json]] = None
)

object ReadResourceDirectoryRequestParams:

  given JsonCodec[ReadResourceDirectoryRequestParams] =
    DeriveJsonCodec.gen[ReadResourceDirectoryRequestParams]

/** `ReadResourceDirectoryResult extends PaginatedResult` — ordinary [[Resource]] metadata of the
  * directory's direct children; no caching attributes are prescribed for it.
  */
final case class ReadResourceDirectoryResult(
    resources: List[Resource],
    nextCursor: Option[Cursor] = None,
    _meta: Option[Map[String, Json]] = None
)

object ReadResourceDirectoryResult:
  given JsonCodec[ReadResourceDirectoryResult] = DeriveJsonCodec.gen[ReadResourceDirectoryResult]
