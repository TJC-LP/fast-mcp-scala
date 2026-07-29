package com.tjclp.fastmcp.jsonrpc

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import sttp.tapir.generic.auto.*
import zio.*
import zio.json.*

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.jsonrpc.JsonRpcMessage.*
import com.tjclp.fastmcp.server.McpServer
import com.tjclp.fastmcp.server.router.Session
import com.tjclp.fastmcp.server.transport.MessageLoop

/** Envelope-level regression net for the hand-rolled [[JsonRpcMessage]] codec: structural
  * discrimination, id-shape echo fidelity, the JSON-RPC violations that must answer `-32600`
  * (previously `id: null` decoded as a droppable notification and a missing `jsonrpc` was
  * accepted), and unknown-field tolerance (2026-07-28 forward compatibility: new `_meta`-style
  * fields must never break decoding).
  */
class JsonRpcEnvelopeTest extends AnyFunSuite with Matchers:

  case class NoArgs(x: Option[Int] = None)

  private def decode(s: String): JsonRpcMessage =
    s.fromJson[JsonRpcMessage].fold(err => fail(s"decode failed: $err"), identity)

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  test("structural discrimination: request / notification / success / failure") {
    decode("""{"jsonrpc":"2.0","id":1,"method":"ping"}""") shouldBe
      Request(RequestId.NumId(1), "ping", None)
    decode("""{"jsonrpc":"2.0","method":"notifications/initialized"}""") shouldBe
      Notification("notifications/initialized", None)
    decode("""{"jsonrpc":"2.0","id":"a","result":{}}""") shouldBe
      Success(RequestId.StrId("a"), zio.json.ast.Json.Obj())
    decode("""{"jsonrpc":"2.0","id":2,"error":{"code":-32601,"message":"nope"}}""") shouldBe
      Failure(Some(RequestId.NumId(2)), JsonRpcErrorObject(-32601, "nope"))
  }

  test("id shape is preserved: string ids stay strings, numeric stay numbers") {
    (Success(RequestId.StrId("42"), zio.json.ast.Json.Obj()): JsonRpcMessage).toJson should
      include(""""id":"42"""")
    (Success(RequestId.NumId(42), zio.json.ast.Json.Obj()): JsonRpcMessage).toJson should
      include(""""id":42""")
  }

  test("request with explicit id:null is Invalid, not a notification") {
    decode("""{"jsonrpc":"2.0","id":null,"method":"tools/list"}""") match
      case Invalid(None, reason) => reason should include("must not be null")
      case other => fail(s"expected Invalid, got $other")
  }

  test("missing or wrong jsonrpc version is Invalid with the id echoed") {
    decode("""{"id":7,"method":"tools/list"}""") match
      case Invalid(Some(RequestId.NumId(7)), reason) => reason should include("jsonrpc")
      case other => fail(s"expected Invalid(id=7), got $other")
    decode("""{"jsonrpc":"1.0","id":7,"method":"tools/list"}""") shouldBe a[Invalid]
  }

  test("fractional ids and non-string methods are Invalid") {
    decode("""{"jsonrpc":"2.0","id":1.5,"method":"tools/list"}""") shouldBe a[Invalid]
    decode("""{"jsonrpc":"2.0","id":1,"method":42}""") match
      case Invalid(Some(RequestId.NumId(1)), reason) => reason should include("method")
      case other => fail(s"expected Invalid(id=1), got $other")
  }

  test("unknown top-level fields are tolerated (forward compatibility)") {
    decode("""{"jsonrpc":"2.0","id":1,"method":"ping","x-new-field":{"a":1}}""") shouldBe
      Request(RequestId.NumId(1), "ping", None)
  }

  test("the router answers Invalid frames with -32600 and a null id") {
    val server = McpServer("EnvelopeServer")
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("env"))
    val reply = runUnsafe(
      MessageLoop.handleFrame(router, session, """{"jsonrpc":"2.0","id":null,"method":"ping"}""")
    ).getOrElse(fail("no reply"))
    reply should include(""""code":-32600""")
    reply should include(""""id":null""")
  }

  test("tools/call with non-object arguments answers -32602") {
    val server = McpServer("EnvelopeServer2")
    runUnsafe(
      server.tool(
        McpTool[NoArgs, String](name = "noop", description = Some("noop"))(_ => ZIO.succeed("ok"))
      )
    )
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("env2"))
    runUnsafe(
      MessageLoop.handleFrame(
        router,
        session,
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""
      )
    )
    val reply = runUnsafe(
      MessageLoop.handleFrame(
        router,
        session,
        """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"noop","arguments":42}}"""
      )
    ).getOrElse(fail("no reply"))
    reply should include(""""code":-32602""")
    reply should include("must be a JSON object")
  }
