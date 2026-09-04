package com.tjclp.fastmcp
package macros

import org.scalatest.funsuite.AnyFunSuite
import zio.*

import com.tjclp.fastmcp.JsonTestSupport.*
import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.RegistrationMacro.scanAnnotations
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.transport.JvmTransportBackend.given

// ---------------------------------------------------------------------------------------------
// Fixtures are TOP-LEVEL so OverloadNegativeTest can reference them from a typeCheckErrors
// snippet. In every fixture the UN-annotated overload is declared FIRST: at HEAD (F4 / TJC-2298)
// `MacroUtils.getMethodRefExpr` re-resolved the method by name and bound the registration to that
// first declaration instead of the annotated one.
// ---------------------------------------------------------------------------------------------

object OverloadedTools:

  // (a) same arity, different parameter type; same (pure) effect shape
  def echo(a: Int): String = s"int:$a"

  @Tool(name = Some("echo"), description = Some("Echo a string"))
  def echo(@Param("Text to echo") a: String): String = s"string:$a"

  // (b) raw helper with an extra `unsafe` parameter declared before the validated wrapper
  def raw(a: String, unsafe: Boolean): String = s"raw:$a:$unsafe"

  @Tool(name = Some("safe"))
  def raw(@Param("Text") a: String): String = s"safe:$a"

  // (c) effect shapes differ: HEAD cast the pure Int result to ZIO and failed at call time
  def eff(a: String): ZIO[Any, Throwable, String] = ZIO.succeed(s"zio:$a")

  @Tool(name = Some("eff"))
  def eff(@Param("Number") a: Int): Int = a * 2

  // (d) two ANNOTATED overloads with distinct explicit names both register
  @Tool(name = Some("sum-ints"))
  def sum(@Param("a") a: Int, @Param("b") b: Int): Int = a + b

  @Tool(name = Some("sum-strings"))
  def sum(@Param("a") a: String, @Param("b") b: String): String = a + b

object DescriptionOnlyTools:

  @Tool(description = Some("Adds two numbers"))
  def add(@Param("a") a: Int, @Param("b") b: Int): Int = a + b

object OverloadedResources:

  def greeting(x: Int): String = s"greeting:$x"

  @Resource("static://greeting")
  def greeting(): String = "hello"

  def profile(userId: Int): String = s"int-user:$userId"

  @Resource("users://{userId}")
  def profile(@Param("The user id") userId: String): String = s"user:$userId"

object OverloadedPrompts:

  def ask(topic: Int): String = s"int-topic:$topic"

  @Prompt(name = Some("ask"))
  def ask(@Param("Topic") topic: String): String = s"Tell me about $topic"

/** Positive coverage for F4 (TJC-2298): the registered handler, its schema and its `@Param`
  * metadata must all come from the ANNOTATED declaration even when a same-named overload is
  * declared before it.
  */
class OverloadBindingTest extends AnyFunSuite:

  private def run[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }

  private lazy val toolServer =
    val s = new McpServer[Any]("OverloadServer", "0.1.0")
    s.scanAnnotations[OverloadedTools.type]
    s

  private def schemaOf(server: McpServer[Any], tool: String) =
    parse(server.toolManager.getToolDefinition(tool).get.inputSchema.toJsonString).toOption.get

  test("(a) @Tool binds to the annotated String overload, not the earlier Int one") {
    val schema = schemaOf(toolServer, "echo")
    val a = schema.hcursor.downField("properties").downField("a")
    assert(a.downField("type").as[String] == Right("string"))
    assert(a.downField("description").as[String] == Right("Text to echo"))
    assert(schema.hcursor.downField("required").as[List[String]] == Right(List("a")))
    assert(toolServer.toolManager.getToolDefinition("echo").get.description == Some("Echo a string"))

    val result = run(toolServer.toolManager.callTool("echo", Map("a" -> "hi"), None))
    assert(result == "string:hi")
  }

  test("(b) the un-annotated raw helper's extra parameter is not part of the registered signature") {
    val schema = schemaOf(toolServer, "safe")
    assert(schema.hcursor.downField("properties").keys.map(_.toSet) == Some(Set("a")))
    val result = run(toolServer.toolManager.callTool("safe", Map("a" -> "x"), None))
    assert(result == "safe:x")
  }

  test("(c) differing effect shapes: the annotated pure overload runs without a cast failure") {
    val schema = schemaOf(toolServer, "eff")
    assert(
      schema.hcursor.downField("properties").downField("a").downField("type").as[String] ==
        Right("integer")
    )
    val result = run(toolServer.toolManager.callTool("eff", Map("a" -> 21), None))
    assert(result == 42)
  }

  test("(d) two annotated overloads with distinct explicit names both register and dispatch") {
    val ints = schemaOf(toolServer, "sum-ints")
    val strings = schemaOf(toolServer, "sum-strings")
    assert(
      ints.hcursor.downField("properties").downField("a").downField("type").as[String] ==
        Right("integer")
    )
    assert(
      strings.hcursor.downField("properties").downField("b").downField("type").as[String] ==
        Right("string")
    )
    assert(run(toolServer.toolManager.callTool("sum-ints", Map("a" -> 2, "b" -> 3), None)) == 5)
    assert(
      run(toolServer.toolManager.callTool("sum-strings", Map("a" -> "x", "b" -> "y"), None)) ==
        "xy"
    )
    assert(toolServer.toolManager.listDefinitions().map(_.name).toSet ==
      Set("echo", "safe", "eff", "sum-ints", "sum-strings"))
  }

  test("description-only @Tool registers under the method name, never under the description") {
    val server = new McpServer[Any]("DescriptionOnlyServer", "0.1.0")
    server.scanAnnotations[DescriptionOnlyTools.type]

    val add = server.toolManager.getToolDefinition("add")
    assert(add.isDefined)
    assert(add.get.description == Some("Adds two numbers"))
    assert(server.toolManager.getToolDefinition("Adds two numbers").isEmpty)
    assert(run(server.toolManager.callTool("add", Map("a" -> 1, "b" -> 2), None)) == 3)
  }

  test("@Resource static + template bind to the annotated overloads") {
    val server = new McpServer[Any]("OverloadResourceServer", "0.1.0")
    server.scanAnnotations[OverloadedResources.type]

    assert(run(server.resourceManager.readResource("static://greeting", None)) == "hello")
    assert(run(server.resourceManager.readResource("users://abc", None)) == "user:abc")

    val templateArgs = server.resourceManager
      .listTemplateResources()
      .find(_.uri == "users://{userId}")
      .flatMap(_.arguments)
    assert(templateArgs == Some(List(ResourceArgument("userId", Some("The user id"), true))))
  }

  test("@Prompt binds to the annotated String overload") {
    val server = new McpServer[Any]("OverloadPromptServer", "0.1.0")
    server.scanAnnotations[OverloadedPrompts.type]

    val messages = run(server.promptManager.getPrompt("ask", Map("topic" -> "scala"), None))
    assert(messages.head.content.asInstanceOf[TextContent].text.contains("scala"))
    assert(
      server.promptManager.getPromptDefinition("ask").get.arguments ==
        Some(List(PromptArgument("topic", Some("Topic"), true)))
    )
  }
