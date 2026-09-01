package com.tjclp.fastmcp
package server.transport

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.*
import zio.stream.*

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.core.wire.{ListRootsResult, Root}
import com.tjclp.fastmcp.jsonrpc.{JsonRpcMessage, McpError}
import com.tjclp.fastmcp.server.router.Session

/** Regression test for the stdio dispatch-concurrency fix.
  *
  * A tool that issues a server→client `roots/list` request must resolve from a *later* inbound frame
  * on the same connection. A sequential read loop (awaiting each dispatch) deadlocks here;
  * [[StdioLoop.run]] forks each frame so the reply can be read while the handler is
  * still blocked.
  *
  * Driven over in-memory queues (no real `System.in`/`out`); a hard timeout turns a regression
  * deadlock into a fast failure rather than a hung suite.
  */
class StdioLoopServerRequestTest extends AnyFunSuite with Matchers:

  case class RootsToolArgs(unused: Boolean = false)

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private def takeUntil(q: Queue[String], pred: String => Boolean): UIO[String] =
    q.take.flatMap(s => if pred(s) then ZIO.succeed(s) else takeUntil(q, pred))

  test("stdio loop resolves a tool's server→client roots/list from a later inbound frame") {
    val initFrame =
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{"roots":{}},"clientInfo":{"name":"t","version":"1.0"}}}"""
    val toolsCall =
      """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_roots","arguments":{"unused":false}}}"""

    val program =
      for
        server <- ZIO.succeed(McpServer("StdioLoopServer"))
        _ <- server.tool(
          McpTool[RootsToolArgs, String](name = "list_roots").contextual { (_, ctxOpt) =>
            val eff: Task[String] = ctxOpt match
              case Some(ctx) => ctx.listRoots().map(_.roots.map(_.uri).mkString(","))
              case None => ZIO.fail(McpError.internalError("no context"))
            eff
          }
        )
        router <- server.buildRouter
        session <- Session.make("stdio-loop")
        inQ <- Queue.unbounded[String]
        outQ <- Queue.unbounded[String]
        loop <- StdioLoop
          .run(router, session, ZStream.fromQueue(inQ), s => outQ.offer(s).unit)
          .fork
        _ <- inQ.offer(initFrame)
        _ <- takeUntil(outQ, _.contains("\"id\":1")) // initialize reply (caps now stored on session)
        _ <- inQ.offer("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        _ <- inQ.offer(toolsCall) // tool handler forks, emits roots/list, then blocks awaiting it
        reqLine <- takeUntil(outQ, _.contains("\"method\":\"roots/list\""))
        reqId <- reqLine.fromJson[JsonRpcMessage] match
          case Right(JsonRpcMessage.Request(id, _, _)) => ZIO.succeed(id)
          case _ => ZIO.fail(new RuntimeException(s"expected a roots/list request, got: $reqLine"))
        rootsJson = ListRootsResult(List(Root("file:///workspace", Some("ws")), Root("file:///tmp")))
          .toJsonAST
          .toOption
          .get
        reply = (JsonRpcMessage.Success(reqId, rootsJson): JsonRpcMessage).toJson
        _ <- inQ.offer(reply) // the server→client reply the loop must read mid-handler
        resultLine <- takeUntil(outQ, _.contains("\"id\":2"))
        _ <- loop.interrupt
      yield resultLine

    val result = runUnsafe(
      program.timeoutFail(
        new RuntimeException("DEADLOCK: stdio loop did not complete the server→client round-trip")
      )(15.seconds)
    )
    result should include("file:///workspace")
    result should include("file:///tmp")
  }
