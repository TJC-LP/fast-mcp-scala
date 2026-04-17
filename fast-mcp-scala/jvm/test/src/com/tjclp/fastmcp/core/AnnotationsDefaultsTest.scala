package com.tjclp.fastmcp.core

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** These simple sanity checks assert that the compile‑time annotations expose sensible default
  * values at runtime. While the annotations are primarily consumed by macros, exercising them in
  * regular unit tests bumps coverage and protects against accidental changes to their parameter
  * lists / defaults.
  */
class AnnotationsDefaultsTest extends AnyFlatSpec with Matchers {

  "@Tool" should "provide expected defaults" in {
    val ann = new Tool()
    ann.name shouldBe None
    ann.description shouldBe None
    ann.examples shouldBe Nil
    ann.version shouldBe None
    ann.deprecated shouldBe false
    ann.deprecationMessage shouldBe None
    ann.tags shouldBe Nil
    ann.timeoutMillis shouldBe None
    ann.title shouldBe None
    ann.readOnlyHint shouldBe None
    ann.destructiveHint shouldBe None
    ann.idempotentHint shouldBe None
    ann.openWorldHint shouldBe None
    ann.returnDirect shouldBe None
  }

  "@Param" should "default required = true with empty examples and no schema override" in {
    val ann = new Param(description = "desc")
    ann.required shouldBe true
    ann.examples shouldBe Nil
    ann.schema shouldBe None
  }

  "@Resource" should "default optional params to None" in {
    val ann = new Resource(uri = "file:///tmp.txt")
    ann.name shouldBe None
    ann.description shouldBe None
    ann.mimeType shouldBe None
  }

  "@Prompt" should "default optional params to None" in {
    val ann = new Prompt()
    ann.name shouldBe None
    ann.description shouldBe None
  }
}
