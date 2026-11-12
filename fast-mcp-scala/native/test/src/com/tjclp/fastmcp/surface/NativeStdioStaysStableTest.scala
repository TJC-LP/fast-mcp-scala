package com.tjclp.fastmcp.surface

import org.scalatest.funsuite.AnyFunSuite

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.server.transport.TransportBackend

/** HTTP on Scala Native is `@experimental`; stdio is not. This file is compiled WITHOUT
  * `-experimental` and without any `@experimental` annotation, on the same root import a user
  * writes, so it only compiles if a stdio program never touches the experimental HTTP given.
  */
class NativeStdioStaysStableTest extends AnyFunSuite:

  object StdioApp extends McpServerApp[Stdio, StdioApp.type]:
    @Tool(name = Some("add"), description = Some("Add two numbers"))
    def add(@Param("First") a: Int, @Param("Second") b: Int): Int = a + b

  test("a stdio app and the stdio backend resolve from the root import in stable code") {
    assert(StdioApp.name == "StdioApp")
    assert(Option(summon[TransportBackend]).isDefined)
  }
