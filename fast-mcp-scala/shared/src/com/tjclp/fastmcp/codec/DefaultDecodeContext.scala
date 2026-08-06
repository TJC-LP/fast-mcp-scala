package com.tjclp.fastmcp
package codec

import scala.reflect.ClassTag

import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.McpDecodeContext

/** The single, platform-neutral [[McpDecodeContext]] for the native core.
  *
  * Replaces BOTH the deleted JVM `JacksonConversionContext` and the JS `JsMcpDecodeContext` with
  * one zio-json implementation that compiles on JVM and Scala.js alike — the convergence the native
  * rewrite is after ("the SDK is identical regardless of platform").
  *
  * Design: argument values flow as zio-json [[Json]] AST nodes (never Scala `null`), so
  * `writeValueAsString` is just `.toJson` and parsing is just `fromJson`. Decoders re-serialize a
  * shallow `Map[String, Json]` and let zio-json's derived `JsonDecoder` produce the typed value —
  * no bespoke per-field reflection.
  */
final class DefaultDecodeContext extends McpDecodeContext:

  /** Best-effort cast — callers are almost always other decoders, not user code. */
  override def convertValue[T: ClassTag](name: String, rawValue: Any): T =
    rawValue match
      case t: T => t
      case other =>
        throw new RuntimeException(
          s"Failed to convert value for parameter '$name': $other"
        )

  override def parseJsonArray(name: String, rawJson: String): List[Any] =
    rawJson.fromJson[Json] match
      case Right(Json.Arr(elems)) => elems.toList.map(DefaultDecodeContext.fromJsonAst)
      case Right(other) =>
        throw new RuntimeException(s"Expected JSON array for '$name', got: $other")
      case Left(err) =>
        throw new RuntimeException(s"Failed to parse JSON array for '$name': $err")

  override def parseJsonObject(name: String, rawJson: String): Map[String, Any] =
    rawJson.fromJson[Json] match
      case Right(Json.Obj(fields)) =>
        fields.iterator.map((k, v) => k -> DefaultDecodeContext.fromJsonAst(v)).toMap
      case Right(other) =>
        throw new RuntimeException(s"Expected JSON object for '$name', got: $other")
      case Left(err) =>
        throw new RuntimeException(s"Failed to parse JSON object for '$name': $err")

  override def writeValueAsString(value: Any): String =
    DefaultDecodeContext.toJsonAst(value).toJson

object DefaultDecodeContext:

  lazy val default: DefaultDecodeContext = new DefaultDecodeContext

  /** Coerce an arbitrary argument value into a [[Json]] AST. Handles the shapes that reach the
    * decode path: `Json` nodes (passthrough), shallow `Map`/`Iterable` structures produced by
    * [[parseJsonObject]]/[[parseJsonArray]], primitives, byte arrays (base64), and `Option`. Falls
    * back to a string rendering for anything else (rare; matches the old JS behavior).
    */
  def toJsonAst(value: Any): Json =
    value match
      case j: Json => j
      case s: String => Json.Str(s)
      case b: Boolean => Json.Bool(b)
      case i: Int => Json.Num(BigDecimal(i))
      case l: Long => Json.Num(BigDecimal(l))
      case d: Double => Json.Num(BigDecimal(d))
      case f: Float => Json.Num(BigDecimal(f.toDouble))
      case bd: BigDecimal => Json.Num(bd)
      case bytes: Array[Byte] => Json.Str(java.util.Base64.getEncoder.encodeToString(bytes))
      case opt: Option[?] => opt.map(toJsonAst).getOrElse(Json.Null)
      case m: scala.collection.Map[?, ?] =>
        Json.Obj(m.iterator.map { case (k, v) => k.toString -> toJsonAst(v) }.toSeq*)
      case it: Iterable[?] =>
        Json.Arr(it.iterator.map(toJsonAst).toSeq*)
      case p: Product if p.productArity > 0 =>
        // A raw case-class instance passed straight through (e.g. a direct in-process tool call).
        // Reflect its fields via Product — cross-platform, no per-type encoder — omitting `None`
        // so optional fields stay absent (absent ≠ null) for the re-decode.
        val fields = p.productElementNames
          .zip(p.productIterator)
          .collect { case (k, v) if v != None => k -> toJsonAst(v) }
          .toSeq
        Json.Obj(fields*)
      case other => Json.Str(other.toString)

  /** Inverse of [[toJsonAst]]: unwrap a Json AST into native Scala values so custom decoders that
    * call [[parseJsonObject]]/[[parseJsonArray]] observe plain `String`/`Int`/`Long`/`Double`/
    * `Boolean`/`List`/`Map` values (matching the old Jackson context). JSON `null` becomes `None`
    * (the `Null` wart forbids a bare `null`).
    */
  def fromJsonAst(j: Json): Any =
    j match
      case Json.Str(s) => s
      case Json.Bool(b) => b
      case Json.Num(n) =>
        val bd = scala.math.BigDecimal(n)
        if bd.isValidInt then bd.toInt
        else if bd.isValidLong then bd.toLong
        else bd.toDouble
      case Json.Null => None
      case Json.Arr(elems) => elems.toList.map(fromJsonAst)
      case Json.Obj(fields) =>
        fields.iterator.map((k, v) => k -> fromJsonAst(v)).toMap
