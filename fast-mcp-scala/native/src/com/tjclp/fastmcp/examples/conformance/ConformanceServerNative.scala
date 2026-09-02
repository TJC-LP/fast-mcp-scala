package com.tjclp.fastmcp.examples.conformance

import zio.*

import com.tjclp.fastmcp.{*, given}

/** Launches the cross-platform [[ConformanceServer]] over Scala Native streamable HTTP — the
  * `java.net.ServerSocket` backend — for the official MCP conformance harness (`bunx
  * \@modelcontextprotocol/conformance server --url …`). Held to the same empty baseline as the JVM:
  * the two share every MCP decision through the shared handler, so any divergence is a socket-layer
  * bug, never a new baseline.
  *
  * Port from `argv(0)` (default [[ConformanceServer.DefaultPort]]); binds `127.0.0.1` so the
  * DNS-rebinding scenario's localhost gate engages.
  *
  * Linked by `./mill fast-mcp-scala.scalaNative.conformanceLink` and driven by
  * `scripts/conformance.sh scala-native`.
  */
object ConformanceServerNative extends ZIOAppDefault:

  override def run =
    for
      args <- getArgs
      port = args.headOption.flatMap(_.toIntOption).getOrElse(ConformanceServer.DefaultPort)
      server = McpServer(
        ConformanceServer.Name,
        ConformanceServer.Version,
        ConformanceServer.settings(port)
      )
      _ <- ConformanceServer.register(server)
      _ <- server.runHttp()
    yield ()
