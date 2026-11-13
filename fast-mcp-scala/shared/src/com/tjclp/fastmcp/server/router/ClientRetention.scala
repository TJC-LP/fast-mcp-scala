package com.tjclp.fastmcp.server.router

import scala.annotation.tailrec

import zio.json.ast.Json

/** The budget for what a durable legacy session keeps from its client's `initialize`.
  *
  * A stored session holds the decoded `clientInfo` and `capabilities` for as long as it lives
  * ([[Session.setClientInfo]]), and both have open members (`icons`, `experimental`, `extensions`,
  * the per-capability objects). Their size is therefore bounded here, by fixed constants that do
  * not depend on `maxRequestBodyBytes` or the frame limits: an `initialize` over the budget is
  * refused with `-32602` before anything is retained.
  *
  * The two `params` members are weighed as they arrive, before decoding. What is decoded from them
  * is a subset of what they hold (unknown members are dropped), so the weight is an upper bound on
  * what the session keeps:
  *   - '''values''': every JSON value in the two members — each object, array, string, number,
  *     boolean and null, the two members themselves included;
  *   - '''characters''': member names, string values and number digits, in UTF-16 code units.
  *
  * A typical `initialize` weighs a few dozen values and a few hundred characters.
  */
private[fastmcp] object ClientRetention:

  /** Most JSON values `clientInfo` and `capabilities` may hold together. */
  val MaxValues: Int = 256

  /** Most characters of member names, strings and number digits the two may hold together. */
  val MaxChars: Int = 16 * 1024

  private val Retained: Set[String] = Set("clientInfo", "capabilities")

  /** `None` when the `initialize` params are within the budget, else what to tell the client. A
    * member that appears twice is weighed twice: whichever one the decoder keeps is covered.
    */
  def overBudget(params: Json): Option[String] =
    params match
      case Json.Obj(fields) =>
        val members = fields.iterator.collect { case (name, value) if Retained(name) => value }
        val (values, chars) = weigh(members :: Nil, 0, 0L)
        if values > MaxValues then
          Some(s"clientInfo and capabilities together hold more than $MaxValues JSON values")
        else if chars > MaxChars then
          Some(
            s"clientInfo and capabilities together hold more than $MaxChars characters of names, strings and numbers"
          )
        else None
      case _ => None

  /** Weigh the pending values, stopping at the first step past either bound — a frame-sized member
    * costs a few hundred steps, not a walk of the whole frame. Depth is bounded by the frame's
    * `limits.maxDepth`; the explicit stack keeps the walk independent of it anyway.
    */
  @tailrec
  private def weigh(pending: List[Iterator[Json]], values: Int, chars: Long): (Int, Long) =
    if values > MaxValues || chars > MaxChars then (values, chars)
    else
      pending match
        case Nil => (values, chars)
        case current :: rest =>
          if !current.hasNext then weigh(rest, values, chars)
          else
            current.next() match
              case Json.Obj(fields) =>
                val names = fields.foldLeft(0L)((sum, field) => sum + field._1.length)
                weigh(fields.iterator.map(_._2) :: pending, values + 1, chars + names)
              case Json.Arr(elements) => weigh(elements.iterator :: pending, values + 1, chars)
              case Json.Str(text) => weigh(pending, values + 1, chars + text.length)
              case Json.Num(number) => weigh(pending, values + 1, chars + digits(number))
              case _ => weigh(pending, values + 1, chars)

  /** The decimal digits a number keeps, from the bit length of its unscaled value (about 3.3 bits a
    * digit, rounded up to one digit per 3 bits). Constant time: no digit string is rendered and no
    * power of ten is computed, however long the number is.
    */
  private def digits(number: java.math.BigDecimal): Long =
    1L + number.unscaledValue.bitLength / 3
