package com.tjclp.fastmcp
package conformance

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.tapir.generic.auto.*
import zio.*
import zio.json.*

import com.tjclp.fastmcp.conformance.facades as clientFacade
import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.facades.server as tsdk
import com.tjclp.fastmcp.server.JsMcpServer

/** Pure Scala.js conformance test: stands up a JsMcpServer in-process, connects an
  * `@modelcontextprotocol/sdk` Client to it via InMemoryTransport, and drives every MCP operation
  * our runtime supports. No subprocess, no JVM — if this suite passes on Bun, the Scala.js backend
  * is genuinely a working MCP server.
  */
class JsServerConformanceTest extends AsyncFlatSpec with Matchers with BeforeAndAfterAll:

  override implicit val executionContext: ExecutionContext = ExecutionContext.global

  // --- Typed contracts registered on the server ---

  case class AddArgs(
      @Param(description = "left operand")
      a: Int,
      @Param(description = "right operand")
      b: Int
  )
  case class AddResult(sum: Int)
  case class UserArgs(userId: String)
  case class GreetArgs(name: String)

  given JsonEncoder[AddResult] = DeriveJsonEncoder.gen[AddResult]
  given JsonDecoder[UserArgs] = DeriveJsonDecoder.gen[UserArgs]
  given JsonDecoder[GreetArgs] = DeriveJsonDecoder.gen[GreetArgs]

  private val addTool = McpTool[AddArgs, AddResult](
    name = "typed-add",
    description = Some("Add two ints")
  )(args => AddResult(args.a + args.b))

  private val brokenTool = McpTool[AddArgs, AddResult](
    name = "broken",
    description = Some("Always fails")
  )(_ => ZIO.fail(new RuntimeException("kaboom")))

  private val welcomeResource = McpStaticResource(
    uri = "static://welcome",
    description = Some("Welcome")
  )("Welcome to JsMcpServer!")

  private val userResource = McpTemplateResource[UserArgs](
    uriPattern = "users://{userId}/profile",
    description = Some("User profile"),
    mimeType = Some("application/json"),
    arguments = List(ResourceArgument("userId", Some("user id"), required = true))
  )(args => s"""{"userId":"${args.userId}"}""")

  private val greetingPrompt = McpPrompt[GreetArgs](
    name = "greeting",
    description = Some("Greet someone"),
    arguments = List(PromptArgument("name", Some("the name"), required = true))
  )(args => List(Message(Role.User, TextContent(s"Hello ${args.name}!"))))

  // --- Fixtures ---

  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  private var client: clientFacade.Client = scala.compiletime.uninitialized

  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  private var tsServer: tsdk.Server = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    super.beforeAll()

  override def afterAll(): Unit =
    if client != null then
      val _ = client.close()
    if tsServer != null then
      val _ = tsServer.close()
    super.afterAll()

  private def runZio[A](effect: ZIO[Any, Throwable, A]): Future[A] =
    val promise = Promise[A]()
    Unsafe.unsafe { implicit unsafe =>
      val fiber = Runtime.default.unsafe.runToFuture(effect)
      fiber.onComplete {
        case scala.util.Success(a) => val _ = promise.trySuccess(a)
        case scala.util.Failure(e) => val _ = promise.tryFailure(e)
      }
    }
    promise.future

  private def fromJsPromise[A](p: js.Promise[A]): Future[A] =
    val promise = Promise[A]()
    val _ = p.`then`[Unit](
      (value: A) => { val _ = promise.trySuccess(value); () },
      (err: scala.Any) =>
        val t = err match
          case th: Throwable => th
          case other => new RuntimeException(String.valueOf(other))
        val _ = promise.tryFailure(t)
        ()
    )
    promise.future

  private def ensureConnected(): Future[clientFacade.Client] =
    if client != null then Future.successful(client)
    else
      val server = com.tjclp.fastmcp.server.McpServer("JsConformanceServer", "0.1.0")
      val register = for
        _ <- server.tool(addTool)
        _ <- server.tool(brokenTool)
        _ <- server.resource(welcomeResource)
        _ <- server.resource(userResource)
        _ <- server.prompt(greetingPrompt)
      yield ()

      val pair = tsdk.InMemoryTransport.createLinkedPair()
      val clientTransport = pair._1
      val serverTransport = pair._2

      for
        _ <- runZio(register)
        ts <- runZio(server.connect(serverTransport))
        _ = { tsServer = ts }
        c = new clientFacade.Client(
          clientFacade.ClientInfo("conformance-client", "0.1.0"),
          clientFacade.ClientOptions()
        )
        _ <- fromJsPromise(c.connect(clientTransport.asInstanceOf[clientFacade.Transport]))
      yield
        client = c
        c

  // --- Initialize ---

  "initialize" should "populate server info from the JsMcpServer name/version" in {
    ensureConnected().map { c =>
      val info = c.getServerVersion().toOption
      info.map(_.name) shouldBe Some("JsConformanceServer")
      info.map(_.version) shouldBe Some("0.1.0")
    }
  }

  it should "advertise tool/resource/prompt capabilities" in {
    ensureConnected().map { c =>
      val caps = c.getServerCapabilities().toOption
      caps.flatMap(_.tools.toOption).isDefined shouldBe true
      caps.flatMap(_.resources.toOption).isDefined shouldBe true
      caps.flatMap(_.prompts.toOption).isDefined shouldBe true
    }
  }

  // --- Tools ---

  "tools/list" should "return registered typed contracts" in {
    ensureConnected().flatMap(c => fromJsPromise(c.listTools())).map { result =>
      val names = result.tools.map(_.name).toSeq
      names should contain allOf ("typed-add", "broken")
    }
  }

  "tools/call" should "succeed for the typed-add tool" in {
    ensureConnected()
      .flatMap(c =>
        fromJsPromise(c.callTool(clientFacade.CallToolParams("typed-add", js.Dictionary("a" -> 2, "b" -> 3))))
      )
      .map { result =>
        result.isError.toOption shouldBe Some(false)
        val text = result.content.find(_.`type` == "text").flatMap(_.text.toOption)
        text.getOrElse("").contains("5") shouldBe true
      }
  }

  it should "surface handler failures via isError=true" in {
    ensureConnected()
      .flatMap(c =>
        fromJsPromise(c.callTool(clientFacade.CallToolParams("broken", js.Dictionary("a" -> 1, "b" -> 2))))
      )
      .map { result =>
        result.isError.toOption shouldBe Some(true)
        val text = result.content.find(_.`type` == "text").flatMap(_.text.toOption)
        text.exists(_.contains("kaboom")) shouldBe true
      }
  }

  it should "reject schema-mismatched arguments" in {
    ensureConnected()
      .flatMap(c =>
        fromJsPromise(c.callTool(clientFacade.CallToolParams("typed-add", js.Dictionary("a" -> "nope"))))
      )
      .map(_.isError.toOption shouldBe Some(true))
  }

  // --- Resources ---

  "resources/list" should "return the static welcome resource" in {
    ensureConnected().flatMap(c => fromJsPromise(c.listResources())).map { result =>
      val uris = result.resources.map(_.uri).toSeq
      uris should contain("static://welcome")
    }
  }

  "resources/read" should "return the welcome resource body" in {
    ensureConnected()
      .flatMap(c => fromJsPromise(c.readResource(clientFacade.ReadResourceParams("static://welcome"))))
      .map { result =>
        val first = result.contents.headOption.getOrElse(fail("missing resource content"))
        val text = first.text.toOption.getOrElse("")
        text should include("Welcome to JsMcpServer")
        first.mimeType.toOption shouldBe Some("text/plain")
      }
  }

  "resources/templates/list" should "expose the user profile template" in {
    ensureConnected().flatMap(c => fromJsPromise(c.listResourceTemplates())).map { result =>
      val templates = result.resourceTemplates.map(_.uriTemplate).toSeq
      templates should contain("users://{userId}/profile")
    }
  }

  "resources/read" should "preserve template mimeType metadata" in {
    ensureConnected()
      .flatMap(c => fromJsPromise(c.readResource(clientFacade.ReadResourceParams("users://ada/profile"))))
      .map { result =>
        val first = result.contents.headOption.getOrElse(fail("missing template content"))
        first.mimeType.toOption shouldBe Some("application/json")
        first.text.toOption.getOrElse("") should include("ada")
      }
  }

  // --- Prompts ---

  "prompts/list" should "include the greeting prompt" in {
    ensureConnected().flatMap(c => fromJsPromise(c.listPrompts())).map { result =>
      val names = result.prompts.map(_.name).toSeq
      names should contain("greeting")
    }
  }

  "prompts/get" should "render the greeting with the provided name" in {
    ensureConnected()
      .flatMap(c =>
        fromJsPromise(c.getPrompt(clientFacade.GetPromptParams("greeting", js.Dictionary("name" -> "Ada"))))
      )
      .map { result =>
        result.messages.length should be > 0
        val text = result.messages.head.content.text.toOption.getOrElse("")
        text should include("Ada")
      }
  }
