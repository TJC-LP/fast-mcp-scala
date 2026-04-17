package com.tjclp.fastmcp.core

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.json.*

class ToolExampleJsonCodecTest extends AnyFlatSpec with Matchers {

  "ToolExample JsonCodec" should "round‑trip with Some values" in {
    val ex = ToolExample(Some("ex1"), Some("desc1"))
    ex.toJson.fromJson[ToolExample] shouldBe Right(ex)
  }

  it should "round‑trip with None values" in {
    val ex = ToolExample(None, None)
    ex.toJson.fromJson[ToolExample] shouldBe Right(ex)
  }
}
