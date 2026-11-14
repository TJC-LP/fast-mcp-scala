package com.tjclp.fastmcp
package macros

import scala.compiletime.testing.{typeCheckErrors, Error}

import org.scalatest.funsuite.AnyFunSuite
import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.MacroDxHarness.{assertSomeMessageContains, messages, runUnsafe}
import com.tjclp.fastmcp.macros.RegistrationMacro.scanAnnotations
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.manager.ResourceRegistrationError
import com.tjclp.fastmcp.server.transport.JvmTransportBackend.given

object MimeResources:

  @Resource("mime://json/{id}", mimeType = Some("application/json"))
  def json(id: String): String = s"""{"id":"$id"}"""

  @Resource("mime://none/{id}")
  def none(id: String): String = id

  @Resource("mime://bytes/{id}")
  def bytes(id: String): Array[Byte] = id.getBytes("UTF-8")

  @Resource("mime://png/{id}", mimeType = Some("image/png"))
  def png(id: String): Array[Byte] = id.getBytes("UTF-8")

  @Resource("mime://static-none")
  def staticNone(): String = "plain"

  @Resource("mime://static-md", mimeType = Some("text/markdown"))
  def staticMd(): String = "# md"

case class MimeArgs(id: String)

object AdjacentPlaceholders:
  @Resource("x://{a}{b}") def r(a: String, b: String): String = a + b

object RepeatedPlaceholder:
  @Resource("x://{a}/{a}") def r(a: String): String = a

object UnclosedPlaceholder:
  @Resource("x://{a}/{b") def r(a: String): String = a

object SlashInPlaceholder:
  @Resource("x://{a}/{b/c}") def r(a: String): String = a

object SeparatedPlaceholders:
  @Resource("ok://{name}.{ext}") def r(name: String, ext: String): String = s"$name.$ext"

/** D1.23 / D1.24 / D1.27 (GH #116, TJC-2362): the mime type of a templated read, what an omitted
  * `@Resource(mimeType)` means, and invalid URI templates.
  */
class ResourceDxTest extends AnyFunSuite:

  private lazy val h = MacroDxHarness("resource-dx") { server =>
    val _ = server.scanAnnotations[MimeResources.type]
    runUnsafe(
      server.resource(
        McpTemplateResource[MimeArgs](
          uriPattern = "typed://default/{id}",
          arguments = List(ResourceArgument("id", None))
        )(_.id)
      ) *> server.resource(
        McpTemplateResource[MimeArgs](
          uriPattern = "typed://csv/{id}",
          mimeType = Some("text/csv"),
          arguments = List(ResourceArgument("id", None))
        )(_.id)
      ) *> server.resource(
        McpTemplateResource[MimeArgs](
          uriPattern = "typed://bytes/{id}",
          arguments = List(ResourceArgument("id", None))
        )(args => args.id.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      ) *> server.resource(
        McpTemplateResource[MimeArgs](
          uriPattern = "typed://none/{id}",
          mimeType = None,
          arguments = List(ResourceArgument("id", None))
        )(_.id)
      )
    )
  }

  private def json(text: String): Json =
    text.fromJson[Json].fold(error => fail(s"bad JSON <$text>: $error"), identity)

  /** The single `contents` entry of `resources/read`. */
  private def read(uri: String): Json.Obj =
    val raw = h.frame(s"""{"jsonrpc":"2.0","id":9,"method":"resources/read","params":{"uri":"$uri"}}""")
    json(raw).asObject
      .flatMap(_.get("result"))
      .flatMap(_.asObject)
      .flatMap(_.get("contents"))
      .flatMap(_.asArray)
      .flatMap(_.headOption)
      .flatMap(_.asObject)
      .getOrElse(fail(s"resources/read $uri returned no contents: $raw"))

  private def mimeOf(uri: String): Option[String] = read(uri).get("mimeType").flatMap(_.asString)

  // ---- D1.23 ----

  test("resources/read on a template returns the mimeType the template declares") {
    val entry = read("mime://json/7")
    assert(entry.get("text").flatMap(_.asString).contains("""{"id":"7"}"""))
    assert(entry.get("mimeType").flatMap(_.asString).contains("application/json"))
    assert(mimeOf("mime://png/7").contains("image/png"))
    assert(read("mime://png/7").contains("blob"))
  }

  test("typed templates: the declared mimeType, or the contract's text/plain default") {
    assert(mimeOf("typed://csv/1").contains("text/csv"))
    assert(mimeOf("typed://default/1").contains("text/plain"))
    assert(mimeOf("typed://none/1").isEmpty)
    // The default is `text/plain`, and bytes are not text: a binary body keeps octet-stream.
    assert(mimeOf("typed://bytes/1").contains("application/octet-stream"))
    assert(read("typed://bytes/1").contains("blob"))
  }

  // ---- D1.24 ----

  test("@Resource without mimeType: none is sent for text, application/octet-stream for bytes") {
    assert(mimeOf("mime://none/7").isEmpty)
    assert(mimeOf("mime://bytes/7").contains("application/octet-stream"))
    assert(mimeOf("mime://static-none").isEmpty)
    assert(mimeOf("mime://static-md").contains("text/markdown"))

    val raw = h.frame("""{"jsonrpc":"2.0","id":10,"method":"resources/list","params":{}}""")
    val listed = json(raw).asObject
      .flatMap(_.get("result"))
      .flatMap(_.asObject)
      .flatMap(_.get("resources"))
      .flatMap(_.asArray)
      .map(_.toList.flatMap(_.asObject))
      .getOrElse(fail(s"no resources: $raw"))
    def listedMime(uri: String): Option[Option[String]] =
      listed
        .find(_.get("uri").flatMap(_.asString).contains(uri))
        .map(_.get("mimeType").flatMap(_.asString))
    assert(listedMime("mime://static-none").contains(None))
    assert(listedMime("mime://static-md").contains(Some("text/markdown")))
  }

  // ---- D1.27 ----

  test("adjacent placeholders in a @Resource template are a compile-time error") {
    val errs: List[Error] =
      typeCheckErrors("""McpServer("s").scanAnnotations[AdjacentPlaceholders.type]""")
    assertSomeMessageContains(
      errs,
      "@Resource method 'r' has an invalid URI template",
      "template 'x://{a}{b}' has adjacent placeholders before '{b}'",
      "separate placeholders with literal text"
    )
    assert(errs.sizeIs == 1, messages(errs))
  }

  test("every other template the registration refuses is refused at compile time too") {
    assertSomeMessageContains(
      typeCheckErrors("""McpServer("s").scanAnnotations[RepeatedPlaceholder.type]"""),
      "uses placeholder '{a}' more than once"
    )
    assertSomeMessageContains(
      typeCheckErrors("""McpServer("s").scanAnnotations[UnclosedPlaceholder.type]"""),
      "has an unclosed '{'"
    )
    assertSomeMessageContains(
      typeCheckErrors("""McpServer("s").scanAnnotations[SlashInPlaceholder.type]"""),
      "has a '/' inside placeholder"
    )
  }

  test("control: placeholders separated by literal text compile and match") {
    val errs: List[Error] =
      typeCheckErrors("""McpServer("s").scanAnnotations[SeparatedPlaceholders.type]""")
    assert(errs.isEmpty, messages(errs))
  }

  test("registering an invalid template at run time fails with the reason in the message") {
    val server = McpServer("bad-template")
    val out = new java.io.ByteArrayOutputStream()
    val failure = scala.Console.withOut(out) {
      val original = java.lang.System.out
      java.lang.System.setOut(new java.io.PrintStream(out, true, "UTF-8"))
      try
        runUnsafe(
          server
            .resource(
              McpTemplateResource[MimeArgs](
                uriPattern = "x://{id}{more}",
                arguments = List(ResourceArgument("id", None), ResourceArgument("more", None))
              )(_.id)
            )
            .either
        )
      finally java.lang.System.setOut(original)
    }
    val error = failure.left.toOption.getOrElse(fail("the registration succeeded"))
    assert(error.isInstanceOf[ResourceRegistrationError])
    assert(
      error.getMessage ==
        "Failed to register resource template 'x://{id}{more}': template 'x://{id}{more}' has " +
        "adjacent placeholders before '{more}' — ambiguous; separate placeholders with literal text"
    )
    assert(out.toString("UTF-8").isEmpty, "the failed registration wrote to stdout")
    assert(server.resourceManager.listTemplateResources().isEmpty)
  }
