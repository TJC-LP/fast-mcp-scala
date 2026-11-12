package com.tjclp.fastmcp
package server.transport

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.json.ast.Json

import com.tjclp.fastmcp.jsonrpc.{JsonRpcMessage, McpError, RequestId}

/** F10 §2: a sentinel-shaped `Mcp-Name` value shorter than `=?base64?` + `?=` (or otherwise
  * malformed) must yield the `Malformed Base64 header sentinel` McpError, never throw. The Bun
  * end-to-end proof is `JsServerHttpTest`; this pins the shared decoder on the JVM.
  */
class HttpHeaderValidationTest extends AnyFunSuite with Matchers:

  private val toolsCall = JsonRpcMessage.Request(
    RequestId.NumId(1),
    "tools/call",
    Some(Json.Obj("name" -> Json.Str("add"), "arguments" -> Json.Obj()))
  )

  private def validate(mcpName: String): Either[McpError, Unit] =
    val headers = Map("mcp-method" -> "tools/call", "mcp-name" -> mcpName)
    HttpHeaderValidation.validate(toolsCall, headers.get, Map.empty)

  test("`=?base64?=` (prefix and suffix overlapping) is a malformed sentinel, not an exception") {
    val result = validate("=?base64?=")
    result.isLeft shouldBe true
    val err = result.left.getOrElse(fail("expected Left"))
    err.code shouldBe -32020
    err.message shouldBe "Malformed Base64 header sentinel"
  }

  test("`=?base64??=` (empty payload) is a malformed sentinel") {
    val err = validate("=?base64??=").left.getOrElse(fail("expected Left"))
    err.code shouldBe -32020
    err.message shouldBe "Malformed Base64 header sentinel"
  }

  test("`=?base64?!!?=` (invalid Base64 payload) is a malformed sentinel") {
    val err = validate("=?base64?!!?=").left.getOrElse(fail("expected Left"))
    err.code shouldBe -32020
    err.message shouldBe "Malformed Base64 header sentinel"
  }

  test("a valid Base64 sentinel decodes and matches the body name") {
    validate("=?base64?YWRk?=") shouldBe Right(()) // "add"
    val mismatch = validate("=?base64?c3Vi?=").left.getOrElse(fail("expected Left")) // "sub"
    mismatch.code shouldBe -32020
    mismatch.message should include("does not match")
  }

  test("a plain header value still works and mismatches are reported") {
    validate("add") shouldBe Right(())
    validate("subtract").isLeft shouldBe true
  }
