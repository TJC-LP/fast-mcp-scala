package com.tjclp.fastmcp
package examples

import zio.*
import zio.json.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.server.*

/** Scala.js Streamable-HTTP MCP server on Bun — mirror of the JVM [[HttpServer]].
  *
  * `stateless = true` in `McpServerSettings` flips the transport into JSON-response mode (no
  * SSE, fresh Server + transport per POST). Leaving it `false` (default) uses the full session-
  * based Streamable HTTP transport keyed by `mcp-session-id`.
  *
  * Bundle and run:
  * {{{
  *   ./mill fast-mcp-scala.js.fullLinkJS
  *   bun run out/fast-mcp-scala/js/fullLinkJS.dest/main.js
  * }}}
  */
object HttpServerJs extends ZIOAppDefault:

  case class GreetArgs(name: String)
  case class GreetResult(message: String)

  given JsonDecoder[GreetArgs] = DeriveJsonDecoder.gen[GreetArgs]
  given JsonEncoder[GreetResult] = DeriveJsonEncoder.gen[GreetResult]

  private val greetSchema = ToolInputSchema.unsafeFromJsonString(
    """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}"""
  )

  private val greetTool = McpTool.withSchema[GreetArgs, GreetResult](
    name = "greet",
    description = Some("Say hello"),
    inputSchema = greetSchema
  )(args => GreetResult(s"Hello, ${args.name}!"))

  override def run: ZIO[Any, Throwable, Unit] =
    val server = McpServer(
      "HttpServerJs",
      "0.1.0",
      McpServerSettings(
        host = "0.0.0.0",
        port = 8090,
        httpEndpoint = "/mcp",
        stateless = false // flip to `true` for request/response-only mode
      )
    )
    for
      _ <- server.tool(greetTool)
      _ <- server.runHttp()
    yield ()
