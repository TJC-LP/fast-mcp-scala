package com.tjclp.fastmcp.server.skills

import zio.*

import com.tjclp.fastmcp.core.skills.SkillUri
import com.tjclp.fastmcp.core.wire.{Resource, Skill}
import com.tjclp.fastmcp.server.McpContext

/** The body and metadata of one skill file served through `resources/read`. `body` is a `String`
  * for text (emitted as `text`) or an `Array[Byte]` for binary (emitted as a base64 `blob`).
  */
final case class SkillFileContent(body: String | Array[Byte], mimeType: Option[String] = None)

/** A source of skills the server serves alongside its in-memory catalog: generated, remote, or
  * authorization-scoped. Every method receives the request's [[McpContext]] so a provider can scope
  * what it returns to the caller (client capabilities, request `_meta`, transport identity).
  *
  * Contract:
  *   - [[namespaces]] are the roots this provider owns (a skill root such as
  *     `skill://reports/daily` or a wider prefix such as `skill://reports`). Requests for URIs at
  *     or below a namespace are routed here; the registry refuses a provider whose namespace
  *     overlaps a published skill or another provider, so ownership is never ambiguous.
  *   - [[list]] MAY return a subset (or nothing); [[get]] MUST answer for every skill the provider
  *     serves, listed or not. Listing and getting are metadata operations: they MUST NOT read
  *     supporting files.
  *   - Entries whose content is generated such that stable digests cannot be published carry
  *     `SkillResources.Dynamic`; a provider MUST NOT advertise digests it cannot honour on read.
  *     The `frontmatter` of an entry MUST equal the frontmatter of the `SKILL.md` [[read]] returns.
  *   - [[readDirectory]] returns the direct children of a directory (files with ordinary metadata,
  *     subdirectories with `mimeType: inode/directory`), `None` when the URI is not a directory it
  *     serves. A provider that cannot honour it overrides [[supportsDirectoryRead]] with `false`,
  *     which keeps the server from advertising `directoryRead: true` at all.
  *   - [[listedResources]] is the static metadata of files that should appear in `resources/list`;
  *     dynamic providers leave it empty.
  */
trait SkillProvider[R]:

  def namespaces: List[SkillUri]

  def supportsDirectoryRead: Boolean = true

  def list(context: McpContext): ZIO[R, Throwable, List[Skill]]

  def get(uri: String, context: McpContext): ZIO[R, Throwable, Option[Skill]]

  def read(uri: String, context: McpContext): ZIO[R, Throwable, Option[SkillFileContent]]

  def readDirectory(uri: String, context: McpContext): ZIO[R, Throwable, Option[List[Resource]]]

  def listedResources: List[Resource] = Nil

  /** Whether `uri` falls at or under one of this provider's namespaces. */
  final def covers(uri: String): Boolean =
    SkillUri.parse(uri).fold(_ => false, u => namespaces.exists(ns => u == ns || u.isWithin(ns)))
