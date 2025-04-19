package com.tjclp.fastmcp

import zio.json.*
import org.scalatest.Assertion
import org.scalatest.matchers.should.Matchers

/** Small utility used by multiple codec specs to assert that a value `A` survives a ZIO‑JSON
  * round‑trip intact.
  */
object TestJsonCheck extends Matchers {

  /** Serialises the given value to JSON and immediately decodes it back, asserting that the result
    * equals the original value.
    */
  def roundTrip[A: JsonEncoder: JsonDecoder](a: A): Assertion =
    a.toJson.fromJson[A] shouldBe Right(a)
}
