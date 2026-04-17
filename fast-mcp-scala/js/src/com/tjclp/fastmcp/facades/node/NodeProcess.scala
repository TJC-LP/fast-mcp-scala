package com.tjclp.fastmcp
package facades
package node

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

/** Minimal `@js.native` shim for the parts of the Node/Bun `process` global that the MCP runtime
  * needs. The TS SDK's `StdioServerTransport` wires stdin/stdout itself — we use this facade only
  * for stderr logging, process-wide lifecycle hooks, and environment variables.
  */
@js.native
@JSGlobal("process")
object NodeProcess extends js.Object:
  val stderr: NodeWritableStream = js.native
  val env: js.Dictionary[String] = js.native
  def on(event: String, handler: js.Function0[Unit]): Unit = js.native

/** Minimal subset of Node's writable stream we need for `process.stderr.write(...)`. */
@js.native
trait NodeWritableStream extends js.Object:
  def write(chunk: String): Boolean = js.native
