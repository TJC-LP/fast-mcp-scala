package com.tjclp.fastmcp
package codec

import scala.collection.mutable

import zio.json.ast.Json

/** Structural bounds on client-supplied JSON: frame length, nesting depth, per-object member count,
  * per-array element count, total value count, and the magnitude of number literals. Pure and
  * platform-neutral (JVM, Scala.js, Scala Native) — depends only on zio-json's [[Json]] AST. Lives
  * in `codec/` so both [[DefaultDecodeContext]] (embedded JSON strings, argument decoding) and the
  * transport choke point (`MessageLoop.parseFrame`) can use it.
  *
  * Two layers: [[preScan]] is a linear, near-allocation-free pass over the raw text that rejects a
  * violating frame BEFORE the parser allocates a single node (so a 100 000-deep `[[[[…`, a 2^17-key
  * object, a megabyte of `1,1,1,…` or a `1e999999999` costs one character scan); [[validate]] is
  * the authoritative, iterative re-check on the parsed AST. Neither recurses, so neither can
  * overflow the stack on any input, and neither does work proportional to the magnitude of a
  * number.
  */
private[fastmcp] object JsonLimits:

  /** Hard ceiling for any configured depth bound: the stack safety of every later recursive walk
    * over client JSON (zio-json encode, `equals`, `toString`, AST unwrapping) depends on it.
    * `server.LimitSettings.MaxSupportedDepth` re-exports it; it lives here so `codec` never depends
    * on `server`.
    */
  val MaxSupportedDepth: Int = 256

  /** Hard ceilings for the two number bounds. A number inside the bounds expands to an integer of
    * at most `maxNumberDigits + maxNumberExponent` decimal digits, so these keep the worst case of
    * any later `BigInteger` / `BigDecimal` arithmetic on a client number at a few kilobytes
    * whatever the configuration. Re-exported by `server.LimitSettings`.
    */
  val MaxSupportedNumberDigits: Int = 4096
  val MaxSupportedNumberExponent: Int = 4096

  /** Defaults shared by `server.LimitSettings` (frame level) and [[DefaultDecodeContext]] (JSON
    * embedded in string arguments, argument decoding).
    */
  val DefaultMaxDepth: Int = 64
  val DefaultMaxObjectFields: Int = 1024
  val DefaultMaxArrayElements: Int = 4096
  val DefaultMaxValues: Int = 65536
  val DefaultMaxNumberDigits: Int = 512
  val DefaultMaxNumberExponent: Int = 512

  /** One immutable set of JSON bounds, built once per `LimitSettings` / [[DefaultDecodeContext]] so
    * the per-frame path passes a single reference. See `server.LimitSettings` for what each field
    * means. The number bounds are range-checked here as well as there, because the cost of every
    * later expansion of a client number depends on them; `maxDepth` / `maxObjectFields` keep the
    * (unchecked) contract [[DefaultDecodeContext]]'s public constructor always had.
    */
  final case class Bounds(
      maxDepth: Int = DefaultMaxDepth,
      maxObjectFields: Int = DefaultMaxObjectFields,
      maxArrayElements: Int = DefaultMaxArrayElements,
      maxValues: Int = DefaultMaxValues,
      maxNumberDigits: Int = DefaultMaxNumberDigits,
      maxNumberExponent: Int = DefaultMaxNumberExponent
  ):
    require(maxArrayElements >= 1, "maxArrayElements must be >= 1")
    require(maxValues >= 1, "maxValues must be >= 1")
    require(
      maxNumberDigits >= 1 && maxNumberDigits <= MaxSupportedNumberDigits,
      s"maxNumberDigits must be in 1..$MaxSupportedNumberDigits"
    )
    require(
      maxNumberExponent >= 1 && maxNumberExponent <= MaxSupportedNumberExponent,
      s"maxNumberExponent must be in 1..$MaxSupportedNumberExponent"
    )

    /** The widest decimal window a number inside the bounds can occupy: no digit of it sits above
      * `10^window` or below `10^-window`. This is what the AST check enforces (the text form of a
      * parsed number is gone) and what bounds every later expansion to an integer.
      */
    val numberWindow: Int = maxNumberDigits + maxNumberExponent

  enum Violation:
    case FrameTooLong(length: Int, max: Int)
    case TooDeep(max: Int)
    case TooManyFields(max: Int)
    case TooManyElements(max: Int)
    case TooManyValues(max: Int)
    case NumberTooLong(max: Int)
    case NumberExponentTooLarge(max: Int)

    def message: String = this match
      case FrameTooLong(n, max) => s"frame is $n characters; limits.maxFrameChars is $max"
      case TooDeep(max) => s"JSON nesting exceeds limits.maxDepth ($max)"
      case TooManyFields(max) => s"a JSON object exceeds limits.maxObjectFields ($max)"
      case TooManyElements(max) => s"a JSON array exceeds limits.maxArrayElements ($max)"
      case TooManyValues(max) => s"the JSON holds more values than limits.maxValues ($max)"
      case NumberTooLong(max) => s"a JSON number has more digits than limits.maxNumberDigits ($max)"
      case NumberExponentTooLarge(max) =>
        s"a JSON number's exponent is outside limits.maxNumberExponent ($max)"

  /** Linear pass over raw JSON text. Exact for valid JSON; conservative (may under-count) for
    * invalid JSON, which the parser rejects anyway. Brackets, braces, commas, digits and exponent
    * markers inside string literals (member names or values) are never interpreted; `\` escapes are
    * honoured so an escaped quote cannot end a string early.
    *
    *   - Depth 1 = the outermost container.
    *   - An object with exactly `maxObjectFields` members and an array with exactly
    *     `maxArrayElements` elements pass (`n` children are `n - 1` commas).
    *   - Values: every object, array, string, number, `true`, `false` and `null` in value position
    *     counts once (member names do not). Counted without tokenising: one for the root, one per
    *     comma, one per non-empty container (its first child).
    *   - Numbers: the digits of the significand as written (integer and fraction part, leading
    *     zeros included) are counted against `maxNumberDigits`; the exponent is accumulated with
    *     saturation, so a thousand-digit exponent costs a thousand character reads and no
    *     arithmetic on its value.
    */
  @SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.Return"))
  def preScan(text: String, maxFrameChars: Int, bounds: Bounds): Option[Violation] =
    if text.length > maxFrameChars then
      return Some(Violation.FrameTooLong(text.length, maxFrameChars))
    val maxDepth = bounds.maxDepth
    val maxObjectFields = bounds.maxObjectFields
    val maxArrayElements = bounds.maxArrayElements
    val maxValues = bounds.maxValues
    val maxDigits = bounds.maxNumberDigits
    val maxExponent = bounds.maxNumberExponent
    // maxDepth is capped (<= 256) so the scratch arrays stay tiny; the +2 leaves room for the
    // depth that triggers TooDeep before we index into them.
    val isObject = new Array[Boolean](maxDepth + 2)
    val members = new Array[Int](maxDepth + 2)
    var depth = 0
    var values = 1
    var i = 0
    val n = text.length
    while i < n do
      val c = text.charAt(i)
      if c == '"' then i = stringEnd(text, i)
      else if c == '-' || (c >= '0' && c <= '9') then
        // One number token: [-]digits[.digits][(e|E)[+|-]digits]. Consumed here so its `e`, `.`
        // and signs never reach the structural match below.
        var digits = 0
        var exponent = 0
        var inExponent = false
        var more = true
        while more && i < n do
          val d = text.charAt(i)
          if d >= '0' && d <= '9' then
            if inExponent then
              // Saturates one step past the bound: no overflow, no magnitude-sized work.
              if exponent <= maxExponent then exponent = exponent * 10 + (d - '0')
            else digits += 1
            i += 1
          else if d == 'e' || d == 'E' then
            inExponent = true
            i += 1
          else if d == '.' || d == '-' || d == '+' then i += 1
          else more = false
        if digits > maxDigits then return Some(Violation.NumberTooLong(maxDigits))
        if exponent > maxExponent then return Some(Violation.NumberExponentTooLarge(maxExponent))
      else
        c match
          case '{' | '[' =>
            depth += 1
            if depth > maxDepth then return Some(Violation.TooDeep(maxDepth))
            isObject(depth) = c == '{'
            members(depth) = 0
            // A non-empty container holds one value more than it has commas: peek past the
            // whitespace for its first child. Whitespace runs are disjoint, so the peeks add at
            // most one extra read per character of the frame.
            var j = i + 1
            while j < n && isWhitespace(text.charAt(j)) do j += 1
            if j < n then
              val next = text.charAt(j)
              if next != '}' && next != ']' then
                values += 1
                if values > maxValues then return Some(Violation.TooManyValues(maxValues))
          case '}' | ']' =>
            if depth > 0 then depth -= 1
          case ',' =>
            values += 1
            if values > maxValues then return Some(Violation.TooManyValues(maxValues))
            if depth > 0 then
              members(depth) += 1
              if isObject(depth) then
                if members(depth) >= maxObjectFields then
                  return Some(Violation.TooManyFields(maxObjectFields))
              else if members(depth) >= maxArrayElements then
                return Some(Violation.TooManyElements(maxArrayElements))
          case _ => ()
        i += 1
    None

  /** The depth / object-width form kept for callers that bound nothing else; the array, value and
    * number bounds apply at their defaults.
    */
  def preScan(
      text: String,
      maxFrameChars: Int,
      maxDepth: Int,
      maxObjectFields: Int
  ): Option[Violation] =
    preScan(text, maxFrameChars, Bounds(maxDepth = maxDepth, maxObjectFields = maxObjectFields))

  /** Index just past the string literal that opens at `open` (`text.length` when it never closes).
    * A quote ends the string unless an odd number of backslashes sits directly before it. Jumps
    * from quote to quote with `indexOf`, so the body of a long string (a base64 payload) is skipped
    * by the platform's own search instead of being read a character at a time; each run of
    * backslashes is counted once, so the whole scan stays linear.
    */
  @SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.Return"))
  private def stringEnd(text: String, open: Int): Int =
    var quote = text.indexOf('"', open + 1)
    while quote >= 0 do
      var k = quote - 1
      while k > open && text.charAt(k) == '\\' do k -= 1
      if ((quote - 1 - k) & 1) == 0 then return quote + 1
      quote = text.indexOf('"', quote + 1)
    text.length

  private inline def isWhitespace(c: Char): Boolean =
    c == ' ' || c == '\n' || c == '\r' || c == '\t'

  /** Exact re-check on a parsed AST — iterative with an explicit worklist, never recurses. Only
    * containers count toward depth (the outermost container is depth 1) and every node counts as
    * one value, matching [[preScan]]. Numbers are checked with [[numberViolation]].
    */
  @SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.Return"))
  def validate(json: Json, bounds: Bounds): Option[Violation] =
    json match
      case _: Json.Obj | _: Json.Arr => ()
      case n: Json.Num => return numberViolation(n.value, bounds)
      case _ => return None
    val maxDepth = bounds.maxDepth
    val maxValues = bounds.maxValues
    val work = mutable.ArrayDeque.empty[(Json, Int)]
    var values = 1
    var found: Option[Violation] = None
    def push(j: Json, depth: Int): Unit = j match
      case _: Json.Obj | _: Json.Arr => work.append((j, depth))
      case n: Json.Num => if found.isEmpty then found = numberViolation(n.value, bounds)
      case _ => ()
    work.append((json, 1))
    while work.nonEmpty do
      val (node, depth) = work.removeLast()
      if depth > maxDepth then return Some(Violation.TooDeep(maxDepth))
      node match
        case Json.Obj(fields) =>
          if fields.length > bounds.maxObjectFields then
            return Some(Violation.TooManyFields(bounds.maxObjectFields))
          values += fields.length
          if values > maxValues then return Some(Violation.TooManyValues(maxValues))
          fields.foreach((_, v) => push(v, depth + 1))
        case Json.Arr(elems) =>
          if elems.length > bounds.maxArrayElements then
            return Some(Violation.TooManyElements(bounds.maxArrayElements))
          values += elems.length
          if values > maxValues then return Some(Violation.TooManyValues(maxValues))
          elems.foreach(push(_, depth + 1))
        case _ => ()
      if found.isDefined then return found
    None

  /** The depth / object-width form; the array, value and number bounds apply at their defaults. */
  def validate(json: Json, maxDepth: Int, maxObjectFields: Int): Option[Violation] =
    validate(json, Bounds(maxDepth = maxDepth, maxObjectFields = maxObjectFields))

  /** What [[scanNumbers]] saw in one argument value.
    *
    * @param violation
    *   the first number outside the bounds, if any
    * @param numericString
    *   whether some JSON STRING in the value is written as a number with more digits or a larger
    *   exponent than the bounds allow. Not a violation by itself (a `String` parameter may hold any
    *   text); the caller checks what such a value decoded into — see [[decodedNumberViolation]]
    */
  final class NumberScan(val violation: Option[Violation], val numericString: Boolean)

  /** The shared result for a value with nothing to report: the common case allocates nothing. */
  val CleanScan: NumberScan = new NumberScan(None, false)

  /** The number bounds only, over a whole argument value — the guard in front of every argument
    * decoder ([[DefaultDecodeContext.decodeRaw]]), which must hold for values that never crossed
    * the frame check (in-process calls, raw Scala `BigDecimal`s, prompt and resource-template
    * arguments, which are always strings). A scalar costs one type test; a container is walked
    * iteratively, so depth is irrelevant here.
    *
    * It also watches the string door: zio-json's numeric decoders accept a JSON string, and its
    * `BigDecimal` decoders keep whatever exponent that string carries, so a number can reach a
    * handler without ever being a `Json.Num`. One walk finds both.
    */
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  def scanNumbers(json: Json, bounds: Bounds): NumberScan =
    json match
      case n: Json.Num =>
        numberViolation(n.value, bounds) match
          case None => CleanScan
          case found => new NumberScan(found, false)
      case s: Json.Str =>
        if numericStringOutOfRange(s.value, bounds) then new NumberScan(None, true) else CleanScan
      case _: Json.Obj | _: Json.Arr =>
        // A list as the stack: a flat argument object allocates nothing beyond its one cell.
        var stack: List[Json] = json :: Nil
        var found: Option[Violation] = None
        var numericString = false
        def visit(child: Json): Unit = child match
          case n: Json.Num => found = numberViolation(n.value, bounds)
          case s: Json.Str =>
            if !numericString && numericStringOutOfRange(s.value, bounds) then numericString = true
          case _: Json.Obj | _: Json.Arr => stack = child :: stack
          case _ => ()
        while found.isEmpty && stack.nonEmpty do
          val node = stack.head
          stack = stack.tail
          node match
            case Json.Obj(fields) =>
              var i = 0
              val length = fields.length
              while found.isEmpty && i < length do
                visit(fields(i)._2)
                i += 1
            case Json.Arr(elems) =>
              var i = 0
              val length = elems.length
              while found.isEmpty && i < length do
                visit(elems(i))
                i += 1
            case _ => ()
        if found.isEmpty && !numericString then CleanScan else new NumberScan(found, numericString)
      case _ => CleanScan

  /** True when `s` could be read as a number by zio-json's string-accepting numeric decoders —
    * `[-](digits|.)*[(e|E)[+|-]digits]` and nothing else — and is written with more digits or a
    * larger exponent than the bounds allow. Deliberately a superset of the grammar zio-json accepts
    * (it takes `.5` and `-.5e9` as well as `0.5`): a false positive only costs the caller its
    * post-decode check, a false negative would let a number through. Cheap for ordinary text: a
    * string is dismissed on its first character that cannot be part of a number. The exponent is
    * accumulated with saturation, as in [[preScan]].
    */
  @SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.Return"))
  def numericStringOutOfRange(s: String, bounds: Bounds): Boolean =
    val n = s.length
    if n < 2 then return false
    val maxExponent = bounds.maxNumberExponent
    var i = if s.charAt(0) == '-' then 1 else 0
    var digits = 0
    var marker = -1
    while marker < 0 && i < n do
      val c = s.charAt(i)
      if c >= '0' && c <= '9' then
        digits += 1
        i += 1
      else if c == '.' then i += 1
      else if c == 'e' || c == 'E' then marker = i
      else return false
    if marker < 0 then return digits > bounds.maxNumberDigits
    i = marker + 1
    if i < n && (s.charAt(i) == '-' || s.charAt(i) == '+') then i += 1
    if i >= n then return false
    var exponent = 0
    while i < n do
      val c = s.charAt(i)
      if c < '0' || c > '9' then return false
      if exponent <= maxExponent then exponent = exponent * 10 + (c - '0')
      i += 1
    exponent > maxExponent || digits > bounds.maxNumberDigits

  /** The number bounds applied to a DECODED argument value: the first `BigDecimal` in it that is
    * outside the bounds. Only called for a value [[scanNumbers]] flagged (`numericString`), so
    * ordinary requests never pay for it. Walks what a decoder builds from JSON — case classes and
    * tuples (`Product`), `Option`, strict collections, maps, arrays — iteratively; a lazy
    * collection and an object of any other class are not opened.
    */
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  def decodedNumberViolation(value: Any, bounds: Bounds): Option[Violation] =
    var stack: List[Any] = value :: Nil
    var found: Option[Violation] = None
    while found.isEmpty && stack.nonEmpty do
      val node = stack.head
      stack = stack.tail
      node match
        case n: java.math.BigDecimal => found = numberViolation(n, bounds)
        case n: scala.math.BigDecimal => found = numberViolation(n.bigDecimal, bounds)
        case _: String | _: Json | _: Array[Byte] => ()
        case _: LazyList[?] | _: scala.collection.View[?] => ()
        case m: scala.collection.Map[?, ?] =>
          m.foreachEntry { (k, v) =>
            stack = k :: v :: stack
          }
        case it: scala.collection.Iterable[?] => it.foreach(e => stack = e :: stack)
        case a: Array[?] => a.foreach(e => stack = e :: stack)
        case p: Product => p.productIterator.foreach(e => stack = e :: stack)
        case _ => ()
    found

  /** A parsed number is inside the bounds when it has at most `maxNumberDigits` significant digits
    * and none of them sits outside `10^±(maxNumberDigits + maxNumberExponent)` — the window every
    * literal the pre-scan admits falls in, whatever normalisation the parser applied. Reads only
    * the scale and (once the scale is known to be small) the precision: nothing here is
    * proportional to the magnitude, and a compact `BigDecimal` is not inflated.
    */
  def numberViolation(n: java.math.BigDecimal, bounds: Bounds): Option[Violation] =
    val window = bounds.numberWindow.toLong
    val scale = n.scale.toLong
    if scale > window || -scale > window then
      Some(Violation.NumberExponentTooLarge(bounds.maxNumberExponent))
    else
      val precision = n.precision.toLong
      if precision - scale > window then
        Some(Violation.NumberExponentTooLarge(bounds.maxNumberExponent))
      else if precision > bounds.maxNumberDigits then
        Some(Violation.NumberTooLong(bounds.maxNumberDigits))
      else None

  /** Human-readable JSON type name for error messages — used instead of rendering the offending
    * value, so a multi-megabyte frame cannot amplify into a multi-megabyte error body.
    */
  def typeName(json: Json): String = json match
    case _: Json.Obj => "object"
    case _: Json.Arr => "array"
    case _: Json.Str => "string"
    case _: Json.Num => "number"
    case _: Json.Bool => "boolean"
    case Json.Null => "null"
