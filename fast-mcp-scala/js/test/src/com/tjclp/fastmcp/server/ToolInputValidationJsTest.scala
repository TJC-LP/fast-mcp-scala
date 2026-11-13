package com.tjclp.fastmcp.server

import org.scalatest.funsuite.AnyFunSuite
import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.core.JsonSchemaValidator
import com.tjclp.fastmcp.server.manager.{ToolArgumentError, ToolInputValidation, ToolRegistrationOptions}

/** Scala.js mirror of the JVM `ToolInputValidationTest` / `JsonSchemaValidatorTest`: the
  * advertised `inputSchema` is enforced before the handler on this platform too, by the same
  * shared validator (no regular expressions, no reflection, `java.math.BigDecimal` comparisons
  * only).
  */
class ToolInputValidationJsTest extends AnyFunSuite:

  object ValidatedTools:
    @SuppressWarnings(Array("org.wartremover.warts.Var"))
    var calls: Int = 0

    @Tool(name = Some("sort"))
    def sort(
        @Param(
          description = "Sort order",
          schema = Some("""{"type":"string","enum":["relevance","date"]}""")
        )
        order: String
    ): String =
      calls += 1
      s"sorted by $order"

    @Tool(name = Some("page"))
    def page(
        @Param(
          description = "Page size",
          schema = Some("""{"type":"integer","minimum":1,"maximum":100}""")
        )
        size: Int
    ): String =
      calls += 1
      s"page of $size"

  case class SizeArgs(size: Int)

  private val boundsSchema = ToolInputSchema.unsafeFromJsonString(
    """{"type":"object","properties":{"size":{"type":"integer","minimum":1,"maximum":100}},"required":["size"]}"""
  )

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }

  private def call(server: McpServer[Any], name: String, args: (String, Json)*): Either[Throwable, Any] =
    runUnsafe(server.toolManager.callTool(name, args.toMap, None).either)

  private def json(s: String): Json = s.fromJson[Json].fold(e => fail(e), identity)

  test("annotation path: enum and numeric bounds from @Param(schema) are enforced before the method") {
    val server = McpServer("AnnotatedJs")
    val _ = server.scanAnnotations[ValidatedTools.type]
    val before = ValidatedTools.calls

    val badEnum = call(server, "sort", "order" -> Json.Str("popularity"))
    assert(badEnum.left.toOption.exists(_.isInstanceOf[ToolArgumentError]), s"got $badEnum")
    assert(call(server, "page", "size" -> Json.Num(0)).isLeft)
    assert(call(server, "page", "size" -> Json.Num(101)).isLeft)
    assert(call(server, "page", "size" -> Json.Str("5")).isLeft)
    assert(ValidatedTools.calls == before, "a rejected call must not reach the method")

    assert(call(server, "sort", "order" -> Json.Str("date")) == Right("sorted by date"))
    assert(call(server, "page", "size" -> Json.Num(100)) == Right("page of 100"))
    assert(ValidatedTools.calls == before + 2)
  }

  test("McpTool.withSchema: bounds are enforced; the per-tool and server-wide switches turn it off") {
    @SuppressWarnings(Array("org.wartremover.warts.Var"))
    var ran = 0
    def tool = McpTool.withSchema[SizeArgs, String](name = "page", inputSchema = boundsSchema) { args =>
      ran += 1
      s"page of ${args.size}"
    }

    val enforced = McpServer("ContractJs")
    runUnsafe(enforced.tool(tool))
    assert(call(enforced, "page", "size" -> Json.Num(5000)).isLeft)
    assert(ran == 0)
    assert(call(enforced, "page", "size" -> Json.Num(7)).isRight)
    assert(ran == 1)
    assert(enforced.toolInputValidation == Map("page" -> ToolInputValidation(true, Nil)))

    val optedOut = McpServer("OptOutJs")
    runUnsafe(optedOut.tool(tool, ToolRegistrationOptions(validateInputSchema = false)))
    assert(call(optedOut, "page", "size" -> Json.Num(5000)).isRight)
    assert(optedOut.toolInputValidation == Map("page" -> ToolInputValidation(false, Nil)))

    val serverOff = McpServer("ServerOffJs", settings = McpServerSettings(validateToolInput = false))
    runUnsafe(serverOff.tool(tool))
    assert(call(serverOff, "page", "size" -> Json.Num(5000)).isRight)
    assert(serverOff.toolInputValidation == Map("page" -> ToolInputValidation(false, Nil)))
  }

  test("the validator behaves the same here: integers, bounds, lengths, combinators, $ref, reporting") {
    val v = JsonSchemaValidator.compile(
      json(
        """{"type":"object","properties":{
          |"n":{"type":"integer","minimum":-10,"maximum":10},
          |"s":{"type":"string","minLength":2,"maxLength":2},
          |"level":{"$ref":"#/$defs/level"},
          |"opt":{"anyOf":[{"type":"string","enum":["a","b"]},{"type":"null"}]},
          |"tags":{"type":"array","items":{"type":"string"},"maxItems":2,"uniqueItems":true},
          |"id":{"type":"string","pattern":"^[a-z]+$"}},
          |"required":["n"],"additionalProperties":false,
          |"$defs":{"level":{"enum":["low","high"]}}}""".stripMargin
      )
    )
    assert(v.unenforced == List("pattern", "uniqueItems"))

    def ok(value: String): Unit = assert(v.validate(json(value)).isEmpty, value)
    def bad(value: String): Unit = assert(v.validate(json(value)).nonEmpty, value)

    ok("""{"n":3}""")
    ok("""{"n":3.0}""")
    ok("""{"n":1e1}""")
    ok("""{"n":0,"s":"ab","level":"high","opt":null,"tags":["x","x"],"id":"NOT-MATCHED"}""")
    bad("{}")
    bad("""{"n":1.5}""")
    bad("""{"n":"3"}""")
    bad("""{"n":11}""")
    bad("""{"n":1,"s":"abc"}""")
    bad("""{"n":1,"level":"mid"}""")
    bad("""{"n":1,"opt":"c"}""")
    bad("""{"n":1,"tags":["a","b","c"]}""")
    bad("""{"n":1,"extra":true}""")

    // Exponents are compared, never expanded.
    assert(v.validate(Json.Obj("n" -> Json.Num(new java.math.BigDecimal("1e999999999")))).nonEmpty)
    assert(v.validate(Json.Obj("n" -> Json.Num(new java.math.BigDecimal("1e-999999999")))).nonEmpty)
    // One code point, two UTF-16 units: too short for minLength 2.
    assert(v.validate(Json.Obj("n" -> Json.Num(1), "s" -> Json.Str("\uD83D\uDE00"))).nonEmpty)
    assert(v.validate(Json.Obj("n" -> Json.Num(1), "s" -> Json.Str("\uD83D\uDE00\uD83D\uDE00"))).isEmpty)
    assert(v.validate(json("""{"n":"x"}""")) == Some("'n' must be integer, got a string"))
  }
