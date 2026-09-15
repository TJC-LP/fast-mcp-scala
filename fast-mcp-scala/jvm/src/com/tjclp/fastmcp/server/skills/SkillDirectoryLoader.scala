package com.tjclp.fastmcp.server.skills

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}
import java.nio.file.attribute.BasicFileAttributes

import scala.jdk.CollectionConverters.*

import zio.*

import com.tjclp.fastmcp.core.skills.*
import com.tjclp.fastmcp.core.wire.Skills

/** JVM-only convenience adapter: load a skill directory from the local filesystem into a validated
  * [[McpSkill]], which then goes through the same shared publication pipeline as an in-memory skill
  * (`server.skill(...)`). Nothing here is reachable from Scala.js or Scala Native; the shared core
  * has no filesystem dependency.
  *
  * '''Selection policy''' ([[SkillDirectoryLoader.Options]]): regular files only, hidden entries
  * (dot-files) and the default exclusion set (`.git`, `node_modules`, `__pycache__`, `.DS_Store`,
  * `Thumbs.db`, `.env*`) skipped, everything else published. The manifest is computed from exactly
  * the files that were selected and read, so it can never claim a tree different from the one
  * served. Nothing is executed, no executable bit is honoured, no archive is extracted.
  *
  * '''Filesystem assumptions and safety.''' The caller chooses the root; it is resolved once with
  * `toRealPath`. Every entry is inspected with `NOFOLLOW_LINKS`: a symbolic link, device, socket or
  * other non-regular entry fails the load (fail closed) unless `Options.skipSpecialFiles` is set,
  * in which case it is skipped and listed in the result's `skipped`. Files are opened with
  * `NOFOLLOW_LINKS` (`O_NOFOLLOW` on POSIX, so a link swapped in for the final path component is
  * refused by the kernel), reads are bounded by `maxFileBytes`, and after reading the entry's
  * attributes are re-read and compared (size, file key) so a file replaced mid-read is rejected.
  * Every entry's real path must stay under the real root. This detects an escape through a
  * symlinked parent that existed at walk time and refuses obviously racing replacements; it is NOT
  * a proof against every TOCTOU race a hostile writer with concurrent access to the tree can stage
  * (there is no portable openat-style descriptor-relative traversal in the JDK), so the tree must
  * belong to the operator, never to a remote party.
  */
object SkillDirectoryLoader:

  /** @param scheme
    *   URI scheme for the published skill
    * @param namespace
    *   prefix segments placed before the skill name (`List("acme", "billing")` →
    *   `skill://acme/billing/<name>`); at least the authority segment is needed unless `skillPath`
    *   is given, so a single-segment skill path is `namespace = Nil` + directory name
    * @param skillPath
    *   explicit full skill path (`acme/billing/refunds`); when given, `namespace` is ignored and
    *   the directory name need not equal the skill name (the path's final segment must)
    * @param maxFiles
    *   maximum number of files (SKILL.md included)
    * @param maxTotalBytes
    *   maximum summed file size
    * @param maxFileBytes
    *   maximum size of any one file
    * @param includeHidden
    *   include dot-files and dot-directories
    * @param exclude
    *   entry names (not paths) excluded at every level
    * @param skipSpecialFiles
    *   skip symlinks / non-regular entries instead of failing the load
    * @param textExtensions
    *   lower-case extensions served as `text` when their bytes are valid UTF-8; everything else is
    *   a base64 `blob`
    */
  final case class Options(
      scheme: String = SkillUri.DefaultScheme,
      namespace: List[String] = Nil,
      skillPath: Option[String] = None,
      maxFiles: Int = Skills.MaxResourcesPerSkill,
      maxTotalBytes: Long = Skills.MaxTotalBytesPerSkill,
      maxFileBytes: Long = Skills.MaxTotalBytesPerSkill,
      includeHidden: Boolean = false,
      exclude: Set[String] = Options.DefaultExcludes,
      skipSpecialFiles: Boolean = false,
      textExtensions: Set[String] = SkillMimeTypes.TextExtensions
  ):
    require(maxFiles >= 1, "maxFiles must be >= 1")
    require(maxTotalBytes >= 1L, "maxTotalBytes must be >= 1")
    require(maxFileBytes >= 1L, "maxFileBytes must be >= 1")

  object Options:

    val DefaultExcludes: Set[String] =
      Set(".git", ".hg", ".svn", "node_modules", "__pycache__", ".DS_Store", "Thumbs.db")

  /** A loaded skill plus the entries the selection policy skipped (empty unless `skipSpecialFiles`
    * or exclusions applied).
    */
  final case class Loaded(skill: McpSkill, skipped: List[String])

  /** Load `root` as a skill. Blocking filesystem work runs on ZIO's blocking executor. */
  def load(root: Path, options: Options = Options()): ZIO[Any, Throwable, McpSkill] =
    loadDetailed(root, options).map(_.skill)

  def loadDetailed(root: Path, options: Options = Options()): ZIO[Any, Throwable, Loaded] =
    ZIO
      .attemptBlocking(loadBlocking(root, options))
      .flatMap(ZIO.fromEither(_))
      .mapError {
        case e: SkillError => new SkillError.Exception(e)
        case other: Throwable => other
      }

  private def loadBlocking(rootIn: Path, options: Options): Either[SkillError | Throwable, Loaded] =
    val skillLabel = rootIn.toString
    try
      val root = rootIn.toRealPath()
      val attrs =
        Files.readAttributes(root, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
      if !attrs.isDirectory then
        Left(SkillError.InvalidFile(skillLabel, root.toString, "not a directory"))
      else
        val dirName = root.getFileName.toString
        val skipped = scala.collection.mutable.ListBuffer.empty[String]
        val files = scala.collection.mutable.LinkedHashMap.empty[String, SkillFile]
        val emptyDirs = scala.collection.mutable.LinkedHashSet.empty[String]
        var total = 0L
        var count = 0

        def walk(dir: Path, rel: String): Either[SkillError, Unit] =
          val stream = Files.newDirectoryStream(dir)
          try
            val entries = stream.iterator().asScala.toList.sortBy(_.getFileName.toString)
            var seenChild = false
            val result = entries.foldLeft[Either[SkillError, Unit]](Right(())) {
              case (Left(e), _) => Left(e)
              case (Right(()), entry) =>
                val name = entry.getFileName.toString
                val relPath = if rel.isEmpty then name else s"$rel/$name"
                if options.exclude
                    .contains(name) || (!options.includeHidden && name.startsWith(".")) ||
                  (!options.includeHidden && name.startsWith(".env"))
                then
                  skipped += relPath
                  Right(())
                else
                  val a = Files.readAttributes(
                    entry,
                    classOf[BasicFileAttributes],
                    LinkOption.NOFOLLOW_LINKS
                  )
                  val real = entry.toRealPath(LinkOption.NOFOLLOW_LINKS)
                  if !real.startsWith(root) then
                    Left(
                      SkillError.InvalidFile(skillLabel, relPath, "resolves outside the skill root")
                    )
                  else if a.isSymbolicLink || a.isOther then
                    if options.skipSpecialFiles then
                      skipped += relPath
                      Right(())
                    else
                      Left(
                        SkillError.InvalidFile(
                          skillLabel,
                          relPath,
                          if a.isSymbolicLink then "symbolic links are not published"
                          else "not a regular file"
                        )
                      )
                  else if a.isDirectory then
                    seenChild = true
                    walk(entry, relPath)
                  else if !a.isRegularFile then
                    Left(SkillError.InvalidFile(skillLabel, relPath, "not a regular file"))
                  else
                    seenChild = true
                    if a.size() > options.maxFileBytes then
                      Left(
                        SkillError.LimitExceeded(
                          skillLabel,
                          s"bytes in '$relPath'",
                          a.size(),
                          options.maxFileBytes
                        )
                      )
                    else
                      count += 1
                      if count > options.maxFiles then
                        Left(
                          SkillError.LimitExceeded(
                            skillLabel,
                            "files",
                            count.toLong,
                            options.maxFiles.toLong
                          )
                        )
                      else
                        readBounded(entry, a, options.maxFileBytes) match
                          case Left(reason) =>
                            Left(SkillError.InvalidFile(skillLabel, relPath, reason))
                          case Right(bytes) =>
                            total = Math.addExact(total, bytes.length.toLong)
                            if total > options.maxTotalBytes then
                              Left(
                                SkillError.LimitExceeded(
                                  skillLabel,
                                  "total bytes",
                                  total,
                                  options.maxTotalBytes
                                )
                              )
                            else if relPath == Skills.SkillFileName then
                              files.update(
                                relPath,
                                SkillFile.binary(bytes)
                              ) // placeholder; handled below
                              Right(())
                            else
                              files.update(relPath, classify(relPath, bytes, options))
                              Right(())
            }
            if result.isRight && !seenChild && rel.nonEmpty then emptyDirs += rel
            result
          finally stream.close()

        walk(root, "").flatMap { _ =>
          files.remove(Skills.SkillFileName) match
            case None =>
              Left(
                SkillError.InvalidSkillMd(
                  skillLabel,
                  s"no ${Skills.SkillFileName} at the skill root"
                )
              )
            case Some(md) =>
              val path = options.skillPath.getOrElse((options.namespace :+ dirName).mkString("/"))
              McpSkill
                .parseBytes(path, md.bytes, files.toMap, emptyDirs.toSet, options.scheme)
                .map(skill => Loaded(skill, skipped.toList))
        }
    catch
      case e: SkillError.Exception => Left(e.error)
      case e: java.io.IOException => Left(e)
      case e: ArithmeticException =>
        Left(
          SkillError.LimitExceeded(skillLabel, "total bytes", Long.MaxValue, options.maxTotalBytes)
        )

  /** Text when the extension says so and the bytes are strict UTF-8; binary otherwise. */
  private def classify(relPath: String, bytes: Array[Byte], options: Options): SkillFile =
    val name = relPath.substring(relPath.lastIndexOf('/') + 1)
    val dot = name.lastIndexOf('.')
    val ext = if dot >= 0 then name.substring(dot + 1).toLowerCase else ""
    if options.textExtensions.contains(ext) then
      SkillFile.textBytes(bytes).getOrElse(SkillFile.binary(bytes))
    else SkillFile.binary(bytes)

  /** Open without following a final-component symlink, read at most `max + 1` bytes, and refuse a
    * file whose size or identity changed between the attribute read and the end of the read.
    */
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private def readBounded(
      path: Path,
      before: BasicFileAttributes,
      max: Long
  ): Either[String, Array[Byte]] =
    val channel: SeekableByteChannel =
      Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
    try
      val expected = before.size()
      if expected > max then Left(s"file grew past $max bytes")
      else
        val buffer = ByteBuffer.allocate(expected.toInt + 1)
        var read = 0
        var done = false
        while !done do
          val n = channel.read(buffer)
          if n < 0 then done = true
          else
            read += n
            if !buffer.hasRemaining then done = true
        val after =
          Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
        val sameIdentity =
          after.isRegularFile && after.size() == expected &&
            Option(before.fileKey()).forall(k => k == after.fileKey())
        if read.toLong != expected || !sameIdentity then
          Left("file changed while it was being read")
        else
          val out = new Array[Byte](read)
          buffer.flip()
          buffer.get(out)
          Right(out)
    finally channel.close()
