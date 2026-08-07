package com.tjclp.fastmcp
package server.transport

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.ErrorCodes
import com.tjclp.fastmcp.jsonrpc.{JsonRpcErrorObject, JsonRpcMessage, RequestId}
import com.tjclp.fastmcp.server.McpServer
import com.tjclp.fastmcp.server.router.McpRouter

/** Unit pins for the shared 2026-07-28 HTTP validation: version classification precedence and,
  * critically, validateRequest's ordering — an unsupported version must answer -32022 (with
  * `data.supported`) before `_meta` decoding gets a chance to answer a misleading -32602.
  */
class ModernHttpValidationTest extends AnyFunSuite with Matchers:

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private val router: McpRouter[Any] =
    runUnsafe(McpServer.typed[Any]("ValidationT", "0.1.0").buildRouter)

  private def headers(pairs: (String, String)*): String => Option[String] =
    pairs.toMap.get

  private def parseParams(json: String): Option[Json] =
    json.fromJson[Json].toOption

  private def request(method: String, params: Option[Json]): JsonRpcMessage.Request =
    JsonRpcMessage.Request(RequestId.StrId("1"), method, params)

  private val legacyBody = request("tools/list", None)

  private val modernMeta =
    """{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}"""

  test("requestedVersion prefers the header and falls back to the body _meta declaration") {
    val bodyDeclared = request("tools/list", parseParams(modernMeta))
    ModernHttpValidation.requestedVersion(
      headers("mcp-protocol-version" -> "2099-01-01"),
      bodyDeclared
    ) shouldBe Some("2099-01-01")
    ModernHttpValidation.requestedVersion(headers(), bodyDeclared) shouldBe Some("2026-07-28")
    ModernHttpValidation.requestedVersion(headers(), legacyBody) shouldBe None
  }

  test("isModern: legacy header no, unknown header yes, body declaration yes, nothing no") {
    ModernHttpValidation.isModern(
      headers("mcp-protocol-version" -> "2025-11-25"),
      legacyBody
    ) shouldBe false
    ModernHttpValidation.isModern(
      headers("mcp-protocol-version" -> "bogus"),
      legacyBody
    ) shouldBe true
    ModernHttpValidation.isModern(
      headers(),
      request("tools/list", parseParams(modernMeta))
    ) shouldBe true
    ModernHttpValidation.isModern(headers(), legacyBody) shouldBe false
  }

  test("validateRequest answers -32022 for an unknown version BEFORE the _meta decode -32602") {
    val result = ModernHttpValidation.validateRequest(
      router,
      legacyBody,
      headers(
        "content-type" -> "application/json",
        "accept" -> "application/json, text/event-stream",
        "mcp-protocol-version" -> "2099-01-01",
        "mcp-method" -> "tools/list"
      )
    )
    result match
      case Left((status, err)) =>
        status shouldBe 400
        err.code shouldBe ErrorCodes.UnsupportedProtocolVersion
        err.data.map(_.toString).getOrElse("") should include("2026-07-28")
      case Right(_) => fail("expected -32022")
  }

  test("validateRequest without any version still fails _meta decoding with -32602") {
    val result = ModernHttpValidation.validateRequest(
      router,
      request("tools/list", parseParams("""{"cursor":null}""")),
      headers(
        "content-type" -> "application/json",
        "accept" -> "application/json, text/event-stream",
        "mcp-method" -> "tools/list"
      )
    )
    result match
      case Left((status, err)) =>
        status shouldBe 400
        err.code shouldBe ErrorCodes.InvalidParams
        err.message should include("_meta")
      case Right(_) => fail("expected -32602")
  }

  test("validateRequest accepts a well-formed modern request") {
    // server/discover is wired on every router; tools/list would need a registered tool.
    ModernHttpValidation.validateRequest(
      router,
      request("server/discover", parseParams(modernMeta)),
      headers(
        "content-type" -> "application/json",
        "accept" -> "application/json, text/event-stream",
        "mcp-protocol-version" -> "2026-07-28",
        "mcp-method" -> "server/discover"
      )
    ) shouldBe Right(())
  }

  test("errorStatus maps -32020/-32021/-32022 failures to 400 and nothing else") {
    def failure(code: Int): JsonRpcMessage =
      JsonRpcMessage.Failure(None, JsonRpcErrorObject(code, "x", None))
    ModernHttpValidation.errorStatus(failure(ErrorCodes.HeaderMismatch)) shouldBe Some(400)
    ModernHttpValidation.errorStatus(
      failure(ErrorCodes.MissingRequiredClientCapability)
    ) shouldBe Some(400)
    ModernHttpValidation.errorStatus(
      failure(ErrorCodes.UnsupportedProtocolVersion)
    ) shouldBe Some(400)
    ModernHttpValidation.errorStatus(failure(ErrorCodes.InvalidParams)) shouldBe None
  }
