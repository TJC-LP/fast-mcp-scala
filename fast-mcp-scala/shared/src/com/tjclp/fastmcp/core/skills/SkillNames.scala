package com.tjclp.fastmcp.core.skills

/** The Agent Skills specification's frontmatter rules (agentskills.io/specification, retrieved
  * 2026-09-15), which the MCP extension delegates to without change.
  */
object SkillNames:

  val MaxNameLength = 64
  val MaxDescriptionLength = 1024
  val MaxCompatibilityLength = 500

  /** `name`: 1-64 characters, lowercase `a-z`, `0-9` and `-`; no leading, trailing or consecutive
    * hyphens. Also the rule for the final segment of every skill path.
    */
  def validateName(name: String): Either[String, String] =
    if name.isEmpty then Left("name must be non-empty")
    else if name.length > MaxNameLength then Left(s"name must be at most $MaxNameLength characters")
    else if !name.forall(c => (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-') then
      Left(s"name '$name' may only contain lowercase letters, digits and hyphens")
    else if name.head == '-' || name.last == '-' then
      Left(s"name '$name' must not start or end with a hyphen")
    else if name.contains("--") then Left(s"name '$name' must not contain consecutive hyphens")
    else Right(name)

  def isValidName(name: String): Boolean = validateName(name).isRight

  /** `description`: 1-1024 characters (Unicode code points), non-empty. */
  def validateDescription(description: String): Either[String, String] =
    val length = description.codePointCount(0, description.length)
    if length == 0 then Left("description must be non-empty")
    else if length > MaxDescriptionLength then
      Left(s"description must be at most $MaxDescriptionLength characters")
    else Right(description)

  /** `compatibility`, when present: 1-500 characters. */
  def validateCompatibility(value: String): Either[String, String] =
    val length = value.codePointCount(0, value.length)
    if length == 0 then Left("compatibility must be non-empty when present")
    else if length > MaxCompatibilityLength then
      Left(s"compatibility must be at most $MaxCompatibilityLength characters")
    else Right(value)
