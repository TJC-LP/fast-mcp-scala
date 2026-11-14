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
  * Numbers get a second, independent check here: [[DefaultDecodeContext.decodeRaw]] refuses any
  * number outside the window `maxNumberDigits` / `maxNumberExponent` imply before a decoder sees
  * it. zio-json's `BigInt` / `BigInteger` decoders expand a `Json.Num` to an integer with no cap of
  * their own, and its `BigDecimal` decoders hand the raw scale to the handler, so without this a
  * value that never crossed the frame check as a JSON number (an in-process call, a raw Scala
  * `BigDecimal`, a prompt or resource-template argument, a number written as a string) could cost
  * time and memory proportional to `10^exponent`.
  *
  * @param maxDepth
  *   nesting bound for JSON embedded in STRING arguments parsed via [[parseJsonObject]] /
  *   [[parseJsonArray]] — invisible to the frame-level limits, so bounded here
  * @param maxObjectFields
  *   per-object member bound for the same embedded JSON
  * @param maxArrayElements
  *   per-array element bound for the same embedded JSON
  * @param maxValues
  *   total value count bound for one embedded JSON document
  * @param maxNumberDigits
  *   significand digit bound for numbers in embedded JSON and in every decoded argument (1 to
  *   `LimitSettings.MaxSupportedNumberDigits`)
  * @param maxNumberExponent
  *   exponent bound for the same numbers (1 to `LimitSettings.MaxSupportedNumberExponent`). On an
  *   argument value, where the text of a number is gone, the two bound its magnitude together: no
  *   digit outside `10^±(maxNumberDigits + maxNumberExponent)`
  */
final class DefaultDecodeContext(
    val maxDepth: Int = JsonLimits.DefaultMaxDepth,
    val maxObjectFields: Int = JsonLimits.DefaultMaxObjectFields,
    val maxArrayElements: Int = JsonLimits.DefaultMaxArrayElements,
    val maxValues: Int = JsonLimits.DefaultMaxValues,
    val maxNumberDigits: Int = JsonLimits.DefaultMaxNumberDigits,
    val maxNumberExponent: Int = JsonLimits.DefaultMaxNumberExponent
) extends McpDecodeContext:

  private[fastmcp] val bounds: JsonLimits.Bounds = JsonLimits.Bounds(
    maxDepth,
    maxObjectFields,
    maxArrayElements,
    maxValues,
    maxNumberDigits,
    maxNumberExponent
  )

  // Binary compatibility with 1.0.x: the `(maxDepth, maxObjectFields)` constructor. The bounds
  // added since take their defaults.
  private[fastmcp] def this(maxDepth: Int, maxObjectFields: Int) =
    this(maxDepth, maxObjectFields, maxArrayElements = JsonLimits.DefaultMaxArrayElements)

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

  /** Bound the raw text BEFORE zio-json's recursive parser sees it — depth, object width, array
    * length, value count and number digits / exponent, the same bounds as a frame
    * (`JsonLimits.preScan`, linear, never recurses — the same guard the transport choke point uses,
    * so a multi-MB `[[[[…` string argument cannot drive the parser into a stack overflow, which
    * Scala Native does not deliver as a catchable error), then parse and re-check the AST
    * iteratively before any recursive walk over it — `fromJsonAst` recurses per level. A violation
    * is bad input: `IllegalArgumentException` maps to -32602 via `McpError.fromThrowable`.
    */
  private def parseBounded(name: String, rawJson: String, kind: String): Json =
    def reject(violation: JsonLimits.Violation): Nothing =
      throw new IllegalArgumentException(s"JSON $kind for '$name' rejected: ${violation.message}")
    JsonLimits.preScan(rawJson, Int.MaxValue, bounds).foreach(reject)
    rawJson.fromJson[Json] match
      case Right(json) =>
        JsonLimits.validate(json, bounds).foreach(reject)
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
    * Every number in the value is checked first against the window the context's `maxNumberDigits`
    * / `maxNumberExponent` imply — at most `maxNumberDigits` significant digits, none of them
    * outside `10^±(maxNumberDigits + maxNumberExponent)` — and refused outside it
    * (`IllegalArgumentException`, -32602): one type test for a scalar argument, one iterative walk
    * for a container. That is what bounds decoding into `BigInt`, `BigInteger` and `BigDecimal` by
    * the size of the request instead of by the magnitude written in it, including for values that
    * did not come through the frame check. A JSON string written as a number outside the bounds is
    * not refused on sight (it may be bound for a `String`); the decoded value is checked instead,
    * and refused if a `BigDecimal` outside the window came out of it — inside case classes, tuples,
    * `Option`, `Either`, arrays and standard collections; a class of its own that a custom decoder
    * builds around a `BigDecimal` is not opened. The legacy path of a custom context is not checked
    * here: the server itself always decodes with a [[DefaultDecodeContext]].
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
      case default: DefaultDecodeContext =>
        val ast = guardDepth(toJsonAst(rawValue))
        // Before ANY decoder: zio-json's BigInt / BigInteger decoders expand the number, and its
        // BigDecimal decoders pass the raw scale on. Bad input, so -32602.
        val scan = JsonLimits.scanNumbers(ast, default.bounds)
        scan.violation.foreach(rejectNumber(name, _))
        decoder.fromJsonAST(ast) match
          case Right(value) =>
            // A JSON string written as an out-of-range number is fine as a String and refused
            // only if it became a number. Never taken for an ordinary request.
            if scan.numericString then
              JsonLimits
                .decodedNumberViolation(value, default.bounds)
                .foreach(rejectNumber(name, _))
            value
          case Left(err) => throw new ArgumentDecodeFailure(name, err, ast)
      case other =>
        // A custom context's encoder is user code; a JVM StackOverflowError there still becomes
        // an IllegalArgumentException (-32602) instead of a fatal Error.
        val json = guardDepth(other.writeValueAsString(rawValue))
        decoder.decodeJson(json) match
          case Right(value) => value
          case Left(err) =>
            throw new RuntimeException(
              s"Failed to decode parameter '$name' from JSON: $err. Value: $json"
            )

  /** The message of a failed decode: the parameter, zio-json's reason, a preview of the value. */
  private[codec] def decodeFailureMessage(name: String, reason: String, value: Option[Json]) =
    s"Failed to decode parameter '$name' from JSON: $reason" +
      value.fold("")(v => s". Value: ${preview(v)}")

  private def rejectNumber(name: String, violation: JsonLimits.Violation): Nothing =
    throw new IllegalArgumentException(s"Parameter '$name' rejected: ${violation.message}")

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

/** [[DefaultDecodeContext.decodeRaw]] could not decode `value` as the parameter `parameter`;
  * `reason` is zio-json's error. A `RuntimeException` with the message decode failures have always
  * had, so nothing that catches or maps one changes. It carries its parts so that the
  * typed-contract path can say which FIELD failed ([[forField]]).
  */
private[fastmcp] final class ArgumentDecodeFailure(
    val parameter: String,
    val reason: String,
    val value: Json
) extends RuntimeException(
      DefaultDecodeContext.decodeFailureMessage(parameter, reason, Some(value))
    ):

  /** The same failure, named after the member of `value` that zio-json's error path starts with.
    *
    * A typed contract decodes the whole `arguments` object as one value, under the name of the
    * tool, prompt or resource template. zio-json puts the path to the failing value in front of its
    * message: `.id(Invalid job id 'nope')`, `.filter.ids[2](expected a number)`. The first step of
    * that path is the argument the client got wrong, which is what the annotation path names:
    * `Failed to decode parameter 'id' from JSON: (Invalid job id 'nope'). Value: "nope"`.
    *
    * `None` when the error names no member (the value is not an object, say): the failure is about
    * the arguments as a whole and keeps its message.
    */
  def forField: Option[RuntimeException] =
    if !reason.startsWith(".") then None
    else
      val end = reason.indexWhere(c => c == '.' || c == '[' || c == '(', 1)
      val field = if end < 0 then reason.drop(1) else reason.substring(1, end)
      val rest = if end < 0 then "" else reason.substring(end)
      val member = value match
        case Json.Obj(fields) => fields.collectFirst { case (key, v) if key == field => v }
        case _ => None
      Option.when(field.nonEmpty)(
        new RuntimeException(DefaultDecodeContext.decodeFailureMessage(field, rest, member))
      )
