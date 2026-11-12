package com.tjclp.fastmcp
package macros

import org.scalatest.funsuite.AnyFunSuite
import zio.*
import zio.json.ast.Json

import com.tjclp.fastmcp.JsonTestSupport.*
import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.examples.AnnotatedServer
import com.tjclp.fastmcp.macros.RegistrationMacro.scanAnnotations
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.transport.JvmTransportBackend.given

/** Scala default arguments are applied when a `tools/call` / `prompts/get` omits the parameter, and
  * `@Param` on an `Option[T]` parameter no longer re-requires it (TJC-2334; D1.14–D1.17, D1.29).
  *
  * Before the fix the generated `Map[String, Any] => R` handler threw `Key not found in map: <param>`
  * for every omitted parameter — including the ones the advertised schema itself marked optional —
  * and a description-only `@Param` flipped an `Option` parameter back into `required`.
  */
class DefaultArgumentsTest extends AnyFunSuite:

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }

  private def failureOf[A](effect: ZIO[Any, Throwable, A]): Throwable =
    runUnsafe(effect.either) match
      case Left(t) => t
      case Right(v) => fail(s"expected a failure, got $v")

  private def requiredOf(server: McpServer[Any], tool: String): List[String] =
    val toolDef = server.toolManager.getToolDefinition(tool)
    assert(toolDef.isDefined, s"tool '$tool' should be registered")
    val schemaJson = parse(toolDef.get.inputSchema.toJsonString).getOrElse(Json.Null)
    schemaJson.hcursor.downField("required").as[List[String]].getOrElse(Nil)

  private def promptArg(server: McpServer[Any], prompt: String, arg: String): PromptArgument =
    val definition = server.promptManager.getPromptDefinition(prompt)
    assert(definition.isDefined, s"prompt '$prompt' should be registered")
    definition.get.arguments.getOrElse(Nil).find(_.name == arg) match
      case Some(a) => a
      case None => fail(s"prompt '$prompt' should advertise argument '$arg'")

  private def promptText(messages: List[Message]): String =
    messages.head.content match
      case TextContent(text, _, _) => text
      case other => fail(s"expected TextContent, got $other")

  // ---- flagship example (D1.16, D1.17) ----

  test("AnnotatedServer.calculator applies the `operation` default when the argument is omitted") {
    val server = McpServer("CalculatorDefault")
    val _ = server.scanAnnotations[AnnotatedServer.type]

    val result = runUnsafe(
      server.toolManager.callTool("calculator", Map("a" -> 1.0, "b" -> 2.0), None)
    )
    val text = result.toString
    assert(text.contains("\"add\""), s"expected the default operation 'add', got: $text")
    assert(text.contains("3.0"), s"expected 1.0 + 2.0 = 3.0, got: $text")
  }

  test("AnnotatedServer.greeting_prompt applies the `title` default when the argument is omitted") {
    val server = McpServer("GreetingDefault")
    val _ = server.scanAnnotations[AnnotatedServer.type]

    val messages = runUnsafe(
      server.promptManager.getPrompt("greeting_prompt", Map("name" -> "Ada"), None)
    )
    assert(promptText(messages) == "Generate a warm greeting for Ada.")
  }

  // ---- tools (D1.15) ----

  test("an omitted `required = false` parameter with a default takes the Scala default") {
    val server = McpServer("ToolDefaults")
    val _ = server.scanAnnotations[DefaultArgumentsTest.Fixture.type]

    assert(!requiredOf(server, "with_default").contains("s"))
    assert(runUnsafe(server.toolManager.callTool("with_default", Map("a" -> 1), None)) == "1:x")
    assert(
      runUnsafe(server.toolManager.callTool("with_default", Map("a" -> 1, "s" -> "y"), None)) ==
        "1:y"
    )
  }

  test("an omitted parameter without a default fails with a message naming the argument") {
    val server = McpServer("ToolMissing")
    val _ = server.scanAnnotations[DefaultArgumentsTest.Fixture.type]

    val err = failureOf(server.toolManager.callTool("with_default", Map("s" -> "y"), None))
    val message = Option(err.getMessage).getOrElse("")
    assert(
      message.contains("Missing required argument 'a'"),
      s"expected the failure to name the missing argument, got: $message"
    )
  }

  test("an omitted Option parameter with a non-None default takes the Scala default") {
    val server = McpServer("OptionDefault")
    val _ = server.scanAnnotations[DefaultArgumentsTest.Fixture.type]

    assert(runUnsafe(server.toolManager.callTool("opt_default", Map.empty, None)) == "7")
    assert(runUnsafe(server.toolManager.callTool("opt_default", Map("x" -> 3), None)) == "3")
  }

  // ---- Option semantics (D1.14) ----

  test("a description-only @Param on an Option parameter does not re-require it") {
    val server = McpServer("OptionParam")
    val _ = server.scanAnnotations[DefaultArgumentsTest.Fixture.type]

    val required = requiredOf(server, "opt_param")
    assert(!required.contains("x"), s"Option parameter 'x' must not be required, got: $required")
    assert(required.contains("label"), s"non-Option parameter 'label' stays required: $required")
    assert(runUnsafe(server.toolManager.callTool("opt_param", Map("label" -> "l"), None)) == "l:none")
  }

  test("an explicit @Param(required = true) re-requires an Option parameter") {
    val server = McpServer("OptionRequired")
    val _ = server.scanAnnotations[DefaultArgumentsTest.Fixture.type]

    val required = requiredOf(server, "opt_required")
    assert(required.contains("x"), s"explicit required = true must win, got: $required")
  }

  // ---- prompts (D1.17, D1.29) ----

  test("an omitted `required = false` prompt argument with a default takes the Scala default") {
    val server = McpServer("PromptDefaults")
    val _ = server.scanAnnotations[DefaultArgumentsTest.Fixture.type]

    assert(!promptArg(server, "default_prompt", "greeting").required)
    val messages = runUnsafe(
      server.promptManager.getPrompt("default_prompt", Map("name" -> "Ada"), None)
    )
    assert(promptText(messages) == "Hello, Ada")
  }

  test("an Option prompt argument is optional with and without a description-only @Param") {
    val server = McpServer("PromptOption")
    val _ = server.scanAnnotations[DefaultArgumentsTest.Fixture.type]

    assert(!promptArg(server, "opt_prompt", "who").required)
    assert(!promptArg(server, "bare_opt_prompt", "who").required)
    assert(promptArg(server, "opt_prompt", "salutation").required)

    val messages = runUnsafe(
      server.promptManager.getPrompt("opt_prompt", Map("salutation" -> "hi"), None)
    )
    assert(promptText(messages) == "hi anon")
  }

object DefaultArgumentsTest:

  object Fixture:

    @Tool(name = Some("with_default"))
    def withDefault(
        @Param("First") a: Int,
        @Param("Suffix", required = false) s: String = "x"
    ): String = s"$a:$s"

    @Tool(name = Some("opt_param"))
    def optParam(
        @Param("Label") label: String,
        @Param("Optional count") x: Option[Int]
    ): String = s"$label:${x.fold("none")(_.toString)}"

    @Tool(name = Some("opt_required"))
    def optRequired(@Param("Count", required = true) x: Option[Int]): String =
      x.fold("none")(_.toString)

    @Tool(name = Some("opt_default"))
    def optDefault(@Param("Count", required = false) x: Option[Int] = Some(7)): String =
      x.fold("none")(_.toString)

    @Prompt(name = Some("default_prompt"))
    def defaultPrompt(
        @Param("Name") name: String,
        @Param("Greeting", required = false) greeting: String = "Hello"
    ): String = s"$greeting, $name"

    @Prompt(name = Some("opt_prompt"))
    def optPrompt(
        @Param("Salutation") salutation: String,
        @Param("Who") who: Option[String]
    ): String = s"$salutation ${who.getOrElse("anon")}"

    @Prompt(name = Some("bare_opt_prompt"))
    def bareOptPrompt(who: Option[String]): String = s"hi ${who.getOrElse("anon")}"
