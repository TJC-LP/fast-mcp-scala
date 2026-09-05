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
  * Design: argument values flow as zio-json [[Json]] AST nodes (never Scala `null`). The default
  * decoders ([[McpDecoders]] and the macro-generated per-parameter decoders) go through
  * [[DefaultDecodeContext.decodeRaw]], which feeds the node into zio-json's guarded `fromJsonAST`
  * (a `StackOverflowError` inside it is caught by zio-json and surfaces as a decode `Left`; the
  * frame-level depth bound keeps the node small anyway). `writeValueAsString` remains for custom
  * contexts/decoders and, on the JVM, converts a `StackOverflowError` into an
  * `IllegalArgumentException` (-32602) instead of letting a fatal error reach the fiber.
  *
  * @param maxDepth
  *   nesting bound for JSON embedded in STRING arguments parsed via [[parseJsonObject]] /
  *   [[parseJsonArray]] — invisible to the frame-level limits, so bounded here
  * @param maxObjectFields
  *   per-object member bound for the same embedded JSON
  */
final class DefaultDecodeContext(
    val maxDepth: Int = JsonLimits.DefaultMaxDepth,
    val maxObjectFields: Int = JsonLimits.DefaultMaxObjectFields
) extends McpDecodeContext:

  /** Best-effort cast — callers are almost always other decoders, not user code. */
  override def convertValue[T: ClassTag](name: String, rawValue: Any): T =
    rawValue match
      case t: T => t
      case other =>
        throw new RuntimeException(
          s"Failed to convert value for parameter '$name': $other"
        )

  override def parseJsonArray(name: String, rawJson: String): List[Any] =
    parseBounded(name, rawJson, "array") match
      case Json.Arr(elems) => elems.toList.map(DefaultDecodeContext.fromJsonAst)
      case other =>
        throw new RuntimeException(
          s"Expected JSON array for '$name', got: ${JsonLimits.typeName(other)}"
        )

  override def parseJsonObject(name: String, rawJson: String): Map[String, Any] =
    parseBounded(name, rawJson, "object") match
      case Json.Obj(fields) =>
        fields.iterator.map((k, v) => k -> DefaultDecodeContext.fromJsonAst(v)).toMap
      case other =>
        throw new RuntimeException(
          s"Expected JSON object for '$name', got: ${JsonLimits.typeName(other)}"
        )

  /** Bound the raw text BEFORE zio-json's recursive parser sees it (`JsonLimits.preScan`, linear,
    * never recurses — the same guard the transport choke point uses, so a multi-MB `[[[[…` string
    * argument cannot drive the parser into a stack overflow, which Scala Native does not deliver as
    * a catchable error), then parse and re-check the AST iteratively before any recursive walk over
    * it — `fromJsonAst` recurses per level. A violation is bad input: `IllegalArgumentException`
    * maps to -32602 via `McpError.fromThrowable`.
    */
  private def parseBounded(name: String, rawJson: String, kind: String): Json =
    def reject(violation: JsonLimits.Violation): Nothing =
      throw new IllegalArgumentException(s"JSON $kind for '$name' rejected: ${violation.message}")
    JsonLimits.preScan(rawJson, Int.MaxValue, maxDepth, maxObjectFields).foreach(reject)
    rawJson.fromJson[Json] match
      case Right(json) =>
        JsonLimits.validate(json, maxDepth, maxObjectFields).foreach(reject)
        json
      case Left(err) =>
        throw new RuntimeException(s"Failed to parse JSON $kind for '$name': $err")

  override def writeValueAsString(value: Any): String =
    DefaultDecodeContext.guardDepth(DefaultDecodeContext.toJsonAst(value).toJson)

object DefaultDecodeContext:

  lazy val default: DefaultDecodeContext = new DefaultDecodeContext()

  /** JVM: convert a `StackOverflowError` from a recursive walk over an argument value into -32602
    * material instead of letting it reach `ZIO.attempt` (where a `VirtualMachineError` is fatal and
    * terminates the process). This catch is a JVM-only backstop: Scala.js throws a `RangeError`
    * (`js.JavaScriptException`) that this clause never matches, and Scala Native does not reliably
    * deliver a stack overflow as a catchable error at all — on both, the depth bounds (frame-level
    * `limits.maxDepth` and the embedded-JSON `maxDepth` here) are the protection.
    */
  private[fastmcp] inline def guardDepth[A](inline body: A): A =
    try body
    catch
      case _: StackOverflowError =>
        throw new IllegalArgumentException("argument value is nested too deeply to encode")

  /** Decode a raw argument through zio-json's guarded `fromJsonAST`. Decoders that override
    * `unsafeFromJsonAST` (derived case classes, primitives, `Option`, `Json`, collections) decode
    * straight from the node with no intermediate string; a decoder that keeps zio-json's default
    * re-encodes the node INSIDE `fromJsonAST`, which catches a `StackOverflowError` (JVM) and whose
    * input is already bounded by the frame-level `limits.maxDepth`. `toJsonAst` is the identity for
    * wire `Json` nodes and a shallow `Json.Obj` for the typed-contract `Map[String, Any]`. Custom
    * [[McpDecodeContext]] implementations keep the legacy `writeValueAsString` + `decodeJson` path.
    *
    * PUBLIC and stable: it is the target of the inline `derivedZioJsonDecoder` given and of the
    * macro-generated per-parameter decoders, so it is spliced into user compilation units.
    */
  def decodeRaw[T](
      name: String,
      rawValue: Any,
      context: McpDecodeContext,
      decoder: JsonDecoder[T]
  ): T =
    context match
      case _: DefaultDecodeContext =>
        val ast = guardDepth(toJsonAst(rawValue))
        decoder.fromJsonAST(ast) match
          case Right(value) => value
          case Left(err) =>
            throw new RuntimeException(
              s"Failed to decode parameter '$name' from JSON: $err. Value: ${preview(ast)}"
            )
      case other =>
        val json = other.writeValueAsString(rawValue)
        decoder.decodeJson(json) match
          case Right(value) => value
          case Left(err) =>
            throw new RuntimeException(
              s"Failed to decode parameter '$name' from JSON: $err. Value: $json"
            )

  /** Render a failing value for the error message — guarded and truncated, so an error body never
    * amplifies the offending input.
    */
  private def preview(ast: Json): String =
    try
      val s = ast.toJson
      if s.length > 512 then s.take(512) + "…" else s
    catch case _: StackOverflowError => "<too deep>"

  /** Coerce an arbitrary argument value into a [[Json]] AST. Handles the shapes that reach the
    * decode path: `Json` nodes (passthrough), shallow `Map`/`Iterable` structures produced by
    * [[parseJsonObject]]/[[parseJsonArray]], primitives, byte arrays (base64), and `Option`. Falls
    * back to a string rendering for anything else (rare; matches the old JS behavior).
    */
  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  def toJsonAst(value: Any): Json =
    value match
      // Raw null can reach us from direct in-process calls and JS interop (wire null arrives
      // as None via fromJsonAst); rendering it as Json.Null instead of NPE-ing on toString
      case null => Json.Null
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
