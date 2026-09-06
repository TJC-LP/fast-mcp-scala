package com.tjclp.fastmcp.server.manager

import scala.util.Random
import scala.util.matching.Regex

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*

import com.tjclp.fastmcp.core.{ResourceArgument, ResourceDefinition}

/** [[ResourceTemplatePattern]] (TJC-2295 / F2): semantics table, registration errors, the
  * greedy-regex equivalence property, and the linear-time (ReDoS) guarantee — measured on inputs
  * that reach the separator-binding loop and fail LATE, not on the segment-count short-circuit.
  */
class UriTemplateMatcherTest extends AnyFunSuite with Matchers:

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private def m(template: String, uri: String): Option[Map[String, String]] =
    ResourceTemplatePattern(template).matches(uri)

  private def timed[A](body: => A): (A, Long) =
    val start = java.lang.System.nanoTime()
    val result = body
    (result, (java.lang.System.nanoTime() - start) / 1_000_000L)

  // ---- semantics ----

  test("single-placeholder and per-segment placeholders") {
    m("users://{id}/profile", "users://123/profile") shouldBe Some(Map("id" -> "123"))
    m("items://{cat}/{itemId}", "items://books/xyz-987") shouldBe Some(
      Map("cat" -> "books", "itemId" -> "xyz-987")
    )
    m("repos://{owner-name}/{repo_name}", "repos://tjc-lp/fast_mcp") shouldBe Some(
      Map("owner-name" -> "tjc-lp", "repo_name" -> "fast_mcp")
    )
    m("users://{id}/profile", "users://123/profile/extra") shouldBe None
    m("users://{id}/profile", "users://123") shouldBe None
  }

  test("several placeholders in one segment bind separators to their last occurrence (greedy)") {
    m("x://{a}-{b}", "x://1-2-3") shouldBe Some(Map("a" -> "1-2", "b" -> "3"))
    m("x://{name}.{ext}", "x://archive.tar.gz") shouldBe Some(
      Map("name" -> "archive.tar", "ext" -> "gz")
    )
    m("d://{y}-{m}-{d}", "d://2026-09-04") shouldBe Some(
      Map("y" -> "2026", "m" -> "09", "d" -> "04")
    )
    m("x://v{n}.json", "x://v12.json") shouldBe Some(Map("n" -> "12"))
    m("x://{a}.{b}.{c}", "x://a.b.c.d") shouldBe Some(Map("a" -> "a.b", "b" -> "c", "c" -> "d"))
  }

  test("variables must be non-empty") {
    m("x://{a}-{b}", "x://-2") shouldBe None
    m("x://{a}-{b}", "x://1-") shouldBe None
    m("x://{a}-{b}", "x://-") shouldBe None
    m("x://{a}", "x://") shouldBe None
    m("x://v{n}.json", "x://v.json") shouldBe None
  }

  test("literal text is literal (no regex metacharacters)") {
    m("f://{id}.txt", "f://1Xtxt") shouldBe None
    m("f://{id}.txt", "f://1.txt") shouldBe Some(Map("id" -> "1"))
    m("p://{id}(1)", "p://7(1)") shouldBe Some(Map("id" -> "7"))
    m("p://{id}(1)", "p://71") shouldBe None
    m("q://{id}$", "q://a$") shouldBe Some(Map("id" -> "a"))
  }

  test("a zero-placeholder template matches only itself") {
    val p = ResourceTemplatePattern("static://exact/path")
    p.isTemplate shouldBe false
    p.matches("static://exact/path") shouldBe Some(Map.empty)
    p.matches("static://exact/path2") shouldBe None
    p.matches("static://exact") shouldBe None
  }

  test("equality is by pattern text") {
    ResourceTemplatePattern("a://{x}") shouldBe ResourceTemplatePattern("a://{x}")
    ResourceTemplatePattern("a://{x}").hashCode shouldBe ResourceTemplatePattern("a://{x}").hashCode
    ResourceTemplatePattern("a://{x}") should not be ResourceTemplatePattern("a://{y}")
    ResourceTemplatePattern("a://{x}").toString shouldBe "ResourceTemplatePattern(a://{x})"
    ResourceTemplatePattern("a://{x}/{y}").paramNames shouldBe List("x", "y")
  }

  // ---- registration errors ----

  test("invalid templates are rejected at parse time") {
    ResourceTemplatePattern.parse("x://{a}{b}").isLeft shouldBe true
    ResourceTemplatePattern.parse("x://{a}/{a}").isLeft shouldBe true
    ResourceTemplatePattern.parse("x://{a").isLeft shouldBe true
    ResourceTemplatePattern.parse("x://a}").isLeft shouldBe true
    ResourceTemplatePattern.parse("x://{a/b}").isLeft shouldBe true
    ResourceTemplatePattern.parse("x://{{a}}").isLeft shouldBe true
    ResourceTemplatePattern.parse("x://{}").isLeft shouldBe true
    ResourceTemplatePattern.parse("x://{a}-{b}").isRight shouldBe true
    val _ = an[IllegalArgumentException] should be thrownBy ResourceTemplatePattern("x://{a}{b}")
    ResourceTemplatePattern.parse("x://{a}{b}").left.toOption.get should include("adjacent")
    ResourceTemplatePattern.parse("x://{a}/{a}").left.toOption.get should include("more than once")
  }

  test(
    "ResourceManager.addTemplateResource surfaces an invalid template as ResourceRegistrationError"
  ) {
    val rm = new ResourceManager[Any]
    val ex = runUnsafe(
      rm.addTemplateResource(
        "x://{a}{b}",
        _ => ZIO.succeed("nope"),
        ResourceDefinition(
          "x://{a}{b}",
          None,
          None,
          isTemplate = true,
          arguments = Some(
            List(
              ResourceArgument("a", None, required = true),
              ResourceArgument("b", None, required = true)
            )
          )
        )
      ).either
    ).swap.getOrElse(fail("registration unexpectedly succeeded"))
    ex shouldBe a[ResourceRegistrationError]
    ex.getMessage should include("Failed to register resource template")
    Option(ex.getCause).map(_.getMessage).getOrElse("") should include("adjacent")
    rm.listTemplateDefinitions() shouldBe Nil
  }

  // ---- greedy-regex equivalence ----

  /** The former construction — `{name}` → `([^/]+)`, anchored — but with literals `Regex.quote`d.
    */
  private def legacyMatcher(template: String): String => Option[Map[String, String]] =
    val placeholder = """\{([^{}]+)\}""".r
    val names = placeholder.findAllMatchIn(template).map(_.group(1)).toList
    val sb = new StringBuilder("^")
    var last = 0
    placeholder.findAllMatchIn(template).foreach { mt =>
      sb.append(Regex.quote(template.substring(last, mt.start))).append("([^/]+)")
      last = mt.end
    }
    sb.append(Regex.quote(template.substring(last))).append("$")
    val regex = new Regex(sb.result())
    uri =>
      regex
        .findFirstMatchIn(uri)
        .map(mt => names.zipWithIndex.map((n, i) => n -> mt.group(i + 1)).toMap)

  test("matches equals the former greedy regex (with quoted literals) on randomised inputs") {
    val templates = List(
      "x://{a}-{b}",
      "x://{a}-{b}-{c}",
      "x://{a}.{b}-{c}",
      "x://{a}--{b}-{c}",
      "x://v{n}.json",
      "x://{a}aaab{b}",
      "x://{a}/{b}.{c}",
      "x://{a}b{c}"
    )
    val alphabet = Array('a', '-', '.', 'b')
    val rnd = new Random(20260904L)
    templates.foreach { template =>
      val compiled = ResourceTemplatePattern(template)
      val legacy = legacyMatcher(template)
      var matched = 0
      (1 to 2000).foreach { _ =>
        val len = 1 + rnd.nextInt(12)
        val tail = Array.fill(len)(alphabet(rnd.nextInt(alphabet.length))).mkString
        val uri = "x://" + (if rnd.nextInt(8) == 0 then tail.replaceFirst("-", "/") else tail)
        withClue(s"$template on '$uri'") {
          compiled.matches(uri) shouldBe legacy(uri)
        }
        if compiled.matches(uri).isDefined then matched += 1
      }
      // Targeted inputs that random text rarely hits.
      List("x://v1.json", "x://vaaa.json", "x://aaaab", "x://aaaabaaab", "x://aaaaba", "x://a/b.c")
        .foreach { uri =>
          withClue(s"$template on '$uri'") { compiled.matches(uri) shouldBe legacy(uri) }
        }
      withClue(s"$template never matched — property vacuous") {
        (matched > 0 || template.contains("json") || template.contains("{a}/{b}")) shouldBe true
      }
    }
  }

  // ---- ReDoS / linearity ----

  private def registerAll(rm: ResourceManager[Any], templates: List[String]): Unit =
    templates.foreach { template =>
      val names = ResourceTemplatePattern(template).paramNames
      runUnsafe(
        rm.addTemplateResource(
          template,
          params => ZIO.succeed(params.toString),
          ResourceDefinition(
            template,
            None,
            None,
            isTemplate = true,
            arguments = Some(names.map(n => ResourceArgument(n, None, required = true)))
          )
        )
      )
    }

  test(
    "100 KB adversarial URIs are matched in milliseconds, directly and through ResourceManager"
  ) {
    val templates = List("x://{a}-{b}", "x://{a}.{b}.{c}", "x://{a}aaab{b}", "x://{id}/profile")
    val rm = new ResourceManager[Any]
    registerAll(rm, templates)
    val aaa = "a" * 100_000

    // Warm-up on a small input.
    val _ = ResourceTemplatePattern("x://{a}-{b}").matches("x://aaa-b")

    val cases: List[(String, String, Option[Map[String, String]])] = List(
      // no separator anywhere: one full lastIndexOf scan
      ("x://{a}-{b}", "x://" + aaa, None),
      // separator only at index 0: idx < 1 → V1 empty → None
      ("x://{a}-{b}", "x://-" + aaa, None),
      // the finding's original input — segment-count short-circuit
      ("x://{a}-{b}", "x://" + "a-" * 50_000 + "/", None),
      // one separator, two needed: second lastIndexOf over the whole left window
      ("x://{a}.{b}.{c}", "x://" + "a" * 99_999 + ".", None),
      // matches — both windows exercised; last-occurrence binding
      (
        "x://{a}.{b}.{c}",
        "x://" + "a." * 50_000 + "b",
        Some(Map("a" -> ("a." * 49_998 + "a"), "b" -> "a", "c" -> "b"))
      ),
      ("x://{a}.{b}.{c}", "x://" + "a." * 50_000 + "/", None),
      // self-overlapping literal worst case
      ("x://{a}aaab{b}", "x://" + aaa, None)
    )

    cases.foreach { (template, uri, expected) =>
      val (direct, directMs) = timed(ResourceTemplatePattern(template).matches(uri))
      withClue(s"$template direct") {
        val _ = direct shouldBe expected
        directMs should be < 100L
      }
    }

    // Through the manager, every registered template is tried against each URI.
    cases.foreach { (template, uri, expected) =>
      val (found, ms) = timed(rm.findMatchingTemplate(uri))
      withClue(s"$template via ResourceManager") {
        ms should be < 100L
        expected match
          case Some(params) =>
            found.map(_._1.pattern) shouldBe Some(template)
            found.map(_._4) shouldBe Some(params)
          case None => found shouldBe None
      }
    }
  }

  test("registered templates are compiled once (findMatchingTemplate uses the stored pattern)") {
    val rm = new ResourceManager[Any]
    registerAll(rm, List("x://{a}-{b}"))
    val Some((pattern, definition, _, params)) = rm.findMatchingTemplate("x://1-2"): @unchecked
    pattern.pattern shouldBe "x://{a}-{b}"
    definition.uri shouldBe "x://{a}-{b}"
    params shouldBe Map("a" -> "1", "b" -> "2")
    rm.getTemplateResourceHandler("x://{a}-{b}") shouldBe defined
    rm.listTemplateDefinitions().map(_.uri) shouldBe List("x://{a}-{b}")
    rm.extractTemplateParams("x://{a}-{b}", "x://1-2") shouldBe Some(Map("a" -> "1", "b" -> "2"))
    rm.extractTemplateParams("x://{a}{b}", "x://12") shouldBe None // invalid template → no match
  }
