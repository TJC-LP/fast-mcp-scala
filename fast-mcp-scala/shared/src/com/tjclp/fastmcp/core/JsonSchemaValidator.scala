package com.tjclp.fastmcp
package core

import scala.collection.mutable

import zio.Chunk
import zio.json.ast.Json

/** A small, dependency-free JSON Schema validator for `tools/call` arguments. Platform-neutral (no
  * regular expressions, no reflection): the same code runs on the JVM, Scala.js and Scala Native.
  *
  * It enforces a documented SUBSET of JSON Schema, chosen so that every schema the library derives
  * validates its own valid inputs and so that checking a value never costs more than a walk over
  * it.
  *
  * Enforced keywords ([[JsonSchemaValidator.EnforcedKeywords]]):
  *   - any value: `type` (a name or a list of names), `enum`, `const`
  *   - numbers: `minimum`, `maximum`, `exclusiveMinimum`, `exclusiveMaximum` (the numeric form and
  *     the draft-04 boolean form)
  *   - strings: `minLength`, `maxLength` (counted in Unicode code points)
  *   - arrays: `items`, `prefixItems` (and the draft-07 array form of `items` with
  *     `additionalItems`), `minItems`, `maxItems`
  *   - objects: `properties`, `required`, `additionalProperties`
  *   - combinators: `allOf`, `anyOf`, `oneOf`, `not`
  *   - `$ref` to a location inside the same schema (`#`, `#/$defs/Name`, `#/definitions/Name` or
  *     any other JSON Pointer), with its sibling keywords applied as well
  *
  * NOT enforced ([[JsonSchemaValidator.UnenforcedKeywords]]) — a schema that uses one of these is
  * still accepted, the keyword is skipped, and it is listed in [[unenforced]]:
  *   - `pattern` and `patternProperties`: matching a client-supplied string against a regular
  *     expression with a backtracking engine is not linear-time, so neither is evaluated. Check the
  *     string in the handler or in an `McpInputCodec` decoder.
  *   - `format`, `multipleOf`, `uniqueItems`, `minProperties`, `maxProperties`, `propertyNames`,
  *     `contains` / `minContains` / `maxContains`, `dependentRequired` / `dependentSchemas` /
  *     `dependencies`, `if` / `then` / `else`, `unevaluatedProperties` / `unevaluatedItems`, the
  *     `content*` keywords, `$dynamicRef` / `$recursiveRef`
  *   - a `$ref` that is not a local JSON Pointer, that does not resolve, or that leads back to a
  *     schema still being resolved (a recursive schema): the reference accepts any value
  *   - `additionalProperties` when `patternProperties` is also present (the properties it applies
  *     to cannot be determined without evaluating the patterns)
  *   - a supported keyword whose value has the wrong shape (for example `"maximum": "10"`)
  *
  * Every other keyword (`description`, `title`, `default`, `examples`, `$schema`, vendor
  * extensions, ...) is an annotation and has no effect on validation.
  *
  * Cost. A schema is compiled once ([[JsonSchemaValidator.compile]]); validation then visits each
  * node of the value once per schema node that applies to it and allocates only for objects with
  * `required` members and on failure. With no `$ref` that is at most `schema nodes x value nodes`
  * steps, and for the schemas the library derives a small constant per value node. Numbers are
  * compared without expanding their exponent, string lengths are counted in one pass, and object
  * members are looked up in tables built from the SCHEMA's own property names, never from the
  * client's keys. The first violation ends the walk.
  *
  * Depth. The walk recurses once per nesting level of the value. Values that arrive on the wire are
  * bounded by `LimitSettings.maxDepth` before they get here.
  */
final class JsonSchemaValidator private (
    root: JsonSchemaValidator.Node,
    /** What this schema declares but the validator does not enforce: unenforced keyword names and
      * descriptions of unusable `$ref`s, sorted. Empty when every constraint in the schema is
      * enforced.
      */
    val unenforced: List[String]
):
  import JsonSchemaValidator.*

  /** `None` when `value` conforms to the enforced part of the schema; otherwise a description of
    * the first violation found. The description names the location and the constraint; it never
    * echoes the offending value.
    */
  def validate(value: Json): Option[String] =
    val failure = check(root, value)
    if failure eq NoFailure then None else Some(failure.render)

  /** Whether the schema constrains anything at all (`false` for `{}`, `true` and schemas made only
    * of annotations and unenforced keywords).
    */
  def constrains: Boolean = !(root eq Accept)

  private def check(node: Node, value: Json): Failure =
    node match
      case c: Constraints => checkConstraints(c, value)
      case _ => if node eq Reject then new Failure("no value is allowed here") else NoFailure

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private def checkConstraints(c: Constraints, value: Json): Failure =
    var failure =
      if c.types != 0 && !typeMatches(c.types, value) then
        new Failure(s"must be ${typeNames(c.types)}, got ${typeName(value)}")
      else NoFailure
    if (failure eq NoFailure) && c.hasEnum then failure = checkEnum(c, value)
    if failure eq NoFailure then
      failure = value match
        case Json.Str(s) => if c.hasStringChecks then checkString(c, s) else NoFailure
        case Json.Num(n) => if c.hasNumberChecks then checkNumber(c, n) else NoFailure
        case Json.Obj(fields) => if c.hasObjectChecks then checkObject(c, fields) else NoFailure
        case Json.Arr(elements) => if c.hasArrayChecks then checkArray(c, elements) else NoFailure
        case _ => NoFailure
    if (failure eq NoFailure) && c.hasCombinators then failure = checkCombinators(c, value)
    failure

  private def checkEnum(c: Constraints, value: Json): Failure =
    val allowed =
      if c.enumIsStrings then
        value match
          case Json.Str(s) => c.enumStrings.contains(s)
          case _ => false
      else c.enumValues.exists(member => jsonEquals(member, value))
    if allowed then
      if c.hasConst && !jsonEquals(c.const, value) then new Failure("is not the required value")
      else NoFailure
    else if c.enumIsStrings then new Failure(s"must be one of: ${preview(c.enumValues)}")
    else new Failure("is not one of the allowed values")

  private def checkString(c: Constraints, s: String): Failure =
    // A string has at most as many code points as UTF-16 units, so most strings are decided on
    // `length` alone; the code-point count runs only when surrogate pairs could change the answer.
    // (`units - units / 2` is the fewest code points `units` UTF-16 units can hold.)
    val units = s.length
    val fewest = units - units / 2
    if c.maxLength >= 0 && units > c.maxLength &&
      (fewest > c.maxLength || s.codePointCount(0, units) > c.maxLength)
    then new Failure(s"must be at most ${c.maxLength} characters long")
    else if c.minLength > 0 &&
      (units < c.minLength || (fewest < c.minLength && s.codePointCount(0, units) < c.minLength))
    then new Failure(s"must be at least ${c.minLength} characters long")
    else NoFailure

  private def checkNumber(c: Constraints, n: java.math.BigDecimal): Failure =
    if c.hasMinimum && n.compareTo(c.minimum) < 0 then new Failure(s"must be >= ${c.minimum}")
    else if c.hasMaximum && n.compareTo(c.maximum) > 0 then new Failure(s"must be <= ${c.maximum}")
    else if c.hasExclusiveMinimum && n.compareTo(c.exclusiveMinimum) <= 0 then
      new Failure(s"must be > ${c.exclusiveMinimum}")
    else if c.hasExclusiveMaximum && n.compareTo(c.exclusiveMaximum) >= 0 then
      new Failure(s"must be < ${c.exclusiveMaximum}")
    else NoFailure

  /** One pass over the members. A member is looked up in `c.properties` — a table keyed by the
    * schema's own names — and required members are ticked off in a flag array, so no table is ever
    * built from client-chosen keys. A key that occurs more than once is validated every time.
    */
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private def checkObject(c: Constraints, fields: Chunk[(String, Json)]): Failure =
    val required = c.required
    val seen = if required.length == 0 then NoFlags else new Array[Boolean](required.length)
    var missing = required.length
    var failure = NoFailure
    val it = fields.iterator
    while (failure eq NoFailure) && it.hasNext do
      val field = it.next()
      val key = field._1
      val property = c.properties.getOrElse(key, UndeclaredProperty)
      if property.requiredIndex >= 0 && !seen(property.requiredIndex) then
        seen(property.requiredIndex) = true
        missing -= 1
      val schema = if property.declared then property.schema else c.additionalProperties
      if !(schema eq Accept) then
        if !property.declared && (schema eq Reject) then
          failure = new Failure(s"unexpected property '${clip(key)}'")
        else
          val inner = check(schema, field._2)
          if !(inner eq NoFailure) then failure = inner.under(clip(key))
    if (failure eq NoFailure) && missing > 0 then
      new Failure(s"missing required property '${required(seen.indexOf(false))}'")
    else failure

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private def checkArray(c: Constraints, elements: Chunk[Json]): Failure =
    val size = elements.length
    if c.minItems > 0 && size < c.minItems then
      new Failure(s"must have at least ${c.minItems} items")
    else if c.maxItems >= 0 && size > c.maxItems then
      new Failure(s"must have at most ${c.maxItems} items")
    else if c.prefixItems.length == 0 && (c.items eq Accept) then NoFailure
    else
      var failure = NoFailure
      var index = 0
      val it = elements.iterator
      while (failure eq NoFailure) && it.hasNext do
        val element = it.next()
        val schema = if index < c.prefixItems.length then c.prefixItems(index) else c.items
        if !(schema eq Accept) then
          val inner = check(schema, element)
          if !(inner eq NoFailure) then failure = inner.under(s"[$index]")
        index += 1
      failure

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private def checkCombinators(c: Constraints, value: Json): Failure =
    var failure = NoFailure
    var i = 0
    while (failure eq NoFailure) && i < c.allOf.length do
      failure = check(c.allOf(i), value)
      i += 1
    if (failure eq NoFailure) && c.anyOf.length > 0 then
      var matched = false
      var first = NoFailure
      i = 0
      while !matched && i < c.anyOf.length do
        val inner = check(c.anyOf(i), value)
        if inner eq NoFailure then matched = true
        else if i == c.anyOfPrimary then first = inner
        i += 1
      if !matched then
        // `anyOf [X, {"type":"null"}]` is how an optional value is advertised: X's own reason is
        // the useful one. Anything else gets the generic message.
        failure =
          if !(first eq NoFailure) then first
          else new Failure(s"does not match any of the ${c.anyOf.length} allowed alternatives")
    if (failure eq NoFailure) && c.oneOf.length > 0 then
      var matches = 0
      i = 0
      while matches < 2 && i < c.oneOf.length do
        if check(c.oneOf(i), value) eq NoFailure then matches += 1
        i += 1
      if matches == 0 then
        failure = new Failure(s"does not match any of the ${c.oneOf.length} allowed alternatives")
      else if matches > 1 then
        failure = new Failure("matches more than one of the alternatives, exactly one is allowed")
    if (failure eq NoFailure) && c.hasNot && (check(c.not, value) eq NoFailure) then
      failure = new Failure("matches a schema it must not match")
    failure

object JsonSchemaValidator:

  /** The keywords [[JsonSchemaValidator]] enforces. */
  val EnforcedKeywords: Set[String] = Set(
    "type",
    "enum",
    "const",
    "minimum",
    "maximum",
    "exclusiveMinimum",
    "exclusiveMaximum",
    "minLength",
    "maxLength",
    "items",
    "prefixItems",
    "additionalItems",
    "minItems",
    "maxItems",
    "properties",
    "required",
    "additionalProperties",
    "allOf",
    "anyOf",
    "oneOf",
    "not",
    "$ref"
  )

  /** Validation keywords the validator recognises and deliberately does NOT enforce. A schema that
    * uses one compiles normally; the keyword is skipped and reported in
    * [[JsonSchemaValidator.unenforced]].
    */
  val UnenforcedKeywords: Set[String] = Set(
    "pattern",
    "patternProperties",
    "format",
    "multipleOf",
    "uniqueItems",
    "minProperties",
    "maxProperties",
    "propertyNames",
    "contains",
    "minContains",
    "maxContains",
    "dependentRequired",
    "dependentSchemas",
    "dependencies",
    "if",
    "then",
    "else",
    "unevaluatedProperties",
    "unevaluatedItems",
    "contentEncoding",
    "contentMediaType",
    "contentSchema",
    "$dynamicRef",
    "$recursiveRef"
  )

  /** Compile a JSON Schema document. Never fails: anything the validator cannot enforce is skipped
    * and reported in [[JsonSchemaValidator.unenforced]].
    */
  def compile(schema: Json): JsonSchemaValidator =
    val compiler = new Compiler(schema)
    val root = compiler.compileDocument()
    new JsonSchemaValidator(root, compiler.unenforced.toList.sorted)

  /** Compile a tool's advertised `inputSchema`. */
  def compile(schema: ToolInputSchema): JsonSchemaValidator = compile(schema.toAst)

  // ---- compiled form ----

  private sealed abstract class Node

  /** `true`, `{}` and schemas that constrain nothing. */
  private object Accept extends Node

  /** `false`. */
  private object Reject extends Node

  /** One schema object, flattened into fields the checks read directly. Written only by the
    * [[Compiler]] and never after [[compile]] returns.
    */
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private final class Constraints extends Node:
    var types: Int = 0
    var hasEnum: Boolean = false
    var enumIsStrings: Boolean = false
    var enumStrings: Set[String] = Set.empty
    var enumValues: Array[Json] = NoJson
    var hasConst: Boolean = false
    var const: Json = Json.Null
    var hasMinimum: Boolean = false
    var minimum: java.math.BigDecimal = java.math.BigDecimal.ZERO
    var hasMaximum: Boolean = false
    var maximum: java.math.BigDecimal = java.math.BigDecimal.ZERO
    var hasExclusiveMinimum: Boolean = false
    var exclusiveMinimum: java.math.BigDecimal = java.math.BigDecimal.ZERO
    var hasExclusiveMaximum: Boolean = false
    var exclusiveMaximum: java.math.BigDecimal = java.math.BigDecimal.ZERO
    var minLength: Int = 0
    var maxLength: Int = -1
    var minItems: Int = 0
    var maxItems: Int = -1
    var items: Node = Accept
    var prefixItems: Array[Node] = NoNodes
    var properties: Map[String, Property] = Map.empty
    var required: Array[String] = NoStrings
    var additionalProperties: Node = Accept
    var allOf: Array[Node] = NoNodes
    var anyOf: Array[Node] = NoNodes
    var anyOfPrimary: Int = -1
    var oneOf: Array[Node] = NoNodes
    var hasNot: Boolean = false
    var not: Node = Accept

    def hasStringChecks: Boolean = minLength > 0 || maxLength >= 0

    def hasNumberChecks: Boolean =
      hasMinimum || hasMaximum || hasExclusiveMinimum || hasExclusiveMaximum

    def hasObjectChecks: Boolean =
      properties.nonEmpty || required.length > 0 || !(additionalProperties eq Accept)

    def hasArrayChecks: Boolean =
      minItems > 0 || maxItems >= 0 || prefixItems.length > 0 || !(items eq Accept)

    def hasCombinators: Boolean =
      allOf.length > 0 || anyOf.length > 0 || oneOf.length > 0 || hasNot

    def constrainsNothing: Boolean =
      types == 0 && !hasEnum && !hasStringChecks && !hasNumberChecks && !hasObjectChecks &&
        !hasArrayChecks && !hasCombinators

  /** What the schema says about one object member name.
    *
    * @param schema
    *   the `properties` schema ([[Accept]] when the name is only listed in `required`)
    * @param declared
    *   whether the name appears in `properties` (otherwise `additionalProperties` applies)
    * @param requiredIndex
    *   the name's position in `required`, or -1
    */
  private final class Property(val schema: Node, val declared: Boolean, val requiredIndex: Int)

  private val UndeclaredProperty = new Property(Accept, declared = false, requiredIndex = -1)

  private val NoNodes: Array[Node] = Array.empty
  private val NoJson: Array[Json] = Array.empty
  private val NoStrings: Array[String] = Array.empty
  private val NoFlags: Array[Boolean] = Array.empty

  // ---- failures ----

  /** The first violation, with the path to it built while the walk unwinds (so a conforming value
    * costs no path bookkeeping at all).
    */
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private final class Failure(val message: String):
    private var path: List[String] = Nil

    def under(segment: String): Failure =
      path = segment :: path
      this

    def render: String =
      if path.isEmpty then message
      else
        val where = new StringBuilder
        path.foreach { segment =>
          if where.nonEmpty && !segment.startsWith("[") then where.append('.')
          where.append(segment)
        }
        s"'$where' $message"

  private val NoFailure = new Failure("")

  /** Client-chosen member names appear in messages; keep them short. */
  private def clip(key: String): String =
    if key.length <= MaxKeyChars then key else key.substring(0, MaxKeyChars) + "..."

  private val MaxKeyChars = 64

  /** The start of a string `enum`, for the message. */
  private def preview(values: Array[Json]): String =
    val shown = values.iterator
      .collect { case Json.Str(s) => clip(s) }
      .take(MaxEnumPreview)
      .mkString(", ")
    if values.length > MaxEnumPreview then s"$shown, ..." else shown

  private val MaxEnumPreview = 16

  // ---- types ----

  private val TNull = 1
  private val TBoolean = 2
  private val TObject = 4
  private val TArray = 8
  private val TNumber = 16
  private val TInteger = 32
  private val TString = 64

  private val TypeBits: List[(String, Int)] = List(
    "null" -> TNull,
    "boolean" -> TBoolean,
    "object" -> TObject,
    "array" -> TArray,
    "number" -> TNumber,
    "integer" -> TInteger,
    "string" -> TString
  )

  private def typeMatches(types: Int, value: Json): Boolean =
    value match
      case _: Json.Str => (types & TString) != 0
      case Json.Num(n) =>
        (types & TNumber) != 0 || ((types & TInteger) != 0 && isIntegral(n))
      case _: Json.Obj => (types & TObject) != 0
      case _: Json.Arr => (types & TArray) != 0
      case _: Json.Bool => (types & TBoolean) != 0
      case _ => (types & TNull) != 0

  private def typeName(value: Json): String =
    value match
      case _: Json.Str => "a string"
      case _: Json.Num => "a number"
      case _: Json.Obj => "an object"
      case _: Json.Arr => "an array"
      case _: Json.Bool => "a boolean"
      case _ => "null"

  private def typeNames(types: Int): String =
    TypeBits.collect { case (name, bit) if (types & bit) != 0 => name }.mkString(" or ")

  /** Whether a JSON number is an integer (`1`, `1.0` and `1e3` are; `1.5` and `1e-3` are not),
    * decided without expanding the exponent. A non-positive scale is integral by construction. For
    * a positive scale the unscaled value must be a multiple of `10^scale`, which needs more than `3
    * * scale` bits — so a huge negative exponent is answered from the bit length alone, and
    * otherwise the power of ten is no larger than the number itself: one division, never a loop
    * over the digits.
    */
  private def isIntegral(n: java.math.BigDecimal): Boolean =
    val scale = n.scale
    if scale <= 0 || n.signum == 0 then true
    else
      val unscaled = n.unscaledValue
      3L * scale < unscaled.bitLength.toLong &&
      unscaled.mod(java.math.BigInteger.TEN.pow(scale)).signum == 0

  /** JSON equality between a schema-side value (`enum` member, `const`) and an argument: numbers
    * compare by value (`1` equals `1.0`), objects ignore member order. An object or array costs at
    * most the size of the schema-side value squared, because a size mismatch is decided first.
    */
  private def jsonEquals(expected: Json, actual: Json): Boolean =
    (expected, actual) match
      case (Json.Str(a), Json.Str(b)) => a == b
      case (Json.Num(a), Json.Num(b)) => a.compareTo(b) == 0
      case (Json.Bool(a), Json.Bool(b)) => a == b
      case (Json.Null, Json.Null) => true
      case (Json.Arr(a), Json.Arr(b)) =>
        a.length == b.length && a.iterator.zip(b.iterator).forall((x, y) => jsonEquals(x, y))
      case (Json.Obj(a), Json.Obj(b)) =>
        a.length == b.length &&
        a.forall((key, x) => b.exists((k, y) => k == key && jsonEquals(x, y))) &&
        b.forall((key, _) => a.exists((k, _) => k == key))
      case _ => false

  // ---- compiler ----

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private final class Compiler(document: Json):

    val unenforced: mutable.Set[String] = mutable.Set.empty

    /** `$ref` targets already compiled, by reference string (shared, so the result is a DAG). */
    private val resolved = mutable.HashMap.empty[String, Node]

    /** References being compiled right now. Meeting one of them again means the schema is
      * recursive; that reference is not followed, which also keeps the compiled form acyclic — a
      * validation walk can therefore never loop on the schema.
      */
    private val resolving = mutable.HashSet.empty[String]

    def compileDocument(): Node =
      resolving += "#"
      val node = compile(document)
      resolving -= "#"
      node

    private def compile(schema: Json): Node =
      schema match
        case Json.Bool(allow) => if allow then Accept else Reject
        case obj: Json.Obj => compileObject(obj)
        case _ =>
          unenforced += "a schema that is neither an object nor a boolean"
          Accept

    private def compileAll(key: String, value: Json): Array[Node] =
      value match
        case Json.Arr(schemas) if schemas.nonEmpty => schemas.map(compile).toArray
        case _ =>
          malformed(key)
          NoNodes

    private def malformed(key: String): Unit =
      unenforced += s"$key (value has the wrong shape)"

    private def compileObject(obj: Json.Obj): Node =
      val c = new Constraints
      val allOf = mutable.ArrayBuffer.empty[Node]
      var properties = Map.empty[String, Node]
      var required = Vector.empty[String]
      var minimumIsExclusive = false
      var maximumIsExclusive = false
      var tupleItems = false
      var additionalItems: Node = Accept
      var patternProperties = false

      obj.fields.foreach { (key, value) =>
        key match
          case "type" =>
            typeMask(value) match
              case Some(mask) => c.types = mask
              case None => malformed(key)
          case "enum" =>
            value match
              case Json.Arr(members) =>
                c.hasEnum = true
                c.enumValues = members.toArray
                val strings = members.collect { case Json.Str(s) => s }
                c.enumIsStrings = strings.length == members.length
                c.enumStrings = strings.toSet
              case _ => malformed(key)
          case "const" =>
            c.hasConst = true
            c.const = value
          case "minimum" =>
            value match
              case Json.Num(n) =>
                c.hasMinimum = true
                c.minimum = n
              case _ => malformed(key)
          case "maximum" =>
            value match
              case Json.Num(n) =>
                c.hasMaximum = true
                c.maximum = n
              case _ => malformed(key)
          case "exclusiveMinimum" =>
            value match
              case Json.Num(n) =>
                c.hasExclusiveMinimum = true
                c.exclusiveMinimum = n
              case Json.Bool(flag) => minimumIsExclusive = flag
              case _ => malformed(key)
          case "exclusiveMaximum" =>
            value match
              case Json.Num(n) =>
                c.hasExclusiveMaximum = true
                c.exclusiveMaximum = n
              case Json.Bool(flag) => maximumIsExclusive = flag
              case _ => malformed(key)
          case "minLength" => count(key, value).foreach(n => c.minLength = n)
          case "maxLength" => count(key, value).foreach(n => c.maxLength = n)
          case "minItems" => count(key, value).foreach(n => c.minItems = n)
          case "maxItems" => count(key, value).foreach(n => c.maxItems = n)
          case "items" =>
            value match
              case Json.Arr(schemas) =>
                tupleItems = true
                c.prefixItems = schemas.map(compile).toArray
              case _ => c.items = compile(value)
          case "prefixItems" =>
            value match
              case Json.Arr(schemas) => c.prefixItems = schemas.map(compile).toArray
              case _ => malformed(key)
          case "additionalItems" => additionalItems = compile(value)
          case "properties" =>
            value match
              case Json.Obj(members) =>
                properties = members.iterator.map((name, schema) => name -> compile(schema)).toMap
              case _ => malformed(key)
          case "required" =>
            value match
              case Json.Arr(names) =>
                val strings = names.collect { case Json.Str(name) => name }
                if strings.length == names.length then required = strings.distinct.toVector
                else malformed(key)
              case _ => malformed(key)
          case "additionalProperties" => c.additionalProperties = compile(value)
          case "patternProperties" =>
            patternProperties = true
            unenforced += key
          case "allOf" => allOf ++= compileAll(key, value)
          case "anyOf" => c.anyOf = compileAll(key, value)
          case "oneOf" => c.oneOf = compileAll(key, value)
          case "not" =>
            c.hasNot = true
            c.not = compile(value)
          case "$ref" =>
            value match
              case Json.Str(reference) => allOf += resolve(reference)
              case _ => malformed(key)
          case other => if UnenforcedKeywords.contains(other) then unenforced += other
      }

      // Draft-04 spelling: `"minimum": 1, "exclusiveMinimum": true`.
      if minimumIsExclusive && c.hasMinimum then
        c.hasExclusiveMinimum = true
        c.exclusiveMinimum = c.minimum
        c.hasMinimum = false
      if maximumIsExclusive && c.hasMaximum then
        c.hasExclusiveMaximum = true
        c.exclusiveMaximum = c.maximum
        c.hasMaximum = false
      // Draft-07 spelling: `items` as an array is the prefix, `additionalItems` covers the rest.
      if tupleItems then c.items = additionalItems
      // Which members `additionalProperties` covers depends on the patterns, which are not
      // evaluated — so it cannot be applied without rejecting members a pattern would have claimed.
      if patternProperties && !(c.additionalProperties eq Accept) then
        unenforced += "additionalProperties (next to patternProperties)"
        c.additionalProperties = Accept
      if c.hasConst && !c.hasEnum then
        c.hasEnum = true
        c.enumValues = Array(c.const)
        c.hasConst = false

      c.required = required.toArray
      c.properties = properties.map((name, schema) =>
        name -> new Property(schema, declared = true, requiredIndex = required.indexOf(name))
      ) ++ required.zipWithIndex.collect {
        case (name, index) if !properties.contains(name) =>
          name -> new Property(Accept, declared = false, requiredIndex = index)
      }
      c.allOf = allOf.filterNot(_ eq Accept).toArray
      c.anyOfPrimary = primaryAlternative(c.anyOf)

      if c.constrainsNothing then Accept else c

    /** In `anyOf [X, {"type":"null"}]` (an optional value) the index of `X`; -1 for any other
      * shape.
      */
    private def primaryAlternative(anyOf: Array[Node]): Int =
      val others = anyOf.indices.filterNot(i => isNullType(anyOf(i)))
      if anyOf.length >= 2 && others.length == 1 then others.head else -1

    private def isNullType(node: Node): Boolean =
      node match
        case c: Constraints =>
          c.types == TNull && !c.hasEnum && !c.hasCombinators
        case _ => false

    private def typeMask(value: Json): Option[Int] =
      def bit(name: Json): Option[Int] =
        name match
          case Json.Str(n) => TypeBits.collectFirst { case (`n`, b) => b }
          case _ => None
      value match
        case Json.Arr(names) if names.nonEmpty =>
          names.foldLeft(Option(0))((mask, name) => mask.flatMap(m => bit(name).map(_ | m)))
        case other => bit(other)

    /** A non-negative integer keyword value (`minLength`, `maxItems`, ...), clamped to `Int`. */
    private def count(key: String, value: Json): Option[Int] =
      value match
        case Json.Num(n) if n.signum >= 0 && isIntegral(n) =>
          if n.compareTo(MaxCount) > 0 then Some(Int.MaxValue) else Some(n.intValue)
        case _ =>
          malformed(key)
          None

    private def resolve(reference: String): Node =
      if resolving.contains(reference) then
        unenforced += s"$$ref $reference (recursive)"
        Accept
      else
        resolved.get(reference) match
          case Some(node) => node
          case None =>
            locate(reference) match
              case Some(target) =>
                resolving += reference
                val node = compile(target)
                resolving -= reference
                resolved.update(reference, node)
                node
              case None =>
                unenforced += s"$$ref $reference (not a reference inside this schema)"
                Accept

    /** The schema a local reference (`#`, `#/$defs/Name`, any JSON Pointer fragment) points at. */
    private def locate(reference: String): Option[Json] =
      if reference == "#" then Some(document)
      else if !reference.startsWith("#/") then None
      else
        reference
          .substring(2)
          .split("/", -1)
          .foldLeft(Option(document)) { (current, escaped) =>
            val segment = escaped.replace("~1", "/").replace("~0", "~")
            current.flatMap {
              case Json.Obj(members) => members.collectFirst { case (`segment`, value) => value }
              case Json.Arr(elements) =>
                segment.toIntOption.filter(i => i >= 0 && i < elements.length).map(elements(_))
              case _ => None
            }
          }

  private val MaxCount = java.math.BigDecimal.valueOf(Int.MaxValue.toLong)
