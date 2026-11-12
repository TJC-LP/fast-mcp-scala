package com.tjclp.fastmcp.core.skills

import java.nio.charset.StandardCharsets

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.json.ast.Json

import com.tjclp.fastmcp.core.wire.SkillFrontmatter

/** `SKILL.md` → verbatim frontmatter JSON: delimiters, encodings, the YAML-to-JSON scalar policy,
  * the Agent Skills field rules, and the parser-bomb defences.
  */
class SkillFrontmatterReaderTest extends AnyFunSuite with Matchers:

  private def parse(text: String): SkillFrontmatter =
    SkillFrontmatterReader.parseText(text).fold(e => fail(s"$e\n---\n$text"), _.frontmatter)

  private def reject(text: String): String =
    SkillFrontmatterReader.parseText(text).fold(identity, p => fail(s"expected rejection, got ${p.frontmatter.toJson}"))

  private def md(fields: String): String = s"---\n$fields\n---\n\n# Body\n"

  test("minimal frontmatter yields name and description verbatim") {
    val fm = parse(md("name: pdf-processing\ndescription: Extract, fill, and assemble PDF documents"))
    fm.name shouldBe "pdf-processing"
    fm.description shouldBe "Extract, fill, and assemble PDF documents"
    fm.toJson shouldBe Json.Obj(
      "name" -> Json.Str("pdf-processing"),
      "description" -> Json.Str("Extract, fill, and assemble PDF documents")
    )
  }

  test("quoted scalars, colons in descriptions, literal and folded blocks, nested objects, sequences, booleans, nulls, numbers") {
    val fm = parse(
      md(
        """name: "quoted-name"
          |description: 'Use when: the user says "reconcile", e.g. 12:30 or 13:00.'
          |license: Apache-2.0
          |compatibility: >
          |  Requires git,
          |  and jq.
          |notes: |
          |  line one
          |  line two
          |metadata:
          |  author: acme
          |  version: "1.0"
          |tags:
          |  - one
          |  - two
          |flags: [a, "b", 3]
          |experimental: true
          |disabled: false
          |nothing: null
          |tilde: ~
          |empty:
          |count: 42
          |ratio: 0.5
          |negative: -7
          |hexa: 0x1f
          |octal: 0o17
          |big: 123456789012345678901234567890
          |quotedTrue: "true"
          |quotedNumber: '007'
          |""".stripMargin
      )
    )
    fm.name shouldBe "quoted-name"
    fm.description shouldBe """Use when: the user says "reconcile", e.g. 12:30 or 13:00."""
    fm.get("compatibility") shouldBe Some(Json.Str("Requires git, and jq.\n"))
    fm.get("notes") shouldBe Some(Json.Str("line one\nline two\n"))
    fm.get("metadata") shouldBe Some(Json.Obj("author" -> Json.Str("acme"), "version" -> Json.Str("1.0")))
    fm.get("tags") shouldBe Some(Json.Arr(Json.Str("one"), Json.Str("two")))
    fm.get("flags") shouldBe Some(Json.Arr(Json.Str("a"), Json.Str("b"), Json.Num(3)))
    fm.get("experimental") shouldBe Some(Json.Bool(true))
    fm.get("disabled") shouldBe Some(Json.Bool(false))
    fm.get("nothing") shouldBe Some(Json.Null)
    fm.get("tilde") shouldBe Some(Json.Null)
    fm.get("empty") shouldBe Some(Json.Null)
    fm.get("count") shouldBe Some(Json.Num(42))
    fm.get("ratio") shouldBe Some(Json.Num(new java.math.BigDecimal("0.5")))
    fm.get("negative") shouldBe Some(Json.Num(-7))
    fm.get("hexa") shouldBe Some(Json.Num(31))
    fm.get("octal") shouldBe Some(Json.Num(15))
    fm.get("big") shouldBe Some(Json.Num(new java.math.BigDecimal("123456789012345678901234567890")))
    fm.get("quotedTrue") shouldBe Some(Json.Str("true"))
    fm.get("quotedNumber") shouldBe Some(Json.Str("007"))
    // Source order is preserved.
    fm.fields.map(_._1).take(3) shouldBe zio.Chunk("name", "description", "license")
  }

  test("backslashes and multi-line plain scalars survive scala-yaml's tokenizer escaping") {
    val fm = parse(md("name: x\ndescription: d\npath: C:\\new\\dir\\n\nmulti: line one\n  line two\nfolded: >-\n  a\\b\n  c\nnoback: 'C:\\new'"))
    fm.get("path") shouldBe Some(Json.Str("C:\\new\\dir\\n"))
    fm.get("multi") shouldBe Some(Json.Str("line one line two"))
    fm.get("folded") shouldBe Some(Json.Str("a\\b c"))
    fm.get("noback") shouldBe Some(Json.Str("C:\\new"))
  }

  test("unknown fields pass through, including future Agent Skills fields") {
    val fm = parse(md("name: x\ndescription: d\nfuture-field:\n  deep:\n    er: [1, 2]\nallowed-tools: Bash(git:*) Read"))
    fm.get("future-field") shouldBe Some(Json.Obj("deep" -> Json.Obj("er" -> Json.Arr(Json.Num(1), Json.Num(2)))))
    fm.get("allowed-tools") shouldBe Some(Json.Str("Bash(git:*) Read"))
  }

  test("CRLF line endings and a leading BOM are accepted; the body is untouched") {
    val text = "\uFEFF---\r\nname: crlf-skill\r\ndescription: Windows authored.\r\nnotes: |\r\n  a\r\n  b\r\n---\r\n\r\n# Body\r\n"
    val parsed = SkillFrontmatterReader.parse(text.getBytes(StandardCharsets.UTF_8)).fold(fail(_), identity)
    parsed.frontmatter.name shouldBe "crlf-skill"
    // The line break before the closing `---` is not part of the block (documented policy), so a
    // literal scalar that ends the frontmatter has no trailing newline.
    parsed.frontmatter.get("notes") shouldBe Some(Json.Str("a\nb"))
    parsed.rawFrontmatter should include("\r\n")
  }

  test("Unicode names in descriptions and values survive") {
    val fm = parse(md("name: unicode\ndescription: Grüße aus 東京 🎉 — em dash\nmetadata:\n  owner: Ærøskøbing"))
    fm.description shouldBe "Grüße aus 東京 🎉 — em dash"
    fm.get("metadata") shouldBe Some(Json.Obj("owner" -> Json.Str("Ærøskøbing")))
  }

  test("invalid UTF-8 bytes are rejected, never repaired") {
    val bytes = "---\nname: x\ndescription: d".getBytes(StandardCharsets.UTF_8) ++ Array(0xc0.toByte, 0x80.toByte) ++
      "\n---\n".getBytes(StandardCharsets.UTF_8)
    SkillFrontmatterReader.parse(bytes).left.map(_.contains("UTF-8")) shouldBe Left(true)
  }

  test("delimiter rules: must start with ---, must be closed, only --- closes") {
    reject("name: x\ndescription: d\n") should include("must begin")
    reject("---\nname: x\ndescription: d\n") should include("not closed")
    reject("---\nname: x\ndescription: d\n...\n") should include("not closed")
    reject("--- \nname: x\ndescription: d\n---\n") should include("must begin")
    reject("---\nname: x\ndescription: d\n---x\n") should include("not closed")
    reject("") should include("must begin")
    // An empty block is not a mapping.
    reject("---\n---\n") should include("empty")
  }

  test("name and description are required strings with the Agent Skills constraints") {
    reject(md("description: d")) should include("name")
    reject(md("name: x")) should include("description")
    reject(md("name: 42\ndescription: d")) should include("name must be a string")
    reject(md("name: x\ndescription: [a]")) should include("description")
    reject(md("name: Bad-Name\ndescription: d")) should include("lowercase")
    reject(md("name: -x\ndescription: d")) should include("hyphen")
    reject(md("name: a--b\ndescription: d")) should include("consecutive")
    reject(md(s"name: ${"a" * 65}\ndescription: d")) should include("64")
    reject(md(s"name: x\ndescription: ${"d" * 1025}")) should include("1024")
    reject(md("name: x\ndescription: \"\"")) should include("non-empty")
    reject(md(s"name: x\ndescription: d\ncompatibility: ${"c" * 501}")) should include("500")
    reject(md("name: x\ndescription: d\nmetadata: [a]")) should include("metadata must be a mapping")
    reject(md("name: x\ndescription: d\nmetadata:\n  version: 1.0")) should include("must be a string")
    reject(md("name: x\ndescription: d\nlicense: [MIT]")) should include("license")
    reject(md("name: x\ndescription: d\nallowed-tools:\n  - Read")) should include("allowed-tools")
  }

  test("duplicate keys are rejected at every level") {
    reject(md("name: x\ndescription: d\nname: y")) should include("duplicate")
    reject(md("name: x\ndescription: d\nmetadata:\n  a: \"1\"\n  a: \"2\"")) should include("duplicate")
  }

  test("tags, anchors and aliases are rejected; no YAML feature turns data into a graph or a type hint") {
    reject(md("name: x\ndescription: !!str d")) should include("tags")
    reject(md("name: x\ndescription: d\nextra: !custom v")) should include("tags")
    reject(md("name: x\ndescription: d\na: &anchor v\nb: *anchor")) should include("anchors")
    reject(md("name: x\ndescription: d\nb: *nowhere")) should include("alias")
  }

  test("non-JSON-representable numbers, non-scalar keys, multiple documents and non-mapping roots are rejected") {
    reject(md("name: x\ndescription: d\ninf: .inf")) should include("no JSON representation")
    reject(md("name: x\ndescription: d\nnan: .nan")) should include("no JSON representation")
    SkillFrontmatterReader.parseText(md("name: x\ndescription: d\n? [a, b]\n: v")).isLeft shouldBe true // complex keys
    // The first closing `---` ends the block; a later `---` in the body is Markdown, not YAML.
    parse("---\nname: x\ndescription: d\n---\nmore: stuff\n---\n").get("more") shouldBe None
    reject("---\n- a\n- b\n---\n") should include("mapping")
    reject("---\njust a scalar\n---\n") should include("mapping")
  }

  test("resource exhaustion: oversized block, deep nesting and node floods are refused before they cost anything") {
    val small = SkillFrontmatterReader.Limits(maxBytes = 64, maxDepth = 3, maxNodes = 100)
    SkillFrontmatterReader.parseText(md("name: x\ndescription: " + "d" * 100), small).left.map(_.contains("exceeds 64 bytes")) shouldBe Left(true)
    val deep = md("name: x\ndescription: d\na:\n  b:\n    c:\n      d: 1")
    SkillFrontmatterReader.parseText(deep, small).left.map(_.contains("nesting")) shouldBe Left(true)
    val wide = md("name: x\ndescription: d\nlist: [1, 2, 3, 4, 5, 6, 7, 8, 9]")
    SkillFrontmatterReader.parseText(wide, SkillFrontmatterReader.Limits(maxNodes = 8)).left.map(_.contains("nodes")) shouldBe Left(true)
    // Defaults: a 200-deep flow bomb is rejected without a stack overflow.
    val bomb = md("name: x\ndescription: d\nz: " + "[" * 200 + "]" * 200)
    SkillFrontmatterReader.parseText(bomb).isLeft shouldBe true
  }

  test("the parsed frontmatter compares by content, not key order") {
    val a = parse(md("name: x\ndescription: d\nlicense: MIT"))
    val b = parse(md("license: MIT\nname: x\ndescription: d"))
    a.sameContentAs(b) shouldBe true
    SkillVerifier.frontmatterDifferences(a.toJson, b.toJson) shouldBe Nil
    val c = parse(md("name: x\ndescription: d\nlicense: BSD"))
    SkillVerifier.frontmatterDifferences(a.toJson, c.toJson) shouldBe List("license: file=\"MIT\" entry=\"BSD\"")
  }
