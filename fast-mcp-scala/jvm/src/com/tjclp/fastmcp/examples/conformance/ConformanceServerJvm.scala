package com.tjclp.fastmcp.examples.conformance

import zio.*

import com.tjclp.fastmcp.{*, given}

/** Launches the cross-platform [[ConformanceServer]] over JVM streamable HTTP for the official MCP
  * conformance harness (`bunx @modelcontextprotocol/conformance server --url …`).
  *
  * Port from `argv(0)` (default [[ConformanceServer.DefaultPort]]); binds `127.0.0.1` so the
  * DNS-rebinding scenario's localhost gate engages.
  *
  * `./mill fast-mcp-scala.jvm.runMain com.tjclp.fastmcp.examples.conformance.ConformanceServerJvm
  * 8077`
  */
object ConformanceServerJvm extends ZIOAppDefault:

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
