package com.tjclp.fastmcp.surface.socketconfigured

import org.scalatest.funsuite.AnyFunSuite

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.server.transport.{HttpTransportBackend, JvmSocketHttpBackend}

/** The documented way to install a CONFIGURED socket backend, at the top level of a file exactly as
  * `JvmSocketHttpBackend`'s scaladoc and docs/transports.md give it: a given declared under the
  * default's name, `httpInstance`. A definition in the file outranks the root wildcard import for
  * that name, so the import's `httpInstance` is not a candidate anywhere in the file. (A given
  * under any OTHER name does not do this — see `Pitfall.scala`.)
  */
given httpInstance: HttpTransportBackend = JvmSocketHttpBackend.withSettings(maxConnections = 8)

object ConfiguredApp extends McpServerApp[Http, ConfiguredApp.type]:
  @Tool(name = Some("add"), description = Some("Add two numbers"))
  def add(@Param("First") a: Int, @Param("Second") b: Int): Int = a + b

  val resolvedInBody: HttpTransportBackend = summon[HttpTransportBackend]

/** Resolves its backend where `McpServerApp`'s `using TransportRunner[Http]` does: in the parent
  * clause of a top-level object.
  */
trait ParentClauseProbe(using val resolved: HttpTransportBackend)

object TopLevelParentClause extends ParentClauseProbe

val resolvedAtTopLevel: HttpTransportBackend = summon[HttpTransportBackend]

class JvmSocketHttpConfiguredTest extends AnyFunSuite:

  test("a top-level given named httpInstance replaces the default at the top level of the file") {
    assert(resolvedAtTopLevel eq httpInstance)
  }

  test("... in the parent clause of a top-level object, where McpServerApp resolves its runner") {
    assert(TopLevelParentClause.resolved eq httpInstance)
  }

  test("... and inside the app object's body") {
    assert(ConfiguredApp.resolvedInBody eq httpInstance)
    assert(ConfiguredApp.name == "ConfiguredApp")
  }
