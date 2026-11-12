package com.tjclp.fastmcp.core.skills

import scala.collection.immutable.SortedMap

import com.tjclp.fastmcp.core.wire.{SkillFrontmatter, Skills}

/** One supporting file of a skill: exact bytes plus whether `resources/read` serves them as `text`
  * (UTF-8, validated) or as a base64 `blob`. Bytes are copied on construction and on every read, so
  * a caller mutating its array afterwards cannot change what is published.
  */
sealed trait SkillFile:
  def mimeType: Option[String]
  def isText: Boolean

  /** A fresh copy of the exact bytes. */
  def bytes: Array[Byte]
  def size: Long = bytes.length.toLong

object SkillFile:

  final class Text private[SkillFile] (private val data: Array[Byte], val mimeType: Option[String])
      extends SkillFile:
    def isText: Boolean = true
    def bytes: Array[Byte] = data.clone()
    def content: String = new String(data, java.nio.charset.StandardCharsets.UTF_8)
    override def toString: String = s"SkillFile.Text(${data.length} bytes, $mimeType)"

  final class Binary private[SkillFile] (
      private val data: Array[Byte],
      val mimeType: Option[String]
  ) extends SkillFile:
    def isText: Boolean = false
    def bytes: Array[Byte] = data.clone()
    override def toString: String = s"SkillFile.Binary(${data.length} bytes, $mimeType)"

  /** A UTF-8 text file from a Scala string (always well-formed UTF-8). */
  def text(content: String, mimeType: Option[String] = None): SkillFile =
    new Text(Utf8.encode(content), mimeType)

  /** A text file from raw bytes; fails unless they are strict UTF-8. */
  def textBytes(content: Array[Byte], mimeType: Option[String] = None): Either[String, SkillFile] =
    Utf8.decodeStrict(content) match
      case Right(_) => Right(new Text(content.clone(), mimeType))
      case Left(off) => Left(s"text file is not valid UTF-8 (byte offset $off)")

  /** A binary file, served as a base64 blob whose digest covers the decoded bytes. */
  def binary(content: Array[Byte], mimeType: Option[String] = None): SkillFile =
    new Binary(content.clone(), mimeType)

/** A validated, in-memory skill: the exact `SKILL.md` bytes, its parsed frontmatter, the supporting
  * files keyed by skill-relative path, and any directories that must exist even when empty.
  *
  * Construct through [[McpSkill.parse]] (an `Either`) or [[McpSkill.fromMarkdown]] (throws
  * [[SkillError.Exception]] — for declarative `val skills = List(...)` sites). Both validate
  * everything up front: the skill path ([[SkillUri]] policy, final segment equals
  * `frontmatter.name`), the frontmatter (Agent Skills rules, strict UTF-8), every file path (no dot
  * segments, no separators, no path that is both a file and a directory, no second top-level
  * `SKILL.md`). The per-skill limits (512 entries / 16 MiB) are checked at publication against the
  * server's `SkillSettings`, because they are configurable there; [[McpSkill.checkLimits]] applies
  * the spec defaults.
  *
  * `listed = false` keeps a skill out of `skills/list` (and `resources/list`) while `skills/get`
  * and `resources/read` still resolve it — the spec's partial-catalog case.
  */
final class McpSkill private (
    val root: SkillUri,
    val frontmatter: SkillFrontmatter,
    private val skillMdBytes: Array[Byte],
    val files: SortedMap[String, SkillFile],
    val emptyDirectories: Set[String],
    val listed: Boolean,
    val skillMdMimeType: Option[String]
):

  /** `<root>/SKILL.md` — the skill's identity URI. */
  val uri: SkillUri = root.child(Skills.SkillFileName).getOrElse(root)

  def name: String = frontmatter.name
  def description: String = frontmatter.description

  /** A fresh copy of the exact `SKILL.md` bytes. */
  def skillMd: Array[Byte] = skillMdBytes.clone()
  def skillMdSize: Long = skillMdBytes.length.toLong

  /** `SKILL.md` plus every supporting file. */
  def resourceCount: Int = files.size + 1

  /** Sum of all file sizes in bytes (checked arithmetic; the inputs are array lengths). */
  def totalBytes: Long = files.values.foldLeft(skillMdSize)((acc, f) => Math.addExact(acc, f.size))

  def withListed(value: Boolean): McpSkill =
    new McpSkill(root, frontmatter, skillMdBytes, files, emptyDirectories, value, skillMdMimeType)

  /** Hidden from `skills/list` / `resources/list`; still served by `skills/get` and
    * `resources/read`.
    */
  def unlisted: McpSkill = withListed(false)

  /** Apply the spec's interoperability limits (or custom ones). */
  def checkLimits(
      maxResources: Int = Skills.MaxResourcesPerSkill,
      maxTotalBytes: Long = Skills.MaxTotalBytesPerSkill
  ): Either[SkillError, McpSkill] =
    if resourceCount > maxResources then
      Left(
        SkillError.LimitExceeded(
          uri.render,
          "resources per skill",
          resourceCount.toLong,
          maxResources.toLong
        )
      )
    else
      val total =
        try Right(totalBytes)
        catch case _: ArithmeticException => Left(Long.MaxValue)
      total match
        case Left(_) =>
          Left(
            SkillError.LimitExceeded(
              uri.render,
              "total bytes per skill",
              Long.MaxValue,
              maxTotalBytes
            )
          )
        case Right(t) if t > maxTotalBytes =>
          Left(SkillError.LimitExceeded(uri.render, "total bytes per skill", t, maxTotalBytes))
        case Right(_) => Right(this)

  /** The skill nested at `relativeDirectory` (which must hold a `SKILL.md`), as its own catalog
    * entry: the nested `SKILL.md` becomes the manifest and the files below the directory become its
    * supporting files. The parent keeps every file — nested content is supporting content from the
    * parent's perspective.
    */
  def nestedSkill(relativeDirectory: String): Either[SkillError, McpSkill] =
    val prefix = relativeDirectory.stripSuffix("/") + "/"
    val skillMdPath = prefix + Skills.SkillFileName
    files.get(skillMdPath) match
      case None =>
        Left(
          SkillError.InvalidFile(uri.render, skillMdPath, "nested skill directory has no SKILL.md")
        )
      case Some(md) =>
        val nestedFiles = files.collect {
          case (path, file) if path.startsWith(prefix) && path != skillMdPath =>
            path.substring(prefix.length) -> file
        }
        val nestedDirs = emptyDirectories.collect {
          case d if d.startsWith(prefix) && d.length > prefix.length => d.substring(prefix.length)
        }
        for
          nestedRoot <- root
            .descend(relativeDirectory)
            .left
            .map(e => SkillError.InvalidUri(Some(uri.render), relativeDirectory, e.message))
          skill <- McpSkill.build(
            nestedRoot,
            md.bytes,
            nestedFiles,
            nestedDirs,
            listed,
            md.mimeType
          )
        yield skill

  override def toString: String =
    s"McpSkill(${uri.render}, ${files.size} files, listed=$listed)"

object McpSkill:

  /** Build a skill from a Markdown string and in-memory files; `Left` on any validation failure.
    *
    * @param skillPath
    *   the `/`-separated skill path (`git-workflow`, `acme/billing/refunds`); its final segment
    *   must equal `frontmatter.name`
    * @param markdown
    *   the exact `SKILL.md` content
    * @param files
    *   supporting files keyed by skill-relative path (`references/rules.md`)
    * @param emptyDirectories
    *   directories to publish even when they hold no file (`templates/drafts`)
    * @param scheme
    *   the URI scheme (`skill` by default; any lowercase scheme is accepted)
    */
  def parse(
      skillPath: String,
      markdown: String,
      files: Map[String, SkillFile] = Map.empty,
      emptyDirectories: Set[String] = Set.empty,
      scheme: String = SkillUri.DefaultScheme,
      skillMdMimeType: Option[String] = Some(Skills.MarkdownMimeType),
      frontmatterLimits: SkillFrontmatterReader.Limits = SkillFrontmatterReader.Limits.default
  ): Either[SkillError, McpSkill] =
    parseBytes(
      skillPath,
      Utf8.encode(markdown),
      files,
      emptyDirectories,
      scheme,
      skillMdMimeType,
      frontmatterLimits
    )

  /** [[parse]] over the exact `SKILL.md` bytes (strict UTF-8 is required). */
  def parseBytes(
      skillPath: String,
      markdown: Array[Byte],
      files: Map[String, SkillFile] = Map.empty,
      emptyDirectories: Set[String] = Set.empty,
      scheme: String = SkillUri.DefaultScheme,
      skillMdMimeType: Option[String] = Some(Skills.MarkdownMimeType),
      frontmatterLimits: SkillFrontmatterReader.Limits = SkillFrontmatterReader.Limits.default
  ): Either[SkillError, McpSkill] =
    for
      root <- SkillUri
        .fromPath(scheme, skillPath)
        .left
        .map(e => SkillError.InvalidUri(None, s"$scheme://$skillPath", e.message))
      skill <- build(
        root,
        markdown,
        files,
        emptyDirectories,
        listed = true,
        skillMdMimeType,
        frontmatterLimits
      )
    yield skill

  /** Throwing form of [[parse]] for declarative registration sites. */
  def fromMarkdown(
      skillPath: String,
      markdown: String,
      files: Map[String, SkillFile] = Map.empty,
      emptyDirectories: Set[String] = Set.empty,
      scheme: String = SkillUri.DefaultScheme
  ): McpSkill =
    parse(skillPath, markdown, files, emptyDirectories, scheme).fold(SkillError.fail, identity)

  /** Throwing form of [[parseBytes]]. */
  def fromMarkdownBytes(
      skillPath: String,
      markdown: Array[Byte],
      files: Map[String, SkillFile] = Map.empty,
      emptyDirectories: Set[String] = Set.empty,
      scheme: String = SkillUri.DefaultScheme
  ): McpSkill =
    parseBytes(skillPath, markdown, files, emptyDirectories, scheme).fold(SkillError.fail, identity)

  private[skills] def build(
      root: SkillUri,
      markdown: Array[Byte],
      files: Map[String, SkillFile],
      emptyDirectories: Set[String],
      listed: Boolean,
      skillMdMimeType: Option[String],
      frontmatterLimits: SkillFrontmatterReader.Limits = SkillFrontmatterReader.Limits.default
  ): Either[SkillError, McpSkill] =
    val skillUri = root.render + "/" + Skills.SkillFileName
    for
      _ <- SkillNames
        .validateName(root.lastSegment)
        .left
        .map(e =>
          SkillError.InvalidUri(Some(skillUri), root.render, s"final skill-path segment: $e")
        )
      parsed <- SkillFrontmatterReader
        .parse(markdown, frontmatterLimits)
        .left
        .map(e => SkillError.InvalidSkillMd(skillUri, e))
      _ <-
        if parsed.frontmatter.name == root.lastSegment then Right(())
        else Left(SkillError.NameMismatch(skillUri, root.lastSegment, parsed.frontmatter.name))
      validFiles <- validateFiles(skillUri, root, files)
      validDirs <- validateDirectories(skillUri, root, emptyDirectories, validFiles)
    yield new McpSkill(
      root,
      parsed.frontmatter,
      markdown.clone(),
      validFiles,
      validDirs,
      listed,
      skillMdMimeType
    )

  private def validateFiles(
      skillUri: String,
      root: SkillUri,
      files: Map[String, SkillFile]
  ): Either[SkillError, SortedMap[String, SkillFile]] =
    val sorted = SortedMap.from(files)
    val paths = sorted.keySet
    val directories = paths.flatMap(p => ancestors(p))
    sorted.keys
      .foldLeft[Either[SkillError, Unit]](Right(())) {
        case (Left(e), _) => Left(e)
        case (Right(()), path) =>
          if path == Skills.SkillFileName then
            Left(
              SkillError.InvalidFile(
                skillUri,
                path,
                "the top-level SKILL.md is the skill's markdown, not a supporting file"
              )
            )
          else
            root.descend(path) match
              case Left(e) => Left(SkillError.InvalidFile(skillUri, path, e.message))
              case Right(_) if directories.contains(path) =>
                Left(SkillError.InvalidFile(skillUri, path, "path is both a file and a directory"))
              case Right(_) => Right(())
      }
      .map(_ => sorted)

  private def validateDirectories(
      skillUri: String,
      root: SkillUri,
      emptyDirectories: Set[String],
      files: SortedMap[String, SkillFile]
  ): Either[SkillError, Set[String]] =
    emptyDirectories.toList.sorted.foldLeft[Either[SkillError, Set[String]]](Right(Set.empty)) {
      case (Left(e), _) => Left(e)
      case (Right(acc), dir) =>
        val normalized = dir.stripSuffix("/")
        root.descend(normalized) match
          case Left(e) =>
            Left(SkillError.InvalidFile(skillUri, dir, s"invalid directory: ${e.message}"))
          case Right(_) if files.contains(normalized) =>
            Left(SkillError.InvalidFile(skillUri, dir, "directory path is also a file"))
          case Right(_) if normalized == Skills.SkillFileName =>
            Left(SkillError.InvalidFile(skillUri, dir, "SKILL.md cannot be a directory"))
          case Right(_) =>
            files.keys.find(f => normalized.startsWith(f + "/")) match
              case Some(f) =>
                Left(SkillError.InvalidFile(skillUri, dir, s"directory lies under file '$f'"))
              case None => Right(acc + normalized)
    }

  /** Every proper ancestor directory of a relative path (`a/b/c.md` → `a`, `a/b`). */
  private[skills] def ancestors(path: String): List[String] =
    val parts = path.split("/").toList
    parts.init.scanLeft("")((acc, p) => if acc.isEmpty then p else s"$acc/$p").drop(1)
