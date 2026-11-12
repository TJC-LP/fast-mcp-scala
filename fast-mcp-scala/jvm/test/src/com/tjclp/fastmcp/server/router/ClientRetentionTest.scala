package com.tjclp.fastmcp
package server.router

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.jsonrpc.{JsonRpcMessage, RequestId}

/** The budget for what a durable legacy session keeps from `initialize` ([[ClientRetention]]): how
  * the two retained members are weighed, where the bounds sit, and which sessions it applies to.
  */
class ClientRetentionTest extends AnyFunSuite with Matchers:

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private def params(capabilities: String, clientInfo: String, extra: String = ""): Json =
    s"""{"protocolVersion":"2025-11-25","capabilities":$capabilities,"clientInfo":$clientInfo$extra}"""
      .fromJson[Json]
      .fold(err => fail(err), identity)

  private val plainInfo = """{"name":"t","version":"1.0"}"""

  private def zeros(n: Int): String = List.fill(n)("0").mkString("[", ",", "]")

  test("an ordinary initialize is far inside the budget") {
    val capabilities =
      """{"roots":{"listChanged":true},"sampling":{},"elicitation":{"form":{},"url":{}},"tasks":{"list":{},"cancel":{},"requests":{"sampling":{"createMessage":{}},"elicitation":{"create":{}}}},"extensions":{"io.modelcontextprotocol/ui":{"mimeTypes":["text/html;profile=mcp-app"]}}}"""
    val clientInfo =
      """{"name":"client","version":"1.2.3","title":"A client","websiteUrl":"https://example.com","icons":[{"src":"https://example.com/icon.png","mimeType":"image/png","sizes":["48x48","96x96"]}]}"""
    ClientRetention.overBudget(params(capabilities, clientInfo)) shouldBe None
  }

  test("the value bound counts every JSON value of the two members, the members included") {
    // clientInfo: the object + 2 strings = 3. capabilities: the object, `experimental`, the array
    // = 3. The array's elements make up the rest.
    val fixed = 6
    val atBound = params(s"""{"experimental":{"x":${zeros(ClientRetention.MaxValues - fixed)}}}""", plainInfo)
    ClientRetention.overBudget(atBound) shouldBe None
    val onePast =
      params(s"""{"experimental":{"x":${zeros(ClientRetention.MaxValues - fixed + 1)}}}""", plainInfo)
    ClientRetention.overBudget(onePast).getOrElse("") should include("JSON values")
  }

  test("the character bound counts member names, strings and number digits") {
    // Names `name` + `version`, the string "1.0", and the n characters of the name itself.
    val fixed = "name".length + "version".length + "1.0".length
    def named(n: Int) = params("{}", s"""{"name":"${"n" * n}","version":"1.0"}""")
    ClientRetention.overBudget(named(ClientRetention.MaxChars - fixed)) shouldBe None
    ClientRetention.overBudget(named(ClientRetention.MaxChars - fixed + 1)).getOrElse("") should
      include("characters")

    // Long member names count like long strings do.
    val longKey = params(s"""{"experimental":{"${"k" * (ClientRetention.MaxChars + 1)}":1}}""", plainInfo)
    ClientRetention.overBudget(longKey).getOrElse("") should include("characters")

    // So do the digits of a long number (built directly: the JSON reader has its own bound).
    val longNumber = Json.Obj(
      "capabilities" -> Json.Obj(
        "experimental" -> Json.Obj(
          "x" -> Json.Num(new java.math.BigDecimal("9" * (ClientRetention.MaxChars * 2)))
        )
      )
    )
    ClientRetention.overBudget(longNumber).getOrElse("") should include("characters")
  }

  test("a number with a huge exponent keeps one digit and is not charged for its magnitude") {
    val tiny = Json.Obj(
      "capabilities" -> Json.Obj(
        "experimental" -> Json.Obj(
          "x" -> Json.Arr(
            Json.Num(new java.math.BigDecimal("1e900000")),
            Json.Num(new java.math.BigDecimal("1e-900000"))
          )
        )
      )
    )
    ClientRetention.overBudget(tiny) shouldBe None
  }

  test("members that are not retained are not weighed") {
    val big = zeros(10 * ClientRetention.MaxValues)
    val meta = params("{}", plainInfo, extra = s""","_meta":{"x":$big},"other":$big""")
    ClientRetention.overBudget(meta) shouldBe None
  }

  test("a retained member that appears twice is weighed twice") {
    val half = Json.Arr(Chunk.fill(ClientRetention.MaxValues / 2)(Json.Num(0)))
    def capabilities = Json.Obj("experimental" -> Json.Obj("x" -> half))
    val info = Json.Obj("name" -> Json.Str("t"), "version" -> Json.Str("1.0"))
    val once = Json.Obj("capabilities" -> capabilities, "clientInfo" -> info)
    ClientRetention.overBudget(once) shouldBe None
    val twice =
      Json.Obj("capabilities" -> capabilities, "capabilities" -> capabilities, "clientInfo" -> info)
    ClientRetention.overBudget(twice).getOrElse("") should include("JSON values")
  }

  test("params that are not an object are left to the decoder") {
    ClientRetention.overBudget(Json.Null) shouldBe None
    ClientRetention.overBudget(Json.Arr()) shouldBe None
  }

  test("an enormous member is refused at the bound, without being walked") {
    // Three levels of a thousand shared children: a billion values by count, a few kilobytes in
    // memory. Walking them all takes far longer than this test allows; stopping at the bound is
    // a few hundred steps.
    def thousand(child: Json): Json = Json.Arr(Chunk.fill(1000)(child))
    val huge = Json.Obj(
      "capabilities" -> Json.Obj(
        "experimental" -> Json.Obj("x" -> thousand(thousand(thousand(Json.Num(0)))))
      )
    )
    val started = java.lang.System.nanoTime()
    ClientRetention.overBudget(huge).getOrElse("") should include("JSON values")
    val elapsedMs = (java.lang.System.nanoTime() - started) / 1_000_000
    withClue(s"$elapsedMs ms: ") { elapsedMs should be < 1000L }
  }

  // ---- which sessions the budget applies to ----

  private val overBudgetInit: JsonRpcMessage =
    JsonRpcMessage.Request(
      RequestId.NumId(1),
      "initialize",
      Some(params(s"""{"experimental":{"x":${zeros(2 * ClientRetention.MaxValues)}}}""", plainInfo))
    )

  private val plainInit: JsonRpcMessage =
    JsonRpcMessage.Request(RequestId.NumId(1), "initialize", Some(params("{}", plainInfo)))

  private def dispatch(session: Session, message: JsonRpcMessage): String =
    val server = McpServer.typed[Any]("RetentionT", "0.1.0", McpServerSettings())
    runUnsafe(server.buildRouter.flatMap(_.dispatch(session, message)))
      .map(_.toJson)
      .getOrElse(fail("no reply"))

  test("a durable legacy session refuses an over-budget initialize with -32602 and retains nothing") {
    val session = runUnsafe(Session.makeProtocolSession("sid-1"))
    val reply = dispatch(session, overBudgetInit)
    reply should include("-32602")
    reply should include("JSON values")
    runUnsafe(session.clientInfo) shouldBe None
    runUnsafe(session.clientCapabilities) shouldBe None
    runUnsafe(session.isInitialized) shouldBe false
  }

  test("an over-budget re-initialize keeps what the session already holds") {
    val session = runUnsafe(Session.makeProtocolSession("sid-2"))
    dispatch(session, plainInit) should include("serverInfo")
    dispatch(session, overBudgetInit) should include("-32602")
    runUnsafe(session.clientInfo).map(_.name) shouldBe Some("t")
    runUnsafe(session.clientCapabilities).flatMap(_.experimental) shouldBe None
  }

  test("stdio and the sessionless legacy adapter store no session and are not budgeted") {
    for id <- List("stdio", "stateless") do
      val session = runUnsafe(Session.make(id))
      dispatch(session, overBudgetInit) should include("serverInfo")
  }
