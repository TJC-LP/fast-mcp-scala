package com.tjclp.fastmcp
package examples

import com.tjclp.fastmcp.*

/** The smallest Scala.js fast-mcp-scala server — one annotated tool, stdio, running under Bun.
  * Mirror of [[com.tjclp.fastmcp.examples.HelloWorld]] on the JVM. Same declarative trait, same
  * typed contract machinery, different runtime backend.
  *
  * Bundle and run:
  * {{{
  *   ./mill fast-mcp-scala.js.fastLinkJS
  *   bun run out/fast-mcp-scala/js/fastLinkJS.dest/main.js
  * }}}
  */
object HelloWorldJs extends McpServerApp[Stdio, HelloWorldJs.type]:

  @Tool(name = Some("add"), description = Some("Add two numbers"), readOnlyHint = Some(true))
  def add(@Param("First operand") a: Int, @Param("Second operand") b: Int): Int = a + b
