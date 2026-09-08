package com.tjclp.fastmcp.server.router

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.ast.Json

import com.tjclp.fastmcp.jsonrpc.JsonRpcMessage

/** DRAFT (C3 step 5, TJC-2328) — RED on main 8b2fede by construction.
  *
  * `Session.outbound` is `Queue.unbounded` (Session.scala:282). On a Bun legacy session there is no
  * GET push channel and nothing else ever drains that queue (JsTransportBackend.scala:400-405
  * answers 405; only stdio forks a drainer, JsTransportBackend.scala:55-58 / StdioLoop.scala:53-56),
  * so every `Session.send` issued OUTSIDE a per-POST sink — the legacy `notifications/tasks/status`
  * (TaskRouting.scala:106 under `runWithoutSink`), `ctx.sendProgress` / `ctx.sendLogMessage` from a
  * task body (McpContext.scala:98,114) — accumulates for the session's lifetime (D6 collateral, D4
  * row 35). This spec pins the 1.0.1 policy proposed in `$SCRATCH/c3/outbound.md`:
  *
  *   1. a session constructed WITHOUT a push channel keeps at most a bounded number of undelivered
  *      outbound messages (sliding / dropping, sized from `LimitSettings`), and
  *   2. `sendRequest` on such a session fails FAST with an error naming the missing channel instead
  *      of parking for the full 60 s `McpContext.DefaultRequestTimeout`.
  *
  * Both tests use only today's `Session` API so the file compiles against main; they fail on the
  * assertions (100 queued; "timed out" instead of a channel error) until the policy lands. When the
  * policy adds a constructor flag (e.g. `Session.make(id, pushChannel = false)`), switch the
  * `Session.make` calls below to it — the JVM streamable session keeps its unbounded/buffered
  * behaviour because a GET may open later.
  */
class SessionOutboundPolicySpec extends AnyFlatSpec with Matchers:

  private def runUnsafe[A](z: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run(z).getOrThrowFiberFailure()
    }

  /** Proposed default for `LimitSettings.maxUndeliveredOutbound` (see outbound.md). */
  private val ProposedBound = 64

  private def statusNotification(i: Int): JsonRpcMessage =
    JsonRpcMessage.Notification(
      "notifications/tasks/status",
      Some(Json.Obj("taskId" -> Json.Str(s"t-$i"), "status" -> Json.Str("completed")))
    )

  "a session without a push channel" should
    "keep at most a bounded number of undelivered outbound messages after 100 out-of-sink sends" in {
      val size = runUnsafe(
        for
          // Bun legacy session shape: minted by mintSession (JsTransportBackend.scala:425), never
          // drained. No sink is active here, exactly like a task fiber under `runWithoutSink`.
          session <- Session.make("bun-legacy-no-get")
          _ <- ZIO.foreachDiscard(1 to 100)(i => session.send(statusNotification(i)))
          n <- session.outbound.size
        yield n
      )
      withClue(s"undelivered outbound messages queued on a drainer-less session: $size ") {
        size should be <= ProposedBound
      }
    }

  it should "fail sendRequest fast, naming the missing server->client channel" in {
    val (elapsedMs, outcome) = runUnsafe(
      for
        session <- Session.make("bun-legacy-no-get")
        start <- Clock.nanoTime
        // A legacy `sampling/createMessage` from inside a task body on Bun: today this parks for
        // the caller's full timeout and then fails with "... timed out".
        exit <- session.sendRequest("sampling/createMessage", None, 2.seconds).exit
        end <- Clock.nanoTime
      yield ((end - start) / 1_000_000L, exit)
    )
    outcome match
      case Exit.Failure(cause) =>
        val message = cause.failureOption.map(_.message).getOrElse(cause.prettyPrint)
        withClue(s"sendRequest failed after ${elapsedMs}ms with: $message ") {
          elapsedMs should be < 1000L
          message.toLowerCase should include("channel")
        }
      case Exit.Success(v) => fail(s"sendRequest with no push channel succeeded with $v")
  }
