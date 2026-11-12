package com.tjclp.fastmcp.surface

import org.scalatest.funsuite.AnyFunSuite

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.server.transport.{HttpTransportBackend, JvmHttpBackend, JvmSocketHttpBackend}

/** The JVM keeps ZIO HTTP (Netty) as its default `HttpTransportBackend`; the netty-free socket
  * backend is an explicit opt-in. This file pins the default half — the root import resolves Netty
  * — and that a backend passed explicitly is the one used. The opt-in recipe itself is pinned in
  * `socketoptin/JvmSocketHttpOptInTest.scala`, written at the top level exactly as documented.
  */
class JvmSocketHttpBackendSurfaceTest extends AnyFunSuite:

  test("the root import resolves the Netty backend by default") {
    assert(summon[HttpTransportBackend] eq JvmHttpBackend)
  }

  test("a differently-named top-level given does NOT replace the default inside an object") {
    // The pitfall the documented recipe exists to avoid: at the top level the singleton-typed
    // given wins, but inside any object the root import is the more deeply nested binding.
    assert(Pitfall.resolvedAtTopLevel eq JvmSocketHttpBackend)
    assert(Pitfall.InObject.resolved eq JvmHttpBackend)
  }

  test("the socket backend is not exported from the package root") {
    assert(summon[HttpTransportBackend] ne JvmSocketHttpBackend)
  }
