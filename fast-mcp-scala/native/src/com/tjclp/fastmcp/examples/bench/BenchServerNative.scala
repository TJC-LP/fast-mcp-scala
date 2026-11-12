package com.tjclp.fastmcp.examples.bench

import scala.annotation.experimental

import zio.*

import com.tjclp.fastmcp.{*, given}

/** Scala Native launcher for [[BenchServer]] over the `java.net.ServerSocket` backend
  * (`bench/run.sh scala-native`). `BenchServerNative [port] [stateless]`.
  *
  * Linked by `./mill fast-mcp-scala.scalaNative.benchLink`; use `SCALANATIVE_MODE=release-fast` for
  * numbers worth quoting.
  */
@experimental // HTTP on Scala Native is experimental (NativeHttpBackend)
object BenchServerNative extends ZIOAppDefault:

  override def run =
    for
      args <- getArgs
      port = args.headOption.flatMap(_.toIntOption).getOrElse(BenchServer.DefaultPort)
      settings = BenchServer.settings(port, stateless = args.contains("stateless"))
      server = McpServer(BenchServer.Name, BenchServer.Version, settings)
      _ <- BenchServer.register(server)
      _ <- server.runHttp()
    yield ()
