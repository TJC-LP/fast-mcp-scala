package com.tjclp.fastmcp.core.skills

import scala.collection.immutable.SortedMap

import com.tjclp.fastmcp.core.wire.{Resource, Skill, SkillResource, SkillResources, Skills}

/** One published file: exact bytes (private, copied on read), the wire metadata and the digest and
  * size computed from those very bytes.
  */
final class PublishedFile private[skills] (
    val uri: String,
    val relativePath: String,
    private val data: Array[Byte],
    val isText: Boolean,
    val mimeType: String,
    val digest: String,
    val name: String,
    val description: Option[String]
):
  def size: Long = data.length.toLong

  /** Exact bytes (fresh copy). */
  def bytes: Array[Byte] = data.clone()

  /** The `resources/read` body: the UTF-8 text for text files, the raw bytes for binary ones. */
  def body: String | Array[Byte] =
    if isText then new String(data, java.nio.charset.StandardCharsets.UTF_8) else data.clone()

  def resource: Resource =
    Resource(
      uri = uri,
      name = name,
      description = description,
      mimeType = Some(mimeType),
      size = Some(size)
    )

  def manifestEntry: SkillResource = SkillResource(uri, digest, size)

  /** Same served bytes and metadata — two publications may share such a file. */
  def sameContentAs(other: PublishedFile): Boolean =
    digest == other.digest && size == other.size && isText == other.isText &&
      mimeType == other.mimeType && java.util.Arrays.equals(data, other.data)

/** A directory of the published tree: its URI, its `inode/directory` resource and its direct
  * children (files and subdirectories), sorted by name.
  */
final case class PublishedDirectory(uri: String, name: String, children: Vector[Resource]):

  def resource: Resource =
    Resource(uri = uri, name = name, mimeType = Some(Skills.DirectoryMimeType))

/** The immutable publication of one [[McpSkill]]: the wire entry (with digests), every file by URI,
  * and the directory index (skill root included). Built once at registration; every listing and
  * read afterwards is served from this object without re-reading or re-hashing anything.
  */
final class SkillSnapshot private (
    val skill: McpSkill,
    val entry: Skill,
    val files: SortedMap[String, PublishedFile],
    val directories: SortedMap[String, PublishedDirectory]
):
  def root: SkillUri = skill.root
  def rootUri: String = skill.root.render
  def uri: String = skill.uri.render
  def listed: Boolean = skill.listed

  def file(uri: String): Option[PublishedFile] = files.get(uri)
  def directory(uri: String): Option[PublishedDirectory] = directories.get(uri)

  /** Every URI this snapshot serves — files and directories. */
  def owns(uri: String): Boolean = files.contains(uri) || directories.contains(uri)

object SkillSnapshot:

  /** Publish `skill`: hash every file with `sha256`, build the manifest and the directory index. */
  def build(skill: McpSkill, sha256: Array[Byte] => Array[Byte] = Sha256.digest): SkillSnapshot =
    val root = skill.root
    val md = skill.skillMd
    val mdFile = new PublishedFile(
      uri = skill.uri.render,
      relativePath = Skills.SkillFileName,
      data = md,
      isText = true,
      mimeType = skill.skillMdMimeType.getOrElse(Skills.MarkdownMimeType),
      digest = Sha256.formatted(md, sha256),
      name = skill.name,
      description = Some(skill.description)
    )
    val supporting = skill.files.toList.map { case (path, file) =>
      val bytes = file.bytes
      val uri = root.descend(path).fold(e => throw new IllegalStateException(e.message), _.render)
      new PublishedFile(
        uri = uri,
        relativePath = path,
        data = bytes,
        isText = file.isText,
        mimeType = file.mimeType.getOrElse(SkillMimeTypes.forPath(path, file.isText)),
        digest = Sha256.formatted(bytes, sha256),
        name = path.substring(path.lastIndexOf('/') + 1),
        description = None
      )
    }
    val allFiles = SortedMap.from((mdFile :: supporting).map(f => f.uri -> f))

    // Directory index: the root, every ancestor of a file, every explicit empty directory.
    val dirPaths: Set[String] =
      skill.files.keySet.flatMap(McpSkill.ancestors) ++
        skill.emptyDirectories ++ skill.emptyDirectories.flatMap(McpSkill.ancestors)
    val dirUris: Map[String, String] = dirPaths.iterator.map { p =>
      p -> root.descend(p).fold(e => throw new IllegalStateException(e.message), _.render)
    }.toMap + ("" -> root.render)

    def parentPath(path: String): String =
      val i = path.lastIndexOf('/')
      if i < 0 then "" else path.substring(0, i)

    val childFiles: Map[String, List[Resource]] =
      (mdFile :: supporting)
        .groupBy(f => parentPath(f.relativePath))
        .view
        .mapValues(_.map(_.resource))
        .toMap
    val childDirs: Map[String, List[Resource]] =
      dirPaths.toList
        .groupBy(parentPath)
        .view
        .mapValues(_.map { p =>
          Resource(
            uri = dirUris(p),
            name = p.substring(p.lastIndexOf('/') + 1),
            mimeType = Some(Skills.DirectoryMimeType)
          )
        })
        .toMap

    val directories = SortedMap.from(dirUris.map { case (path, uri) =>
      val children =
        (childFiles.getOrElse(path, Nil) ++ childDirs.getOrElse(path, Nil)).sortBy(_.name).toVector
      val name =
        if path.isEmpty then root.lastSegment else path.substring(path.lastIndexOf('/') + 1)
      uri -> PublishedDirectory(uri, name, children)
    })

    val entry = Skill(
      uri = skill.uri.render,
      frontmatter = skill.frontmatter,
      resources = SkillResources.Static(allFiles.values.map(_.manifestEntry).toList)
    )
    new SkillSnapshot(skill, entry, allFiles, directories)
