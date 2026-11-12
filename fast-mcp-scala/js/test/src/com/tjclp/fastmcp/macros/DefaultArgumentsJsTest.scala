package com.tjclp.fastmcp.macros

import org.scalatest.funsuite.AnyFunSuite
import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.{*, given}

/** Scala.js mirror of the JVM `DefaultArgumentsTest` (TJC-2334; D1.50, D1.51): the same macro
  * expansion must apply Scala default arguments for an omitted `tools/call` / `prompts/get`
  * parameter under Bun, and a description-only `@Param` on an `Option` parameter must not land in
  * the advertised `required` array.
  */
class DefaultArgumentsJsTest extends AnyFunSuite:

  object DefaultsJsTools:

    @Tool(name = Some("with_default"))
    def withDefault(
        @Param("First") a: Int,
        @Param("Suffix", required = false) s: String = "x"
    ): String = s"$a:$s"

    @Tool(name = Some("opt_param"))
    def optParam(
        @Param("Label") label: String,
        @Param("Optional note") note: Option[String]
    ): String = s"$label:${note.getOrElse("none")}"

    @Prompt(name = Some("default_prompt"))
    def defaultPrompt(
        @Param("Name") name: String,
        @Param("Greeting", required = false) greeting: String = "Hello"
    ): String = s"$greeting, $name"

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }

  private def requiredOf(server: McpServer[Any], tool: String): List[String] =
    val toolDef = server.toolManager.getToolDefinition(tool)
    assert(toolDef.isDefined, s"tool '$tool' should be registered")
    toolDef.get.inputSchema.toJsonString
      .fromJson[Json]
      .toOption
      .flatMap(_.asObject)
      .flatMap(_.get("required"))
      .flatMap(_.as[List[String]].toOption)
      .getOrElse(Nil)

  test("an omitted `required = false` parameter with a default takes the Scala default under Scala.js") {
    val server = McpServer("DefaultsJs")
    val _ = server.scanAnnotations[DefaultsJsTools.type]

    assert(!requiredOf(server, "with_default").contains("s"))
    assert(runUnsafe(server.toolManager.callTool("with_default", Map("a" -> 2), None)) == "2:x")
  }

  test("a description-only @Param on an Option parameter is not required under Scala.js") {
    val server = McpServer("OptionJs")
    val _ = server.scanAnnotations[DefaultsJsTools.type]

    val required = requiredOf(server, "opt_param")
    assert(!required.contains("note"), s"Option parameter 'note' must not be required: $required")
    assert(required.contains("label"), s"non-Option parameter 'label' stays required: $required")
    assert(runUnsafe(server.toolManager.callTool("opt_param", Map("label" -> "l"), None)) == "l:none")
  }

  test("an omitted `required = false` prompt argument with a default takes the Scala default under Scala.js") {
    val server = McpServer("PromptDefaultsJs")
    val _ = server.scanAnnotations[DefaultsJsTools.type]

    val messages = runUnsafe(
      server.promptManager.getPrompt("default_prompt", Map("name" -> "Ada"), None)
    )
    val text = messages.head.content match
      case TextContent(t, _, _) => t
      case other => fail(s"expected TextContent, got $other")
    assert(text == "Hello, Ada")
  }
