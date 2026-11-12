package com.tjclp.fastmcp.server.skills

import java.nio.charset.StandardCharsets
import java.util.Base64

import com.tjclp.fastmcp.core.{Cursor, Fnv1a}
import com.tjclp.fastmcp.jsonrpc.McpError

/** Opaque, stateless pagination cursors for `skills/list` and `resources/directory/read`.
  *
  * A cursor is `fms1.` + base64url(`<op>:<fingerprint>:<offset>`). The fingerprint hashes the
  * operation, the scope (the directory URI, or nothing for the catalog), the publication generation
  * and the ordered item identities of the listing the caller is paging through — so a cursor is
  * rejected as stale when the listing changed (a republish, a dynamic provider producing a
  * different set, a differently-authorized caller seeing a different catalog), and as foreign when
  * it belongs to another operation or directory. Nothing is retained server-side.
  */
private[fastmcp] object SkillCursor:

  private val Prefix = "fms1."

  def fingerprint(op: String, scope: String, generation: Long, items: Iterable[String]): String =
    Fnv1a.hex64(
      (op :: scope :: generation.toString :: items.toList).mkString("\u0000")
    )

  def encode(op: String, fingerprint: String, offset: Int): Cursor =
    val payload = s"$op:$fingerprint:$offset".getBytes(StandardCharsets.UTF_8)
    Cursor(Prefix + Base64.getUrlEncoder.withoutPadding.encodeToString(payload))

  /** Decode and validate against the current listing; `Right(offset)`. */
  def decode(raw: String, op: String, fingerprint: String, itemCount: Int): Either[McpError, Int] =
    val invalid = McpError.invalidParams(s"$op: invalid cursor")
    if !raw.startsWith(Prefix) then Left(invalid)
    else
      val decoded =
        try
          Right(
            new String(
              Base64.getUrlDecoder.decode(raw.substring(Prefix.length)),
              StandardCharsets.UTF_8
            )
          )
        catch case _: IllegalArgumentException => Left(invalid)
      decoded.flatMap { text =>
        text.split(":", -1) match
          case Array(cursorOp, cursorFp, offsetText) =>
            offsetText.toIntOption match
              case None => Left(invalid)
              case Some(offset) if cursorOp != op =>
                Left(McpError.invalidParams(s"$op: cursor was issued by $cursorOp"))
              case Some(_) if cursorFp != fingerprint =>
                Left(McpError.invalidParams(s"$op: cursor is stale or belongs to another listing"))
              case Some(offset) if offset <= 0 || offset > itemCount =>
                Left(McpError.invalidParams(s"$op: cursor offset out of range"))
              case Some(offset) => Right(offset)
          case _ => Left(invalid)
      }
