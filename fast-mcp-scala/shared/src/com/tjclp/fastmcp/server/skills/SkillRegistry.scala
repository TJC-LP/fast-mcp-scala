package com.tjclp.fastmcp.server.skills

import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec
import scala.collection.immutable.SortedMap

import zio.*

import com.tjclp.fastmcp.core.{Cursor, ResourceDefinition}
import com.tjclp.fastmcp.core.skills.*
import com.tjclp.fastmcp.core.wire.{Resource, Skill, Skills}
import com.tjclp.fastmcp.jsonrpc.McpError
import com.tjclp.fastmcp.server.{McpContext, SkillSettings}
import com.tjclp.fastmcp.server.manager.ResourceSource

/** The server-side skill catalog: an atomically swapped generation of immutable [[SkillSnapshot]]s
  * plus the registered [[SkillProvider]]s, exposed to the router as one effectful provider and to
  * the [[com.tjclp.fastmcp.server.manager.ResourceManager]] as a [[ResourceSource]] (so skill files
  * are ordinary resources for `resources/list` / `resources/read`).
  *
  * '''Publication.''' [[publish]] validates every new skill, hashes its files once, checks the
  * whole resulting generation for conflicts — the same URI with different bytes, a file where
  * another skill has a directory, a nested skill whose files do not match its parent's, an overlap
  * with a dynamic provider's namespace, an existing static resource or a matching template — and
  * only then swaps the generation. A failure publishes nothing. Readers only ever observe a
  * complete generation; a concurrent `skills/list` sees either the old or the new catalog.
  *
  * '''Lookup is independent of enumeration.''' Unlisted skills (`McpSkill.unlisted`) and every
  * provider skill resolve through `get` / `read` whether or not they appear in `list`.
  *
  * '''Pagination''' is stateless ([[SkillCursor]]): deterministic ordering by URI, a cursor bound
  * to the operation, scope, generation and the items it pages over.
  */
final class SkillRegistry[R] private (
    val settings: SkillSettings,
    sha256: Array[Byte] => Array[Byte],
    externalConflict: String => Option[String]
) extends ResourceSource[R]:

  import SkillRegistry.*

  private val state =
    new AtomicReference[Generation[R]](Generation(0L, SortedMap.empty, Vector.empty))

  // ---- registration ----

  /** Atomically add or replace (by root URI) the given skills. */
  def publish(skills: List[McpSkill]): IO[SkillError, Unit] =
    ZIO.fromEither(
      for
        _ <- skills.foldLeft[Either[SkillError, Unit]](Right(())) {
          case (Left(e), _) => Left(e)
          case (Right(()), s) =>
            s.checkLimits(settings.maxResourcesPerSkill, settings.maxTotalBytesPerSkill)
              .map(_ => ())
        }
        _ <- duplicateRoots(skills)
        snapshots = skills.map(s => SkillSnapshot.build(s, sha256))
        _ <- swap { current =>
          val next = current.snapshots ++ snapshots.map(s => s.rootUri -> s)
          validate(Generation(current.generation + 1, next, current.providers))
        }
      yield ()
    )

  /** Atomically remove the skills rooted at the given URIs (unknown roots are ignored). */
  def remove(roots: List[String]): UIO[Unit] =
    ZIO.succeed {
      val _ = swap { current =>
        Right(Generation(current.generation + 1, current.snapshots -- roots, current.providers))
      }
    }

  /** Register a provider; its namespaces must not overlap any skill or provider already present. */
  def addProvider(provider: SkillProvider[R]): IO[SkillError, Unit] =
    ZIO.fromEither(
      if provider.namespaces.isEmpty then
        Left(
          SkillError
            .InvalidUri(None, "<provider>", "a skill provider must declare at least one namespace")
        )
      else
        swap { current =>
          validate(
            Generation(current.generation + 1, current.snapshots, current.providers :+ provider)
          )
        }
    )

  @tailrec
  private def swap(
      f: Generation[R] => Either[SkillError, Generation[R]]
  ): Either[SkillError, Unit] =
    val current = state.get()
    f(current) match
      case Left(e) => Left(e)
      case Right(next) => if state.compareAndSet(current, next) then Right(()) else swap(f)

  // ---- read side ----

  def isEmpty: Boolean =
    val g = state.get()
    g.snapshots.isEmpty && g.providers.isEmpty

  def nonEmpty: Boolean = !isEmpty

  /** Current publication generation (bumps on every successful publish / remove / addProvider). */
  def generation: Long = state.get().generation

  /** `directoryRead: true` is honest only if every provider can honour the method. */
  def directoryReadSupported: Boolean = state.get().providers.forall(_.supportsDirectoryRead)

  def snapshots: List[SkillSnapshot] = state.get().snapshots.values.toList

  def snapshot(rootUri: String): Option[SkillSnapshot] = state.get().snapshots.get(rootUri)

  /** Listed entries (in-memory listed skills + provider listings), sorted by URI. Metadata only. */
  def listAll(context: McpContext): ZIO[R, McpError, List[Skill]] =
    val g = state.get()
    val static = g.snapshots.values.filter(_.listed).map(_.entry).toList
    ZIO
      .foreach(g.providers.toList)(p =>
        p.list(context)
          .mapError(providerFailure(p, "list", _))
          .flatMap(validateProviderEntries(p, _))
      )
      .map(dynamic => (static ++ dynamic.flatten).sortBy(_.uri))
      .flatMap { all =>
        val uris = all.map(_.uri)
        if uris.distinct.length != uris.length then
          ZIO.fail(
            McpError.internalError(
              s"skills/list: duplicate skill entries for ${uris.diff(uris.distinct).distinct.mkString(", ")}"
            )
          )
        else ZIO.succeed(all)
      }

  /** One page of `skills/list`. */
  def listPage(
      cursor: Option[Cursor],
      context: McpContext
  ): ZIO[R, McpError, (List[Skill], Option[Cursor])] =
    val gen = generation
    listAll(context).flatMap { all =>
      val fp = SkillCursor.fingerprint(Skills.MethodSkillsList, "", gen, all.map(_.uri))
      paginate(Skills.MethodSkillsList, fp, all, cursor, settings.listPageSize)
    }

  /** `skills/get`: any served skill, listed or not; `None` when no skill lives at `uri`. */
  def get(uri: String, context: McpContext): ZIO[R, McpError, Option[Skill]] =
    val g = state.get()
    SkillUri.parse(uri) match
      case Left(_) => ZIO.none
      case Right(parsed) =>
        SkillUri.skillRootOf(parsed).flatMap(root => g.snapshots.get(root.render)) match
          case Some(snap) => ZIO.some(snap.entry)
          case None =>
            g.providers.find(_.covers(uri)) match
              case None => ZIO.none
              case Some(p) =>
                p.get(uri, context)
                  .mapError(providerFailure(p, "get", _))
                  .flatMap {
                    case None => ZIO.none
                    case Some(entry) =>
                      validateProviderEntries(p, List(entry)).map(_.headOption).flatMap {
                        case Some(e) if e.uri != uri =>
                          ZIO.fail(
                            McpError
                              .internalError(s"skills/get: provider returned ${e.uri} for $uri")
                          )
                        case other => ZIO.succeed(other)
                      }
                  }

  /** File content for `resources/read`; `None` when `uri` is not a served file. */
  def readFile(uri: String, context: McpContext): ZIO[R, McpError, Option[SkillFileContent]] =
    val g = state.get()
    g.filesByUri.get(uri) match
      case Some(file) => ZIO.some(SkillFileContent(file.body, Some(file.mimeType)))
      case None =>
        g.providers.find(_.covers(uri)) match
          case None => ZIO.none
          case Some(p) => p.read(uri, context).mapError(providerFailure(p, "read", _))

  /** Direct children of a directory; `None` when `uri` is not a served directory. */
  def readDirectoryAll(uri: String, context: McpContext): ZIO[R, McpError, Option[List[Resource]]] =
    val g = state.get()
    g.directoriesByUri.get(uri) match
      case Some(dir) => ZIO.some(dir.children.toList)
      case None =>
        g.providers.find(_.covers(uri)) match
          case None => ZIO.none
          case Some(p) =>
            p.readDirectory(uri, context)
              .mapError(providerFailure(p, "readDirectory", _))
              .map(_.map(_.sortBy(_.name)))

  /** One page of `resources/directory/read`; `None` when `uri` is not a directory. */
  def readDirectoryPage(
      uri: String,
      cursor: Option[Cursor],
      context: McpContext
  ): ZIO[R, McpError, Option[(List[Resource], Option[Cursor])]] =
    val gen = generation
    readDirectoryAll(uri, context).flatMap {
      case None => ZIO.none
      case Some(children) =>
        val fp = SkillCursor.fingerprint(
          Skills.MethodResourcesDirectoryRead,
          uri,
          gen,
          children.map(_.uri)
        )
        paginate(
          Skills.MethodResourcesDirectoryRead,
          fp,
          children,
          cursor,
          settings.directoryPageSize
        ).map(Some(_))
    }

  private def paginate[A](
      op: String,
      fingerprint: String,
      items: List[A],
      cursor: Option[Cursor],
      pageSize: Int
  ): IO[McpError, (List[A], Option[Cursor])] =
    val offset: Either[McpError, Int] = cursor match
      case None => Right(0)
      case Some(c) => SkillCursor.decode(c.value, op, fingerprint, items.length)
    ZIO.fromEither(offset).map { start =>
      val page = items.slice(start, start + pageSize)
      val end = start + page.length
      val next = if end < items.length then Some(SkillCursor.encode(op, fingerprint, end)) else None
      (page, next)
    }

  private def providerFailure(p: SkillProvider[R], op: String, e: Throwable): McpError =
    e match
      case m: McpError => m
      case other =>
        McpError.internalError(
          s"skill provider for ${p.namespaces.map(_.render).mkString(", ")} failed in $op: ${Option(other.getMessage).getOrElse(other.getClass.getSimpleName)}"
        )

  /** A provider's entries must be valid and inside its namespaces — the server never republishes a
    * malformed entry as if it were its own.
    */
  private def validateProviderEntries(
      p: SkillProvider[R],
      entries: List[Skill]
  ): IO[McpError, List[Skill]] =
    ZIO.foreach(entries) { entry =>
      SkillVerifier.validateEntry(entry, enforceLimits = false) match
        case Left(f) =>
          ZIO.fail(
            McpError.internalError(s"skill provider returned an invalid entry: ${f.message}")
          )
        case Right(()) if !p.covers(entry.uri) =>
          ZIO.fail(
            McpError.internalError(s"skill provider returned ${entry.uri}, outside its namespaces")
          )
        case Right(()) => ZIO.succeed(entry)
    }

  // ---- ResourceSource (ordinary resource routing) ----

  override def owns(uri: String): Boolean =
    val g = state.get()
    g.filesByUri.contains(uri) || g.directoriesByUri.contains(uri) || g.providers.exists(
      _.covers(uri)
    )

  override def ownedUris: Iterable[String] =
    val g = state.get()
    g.filesByUri.keys ++ g.directoriesByUri.keys ++ g.providers.flatMap(
      _.listedResources.map(_.uri)
    )

  override def listDefinitions(): List[ResourceDefinition] =
    val g = state.get()
    val static =
      g.snapshots.values.filter(_.listed).flatMap(_.files.values).map(f => toDefinition(f.resource))
    val dynamic = g.providers.flatMap(_.listedResources).map(toDefinition)
    (static ++ dynamic).toList.distinctBy(_.uri)

  override def definition(uri: String): Option[ResourceDefinition] =
    val g = state.get()
    g.filesByUri
      .get(uri)
      .map(f => toDefinition(f.resource))
      .orElse(g.providers.flatMap(_.listedResources).find(_.uri == uri).map(toDefinition))

  override def read(
      uri: String,
      context: Option[McpContext]
  ): ZIO[R, Throwable, Option[(Option[String], String | Array[Byte])]] =
    readFile(uri, context.getOrElse(new McpContext())).map(_.map(c => (c.mimeType, c.body)))

  private def toDefinition(r: Resource): ResourceDefinition =
    ResourceDefinition(
      uri = r.uri,
      name = Some(r.name),
      description = r.description,
      mimeType = r.mimeType
    )

  // ---- validation of a candidate generation ----

  private def duplicateRoots(skills: List[McpSkill]): Either[SkillError, Unit] =
    val roots = skills.map(_.root.render)
    roots.diff(roots.distinct).headOption match
      case Some(dup) =>
        Left(
          SkillError.ConflictingResource(
            dup,
            dup,
            "the same skill root is published twice in one call"
          )
        )
      case None => Right(())

  private def validate(g: Generation[R]): Either[SkillError, Generation[R]] =
    val snaps = g.snapshots.values.toList
    // Nesting first: an incomplete parent manifest is reported as such, not as a directory shape
    // difference.
    for
      _ <- checkNesting(snaps)
      _ <- checkFileAndDirectoryConsistency(snaps)
      _ <- checkProviders(snaps, g.providers)
      _ <- checkExternal(snaps)
    yield g

  private def conflict(snap: SkillSnapshot, uri: String, reason: String): SkillError =
    SkillError.ConflictingResource(snap.uri, uri, reason)

  private def checkFileAndDirectoryConsistency(
      snaps: List[SkillSnapshot]
  ): Either[SkillError, Unit] =
    val files = scala.collection.mutable.HashMap.empty[String, (SkillSnapshot, PublishedFile)]
    val dirs = scala.collection.mutable.HashMap.empty[String, (SkillSnapshot, PublishedDirectory)]
    snaps
      .foldLeft[Either[SkillError, Unit]](Right(())) {
        case (Left(e), _) => Left(e)
        case (Right(()), snap) =>
          val fileCheck = snap.files.values.foldLeft[Either[SkillError, Unit]](Right(())) {
            case (Left(e), _) => Left(e)
            case (Right(()), f) =>
              files.get(f.uri) match
                case Some((other, existing)) if !existing.sameContentAs(f) =>
                  Left(
                    conflict(
                      snap,
                      f.uri,
                      s"different content than the same file published by ${other.uri}"
                    )
                  )
                case _ =>
                  files.update(f.uri, (snap, f))
                  Right(())
          }
          fileCheck.flatMap { _ =>
            snap.directories.values.foldLeft[Either[SkillError, Unit]](Right(())) {
              case (Left(e), _) => Left(e)
              case (Right(()), d) =>
                // Structural equality: the same children (URI + kind). Resource NAMES may differ —
                // a nested skill names its own SKILL.md by frontmatter, the parent by file name.
                dirs.get(d.uri) match
                  case Some((other, existing)) if shape(existing) != shape(d) =>
                    Left(
                      conflict(
                        snap,
                        d.uri,
                        s"different directory contents than published by ${other.uri}"
                      )
                    )
                  case _ =>
                    dirs.update(d.uri, (snap, d))
                    Right(())
            }
          }
      }
      .flatMap { _ =>
        files.keys.find(dirs.contains) match
          case Some(u) =>
            Left(conflict(files(u)._1, u, s"a file here, but a directory in ${dirs(u)._1.uri}"))
          case None => Right(())
      }

  private def shape(d: PublishedDirectory): Set[(String, Option[String])] =
    d.children.map(c => (c.uri, c.mimeType)).toSet

  /** A skill nested inside another must publish exactly the parent's files below its root. */
  private def checkNesting(snaps: List[SkillSnapshot]): Either[SkillError, Unit] =
    val pairs = for
      parent <- snaps
      child <- snaps
      if child.root.isWithin(parent.root)
    yield (parent, child)
    pairs.foldLeft[Either[SkillError, Unit]](Right(())) {
      case (Left(e), _) => Left(e)
      case (Right(()), (parent, child)) =>
        val parentBelow =
          parent.files.keySet.filter(u => SkillUri.parse(u).exists(_.isWithin(child.root)))
        val childFiles = child.files.keySet
        (parentBelow -- childFiles).headOption match
          case Some(missing) =>
            Left(
              conflict(
                child,
                missing,
                s"listed by the enclosing skill ${parent.uri} but not by the nested skill"
              )
            )
          case None =>
            (childFiles -- parentBelow).headOption match
              case Some(extra) =>
                Left(
                  conflict(
                    parent,
                    extra,
                    s"published by the nested skill ${child.uri} but missing from the enclosing skill's manifest"
                  )
                )
              case None => Right(())
    }

  private def checkProviders(
      snaps: List[SkillSnapshot],
      providers: Vector[SkillProvider[R]]
  ): Either[SkillError, Unit] =
    val namespaces = providers.flatMap(p => p.namespaces.map(ns => (p, ns)))
    val overlapSkill = namespaces.iterator
      .flatMap { case (_, ns) =>
        snaps
          .find(s => s.root == ns || s.root.isWithin(ns) || ns.isWithin(s.root))
          .map(s => (ns, s))
      }
      .nextOption()
    overlapSkill match
      case Some((ns, s)) =>
        Left(conflict(s, ns.render, "a skill provider namespace overlaps this skill"))
      case None =>
        val overlapProvider = namespaces.combinations(2).find {
          case Vector((p1, a), (p2, b)) => (p1 ne p2) && (a == b || a.isWithin(b) || b.isWithin(a))
          case _ => false
        }
        overlapProvider match
          case Some(Vector((_, a), (_, b))) =>
            Left(
              SkillError.ConflictingResource(
                a.render,
                b.render,
                "two skill providers claim overlapping namespaces"
              )
            )
          case _ => Right(())

  private def checkExternal(snaps: List[SkillSnapshot]): Either[SkillError, Unit] =
    snaps.foldLeft[Either[SkillError, Unit]](Right(())) {
      case (Left(e), _) => Left(e)
      case (Right(()), snap) =>
        (snap.files.keys ++ snap.directories.keys).iterator
          .map(u => externalConflict(u).map(reason => conflict(snap, u, reason)))
          .collectFirst { case Some(e) => e }
          .toLeft(())
    }

object SkillRegistry:

  private final case class Generation[R](
      generation: Long,
      snapshots: SortedMap[String, SkillSnapshot],
      providers: Vector[SkillProvider[R]]
  ):

    // Union indexes over identical-content duplicates (validated before the generation is
    // installed), so lookups are one hash probe. Snapshots are visited in root order, so for a
    // directory shared by a parent and a nested skill the nested skill's view (its SKILL.md named by
    // frontmatter) is the one served.
    lazy val filesByUri: Map[String, PublishedFile] =
      snapshots.values.iterator.flatMap(_.files.iterator).toMap

    lazy val directoriesByUri: Map[String, PublishedDirectory] =
      snapshots.values.iterator.flatMap(_.directories.iterator).toMap

  /** @param externalConflict
    *   consulted for every file and directory URI at publication; returns the reason the URI is
    *   already taken outside the registry (a static resource, a matching template), if any.
    */
  def make[R](
      settings: SkillSettings,
      sha256: Array[Byte] => Array[Byte],
      externalConflict: String => Option[String] = _ => None
  ): SkillRegistry[R] =
    new SkillRegistry[R](settings, sha256, externalConflict)
