package com.tjclp.fastmcp.core.wire

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.Chunk
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.Cursor

/** Codec round trips and decode-time validation of the Skills wire shapes. */
class SkillsWireCodecTest extends AnyFunSuite with Matchers:

  private val digest = "sha256:" + "ab" * 32

  private val fm = SkillFrontmatter
    .fromFields(
      Chunk(
        "name" -> Json.Str("refunds"),
        "description" -> Json.Str("Process refunds"),
        "license" -> Json.Str("Apache-2.0"),
        "metadata" -> Json.Obj("version" -> Json.Str("2.1.0")),
        "future" -> Json.Arr(Json.Num(1), Json.Null, Json.Bool(true))
      )
    )
    .fold(fail(_), identity)

  test("static entry round-trips, preserving unknown frontmatter fields and source key order") {
    val skill = Skill(
      uri = "skill://acme/billing/refunds/SKILL.md",
      frontmatter = fm,
      resources = SkillResources.Static(
        List(
          SkillResource("skill://acme/billing/refunds/SKILL.md", digest, 3871L),
          SkillResource("skill://acme/billing/refunds/examples/email.md", digest, 0L)
        )
      )
    )
    val text = skill.toJson
    text should include(""""frontmatter":{"name":"refunds","description":"Process refunds","license":"Apache-2.0","metadata":{"version":"2.1.0"},"future":[1,null,true]}""")
    text should include(""""size":3871""")
    text.fromJson[Skill] shouldBe Right(skill)
  }

  test("dynamic entry round-trips as the string \"dynamic\"") {
    val skill = Skill("skill://reports/daily/SKILL.md", fm, SkillResources.Dynamic)
    skill.toJson should include(""""resources":"dynamic"""")
    skill.toJson.fromJson[Skill] shouldBe Right(skill)
  }

  test("resources: missing, other strings, objects, null and booleans are invalid") {
    def entry(resources: String): String =
      s"""{"uri":"skill://a/x/SKILL.md","frontmatter":{"name":"x","description":"d"}$resources}"""
    entry("").fromJson[Skill].isLeft shouldBe true
    entry(""","resources":"static"""").fromJson[Skill].left.map(_.contains("dynamic")) shouldBe Left(true)
    entry(""","resources":{}""").fromJson[Skill].isLeft shouldBe true
    entry(""","resources":null""").fromJson[Skill].isLeft shouldBe true
    entry(""","resources":true""").fromJson[Skill].isLeft shouldBe true
    entry(""","resources":[]""").fromJson[Skill].map(_.resources) shouldBe Right(SkillResources.Static(Nil))
  }

  test("SkillResource: fractional, negative, overflowing sizes and malformed digests are invalid") {
    def res(size: String, d: String = digest): String = s"""{"uri":"skill://a/x/SKILL.md","digest":"$d","size":$size}"""
    res("12").fromJson[SkillResource] shouldBe Right(SkillResource("skill://a/x/SKILL.md", digest, 12L))
    res("0").fromJson[SkillResource].map(_.size) shouldBe Right(0L)
    res("9223372036854775807").fromJson[SkillResource].map(_.size) shouldBe Right(Long.MaxValue)
    res("12.0").fromJson[SkillResource].map(_.size) shouldBe Right(12L) // integral value written as a decimal
    res("1e3").fromJson[SkillResource].map(_.size) shouldBe Right(1000L)
    res("12.5").fromJson[SkillResource].isLeft shouldBe true
    res("-1").fromJson[SkillResource].isLeft shouldBe true
    res("9223372036854775808").fromJson[SkillResource].isLeft shouldBe true
    res("1e30").fromJson[SkillResource].isLeft shouldBe true
    res("\"12\"").fromJson[SkillResource].isLeft shouldBe true
    res("12", "sha256:" + "AB" * 32).fromJson[SkillResource].isLeft shouldBe true
    res("12", "sha256:" + "ab" * 31).fromJson[SkillResource].isLeft shouldBe true
    res("12", "sha1:" + "ab" * 32).fromJson[SkillResource].isLeft shouldBe true
    """{"uri":"skill://a/x/SKILL.md","digest":"sha256:%s"}""".format("ab" * 32).fromJson[SkillResource].isLeft shouldBe true
    """{"digest":"sha256:%s","size":1}""".format("ab" * 32).fromJson[SkillResource].isLeft shouldBe true
  }

  test("frontmatter requires non-empty string name and description") {
    """{"name":"x"}""".fromJson[SkillFrontmatter].isLeft shouldBe true
    """{"name":"x","description":""}""".fromJson[SkillFrontmatter].isLeft shouldBe true
    """{"name":1,"description":"d"}""".fromJson[SkillFrontmatter].isLeft shouldBe true
    """[]""".fromJson[SkillFrontmatter].isLeft shouldBe true
    """{"name":"x","description":"d","name":"y"}""".fromJson[SkillFrontmatter].isLeft shouldBe true
    """{"description":"d","name":"x"}""".fromJson[SkillFrontmatter].map(_.name) shouldBe Right("x")
  }

  test("list/get/directory results carry the prescribed fields and defaults") {
    val entry = Skill("skill://a/x/SKILL.md", fm, SkillResources.Dynamic)
    val list = ListSkillsResult(List(entry), nextCursor = Some(Cursor("c1")))
    val listJson = list.toJsonAST.getOrElse(Json.Null)
    listJson.asInstanceOf[Json.Obj].fields.map(_._1).toList shouldBe List("skills", "nextCursor", "ttlMs", "cacheScope")
    list.toJson should include(""""ttlMs":0,"cacheScope":"private"""")
    list.toJson.fromJson[ListSkillsResult] shouldBe Right(list)

    val get = GetSkillResult(entry, ttlMs = 300000L, cacheScope = CacheScope.Public)
    get.toJson should include(""""ttlMs":300000,"cacheScope":"public"""")
    get.toJson should not include "nextCursor"
    get.toJson.fromJson[GetSkillResult] shouldBe Right(get)

    val dir = ReadResourceDirectoryResult(
      List(Resource(uri = "skill://a/x/templates", name = "templates", mimeType = Some(Skills.DirectoryMimeType)))
    )
    dir.toJson shouldBe """{"resources":[{"uri":"skill://a/x/templates","name":"templates","mimeType":"inode/directory"}]}"""
    dir.toJson.fromJson[ReadResourceDirectoryResult] shouldBe Right(dir)

    """{"uri":"skill://a/x"}""".fromJson[ReadResourceDirectoryRequestParams] shouldBe
      Right(ReadResourceDirectoryRequestParams("skill://a/x"))
    """{"uri":"skill://a/x","cursor":"abc"}""".fromJson[ReadResourceDirectoryRequestParams].map(_.cursor.map(_.value)) shouldBe
      Right(Some("abc"))
    """{}""".fromJson[GetSkillRequestParams].isLeft shouldBe true
  }
