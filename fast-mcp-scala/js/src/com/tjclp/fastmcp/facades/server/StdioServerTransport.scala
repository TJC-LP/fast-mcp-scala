package com.tjclp.fastmcp
package facades
package server

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** Scala.js facade for `StdioServerTransport` from `@modelcontextprotocol/sdk/server/stdio.js`.
  *
  * Runs on Node.js and Bun; reads JSON-RPC framed messages from `process.stdin` and writes
  * responses to `process.stdout`. The no-argument constructor defaults to the current process
  * streams — same behavior as the TS SDK.
  */
@JSImport("@modelcontextprotocol/sdk/server/stdio.js", "StdioServerTransport")
@js.native
class StdioServerTransport() extends Transport:
  def start(): js.Promise[Unit] = js.native
  def close(): js.Promise[Unit] = js.native
