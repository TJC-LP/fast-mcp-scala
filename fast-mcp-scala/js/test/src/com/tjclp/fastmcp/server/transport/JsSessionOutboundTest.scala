package com.tjclp.fastmcp.server.transport

import scala.concurrent.{ExecutionContext, Future}

import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.*

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.core.{TaskSupport, Tasks, ToolInputSchema}
import com.tjclp.fastmcp.jsonrpc.JsonRpcMessage
import com.tjclp.fastmcp.server.TaskSettings
import com.tjclp.fastmcp.server.router.Session
import com.tjclp.fastmcp.testkit.Eventually
import com.tjclp.fastmcp.server.transport.JsTransportBackend.given

/** Bun answers a legacy session's standalone GET with 405, so nothing ever reads the session's
  * out-of-request channel there. `JsTransportBackend` therefore builds those sessions with
  * `outboundCapacity = 0` (pinned by the JVM suite's `SessionOutboundBoundTest` source scan); this
  * drives the shared router on the JS runtime with exactly such a session and checks that the task
  * status notifications of completed legacy tasks are not kept. A drained (stdio) session still
  * receives every one of them.
  */
class JsSessionOutboundTest extends AsyncFlatSpec with Matchers:
  override implicit val executionContext: ExecutionContext = ExecutionContext.global

  case class QuickArgs(msg: Option[String] = None)
  given JsonDecoder[QuickArgs] = DeriveJsonDecoder.gen[QuickArgs]

  private val quick = McpTool
    .withSchema[QuickArgs, String](
      name = "quick",
      inputSchema = ToolInputSchema.unsafeFromJsonString("""{"type":"object","properties":{}}"""),
      description = Some("Done at once")
    )
    .contextual((_, _) => ZIO.succeed("quick done"))
    .withTaskSupport(TaskSupport.Optional)

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""

  private def call(id: Int): String =
    s"""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"quick","arguments":{},"task":{"ttl":60000}}}"""

  private def run[A](effect: ZIO[Any, Throwable, A]): Future[A] =
    Unsafe.unsafe(implicit unsafe => Runtime.default.unsafe.runToFuture(effect))

  /** Run `calls` task-augmented calls on `session` to completion; hand back the replies. */
  private def completedTasks(session: Session, calls: Int): ZIO[Any, Throwable, List[String]] =
    val server =
      McpServer("js-outbound", "0.1.0", McpServerSettings(tasks = TaskSettings(enabled = true)))
    for
      built <- server.tool(quick) *> server.buildRouterWithTasks
      router = built._1
      tm <- ZIO.fromOption(built._2).orElseFail(new RuntimeException("tasks enabled, no manager"))
      _ <- MessageLoop.handleFrame(router, session, initFrame)
      replies <- ZIO.foreach((1 to calls).toList)(i =>
        MessageLoop.handleFrame(router, session, call(i + 1)).map(_.getOrElse(""))
      )
      _ <- Eventually(s"all $calls tasks are terminal")(tm.stats)(stats =>
        stats.total == calls && stats.running == 0
      )
    yield replies

  "A Bun legacy HTTP session" should "keep none of the messages its tasks emit outside a request" in {
    run(
      for
        session <- Session.makeProtocolSession("bun-session", outboundCapacity = 0)
        replies <- completedTasks(session, calls = 50)
        retained <- session.outbound.size
      yield (replies, retained)
    ).map { (replies, retained) =>
      replies.count(_.contains(""""taskId"""")) shouldBe 50
      retained shouldBe 0
    }
  }

  "A stdio session" should "be handed every task status notification, however slow its writer" in {
    run(
      for
        // More notifications than the buffer holds: at the bound a finishing task waits for the
        // writer (back-pressure) instead of dropping a message.
        session <- Session.makeDrained("stdio", outboundCapacity = 4)
        seen <- Ref.make(List.empty[JsonRpcMessage])
        drainer <- (ZIO.sleep(2.millis) *> session.outbound.take.flatMap(m => seen.update(m :: _))).forever.fork
        _ <- completedTasks(session, calls = 20)
        received <- Eventually("all 20 task status notifications are read")(seen.get)(_.size >= 20)
        _ <- drainer.interrupt
      yield received
    ).map { received =>
      received should have size 20
      received.foreach {
        case JsonRpcMessage.Notification(method, _) => method shouldBe Tasks.NotificationTasksStatus
        case other => fail(s"unexpected message: $other")
      }
      succeed
    }
  }
