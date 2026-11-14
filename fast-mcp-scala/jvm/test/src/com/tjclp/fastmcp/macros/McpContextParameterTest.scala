package com.tjclp.fastmcp
package macros

import scala.compiletime.testing.{typeCheckErrors, Error}

import org.scalatest.funsuite.AnyFunSuite
import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.MacroDxHarness.{assertSomeMessageContains, messages}
import com.tjclp.fastmcp.macros.RegistrationMacro.scanAnnotations
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.transport.JvmTransportBackend.given

object ContextByTypeTools:

  private def client(context: McpContext): String =
    context.getClientInfo.map(_.name).getOrElse("no-client")

  // D1.32: any name, any position.
  @Tool(name = Some("whoami"))
  def whoami(context: McpContext, @Param("A number") a: Int): String = s"${client(context)}:$a"

  @Tool(name = Some("middle"))
  def middle(a: Int, request: McpContext, b: String = "b"): String = s"${client(request)}:$a:$b"

  // The 1.0.x spelling keeps working.
  @Tool(name = Some("classic"))
  def classic(a: Int, ctx: McpContext): String = s"${client(ctx)}:$a"

  @Tool(name = Some("effect"))
  def effect(a: Int, c: McpContext): ZIO[Any, Throwable, String] = ZIO.succeed(s"${client(c)}:$a")

  // A parameter that is merely NAMED ctx is an ordinary argument.
  @Tool(name = Some("named_ctx"))
  def namedCtx(ctx: String): String = s"arg:$ctx"

  // D1.33: the context on a prompt is injected, not advertised as an argument.
  @Prompt(name = Some("greeting"))
  def greeting(context: McpContext, @Param("Who to greet") name: String): String =
    s"Hello $name from ${client(context)}"

  @Prompt(name = Some("only_context"))
  def onlyContext(ctx: McpContext): String = s"client=${client(ctx)}"

  @Prompt(name = Some("plain"))
  def plain(name: String): String = s"Hi $name"

object ContextOnTemplate:
  @Resource("ctx://{id}") def read(id: String, ctx: McpContext): String = id

object ContextOnStatic:
  @Resource("ctx://static") def read(context: McpContext): String = ""

object TwoContexts:
  @Tool def twice(a: McpContext, b: McpContext): String = ""

/** D1.32 / D1.33 / #119 C2.15 (TJC-2362): the request's `McpContext` is injected into the
  * parameter of that TYPE, whatever its name, on `@Tool` and `@Prompt`; on `@Resource` it is a
  * compile-time error that says so. Before, only a parameter literally named `ctx` on a `@Tool`
  * was recognised: any other name failed with "Cannot derive an MCP JSON Schema for ...McpContext",
  * a prompt advertised `ctx` as a required wire argument, and a resource template complained that
  * the URI parameters did not match.
  */
class McpContextParameterTest extends AnyFunSuite:

  private lazy val h = MacroDxHarness("context-by-type") { server =>
    val _ = server.scanAnnotations[ContextByTypeTools.type]
  }

  private def reply(method: String, params: String): Json =
    val raw = h.frame(s"""{"jsonrpc":"2.0","id":77,"method":"$method","params":$params}""")
    raw.fromJson[Json].fold(error => fail(s"unparseable reply <$raw>: $error"), identity)

  private def result(method: String, params: String): Json =
    reply(method, params).asObject.flatMap(_.get("result")).getOrElse {
      fail(s"$method $params returned no result: ${reply(method, params).toJson}")
    }

  private def promptText(name: String, arguments: String): String =
    val messages = result("prompts/get", s"""{"name":"$name","arguments":$arguments}""").asObject
      .flatMap(_.get("messages"))
      .flatMap(_.asArray)
      .getOrElse(fail("no messages"))
    messages
      .flatMap(_.asObject.flatMap(_.get("content")).flatMap(_.asObject).flatMap(_.get("text")))
      .flatMap(_.asString)
      .mkString

  test("a McpContext parameter of any name is injected and is not part of the tool's schema") {
    assert(h.propertyNames("whoami") == List("a"))
    assert(h.required("whoami") == List("a"))
    assert(h.propertyNames("middle") == List("a", "b"))
    assert(h.required("middle") == List("a"))
    assert(h.propertyNames("classic") == List("a"))
    assert(h.propertyNames("effect") == List("a"))

    // The harness initializes its session as client "macro-dx".
    assert(h.call("whoami", """{"a":1}""").text == "macro-dx:1")
    assert(h.call("middle", """{"a":2}""").text == "macro-dx:2:b")
    assert(h.call("middle", """{"a":2,"b":"z"}""").text == "macro-dx:2:z")
    assert(h.call("classic", """{"a":3}""").text == "macro-dx:3")
    assert(h.call("effect", """{"a":4}""").text == "macro-dx:4")
  }

  test("a client cannot supply the context: an argument under that name is replaced by the real one") {
    val smuggled = h.call("whoami", """{"a":1,"context":{"x":1}}""")
    assert(!smuggled.isError && smuggled.text == "macro-dx:1", s"reply: $smuggled")
  }

  test("a String parameter that is only named ctx stays an ordinary argument") {
    assert(h.propertyNames("named_ctx") == List("ctx"))
    assert(h.call("named_ctx", """{"ctx":"v"}""").text == "arg:v")
  }

  test("a direct call without a request context gets McpContext.empty") {
    val server = McpServer("no-context")
    val _ = server.scanAnnotations[ContextByTypeTools.type]
    val out = MacroDxHarness.runUnsafe(
      server.toolManager.callTool("whoami", Map("a" -> Json.Num(5)), None)
    )
    assert(out == "no-client:5")
  }

  test("@Prompt: the context is injected and is not advertised as a prompt argument") {
    val prompts = result("prompts/list", "{}").asObject
      .flatMap(_.get("prompts"))
      .flatMap(_.asArray)
      .getOrElse(fail("no prompts"))
    def argumentNames(prompt: String): Option[List[String]] =
      prompts
        .find(_.asObject.flatMap(_.get("name")).flatMap(_.asString).contains(prompt))
        .flatMap(_.asObject)
        .flatMap(_.get("arguments"))
        .flatMap(_.asArray)
        .map(_.toList.flatMap(_.asObject.flatMap(_.get("name")).flatMap(_.asString)))

    assert(argumentNames("greeting").contains(List("name")))
    assert(argumentNames("only_context").isEmpty, "a context-only prompt has no arguments")
    assert(argumentNames("plain").contains(List("name")))

    assert(promptText("greeting", """{"name":"Ada"}""") == "Hello Ada from macro-dx")
    assert(promptText("only_context", "{}") == "client=macro-dx")
    assert(promptText("plain", """{"name":"Ada"}""") == "Hi Ada")
  }

  test("@Prompt: a missing real argument is still reported, and it is not the context") {
    val error = reply("prompts/get", """{"name":"greeting","arguments":{}}""").asObject
      .flatMap(_.get("error"))
      .flatMap(_.asObject)
      .flatMap(_.get("message"))
      .flatMap(_.asString)
      .getOrElse(fail("expected an error"))
    assert(error.contains("name") && !error.contains("context"), error)
  }

  test("@Resource with a McpContext parameter is a compile-time error that says why") {
    val template: List[Error] =
      typeCheckErrors("""McpServer("s").scanAnnotations[ContextOnTemplate.type]""")
    assertSomeMessageContains(
      template,
      "@Resource method 'read' declares an McpContext parameter ('ctx')",
      "@Tool and @Prompt methods only"
    )
    val static: List[Error] =
      typeCheckErrors("""McpServer("s").scanAnnotations[ContextOnStatic.type]""")
    assertSomeMessageContains(static, "declares an McpContext parameter ('context')")
  }

  test("two McpContext parameters are a compile-time error") {
    val errs: List[Error] = typeCheckErrors("""McpServer("s").scanAnnotations[TwoContexts.type]""")
    assertSomeMessageContains(errs, "declares 2 McpContext parameters (a, b)")
    assert(errs.sizeIs == 1, messages(errs))
  }
