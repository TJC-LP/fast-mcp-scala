package com.tjclp.fastmcp
package server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.core.wire.*
import com.tjclp.fastmcp.jsonrpc.{JsonRpcErrorObject, JsonRpcMessage, McpError, RequestId}
import com.tjclp.fastmcp.server.router.{McpRouter, Session}
import com.tjclp.fastmcp.server.transport.JvmTransportBackend.given

/** Server→client request/response correlation: the `Session` pending registry + `McpRouter` response
  * routing, and the three capability-gated `McpContext` methods built on top — `listRoots`,
  * `createMessage` (sampling), `elicit`.
  *
  * The round-trip is synthetic: fork the server-initiated call, drain the session's outbound queue to
  * capture the emitted JSON-RPC `Request`, then feed a matching `Success`/`Failure` back through
  * `router.dispatch`, exactly as a real bidirectional transport does when the client replies.
  */
class ServerRequestTest extends AnyFunSuite with Matchers:

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private val fullCaps = ClientCapabilities(
    roots = Some(RootsCapability()),
    sampling = Some(SamplingCapability()),
    elicitation = Some(ElicitationCapability(url = Some(Json.Obj())))
  )

  private def freshRouter: McpRouter[Any] =
    runUnsafe(McpServer.typed[Any]("ServerRequestTest", "0.1.0").buildRouter)

  private def ast[A: JsonEncoder](a: A): Json = a.toJsonAST.toOption.get

  /** Fork `call`, capture the emitted outbound `Request`, route `respond(id)` back through the
    * router, and return both the captured request and the call's resolved outcome.
    */
  private def roundTrip[A](
      router: McpRouter[Any],
      session: Session,
      call: IO[McpError, A],
      respond: RequestId => JsonRpcMessage
  ): (JsonRpcMessage, Either[McpError, A]) =
    runUnsafe(
      for
        fiber <- call.either.fork
        sent <- session.outbound.take
        id <- sent match
          case JsonRpcMessage.Request(rid, _, _) => ZIO.succeed(rid)
          case other => ZIO.die(new RuntimeException(s"expected a Request, got $other"))
        _ <- router.dispatch(session, respond(id))
        out <- fiber.join
      yield (sent, out)
    )

  // ---------- happy-path round-trips, one per method ----------

  test("listRoots emits roots/list (no params) and resolves with the client's response") {
    val router = freshRouter
    val session = runUnsafe(Session.make("roots-ok"))
    val ctx = McpContext.withSession(session, clientCapabilities = Some(fullCaps))
    val expected = ListRootsResult(List(Root("file:///work", Some("work"))))
    val (sent, out) =
      roundTrip(router, session, ctx.listRoots(), id => JsonRpcMessage.Success(id, ast(expected)))
    sent match
      case JsonRpcMessage.Request(_, method, params) =>
        method shouldBe "roots/list"
        params shouldBe None
      case other => fail(s"expected Request, got $other")
    out shouldBe Right(expected)
  }

  test("createMessage emits sampling/createMessage with encoded params and decodes the result") {
    val router = freshRouter
    val session = runUnsafe(Session.make("sampling-ok"))
    val ctx = McpContext.withSession(session, clientCapabilities = Some(fullCaps))
    val params = CreateMessageRequestParams(
      messages = List(SamplingMessage(Role.User, TextContent("hello"))),
      maxTokens = 100,
      systemPrompt = Some("be brief")
    )
    val result = CreateMessageResult(Role.Assistant, TextContent("hi"), "claude-test", Some("endTurn"))
    val (sent, out) = roundTrip(
      router,
      session,
      ctx.createMessage(params),
      id => JsonRpcMessage.Success(id, ast(result))
    )
    sent match
      case JsonRpcMessage.Request(_, method, ps) =>
        method shouldBe "sampling/createMessage"
        ps.flatMap(_.as[CreateMessageRequestParams].toOption) shouldBe Some(params)
      case other => fail(s"expected Request, got $other")
    out shouldBe Right(result)
  }

  test("elicit emits elicitation/create with encoded params and decodes the result") {
    val router = freshRouter
    val session = runUnsafe(Session.make("elicit-ok"))
    val ctx = McpContext.withSession(session, clientCapabilities = Some(fullCaps))
    val params = ElicitRequestParams(
      message = "What is your name?",
      requestedSchema = Json.Obj("type" -> Json.Str("object"))
    )
    val result = ElicitResult("accept", Some(Map("name" -> Json.Str("Ada"))))
    val (sent, out) = roundTrip(
      router,
      session,
      ctx.elicit(params),
      id => JsonRpcMessage.Success(id, ast(result))
    )
    sent match
      case JsonRpcMessage.Request(_, method, ps) =>
        method shouldBe "elicitation/create"
        ps.flatMap(_.as[ElicitRequestParams].toOption) shouldBe Some(params)
      case other => fail(s"expected Request, got $other")
    out shouldBe Right(result)
  }

  test("elicitUrl emits URL-mode elicitation/create with mode and elicitationId on the wire") {
    val router = freshRouter
    val session = runUnsafe(Session.make("elicit-url"))
    val ctx = McpContext.withSession(session, clientCapabilities = Some(fullCaps))
    val params = ElicitRequestUrlParams(
      message = "Complete sign-in in your browser",
      url = "https://example.com/auth",
      elicitationId = "elic-1"
    )
    val result = ElicitResult("accept")
    val (sent, out) = roundTrip(
      router,
      session,
      ctx.elicitUrl(params),
      id => JsonRpcMessage.Success(id, ast(result))
    )
    sent match
      case JsonRpcMessage.Request(_, method, ps) =>
        method shouldBe "elicitation/create"
        val wire = ps.map(_.toString).getOrElse("")
        wire should include(""""mode":"url"""")
        wire should include(""""elicitationId":"elic-1"""")
        ps.flatMap(_.as[ElicitRequestUrlParams].toOption) shouldBe Some(params)
      case other => fail(s"expected Request, got $other")
    out shouldBe Right(result)
  }

  // ---------- error/edge paths ----------

  test("a Failure response fails the pending request with the mapped McpError") {
    val router = freshRouter
    val session = runUnsafe(Session.make("roots-fail"))
    val ctx = McpContext.withSession(session, clientCapabilities = Some(fullCaps))
    val (_, out) = roundTrip(
      router,
      session,
      ctx.listRoots(),
      id => JsonRpcMessage.Failure(Some(id), JsonRpcErrorObject(-32000, "client refused", None))
    )
    out.swap.toOption.map(e => (e.code, e.message)) shouldBe Some((-32000, "client refused"))
  }

  test("a server request times out when no response ever arrives") {
    val session = runUnsafe(Session.make("timeout"))
    val ctx = McpContext.withSession(session, clientCapabilities = Some(fullCaps))
    val out = runUnsafe(ctx.listRoots(50.millis).either)
    val err = out.swap.toOption.get
    err.code shouldBe ErrorCodes.InternalError
    err.message should include("timed out")
  }

  test("an inbound Success with an unknown id is ignored, not an error") {
    val router = freshRouter
    val session = runUnsafe(Session.make("unknown-id"))
    val res = runUnsafe(
      router.dispatch(session, JsonRpcMessage.Success(RequestId.StrId("srv-999"), Json.Obj()))
    )
    res shouldBe None
  }

  test("an inbound Failure with a null id is ignored (uncorrelatable)") {
    val router = freshRouter
    val session = runUnsafe(Session.make("null-id"))
    val res = runUnsafe(
      router.dispatch(session, JsonRpcMessage.Failure(None, JsonRpcErrorObject(-32700, "parse", None)))
    )
    res shouldBe None
  }

  test("server requests without a session fail fast with an internal error") {
    val out = runUnsafe(McpContext.empty.sendRequest("roots/list", None).either)
    val err = out.swap.toOption.get
    err.code shouldBe ErrorCodes.InternalError
    err.message should include("session-bearing")
  }

  test("each typed request is gated on the matching client capability") {
    val session = runUnsafe(Session.make("no-caps"))
    val ctx = McpContext.withSession(session, clientCapabilities = Some(ClientCapabilities()))
    val roots = runUnsafe(ctx.listRoots().either)
    val sampling = runUnsafe(
      ctx
        .createMessage(CreateMessageRequestParams(List(SamplingMessage(Role.User, TextContent("x"))), 10))
        .either
    )
    val elicit = runUnsafe(ctx.elicit(ElicitRequestParams("msg", Json.Obj())).either)
    roots.swap.toOption.get.code shouldBe ErrorCodes.InvalidRequest
    sampling.swap.toOption.get.code shouldBe ErrorCodes.InvalidRequest
    elicit.swap.toOption.get.code shouldBe ErrorCodes.InvalidRequest
    roots.swap.toOption.get.message should include("roots")
    sampling.swap.toOption.get.message should include("sampling")
    elicit.swap.toOption.get.message should include("elicitation")
  }
