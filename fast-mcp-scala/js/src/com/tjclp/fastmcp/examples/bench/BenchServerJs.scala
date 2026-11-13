package com.tjclp.fastmcp.examples.bench

import scala.scalajs.js.annotation.JSExportTopLevel

import zio.{Runtime, Unsafe}

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.server.transport.*

/** Bun entry for [[BenchServer]], exported as `startBench` so `bench/run.sh` can import and launch
  * it from a two-line entry module (the same arrangement as `startConformance`).
  */
object BenchServerJs:

  @JSExportTopLevel("startBench")
  def start(port: Int, stateless: Boolean): Unit =
    val server =
      McpServer(BenchServer.Name, BenchServer.Version, BenchServer.settings(port, stateless))
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run(BenchServer.register(server)).getOrThrowFiberFailure()
    }
    val _ = if stateless then server.startStatelessHttp() else server.startStatefulHttp()
