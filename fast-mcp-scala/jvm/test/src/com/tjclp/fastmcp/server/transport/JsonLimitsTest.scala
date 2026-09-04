package com.tjclp.fastmcp
package server.transport

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.codec.JsonLimits
import com.tjclp.fastmcp.jsonrpc.*
import com.tjclp.fastmcp.jsonrpc.JsonRpcMessage.*
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.router.Session
import com.tjclp.fastmcp.server.transport.JvmTransportBackend.given

/** The inbound frame limits at the `MessageLoop.parseFrame` choke point (TJC-2295 / F1, F3): the
  * hash-collision object bound, the depth bound, the frame-length bound, string-awareness of the
  * pre-scan, exact boundaries, and the no-HashMap envelope decoder (last-wins duplicate keys, no
  * error-body amplification). Transport-independent — these exercise the shared code every
  * platform routes through.
  */
class JsonLimitsTest extends AnyFunSuite with Matchers:

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  /** 3^blocks distinct keys of length 2·blocks that all share one `String.hashCode` — "Aa", "BB"
    * and "C#" hash to 2112, and the polynomial hash is block-compositional.
    */
  private def collidingKeys(blocks: Int): IndexedSeq[String] =
    val parts = Array("Aa", "BB", "C#")
    val count = math.pow(3, blocks).toInt
    (0 until count).map { k =>
      val sb = new StringBuilder(2 * blocks)
      var rest = k
      var b = 0
      while b < blocks do
        sb.append(parts(rest % 3))
        rest /= 3
        b += 1
      sb.result()
    }

  /** `{"jsonrpc":"2.0","id":1,"method":"ping",<keys>:0,…}` — the colliding object IS the envelope. */
  private def collidingEnvelope(keys: IndexedSeq[String]): String =
    val sb = new StringBuilder(keys.length * (keys.head.length + 6) + 64)
    sb.append("""{"jsonrpc":"2.0","id":1,"method":"ping"""")
    keys.foreach(k => sb.append(",\"").append(k).append("\":0"))
    sb.append('}').result()

  private def collidingObject(keys: IndexedSeq[String]): String =
    val sb = new StringBuilder(keys.length * (keys.head.length + 6) + 2)
    sb.append('{')
    keys.zipWithIndex.foreach { (k, i) =>
      if i > 0 then sb.append(',')
      sb.append('"').append(k).append("\":0")
    }
    sb.append('}').result()

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

  private val big = LimitSettings(maxFrameChars = 8 * 1024 * 1024)

  // ---- F1: colliding keys ----

  test("colliding keys share one hashCode (the attack premise holds on this JVM)") {
    val keys = collidingKeys(5)
    keys.map(_.hashCode).distinct.size shouldBe 1
    keys.distinct.size shouldBe keys.size
  }

  test("2^15+ and 2^17+ colliding keys are rejected by the pre-scan in well under a second") {
    val warm = collidingKeys(6)
    val _ = MessageLoop.parseFrame(collidingEnvelope(warm), big) // warm-up

    val k10 = collidingKeys(10) // 59 049 > 2^15
    val k11 = collidingKeys(11) // 177 147 > 2^17
    k10.size should be > (1 << 15)
    k11.size should be > (1 << 17)

    val (r10, ms10) = timed(MessageLoop.parseFrame(collidingEnvelope(k10), big))
    parseErrorMessage(r10) should include("maxObjectFields")
    ms10 should be < 200L

    val (r11, ms11) = timed(MessageLoop.parseFrame(collidingEnvelope(k11), big))
    parseErrorMessage(r11) should include("maxObjectFields")
    ms11 should be < 200L
  }

  test("a colliding object nested under params.arguments is rejected too") {
    val frame =
      s"""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"t","arguments":${collidingObject(
          collidingKeys(10)
        )}}}"""
    val (result, ms) = timed(MessageLoop.parseFrame(frame, big))
    parseErrorMessage(result) should include("maxObjectFields")
    ms should be < 200L
  }

  test("a colliding frame does not delay a concurrent ping on the same router") {
    val server = McpServer("Limits", "0.1.0")
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("limits"))
    val colliding = collidingEnvelope(collidingKeys(10)) // ~1.5 MB, inside the 4 MiB default
    val ping = """{"jsonrpc":"2.0","id":7,"method":"ping"}"""
    val _ = runUnsafe(MessageLoop.handleFrame(router, session, ping)) // warm-up

    val ((bad, good), ms) = timed(
      runUnsafe(
        MessageLoop
          .handleFrame(router, session, colliding)
          .zipPar(MessageLoop.handleFrame(router, session, ping))
      )
    )
    bad.getOrElse("") should include("-32700")
    bad.getOrElse("") should include("maxObjectFields")
    good.getOrElse("") should include(""""result":{}""")
    ms should be < 500L
  }

  // ---- F3: depth ----

  test("100 000 nested arrays are rejected as maxDepth before the parser runs") {
    val frame = "[" * 100_000 + "]" * 100_000
    val (result, ms) = timed(MessageLoop.parseFrame(frame))
    parseErrorMessage(result) should include("maxDepth")
    ms should be < 200L
  }

  test("300 nested objects are rejected; 60-deep tools/call arguments are accepted") {
    val deepObj = """{"a":""" * 300 + "1" + "}" * 300
    parseErrorMessage(MessageLoop.parseFrame(deepObj)) should include("maxDepth")

    // envelope (1) + params (2) + arguments (3) + 60 nested arrays = depth 63 <= 64
    val args = "[" * 60 + "1" + "]" * 60
    val frame =
      s"""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"t","arguments":{"x":$args}}}"""
    MessageLoop.parseFrame(frame) match
      case Right(Request(RequestId.NumId(1), "tools/call", Some(_))) => succeed
      case other => fail(s"expected a Request, got $other")
  }

  // ---- frame length ----

  test("frames longer than maxFrameChars are rejected without parsing") {
    val huge = "x" * (LimitSettings().maxFrameChars + 1)
    parseErrorMessage(MessageLoop.parseFrame(huge)) should include("maxFrameChars")

    val eleven = """{"a":12345}"""
    eleven.length shouldBe 11
    parseErrorMessage(MessageLoop.parseFrame(eleven, LimitSettings(maxFrameChars = 10))) should include(
      "maxFrameChars"
    )
    MessageLoop.parseFrame(eleven, LimitSettings(maxFrameChars = 11)) match
      case Right(Invalid(_, _)) => succeed // parsed (and structurally invalid) — the limit passed
      case other => fail(s"unexpected $other")
  }

  // ---- string-awareness ----

  test("brackets, braces and commas inside strings (keys or values) never count") {
    val tight = LimitSettings(maxObjectFields = 4, maxDepth = 2)
    val valueFrame =
      """{"jsonrpc":"2.0","id":1,"method":"ping","params":{"s":"{{{[[[,,,\\\"}"}}"""
    MessageLoop.parseFrame(valueFrame, tight) match
      case Right(Request(_, "ping", Some(Json.Obj(fields)))) =>
        fields.head._2 shouldBe Json.Str("""{{{[[[,,,\"}""")
      case other => fail(s"unexpected $other")

    val keyFrame = """{"jsonrpc":"2.0","id":1,"method":"ping","params":{"a,{b\"[":1}}"""
    MessageLoop.parseFrame(keyFrame, tight) match
      case Right(Request(_, "ping", Some(_))) => succeed
      case other => fail(s"unexpected $other")
  }

  // ---- exact boundaries ----

  test("maxObjectFields is inclusive: N members pass, N+1 fail (pre-scan and AST validate)") {
    val four = LimitSettings(maxObjectFields = 4)
    val fourMembers = """{"jsonrpc":"2.0","id":1,"method":"ping","params":{}}"""
    val fiveMembers = """{"jsonrpc":"2.0","id":1,"method":"ping","params":{},"x":1}"""
    MessageLoop.parseFrame(fourMembers, four).isRight shouldBe true
    parseErrorMessage(MessageLoop.parseFrame(fiveMembers, four)) should include("maxObjectFields")

    def obj(n: Int): Json = Json.Obj(Chunk.fromIterable((1 to n).map(i => s"k$i" -> Json.Num(i))))
    JsonLimits.validate(obj(4), 64, 4) shouldBe None
    JsonLimits.validate(obj(5), 64, 4) shouldBe Some(JsonLimits.Violation.TooManyFields(4))
    // nested objects are checked too
    JsonLimits.validate(Json.Obj("inner" -> obj(5)), 64, 4) shouldBe Some(
      JsonLimits.Violation.TooManyFields(4)
    )
    JsonLimits.preScan(obj(5).toJson, Int.MaxValue, 64, 4) shouldBe Some(
      JsonLimits.Violation.TooManyFields(4)
    )
    JsonLimits.preScan(obj(4).toJson, Int.MaxValue, 64, 4) shouldBe None
  }

  test("maxDepth is inclusive: depth N passes, N+1 fails (pre-scan and AST validate)") {
    val three = LimitSettings(maxDepth = 3)
    val depth3 = """{"jsonrpc":"2.0","id":1,"method":"ping","params":{"a":{"b":1}}}"""
    val depth4 = """{"jsonrpc":"2.0","id":1,"method":"ping","params":{"a":{"b":{"c":1}}}}"""
    MessageLoop.parseFrame(depth3, three).isRight shouldBe true
    parseErrorMessage(MessageLoop.parseFrame(depth4, three)) should include("maxDepth")

    val ast3 = depth3.fromJson[Json].toOption.get
    val ast4 = depth4.fromJson[Json].toOption.get
    JsonLimits.validate(ast3, 3, 1024) shouldBe None
    JsonLimits.validate(ast4, 3, 1024) shouldBe Some(JsonLimits.Violation.TooDeep(3))
    // scalars do not add depth: a string at depth N+1 inside an object at depth N is fine
    JsonLimits.validate(Json.Arr(Json.Arr(Json.Str("leaf"))), 2, 1024) shouldBe None
  }

  // ---- envelope decoder semantics ----

  test("duplicate keys keep last-wins semantics (as Chunk.toMap did)") {
    MessageLoop.parseFrame("""{"jsonrpc":"2.0","id":1,"id":2,"method":"ping"}""") shouldBe Right(
      Request(RequestId.NumId(2), "ping", None)
    )
    JsonFields.get(Json.Obj("a" -> Json.Num(1), "a" -> Json.Num(2)), "a") shouldBe Some(Json.Num(2))
    JsonFields.get(Json.Obj("a" -> Json.Num(1)), "b") shouldBe None
    JsonFields.get(Json.Str("not an object"), "a") shouldBe None
  }

  test("unknown top-level fields are tolerated and never materialised") {
    MessageLoop.parseFrame(
      """{"jsonrpc":"2.0","id":1,"method":"ping","extra":{"x":[1,2,3]},"more":true}"""
    ) shouldBe Right(Request(RequestId.NumId(1), "ping", None))
  }

  test("router wiring: a server-configured maxDepth floor is applied, defaults stay usable") {
    val initFrame =
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""
    // envelope (1) + params (2) + 8 arrays = depth 10
    val tenDeep = s"""{"jsonrpc":"2.0","id":2,"method":"ping","params":{"a":${"[" * 8}1${"]" * 8}}}"""

    val tight = McpServer("Tight", "0.1.0", McpServerSettings(limits = LimitSettings(maxDepth = 8)))
    val tightRouter = runUnsafe(tight.buildRouter)
    tightRouter.limits.maxDepth shouldBe 8
    val tightSession = runUnsafe(Session.make("tight"))
    runUnsafe(MessageLoop.handleFrame(tightRouter, tightSession, initFrame)).getOrElse("") should include(
      "\"serverInfo\""
    )
    val rejected = runUnsafe(MessageLoop.handleFrame(tightRouter, tightSession, tenDeep)).getOrElse("")
    rejected should include("-32700")
    rejected should include("maxDepth")

    val loose = McpServer("Loose", "0.1.0")
    val looseRouter = runUnsafe(loose.buildRouter)
    val looseSession = runUnsafe(Session.make("loose"))
    runUnsafe(MessageLoop.handleFrame(looseRouter, looseSession, tenDeep)).getOrElse("") should include(
      """"result":{}"""
    )
  }

  // ---- error-body amplification ----

  test("a 1 MiB top-level array yields a small -32700 body naming the type, not echoing it") {
    val ones = "[" + "1," * 500_000 + "1]"
    ones.length should be > 1_000_000
    MessageLoop.parseFrame(ones, big) match
      case Left(failure) =>
        val body = failure.toJson
        body.length should be < 1024
        body should include("got: array")
      case other => fail(s"unexpected $other")
  }

  test("a 1 MiB array `id` yields a small -32600 body naming the type") {
    val ones = "[" + "1," * 500_000 + "1]"
    val frame = s"""{"jsonrpc":"2.0","id":$ones,"method":"ping"}"""
    val message = MessageLoop.parseFrame(frame, big) match
      case Right(invalid: Invalid) =>
        invalid.reason should include("got: array")
        invalid
      case other => fail(s"unexpected $other")
    val server = McpServer("Amp", "0.1.0")
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("amp"))
    val reply = runUnsafe(router.dispatch(session, message)).map(_.toJson).getOrElse("")
    reply should include("-32600")
    reply.length should be < 1024
  }

  // ---- construction guards ----

  test("LimitSettings validates its values at construction") {
    val _ = an[IllegalArgumentException] should be thrownBy LimitSettings(maxDepth = 0)
    val _ = an[IllegalArgumentException] should be thrownBy LimitSettings(maxDepth = 257)
    val _ = an[IllegalArgumentException] should be thrownBy LimitSettings(maxObjectFields = 0)
    val _ = an[IllegalArgumentException] should be thrownBy LimitSettings(maxFrameChars = 0)
    val _ = an[IllegalArgumentException] should be thrownBy LimitSettings(maxUriChars = 0)
    val _ = an[IllegalArgumentException] should be thrownBy LimitSettings(maxSubscriptionsPerSession = 0)
    LimitSettings(maxDepth = LimitSettings.MaxSupportedDepth).maxDepth shouldBe 256
    LimitSettings().maxFrameChars shouldBe 4 * 1024 * 1024
    LimitSettings().maxObjectFields shouldBe 1024
  }
