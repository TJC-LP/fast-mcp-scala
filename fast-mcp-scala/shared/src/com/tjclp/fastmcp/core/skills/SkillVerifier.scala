package com.tjclp.fastmcp.core.skills

import java.util.Base64

import com.tjclp.fastmcp.core.wire.{
  BlobResourceContents,
  ResourceContents,
  Skill,
  SkillResources,
  Skills,
  TextResourceContents
}

/** The identity of an MCP-served skill: the HOST-assigned label of the originating server plus the
  * skill's `SKILL.md` URI. Never the URI alone, never the server's self-reported `serverInfo.name`
  * (§Skill URIs, §Security Considerations).
  */
final case class SkillIdentity(serverLabel: String, uri: String):
  require(serverLabel.nonEmpty, "serverLabel must be a host-assigned, non-empty label")
  require(uri.nonEmpty, "uri must be non-empty")

/** An immutable snapshot of the entry a host obtained from `skills/list` / `skills/get`, bound to
  * the skill's identity. Verification never mutates it: a failure is reported, and the host decides
  * whether to fetch a fresh entry (which revokes any content-bound approval).
  */
final case class HeldEntry(identity: SkillIdentity, entry: Skill):

  /** The `(uri, digest)` set a persisted approval binds to; `None` for a dynamic skill, which
    * cannot be content-bound.
    */
  def approvalKey: Option[Set[(String, String)]] = entry.resources match
    case SkillResources.Static(entries) => Some(entries.map(r => r.uri -> r.digest).toSet)
    case SkillResources.Dynamic => None

  /** Whether a fresh entry describes the same content set (a differing set revokes approval). */
  def sameContentSet(fresh: Skill): Boolean =
    approvalKey.isDefined && approvalKey == HeldEntry(identity, fresh).approvalKey

object HeldEntry:

  def apply(serverLabel: String, entry: Skill): HeldEntry =
    HeldEntry(SkillIdentity(serverLabel, entry.uri), entry)

/** Why a verification failed. Every failure means the content MUST NOT be used under the held entry
  * (§Integrity and Verification).
  */
enum VerificationFailure:
  case UnlistedFile(uri: String)
  case NotWithinSkill(uri: String)
  case SizeMismatch(uri: String, expected: Long, actual: Long)
  case DigestMismatch(uri: String, expected: String, actual: String)
  case FrontmatterMismatch(uri: String, differences: List[String])
  case InvalidSkillMd(uri: String, reason: String)
  case InvalidEntry(uri: String, reason: String)
  case UndecodableContents(uri: String, reason: String)

  def message: String = this match
    case UnlistedFile(uri) => s"$uri is not listed in the held entry's resources"
    case NotWithinSkill(uri) => s"$uri is not within the skill"
    case SizeMismatch(uri, e, a) => s"$uri: size $a does not match the entry's $e"
    case DigestMismatch(uri, e, a) => s"$uri: digest $a does not match the entry's $e"
    case FrontmatterMismatch(uri, diffs) =>
      s"$uri: frontmatter differs from the entry: ${diffs.mkString("; ")}"
    case InvalidSkillMd(uri, reason) => s"$uri: invalid SKILL.md: $reason"
    case InvalidEntry(uri, reason) => s"$uri: invalid entry: $reason"
    case UndecodableContents(uri, reason) => s"$uri: cannot decode resource contents: $reason"

/** The three possible outcomes of verifying fetched bytes against a held entry. */
enum VerificationOutcome:
  /** Size and digest match the entry (and, for `SKILL.md`, the frontmatter is identical). */
  case Verified(uri: String, size: Long, digest: String)

  /** The entry is `"dynamic"`: nothing to verify against. `SKILL.md` frontmatter was still checked
    * when this is returned for the skill's own URI. Not integrity-verified.
    */
  case Unverifiable(uri: String)

  case Failed(failure: VerificationFailure)

  def isVerified: Boolean = this match
    case Verified(_, _, _) => true
    case _ => false

/** Platform-neutral, network-independent helpers for the host-side half of the extension:
  * validating an entry, verifying fetched bytes against it, and comparing fetched frontmatter. They
  * stop at pure functions — this SDK has no client layer to hang a loader on — and none of them
  * ever refreshes an entry on failure.
  *
  * Hashes prove consistency between an entry and the bytes a server sent, not authorship or trust
  * (§Digests Are Not a Trust Anchor).
  */
object SkillVerifier:

  /** Entry-level rules checkable without fetching anything (§Resources, §Limits, §Skill URIs): the
    * URI is a canonical `SKILL.md` URI whose final skill-path segment equals `frontmatter.name`; a
    * static manifest is non-empty, duplicate-free, contains the skill's own URI, lists only files
    * within the skill root, and (when `enforceLimits`) stays within the interoperability limits
    * with checked arithmetic.
    */
  def validateEntry(
      entry: Skill,
      enforceLimits: Boolean = true,
      maxResources: Int = Skills.MaxResourcesPerSkill,
      maxTotalBytes: Long = Skills.MaxTotalBytesPerSkill
  ): Either[VerificationFailure, Unit] =
    val invalid = (reason: String) => Left(VerificationFailure.InvalidEntry(entry.uri, reason))
    SkillUri.parse(entry.uri) match
      case Left(e) => invalid(s"uri: ${e.message}")
      case Right(skillMd) =>
        SkillUri.skillRootOf(skillMd) match
          case None => invalid(s"uri does not end in /${Skills.SkillFileName}")
          case Some(root) =>
            if !SkillNames.isValidName(root.lastSegment) then
              invalid(s"final skill-path segment '${root.lastSegment}' violates the naming rules")
            else if root.lastSegment != entry.frontmatter.name then
              invalid(
                s"final skill-path segment '${root.lastSegment}' does not equal frontmatter.name '${entry.frontmatter.name}'"
              )
            else
              entry.resources match
                case SkillResources.Dynamic => Right(())
                case SkillResources.Static(entries) =>
                  val uris = entries.map(_.uri)
                  if entries.isEmpty then invalid("resources is empty")
                  else if !uris.contains(entry.uri) then
                    invalid("resources has no entry for the skill's own SKILL.md")
                  else if uris.distinct.length != uris.length then
                    invalid(
                      s"resources lists ${uris.diff(uris.distinct).distinct.mkString(", ")} more than once"
                    )
                  else
                    val outside = entries.find(r =>
                      SkillUri.parse(r.uri).fold(_ => true, u => !u.isWithin(root))
                    )
                    val badDigest = entries.find(r => !Skills.isValidDigest(r.digest))
                    val negative = entries.find(_.size < 0)
                    val total =
                      try Right(entries.foldLeft(0L)((acc, r) => Math.addExact(acc, r.size)))
                      catch case _: ArithmeticException => Left(())
                    if outside.isDefined then
                      invalid(s"${outside.get.uri} is outside the skill root ${root.render}")
                    else if badDigest.isDefined then
                      invalid(s"${badDigest.get.uri}: digest is not sha256:<64 lowercase hex>")
                    else if negative.isDefined then invalid(s"${negative.get.uri}: negative size")
                    else if total.isLeft then invalid("total size overflows")
                    else if enforceLimits && entries.length > maxResources then
                      invalid(s"${entries.length} resources exceed the limit of $maxResources")
                    else if enforceLimits && total.exists(_ > maxTotalBytes) then
                      invalid(
                        s"total size ${total.getOrElse(0L)} exceeds the limit of $maxTotalBytes bytes"
                      )
                    else Right(())

  /** Verify raw bytes read for `uri` against the held entry: unlisted → failure; size then digest
    * compared; a dynamic entry → [[VerificationOutcome.Unverifiable]] (after a containment check).
    * For the skill's own `SKILL.md` use [[verifySkillMd]], which also compares the frontmatter.
    */
  def verifyFile(
      held: HeldEntry,
      uri: String,
      bytes: Array[Byte],
      sha256: Array[Byte] => Array[Byte] = Sha256.digest
  ): VerificationOutcome =
    held.entry.resources match
      case SkillResources.Dynamic =>
        withinSkill(held.entry, uri) match
          case Left(f) => VerificationOutcome.Failed(f)
          case Right(()) => VerificationOutcome.Unverifiable(uri)
      case SkillResources.Static(entries) =>
        entries.find(_.uri == uri) match
          case None => VerificationOutcome.Failed(VerificationFailure.UnlistedFile(uri))
          case Some(listed) =>
            val size = bytes.length.toLong
            if size != listed.size then
              VerificationOutcome.Failed(VerificationFailure.SizeMismatch(uri, listed.size, size))
            else
              val digest = Sha256.formatted(bytes, sha256)
              if digest != listed.digest then
                VerificationOutcome.Failed(
                  VerificationFailure.DigestMismatch(uri, listed.digest, digest)
                )
              else VerificationOutcome.Verified(uri, size, digest)

  /** Verify a fetched `SKILL.md`: bytes as [[verifyFile]] (or unverifiable when dynamic), then the
    * frontmatter is parsed and compared field by field against the entry. Any discrepancy is a
    * failure, dynamic or not (§Frontmatter Verification).
    */
  def verifySkillMd(
      held: HeldEntry,
      bytes: Array[Byte],
      sha256: Array[Byte] => Array[Byte] = Sha256.digest
  ): VerificationOutcome =
    val uri = held.entry.uri
    verifyFile(held, uri, bytes, sha256) match
      case failed @ VerificationOutcome.Failed(_) => failed
      case ok =>
        SkillFrontmatterReader.parse(bytes) match
          case Left(reason) =>
            VerificationOutcome.Failed(VerificationFailure.InvalidSkillMd(uri, reason))
          case Right(parsed) =>
            val diffs =
              frontmatterDifferences(parsed.frontmatter.toJson, held.entry.frontmatter.toJson)
            if diffs.isEmpty then ok
            else VerificationOutcome.Failed(VerificationFailure.FrontmatterMismatch(uri, diffs))

  /** Verify a `resources/read` payload: `text` is re-encoded as UTF-8, `blob` is base64-decoded,
    * and the resulting raw bytes are checked. The skill's own URI goes through [[verifySkillMd]].
    */
  def verifyContents(
      held: HeldEntry,
      contents: ResourceContents,
      sha256: Array[Byte] => Array[Byte] = Sha256.digest
  ): VerificationOutcome =
    val bytes: Either[VerificationFailure, Array[Byte]] = contents match
      case TextResourceContents(_, text, _, _) => Right(Utf8.encode(text))
      case BlobResourceContents(uri, blob, _, _) =>
        try Right(Base64.getDecoder.decode(blob))
        catch
          case e: IllegalArgumentException =>
            Left(
              VerificationFailure.UndecodableContents(
                uri,
                Option(e.getMessage).getOrElse("invalid base64")
              )
            )
    bytes match
      case Left(f) => VerificationOutcome.Failed(f)
      case Right(raw) =>
        if contents.uri == held.entry.uri then verifySkillMd(held, raw, sha256)
        else verifyFile(held, contents.uri, raw, sha256)

  /** Field-by-field differences between a parsed frontmatter and an entry's (content, not key
    * order). Empty when identical.
    */
  def frontmatterDifferences(
      fromFile: zio.json.ast.Json.Obj,
      fromEntry: zio.json.ast.Json.Obj
  ): List[String] =
    val a = fromFile.fields.toMap
    val b = fromEntry.fields.toMap
    (a.keySet ++ b.keySet).toList.sorted.flatMap { k =>
      (a.get(k), b.get(k)) match
        case (Some(x), Some(y)) if x == y => None
        case (x, y) =>
          Some(
            s"$k: file=${x.map(_.toString).getOrElse("<absent>")} entry=${y.map(_.toString).getOrElse("<absent>")}"
          )
    }

  private def withinSkill(entry: Skill, uri: String): Either[VerificationFailure, Unit] =
    (
      SkillUri.parse(entry.uri).toOption.flatMap(SkillUri.skillRootOf),
      SkillUri.parse(uri).toOption
    ) match
      case (Some(root), Some(u)) if uri == entry.uri || u.isWithin(root) => Right(())
      case _ => Left(VerificationFailure.NotWithinSkill(uri))
