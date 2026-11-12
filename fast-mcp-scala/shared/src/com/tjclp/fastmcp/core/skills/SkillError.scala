package com.tjclp.fastmcp.core.skills

/** Typed failures of skill authoring, publication and verification. Every variant names the skill
  * (by root or `SKILL.md` URI where known) and the offending file, so a failing registration says
  * exactly what to fix.
  */
enum SkillError:
  /** A skill path / file path / URI failed the [[SkillUri]] policy. */
  case InvalidUri(skill: Option[String], value: String, reason: String)

  /** The `SKILL.md` bytes or frontmatter are invalid (encoding, delimiters, YAML, field rules). */
  case InvalidSkillMd(skill: String, reason: String)

  /** The final skill-path segment does not equal `frontmatter.name`. */
  case NameMismatch(skill: String, pathSegment: String, frontmatterName: String)

  /** A supporting file is invalid (encoding, path, collides with a directory or `SKILL.md`). */
  case InvalidFile(skill: String, path: String, reason: String)

  /** The skill exceeds a configured limit (resource count, total bytes, per-file bytes). */
  case LimitExceeded(skill: String, limit: String, actual: Long, max: Long)

  /** Two publications claim the same URI with different content, or a skill URI is already an
    * ordinary resource / matched by a template / inside a dynamic provider's namespace.
    */
  case ConflictingResource(skill: String, uri: String, reason: String)

  /** A `Skill` entry received from a server fails the entry rules (completeness, containment...).
    */
  case InvalidEntry(uri: String, reason: String)

  def message: String = this match
    case InvalidUri(skill, value, reason) =>
      s"invalid skill URI${skill.fold("")(s => s" in '$s'")}: '$value': $reason"
    case InvalidSkillMd(skill, reason) => s"invalid SKILL.md for '$skill': $reason"
    case NameMismatch(skill, seg, name) =>
      s"skill '$skill': final path segment '$seg' does not equal frontmatter.name '$name'"
    case InvalidFile(skill, path, reason) => s"skill '$skill': invalid file '$path': $reason"
    case LimitExceeded(skill, limit, actual, max) =>
      s"skill '$skill' exceeds $limit: $actual > $max"
    case ConflictingResource(skill, uri, reason) =>
      s"skill '$skill': conflicting resource '$uri': $reason"
    case InvalidEntry(uri, reason) => s"invalid skill entry '$uri': $reason"

object SkillError:
  /** Throwable form for effect channels and the throwing constructors. */
  final class Exception(val error: SkillError) extends RuntimeException(error.message)

  def fail[A](error: SkillError): A = throw new Exception(error)
