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

  test("a Base64 sentinel whose padding is missing is malformed (SEP-2243: invalid padding)") {
    // "Hello" is `SGVsbG8=`; the JDK decoder also accepts the unpadded spelling.
    HttpHeaderValidation.validate(
      toolsCall.copy(params = Some(Json.Obj("name" -> Json.Str("Hello")))),
      Map("mcp-method" -> "tools/call", "mcp-name" -> "=?base64?SGVsbG8=?=").get,
      Map.empty
    ) shouldBe Right(())
    val unpadded = HttpHeaderValidation.validate(
      toolsCall.copy(params = Some(Json.Obj("name" -> Json.Str("Hello")))),
      Map("mcp-method" -> "tools/call", "mcp-name" -> "=?base64?SGVsbG8?=").get,
      Map.empty
    )
    val err = unpadded.left.getOrElse(fail("expected Left"))
    err.code shouldBe -32020
    err.message shouldBe "Malformed Base64 header sentinel"
  }

  test("a Base64 sentinel that is not the canonical encoding of its bytes is malformed") {
    // `YWR=` decodes to "ad" like `YWQ=` does, with unused trailing bits set; `YWRk` is "add".
    val err = validate("=?base64?YWR=?=").left.getOrElse(fail("expected Left"))
    err.code shouldBe -32020
    err.message shouldBe "Malformed Base64 header sentinel"
    // The URL-safe alphabet is not Base64 as the sentinel defines it.
    validate("=?base64?YWRk_-?=").isLeft shouldBe true
  }

  private def taskRequest(method: String, params: Json): JsonRpcMessage.Request =
    JsonRpcMessage.Request(RequestId.NumId(7), method, Some(params))

  private def validateTask(
      method: String,
      params: Json,
      mcpName: Option[String]
  ): Either[McpError, Unit] =
    val headers = Map("mcp-method" -> method) ++ mcpName.map("mcp-name" -> _)
    HttpHeaderValidation.validate(taskRequest(method, params), headers.get, Map.empty)

  test("tasks/get, tasks/update and tasks/cancel require Mcp-Name to equal params.taskId") {
    val update = Json.Obj("taskId" -> Json.Str("task-1"), "inputResponses" -> Json.Obj())
    val byId = Json.Obj("taskId" -> Json.Str("task-1"))
    for (method, params) <- List("tasks/get" -> byId, "tasks/cancel" -> byId, "tasks/update" -> update)
    do
      withClue(method) {
        validateTask(method, params, Some("task-1")) shouldBe Right(())
        validateTask(method, params, Some("  task-1  ")).isLeft shouldBe true
        val mismatch = validateTask(method, params, Some("task-2"))
        mismatch.left.toOption.map(_.code) shouldBe Some(-32020)
        mismatch.left.toOption.map(_.message).getOrElse("") should include("Mcp-Name")
        val missing = validateTask(method, params, None)
        missing.left.toOption.map(_.code) shouldBe Some(-32020)
        missing.left.toOption.map(_.message).getOrElse("") should include("missing required")
      }
  }

  test("the task id in Mcp-Name may use the Base64 sentinel") {
    val params = Json.Obj("taskId" -> Json.Str("tâche"))
    validateTask("tasks/get", params, Some("=?base64?dMOiY2hl?=")) shouldBe Right(())
    validateTask("tasks/get", params, Some("=?base64?dGFjaGU=?=")).isLeft shouldBe true
  }

  test("a task request without a string taskId is left to its handler; other methods need no Mcp-Name") {
    validateTask("tasks/get", Json.Obj(), None) shouldBe Right(())
    validateTask("tasks/get", Json.Obj("taskId" -> Json.Num(BigDecimal(1))), None) shouldBe Right(())
    // Removed from 2026-07-28 and answered -32601 by the router: no name source.
    validateTask("tasks/result", Json.Obj("taskId" -> Json.Str("task-1")), None) shouldBe Right(())
    validateTask("tools/list", Json.Obj(), None) shouldBe Right(())
  }

  private def repeated(names: String*): Option[String] =
    HttpHeaderValidation.repeatedHeader(names.iterator)

  test("repeatedHeader: one occurrence of each routing header is not a repeat") {
    repeated() shouldBe None
    repeated("host", "content-type", "accept", "accept", "x-custom", "x-custom") shouldBe None
    repeated(
      "MCP-Protocol-Version",
      "Mcp-Method",
      "Mcp-Name",
      "Mcp-Param-Region",
      "Mcp-Param-Shard",
      "Mcp-Param-Zone",
      "mcp-session-id",
      "mcp-session-id"
    ) shouldBe None
    // Names that merely resemble a routing header.
    repeated("mcp-names", "mcp-names", "x-mcp-name", "x-mcp-name", "mcp-para", "mcp-para") shouldBe None
  }

  test("repeatedHeader: a second occurrence is found whatever the case and the position") {
    repeated("mcp-name", "host", "Mcp-Name") shouldBe Some("Mcp-Name")
    repeated("MCP-METHOD", "mcp-method") shouldBe Some("Mcp-Method")
    repeated("mcp-protocol-version", "accept", "MCP-Protocol-Version") shouldBe
      Some("MCP-Protocol-Version")
    repeated("Mcp-Param-Region", "mcp-param-region") shouldBe Some("Mcp-Param-region")
    repeated("mcp-param-a", "mcp-param-b", "mcp-param-c", "MCP-PARAM-C") shouldBe Some("Mcp-Param-c")
    repeated("mcp-param-a", "mcp-param-b", "mcp-param-b") shouldBe Some("Mcp-Param-b")
    repeated("mcp-param-", "mcp-param-") shouldBe Some("Mcp-Param-")
  }

  test("repeatedHeader: a repeated version header is reported before any other repeat") {
    repeated("mcp-name", "mcp-name", "mcp-method", "mcp-method", "mcp-protocol-version", "mcp-protocol-version") shouldBe
      Some(HttpHeaderValidation.ProtocolVersionHeader)
    repeated("mcp-param-a", "mcp-param-a", "mcp-name", "mcp-name") shouldBe Some("Mcp-Name")
  }

  test("repeatedHeader: parameter names differing only in ASCII case are one header") {
    repeated("Mcp-Param-ID", "mcp-param-id") shouldBe Some("Mcp-Param-id")
    repeated("MCP-PARAM-TITLE", "Mcp-Param-Title") shouldBe Some("Mcp-Param-title")
  }

  test("repeatedHeaderError is the header-mismatch error") {
    val err = HttpHeaderValidation.repeatedHeaderError("Mcp-Name")
    err.code shouldBe -32020
    err.message should include("Mcp-Name")
  }

  test("a plain header value still works and mismatches are reported") {
    validate("add") shouldBe Right(())
    validate("subtract").isLeft shouldBe true
  }
