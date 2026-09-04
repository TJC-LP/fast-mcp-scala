package com.tjclp.fastmcp
package codec

import scala.collection.mutable

import zio.json.ast.Json

/** Structural bounds on client-supplied JSON: frame length, nesting depth and per-object member
  * count. Pure and platform-neutral (JVM, Scala.js, Scala Native) — depends only on zio-json's
  * [[Json]] AST. Lives in `codec/` so both [[DefaultDecodeContext]] (embedded JSON strings) and the
  * transport choke point (`MessageLoop.parseFrame`) can use it.
  *
  * Two layers: [[preScan]] is a linear, near-allocation-free pass over the raw text that rejects a
  * violating frame BEFORE the parser allocates a single node (so a 100 000-deep `[[[[…` or a
  * 2^17-key object costs one character scan); [[validate]] is the authoritative, iterative re-check
  * on the parsed AST. Neither recurses, so neither can overflow the stack on any input.
  */
private[fastmcp] object JsonLimits:

  enum Violation:
    case FrameTooLong(length: Int, max: Int)
    case TooDeep(max: Int)
    case TooManyFields(max: Int)

    def message: String = this match
      case FrameTooLong(n, max) => s"frame is $n characters; limits.maxFrameChars is $max"
      case TooDeep(max) => s"JSON nesting exceeds limits.maxDepth ($max)"
      case TooManyFields(max) => s"a JSON object exceeds limits.maxObjectFields ($max)"

  /** Linear pass over raw JSON text. Exact for valid JSON; conservative (may under-count) for
    * invalid JSON, which the parser rejects anyway. Brackets, braces and commas inside string
    * literals (keys or values) are never interpreted; `\` escapes are honoured so an escaped quote
    * cannot end a string early. Depth 1 = the outermost container; an object with exactly
    * `maxObjectFields` members (`maxObjectFields - 1` commas) passes.
    */
  @SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.Return"))
  def preScan(
      text: String,
      maxFrameChars: Int,
      maxDepth: Int,
      maxObjectFields: Int
  ): Option[Violation] =
    if text.length > maxFrameChars then
      return Some(Violation.FrameTooLong(text.length, maxFrameChars))
    // maxDepth is capped by LimitSettings (<= 256) so the scratch arrays stay tiny; the +2 leaves
    // room for the depth that triggers TooDeep before we index into them.
    val isObject = new Array[Boolean](maxDepth + 2)
    val members = new Array[Int](maxDepth + 2)
    var depth = 0
    var inString = false
    var escaped = false
    var i = 0
    val n = text.length
    while i < n do
      val c = text.charAt(i)
      if inString then
        if escaped then escaped = false
        else if c == '\\' then escaped = true
        else if c == '"' then inString = false
      else
        c match
          case '"' => inString = true
          case '{' | '[' =>
            depth += 1
            if depth > maxDepth then return Some(Violation.TooDeep(maxDepth))
            isObject(depth) = c == '{'
            members(depth) = 0
          case '}' | ']' =>
            if depth > 0 then depth -= 1
          case ',' =>
            if depth > 0 && isObject(depth) then
              members(depth) += 1
              if members(depth) >= maxObjectFields then
                return Some(Violation.TooManyFields(maxObjectFields))
          case _ => ()
      i += 1
    None

  /** Exact re-check on a parsed AST — iterative with an explicit worklist, never recurses. Only
    * containers count toward depth (the outermost container is depth 1), matching [[preScan]].
    */
  @SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.Return"))
  def validate(json: Json, maxDepth: Int, maxObjectFields: Int): Option[Violation] =
    val work = mutable.ArrayDeque.empty[(Json, Int)]
    def push(j: Json, depth: Int): Unit = j match
      case _: Json.Obj | _: Json.Arr => work.append((j, depth))
      case _ => ()
    push(json, 1)
    while work.nonEmpty do
      val (node, depth) = work.removeLast()
      if depth > maxDepth then return Some(Violation.TooDeep(maxDepth))
      node match
        case Json.Obj(fields) =>
          if fields.length > maxObjectFields then
            return Some(Violation.TooManyFields(maxObjectFields))
          fields.foreach((_, v) => push(v, depth + 1))
        case Json.Arr(elems) =>
          elems.foreach(push(_, depth + 1))
        case _ => ()
    None

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
