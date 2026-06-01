package com.tjclp.fastmcp.examples.conformance

import scala.scalajs.js.annotation.JSExportTopLevel

import zio.{Runtime, Unsafe}

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.server.transport.*

/** Bun entry for the cross-platform [[ConformanceServer]] over the Scala.js streamable HTTP
  * transport. Exported as `startConformance` so `scripts/conformance.sh js` can import and launch
  * it from a tiny Bun entry module.
  *
  * The Bun streamable transport streams server→client messages (sampling / elicitation / progress /
  * logging) on each request's own POST SSE response, so the full active conformance suite passes on
  * JS, matching the JVM transport.
  */
object ConformanceServerJs:

  @JSExportTopLevel("startConformance")
  def start(port: Int): Unit =
    val server =
      McpServer(ConformanceServer.Name, ConformanceServer.Version, ConformanceServer.settings(port))
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run(ConformanceServer.register(server)).getOrThrowFiberFailure()
    }
    val _ = server.startStatefulHttp()
