package com.tjclp.fastmcp
package server

import org.scalatest.funsuite.AnyFunSuite
import zio.*

/** Cross-platform parity for issue #55: a `JsMcpServer[R]` exposes `runHttp() / runStdio()` with
  * `ZIO[R, Throwable, Unit]` returns, so users can chain `.provide(layer)` from the JS runtime
  * the same way they can on the JVM. The compile-check is the test; we don't bind a port.
  */
class JsServerEnvReturnTest extends AnyFunSuite:

  test("JsMcpServer.typed[R]() yields runHttp(): ZIO[R, Throwable, Unit]") {
    val server = McpServer.typed[Ref[Int]]("JsEnvReturnServer", "0.1.0")
    val effect: ZIO[Ref[Int], Throwable, Unit] = server.runHttp()
    assert(effect != null)
  }
