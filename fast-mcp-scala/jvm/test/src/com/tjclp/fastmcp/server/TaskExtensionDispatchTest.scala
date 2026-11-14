package com.tjclp.fastmcp.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.testkit.Await
import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.core.wire.ElicitRequestParams
import com.tjclp.fastmcp.jsonrpc.McpError
import com.tjclp.fastmcp.server.manager.TaskManager
import com.tjclp.fastmcp.server.router.{McpRouter, Session}
import com.tjclp.fastmcp.server.transport.MessageLoop

/** The 2026-07-28 Tasks extension (`io.modelcontextprotocol/tasks`, SEP-2663) at the router: the
  * capability gate on the task methods, the removed `params.task`, the acknowledgement-only
  * `tasks/cancel` and `tasks/update`, and a task that waits for input (`input_required`).
  */
class TaskExtensionDispatchTest extends AnyFunSuite with Matchers:

  private case class NoArgs()
  private case class SleepArgs(ms: Int)

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit unsafe => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private val Declaring =
    s"""{"elicitation":{},"extensions":{"${Tasks.ExtensionId}":{}}}"""
  private val NotDeclaring = """{"elicitation":{}}"""

  private def meta(capabilities: String): String =
    s""""_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":$capabilities}"""

  private def confirmSchema: Json =
    """{"type":"object","properties":{"confirm":{"type":"boolean"}},"required":["confirm"]}"""
      .fromJson[Json]
      .getOrElse(Json.Obj())

  private def question(text: String): Json =
    Json.Obj(
      "method" -> Json.Str("elicitation/create"),
      "params" -> Json.Obj("message" -> Json.Str(text), "requestedSchema" -> confirmSchema)
    )

  private def rawInputResponses(context: Option[McpContext]): UIO[Map[String, Json]] =
    context.flatMap(_.session) match
      case Some(session) =>
        session.currentRequestContext.map(_.map(_.inputResponses).getOrElse(Map.empty))
      case None => ZIO.succeed(Map.empty)

  private final class Fixture(settings: TaskSettings = TaskSettings(enabled = true, pollIntervalMs = 10)):
    val server: McpServer[Any] = McpServer("TaskExtension", "0.9.0", McpServerSettings(tasks = settings))
    private val tools: List[McpTool[?, ?]] = List(
      McpTool[NoArgs, String](name = "greet")(_ => "hello"),
      McpTool[SleepArgs, String](name = "slow")((args: SleepArgs) =>
        // `ms` of a second or more stands for "still running whenever the test looks"; less for
        // "finishes by itself". Neither is measured on a clock.
        (if args.ms >= 1000 then ZIO.never else ZIO.yieldNow).as("slow done"): zio.Task[String]
      ).withTaskSupport(TaskSupport.Optional),
      // One question, asked the way any handler asks it.
      McpTool[NoArgs, String](name = "confirm")
        .contextual { (_, context) =>
          context match
            case Some(ctx) =>
              ctx
                .elicit(ElicitRequestParams("Delete it?", confirmSchema))
                .map(answer => s"action=${answer.action} content=${answer.content.map(_.toJson)}")
            case None => ZIO.fail(new IllegalStateException("missing context"))
        }
        .withTaskSupport(TaskSupport.Optional),
      // Two questions outstanding at once.
      McpTool[NoArgs, String](name = "two")
        .contextual { (_, context) =>
          rawInputResponses(context).flatMap { responses =>
            if responses.contains("first") && responses.contains("second") then
              ZIO.succeed(s"both: ${responses("first").toJson} ${responses("second").toJson}")
            else
              ZIO.fail(
                McpError.inputRequired(
                  List("first" -> question("First?"), "second" -> question("Second?")),
                  None
                )
              )
          }
        }
        .withTaskSupport(TaskSupport.Optional),
      // Two questions one after the other, each attempt handing `requestState` to the next.
      McpTool[NoArgs, String](name = "rounds")
        .contextual { (_, context) =>
          context match
            case Some(ctx) =>
              for
                name <- ctx.sendRequest(
                  "elicitation/create",
                  Some(Json.Obj("message" -> Json.Str("Name?"), "requestedSchema" -> confirmSchema)),
                  requestState = Some("after-name")
                )
                color <- ctx.sendRequest(
                  "elicitation/create",
                  Some(Json.Obj("message" -> Json.Str("Color?"), "requestedSchema" -> confirmSchema)),
                  requestState = Some("after-color")
                )
              yield s"name=${name.toJson} color=${color.toJson} state=${ctx.getRequestState}"
            case None => ZIO.fail(new IllegalStateException("missing context"))
        }
        .withTaskSupport(TaskSupport.Optional),
      // Asks the same key again after it was answered: a task cannot make progress on that.
      McpTool[NoArgs, String](name = "again")
        .contextual { (_, _) =>
          ZIO.fail(McpError.inputRequired("same", question("Again?"))): zio.Task[String]
        }
        .withTaskSupport(TaskSupport.Optional)
    )
    runUnsafe(ZIO.foreachDiscard(tools)(server.tool(_)))
    private val built = runUnsafe(server.buildRouterWithTasks)
    val router: McpRouter[Any] = built._1
    val manager: TaskManager[Any] = built._2.getOrElse(fail("tasks are enabled"))
    val session: Session = runUnsafe(Session.make("task-extension"))
    private val ids = new java.util.concurrent.atomic.AtomicInteger(0)

    /** Send one 2026-07-28 request and return the reply's `result` or `error` member. */
    def send(method: String, body: String, capabilities: String = Declaring): Either[Json, Json] =
      val separator = if body.isEmpty then "" else ","
      val frame =
        s"""{"jsonrpc":"2.0","id":${ids.incrementAndGet()},"method":"$method","params":{$body$separator${meta(capabilities)}}}"""
      val reply = runUnsafe(MessageLoop.handleFrame(router, session, frame))
        .getOrElse(fail(s"no reply to $method"))
        .fromJson[Json]
        .getOrElse(fail(s"reply to $method is not JSON"))
      (member(reply, "result"), member(reply, "error")) match
        case (Some(success), _) => Right(success)
        case (_, Some(failure)) => Left(failure)
        case _ => fail(s"reply to $method has neither result nor error: ${reply.toJson}")

    def result(method: String, body: String, capabilities: String = Declaring): Json =
      send(method, body, capabilities).fold(e => fail(s"$method failed: ${e.toJson}"), identity)

    def errorCode(method: String, body: String, capabilities: String = Declaring): Int =
      send(method, body, capabilities) match
        case Left(error) => intField(error, "code")
        case Right(ok) => fail(s"$method succeeded: ${ok.toJson}")

    def startTask(tool: String, arguments: String = "{}"): String =
      val created = result("tools/call", s""""name":"$tool","arguments":$arguments""")
      if field(created, "resultType") != "task" then fail(s"$tool did not create a task: ${created.toJson}")
      field(created, "taskId")

    def get(taskId: String): Json = result("tasks/get", s""""taskId":"$taskId"""")

    /** Poll `tasks/get` until the task shows `status`. */
    def awaitStatus(taskId: String, status: String): Json =
      Await.until(s"task $taskId becomes $status")(get(taskId))(field(_, "status") == status)

    def update(taskId: String, responses: String): Either[Json, Json] =
      send("tasks/update", s""""taskId":"$taskId","inputResponses":$responses""")

    def close(): Unit = runUnsafe(manager.shutdown)

  private def member(json: Json, name: String): Option[Json] =
    json match
      case Json.Obj(fields) => fields.collectFirst { case (key, value) if key == name => value }
      case _ => None

  private def field(json: Json, name: String): String =
    member(json, name) match
      case Some(Json.Str(value)) => value
      case _ => fail(s"no string `$name` in ${json.toJson}")

  private def intField(json: Json, name: String): Int =
    member(json, name) match
      case Some(Json.Num(value)) => value.intValue
      case _ => fail(s"no number `$name` in ${json.toJson}")

  private def members(json: Json, name: String): List[(String, Json)] =
    member(json, name) match
      case Some(Json.Obj(fields)) => fields.toList
      case _ => Nil

  private def has(json: Json, name: String): Boolean = member(json, name).isDefined

  private val Accept = """{"action":"accept","content":{"confirm":true}}"""

  private def withFixture[A](settings: TaskSettings = TaskSettings(enabled = true, pollIntervalMs = 10))(
      body: Fixture => A
  ): A =
    val fixture = new Fixture(settings)
    try body(fixture)
    finally fixture.close()

  // ---- capability gate (SEP-2663, Protocol Errors) ----

  test("tasks/get, tasks/update and tasks/cancel answer -32021 to a request that does not declare the extension") {
    withFixture() { f =>
      val taskId = f.startTask("slow", """{"ms":5000}""")
      val requests = List(
        "tasks/get" -> s""""taskId":"$taskId"""",
        "tasks/cancel" -> s""""taskId":"$taskId"""",
        "tasks/update" -> s""""taskId":"$taskId","inputResponses":{}""",
        // The answer does not depend on whether the id names a task.
        "tasks/get" -> """"taskId":"no-such-task"""",
        "tasks/update" -> """"taskId":"no-such-task","inputResponses":{}""",
        "tasks/cancel" -> """"taskId":"no-such-task""""
      )
      for (method, body) <- requests do
        withClue(s"$method $body: ") {
          val error = f.send(method, body, NotDeclaring).left.getOrElse(fail("expected an error"))
          intField(error, "code") shouldBe ErrorCodes.MissingRequiredClientCapability
          error.toJson should include(
            s""""requiredCapabilities":{"extensions":{"${Tasks.ExtensionId}":{}}}"""
          )
        }
      // The gated cancel did nothing: the task is still running for a declaring client.
      field(f.get(taskId), "status") shouldBe "working"
      f.result("tasks/cancel", s""""taskId":"$taskId"""")
    }
  }

  test("an unknown task id is -32602 on tasks/get, tasks/update and tasks/cancel for a declaring client") {
    withFixture() { f =>
      f.errorCode("tasks/get", """"taskId":"no-such-task"""") shouldBe ErrorCodes.InvalidParams
      f.errorCode("tasks/cancel", """"taskId":"no-such-task"""") shouldBe ErrorCodes.InvalidParams
      f.errorCode(
        "tasks/update",
        """"taskId":"no-such-task","inputResponses":{}"""
      ) shouldBe ErrorCodes.InvalidParams
    }
  }

  // ---- the removed `params.task` (SEP-2663, Backward Compatibility) ----

  test("a 2025-11-25 `params.task` on a 2026-07-28 tools/call is ignored, never an opt-in or an error") {
    withFixture() { f =>
      val legacyHint = """"task":{"ttl":60000,"pollInterval":100}"""
      // A tool that never runs as a task stays synchronous.
      val sync = f.result("tools/call", s""""name":"greet","arguments":{},$legacyHint""")
      field(sync, "resultType") shouldBe "complete"
      has(sync, "taskId") shouldBe false
      sync.toJson should include("hello")
      // A client that did not declare the extension gets no task because of the hint.
      val undeclared =
        f.result("tools/call", s""""name":"slow","arguments":{"ms":1},$legacyHint""", NotDeclaring)
      field(undeclared, "resultType") shouldBe "complete"
      has(undeclared, "taskId") shouldBe false
      // A declaring client gets the task the tool's policy gives it, with the server's TTL.
      val created = f.result("tools/call", s""""name":"slow","arguments":{"ms":1},$legacyHint""")
      field(created, "resultType") shouldBe "task"
      intField(created, "ttlMs") shouldBe TaskSettings().defaultTtlMs.toInt
    }
  }

  // ---- acknowledgement-only cancel and update ----

  test("tasks/cancel is acknowledged with an empty result, also for a task that is already terminal") {
    withFixture() { f =>
      val running = f.startTask("slow", """{"ms":60000}""")
      val ack = f.result("tasks/cancel", s""""taskId":"$running"""")
      field(ack, "resultType") shouldBe "complete"
      for name <- List("taskId", "status", "result", "error", "inputRequests") do
        withClue(name)(has(ack, name) shouldBe false)
      field(f.awaitStatus(running, "cancelled"), "status") shouldBe "cancelled"
      // Cancelling again, and cancelling a completed task, is acknowledged and changes nothing.
      field(f.result("tasks/cancel", s""""taskId":"$running""""), "resultType") shouldBe "complete"
      val finished = f.startTask("slow", """{"ms":1}""")
      f.awaitStatus(finished, "completed")
      val again = f.result("tasks/cancel", s""""taskId":"$finished"""")
      field(again, "resultType") shouldBe "complete"
      has(again, "status") shouldBe false
      field(f.get(finished), "status") shouldBe "completed"
    }
  }

  test("tasks/update on a task that is not waiting for input is acknowledged and ignored") {
    withFixture() { f =>
      val running = f.startTask("slow", """{"ms":60000}""")
      val ack = f.update(running, s"""{"anything":$Accept}""").getOrElse(fail("expected an ack"))
      field(ack, "resultType") shouldBe "complete"
      has(ack, "status") shouldBe false
      field(f.get(running), "status") shouldBe "working"
      f.result("tasks/cancel", s""""taskId":"$running"""")
      val finished = f.startTask("slow", """{"ms":1}""")
      f.awaitStatus(finished, "completed")
      f.update(finished, s"""{"anything":$Accept}""").isRight shouldBe true
      field(f.get(finished), "status") shouldBe "completed"
    }
  }

  // ---- input_required ----

  test("a task that asks for input waits in input_required and resumes on tasks/update") {
    withFixture() { f =>
      val taskId = f.startTask("confirm")
      val waiting = f.awaitStatus(taskId, "input_required")
      field(waiting, "resultType") shouldBe "complete"
      has(waiting, "result") shouldBe false
      has(waiting, "requestState") shouldBe false
      val requests = members(waiting, "inputRequests")
      requests.map(_._2).map(field(_, "method")) shouldBe List("elicitation/create")
      requests.head._2.toJson should include("Delete it?")
      val key = requests.head._1

      // A key that is not outstanding is ignored: the task keeps waiting on the same request.
      val ignored = f.update(taskId, s"""{"never-issued":$Accept}""").getOrElse(fail("expected an ack"))
      field(ignored, "resultType") shouldBe "complete"
      members(f.get(taskId), "inputRequests").map(_._1) shouldBe List(key)

      val ack = f.update(taskId, s"""{"$key":$Accept}""").getOrElse(fail("expected an ack"))
      field(ack, "resultType") shouldBe "complete"
      for name <- List("taskId", "status", "result", "error", "inputRequests") do
        withClue(name)(has(ack, name) shouldBe false)

      val done = f.awaitStatus(taskId, "completed")
      has(done, "inputRequests") shouldBe false
      has(done, "requestState") shouldBe false
      done.toJson should include("action=accept")
      done.toJson should include("confirm")
      // The answered key is not outstanding any more: a second response is ignored.
      f.update(taskId, s"""{"$key":$Accept}""").isRight shouldBe true
      val stats = runUnsafe(f.manager.stats)
      stats.running shouldBe 0
      stats.heldFibers shouldBe 0
    }
  }

  test("a partial tasks/update leaves the task in input_required with only the unanswered request") {
    withFixture() { f =>
      val taskId = f.startTask("two")
      val waiting = f.awaitStatus(taskId, "input_required")
      members(waiting, "inputRequests").map(_._1) shouldBe List("first", "second")

      f.update(taskId, s"""{"first":$Accept}""").isRight shouldBe true
      val partial = f.get(taskId)
      field(partial, "status") shouldBe "input_required"
      members(partial, "inputRequests").map(_._1) shouldBe List("second")
      // The answered key is ignored from here on; it does not replace the recorded response.
      f.update(taskId, """{"first":{"action":"decline"}}""").isRight shouldBe true
      members(f.get(taskId), "inputRequests").map(_._1) shouldBe List("second")

      f.update(taskId, """{"second":{"action":"accept","content":{"confirm":false}}}""").isRight shouldBe true
      val done = f.awaitStatus(taskId, "completed")
      done.toJson should include("both:")
      done.toJson should include("""\"confirm\":true""")
      done.toJson should include("""\"confirm\":false""")
      done.toJson should not include "decline"
    }
  }

  test("a task may wait for input more than once; requestState stays between attempts, off the wire") {
    withFixture() { f =>
      val taskId = f.startTask("rounds")
      val first = f.awaitStatus(taskId, "input_required")
      val firstKey = members(first, "inputRequests").map(_._1) match
        case List(key) => key
        case other => fail(s"expected one request, got $other")
      first.toJson should include("Name?")
      first.toJson should not include "after-name"
      f.update(taskId, s"""{"$firstKey":{"action":"accept","content":{"confirm":true}}}""").isRight shouldBe true

      // The second question has its own key; the first is never shown again.
      val second = Await.until("the task asks its second question")(f.get(taskId)) { task =>
        val keys = members(task, "inputRequests").map(_._1)
        field(task, "status") == "input_required" && keys.nonEmpty && keys != List(firstKey)
      }
      second.toJson should include("Color?")
      has(second, "requestState") shouldBe false
      val secondKey = members(second, "inputRequests").head._1
      secondKey should not be firstKey
      f.update(taskId, s"""{"$secondKey":{"action":"accept","content":{"confirm":false}}}""").isRight shouldBe true

      val done = f.awaitStatus(taskId, "completed")
      done.toJson should include("state=Some(after-color)")
      has(done, "requestState") shouldBe false
    }
  }

  test("a response that is not an object is refused with -32602 and the task keeps waiting") {
    withFixture() { f =>
      val taskId = f.startTask("confirm")
      val key = members(f.awaitStatus(taskId, "input_required"), "inputRequests").head._1
      val refused = f.update(taskId, s"""{"$key":12345}""").left.getOrElse(fail("expected an error"))
      intField(refused, "code") shouldBe ErrorCodes.InvalidParams
      members(f.get(taskId), "inputRequests").map(_._1) shouldBe List(key)
      f.update(taskId, s"""{"$key":$Accept}""").isRight shouldBe true
      f.awaitStatus(taskId, "completed")
    }
  }

  test("tasks/cancel ends a task that is waiting for input") {
    withFixture() { f =>
      val taskId = f.startTask("confirm")
      val key = members(f.awaitStatus(taskId, "input_required"), "inputRequests").head._1
      field(f.result("tasks/cancel", s""""taskId":"$taskId""""), "resultType") shouldBe "complete"
      val cancelled = f.awaitStatus(taskId, "cancelled")
      has(cancelled, "inputRequests") shouldBe false
      // A late response is acknowledged and ignored.
      f.update(taskId, s"""{"$key":$Accept}""").isRight shouldBe true
      field(f.get(taskId), "status") shouldBe "cancelled"
      val stats = runUnsafe(f.manager.stats)
      stats.running shouldBe 0
      stats.heldFibers shouldBe 0
    }
  }

  test("a tool that asks again for a key it was given fails the task (a key is asked once)") {
    withFixture() { f =>
      val taskId = f.startTask("again")
      f.awaitStatus(taskId, "input_required")
      f.update(taskId, s"""{"same":$Accept}""").isRight shouldBe true
      val failed = f.awaitStatus(taskId, "failed")
      has(failed, "result") shouldBe false
      val error = member(failed, "error").getOrElse(fail("no error"))
      intField(error, "code") shouldBe ErrorCodes.InternalError
      error.toJson should include("asked again")
    }
  }

  test("responses are charged to the task: one that does not fit maxRequestBytes is refused with -32602") {
    val settings = TaskSettings(enabled = true, pollIntervalMs = 10, maxRequestBytes = 16L * 1024)
    withFixture(settings) { f =>
      val taskId = f.startTask("confirm")
      val key = members(f.awaitStatus(taskId, "input_required"), "inputRequests").head._1
      val waitingBytes = runUnsafe(f.manager.stats).perPool.values.map(_.bytes).sum
      waitingBytes should be > 0L
      val large = "x" * 20000
      val refused = f
        .update(taskId, s"""{"$key":{"action":"accept","content":{"confirm":true,"note":"$large"}}}""")
        .left
        .getOrElse(fail("expected an error"))
      intField(refused, "code") shouldBe ErrorCodes.InvalidParams
      refused.toJson should include("maxRequestBytes")
      // Nothing was stored: same charge, same outstanding request.
      runUnsafe(f.manager.stats).perPool.values.map(_.bytes).sum shouldBe waitingBytes
      members(f.get(taskId), "inputRequests").map(_._1) shouldBe List(key)
      f.update(taskId, s"""{"$key":$Accept}""").isRight shouldBe true
      f.awaitStatus(taskId, "completed")
    }
  }
