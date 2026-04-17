package com.tjclp.fastmcp
package examples

import zio.*
import zio.json.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.server.*

/** The smallest Scala.js MCP server — one typed tool, stdio transport, running under Bun/Node.
  *
  * Mirrors [[com.tjclp.fastmcp.examples.HelloWorld]] on the JVM. Same `McpServer("name", version)`
  * factory, same typed-contract API, different backend.
  *
  * Run with:
  * {{{
  *   ./mill fast-mcp-scala.js.fastLinkJS
  *   bun run out/fast-mcp-scala/js/fastLinkJS.dest/main.js
  * }}}
  * …or drive it under the MCP Inspector once bundled.
  */
object HelloWorldJs extends ZIOAppDefault:

  case class AddArgs(a: Int, b: Int)
  case class AddResult(sum: Int)

  given JsonDecoder[AddArgs] = DeriveJsonDecoder.gen[AddArgs]
  given JsonEncoder[AddResult] = DeriveJsonEncoder.gen[AddResult]

  // On Scala.js we supply the JSON schema manually — Tapir's schema derivation is JVM-only.
  private val addSchema = ToolInputSchema.unsafeFromJsonString(
    """{"type":"object","properties":{"a":{"type":"integer"},"b":{"type":"integer"}},"required":["a","b"]}"""
  )

  private val addTool = McpTool[AddArgs, AddResult](
    name = "add",
    description = Some("Add two numbers"),
    inputSchema = addSchema
  )(args => ZIO.succeed(AddResult(args.a + args.b)))

  override def run: ZIO[Any, Throwable, Unit] =
    for
      server <- ZIO.succeed(McpServer("HelloWorldJs", "0.1.0"))
      _ <- server.tool(addTool)
      _ <- server.runStdio()
    yield ()
