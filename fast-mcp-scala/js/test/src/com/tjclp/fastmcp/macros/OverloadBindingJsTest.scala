package com.tjclp.fastmcp.macros

import org.scalatest.funsuite.AnyFunSuite
import zio.*

import com.tjclp.fastmcp.{*, given}

/** Scala.js mirror of the `@Tool` cases of the JVM `OverloadBindingTest` (F4 / TJC-2298): the same
  * macro expansion must link and dispatch through `RefResolver` under Bun, binding to the ANNOTATED
  * overload even when a same-named method is declared before it (class-nested fixture object, the
  * `Outer.this.Nested` qualifier path).
  */
class OverloadBindingJsTest extends AnyFunSuite:

  object OverloadedJsTools:
    def echo(a: Int): String = s"int:$a"

    @Tool(name = Some("echo"), description = Some("Echo a string"))
    def echo(@Param("Text to echo") a: String): String = s"string:$a"

    def raw(a: String, unsafe: Boolean): String = s"raw:$a:$unsafe"

    @Tool(name = Some("safe"))
    def raw(@Param("Text") a: String): String = s"safe:$a"

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }

  test("@Tool binds to the annotated String overload under Scala.js") {
    val server = McpServer("OverloadJs")
    val _ = server.scanAnnotations[OverloadedJsTools.type]

    val echoDef = server.toolManager.getToolDefinition("echo")
    assert(echoDef.isDefined)
    val schema = echoDef.get.inputSchema.toJsonString
    assert(schema.contains("\"type\":\"string\""), s"expected a string schema for `a`, got $schema")
    assert(!schema.contains("\"type\":\"integer\""), s"Int overload leaked into schema: $schema")
    assert(schema.contains("Text to echo"))

    assert(runUnsafe(server.toolManager.callTool("echo", Map("a" -> "hi"), None)) == "string:hi")
  }

  test("the un-annotated raw helper's extra parameter is not registered under Scala.js") {
    val server = McpServer("OverloadJsRaw")
    val _ = server.scanAnnotations[OverloadedJsTools.type]

    val schema = server.toolManager.getToolDefinition("safe").get.inputSchema.toJsonString
    assert(!schema.contains("unsafe"), s"un-annotated overload's parameter leaked: $schema")
    assert(runUnsafe(server.toolManager.callTool("safe", Map("a" -> "x"), None)) == "safe:x")
  }
