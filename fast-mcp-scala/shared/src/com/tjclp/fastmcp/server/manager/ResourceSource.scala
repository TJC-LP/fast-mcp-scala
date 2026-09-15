package com.tjclp.fastmcp.server.manager

import zio.*

import com.tjclp.fastmcp.core.ResourceDefinition
import com.tjclp.fastmcp.server.McpContext

/** A read-only tree of resources mounted into the [[ResourceManager]] beside the static map and the
  * templates — the seam through which the Skills registry serves skill files as ordinary resources
  * (`resources/list`, `resources/read`, subscriptions, middleware, hooks all unchanged).
  *
  * Resolution order in the manager is static resources → sources → templates, so a source's URIs
  * can never be shadowed by a fallback template; and the manager refuses to register a static
  * resource or a template that collides with a URI a source owns.
  */
trait ResourceSource[R]:

  /** Whether this source serves `uri` (as a file or as a directory). Synchronous and cheap. */
  def owns(uri: String): Boolean

  /** Every URI this source can name statically (files and directories) — used to detect templates
    * that would match a source's URIs. Dynamic providers contribute only what they list.
    */
  def ownedUris: Iterable[String]

  /** Definitions to include in `resources/list`. */
  def listDefinitions(): List[ResourceDefinition]

  /** Static metadata for a URI, when known before reading. */
  def definition(uri: String): Option[ResourceDefinition]

  /** Read `uri`: `None` when this source has no such file (a directory URI reads as `None` too).
    * The `Option[String]` is the mime type of the served body.
    */
  def read(
      uri: String,
      context: Option[McpContext]
  ): ZIO[R, Throwable, Option[(Option[String], String | Array[Byte])]]
