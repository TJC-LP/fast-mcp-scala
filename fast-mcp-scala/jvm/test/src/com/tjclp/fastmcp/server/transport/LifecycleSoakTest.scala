package com.tjclp.fastmcp
package server.transport

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.http.*
import zio.stream.ZPipeline

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.RegistrationMacro.*
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.transport.JvmTransportBackend.given

/** RC1 soak gate (docs/2026-07-28-upgrade.md): disconnected `subscriptions/listen` streams and
  * task-TTL cleanup must leave no fibers or task entries behind. Fiber hygiene is asserted via
  * `Fiber.roots` deltas — a leak of the loop size (25 streams / 10 tasks) is unambiguous against
  * the small tolerance allowed for runtime bookkeeping fibers.
  */
class LifecycleSoakTest extends AnyFunSuite with Matchers:

  object SoakServer:
    @Tool(name = Some("blocky"), description = Some("Long-running"), taskSupport = Some("optional"))
    def blocky(): ZIO[Any, Throwable, String] = ZIO.sleep(30.seconds).as("blocky")

  private val SessionIdHeader = "mcp-session-id"

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private def buildRoutes(): Routes[Any, Response] =
    val server = McpServer.typed[Any](
      "SoakT",
      "0.1.0",
      McpServerSettings(tasks = TaskSettings(enabled = true, pollIntervalMs = 50))
    )
    val _ = server.scanAnnotations[SoakServer.type]
    runUnsafe(
      server.buildRouter.flatMap(r =>
        JvmHttpBackend.httpRoutes(r, server.settings, ZEnvironment.empty)
      )
    )

  private def post(routes: Routes[Any, Response], body: String, sid: Option[String]): Response =
    val base = Request.post(URL(Path.root / "mcp"), Body.fromString(body))
    val req = sid.fold(base)(s => base.addHeader(Header.Custom(SessionIdHeader, s)))
    runUnsafe(ZIO.scoped(routes.runZIO(req)))

  private def bodyOf(resp: Response): String = runUnsafe(resp.body.asString)

  private def initSession(routes: Routes[Any, Response]): String =
    val resp = post(routes, initFrame, None)
    val _ = bodyOf(resp)
    resp.rawHeader(SessionIdHeader).getOrElse(fail("initialize did not return a session id"))

  private def rootFibers: Int = runUnsafe(Fiber.roots.map(_.size))

  test("dropped subscriptions/listen streams leave no fibers behind") {
    val routes = buildRoutes()
    def listenFrame(id: Int): String =
      s"""{"jsonrpc":"2.0","id":$id,"method":"subscriptions/listen","params":{"notifications":{"toolsListChanged":true},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}"""
    def openReadAckAndDrop(id: Int): ZIO[Any, Throwable, Unit] =
      ZIO.scoped {
        val req = Request
          .post(URL(Path.root / "mcp"), Body.fromString(listenFrame(id)))
          .addHeader(Header.Custom("content-type", "application/json"))
          .addHeader(Header.Custom("accept", "application/json, text/event-stream"))
          .addHeader(Header.Custom("mcp-protocol-version", "2026-07-28"))
          .addHeader(Header.Custom("mcp-method", "subscriptions/listen"))
        routes.runZIO(req).flatMap { resp =>
          resp.body.asStream
            .via(ZPipeline.utf8Decode)
            .scan("")(_ + _)
            .takeUntil(_.contains("acknowledged"))
            .runLast
            .timeoutFail(new RuntimeException("no acknowledgement"))(10.seconds)
            .unit
        }
        // Scope close ends the response stream mid-listen: the finalizer must interrupt the
        // dispatch fiber and its ZIO.never listen handler.
      }

    val before = rootFibers
    runUnsafe(ZIO.foreachDiscard(1 to 25)(openReadAckAndDrop))
    runUnsafe(ZIO.sleep(500.millis)) // let interruption finalizers settle
    val after = rootFibers
    withClue(s"root fibers before=$before after=$after: ") {
      (after - before) should be <= 5
    }
  }

  test("expired task TTLs interrupt running work and sweep the entries") {
    val routes = buildRoutes()
    val sid = initSession(routes)
    def augmented(id: Int): String =
      s"""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"blocky","arguments":{},"task":{"ttl":300}}}"""
    val TaskIdPattern = """"taskId":"([^"]+)"""".r

    val before = rootFibers
    val taskIds = (1 to 10).toList.map { i =>
      val body = bodyOf(post(routes, augmented(100 + i), Some(sid)))
      TaskIdPattern.findFirstMatchIn(body).map(_.group(1)).getOrElse(fail(s"no taskId in: $body"))
    }
    // Each task sleeps 30s; the 300ms TTL must interrupt the work and sweep the entry.
    runUnsafe(ZIO.sleep(1500.millis))
    taskIds.foreach { taskId =>
      val reply = bodyOf(
        post(
          routes,
          s"""{"jsonrpc":"2.0","id":200,"method":"tasks/get","params":{"taskId":"$taskId"}}""",
          Some(sid)
        )
      )
      reply should include(""""code":-32602""")
      reply should include("Unknown task")
    }
    val after = rootFibers
    withClue(s"root fibers before=$before after=$after: ") {
      (after - before) should be <= 5
    }
  }
