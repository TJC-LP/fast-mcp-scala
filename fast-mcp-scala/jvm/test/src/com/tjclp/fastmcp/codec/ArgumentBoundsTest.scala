package com.tjclp.fastmcp
package codec

import java.util.concurrent.atomic.AtomicInteger

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.json.internal.RetractReader

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.jsonrpc.*
import com.tjclp.fastmcp.jsonrpc.JsonRpcMessage.Request
import com.tjclp.fastmcp.macros.MapToFunctionMacro
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.router.Session
import com.tjclp.fastmcp.server.transport.JvmTransportBackend.given
import com.tjclp.fastmcp.server.transport.MessageLoop

/** The decode side of the array and number bounds (TJC-5087): what zio-json 0.10.0 does with a
  * huge exponent (the premise), the guard in `DefaultDecodeContext.decodeRaw` that keeps an
  * out-of-range number away from every argument decoder, the same bounds on JSON embedded in string
  * arguments, and end-to-end `tools/call` behaviour for `BigInt`, `BigDecimal` and `Set`
  * parameters. Frame-level boundaries live in `server.transport.JsonValueLimitsTest`.
  */
class ArgumentBoundsTest extends AnyFunSuite with Matchers:

  import McpDecoders.given

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private def timed[A](body: => A): (A, Long) =
    val start = java.lang.System.nanoTime()
    val result = body
    (result, (java.lang.System.nanoTime() - start) / 1_000_000L)

  private def num(text: String): Json = Json.Num(new java.math.BigDecimal(text))

  /** Exponents far outside the limits. The first two are large enough to show the hazard and small
    * enough that the unguarded decoders finish (about a megabyte each), so this suite can be run
    * against a build without the guard; the rest would take minutes and gigabytes there.
    */
  private val hazardousExponents: Seq[String] =
    Seq(
      "1e3000000",
      "1e-3000000",
      "1e999999999",
      "-1e2147483647",
      "1e-999999999",
      "1e-2147483647"
    )

  private val ctx = DefaultDecodeContext.default

  /** `(decimal digits) x log2(10)`, rounded up: the documented bit-length bound of any integer a
    * number inside the limits can expand to.
    */
  private def maxBits(limits: LimitSettings): Int =
    math.ceil((limits.maxNumberDigits + limits.maxNumberExponent) * (math.log(10) / math.log(2))).toInt

  case class BigArgs(n: BigInt) derives JsonDecoder
  case class DecArgs(d: BigDecimal) derives JsonDecoder
  case class Holder(xs: List[BigInt], o: Option[java.math.BigInteger]) derives JsonDecoder
  case class TagArgs(tags: Set[String])
  case class Quote(label: String, amount: BigDecimal) derives JsonDecoder
  case class Note(text: String, tags: List[String]) derives JsonDecoder
  case class BlobArgs(json: String)

  /** Wraps a decoder and counts every invocation, on the text and the AST path alike. */
  private def counting[A](underlying: JsonDecoder[A], calls: AtomicInteger): JsonDecoder[A] =
    new JsonDecoder[A]:
      def unsafeDecode(trace: List[JsonError], in: RetractReader): A =
        val _ = calls.incrementAndGet()
        underlying.unsafeDecode(trace, in)
      override def unsafeFromJsonAST(trace: List[JsonError], json: Json): A =
        val _ = calls.incrementAndGet()
        underlying.unsafeFromJsonAST(trace, json)

  /** 3^blocks distinct strings sharing one `String.hashCode` ("Aa", "BB" and "C#" all hash to
    * 2112).
    */
  private def collidingStrings(blocks: Int): IndexedSeq[String] =
    val parts = Array("Aa", "BB", "C#")
    (0 until math.pow(3, blocks).toInt).map { k =>
      val sb = new StringBuilder(2 * blocks)
      var rest = k
      var b = 0
      while b < blocks do
        sb.append(parts(rest % 3))
        rest /= 3
        b += 1
      sb.result()
    }

  // ---- the zio-json premise ----

  test("zio-json 0.10.0: the text parser keeps any exponent and the AST BigInt decoder expands it") {
    // Text -> AST: the significand is capped at 256 bits, the exponent is not capped at all.
    val parsed = "1e999999999".fromJson[Json]
    parsed.map {
      case Json.Num(n) => n.scale
      case other => fail(s"unexpected $other")
    } shouldBe Right(-999999999)
    ("1" * 100).fromJson[Json].isLeft shouldBe true

    // Text -> BigInt reads digits only (and caps them at 256 bits): an exponent form is refused
    // cheaply...
    "[1e200000]".fromJson[List[BigInt]].isLeft shouldBe true
    ("[" + "1" * 100 + "]").fromJson[List[BigInt]].isLeft shouldBe true
    // ...but AST -> BigInt / BigInteger calls `toBigIntegerExact` with no cap: the power of ten
    // is materialised in full (moderate exponent here so the suite stays fast).
    val (expanded, ms) = timed(JsonDecoder[BigInt].fromJsonAST(num("1e200000")))
    expanded.map(_.bitLength) shouldBe Right(664386)
    JsonDecoder[java.math.BigInteger].fromJsonAST(num("1e200000")).map(_.bitLength) shouldBe Right(
      664386
    )
    info(s"unguarded AST decode of 1e200000 into BigInt: 664 386 bits in $ms ms")
    // AST -> BigDecimal hands the raw scale straight to the caller.
    JsonDecoder[java.math.BigDecimal].fromJsonAST(num("1e999999999")).map(_.scale) shouldBe Right(
      -999999999
    )
    JsonDecoder[BigDecimal].fromJsonAST(num("1e-999999999")).map(_.scale) shouldBe Right(
      999999999
    )

    // Which decoders take the AST path matters for reach: derived case classes, Option and Chunk
    // decode straight from the node, so a BigInt field or Option[BigInt] expands as above. List,
    // Set, Seq, Vector and Map do not override the AST path in 0.10.0: they re-encode the subtree
    // and run the element decoder on TEXT, where BigInt refuses an exponent form.
    JsonDecoder[Option[BigInt]].fromJsonAST(num("1e2000")).map(_.map(_.bitLength)) shouldBe Right(
      Some(6644)
    )
    JsonDecoder[Chunk[BigInt]]
      .fromJsonAST(Json.Arr(num("1e2000")))
      .map(_.map(_.bitLength)) shouldBe Right(Chunk(6644))
    JsonDecoder[List[BigInt]].fromJsonAST(Json.Arr(num("1e2000"))).isLeft shouldBe true
    JsonDecoder[Set[BigInt]].fromJsonAST(Json.Arr(num("1e2000"))).isLeft shouldBe true
    // ...while BigDecimal elements keep their raw scale on the text path too.
    JsonDecoder[List[java.math.BigDecimal]]
      .fromJsonAST(Json.Arr(num("1e999999999")))
      .map(_.map(_.scale)) shouldBe Right(List(-999999999))
  }

  // ---- decodeRaw ----

  test("decodeRaw refuses an out-of-range number before any decoder runs (-32602, bounded time)") {
    hazardousExponents.foreach { literal =>
      withClue(literal) {
        val calls = new AtomicInteger(0)
        val node = num(literal)
        val (bigInt, ms1) = timed(
          intercept[IllegalArgumentException](
            DefaultDecodeContext.decodeRaw[BigInt]("n", node, ctx, counting(JsonDecoder[BigInt], calls))
          )
        )
        bigInt.getMessage should include("'n'")
        bigInt.getMessage should include("maxNumberExponent")
        McpError.fromThrowable(bigInt).code shouldBe -32602
        ms1 should be < 500L

        val (bigDecimal, ms2) = timed(
          intercept[IllegalArgumentException](
            DefaultDecodeContext.decodeRaw[BigDecimal](
              "d",
              node,
              ctx,
              counting(JsonDecoder[BigDecimal], calls)
            )
          )
        )
        bigDecimal.getMessage should include("maxNumberExponent")
        ms2 should be < 500L

        // nested in a case class, a collection and an Option; and as a raw Scala BigDecimal
        val nested = Json.Obj("xs" -> Json.Arr(Json.Num(1), node), "o" -> Json.Null)
        val (holder, ms3) = timed(
          intercept[IllegalArgumentException](
            DefaultDecodeContext.decodeRaw[Holder]("h", nested, ctx, counting(JsonDecoder[Holder], calls))
          )
        )
        holder.getMessage should include("maxNumberExponent")
        ms3 should be < 500L
        val _ = intercept[IllegalArgumentException](
          DefaultDecodeContext.decodeRaw[Holder](
            "h",
            Map("xs" -> List(BigDecimal(new java.math.BigDecimal(literal))), "o" -> None),
            ctx,
            counting(JsonDecoder[Holder], calls)
          )
        )
        calls.get shouldBe 0
      }
    }
  }

  test("decodeRaw applies the context's own number bounds; ordinary values still decode") {
    val tight = new DefaultDecodeContext(maxNumberDigits = 4, maxNumberExponent = 2)
    DefaultDecodeContext.decodeRaw[BigInt]("n", num("1e2"), tight, JsonDecoder[BigInt]) shouldBe BigInt(100)
    intercept[IllegalArgumentException](
      DefaultDecodeContext.decodeRaw[BigInt]("n", num("1e7"), tight, JsonDecoder[BigInt])
    ).getMessage should include("maxNumberExponent")
    intercept[IllegalArgumentException](
      DefaultDecodeContext.decodeRaw[BigInt]("n", num("12345"), tight, JsonDecoder[BigInt])
    ).getMessage should include("maxNumberDigits (4)")

    McpDecoder[BigArgs].decode("a", Map("n" -> num("1e400")), ctx) shouldBe BigArgs(BigInt(10).pow(400))
    McpDecoder[BigArgs].decode("a", Map("n" -> BigDecimal("18446744073709551615")), ctx) shouldBe
      BigArgs(BigInt("18446744073709551615"))
    McpDecoder[DecArgs].decode("a", Map("d" -> num("1.5e-300")), ctx) shouldBe DecArgs(
      BigDecimal("1.5e-300")
    )
    McpDecoder[Holder].decode(
      "h",
      Json.Obj(
        "xs" -> Json.Arr(Json.Num(1), num("123456789012345678901234567890")),
        "o" -> num("1e30")
      ),
      ctx
    ) shouldBe Holder(
      List(BigInt(1), BigInt("123456789012345678901234567890")),
      Some(java.math.BigInteger.TEN.pow(30))
    )
  }

  test("macro-generated per-parameter decoders refuse an out-of-range BigInt / BigDecimal") {
    val calls = new AtomicInteger(0)
    def f(n: BigInt, d: BigDecimal, xs: List[BigInt]): String =
      val _ = calls.incrementAndGet()
      s"${n.bitLength}|${d.scale}|${xs.size}"
    val fn = MapToFunctionMacro.callByMap(f)
    fn(Map("n" -> num("1e400"), "d" -> num("2.5"), "xs" -> Json.Arr(num("3")))) shouldBe "1329|1|1"
    calls.get shouldBe 1
    hazardousExponents.foreach { literal =>
      withClue(literal) {
        Seq(
          Map[String, Any]("n" -> num(literal), "d" -> num("1"), "xs" -> Json.Arr()),
          Map[String, Any]("n" -> num("1"), "d" -> num(literal), "xs" -> Json.Arr()),
          Map[String, Any]("n" -> num("1"), "d" -> num("1"), "xs" -> Json.Arr(num(literal)))
        ).foreach { arguments =>
          val (ex, ms) = timed(intercept[IllegalArgumentException](fn(arguments)))
          ex.getMessage should include("maxNumberExponent")
          ms should be < 500L
        }
      }
    }
    calls.get shouldBe 1
  }

  test("a number written as a JSON string is bounded too, and only when it becomes a number") {
    // zio-json's numeric decoders also accept a JSON string; for BigDecimal that string may carry
    // any exponent (premise), so the raw scale would reach the handler through this door.
    JsonDecoder[BigDecimal].fromJsonAST(Json.Str("1e999999999")).map(_.scale) shouldBe Right(
      -999999999
    )
    JsonDecoder[BigInt].fromJsonAST(Json.Str("1e9")).isLeft shouldBe true // digits only

    // ...without an integer part too (not JSON, but accepted from a string)...
    JsonDecoder[BigDecimal].fromJsonAST(Json.Str(".5e999999999")).map(_.scale) shouldBe Right(
      -999999998
    )
    // ...and so may a long run of fraction digits, with no exponent at all.
    val manyFractionDigits = "0." + "0" * 2000 + "1"
    JsonDecoder[BigDecimal].fromJsonAST(Json.Str(manyFractionDigits)).map(_.scale) shouldBe Right(
      2001
    )

    Seq(
      "1e999999999",
      "-1.5E+2147483647",
      "1e-999999999",
      "0.001e-99999",
      ".5e999999999",
      "-.5E-999999999",
      manyFractionDigits
    ).foreach { text =>
      withClue(text) {
        val node = Json.Str(text)
        val shapes: Seq[() => Any] = Seq(
          () => DefaultDecodeContext.decodeRaw[BigDecimal]("d", node, ctx, JsonDecoder[BigDecimal]),
          () =>
            DefaultDecodeContext.decodeRaw[java.math.BigDecimal](
              "d",
              node,
              ctx,
              JsonDecoder[java.math.BigDecimal]
            ),
          () =>
            DefaultDecodeContext.decodeRaw[Option[BigDecimal]](
              "d",
              node,
              ctx,
              JsonDecoder[Option[BigDecimal]]
            ),
          () =>
            DefaultDecodeContext.decodeRaw[List[BigDecimal]](
              "d",
              Json.Arr(Json.Num(1), node),
              ctx,
              JsonDecoder[List[BigDecimal]]
            ),
          () =>
            DefaultDecodeContext.decodeRaw[Map[String, BigDecimal]](
              "d",
              Json.Obj("k" -> node),
              ctx,
              JsonDecoder[Map[String, BigDecimal]]
            ),
          () => McpDecoder[Quote].decode("q", Map("label" -> Json.Str("x"), "amount" -> node), ctx),
          () => McpDecoder[DecArgs].decode("a", Map("d" -> text), ctx) // raw Scala String
        )
        shapes.foreach { decode =>
          val (ex, ms) = timed(intercept[IllegalArgumentException](decode()))
          ex.getMessage should include("limits.maxNumber")
          ms should be < 500L
        }

        // The same text bound for a String stays a string: nothing is refused on its looks.
        DefaultDecodeContext.decodeRaw[String]("s", node, ctx, JsonDecoder[String]) shouldBe text
        McpDecoder[Note].decode(
          "n",
          Map("text" -> node, "tags" -> Json.Arr(node, Json.Str("1e5"))),
          ctx
        ) shouldBe Note(text, List(text, "1e5"))
        DefaultDecodeContext.decodeRaw[Json]("j", Json.Arr(node), ctx, JsonDecoder[Json]) shouldBe
          Json.Arr(node)
      }
    }
    // in range, a string-encoded decimal still decodes
    McpDecoder[Quote].decode(
      "q",
      Map("label" -> Json.Str("1e999999999"), "amount" -> Json.Str("12.50e2")),
      ctx
    ) shouldBe Quote("1e999999999", BigDecimal("1250.0"))
    DefaultDecodeContext.decodeRaw[BigDecimal](
      "d",
      Json.Str("1.5e-512"),
      ctx,
      JsonDecoder[BigDecimal]
    ) shouldBe BigDecimal("1.5e-512")
  }

  // ---- JSON embedded in string arguments ----

  test("embedded JSON strings get the array, value-count and number bounds (-32602)") {
    val limits = LimitSettings()
    val longArray = Seq.fill(limits.maxArrayElements + 1)("1").mkString("[", ",", "]")
    val ex1 = intercept[IllegalArgumentException](ctx.parseJsonArray("blob", longArray))
    ex1.getMessage should include("maxArrayElements")
    ctx.parseJsonArray("blob", Seq.fill(limits.maxArrayElements)("1").mkString("[", ",", "]")) should
      have size limits.maxArrayElements.toLong

    val rows = Seq.fill(1000)("[1,2,3]").mkString("[", ",", "]")
    val many = (1 to 24).map(i => s""""t$i":$rows""").mkString("{", ",", "}")
    val ex2 = intercept[IllegalArgumentException](ctx.parseJsonObject("blob", many))
    ex2.getMessage should include("maxValues")

    Seq("1e999999999", "-1E+2147483647", "1e-999999999").foreach { literal =>
      val (ex, ms) =
        timed(intercept[IllegalArgumentException](ctx.parseJsonArray("blob", s"[1,$literal]")))
      ex.getMessage should include("maxNumberExponent")
      ms should be < 500L
      intercept[IllegalArgumentException](
        ctx.parseJsonObject("blob", s"""{"a":{"b":$literal}}""")
      ).getMessage should include("maxNumberExponent")
    }
    intercept[IllegalArgumentException](
      ctx.parseJsonArray("blob", "[" + "1" * (limits.maxNumberDigits + 1) + "]")
    ).getMessage should include("maxNumberDigits")
    McpError.fromThrowable(ex1).code shouldBe -32602

    val small = new DefaultDecodeContext(maxArrayElements = 2, maxValues = 6)
    small.parseJsonArray("blob", "[1,2]") shouldBe List(1, 2)
    intercept[IllegalArgumentException](small.parseJsonArray("blob", "[1,2,3]")).getMessage should
      include("maxArrayElements (2)")
    intercept[IllegalArgumentException](
      small.parseJsonArray("blob", "[[1,2],[3,4]]")
    ).getMessage should include("maxValues (6)")
    ctx.parseJsonArray("blob", "[1,2.5,1e3,\"1e999999999\"]") shouldBe List(1, 2.5, 1000, "1e999999999")
  }

  // ---- end to end ----

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""

  /** A schema for [[DecArgs]] that admits a decimal written as a JSON string as well as a number.
    * The schema derived from `BigDecimal` advertises `number` only, and input-schema validation
    * refuses a string for it before any decoder runs; a tool that takes string-encoded decimals
    * has to say so, as here.
    */
  private val decimalOrTextSchema = ToolInputSchema.unsafeFromJsonString(
    """{"type":"object","properties":{"d":{"type":["number","string"]}},"required":["d"]}"""
  )

  private def callFrame(id: Int, tool: String, arguments: String): String =
    s"""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}"""

  test("tools/call: a BigInt or BigDecimal parameter never sees an out-of-range number") {
    val handled = new AtomicInteger(0)
    val limits = LimitSettings()
    val server = McpServer("Numbers", "0.1.0")
    runUnsafe(
      server.tool(McpTool[BigArgs, String](name = "big", description = Some("bit length")) { a =>
        val _ = handled.incrementAndGet()
        s"bits=${a.n.bitLength}"
      }) *>
        server.tool(McpTool[DecArgs, String](name = "dec", description = Some("scale")) { a =>
          val _ = handled.incrementAndGet()
          s"scale=${a.d.scale}"
        }) *>
        server.tool(
          McpTool.withSchema[DecArgs, String](
            name = "decText",
            inputSchema = decimalOrTextSchema,
            description = Some("scale of a decimal that may be written as a string")
          ) { a =>
            val _ = handled.incrementAndGet()
            s"scale=${a.d.scale}"
          }
        )
    )
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("numbers"))
    def reply(frame: String): String =
      runUnsafe(MessageLoop.handleFrame(router, session, frame)).getOrElse(fail("no reply"))
    reply(initFrame) should include("serverInfo")

    // inside the limits: decoded, and the expansion stays inside the documented bit bound
    reply(callFrame(2, "big", """{"n":1e400}""")) should include("bits=1329")
    reply(callFrame(3, "big", """{"n":18446744073709551615}""")) should include("bits=64")
    reply(callFrame(4, "big", s"""{"n":9e${limits.maxNumberExponent}}""")) should include(
      s"bits=${(BigInt(9) * BigInt(10).pow(limits.maxNumberExponent)).bitLength}"
    )
    (BigInt(9) * BigInt(10).pow(limits.maxNumberExponent)).bitLength should be <= maxBits(limits)
    maxBits(limits) shouldBe 3402 // (512 + 512) decimal digits
    reply(callFrame(5, "dec", """{"d":1.5e-300}""")) should include("scale=301")
    handled.get shouldBe 4

    // A string-encoded number passes the frame check (strings are never interpreted there). What
    // refuses it next depends on what the tool advertises:
    //   - `number` (the schema derived from `BigDecimal`): input-schema validation refuses the
    //     string before any decoder sees it;
    val typed = reply(callFrame(40, "dec", """{"d":"1e999999999"}"""))
    typed should include(""""isError":true""")
    typed should include("'d' must be number")
    handled.get shouldBe 4
    //   - a schema that admits a string-encoded decimal: the guard in front of the decoder refuses
    //     it when it turns into a BigDecimal. An in-band tool error either way; the handler never
    //     runs.
    Seq("1e999999999", "1e-2147483647").zipWithIndex.foreach { (text, i) =>
      val (asString, ms) = timed(reply(callFrame(50 + i, "decText", s"""{"d":"$text"}""")))
      asString should include(""""isError":true""")
      asString should include("maxNumberExponent")
      asString.length should be < 2048
      ms should be < 1000L
    }
    reply(callFrame(60, "decText", """{"d":"2.50"}""")) should include("scale=2")
    handled.get shouldBe 5

    hazardousExponents.zipWithIndex.foreach { (literal, i) =>
      withClue(literal) {
        // over the wire: refused at the frame check, before dispatch
        Seq("big" -> "n", "dec" -> "d").foreach { (tool, field) =>
          val (wire, ms) = timed(reply(callFrame(10 + i, tool, s"""{"$field":$literal}""")))
          wire should include("-32700")
          wire should include("maxNumberExponent")
          ms should be < 1000L

          // Past the frame check (an in-process caller) the call meets input-schema validation
          // and then the argument decoder guard; both answer from the number's text-sized parts,
          // never by expanding it. A negative exponent is not an integer, so for the integer-typed
          // `n` validation says so first; every other case reaches the decoder guard.
          val request = Request(
            RequestId.NumId(100L + i),
            "tools/call",
            Some(Json.Obj("name" -> Json.Str(tool), "arguments" -> Json.Obj(field -> num(literal))))
          )
          val (direct, directMs) =
            timed(runUnsafe(router.dispatch(session, request)).map(_.toJson).getOrElse(fail("no reply")))
          direct should include(""""isError":true""")
          if tool == "big" && literal.contains("e-") then direct should include("'n' must be integer")
          else direct should include("maxNumberExponent")
          direct.length should be < 2048
          directMs should be < 1000L
        }
      }
    }
    handled.get shouldBe 5
  }

  test("server wiring: configured limits reach the argument decoders and embedded JSON") {
    val handled = new AtomicInteger(0)
    val settings = McpServerSettings(limits =
      LimitSettings(maxArrayElements = 3, maxValues = 64, maxNumberDigits = 8, maxNumberExponent = 4)
    )
    val server = McpServer("Tight", "0.1.0", settings)
    // A custom decoder that parses its string argument as embedded JSON through the context the
    // server hands it, and reports how many elements came out.
    given McpDecoder[BlobArgs] = McpDecoder.instance { (name, raw, context) =>
      DefaultDecodeContext.toJsonAst(raw) match
        case Json.Obj(fields) =>
          fields.collectFirst { case ("json", Json.Str(text)) => text } match
            case Some(text) => BlobArgs(context.parseJsonArray(name, text).size.toString)
            case None => fail("no json argument")
        case other => fail(s"unexpected arguments $other")
    }
    runUnsafe(
      // Advertises number-or-string: the calls below send decimals as strings, which the schema
      // derived from `BigDecimal` (`number`) would have refused at input-schema validation.
      server.tool(
        McpTool.withSchema[DecArgs, String](
          name = "dec",
          inputSchema = decimalOrTextSchema,
          description = Some("scale")
        ) { a =>
          val _ = handled.incrementAndGet()
          s"scale=${a.d.scale}"
        }
      ) *>
        server.tool(McpTool[BlobArgs, String](name = "blob", description = Some("embedded JSON")) {
          a => s"elements=${a.json}"
        })
    )
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("tight"))
    def reply(frame: String): String =
      runUnsafe(MessageLoop.handleFrame(router, session, frame)).getOrElse(fail("no reply"))
    reply(initFrame) should include("serverInfo")

    // A string-encoded number passes the frame check; the decoder guard uses the server's bounds.
    // On a decoded value the text is gone, so what is enforced is the window the text bounds
    // imply: at most 8 significant digits, none outside 10^±(8 + 4).
    reply(callFrame(2, "dec", """{"d":"1e11"}""")) should include("scale=-11")
    val exponent = reply(callFrame(3, "dec", """{"d":"1e12"}"""))
    exponent should include(""""isError":true""")
    exponent should include("maxNumberExponent (4)")
    reply(callFrame(4, "dec", """{"d":"123456789"}""")) should include("maxNumberDigits (8)")
    handled.get shouldBe 1

    reply(callFrame(5, "blob", """{"json":"[1,2,3]"}""")) should include("elements=3")
    val tooLong = reply(callFrame(6, "blob", """{"json":"[1,2,3,4]"}"""))
    tooLong should include(""""isError":true""")
    tooLong should include("maxArrayElements (3)")
    reply(callFrame(7, "blob", """{"json":"[1e5]"}""")) should include("maxNumberExponent (4)")
    reply(callFrame(8, "blob", """{"json":"[[1,2,3],[4,5,6],[7,8,9]]"}""")) should include(
      "elements=3"
    )
  }

  test("prompts/get and resources/read: a string argument decoded into a BigDecimal is bounded") {
    // Prompt (and resource-template) arguments are always strings, so the frame check never sees
    // them as numbers: the guard in front of the argument decoder is the only line here.
    val handled = new AtomicInteger(0)
    val server = McpServer("Prompts", "0.1.0")
    runUnsafe(
      server.prompt(
        McpPrompt[DecArgs](
          name = "price",
          arguments = List(PromptArgument("d", Some("amount"), required = true))
        ) { a =>
          val _ = handled.incrementAndGet()
          List(Message(Role.User, TextContent(s"scale=${a.d.scale}")))
        }
      ) *>
        server.resource(
          McpTemplateResource[DecArgs](
            uriPattern = "price://{d}",
            arguments = List(ResourceArgument("d", Some("amount"), required = true))
          ) { a =>
            val _ = handled.incrementAndGet()
            s"scale=${a.d.scale}"
          }
        )
    )
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("prompts"))
    def reply(frame: String): String =
      runUnsafe(MessageLoop.handleFrame(router, session, frame)).getOrElse(fail("no reply"))
    def get(id: Int, value: String): String =
      s"""{"jsonrpc":"2.0","id":$id,"method":"prompts/get","params":{"name":"price","arguments":{"d":"$value"}}}"""
    reply(initFrame) should include("serverInfo")

    reply(get(2, "12.50")) should include("scale=2")
    Seq("1e999999999", "-.5E-2147483647", "0." + "0" * 2000 + "1").zipWithIndex.foreach { (text, i) =>
      withClue(text.take(32)) {
        val (refused, ms) = timed(reply(get(3 + i, text)))
        // An error reply, not a rendered prompt. (PromptManager reports every argument-decode
        // failure of a prompt as -32603 today; the code is not what this test is about.)
        refused should include(""""error":{""")
        refused should not include "scale="
        refused should include("limits.maxNumber")
        refused.length should be < 2048
        ms should be < 1000L
      }
    }
    handled.get shouldBe 1

    def read(id: Int, value: String): String =
      s"""{"jsonrpc":"2.0","id":$id,"method":"resources/read","params":{"uri":"price://$value"}}"""
    reply(read(10, "12.50")) should include("scale=2")
    Seq("1e999999999", "-.5E-2147483647").zipWithIndex.foreach { (text, i) =>
      withClue(text) {
        // (ResourceManager's error for a failed template read does not carry the cause.)
        val (refused, ms) = timed(reply(read(11 + i, text)))
        refused should include(""""error":{""")
        refused should not include "scale="
        refused.length should be < 2048
        ms should be < 1000L
      }
    }
    handled.get shouldBe 2
  }

  test("tools/call: an over-long array never reaches a handler decoder; a Set at the bound is cheap") {
    val decoded = new AtomicInteger(0)
    val handled = new AtomicInteger(0)
    given JsonDecoder[TagArgs] = counting(DeriveJsonDecoder.gen[TagArgs], decoded)
    val limits = LimitSettings()
    val server = McpServer("Tags", "0.1.0")
    runUnsafe(
      server.tool(McpTool[TagArgs, String](name = "tags", description = Some("count")) { a =>
        val _ = handled.incrementAndGet()
        s"distinct=${a.tags.size}"
      })
    )
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("tags"))
    def reply(frame: String): String =
      runUnsafe(MessageLoop.handleFrame(router, session, frame)).getOrElse(fail("no reply"))
    reply(initFrame) should include("serverInfo")

    val colliding = collidingStrings(10) // 59 049 distinct strings, one hashCode
    colliding.map(_.hashCode).distinct.size shouldBe 1
    def tags(n: Int): String = colliding.take(n).map(s => s""""$s"""").mkString("""{"tags":[""", ",", "]}")

    reply(callFrame(2, "tags", tags(8))) should include("distinct=8") // warm-up
    decoded.get shouldBe 1

    // one element over the bound, and a 30 000-element array that used to cost ~4.5e8 comparisons
    Seq(limits.maxArrayElements + 1, 30_000).zipWithIndex.foreach { (n, i) =>
      val (refused, ms) = timed(reply(callFrame(3 + i, "tags", tags(n))))
      refused should include("-32700")
      refused should include("maxArrayElements")
      ms should be < 1000L
    }
    decoded.get shouldBe 1
    handled.get shouldBe 1

    // exactly at the bound, every element colliding: the worst case one array can cost
    val (atBound, ms) = timed(reply(callFrame(9, "tags", tags(limits.maxArrayElements))))
    atBound should include(s"distinct=${limits.maxArrayElements}")
    info(s"Set[String] of ${limits.maxArrayElements} fully colliding elements decoded in $ms ms")
    ms should be < 2000L
  }

  test("the per-frame worst case for hash-building decoders stays inside the documented budget") {
    // maxValues caps the elements of all arrays together, so the most colliding work one frame can
    // buy is (maxValues / maxArrayElements) arrays of maxArrayElements colliding elements each:
    // maxArrayElements x maxValues / 2 string comparisons.
    val limits = LimitSettings()
    val perArray = limits.maxArrayElements
    val arrays = (limits.maxValues - 64) / (perArray + 1)
    val colliding = collidingStrings(8).take(perArray) // 6561 available
    colliding should have size perArray.toLong
    colliding.map(_.hashCode).distinct.size shouldBe 1
    val one = colliding.map(s => s""""$s"""").mkString("[", ",", "]")
    val frame = callFrame(1, "t", (1 to arrays).map(i => s""""s$i":$one""").mkString("{", ",", "}"))
    frame.length should be <= limits.maxFrameChars
    val (parsed, parseMs) = timed(MessageLoop.parseFrame(frame))
    val arguments = parsed match
      case Right(Request(_, _, Some(Json.Obj(params)))) =>
        JsonFields.get(params, "arguments").getOrElse(fail("arguments"))
      case other => fail(s"unexpected $other")
    val (sets, decodeMs) = timed(JsonDecoder[Map[String, Set[String]]].fromJsonAST(arguments))
    sets.map(_.size) shouldBe Right(arrays)
    sets.map(_.values.map(_.size).toSet) shouldBe Right(Set(perArray))
    info(
      s"$arrays arrays x $perArray colliding strings (${frame.length} chars): parse $parseMs ms, Set decode $decodeMs ms"
    )
    // About half a second on an idle JVM core (the documented per-frame budget); the ceiling
    // leaves an order of magnitude for a loaded CI machine.
    parseMs should be < 2000L
    decodeMs should be < 5000L
    // one more array is over maxValues and is refused before any of that work
    val over = callFrame(
      1,
      "t",
      (1 to arrays + 1).map(i => s""""s$i":$one""").mkString("{", ",", "}")
    )
    MessageLoop.parseFrame(over) match
      case Left(JsonRpcMessage.Failure(None, err)) =>
        err.code shouldBe -32700
        err.message should include("maxValues")
      case other => fail(s"expected -32700, got $other")
  }
