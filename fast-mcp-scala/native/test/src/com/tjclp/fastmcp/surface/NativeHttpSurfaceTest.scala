package com.tjclp.fastmcp.surface

import org.scalatest.funsuite.AnyFunSuite
import zio.*

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.server.transport.{HttpTransportBackend, NativeHttpBackend}

/** The Scala Native HTTP surface: with the root import, `McpServerApp[Http]` now compiles and
  * resolves the `java.net.ServerSocket` backend (before #81 it failed at the declaration site by
  * design — zio-http has no Native artifacts). Compile-time resolution is the contract; building
  * the core proves the wiring without binding a port.
  */
class NativeHttpSurfaceTest extends AnyFunSuite:

  object HttpApp extends McpServerApp[Http, HttpApp.type]:
    override def settings: McpServerSettings = McpServerSettings(port = 0)

    @Tool(name = Some("add"), description = Some("Add two numbers"))
    def add(@Param("First") a: Int, @Param("Second") b: Int): Int = a + b

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }

  test("the root import resolves the socket backend as the HttpTransportBackend") {
    assert(summon[HttpTransportBackend] eq NativeHttpBackend)
  }

  test("McpServerApp[Http] compiles and builds its core on Scala Native") {
    assert(HttpApp.name == "HttpApp")
    runUnsafe(HttpApp.buildCore.unit)
    succeed
  }
