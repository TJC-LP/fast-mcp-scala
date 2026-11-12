package com.tjclp.fastmcp
package server.manager

import scala.annotation.tailrec

import zio.Cause
import zio.json.ast.Json

import jsonrpc.{McpError, McpErrorCarrier}

/** A conservative estimate of the heap a value keeps reachable, in bytes. It is what the task store
  * charges against `TaskSettings.maxRequestBytes`, `maxResultBytes`, `maxBytesPerOwner` and
  * `maxBytesTotal`.
  *
  * It is an estimate, not a measurement: every platform lays objects out differently, so the walk
  * counts what drives the size — two bytes per string character (UTF-16), a fixed overhead per JSON
  * node, object member and array slot, and the magnitude of each number. A document made of many
  * small nodes is therefore charged several times its wire size, which is what it costs on the
  * heap; a document that is one long string is charged about twice its length.
  *
  * Only data is charged. What every entry costs regardless of its content — ids, timestamps, the
  * result promise, a failed task's stack trace — is sized by the code, not by a client, and is
  * bounded by the stored-entry caps.
  *
  * Linear in the number of nodes, iterative (no recursion to overflow on a deep tree), and it stops
  * walking as soon as the running total passes `limit`.
  */
private[fastmcp] object RetainedBytes:

  /** A value the walk cannot size (anything that is not JSON, text or bytes). */
  val Opaque: Long = 256L

  /** One entry of a `Map` built over a JSON object's members (keys and values are shared). */
  val MapEntry: Long = 64L

  private val Node = 16L
  private val Text = 40L
  private val Number = 64L
  private val Container = 32L
  private val Slot = 8L
  private val Member = 32L

  def ofText(text: String): Long = Text + 2L * text.length

  /** Estimate `json`, giving up (with a total above `limit`) once the total passes `limit`. */
  def ofJson(json: Json, limit: Long = Long.MaxValue): Long =
    @tailrec def loop(stack: List[Json], acc: Long): Long =
      if acc > limit then acc
      else
        stack match
          case Nil => acc
          case head :: tail =>
            head match
              case Json.Str(value) => loop(tail, acc + Node + ofText(value))
              case Json.Num(value) =>
                loop(tail, acc + Node + Number + value.unscaledValue.bitLength / 8)
              case Json.Arr(elements) =>
                loop(
                  elements.foldLeft(tail)((rest, element) => element :: rest),
                  acc + Node + Container + Slot * elements.size
                )
              case Json.Obj(fields) =>
                val members = fields.foldLeft(0L)((sum, field) => sum + Member + ofText(field._1))
                loop(
                  fields.foldLeft(tail)((rest, field) => field._2 :: rest),
                  acc + Node + Container + members
                )
              case _ => loop(tail, acc + Node)
    loop(List(json), 0L)

  /** Estimate an arbitrary task result. Task results produced by the router are JSON; anything else
    * a direct `TaskManager.create` caller returns is charged a flat [[Opaque]].
    */
  def of(value: Any, limit: Long = Long.MaxValue): Long =
    value match
      case json: Json => ofJson(json, limit)
      case text: String => ofText(text)
      case bytes: Array[Byte] => Text + bytes.length
      case _ => Opaque

  /** Estimate what a stored failure keeps: its messages and any JSON error data. */
  def ofCause(cause: Cause[Throwable], limit: Long = Long.MaxValue): Long =
    (cause.failures ++ cause.defects).foldLeft(0L) { (acc, error) =>
      if acc > limit then acc else acc + ofThrowable(error, limit)
    }

  private def ofThrowable(error: Throwable, limit: Long): Long =
    val message = Option(error.getMessage).fold(0L)(ofText)
    val data = error match
      case mcp: McpError => mcp.data.fold(0L)(ofJson(_, limit))
      case carrier: McpErrorCarrier => carrier.toMcpError.data.fold(0L)(ofJson(_, limit))
      case _ => 0L
    message + data
