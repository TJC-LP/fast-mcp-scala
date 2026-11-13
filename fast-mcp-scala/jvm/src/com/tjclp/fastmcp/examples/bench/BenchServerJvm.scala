package com.tjclp.fastmcp.examples.bench

import zio.*

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.server.transport.{
  HttpTransportBackend,
  JvmHttpBackend,
  JvmSocketHttpBackend
}

/** JVM launcher for [[BenchServer]] (`bench/run.sh`): one main for both JVM arms, so they differ
  * only in the HTTP backend.
  *
  * `BenchServerJvm [port] [netty|socket] [stateless]` — `netty` is the default ZIO HTTP backend,
  * `socket` the `java.net.ServerSocket` one.
  */
object BenchServerJvm extends ZIOAppDefault:

  override def run =
    for
      args <- getArgs
      port = args.headOption.flatMap(_.toIntOption).getOrElse(BenchServer.DefaultPort)
      backend: HttpTransportBackend =
        if args.lift(1).contains("socket") then JvmSocketHttpBackend else JvmHttpBackend
      settings = BenchServer.settings(port, stateless = args.contains("stateless"))
      server = McpServer(BenchServer.Name, BenchServer.Version, settings)
      _ <- BenchServer.register(server)
      _ <- server.runHttp()(using backend)
    yield ()
