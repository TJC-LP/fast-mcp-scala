package com.tjclp.fastmcp
package server

import org.scalatest.funsuite.AnyFunSuite
import zio.*

/** Issue #55 on the JS target: `McpServer.typed[R]` exposes `runHttp() / runStdio()` with
  * `ZIO[R, Throwable, Unit]` returns, so users can chain `.provide(layer)` from the JS runtime
  * the same way they can on the JVM (one shared class — parity is structural). The compile-check
  * is the test; we don't bind a port.
  */
class JsServerEnvReturnTest extends AnyFunSuite:

  test("McpServer.typed[R]() yields runHttp(): ZIO[R, Throwable, Unit] on JS") {
    val server = McpServer.typed[Ref[Int]]("JsEnvReturnServer", "0.1.0")
    val effect: ZIO[Ref[Int], Throwable, Unit] = server.runHttp()
    assert(effect != null)
  }
