package com.tjclp.fastmcp.server

import zio.*
import zio.test.*

import java.util.concurrent.atomic.AtomicBoolean

object FastMcpServerShutdownSpec extends ZIOSpecDefault {

  private final class MockCloseableServer(flag: AtomicBoolean) extends AutoCloseable {
    override def close(): Unit = flag.set(true)
  }

  def spec =
    suite("FastMcpServer graceful-shutdown")(
      test("runStdio closes the underlying server when interrupted") {
        for {
          closedFlag <- ZIO.succeed(new AtomicBoolean(false))

          server <- ZIO.succeed(
            new FastMcpServer() {
              override def runStdio() =
                ZIO.scoped {
                  ZIO.acquireRelease(
                    ZIO.succeed(new MockCloseableServer(closedFlag))
                  )(srv => ZIO.succeed(srv.close())) *> ZIO.never
                }
            }
          )

          fiber <- server.runStdio().fork
          _ <- TestClock.adjust(10.millis)
          _ <- fiber.interrupt
        } yield assertTrue(closedFlag.get())
      }
    )
}
