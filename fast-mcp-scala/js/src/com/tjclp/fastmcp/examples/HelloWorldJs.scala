package com.tjclp.fastmcp
package examples

import com.tjclp.fastmcp.macros.RegistrationMacro.*
import zio.*

import com.tjclp.fastmcp.server.*

/** The smallest Scala.js MCP server — one annotated tool, stdio transport, running under Bun/Node.
  *
  * Mirrors [[com.tjclp.fastmcp.examples.HelloWorld]] on the JVM. Same `McpServer("name", version)`
  * factory, same annotation path, different backend.
  *
  * Run with:
  * {{{
  *   ./mill fast-mcp-scala.js.fastLinkJS
  *   bun run out/fast-mcp-scala/js/fastLinkJS.dest/main.js
  * }}}
  * …or drive it under the MCP Inspector once bundled.
  */
object HelloWorldJs extends ZIOAppDefault:

  @Tool(name = Some("add"), description = Some("Add two numbers"), readOnlyHint = Some(true))
  def add(@Param("First operand") a: Int, @Param("Second operand") b: Int): Int =
    a + b

  override def run: ZIO[Any, Throwable, Unit] =
    for
      server <- ZIO.succeed(McpServer("HelloWorldJs", "0.1.0"))
      _ <- ZIO.attempt(server.scanAnnotations[HelloWorldJs.type])
      _ <- server.runStdio()
    yield ()
