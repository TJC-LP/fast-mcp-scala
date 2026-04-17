package com.tjclp.fastmcp
package facades
package server

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** Scala.js facade for `InMemoryTransport` from `@modelcontextprotocol/sdk/inMemory.js`.
  *
  * `createLinkedPair()` returns a `[clientTransport, serverTransport]` tuple that can be used to
  * stand up an end-to-end MCP session without spawning a process or opening a port. Ideal for unit
  * and conformance tests.
  */
@JSImport("@modelcontextprotocol/sdk/inMemory.js", "InMemoryTransport")
@js.native
object InMemoryTransport extends js.Object:
  def createLinkedPair(): js.Tuple2[Transport, Transport] = js.native
