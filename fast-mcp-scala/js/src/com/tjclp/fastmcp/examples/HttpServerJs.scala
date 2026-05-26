package com.tjclp.fastmcp
package examples

import zio.json.*

import com.tjclp.fastmcp.{*, given}

/** Scala.js Streamable-HTTP MCP server on Bun — mirror of the JVM [[HttpServer]].
  *
  * Transport is a phantom type parameter on `McpServerApp[Http, HttpServerJs.type]`. Override
  * `settings` for host / port / endpoint / statelessness. The typed contract below shows explicit
  * JSON Schema input via `McpTool.withSchema` — mount it in `override val tools`.
  *
  * Bundle and run:
  * {{{
  *   ./mill fast-mcp-scala.js.fullLinkJS
  *   bun run out/fast-mcp-scala/js/fullLinkJS.dest/main.js
  * }}}
  */
object HttpServerJs extends McpServerApp[Http, HttpServerJs.type]:

  case class GreetArgs(name: String)
  case class GreetResult(message: String)

  given JsonDecoder[GreetArgs] = DeriveJsonDecoder.gen[GreetArgs]
  given JsonEncoder[GreetResult] = DeriveJsonEncoder.gen[GreetResult]

  private val greetSchema = ToolInputSchema.unsafeFromJsonString(
    """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}"""
  )

  private val greetTool = McpTool.withSchema[GreetArgs, GreetResult](
    name = "greet",
    inputSchema = greetSchema,
    description = Some("Say hello")
  )(args => GreetResult(s"Hello, ${args.name}!"))

  override def settings: McpServerSettings = McpServerSettings(
    host = "0.0.0.0",
    port = 8090,
    httpEndpoint = "/mcp",
    stateless = false
  )

  override val tools: List[McpTool[?, ?]] = List(greetTool)
