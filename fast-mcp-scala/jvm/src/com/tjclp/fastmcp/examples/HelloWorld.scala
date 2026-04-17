package com.tjclp.fastmcp
package examples

import zio.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.RegistrationMacro.*
import com.tjclp.fastmcp.server.*

/** The smallest FastMCP-Scala server — one tool, stdio transport.
  *
  * Run with `scala-cli`:
  * {{{
  *   scala-cli fast-mcp-scala/src/com/tjclp/fastmcp/examples/HelloWorld.scala \
  *     --main-class com.tjclp.fastmcp.examples.HelloWorld
  * }}}
  *
  * Or via Mill: `./mill fast-mcp-scala.jvm.runMain com.tjclp.fastmcp.examples.HelloWorld`.
  */
object HelloWorld extends ZIOAppDefault:

  @Tool(name = Some("add"), description = Some("Add two numbers"), readOnlyHint = Some(true))
  def add(@Param("First operand") a: Int, @Param("Second operand") b: Int): Int = a + b

  override def run: ZIO[Any, Throwable, Unit] =
    for
      server <- ZIO.succeed(FastMcpServer("HelloWorld", "0.1.0"))
      _ <- ZIO.attempt(server.scanAnnotations[HelloWorld.type])
      _ <- server.runStdio()
    yield ()
