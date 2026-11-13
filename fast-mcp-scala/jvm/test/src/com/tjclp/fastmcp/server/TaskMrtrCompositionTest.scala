package com.tjclp.fastmcp.server

import java.util.concurrent.atomic.AtomicInteger

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.core.wire.ElicitRequestParams
import com.tjclp.fastmcp.server.manager.{TaskManager, ToolRegistrationOptions}
import com.tjclp.fastmcp.server.router.{McpRouter, Session}
import com.tjclp.fastmcp.server.transport.MessageLoop

/** SEP-2663, "Composition with Multi Round-Trip Requests": a tool registered with
  * `McpTool.withDeferredTask` gathers its input over MRTR on the request itself and becomes a task
  * only where its handler calls `McpContext.continueAsTask`.
  */
class TaskMrtrCompositionTest extends AnyFunSuite with Matchers:

  private case class NoArgs()
  private case class SleepArgs(ms: Int)

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit unsafe => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private val Declaring =
    s"""{"elicitation":{},"extensions":{"${Tasks.ExtensionId}":{}}}"""
  private val NotDeclaring = """{"elicitation":{}}"""

  private def meta(capabilities: String): String =
    s""""_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":$capabilities}"""

  private val nameSchema: Json =
    """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}"""
      .fromJson[Json]
      .getOrElse(Json.Obj())

  private def accept(name: String): String =
    s"""{"action":"accept","content":{"name":"$name"}}"""

  private def withContext[A](context: Option[McpContext])(
      body: McpContext => ZIO[Any, Throwable, A]
  ): ZIO[Any, Throwable, A] =
    context match
      case Some(ctx) => body(ctx)
      case None => ZIO.fail(new IllegalStateException("missing context"))

  private def named(answer: com.tjclp.fastmcp.core.wire.ElicitResult): String =
    answer.content.flatMap(_.get("name")).map(_.toJson).getOrElse("none")

  private final class Fixture(
      settings: TaskSettings = TaskSettings(enabled = true, pollIntervalMs = 10)
  ):
    /** How often the `compose` handler started, and how often it got past the hand-off. */
    val attempts = new AtomicInteger(0)
    val worked = new AtomicInteger(0)

    val server: McpServer[Any] =
      McpServer("TaskComposition", "0.9.0", McpServerSettings(tasks = settings))

    private def compose(name: String): McpTool[NoArgs, String] =
      McpTool[NoArgs, String](name = name)
        .contextual { (_, context) =>
          withContext(context) { ctx =>
            for
              _ <- ZIO.succeed(attempts.incrementAndGet())
              answer <- ctx.elicit(ElicitRequestParams("Name?", nameSchema))
              asTask <- ctx.continueAsTask
              _ <- ZIO.succeed(worked.incrementAndGet())
            yield s"name=${named(answer)} asTask=$asTask"
          }
        }
        .withDeferredTask

    private def reports(name: String): McpTool[NoArgs, String] =
      McpTool[NoArgs, String](name = name)
        .contextual((_, context) => withContext(context)(_.continueAsTask.map(b => s"asTask=$b")))

    private val tools: List[McpTool[?, ?]] = List(
      compose("compose").withTaskSupport(TaskSupport.Required),
      compose("compose-optional").withTaskSupport(TaskSupport.Optional),
      // Deferred, but task support was never given: the flag alone makes no task.
      compose("compose-forbidden"),
      // One question on the request, one more from inside the task.
      McpTool[NoArgs, String](name = "ask-twice")
        .contextual { (_, context) =>
          withContext(context) { ctx =>
            for
              first <- ctx.elicit(ElicitRequestParams("Name?", nameSchema))
              _ <- ctx.continueAsTask
              second <- ctx.elicit(ElicitRequestParams("Other name?", nameSchema))
            yield s"first=${named(first)} second=${named(second)}"
          }
        }
        .withTaskSupport(TaskSupport.Optional)
        .withDeferredTask,
      // The MRTR phase hands `requestState` from one round to the next.
      McpTool[NoArgs, String](name = "state")
        .contextual { (_, context) =>
          withContext(context) { ctx =>
            for
              answer <- ctx.sendRequest(
                "elicitation/create",
                Some(Json.Obj("message" -> Json.Str("Name?"), "requestedSchema" -> nameSchema)),
                requestState = Some("mrtr-state")
              )
              _ <- ctx.continueAsTask
            yield s"answer=${answer.toJson} state=${ctx.getRequestState}"
          }
        }
        .withTaskSupport(TaskSupport.Required)
        .withDeferredTask,
      // Deferred, and the handler never hands off.
      McpTool[NoArgs, String](name = "no-handoff")(_ => "answered on the request")
        .withTaskSupport(TaskSupport.Required)
        .withDeferredTask,
      reports("reports-deferred").withTaskSupport(TaskSupport.Optional).withDeferredTask,
      reports("reports-upfront").withTaskSupport(TaskSupport.Optional),
      reports("reports-plain"),
      McpTool[SleepArgs, String](name = "slow")((args: SleepArgs) =>
        ZIO.sleep(args.ms.millis).as("slow done"): zio.Task[String]
      ).withTaskSupport(TaskSupport.Optional)
    )
    runUnsafe(ZIO.foreachDiscard(tools)(server.tool(_)))
    private val built = runUnsafe(server.buildRouterWithTasks)
    val router: McpRouter[Any] = built._1
    val manager: TaskManager[Any] = built._2.getOrElse(fail("tasks are enabled"))
    val session: Session = runUnsafe(Session.make("task-composition"))
    private val ids = new AtomicInteger(0)

    def frame(on: Session, value: String): Json =
      runUnsafe(MessageLoop.handleFrame(router, on, value))
        .getOrElse(fail(s"no reply to $value"))
        .fromJson[Json]
        .getOrElse(fail("reply is not JSON"))

    /** Send one 2026-07-28 request and return the reply's `result` or `error` member. */
    def send(method: String, body: String, capabilities: String = Declaring): Either[Json, Json] =
      val reply = frame(
        session,
        s"""{"jsonrpc":"2.0","id":${ids.incrementAndGet()},"method":"$method","params":{$body,${meta(capabilities)}}}"""
      )
      (member(reply, "result"), member(reply, "error")) match
        case (Some(success), _) => Right(success)
        case (_, Some(failure)) => Left(failure)
        case _ => fail(s"reply to $method has neither result nor error: ${reply.toJson}")

    def result(method: String, body: String, capabilities: String = Declaring): Json =
      send(method, body, capabilities).fold(e => fail(s"$method failed: ${e.toJson}"), identity)

    def call(tool: String, extra: String = "", capabilities: String = Declaring): Json =
      result("tools/call", s""""name":"$tool","arguments":{}$extra""", capabilities)

    def get(taskId: String): Json = result("tasks/get", s""""taskId":"$taskId"""")

    def awaitStatus(taskId: String, status: String): Json =
      val deadline = java.lang.System.nanoTime() + 5_000_000_000L
      @scala.annotation.tailrec
      def loop(): Json =
        val task = get(taskId)
        if field(task, "status") == status then task
        else if java.lang.System.nanoTime() > deadline then
          fail(s"task $taskId never became $status; last: ${task.toJson}")
        else
          Thread.sleep(5)
          loop()
      loop()

    def stored: Int = runUnsafe(manager.stats).total

    def close(): Unit = runUnsafe(manager.shutdown)

  private def member(json: Json, name: String): Option[Json] =
    json match
      case Json.Obj(fields) => fields.collectFirst { case (key, value) if key == name => value }
      case _ => None

  private def field(json: Json, name: String): String =
    member(json, name) match
      case Some(Json.Str(value)) => value
      case _ => fail(s"no string `$name` in ${json.toJson}")

  private def keys(json: Json): List[String] =
    member(json, "inputRequests") match
      case Some(Json.Obj(fields)) => fields.toList.map(_._1)
      case _ => Nil

  private def has(json: Json, name: String): Boolean = member(json, name).isDefined

  private def withFixture[A](
      settings: TaskSettings = TaskSettings(enabled = true, pollIntervalMs = 10)
  )(body: Fixture => A): A =
    val fixture = new Fixture(settings)
    try body(fixture)
    finally fixture.close()

  /** Round 1 of a deferred tool: an `input_required` result and no task. Returns the key asked. */
  private def firstRound(f: Fixture, tool: String): (String, Json) =
    val asked = f.call(tool)
    field(asked, "resultType") shouldBe "input_required"
    has(asked, "taskId") shouldBe false
    has(asked, "status") shouldBe false
    f.stored shouldBe 0
    val asking = keys(asked)
    asking should have size 1
    (asking.head, asked)

  test("input is gathered on the request and the task is created at the hand-off") {
    withFixture() { f =>
      val (key, asked) = firstRound(f, "compose")
      asked.toJson should include("Name?")
      f.attempts.get() shouldBe 1
      f.worked.get() shouldBe 0

      val created = f.call("compose", s""","inputResponses":{"$key":${accept("Alice")}}""")
      field(created, "resultType") shouldBe "task"
      for name <- List("requestState", "inputRequests", "result", "content") do
        withClue(name)(has(created, name) shouldBe false)
      // SEP-2663, Task Creation: the task resolves on `tasks/get` once its handle is returned.
      val taskId = field(created, "taskId")
      f.stored shouldBe 1

      val done = f.awaitStatus(taskId, "completed")
      done.toJson should include("""name=\"Alice\" asTask=true""")
      has(done, "inputRequests") shouldBe false
      // Round 1, the round that reached the hand-off, and the replay inside the task.
      f.attempts.get() shouldBe 3
      // What follows the hand-off ran once, in the task.
      f.worked.get() shouldBe 1
      val stats = runUnsafe(f.manager.stats)
      stats.running shouldBe 0
      stats.heldFibers shouldBe 0
    }
  }

  test("the requestState of the MRTR phase reaches the task and stays off the task's wire shape") {
    withFixture() { f =>
      val (key, asked) = firstRound(f, "state")
      field(asked, "requestState") shouldBe "mrtr-state"
      val created = f.call(
        "state",
        s""","inputResponses":{"$key":${accept("Bob")}},"requestState":"mrtr-state""""
      )
      field(created, "resultType") shouldBe "task"
      has(created, "requestState") shouldBe false
      val done = f.awaitStatus(field(created, "taskId"), "completed")
      has(done, "requestState") shouldBe false
      done.toJson should include("Bob")
      done.toJson should include("state=Some(mrtr-state)")
    }
  }

  test("a question after the hand-off is asked from the task, with its own key") {
    withFixture() { f =>
      val (key, _) = firstRound(f, "ask-twice")
      val created = f.call("ask-twice", s""","inputResponses":{"$key":${accept("Alice")}}""")
      val taskId = field(created, "taskId")
      val waiting = f.awaitStatus(taskId, "input_required")
      waiting.toJson should include("Other name?")
      waiting.toJson should not include "Name?\""
      val taskKeys = keys(waiting)
      taskKeys should have size 1
      taskKeys.head should not be key
      // The key of the MRTR phase is not outstanding in the task: answering it changes nothing.
      f.result("tasks/update", s""""taskId":"$taskId","inputResponses":{"$key":${accept("Eve")}}""")
      keys(f.get(taskId)) shouldBe taskKeys
      f.result(
        "tasks/update",
        s""""taskId":"$taskId","inputResponses":{"${taskKeys.head}":${accept("Carol")}}"""
      )
      val done = f.awaitStatus(taskId, "completed")
      done.toJson should include("""first=\"Alice\" second=\"Carol\"""")
    }
  }

  test("a deferred tool that never hands off answers the request with an ordinary result") {
    withFixture() { f =>
      val reply = f.call("no-handoff")
      field(reply, "resultType") shouldBe "complete"
      has(reply, "taskId") shouldBe false
      reply.toJson should include("answered on the request")
      f.stored shouldBe 0
    }
  }

  test("without the tasks extension an optional tool stays a request and a required one is refused") {
    withFixture() { f =>
      val asked = f.call("compose-optional", capabilities = NotDeclaring)
      field(asked, "resultType") shouldBe "input_required"
      val key = keys(asked).head
      val reply = f.call(
        "compose-optional",
        s""","inputResponses":{"$key":${accept("Alice")}}""",
        NotDeclaring
      )
      field(reply, "resultType") shouldBe "complete"
      has(reply, "taskId") shouldBe false
      reply.toJson should include("""name=\"Alice\" asTask=false""")
      f.stored shouldBe 0

      // Required: the capability gate answers before the handler runs, deferred or not.
      val before = f.attempts.get()
      f.send("tools/call", """"name":"compose","arguments":{}""", NotDeclaring) match
        case Left(error) => error.toJson should include("-32021")
        case Right(ok) => fail(s"expected -32021, got ${ok.toJson}")
      f.attempts.get() shouldBe before
    }
  }

  test("withDeferredTask does not make a task of a tool whose task support is forbidden") {
    withFixture() { f =>
      val asked = f.call("compose-forbidden")
      val key = keys(asked).head
      val reply = f.call("compose-forbidden", s""","inputResponses":{"$key":${accept("Alice")}}""")
      field(reply, "resultType") shouldBe "complete"
      reply.toJson should include("asTask=false")
      f.stored shouldBe 0
    }
  }

  test("continueAsTask reports whether the handler runs as a task") {
    withFixture() { f =>
      // Deferred with nothing to ask: the first round is the hand-off.
      val deferred = f.call("reports-deferred")
      field(deferred, "resultType") shouldBe "task"
      f.awaitStatus(field(deferred, "taskId"), "completed").toJson should include("asTask=true")
      f.call("reports-deferred", capabilities = NotDeclaring).toJson should include("asTask=false")

      // Not deferred: the task exists before the handler starts.
      val upfront = f.call("reports-upfront")
      field(upfront, "resultType") shouldBe "task"
      f.awaitStatus(field(upfront, "taskId"), "completed").toJson should include("asTask=true")
      f.call("reports-upfront", capabilities = NotDeclaring).toJson should include("asTask=false")

      f.call("reports-plain").toJson should include("asTask=false")
    }
  }

  test("a hand-off the task store has no room for fails the request with the store's error") {
    withFixture(TaskSettings(enabled = true, pollIntervalMs = 10, maxConcurrentPerSession = 1)) { f =>
      val slow = f.result("tools/call", """"name":"slow","arguments":{"ms":60000}""")
      field(slow, "resultType") shouldBe "task"
      // The MRTR phase needs no room in the store.
      val asked = f.call("compose")
      field(asked, "resultType") shouldBe "input_required"
      val key = keys(asked).head
      val refused = f.send(
        "tools/call",
        s""""name":"compose","arguments":{},"inputResponses":{"$key":${accept("Alice")}}"""
      )
      refused match
        case Left(error) => error.toJson should include("-32602")
        case Right(ok) => fail(s"expected a refusal, got ${ok.toJson}")
      f.worked.get() shouldBe 0
      f.stored shouldBe 1
      f.result("tasks/cancel", s""""taskId":"${field(slow, "taskId")}"""")
    }
  }

  test("a 2025-11-25 session creates the task of a deferred tool when the call asks for one") {
    withFixture() { f =>
      val legacy = runUnsafe(Session.make("legacy-composition"))
      f.frame(
        legacy,
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""
      )
      val sync = f.frame(
        legacy,
        """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"reports-deferred","arguments":{}}}"""
      )
      sync.toJson should include("asTask=false")
      val created = f.frame(
        legacy,
        """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"reports-deferred","arguments":{},"task":{"ttl":60000}}}"""
      )
      val taskId = member(created, "result")
        .flatMap(member(_, "task"))
        .map(field(_, "taskId"))
        .getOrElse(fail(s"no task: ${created.toJson}"))
      val result = f.frame(
        legacy,
        s"""{"jsonrpc":"2.0","id":4,"method":"tasks/result","params":{"taskId":"$taskId"}}"""
      )
      result.toJson should include("asTask=true")
    }
  }

  test("registering a tool again without withDeferredTask clears the deferral") {
    withFixture() { f =>
      f.server.toolManager.isTaskDeferred("compose") shouldBe true
      f.server.toolManager.isTaskDeferred("reports-upfront") shouldBe false
      f.server.toolManager.isTaskDeferred("no-such-tool") shouldBe false
      val plain = McpTool[NoArgs, String](name = "compose")(_ => "replaced")
        .withTaskSupport(TaskSupport.Optional)
      runUnsafe(f.server.tool(plain, ToolRegistrationOptions(allowOverrides = true)))
      f.server.toolManager.isTaskDeferred("compose") shouldBe false
      field(f.call("compose"), "resultType") shouldBe "task"
    }
  }

  test("an environment-aware contract defers its task the same way") {
    val server = McpServer.typed[String](
      "TaskCompositionEnv",
      "0.9.0",
      McpServerSettings(tasks = TaskSettings(enabled = true))
    )
    val tool = McpTool[NoArgs, String, String](name = "env")(_ => ZIO.service[String])
      .withTaskSupport(TaskSupport.Optional)
      .withDeferredTask
    runUnsafe(server.tool(tool))
    server.toolManager.isTaskDeferred("env") shouldBe true
  }
