package com.tjclp.fastmcp
package server.transport

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.stream.*

import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.router.Session
import com.tjclp.fastmcp.server.transport.NativeTransportBackend.given

/** Platform contracts for the Scala Native stdio backend: the /dev/urandom-backed `randomId`
  * (javalib `UUID.randomUUID` does not link on SN 0.5), plus the shared [[StdioLoop]] termination
  * contracts re-run *on the Scala Native runtime* — EOF and interruption must both end the loop AND
  * its outbound-drainer fiber. The loop itself is shared with the JVM, but its fiber semantics rest
  * on SN's threading and GC, so these deliberately duplicate the JVM `StdioLoopLifecycleTest` cases
  * as a platform canary rather than trusting the JVM run.
  */
class NativeTransportBackendTest extends AnyFunSuite with Matchers:

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private val UuidV4 =
    "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$".r

  test("randomId yields distinct RFC-4122 v4 UUIDs from /dev/urandom") {
    val ids = runUnsafe(ZIO.foreach(1 to 100)(_ => NativeTransportBackend.randomId()))
    ids.foreach { id =>
      assert(UuidV4.matches(id), s"not a v4 UUID: $id")
    }
    ids.distinct.size shouldBe ids.size
  }

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""

  test("stdin EOF ends the loop and takes the outbound drainer down with it") {
    val program =
      for
        server <- ZIO.succeed(McpServer("EofServer"))
        router <- server.buildRouter
        session <- Session.make("stdio-eof")
        outQ <- Queue.unbounded[String]
        // A finite inbound stream IS the EOF: the loop must dispatch the frame and return.
        _ <- StdioLoop.run(
          router,
          session,
          ZStream(initFrame),
          s => outQ.offer(s).unit
        )
        _ <- outQ.take // the initialize reply was emitted before EOF completed the loop
        // The drainer is interrupted at EOF: an outbound push after loop end must NOT be emitted.
        _ <- session.send(com.tjclp.fastmcp.jsonrpc.JsonRpcMessage.Notification("post/eof", None))
        _ <- ZIO.sleep(200.millis)
        leaked <- outQ.poll
      yield leaked

    val leaked = runUnsafe(
      program.timeoutFail(new RuntimeException("stdio loop did not terminate on EOF"))(10.seconds)
    )
    leaked shouldBe None
  }

  test("interrupting the loop fiber terminates it promptly (graceful shutdown)") {
    val program =
      for
        server <- ZIO.succeed(McpServer("ShutdownServer"))
        router <- server.buildRouter
        session <- Session.make("stdio-shutdown")
        inQ <- Queue.unbounded[String] // never closed — the loop would run forever
        loop <- StdioLoop
          .run(router, session, ZStream.fromQueue(inQ), _ => ZIO.unit)
          .fork
        _ <- inQ.offer(initFrame)
        _ <- ZIO.sleep(100.millis)
        exit <- loop.interrupt
      yield exit

    val exit = runUnsafe(
      program.timeoutFail(new RuntimeException("stdio loop did not stop on interruption"))(
        10.seconds
      )
    )
    exit.isInterrupted shouldBe true
  }
