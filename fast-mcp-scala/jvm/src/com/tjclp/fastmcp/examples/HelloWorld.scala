package com.tjclp.fastmcp
package examples

import com.tjclp.fastmcp.*

/** The smallest fast-mcp-scala server — one annotated tool on stdio. No `override def run`, no
  * `import zio.*`, no ZIO ceremony; the `McpServerApp[Stdio, Self.type]` trait handles everything.
  *
  * Run:
  * {{{
  *   ./mill fast-mcp-scala.jvm.runMain com.tjclp.fastmcp.examples.HelloWorld
  * }}}
  */
object HelloWorld extends McpServerApp[Stdio, HelloWorld.type]:

  @Tool(name = Some("add"), description = Some("Add two numbers"), readOnlyHint = Some(true))
  def add(@Param("First operand") a: Int, @Param("Second operand") b: Int): Int = a + b
