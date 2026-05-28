package com.tjclp.fastmcp
package facades
package node

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

/** Minimal `@js.native` shim for the parts of the Node/Bun `process` global that the native MCP
  * stdio transport needs: `stdin` (line-delimited JSON-RPC frames in), `stdout` (frames out),
  * `stderr` (logging), plus lifecycle hooks and environment variables.
  */
@js.native
@JSGlobal("process")
object NodeProcess extends js.Object:
  val stdin: NodeReadableStream = js.native
  val stdout: NodeWritableStream = js.native
  val stderr: NodeWritableStream = js.native
  val env: js.Dictionary[String] = js.native
  def on(event: String, handler: js.Function0[Unit]): Unit = js.native

/** Minimal subset of Node's writable stream we need for `process.stdout`/`stderr.write(...)`. */
@js.native
trait NodeWritableStream extends js.Object:
  def write(chunk: String): Boolean = js.native

/** Minimal subset of Node's readable stream for reading `process.stdin`. `on("data", …)` delivers
  * decoded string chunks once `setEncoding("utf8")` is set; `on("end", …)` fires at EOF.
  */
@js.native
trait NodeReadableStream extends js.Object:
  def on(event: String, handler: js.Function1[js.Any, Unit]): Unit = js.native
  def setEncoding(encoding: String): Unit = js.native
  def resume(): Unit = js.native
