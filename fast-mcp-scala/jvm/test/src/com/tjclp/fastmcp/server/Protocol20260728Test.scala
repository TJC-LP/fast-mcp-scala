package com.tjclp.fastmcp.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.*
import zio.json.ast.Json
import sttp.tapir.generic.auto.*

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.jsonrpc.{JsonRpcMessage, RequestId}
import com.tjclp.fastmcp.server.router.Session
import com.tjclp.fastmcp.server.transport.MessageLoop

class Protocol20260728Test extends AnyFunSuite with Matchers:

  private case class RootsArgs()
  private case class HeaderArgs(region: String)
  private case class IntegerHeaderArgs(shard: Int)

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit unsafe => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private def frame(
      router: com.tjclp.fastmcp.server.router.McpRouter[Any],
      session: Session,
      value: String
  ): String =
    runUnsafe(MessageLoop.handleFrame(router, session, value)).getOrElse(fail("no response"))

  private val rootsMeta =
    """"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{"roots":{}},"io.modelcontextprotocol/clientInfo":{"name":"modern-test","version":"1.0"}}"""

  /** MRTR keys are opaque and content-derived (`input-<hash>-<occurrence>`); tests extract them
    * from the `inputRequests` map rather than assuming a literal.
    */
  private val InputKeyPattern = """"inputRequests":\{"(input-[0-9a-f]+-\d+)"""".r

  private def inputKeyOf(response: String): String =
    InputKeyPattern
      .findFirstMatchIn(response)
      .map(_.group(1))
      .getOrElse(fail(s"no input key in: $response"))

  test("server/discover returns typed identity, capabilities, and conservative cache hints") {
    val server = McpServer("ModernDiscovery", "0.9.0")
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("modern-discovery"))
    val response = frame(
      router,
      session,
      s"""{"jsonrpc":"2.0","id":1,"method":"server/discover","params":{$rootsMeta}}"""
    )

    response should include(""""resultType":"complete"""")
    response should include(""""supportedVersions":["2026-07-28"""")
    response should include(""""ttlMs":0""")
    response should include(""""cacheScope":"private"""")
    response should include("io.modelcontextprotocol/serverInfo")
    response should include("ModernDiscovery")
  }

  test("MRTR returns input_required and completes when the original request is retried") {
    val server = McpServer("ModernMrtr", "0.9.0")
    runUnsafe(
      server.tool(
        McpTool[RootsArgs, String](name = "workspace-root").contextual { (_, context) =>
          context match
            case Some(value) => value.listRoots().map(_.roots.map(_.uri).mkString(","))
            case None => ZIO.fail(new IllegalStateException("missing context"))
        }
      )
    )
    runUnsafe(
      server.tool(
        McpTool[RootsArgs, String](name = "stateful-input").contextual { (_, context) =>
          context.flatMap(_.getRequestState) match
            case Some("opaque-state") => ZIO.succeed("resumed")
            case Some(other) => ZIO.fail(new IllegalArgumentException(s"unexpected state: $other"))
            case None =>
              context.get
                .sendRequest("roots/list", None, requestState = Some("opaque-state"))
                .as("unreachable")
        }
      )
    )
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("modern-mrtr"))

    val first = frame(
      router,
      session,
      s"""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"workspace-root","arguments":{},$rootsMeta}}"""
    )
    first should include(""""resultType":"input_required"""")
    first should include(""""method":"roots/list"""")
    val rootsKey = inputKeyOf(first)

    val retried = frame(
      router,
      session,
      s"""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"workspace-root","arguments":{},"inputResponses":{"$rootsKey":{"resultType":"complete","roots":[{"uri":"file:///workspace","name":"workspace"}]}},$rootsMeta}}"""
    )
    retried should include(""""resultType":"complete"""")
    retried should include("file:///workspace")

    val stateful = frame(
      router,
      session,
      s"""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"stateful-input","arguments":{},$rootsMeta}}"""
    )
    stateful should include(""""requestState":"opaque-state"""")

    val resumed = frame(
      router,
      session,
      s"""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"stateful-input","arguments":{},"inputResponses":{"${inputKeyOf(stateful)}":{"resultType":"complete","roots":[]}},"requestState":"opaque-state",$rootsMeta}}"""
    )
    resumed should include("resumed")
    runUnsafe(session.outbound.poll) shouldBe None
  }

  test("parallel MRTR questions get distinct keys and answers route to the asking branch") {
    val server = McpServer("ModernParMrtr", "0.9.0")
    runUnsafe(
      server.tool(
        McpTool[RootsArgs, String](name = "par-input").contextual { (_, context) =>
          val ctx = context.get
          def ask(question: String) =
            ctx
              .sendRequest("elicitation/create", Some(Json.Obj("message" -> Json.Str(question))))
              .map {
                case Json.Obj(fields) =>
                  fields.toMap.get("answer").collect { case Json.Str(s) => s }.getOrElse("?")
                case other => other.toString
              }
          (ask("first") <&> ask("second")).map((a, b) => s"first=$a;second=$b")
        }
      )
    )
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("modern-par-mrtr"))
    def call(id: Int, responses: List[(String, String)]): String =
      val inputResponses =
        if responses.isEmpty then ""
        else
          responses
            .map((k, a) => s""""$k":{"answer":"$a"}""")
            .mkString(""""inputResponses":{""", ",", "},")
      frame(
        router,
        session,
        s"""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"par-input","arguments":{},$inputResponses$rootsMeta}}"""
      )

    // Trip 1: zipPar surfaces one question (nondeterministic which); answer exactly that one.
    val trip1 = call(1, Nil)
    trip1 should include(""""resultType":"input_required"""")
    val k1 = inputKeyOf(trip1)
    val q1 = if trip1.contains(""""message":"first"""") then "first" else "second"

    // Trip 2: the answered branch resolves from inputResponses; the sibling asks with a DIFFERENT
    // key carrying the other question.
    val trip2 = call(2, List(k1 -> s"answer-$q1"))
    val k2 = inputKeyOf(trip2)
    k2 should not be k1
    val q2 = if q1 == "first" then "second" else "first"
    trip2 should include(s""""message":"$q2"""")

    // Trip 3: both answers present — each branch must receive the answer to ITS question.
    val trip3 = call(3, List(k1 -> s"answer-$q1", k2 -> s"answer-$q2"))
    trip3 should include("first=answer-first;second=answer-second")
  }

  test("modern requests enforce capabilities and reject removed core methods") {
    val server = McpServer("ModernErrors", "0.9.0")
    runUnsafe(
      server.tool(
        McpTool[RootsArgs, String](name = "needs-roots").contextual { (_, context) =>
          context.get.listRoots().as("ok")
        }
      )
    )
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("modern-errors"))
    val emptyMeta =
      """"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}"""

    val missingCapability = frame(
      router,
      session,
      s"""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"needs-roots","arguments":{},$emptyMeta}}"""
    )
    missingCapability should include(""""code":-32021""")
    missingCapability should include("requiredCapabilities")

    val ping = frame(
      router,
      session,
      s"""{"jsonrpc":"2.0","id":2,"method":"ping","params":{$emptyMeta}}"""
    )
    ping should include(""""code":-32601""")
  }

  test("cacheable list results are deterministic and carry required cache fields") {
    val server = McpServer("ModernLists", "0.9.0")
    runUnsafe(server.tool(McpTool[RootsArgs, String](name = "z-tool")(_ => "z")))
    runUnsafe(server.tool(McpTool[RootsArgs, String](name = "a-tool")(_ => "a")))
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("modern-lists"))
    val response = frame(
      router,
      session,
      s"""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{$rootsMeta}}"""
    )

    response.indexOf("a-tool") should be < response.indexOf("z-tool")
    response should include(""""ttlMs":0""")
    response should include(""""cacheScope":"private"""")
    response should include(""""resultType":"complete"""")

    val legacySession = runUnsafe(Session.make("legacy-lists"))
    frame(
      router,
      legacySession,
      """{"jsonrpc":"2.0","id":2,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"legacy","version":"1.0"}}}"""
    )
    val legacy = frame(
      router,
      legacySession,
      """{"jsonrpc":"2.0","id":3,"method":"tools/list"}"""
    )
    legacy should not include "ttlMs"
    legacy should not include "cacheScope"
  }

  test("request metadata is required and unsupported stateless versions are structured errors") {
    val server = McpServer("ModernVersions", "0.9.0")
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("modern-versions"))

    val missingCapabilities = frame(
      router,
      session,
      """{"jsonrpc":"2.0","id":1,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28"}}}"""
    )
    missingCapabilities should include(""""code":-32602""")

    val unsupported = frame(
      router,
      session,
      """{"jsonrpc":"2.0","id":2,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2099-01-01","io.modelcontextprotocol/clientCapabilities":{}}}}"""
    )
    unsupported should include(""""code":-32022""")
    unsupported should include(""""requested":"2099-01-01"""")
    unsupported should include(""""supported":["2026-07-28"]""")
  }

  test("request metadata preserves W3C trace context for tool handlers") {
    val server = McpServer("TraceContext", "0.9.0")
    runUnsafe(
      server.tool(
        McpTool[RootsArgs, String](name = "trace-context").contextual { (_, context) =>
          context
            .flatMap(_.requestMetadata("traceparent"))
            .collect { case Json.Str(value) => value }
            .getOrElse("missing")
        }
      )
    )
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("trace-context"))
    val response = frame(
      router,
      session,
      """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"trace-context","arguments":{},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{},"traceparent":"00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"}}}"""
    )
    response should include("00-4bf92f3577b34da6a3ce929d0e0e4736")
  }

  test("modern logging is silent unless the request opts in with a log level") {
    val server = McpServer(
      "ModernLogging",
      "0.9.0",
      McpServerSettings(loggingEnabled = true)
    )
    runUnsafe(
      server.tool(
        McpTool[RootsArgs, String](name = "log-once").contextual { (_, context) =>
          context.get.sendLogMessage(LoggingLevel.Info, Json.Str("hello")).as("done")
        }
      )
    )
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("modern-logging"))
    val noLogMeta =
      """"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}"""
    val withLogMeta =
      """"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{},"io.modelcontextprotocol/logLevel":"info"}"""

    frame(
      router,
      session,
      s"""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"log-once","arguments":{},$noLogMeta}}"""
    )
    runUnsafe(session.outbound.poll) shouldBe None

    frame(
      router,
      session,
      s"""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"log-once","arguments":{},$withLogMeta}}"""
    )
    val notification = runUnsafe(session.outbound.take)
    notification.toJson should include("notifications/message")
  }

  test("subscriptions/listen acknowledges first and carries its request id in metadata") {
    val server = McpServer("ModernSubscriptions", "0.9.0")
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("modern-subscriptions"))
    val params =
      """{"notifications":{"toolsListChanged":true},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}"""
        .fromJson[Json]
        .fold(error => fail(error), identity)
    val request = JsonRpcMessage.Request(
      RequestId.StrId("subscription-1"),
      "subscriptions/listen",
      Some(params)
    )
    val first = runUnsafe(
      for
        fiber <- router.dispatch(session, request).fork
        acknowledgement <- session.outbound.take
        _ <- fiber.interrupt
      yield acknowledgement
    )

    first.toJson should include("notifications/subscriptions/acknowledged")
    first.toJson should include("io.modelcontextprotocol/subscriptionId")
    first.toJson should include("subscription-1")
  }

  test("HTTP header validation decodes Base64 names and enforces x-mcp-header arguments") {
    val schema = ToolInputSchema.unsafeFromJsonString(
      """{"type":"object","properties":{"region":{"type":"string","x-mcp-header":"Region"}},"required":["region"]}"""
    )
    val server = McpServer("HeaderValidation", "0.9.0")
    runUnsafe(
      server.tool(
        McpTool.withSchema[HeaderArgs, String]("héader", schema)(args => args.region)
      )
    )
    val router = runUnsafe(server.buildRouter)
    val request = JsonRpcMessage.Request(
      RequestId.NumId(1),
      "tools/call",
      Some(
        Json.Obj(
          "name" -> Json.Str("héader"),
          "arguments" -> Json.Obj("region" -> Json.Str("us-west1"))
        )
      )
    )
    val validHeaders = Map(
      "mcp-method" -> "tools/call",
      "mcp-name" -> "=?base64?aMOpYWRlcg==?=",
      "mcp-param-region" -> "us-west1"
    )
    router.validateHttpHeaders(request, name => validHeaders.get(name.toLowerCase)) shouldBe Right(())

    val missing = router.validateHttpHeaders(
      request,
      name => validHeaders.removed("mcp-param-region").get(name.toLowerCase)
    )
    missing.left.toOption.map(_.code) shouldBe Some(ErrorCodes.HeaderMismatch)

    val malformedUtf8 = router.validateHttpHeaders(
      request,
      name => validHeaders.updated("mcp-name", "=?base64?/w==?=").get(name.toLowerCase)
    )
    malformedUtf8.left.toOption.map(_.code) shouldBe Some(ErrorCodes.HeaderMismatch)

    val invalidHeaderSchema = ToolInputSchema.unsafeFromJsonString(
      """{"type":"object","properties":{"region":{"type":"string","x-mcp-header":"Région"}},"required":["region"]}"""
    )
    val invalidHeaderServer = McpServer("InvalidHeaderName", "0.9.0")
    runUnsafe(
      invalidHeaderServer.tool(
        McpTool.withSchema[HeaderArgs, String]("bad-header", invalidHeaderSchema)(_.region)
      )
    )
    val invalidHeaderRouter = runUnsafe(invalidHeaderServer.buildRouter)
    val invalidHeaderRequest = request.copy(
      params = Some(
        Json.Obj(
          "name" -> Json.Str("bad-header"),
          "arguments" -> Json.Obj("region" -> Json.Str("us-west1"))
        )
      )
    )
    val invalidHeaderName = invalidHeaderRouter.validateHttpHeaders(
      invalidHeaderRequest,
      name =>
        Map(
          "mcp-method" -> "tools/call",
          "mcp-name" -> "bad-header",
          "mcp-param-région" -> "us-west1"
        ).get(name.toLowerCase)
    )
    invalidHeaderName.left.toOption.map(_.code) shouldBe Some(ErrorCodes.HeaderMismatch)

    val integerSchema = ToolInputSchema.unsafeFromJsonString(
      """{"type":"object","properties":{"shard":{"type":"integer","x-mcp-header":"Shard"}},"required":["shard"]}"""
    )
    val integerServer = McpServer("IntegerHeaders", "0.9.0")
    runUnsafe(
      integerServer.tool(
        McpTool.withSchema[IntegerHeaderArgs, Int]("integer-header", integerSchema)(_.shard)
      )
    )
    val integerRouter = runUnsafe(integerServer.buildRouter)
    val integerRequest = JsonRpcMessage.Request(
      RequestId.NumId(2),
      "tools/call",
      Some(
        Json.Obj(
          "name" -> Json.Str("integer-header"),
          "arguments" -> Json.Obj("shard" -> Json.Num(BigDecimal(42)))
        )
      )
    )
    val integerHeaders = Map(
      "mcp-method" -> "tools/call",
      "mcp-name" -> "integer-header",
      "mcp-param-shard" -> "42.0"
    )
    integerRouter.validateHttpHeaders(
      integerRequest,
      name => integerHeaders.get(name.toLowerCase)
    ) shouldBe Right(())
  }

  test("the Tasks extension returns unsolicited bearer handles and polls through tasks/get") {
    val server = McpServer(
      "ModernTasks",
      "0.9.0",
      McpServerSettings(tasks = TaskSettings(enabled = true, pollIntervalMs = 10))
    )
    runUnsafe(
      server.tool(
        McpTool[RootsArgs, String](name = "async-tool")(_ =>
          ZIO.sleep(25.millis).as("task complete")
        ).withTaskSupport(TaskSupport.Optional)
      )
    )
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("modern-tasks"))
    val taskMeta =
      s""""_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{"extensions":{"${Tasks.ExtensionId}":{}}}}"""

    val listed = frame(
      router,
      session,
      s"""{"jsonrpc":"2.0","id":20,"method":"tools/list","params":{$taskMeta}}"""
    )
    listed should not include "taskSupport"

    val created = frame(
      router,
      session,
      s"""{"jsonrpc":"2.0","id":21,"method":"tools/call","params":{"name":"async-tool","arguments":{},$taskMeta}}"""
    )
    created should include("\"resultType\":\"task\"")
    val taskId = "\"taskId\":\"([^\"]+)\"".r
      .findFirstMatchIn(created)
      .map(_.group(1))
      .getOrElse(fail(s"missing task id in $created"))

    runUnsafe(ZIO.sleep(75.millis))
    val completed = frame(
      router,
      session,
      s"""{"jsonrpc":"2.0","id":22,"method":"tasks/get","params":{"taskId":"$taskId",$taskMeta}}"""
    )
    completed should include("\"resultType\":\"complete\"")
    completed should include("\"status\":\"completed\"")
    completed should include("task complete")
  }
