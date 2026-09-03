package com.tjclp.fastmcp.surface

import org.scalatest.funsuite.AnyFunSuite

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.server.transport.{HttpTransportBackend, JvmHttpBackend, JvmSocketHttpBackend}

/** The JVM keeps ZIO HTTP (Netty) as its default `HttpTransportBackend`; the netty-free socket
  * backend is an explicit opt-in. This pins both halves of that contract: the root import still
  * resolves Netty, and the documented opt-in recipe (a given of the object's singleton type, which
  * wins on specificity over the imported `HttpTransportBackend`) resolves the socket backend for
  * `McpServerApp[Http]` without any ambiguity.
  */
class JvmSocketHttpBackendSurfaceTest extends AnyFunSuite:

  test("the root import resolves the Netty backend by default") {
    assert(summon[HttpTransportBackend] eq JvmHttpBackend)
  }

  test("the documented opt-in resolves the socket backend") {
    assert(OptIn.resolved eq JvmSocketHttpBackend)
    assert(OptIn.SocketApp.name == "SocketApp")
  }

  /** The recipe exactly as the README and `JvmSocketHttpBackend`'s scaladoc give it. */
  object OptIn:
    given JvmSocketHttpBackend.type = JvmSocketHttpBackend

    val resolved: HttpTransportBackend = summon[HttpTransportBackend]

    object SocketApp extends McpServerApp[Http, SocketApp.type]:
      @Tool(name = Some("add"), description = Some("Add two numbers"))
      def add(@Param("First") a: Int, @Param("Second") b: Int): Int = a + b
