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
  * The js module's linked bundle (`./mill fast-mcp-scala.js.fastLinkJS`) has no module initializer
  * and exports only the conformance server's `startConformance`, so `bun run` on it starts nothing
  * (runnable example entry points are on the roadmap). Run this object on Bun with the scala-cli
  * recipe in `docs/platforms.md` (Scala.js / Bun), or link it from your own project.
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

  /** Binds loopback with Host/Origin validation on.
    *
    * The two are separate protections and you want both: binding `127.0.0.1` keeps other machines
    * out, and the `allowedHosts` guard keeps a web page in a local browser out (a request whose
    * `Host` is not one of these names, or whose `Origin` is not this server's own, is refused with
    * 403). These three names are what a loopback bind gets when `allowedHosts` is left unset — they
    * are spelled out here so that the guard stays on if you copy this and change `host`. To expose
    * the server (`host = "0.0.0.0"`), list the name clients reach it by here (and browser
    * front-ends in `allowedOrigins`), and put an authenticating proxy in front of it.
    */
  override def settings: McpServerSettings = McpServerSettings(
    host = "127.0.0.1",
    port = 8090,
    httpEndpoint = "/mcp",
    stateless = false,
    allowedHosts = Some(Set("localhost", "127.0.0.1", "[::1]"))
  )

  override val tools: List[McpTool[?, ?]] = List(greetTool)
