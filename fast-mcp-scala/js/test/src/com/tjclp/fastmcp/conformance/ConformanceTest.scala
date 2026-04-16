package com.tjclp.fastmcp.conformance

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js.JSConverters.*

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

/** Conformance tests that connect a Scala.js MCP client (via TS SDK facades)
  * to the fast-mcp-scala AnnotatedServer running on JVM via stdio.
  */
class ConformanceTest extends AsyncFlatSpec with Matchers with BeforeAndAfterAll:

  // Use the JS microtask queue EC — ScalaTest's serial EC can't track native JS Promises
  override implicit val executionContext: ExecutionContext = ExecutionContext.global

  private val ServerClass = "com.tjclp.fastmcp.examples.AnnotatedServer"
  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  private var client: McpTestClient = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    // Client is connected synchronously in tests via the first test that needs it
    super.beforeAll()

  override def afterAll(): Unit =
    if client != null then client.close()
    super.afterAll()

  private def ensureClient(): Future[McpTestClient] =
    if client != null then Future.successful(client)
    else
      McpTestClient.connectStdio(ServerClass).map { c =>
        client = c
        c
      }

  // --- Initialize ---

  "initialize" should "connect and receive valid server info" in {
    ensureClient().map { c =>
      c.serverName shouldBe Some("MacroAnnotatedServer")
      c.serverVersion shouldBe Some("0.1.0")
    }
  }

  it should "report tool capabilities" in {
    ensureClient().map { c =>
      c.hasToolCapability shouldBe true
    }
  }

  it should "report resource capabilities" in {
    ensureClient().map { c =>
      c.hasResourceCapability shouldBe true
    }
  }

  it should "report prompt capabilities" in {
    ensureClient().map { c =>
      c.hasPromptCapability shouldBe true
    }
  }

  // --- Tools ---

  "tools/list" should "return registered tools" in {
    ensureClient().flatMap(_.listTools()).map { result =>
      val names = result.tools.map(_.name).toSeq
      names should contain("add")
      names should contain("calculator")
      names should contain("transform")
      names should contain("description")
    }
  }

  it should "include input schemas" in {
    ensureClient().flatMap(_.listTools()).map { result =>
      val addTool = result.tools.find(_.name == "add")
      addTool shouldBe defined
      val schema = addTool.get.inputSchema
      schema.isDefined shouldBe true
      schema.get.`type` shouldBe "object"
      schema.get.properties.isDefined shouldBe true
      schema.get.properties.get.contains("a") shouldBe true
      schema.get.properties.get.contains("b") shouldBe true
    }
  }

  "tools/call" should "return correct sum for 'add'" in {
    ensureClient().flatMap(_.callTool("add", Map("a" -> 5, "b" -> 3))).map { result =>
      result.content.length should be > 0
      val text = result.content.find(_.`type` == "text").flatMap(_.text.toOption)
      text shouldBe defined
      text.get should include("8")
    }
  }

  it should "handle calculator multiply" in {
    ensureClient().flatMap(_.callTool("calculator", Map("a" -> 4.0, "b" -> 7.0, "operation" -> "multiply"))).map {
      result =>
        val text = result.content.find(_.`type` == "text").flatMap(_.text.toOption)
        text shouldBe defined
        text.get should include("28")
    }
  }

  it should "handle transform uppercase" in {
    ensureClient()
      .flatMap(_.callTool("transform", Map("text" -> "hello world", "transformation" -> "uppercase")))
      .map { result =>
        val text = result.content.find(_.`type` == "text").flatMap(_.text.toOption)
        text shouldBe defined
        text.get should include("HELLO WORLD")
      }
  }

  // --- Resources ---

  "resources/list" should "return static resources" in {
    ensureClient().flatMap(_.listResources()).map { result =>
      val uris = result.resources.map(_.uri).toSeq
      uris should contain("static://welcome")
    }
  }

  "resources/read" should "return content for static://welcome" in {
    ensureClient().flatMap(_.readResource("static://welcome")).map { result =>
      result.contents.length should be > 0
      val text = result.contents.headOption.flatMap(_.text.toOption)
      text shouldBe defined
      text.get should include("Welcome to the FastMCP-Scala")
    }
  }

  "resources/templates/list" should "return resource templates" in {
    ensureClient().flatMap(_.listResourceTemplates()).map { result =>
      val templates = result.resourceTemplates.map(_.uriTemplate).toSeq
      templates should contain("users://{userId}/profile")
    }
  }

  // --- Prompts ---

  "prompts/list" should "return registered prompts" in {
    ensureClient().flatMap(_.listPrompts()).map { result =>
      val names = result.prompts.map(_.name).toSeq
      names should contain("hello_prompt")
      names should contain("greeting_prompt")
    }
  }

  it should "include argument metadata" in {
    ensureClient().flatMap(_.listPrompts()).map { result =>
      val greeting = result.prompts.find(_.name == "greeting_prompt")
      greeting shouldBe defined
      val args = greeting.get.arguments.toOption
      args shouldBe defined
      val nameArg = args.get.find(_.name == "name")
      nameArg shouldBe defined
      nameArg.get.required.toOption shouldBe Some(true)
    }
  }

  "prompts/get" should "return messages for hello_prompt" in {
    ensureClient().flatMap(_.getPrompt("hello_prompt")).map { result =>
      result.messages.length should be > 0
      result.messages.head.role shouldBe "user"
      result.messages.head.content.text.toOption.get.toLowerCase should include("hello")
    }
  }

  it should "return messages with arguments for greeting_prompt" in {
    ensureClient().flatMap(_.getPrompt("greeting_prompt", Map("name" -> "Alice", "title" -> "Dr."))).map { result =>
      result.messages.length should be > 0
      val text = result.messages.head.content.text.toOption.get
      text should include("Dr.")
      text should include("Alice")
    }
  }

  // --- Error handling ---

  "tools/call" should "return error for divide by zero" in {
    ensureClient().flatMap(_.callTool("calculator", Map("a" -> 10.0, "b" -> 0.0, "operation" -> "divide"))).map {
      result =>
        result.isError.toOption shouldBe Some(true)
    }
  }
