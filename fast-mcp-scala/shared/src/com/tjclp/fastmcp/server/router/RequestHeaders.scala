package com.tjclp.fastmcp
package server.router

import zio.*

/** The allow-listed HTTP request headers of the request being dispatched
  * (`McpServerSettings.contextHeaders`), carried on the dispatching fiber so the built-in handlers
  * can snapshot them into the [[com.tjclp.fastmcp.server.McpContext]] they hand to user code.
  *
  * One process-wide `FiberRef`, set per request by the HTTP transports and never by stdio:
  *   - it is per REQUEST, not per session — a forwarded credential rotates during a legacy
  *     session's life, and a 2026-07-28 request has no session to capture it on;
  *   - fibers forked by the dispatch (the router's handler fiber, a task) inherit the value they
  *     were forked with, so a task keeps the headers of the request that created it;
  *   - with an empty allow-list the transports do not touch it at all.
  *
  * The values can be credentials: nothing here, and nothing that reads it, logs them.
  */
private[fastmcp] object RequestHeaders:

  private val ref: FiberRef[Map[String, String]] =
    Unsafe.unsafe(implicit u => FiberRef.unsafe.make(Map.empty[String, String]))

  /** Lower-cased, trimmed, non-empty header names — the form the allow-list is matched in. */
  def normalise(names: Set[String]): Set[String] =
    names.map(normaliseName).filter(_.nonEmpty)

  /** HTTP header names are ASCII tokens; lower-case them without consulting a locale. */
  def normaliseName(name: String): String =
    name.trim.map(c => if c >= 'A' && c <= 'Z' then (c + 32).toChar else c)

  /** The allow-listed headers present on a request, keyed by lower-cased name. `allow` must already
    * be [[normalise]]d.
    */
  def capture(allow: Set[String], lookup: String => Option[String]): Map[String, String] =
    allow.foldLeft(Map.empty[String, String]) { (acc, name) =>
      lookup(name).fold(acc)(value => acc.updated(name, value))
    }

  /** Run `zio` (and everything it forks) with the request's allow-listed headers bound. */
  def scoped[R, E, A](allow: Set[String], lookup: String => Option[String])(
      zio: ZIO[R, E, A]
  ): ZIO[R, E, A] =
    if allow.isEmpty then zio else ref.locally(capture(allow, lookup))(zio)

  /** The headers bound to the current fiber; empty outside an HTTP request. */
  val current: UIO[Map[String, String]] = ref.get
