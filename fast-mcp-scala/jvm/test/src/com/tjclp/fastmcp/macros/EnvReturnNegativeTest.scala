package com.tjclp.fastmcp
package macros

import org.scalatest.funsuite.AnyFunSuite
import zio.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.RegistrationMacro.scanAnnotations
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.transport.JvmTransportBackend.given

/** A handler that requires a `Ref[Int]` env — used by [[EnvReturnNegativeTest]] to assert the
  * macro rejects a mismatched server R.
  */
object EnvNegativeHandlers:

  @Tool(name = Some("needs-ref"), description = Some("Requires Ref[Int] in env"))
  def needsRef(): ZIO[Ref[Int], Throwable, Int] =
    ZIO.serviceWithZIO[Ref[Int]](_.get)

/** Negative assertion: scanning a handler that requires `R=Ref[Int]` on a server typed for
  * `R=Any` should fail at macro expansion with our actionable error message.
  */
class EnvReturnNegativeTest extends AnyFunSuite:

  test("@Tool with ZIO[Ref[Int], ...] is rejected on McpServer[Any]") {
    assertDoesNotCompile("""
      val server = McpServer.typed[Any]("NegativeServer", "0.1.0")
      server.scanAnnotations[EnvNegativeHandlers.type]
    """)
  }
