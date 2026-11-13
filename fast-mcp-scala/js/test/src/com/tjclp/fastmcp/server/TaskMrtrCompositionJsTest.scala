package com.tjclp.fastmcp.server

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.core.{TaskSupport, Tasks}
import com.tjclp.fastmcp.core.wire.{ElicitRequestParams, ElicitResult}
import com.tjclp.fastmcp.server.router.Session
import com.tjclp.fastmcp.server.transport.MessageLoop

/** Scala.js twin of the JVM `TaskMrtrCompositionTest`: a tool registered with
  * `McpTool.withDeferredTask` asks its question on the request (an `input_required` result, no
  * task), becomes a task where its handler calls `McpContext.continueAsTask`, and asks what comes
  * after that from inside the task (SEP-2663, "Composition with Multi Round-Trip Requests"). The
  * router and the task store are shared code; this runs them on the single-threaded Scala.js
  * runtime.
  */
class TaskMrtrCompositionJsTest extends AsyncFlatSpec with Matchers:

  override implicit val executionContext: ExecutionContext = ExecutionContext.global

  private def run[A](effect: ZIO[Any, Throwable, A]): Future[A] =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.runToFuture(effect))

  private case class NoArgs()

  private val Declaring = s"""{"elicitation":{},"extensions":{"${Tasks.ExtensionId}":{}}}"""
  private val NotDeclaring = """{"elicitation":{}}"""

  private def meta(capabilities: String): String =
    s""""_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":$capabilities}"""

  private val nameSchema: Json =
    """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}"""
      .fromJson[Json]
      .getOrElse(Json.Obj())

  private def accept(name: String): String =
    s"""{"action":"accept","content":{"name":"$name"}}"""

  private def member(json: Json, name: String): Option[Json] =
    json match
      case Json.Obj(fields) => fields.collectFirst { case (key, value) if key == name => value }
      case _ => None

  private def text(json: Json, name: String): String =
    member(json, name) match
      case Some(Json.Str(value)) => value
      case _ => ""

  private def keys(json: Json): List[String] =
    member(json, "inputRequests") match
      case Some(Json.Obj(fields)) => fields.toList.map(_._1)
      case _ => Nil

  private def named(answer: ElicitResult): String =
    answer.content.flatMap(_.get("name")).map(_.toJson).getOrElse("none")

  private def withContext[A](context: Option[McpContext])(
      body: McpContext => ZIO[Any, Throwable, A]
  ): ZIO[Any, Throwable, A] =
    context match
      case Some(ctx) => body(ctx)
      case None => ZIO.fail(new IllegalStateException("missing context"))

  /** The whole flow as one effect. Returns what went wrong; empty when everything held. */
  private val scenario: ZIO[Any, Throwable, List[String]] =
    val server =
      McpServer(
        "TaskCompositionJs",
        "0.9.0",
        McpServerSettings(tasks = TaskSettings(enabled = true, pollIntervalMs = 10))
      )
    val worked = new java.util.concurrent.atomic.AtomicInteger(0)
    // One question on the request, the hand-off, then the work.
    val compose = McpTool[NoArgs, String](name = "compose")
      .contextual { (_, context) =>
        withContext(context) { ctx =>
          for
            answer <- ctx.elicit(ElicitRequestParams("Name?", nameSchema))
            asTask <- ctx.continueAsTask
            _ <- ZIO.succeed(worked.incrementAndGet())
          yield s"name=${named(answer)} asTask=$asTask"
        }
      }
      .withTaskSupport(TaskSupport.Optional)
      .withDeferredTask
    // One question on the request, one more from inside the task.
    val askTwice = McpTool[NoArgs, String](name = "ask-twice")
      .contextual { (_, context) =>
        withContext(context) { ctx =>
          for
            first <- ctx.elicit(ElicitRequestParams("Name?", nameSchema))
            _ <- ctx.continueAsTask
            second <- ctx.elicit(ElicitRequestParams("Other name?", nameSchema))
          yield s"first=${named(first)} second=${named(second)}"
        }
      }
      .withTaskSupport(TaskSupport.Required)
      .withDeferredTask
    val noHandoff = McpTool[NoArgs, String](name = "no-handoff")(_ => "answered on the request")
      .withTaskSupport(TaskSupport.Required)
      .withDeferredTask
    val ids = new java.util.concurrent.atomic.AtomicInteger(0)
    for
      _ <- server.tool(compose)
      _ <- server.tool(askTwice)
      _ <- server.tool(noHandoff)
      built <- server.buildRouterWithTasks
      (router, manager) = built
      session <- Session.make("task-composition")
      send = (method: String, body: String, capabilities: String) =>
        MessageLoop
          .handleFrame(
            router,
            session,
            s"""{"jsonrpc":"2.0","id":${ids.incrementAndGet()},"method":"$method","params":{$body,${meta(capabilities)}}}"""
          )
          .map(_.flatMap(_.fromJson[Json].toOption).getOrElse(Json.Obj()))
      result = (method: String, body: String) =>
        send(method, body, Declaring).map(reply => member(reply, "result").getOrElse(Json.Obj()))
      call = (tool: String, extra: String, capabilities: String) =>
        send("tools/call", s""""name":"$tool","arguments":{}$extra""", capabilities)
          .map(reply => member(reply, "result").getOrElse(Json.Obj()))
      stored = ZIO.foreach(manager)(_.stats).map(_.map(_.total).getOrElse(-1))
      until = (taskId: String, status: String) =>
        (ZIO.sleep(5.millis) *> result("tasks/get", s""""taskId":"$taskId""""))
          .repeatUntil(task => text(task, "status") == status)
          .timeoutFail(new RuntimeException(s"task never became $status"))(10.seconds)

      // Round 1 asks on the request: no task. Round 2 reaches the hand-off: a task.
      asked <- call("compose", "", Declaring)
      storedAfterAsk <- stored
      key = keys(asked).headOption.getOrElse("")
      created <- call("compose", s""","inputResponses":{"$key":${accept("Alice")}}""", Declaring)
      storedAfterCreate <- stored
      done <- until(text(created, "taskId"), "completed")

      // A question after the hand-off is the task's own.
      askedTwice <- call("ask-twice", "", Declaring)
      firstKey = keys(askedTwice).headOption.getOrElse("")
      createdTwice <-
        call("ask-twice", s""","inputResponses":{"$firstKey":${accept("Alice")}}""", Declaring)
      twiceId = text(createdTwice, "taskId")
      waiting <- until(twiceId, "input_required")
      taskKey = keys(waiting).headOption.getOrElse("")
      _ <- result(
        "tasks/update",
        s""""taskId":"$twiceId","inputResponses":{"$taskKey":${accept("Carol")}}"""
      )
      doneTwice <- until(twiceId, "completed")

      // No hand-off, or no tasks extension: the request is answered as a request.
      plain <- call("no-handoff", "", Declaring)
      askedSync <- call("compose", "", NotDeclaring)
      sync <- call(
        "compose",
        s""","inputResponses":{"${keys(askedSync).headOption.getOrElse("")}":${accept("Bob")}}""",
        NotDeclaring
      )
      stats <- ZIO.foreach(manager)(_.stats)
      _ <- ZIO.foreachDiscard(manager)(_.shutdown)
    yield List(
      Option.when(text(asked, "resultType") != "input_required")(s"round 1: ${asked.toJson}"),
      Option.when(member(asked, "taskId").isDefined)("round 1 carries a taskId"),
      Option.when(keys(asked).size != 1)(s"round 1 inputRequests: ${asked.toJson}"),
      Option.when(storedAfterAsk != 0)(s"a task exists after round 1: $storedAfterAsk"),
      Option.when(text(created, "resultType") != "task")(s"round 2: ${created.toJson}"),
      Option.when(member(created, "requestState").isDefined)("requestState on the task handle"),
      Option.when(member(created, "inputRequests").isDefined)("inputRequests on the task handle"),
      Option.when(storedAfterCreate != 1)(s"tasks after round 2: $storedAfterCreate"),
      Option.when(!done.toJson.contains("""name=\"Alice\" asTask=true"""))(s"result: ${done.toJson}"),
      Option.when(text(askedTwice, "resultType") != "input_required")(
        s"ask-twice round 1: ${askedTwice.toJson}"
      ),
      Option.when(text(createdTwice, "resultType") != "task")(
        s"ask-twice round 2: ${createdTwice.toJson}"
      ),
      Option.when(!waiting.toJson.contains("Other name?"))(s"task question: ${waiting.toJson}"),
      Option.when(taskKey.isEmpty || taskKey == firstKey)(s"task key: $taskKey vs $firstKey"),
      Option.when(!doneTwice.toJson.contains("""first=\"Alice\" second=\"Carol\""""))(
        s"ask-twice result: ${doneTwice.toJson}"
      ),
      Option.when(text(plain, "resultType") != "complete" || member(plain, "taskId").isDefined)(
        s"no hand-off: ${plain.toJson}"
      ),
      Option.when(text(askedSync, "resultType") != "input_required")(
        s"undeclared round 1: ${askedSync.toJson}"
      ),
      Option.when(!sync.toJson.contains("""name=\"Bob\" asTask=false"""))(
        s"undeclared round 2: ${sync.toJson}"
      ),
      // `compose` got past the hand-off once as a task and once as a plain request.
      Option.when(worked.get() != 2)(s"work ran ${worked.get()} times"),
      Option.when(!stats.exists(s => s.running == 0 && s.heldFibers == 0 && s.total == 2))(
        s"store: $stats"
      )
    ).flatten

  "a tool with a deferred task" should "gather input on the request and hand off to a task" in {
    run(scenario).map(problems => withClue(problems.mkString(" | "))(problems shouldBe Nil))
  }
