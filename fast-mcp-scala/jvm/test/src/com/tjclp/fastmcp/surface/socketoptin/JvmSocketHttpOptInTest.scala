package com.tjclp.fastmcp.surface.socketoptin

import org.scalatest.funsuite.AnyFunSuite

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.server.transport.{HttpTransportBackend, JvmSocketHttpBackend}
import com.tjclp.fastmcp.server.transport.JvmSocketHttpBackend.httpInstance

/** The documented opt-in, at the top level of a file exactly as the README and
  * `JvmSocketHttpBackend`'s scaladoc give it: the root import plus a NAMED import of the socket
  * backend's `httpInstance`.
  */
object SocketApp extends McpServerApp[Http, SocketApp.type]:
  @Tool(name = Some("add"), description = Some("Add two numbers"))
  def add(@Param("First") a: Int, @Param("Second") b: Int): Int = a + b

  val resolvedInBody: HttpTransportBackend = summon[HttpTransportBackend]

/** Resolves its backend where `McpServerApp`'s `using TransportRunner[Http]` does: in the parent
  * clause of a top-level object.
  */
trait ParentClauseProbe(using val resolved: HttpTransportBackend)

object TopLevelParentClause extends ParentClauseProbe

val resolvedAtTopLevel: HttpTransportBackend = summon[HttpTransportBackend]

class JvmSocketHttpOptInTest extends AnyFunSuite:

  test("the named import replaces the default backend at the top level of the file") {
    assert(resolvedAtTopLevel eq JvmSocketHttpBackend)
  }

  test("... in the parent clause of a top-level object, where McpServerApp resolves its runner") {
    assert(TopLevelParentClause.resolved eq JvmSocketHttpBackend)
  }

  test("... and inside the app object's body") {
    assert(SocketApp.resolvedInBody eq JvmSocketHttpBackend)
    assert(SocketApp.name == "SocketApp")
  }
