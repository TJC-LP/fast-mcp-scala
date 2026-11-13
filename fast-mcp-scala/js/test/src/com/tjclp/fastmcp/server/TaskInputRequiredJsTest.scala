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
import com.tjclp.fastmcp.core.wire.ElicitRequestParams
import com.tjclp.fastmcp.jsonrpc.McpError
import com.tjclp.fastmcp.server.router.Session
import com.tjclp.fastmcp.server.transport.MessageLoop

/** Scala.js twin of the JVM `TaskExtensionDispatchTest`: a 2026-07-28 task that asks for input
  * waits in `input_required`, takes its responses from `tasks/update` (a subset at a time, unknown
  * keys ignored) and completes; plus the extension's dispatch rules around it. The router and the
  * task store are shared code; this runs them on the single-threaded Scala.js runtime.
  */
class TaskInputRequiredJsTest extends AsyncFlatSpec with Matchers:

  override implicit val executionContext: ExecutionContext = ExecutionContext.global

  private def run[A](effect: ZIO[Any, Throwable, A]): Future[A] =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.runToFuture(effect))

  private case class NoArgs()

  private val Declaring = s"""{"elicitation":{},"extensions":{"${Tasks.ExtensionId}":{}}}"""
  private val NotDeclaring = """{"elicitation":{}}"""

  private def meta(capabilities: String): String =
    s""""_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":$capabilities}"""

  private val confirmSchema: Json =
    """{"type":"object","properties":{"confirm":{"type":"boolean"}},"required":["confirm"]}"""
      .fromJson[Json]
      .getOrElse(Json.Obj())

  private def question(text: String): Json =
    Json.Obj(
      "method" -> Json.Str("elicitation/create"),
      "params" -> Json.Obj("message" -> Json.Str(text), "requestedSchema" -> confirmSchema)
    )

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

  /** The whole flow as one effect. Returns what went wrong; empty when everything held. */
  private val scenario: ZIO[Any, Throwable, List[String]] =
    val server =
      McpServer(
        "TaskInputJs",
        "0.9.0",
        McpServerSettings(tasks = TaskSettings(enabled = true, pollIntervalMs = 10))
      )
    val confirm = McpTool[NoArgs, String](name = "confirm")
      .contextual { (_, context) =>
        context match
          case Some(ctx) =>
            ctx
              .elicit(ElicitRequestParams("Delete it?", confirmSchema))
              .map(answer => s"action=${answer.action}")
          case None => ZIO.fail(new IllegalStateException("missing context"))
      }
      .withTaskSupport(TaskSupport.Optional)
    val two = McpTool[NoArgs, String](name = "two")
      .contextual { (_, context) =>
        val responses = context.flatMap(_.session) match
          case Some(session) =>
            session.currentRequestContext.map(_.map(_.inputResponses).getOrElse(Map.empty))
          case None => ZIO.succeed(Map.empty[String, Json])
        responses.flatMap { answered =>
          if answered.contains("first") && answered.contains("second") then ZIO.succeed("both answered")
          else
            ZIO.fail(
              McpError.inputRequired(
                List("first" -> question("First?"), "second" -> question("Second?")),
                None
              )
            )
        }
      }
      .withTaskSupport(TaskSupport.Optional)
    val ids = new java.util.concurrent.atomic.AtomicInteger(0)
    val accept = """{"action":"accept","content":{"confirm":true}}"""
    for
      _ <- server.tool(confirm)
      _ <- server.tool(two)
      built <- server.buildRouterWithTasks
      (router, manager) = built
      session <- Session.make("task-input")
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
      errorCode = (method: String, body: String, capabilities: String) =>
        send(method, body, capabilities).map(reply =>
          member(reply, "error").flatMap(member(_, "code")).map(_.toJson).getOrElse("none")
        )
      until = (taskId: String, status: String) =>
        (ZIO.sleep(5.millis) *> result("tasks/get", s""""taskId":"$taskId""""))
          .repeatUntil(task => text(task, "status") == status)
          .timeoutFail(new RuntimeException(s"task never became $status"))(10.seconds)

      // One question: input_required, an ignored key, the answer, completed.
      created <- result("tools/call", """"name":"confirm","arguments":{}""")
      taskId = text(created, "taskId")
      waiting <- until(taskId, "input_required")
      key = keys(waiting).headOption.getOrElse("")
      ignored <- result("tasks/update", s""""taskId":"$taskId","inputResponses":{"never-issued":$accept}""")
      stillWaiting <- result("tasks/get", s""""taskId":"$taskId"""")
      ack <- result("tasks/update", s""""taskId":"$taskId","inputResponses":{"$key":$accept}""")
      done <- until(taskId, "completed")

      // Two questions: a partial answer leaves the other one outstanding.
      createdTwo <- result("tools/call", """"name":"two","arguments":{}""")
      twoId = text(createdTwo, "taskId")
      twoWaiting <- until(twoId, "input_required")
      _ <- result("tasks/update", s""""taskId":"$twoId","inputResponses":{"first":$accept}""")
      partial <- result("tasks/get", s""""taskId":"$twoId"""")
      _ <- result("tasks/update", s""""taskId":"$twoId","inputResponses":{"second":$accept}""")
      twoDone <- until(twoId, "completed")

      // The dispatch rules around it.
      gated <- errorCode("tasks/get", s""""taskId":"$taskId"""", NotDeclaring)
      unknown <- errorCode("tasks/update", """"taskId":"no-such-task","inputResponses":{}""", Declaring)
      cancelDone <- result("tasks/cancel", s""""taskId":"$taskId"""")
      legacyHint <- result(
        "tools/call",
        """"name":"confirm","arguments":{},"task":{"ttl":1000}"""
      )
      _ <- result("tasks/cancel", s""""taskId":"${text(legacyHint, "taskId")}"""")
      stats <- ZIO.foreach(manager)(_.stats)
      _ <- ZIO.foreachDiscard(manager)(_.shutdown)
    yield List(
      Option.when(text(created, "resultType") != "task")(s"no task: ${created.toJson}"),
      Option.when(text(waiting, "resultType") != "complete")(s"tasks/get: ${waiting.toJson}"),
      Option.when(keys(waiting).size != 1)(s"inputRequests: ${waiting.toJson}"),
      Option.when(!waiting.toJson.contains("elicitation/create"))(s"request: ${waiting.toJson}"),
      Option.when(member(waiting, "requestState").isDefined)("requestState on tasks/get"),
      Option.when(text(ignored, "resultType") != "complete")(s"ignored ack: ${ignored.toJson}"),
      Option.when(keys(stillWaiting) != List(key))(s"after an unknown key: ${stillWaiting.toJson}"),
      Option.when(text(ack, "resultType") != "complete" || member(ack, "status").isDefined)(
        s"update ack: ${ack.toJson}"
      ),
      Option.when(!done.toJson.contains("action=accept"))(s"result: ${done.toJson}"),
      Option.when(member(done, "inputRequests").isDefined)("inputRequests on a completed task"),
      Option.when(keys(twoWaiting) != List("first", "second"))(s"two: ${twoWaiting.toJson}"),
      Option.when(text(partial, "status") != "input_required" || keys(partial) != List("second"))(
        s"partial: ${partial.toJson}"
      ),
      Option.when(!twoDone.toJson.contains("both answered"))(s"two result: ${twoDone.toJson}"),
      Option.when(gated != "-32021")(s"undeclared tasks/get: $gated"),
      Option.when(unknown != "-32602")(s"unknown task: $unknown"),
      Option.when(text(cancelDone, "resultType") != "complete" || member(cancelDone, "status").isDefined)(
        s"cancel of a completed task: ${cancelDone.toJson}"
      ),
      Option.when(text(legacyHint, "resultType") != "task")(s"params.task: ${legacyHint.toJson}"),
      Option.when(!stats.exists(s => s.running == 0 && s.heldFibers == 0))(s"store: $stats")
    ).flatten

  "a task that asks for input" should "wait in input_required and resume on tasks/update" in {
    run(scenario).map(problems => withClue(problems.mkString(" | "))(problems shouldBe Nil))
  }
