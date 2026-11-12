package com.tjclp.fastmcp.surface

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.server.transport.{HttpTransportBackend, JvmSocketHttpBackend}

/** Fixture for `JvmSocketHttpBackendSurfaceTest`: a top-level given of the object's singleton type,
  * which looks like an opt-in but is not one (see the test).
  */
object Pitfall:

  val resolvedAtTopLevel: HttpTransportBackend = TopLevel.resolved

  object InObject:
    val resolved: HttpTransportBackend = PitfallScope.inObject

given JvmSocketHttpBackend.type = JvmSocketHttpBackend

private val topLevelResolved: HttpTransportBackend = summon[HttpTransportBackend]

private object TopLevel:
  val resolved: HttpTransportBackend = topLevelResolved

private object PitfallScope:
  val inObject: HttpTransportBackend = summon[HttpTransportBackend]
