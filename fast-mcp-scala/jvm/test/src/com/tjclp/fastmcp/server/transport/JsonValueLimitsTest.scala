package com.tjclp.fastmcp
package server.transport

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.codec.JsonLimits
import com.tjclp.fastmcp.codec.JsonLimits.{Bounds, Violation}
import com.tjclp.fastmcp.jsonrpc.*
import com.tjclp.fastmcp.jsonrpc.JsonRpcMessage.*
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.router.Session
import com.tjclp.fastmcp.server.transport.JvmTransportBackend.given

/** The array-length, value-count and number-magnitude limits at the `MessageLoop.parseFrame` choke
  * point (TJC-5087): each is enforced on the raw text before the parser allocates a node and
  * re-checked on the parsed AST, with exact boundaries, string-awareness, and the guarantee that
  * ordinary payloads (base64 strings, moderate lists, the full `Long` and `Double` ranges) still
  * pass. The decode-side second line of defence lives in `codec.ArgumentBoundsTest`.
  */
class JsonValueLimitsTest extends AnyFunSuite with Matchers:

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private def timed[A](body: => A): (A, Long) =
    val start = java.lang.System.nanoTime()
    val result = body
    (result, (java.lang.System.nanoTime() - start) / 1_000_000L)

  private def parseErrorMessage(result: Either[JsonRpcMessage, JsonRpcMessage]): String =
    result match
      case Left(Failure(None, err)) =>
        err.code shouldBe -32700
        err.message
      case other => fail(s"expected a -32700 parse failure, got $other")

  private def call(arguments: String): String =
    s"""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"t","arguments":$arguments}}"""

  private def parse(text: String): Json =
    text.fromJson[Json].fold(e => fail(s"not JSON: $e"), identity)

  private def scan(text: String, bounds: Bounds): Option[Violation] =
    JsonLimits.preScan(text, Int.MaxValue, bounds)

  // ---- array length ----

  test("an array over the default maxArrayElements is refused with -32700; one at the bound parses") {
    val max = LimitSettings().maxArrayElements
    val over = call(s"""{"xs":[${Seq.fill(max + 1)("1").mkString(",")}]}""")
    scan(over, LimitSettings().jsonBounds) shouldBe Some(Violation.TooManyElements(max))
    parseErrorMessage(MessageLoop.parseFrame(over)) should include("maxArrayElements")

    val at = call(s"""{"xs":[${Seq.fill(max)("1").mkString(",")}]}""")
    MessageLoop.parseFrame(at).isRight shouldBe true
  }

  test("maxArrayElements is inclusive and per array: N pass, N+1 fail (pre-scan and AST validate)") {
    val three = Bounds(maxArrayElements = 3)
    scan("[1,2,3]", three) shouldBe None
    scan("[ 1 , 2 ,\n 3 ]", three) shouldBe None
    scan("[1,2,3,4]", three) shouldBe Some(Violation.TooManyElements(3))
    // every array is counted on its own, at any depth
    scan("[[1,2,3],[1,2,3],[1,2,3]]", three) shouldBe None
    scan("""{"a":[1,2,3],"b":{"c":[1,[1,2,3,4]]}}""", three) shouldBe Some(
      Violation.TooManyElements(3)
    )
    // commas inside strings and members of objects are not array elements
    scan("""["a,b,c,d,e","x"]""", three) shouldBe None
    scan("""{"a":1,"b":2,"c":3,"d":4,"e":5}""", three) shouldBe None

    def arr(n: Int): Json = Json.Arr(Chunk.fromIterable((1 to n).map(i => Json.Num(i))))
    JsonLimits.validate(arr(3), three) shouldBe None
    JsonLimits.validate(arr(4), three) shouldBe Some(Violation.TooManyElements(3))
    JsonLimits.validate(Json.Obj("a" -> Json.Arr(arr(3), arr(4))), three) shouldBe Some(
      Violation.TooManyElements(3)
    )
    JsonLimits.validate(parse("""{"a":1,"b":2,"c":3,"d":4,"e":5}"""), three) shouldBe None

    val tight = LimitSettings(maxArrayElements = 3)
    MessageLoop.parseFrame(call("""{"xs":[1,2,3]}"""), tight).isRight shouldBe true
    parseErrorMessage(MessageLoop.parseFrame(call("""{"xs":[1,2,3,4]}"""), tight)) should include(
      "maxArrayElements (3)"
    )
  }

  test("a body-cap-sized frame of one long flat array is refused by the raw-text pre-scan") {
    // ~1 MiB (the default HTTP body cap) of two-character elements: before the bound this decoded
    // to an AST tens of times larger than its text.
    val limits = LimitSettings()
    Seq("1", "\"\"", "0.5").foreach { element =>
      val count = (1024 * 1024) / (element.length + 1)
      val frame = call("{\"xs\":[" + (element + ",") * count + element + "]}")
      frame.length should be >= 1024 * 1024
      val (pre, preMs) = timed(JsonLimits.preScan(frame, limits.maxFrameChars, limits.jsonBounds))
      // the text scan alone refuses it: no parser, no AST node
      pre shouldBe Some(Violation.TooManyElements(limits.maxArrayElements))
      preMs should be < 1000L
      val (result, ms) = timed(MessageLoop.parseFrame(frame))
      parseErrorMessage(result) should include("maxArrayElements")
      ms should be < 1000L
    }
  }

  // ---- total value count ----

  test("maxValues counts every JSON value once; the pre-scan and the AST re-check agree exactly") {
    val cases = Seq(
      "1" -> 1,
      "\"s\"" -> 1,
      "null" -> 1,
      "[]" -> 1,
      "{}" -> 1,
      "[ \n ]" -> 1,
      "[1]" -> 2,
      "[1,2,3]" -> 4,
      "[[[]]]" -> 3,
      "[[],{}, [ ] ]" -> 4,
      """{"a":1,"b":[true,null,{"c":"x,y]"}]}""" -> 7,
      """{"jsonrpc":"2.0","id":1,"method":"ping"}""" -> 4,
      """ { "a" : [ 1 , { } , "," ] , "b" : -1.5e3 } """ -> 6
    )
    cases.foreach { (text, count) =>
      withClue(s"$text has $count values: ") {
        scan(text, Bounds(maxValues = count)) shouldBe None
        JsonLimits.validate(parse(text), Bounds(maxValues = count)) shouldBe None
        if count > 1 then
          scan(text, Bounds(maxValues = count - 1)) shouldBe Some(
            Violation.TooManyValues(count - 1)
          )
          JsonLimits.validate(parse(text), Bounds(maxValues = count - 1)) shouldBe Some(
            Violation.TooManyValues(count - 1)
          )
      }
    }
  }

  test("many arrays that each fit maxArrayElements are refused by maxValues; a big table passes") {
    val limits = LimitSettings()
    def table(rows: Int, cols: Int): String =
      Seq.fill(rows)(Seq.fill(cols)("1").mkString("[", ",", "]")).mkString("[", ",", "]")
    // 24 tables x 1000 rows x 3 cells: every array is inside the array bound, ~96 000 values
    val wide = (1 to 24).map(i => s""""t$i":${table(1000, 3)}""").mkString("{", ",", "}")
    val (pre, preMs) = timed(scan(call(wide), limits.jsonBounds))
    pre shouldBe Some(Violation.TooManyValues(limits.maxValues))
    preMs should be < 1000L
    parseErrorMessage(MessageLoop.parseFrame(call(wide))) should include("maxValues")

    // 1000 rows x 20 columns (21 000 values) is an ordinary payload
    MessageLoop.parseFrame(call(s"""{"rows":${table(1000, 20)}}""")).isRight shouldBe true
  }

  test("legitimate large payloads still pass: base64 strings, embeddings, moderate lists") {
    val base64 = java.util.Base64.getEncoder.encodeToString(Array.fill[Byte](750_000)(7))
    base64.length should be >= 1_000_000
    MessageLoop.parseFrame(call(s"""{"data":"$base64"}""")).isRight shouldBe true
    // a string full of structural characters and digits is still one value
    val noisy = "[1e999999999,{" * 50_000
    MessageLoop.parseFrame(call(s"""{"text":"$noisy"}""")).isRight shouldBe true
    // embedding vectors of the common sizes fit the default array bound, alone and in a batch
    Seq(1024, 1536, 3072).foreach { dimensions =>
      val embedding =
        Seq.tabulate(dimensions)(i => s"${(i % 7) - 3}.${i}e-3").mkString("[", ",", "]")
      MessageLoop.parseFrame(call(s"""{"vector":$embedding}""")).isRight shouldBe true
    }
    val batch = Seq.fill(16)(Seq.fill(3072)("-0.0123456").mkString("[", ",", "]"))
    MessageLoop.parseFrame(call(s"""{"vectors":${batch.mkString("[", ",", "]")}}""")).isRight shouldBe true
    val ids = (1 to 4096).map(i => s""""id-$i"""").mkString("[", ",", "]")
    MessageLoop.parseFrame(call(s"""{"ids":$ids}""")).isRight shouldBe true
    // longer arrays are a setting away
    val longer = LimitSettings(maxArrayElements = 8192)
    val vector = Seq.fill(8192)("0.25").mkString("[", ",", "]")
    MessageLoop.parseFrame(call(s"""{"vector":$vector}"""), longer).isRight shouldBe true
    parseErrorMessage(MessageLoop.parseFrame(call(s"""{"vector":$vector}"""))) should include(
      "maxArrayElements"
    )
  }

  // ---- number magnitude ----

  test("number literals with extreme exponents are refused with -32700 in bounded time") {
    val literals = Seq(
      "1e999999999",
      "1E+999999999",
      "-1e2147483647",
      "1e-999999999",
      "0.1E-2147483647",
      "1e99999999999999999999",
      "0e99999999",
      "123.456e+513",
      "1e513",
      "1e-513"
    )
    literals.foreach { literal =>
      withClue(literal) {
        val frames = Seq(
          call(s"""{"n":$literal}"""),
          call(s"""{"deep":{"xs":[1,$literal]}}"""),
          s"""{"jsonrpc":"2.0","id":$literal,"method":"ping"}""",
          literal
        )
        frames.foreach { frame =>
          val (result, ms) = timed(MessageLoop.parseFrame(frame))
          parseErrorMessage(result) should include("maxNumberExponent")
          ms should be < 1000L
        }
      }
    }
  }

  test("maxNumberExponent and maxNumberDigits are inclusive (raw-text pre-scan)") {
    val exp3 = Bounds(maxNumberExponent = 3)
    Seq("1e3", "1E3", "1e+3", "1e-3", "-1.5e-3", "1e003", "[1e3,2E-3]", "7").foreach { ok =>
      withClue(ok)(scan(ok, exp3) shouldBe None)
    }
    Seq("1e4", "1E+4", "1e-4", "1e0004", "[1e3,2E-4]", """{"a":{"b":9e10}}""").foreach { bad =>
      withClue(bad)(scan(bad, exp3) shouldBe Some(Violation.NumberExponentTooLarge(3)))
    }

    val five = Bounds(maxNumberDigits = 5)
    Seq("12345", "-12345", "123.45", "0.1234", "12345e10", "[12345,-0.5]").foreach { ok =>
      withClue(ok)(scan(ok, five) shouldBe None)
    }
    Seq("123456", "1234.56", "0.00001", "-123456e1", """{"a":[1,123456]}""").foreach { bad =>
      withClue(bad)(scan(bad, five) shouldBe Some(Violation.NumberTooLong(5)))
    }

    val limits = LimitSettings()
    val longest = "1" * limits.maxNumberDigits
    scan(longest, limits.jsonBounds) shouldBe None
    scan(longest + "1", limits.jsonBounds) shouldBe Some(
      Violation.NumberTooLong(limits.maxNumberDigits)
    )
    parseErrorMessage(MessageLoop.parseFrame(call(s"""{"n":${longest}1}"""))) should include(
      "maxNumberDigits"
    )
    val zeros = "0." + "0" * limits.maxNumberDigits + "1"
    parseErrorMessage(MessageLoop.parseFrame(call(s"""{"n":$zeros}"""))) should include(
      "maxNumberDigits"
    )
  }

  test("digits, signs and exponents inside strings and member names are never read as numbers") {
    val tight = Bounds(maxNumberDigits = 2, maxNumberExponent = 1)
    scan("""{"k1e99999":"-1e999999999 123456789","e":[true,false,null,"e99"]}""", tight) shouldBe None
    scan("""["\"1e99","12345\\",12]""", tight) shouldBe None
    scan("""["\"1e99","12345\\",123]""", tight) shouldBe Some(Violation.NumberTooLong(2))
    MessageLoop
      .parseFrame(call(s"""{"s":"1e999999999","t":"${"9" * 5000}"}"""))
      .isRight shouldBe true
  }

  test("the AST re-check bounds programmatic numbers without work proportional to magnitude") {
    val bounds = Bounds()
    def num(text: String): Json = Json.Num(new java.math.BigDecimal(text))
    val one = java.math.BigInteger.ONE
    val hazards = Seq(
      num("1e999999999"),
      num("-1e2147483647"),
      num("1e-999999999"),
      Json.Num(new java.math.BigDecimal(one, Int.MaxValue)),
      Json.Num(new java.math.BigDecimal(one, Int.MinValue)),
      num("1e1025")
    )
    hazards.foreach { hazard =>
      val (direct, ms) = timed(JsonLimits.validate(hazard, bounds))
      direct shouldBe Some(Violation.NumberExponentTooLarge(bounds.maxNumberExponent))
      ms should be < 500L
      JsonLimits.validate(Json.Obj("a" -> Json.Arr(Json.Str("x"), hazard)), bounds) shouldBe Some(
        Violation.NumberExponentTooLarge(bounds.maxNumberExponent)
      )
      JsonLimits.scanNumbers(Json.Arr(Json.Obj("n" -> hazard)), bounds).violation shouldBe Some(
        Violation.NumberExponentTooLarge(bounds.maxNumberExponent)
      )
    }
    JsonLimits.validate(num("1" + "0" * 600), bounds) shouldBe Some(
      Violation.NumberTooLong(bounds.maxNumberDigits)
    )
    // every literal the pre-scan admits also passes the AST re-check once parsed
    val admitted = Seq(
      "1e512",
      "-1E-512",
      "0." + "0" * 500 + "12345678901e-512",
      "12345678901234567890123456789012345678901234567890e512",
      "0e512",
      "0.0e-512",
      "9223372036854775807",
      "4.9E-324"
    )
    admitted.foreach { literal =>
      withClue(literal) {
        scan(literal, bounds) shouldBe None
        JsonLimits.validate(parse(literal), bounds) shouldBe None
        JsonLimits.scanNumbers(parse(literal), bounds).violation shouldBe None
      }
    }
  }

  test("ordinary numbers still decode: the full Long and Double ranges, decimals, big integers") {
    def node(literal: String): Json =
      MessageLoop.parseFrame(call(s"""{"n":$literal}""")) match
        case Right(Request(_, _, Some(Json.Obj(params)))) =>
          JsonFields
            .get(params, "arguments")
            .flatMap(JsonFields.get(_, "n"))
            .getOrElse(fail("n"))
        case other => fail(s"$literal: unexpected $other")

    Seq(Long.MaxValue, Long.MinValue, 0L, -1L, 9007199254740993L).foreach { value =>
      JsonDecoder[Long].fromJsonAST(node(value.toString)) shouldBe Right(value)
    }
    Seq(
      Double.MaxValue,
      -Double.MaxValue,
      Double.MinPositiveValue,
      java.lang.Double.MIN_NORMAL,
      1.0e21,
      0.1,
      123456789.123456789,
      math.Pi
    ).foreach { value =>
      // Java's shortest repr (1.7976931348623157E308, 4.9E-324, …) and a lower-case exponent
      JsonDecoder[Double].fromJsonAST(node(value.toString)) shouldBe Right(value)
      JsonDecoder[Double].fromJsonAST(node(value.toString.toLowerCase)) shouldBe Right(value)
    }
    JsonDecoder[Double].fromJsonAST(node("1E+2")) shouldBe Right(100.0)
    JsonDecoder[Int].fromJsonAST(node("1e3")) shouldBe Right(1000)
    JsonDecoder[BigInt].fromJsonAST(node("18446744073709551615")) shouldBe Right(
      BigInt("18446744073709551615")
    )
    JsonDecoder[BigInt].fromJsonAST(node("1e400")) shouldBe Right(BigInt(10).pow(400))
    JsonDecoder[BigDecimal].fromJsonAST(node("12345678901234567890.123456789e-300")) shouldBe Right(
      BigDecimal("12345678901234567890.123456789e-300")
    )
  }

  // ---- settings ----

  test("LimitSettings validates the new bounds and pins its defaults to the codec constants") {
    val _ = an[IllegalArgumentException] should be thrownBy LimitSettings(maxArrayElements = 0)
    val _ = an[IllegalArgumentException] should be thrownBy LimitSettings(maxValues = 0)
    val _ = an[IllegalArgumentException] should be thrownBy LimitSettings(maxNumberDigits = 0)
    val _ = an[IllegalArgumentException] should be thrownBy LimitSettings(maxNumberExponent = 0)
    val _ = an[IllegalArgumentException] should be thrownBy LimitSettings(
      maxNumberDigits = LimitSettings.MaxSupportedNumberDigits + 1
    )
    val _ = an[IllegalArgumentException] should be thrownBy LimitSettings(
      maxNumberExponent = LimitSettings.MaxSupportedNumberExponent + 1
    )
    LimitSettings(
      maxNumberDigits = LimitSettings.MaxSupportedNumberDigits,
      maxNumberExponent = LimitSettings.MaxSupportedNumberExponent
    ).maxNumberDigits shouldBe 4096

    val defaults = LimitSettings()
    defaults.maxArrayElements shouldBe 4096
    defaults.maxValues shouldBe 65536
    defaults.maxNumberDigits shouldBe 512
    defaults.maxNumberExponent shouldBe 512
    defaults.maxArrayElements shouldBe JsonLimits.DefaultMaxArrayElements
    defaults.maxValues shouldBe JsonLimits.DefaultMaxValues
    defaults.maxNumberDigits shouldBe JsonLimits.DefaultMaxNumberDigits
    defaults.maxNumberExponent shouldBe JsonLimits.DefaultMaxNumberExponent
    defaults.jsonBounds shouldBe Bounds()
    LimitSettings.MaxSupportedNumberDigits shouldBe JsonLimits.MaxSupportedNumberDigits
    LimitSettings.MaxSupportedNumberExponent shouldBe JsonLimits.MaxSupportedNumberExponent
    // the five pre-existing fields keep their positions (positional construction still compiles)
    LimitSettings(1024, 8, 16, 64, 4) shouldBe LimitSettings(
      maxFrameChars = 1024,
      maxDepth = 8,
      maxObjectFields = 16,
      maxUriChars = 64,
      maxSubscriptionsPerSession = 4
    )
  }

  test("router wiring: server-configured array, value and number bounds reach the choke point") {
    val settings = McpServerSettings(limits =
      LimitSettings(
        maxArrayElements = 2,
        maxValues = 12,
        maxNumberDigits = 4,
        maxNumberExponent = 2
      )
    )
    val router = runUnsafe(McpServer("Tight", "0.1.0", settings).buildRouter)
    val session = runUnsafe(Session.make("tight"))
    def reply(frame: String): String =
      runUnsafe(MessageLoop.handleFrame(router, session, frame)).getOrElse("")
    def ping(params: String): String =
      s"""{"jsonrpc":"2.0","id":1,"method":"ping","params":$params}"""

    reply(ping("""{"xs":[1,2]}""")) should include(""""result":{}""")
    val tooLong = reply(ping("""{"xs":[1,2,3]}"""))
    tooLong should include("-32700")
    tooLong should include("maxArrayElements (2)")
    val tooMany = reply(ping("""{"a":[1,2],"b":[1,2],"c":[1,2],"d":[1,2]}"""))
    tooMany should include("-32700")
    tooMany should include("maxValues (12)")
    reply(ping("""{"n":12345}""")) should include("maxNumberDigits (4)")
    reply(ping("""{"n":1e3}""")) should include("maxNumberExponent")
    reply(ping("""{"n":1e2}""")) should include(""""result":{}""")
  }
