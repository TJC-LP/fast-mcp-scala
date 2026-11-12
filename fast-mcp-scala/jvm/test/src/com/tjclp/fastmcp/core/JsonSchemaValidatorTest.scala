package com.tjclp.fastmcp.core

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.json.*
import zio.json.ast.Json

/** The built-in `inputSchema` validator: every enforced keyword, what is reported as unenforced,
  * the shape of the messages, and the cost bounds (no exponent expansion, no table keyed by client
  * input, one pass over the value).
  */
class JsonSchemaValidatorTest extends AnyFunSuite with Matchers:

  private def json(s: String): Json = s.fromJson[Json].fold(e => fail(s"bad test JSON: $e"), identity)

  private def validator(schema: String): JsonSchemaValidator = JsonSchemaValidator.compile(json(schema))

  private def accepts(schema: String, values: String*): Unit =
    val v = validator(schema)
    values.foreach(value => withClue(s"$schema <- $value: ")(v.validate(json(value)) shouldBe None))

  private def rejects(schema: String, values: String*): Unit =
    val v = validator(schema)
    values.foreach(value => withClue(s"$schema <- $value: ")(v.validate(json(value)) should not be None))

  // ---- type ----

  test("type: each JSON type name is enforced") {
    accepts("""{"type":"string"}""", "\"a\"", "\"\"")
    rejects("""{"type":"string"}""", "1", "true", "null", "{}", "[]")
    accepts("""{"type":"boolean"}""", "true", "false")
    rejects("""{"type":"boolean"}""", "\"true\"", "0", "null")
    accepts("""{"type":"null"}""", "null")
    rejects("""{"type":"null"}""", "0", "\"\"", "false")
    accepts("""{"type":"object"}""", "{}", """{"a":1}""")
    rejects("""{"type":"object"}""", "[]", "\"{}\"", "null")
    accepts("""{"type":"array"}""", "[]", "[1]")
    rejects("""{"type":"array"}""", "{}", "\"[]\"")
    accepts("""{"type":"number"}""", "1", "1.5", "-2e3")
    rejects("""{"type":"number"}""", "\"1\"", "true", "null")
  }

  test("type integer: integral values in any spelling, nothing else") {
    accepts("""{"type":"integer"}""", "0", "-7", "1.0", "100.00", "1e3", "12e0", "0.0")
    rejects("""{"type":"integer"}""", "1.5", "0.1", "1e-3", "100.01", "\"1\"", "true", "null")
  }

  test("type: a list of names accepts any of them") {
    accepts("""{"type":["string","null"]}""", "\"a\"", "null")
    rejects("""{"type":["string","null"]}""", "1", "[]")
    accepts("""{"type":["integer","string"]}""", "3", "\"x\"")
    rejects("""{"type":["integer","string"]}""", "3.5")
  }

  // ---- enum / const ----

  test("enum: only the listed values, compared as JSON") {
    accepts("""{"enum":["a","b"]}""", "\"a\"", "\"b\"")
    rejects("""{"enum":["a","b"]}""", "\"c\"", "\"A\"", "\"a \"", "1", "null", "[\"a\"]")
    accepts("""{"enum":[1,"x",null,true]}""", "1", "1.0", "\"x\"", "null", "true")
    rejects("""{"enum":[1,"x",null,true]}""", "2", "\"y\"", "false")
    accepts("""{"enum":[{"a":1,"b":[1,2]}]}""", """{"b":[1,2],"a":1.0}""")
    rejects("""{"enum":[{"a":1,"b":[1,2]}]}""", """{"a":1}""", """{"a":1,"b":[2,1]}""", """{"a":1,"a":1}""")
    rejects("""{"enum":[]}""", "\"a\"", "null")
  }

  test("const: exactly that value") {
    accepts("""{"const":"v1"}""", "\"v1\"")
    rejects("""{"const":"v1"}""", "\"v2\"", "1")
    accepts("""{"const":2}""", "2", "2.0")
    rejects("""{"const":2}""", "3")
    rejects("""{"enum":["a","b"],"const":"a"}""", "\"b\"")
    accepts("""{"enum":["a","b"],"const":"a"}""", "\"a\"")
  }

  // ---- numbers ----

  test("minimum / maximum are inclusive") {
    accepts("""{"minimum":1,"maximum":10}""", "1", "10", "5.5", "1.0")
    rejects("""{"minimum":1,"maximum":10}""", "0", "0.999", "10.001", "11", "-1e9", "1e9")
  }

  test("exclusiveMinimum / exclusiveMaximum: numeric and draft-04 boolean forms") {
    accepts("""{"exclusiveMinimum":0,"exclusiveMaximum":1}""", "0.5", "0.0001")
    rejects("""{"exclusiveMinimum":0,"exclusiveMaximum":1}""", "0", "1", "-1", "2")
    accepts("""{"minimum":0,"exclusiveMinimum":true,"maximum":1,"exclusiveMaximum":true}""", "0.5")
    rejects("""{"minimum":0,"exclusiveMinimum":true,"maximum":1,"exclusiveMaximum":true}""", "0", "1")
    accepts("""{"minimum":0,"exclusiveMinimum":false}""", "0")
  }

  test("numeric bounds do not apply to non-numbers") {
    accepts("""{"minimum":5}""", "\"1\"", "null", "[1]")
  }

  // ---- strings ----

  test("minLength / maxLength count Unicode code points") {
    accepts("""{"minLength":2,"maxLength":4}""", "\"ab\"", "\"abcd\"")
    rejects("""{"minLength":2,"maxLength":4}""", "\"a\"", "\"\"", "\"abcde\"")
    // U+1F600 is one code point and two UTF-16 units.
    val smile = "😀"
    val v = validator("""{"minLength":2,"maxLength":2}""")
    v.validate(Json.Str(smile)) should not be None // one code point: too short
    v.validate(Json.Str(smile + smile)) shouldBe None // two code points, four units
    v.validate(Json.Str(smile + smile + smile)) should not be None
    accepts("""{"maxLength":0}""", "\"\"")
    rejects("""{"maxLength":0}""", "\"a\"")
  }

  // ---- arrays ----

  test("items, minItems, maxItems") {
    accepts("""{"type":"array","items":{"type":"integer"},"minItems":1,"maxItems":3}""", "[1]", "[1,2,3]")
    rejects(
      """{"type":"array","items":{"type":"integer"},"minItems":1,"maxItems":3}""",
      "[]",
      "[1,2,3,4]",
      "[1,\"2\"]",
      "[1.5]"
    )
    accepts("""{"items":false}""", "[]")
    rejects("""{"items":false}""", "[1]")
  }

  test("prefixItems with items for the rest; draft-07 tuple items with additionalItems") {
    val modern = """{"prefixItems":[{"type":"string"},{"type":"integer"}],"items":{"type":"boolean"}}"""
    accepts(modern, "[]", "[\"a\"]", "[\"a\",1]", "[\"a\",1,true,false]")
    rejects(modern, "[1]", "[\"a\",\"b\"]", "[\"a\",1,2]")
    val legacy = """{"items":[{"type":"string"},{"type":"integer"}],"additionalItems":false}"""
    accepts(legacy, "[\"a\",1]", "[\"a\"]")
    rejects(legacy, "[\"a\",1,true]", "[1,1]")
  }

  // ---- objects ----

  test("properties, required, additionalProperties") {
    val schema =
      """{"type":"object","properties":{"name":{"type":"string"},"age":{"type":"integer","minimum":0}},"required":["name"],"additionalProperties":false}"""
    accepts(schema, """{"name":"a"}""", """{"name":"a","age":3}""")
    rejects(
      schema,
      "{}",
      """{"age":3}""",
      """{"name":1}""",
      """{"name":"a","age":-1}""",
      """{"name":"a","extra":1}"""
    )
    accepts("""{"properties":{"a":{"type":"string"}}}""", """{"b":1}""", "{}")
    accepts("""{"additionalProperties":{"type":"integer"}}""", """{"a":1,"b":2}""")
    rejects("""{"additionalProperties":{"type":"integer"}}""", """{"a":1,"b":"2"}""")
    accepts("""{"required":["a"]}""", """{"a":null}""")
    rejects("""{"required":["a","b"]}""", """{"a":1}""")
  }

  test("a key that occurs more than once is validated every time and counts once for required") {
    val schema = """{"properties":{"n":{"type":"integer","maximum":10}},"required":["n","m"]}"""
    val v = validator(schema)
    v.validate(Json.Obj("n" -> Json.Num(1), "n" -> Json.Num(99), "m" -> Json.Num(0))) should not be None
    v.validate(Json.Obj("n" -> Json.Num(99), "n" -> Json.Num(1), "m" -> Json.Num(0))) should not be None
    v.validate(Json.Obj("n" -> Json.Num(1), "n" -> Json.Num(2))) should not be None // m is missing
    v.validate(Json.Obj("n" -> Json.Num(1), "n" -> Json.Num(2), "m" -> Json.Num(0))) shouldBe None
  }

  // ---- combinators ----

  test("allOf, anyOf, oneOf, not") {
    accepts("""{"allOf":[{"type":"integer"},{"minimum":3}]}""", "3", "9")
    rejects("""{"allOf":[{"type":"integer"},{"minimum":3}]}""", "2", "3.5")
    accepts("""{"anyOf":[{"type":"string"},{"type":"integer"}]}""", "\"a\"", "1")
    rejects("""{"anyOf":[{"type":"string"},{"type":"integer"}]}""", "1.5", "null")
    accepts("""{"oneOf":[{"type":"integer"},{"minimum":10}]}""", "3", "10.5")
    rejects("""{"oneOf":[{"type":"integer"},{"minimum":10}]}""", "12", "1.5")
    accepts("""{"not":{"type":"string"}}""", "1", "null")
    rejects("""{"not":{"type":"string"}}""", "\"a\"")
    accepts("true", "1", "null")
    rejects("false", "1", "null")
  }

  // ---- $ref ----

  test("local $ref: $defs, definitions, JSON Pointer escapes, siblings still apply") {
    val schema =
      """{"type":"object","properties":{"a":{"$ref":"#/$defs/level"},"b":{"$ref":"#/definitions/small","maximum":5},"c":{"$ref":"#/$defs/we~1ird~0name"}},
        |"$defs":{"level":{"type":"string","enum":["low","high"]},"we/ird~name":{"type":"boolean"}},
        |"definitions":{"small":{"type":"integer","minimum":0}}}""".stripMargin
    val v = validator(schema)
    v.unenforced shouldBe Nil
    accepts(schema, """{"a":"low","b":5,"c":true}""")
    rejects(schema, """{"a":"mid"}""", """{"b":-1}""", """{"b":6}""", """{"c":1}""")
  }

  test("a recursive $ref is not followed, is reported, and can never loop") {
    val tree =
      """{"type":"object","properties":{"value":{"type":"integer"},"children":{"type":"array","items":{"$ref":"#"}}},"required":["value"]}"""
    val v = validator(tree)
    v.unenforced shouldBe List("$ref # (recursive)")
    v.validate(json("""{"value":1,"children":[]}""")) shouldBe None
    v.validate(json("""{"value":"x"}""")) should not be None // first level is enforced
    v.validate(json("""{"value":1,"children":[{"value":"not checked"}]}""")) shouldBe None

    val selfLoop = validator("""{"$defs":{"a":{"$ref":"#/$defs/b"},"b":{"$ref":"#/$defs/a"}},"$ref":"#/$defs/a"}""")
    selfLoop.unenforced should have size 1
    selfLoop.validate(json("1")) shouldBe None
  }

  test("a $ref that is not local or does not resolve is reported and accepts anything") {
    val remote = validator("""{"properties":{"a":{"$ref":"https://example.com/schema.json"}}}""")
    remote.unenforced shouldBe List("$ref https://example.com/schema.json (not a reference inside this schema)")
    remote.validate(json("""{"a":123}""")) shouldBe None
    val dangling = validator("""{"properties":{"a":{"$ref":"#/$defs/missing"}}}""")
    dangling.unenforced shouldBe List("$ref #/$defs/missing (not a reference inside this schema)")
    dangling.validate(json("""{"a":123}""")) shouldBe None
  }

  // ---- unenforced keywords ----

  test("pattern, format, uniqueItems and the other unsupported keywords are skipped and reported") {
    val schema =
      """{"type":"object","properties":{
        |"id":{"type":"string","pattern":"^usr_[a-z0-9]+$"},
        |"when":{"type":"string","format":"date-time"},
        |"tags":{"type":"array","items":{"type":"string"},"uniqueItems":true},
        |"step":{"type":"number","multipleOf":0.5}}}""".stripMargin
    val v = validator(schema)
    v.unenforced shouldBe List("format", "multipleOf", "pattern", "uniqueItems")
    // The enforced part still holds ...
    v.validate(json("""{"id":1}""")) should not be None
    v.validate(json("""{"tags":[1]}""")) should not be None
    // ... the unenforced part does not: these all violate the schema as written.
    v.validate(json("""{"id":"not a user id","when":"yesterday","tags":["a","a"],"step":0.3}""")) shouldBe None
  }

  test("every keyword in UnenforcedKeywords is reported when used") {
    JsonSchemaValidator.UnenforcedKeywords.foreach { keyword =>
      val v = JsonSchemaValidator.compile(Json.Obj("type" -> Json.Str("string"), keyword -> Json.Obj()))
      withClue(keyword)(v.unenforced shouldBe List(keyword))
    }
    (JsonSchemaValidator.UnenforcedKeywords & JsonSchemaValidator.EnforcedKeywords) shouldBe Set.empty
  }

  test("additionalProperties is not applied next to patternProperties") {
    val v = validator("""{"patternProperties":{"^x-":{"type":"string"}},"additionalProperties":false}""")
    v.unenforced shouldBe List("additionalProperties (next to patternProperties)", "patternProperties")
    v.validate(json("""{"x-a":"ok","other":1}""")) shouldBe None
  }

  test("a supported keyword with a malformed value is skipped and reported, never thrown") {
    val v = validator(
      """{"type":"strnig","maximum":"10","minLength":-1,"required":"name","enum":"a","anyOf":[],"properties":[]}"""
    )
    v.unenforced shouldBe List(
      "anyOf (value has the wrong shape)",
      "enum (value has the wrong shape)",
      "maximum (value has the wrong shape)",
      "minLength (value has the wrong shape)",
      "properties (value has the wrong shape)",
      "required (value has the wrong shape)",
      "type (value has the wrong shape)"
    )
    v.validate(json("12345")) shouldBe None
    validator("17").unenforced should have size 1
  }

  test("annotations and unknown keywords have no effect and are not reported") {
    val v = validator(
      """{"$schema":"https://json-schema.org/draft/2020-12/schema","title":"t","description":"d","default":1,"examples":[1],"enumNames":["One"],"x-vendor":true,"deprecated":true}"""
    )
    v.unenforced shouldBe Nil
    v.constrains shouldBe false
    v.validate(json("\"anything\"")) shouldBe None
    validator("{}").constrains shouldBe false
    validator("""{"type":"object","additionalProperties":true}""").constrains shouldBe true
  }

  // ---- messages ----

  test("a failure names the location and the constraint, and never echoes the value") {
    val schema =
      """{"type":"object","properties":{"user":{"type":"object","properties":{"tags":{"type":"array","items":{"type":"string","maxLength":3}},"role":{"enum":["admin","guest"]}}}},"required":["user"]}"""
    val v = validator(schema)
    v.validate(json("""{"user":{"tags":["ok","SECRET-VALUE"]}}""")) shouldBe
      Some("'user.tags[1]' must be at most 3 characters long")
    v.validate(json("""{"user":{"role":"SECRET-VALUE"}}""")) shouldBe
      Some("'user.role' must be one of: admin, guest")
    v.validate(json("""{"user":5}""")) shouldBe Some("'user' must be object, got a number")
    v.validate(json("{}")) shouldBe Some("missing required property 'user'")
    v.validate(json("[]")) shouldBe Some("must be object, got an array")
  }

  test("an optional value (anyOf with null) reports the value branch's own reason") {
    val v = validator("""{"properties":{"mood":{"anyOf":[{"type":"string","enum":["happy","sad"]},{"type":"null"}]}}}""")
    v.validate(json("""{"mood":null}""")) shouldBe None
    v.validate(json("""{"mood":"happy"}""")) shouldBe None
    v.validate(json("""{"mood":"angry"}""")) shouldBe Some("'mood' must be one of: happy, sad")
  }

  test("a long client-chosen member name is clipped in the message") {
    val v = validator("""{"additionalProperties":false}""")
    val message = v.validate(Json.Obj(("k" * 5000) -> Json.Null)).getOrElse(fail("expected a rejection"))
    message.length should be < 200
  }

  // ---- cost ----

  private def timedMillis[A](body: => A): (A, Long) =
    val start = System.nanoTime()
    val result = body
    (result, (System.nanoTime() - start) / 1000000L)

  test("numbers with an enormous exponent are typed and compared without expanding them") {
    val v = validator("""{"type":"object","properties":{"i":{"type":"integer","minimum":-10,"maximum":10},"n":{"type":"number","exclusiveMaximum":1,"enum":[0.5,2]}}}""")
    val huge = Json.Num(new java.math.BigDecimal("1e999999999"))
    val tiny = Json.Num(new java.math.BigDecimal("1e-999999999"))
    val negative = Json.Num(new java.math.BigDecimal("-7e999999999"))
    val (results, millis) = timedMillis {
      List(
        v.validate(Json.Obj("i" -> huge)),
        v.validate(Json.Obj("i" -> tiny)),
        v.validate(Json.Obj("i" -> negative)),
        v.validate(Json.Obj("n" -> huge)),
        v.validate(Json.Obj("n" -> tiny))
      )
    }
    results.foreach(_ should not be None)
    results(1) shouldBe Some("'i' must be integer, got a number") // 1e-999999999 is a fraction
    millis should be < 2000L
  }

  test("a number with a very long mantissa is typed with one division, not one per digit") {
    val v = validator("""{"type":"integer"}""")
    val digits = 200000
    // 1 followed by `digits` zeros, written with scale 1 (…0.0) and with scale digits/2.
    val unscaled = java.math.BigInteger.TEN.pow(digits)
    val (results, millis) = timedMillis {
      List(
        v.validate(Json.Num(new java.math.BigDecimal(unscaled, 1))),
        v.validate(Json.Num(new java.math.BigDecimal(unscaled, digits / 2))),
        v.validate(Json.Num(new java.math.BigDecimal(unscaled.add(java.math.BigInteger.ONE), 1))),
        v.validate(Json.Num(new java.math.BigDecimal(unscaled.negate, 1)))
      )
    }
    results shouldBe List(None, None, Some("must be integer, got a number"), None)
    millis should be < 5000L
  }

  test("a large array and a wide object are validated in one pass") {
    val v = validator(
      """{"type":"object","properties":{"xs":{"type":"array","items":{"type":"integer","minimum":0}},"wide":{"type":"object","additionalProperties":{"type":"string","maxLength":8},"required":["k0"]}}}"""
    )
    val xs = Json.Arr(zio.Chunk.fromIterable((0 until 1000000).map(i => Json.Num(i))))
    val wide = Json.Obj(zio.Chunk.fromIterable((0 until 200000).map(i => s"k$i" -> Json.Str("v"))))
    val (result, millis) = timedMillis(v.validate(Json.Obj("xs" -> xs, "wide" -> wide)))
    result shouldBe None
    millis should be < 5000L
  }

  test("a recursive schema over a deep value stays linear (the back-reference is not followed)") {
    val v = validator(
      """{"oneOf":[{"type":"object","properties":{"next":{"$ref":"#"}},"required":["a"]},{"type":"object","properties":{"next":{"$ref":"#"}},"required":["b"]}]}"""
    )
    val deep = (1 to 60).foldLeft(Json.Obj("a" -> Json.Num(1)): Json)((inner, _) => Json.Obj("a" -> Json.Num(1), "next" -> inner))
    val (result, millis) = timedMillis(v.validate(deep))
    result shouldBe None
    millis should be < 2000L
  }
