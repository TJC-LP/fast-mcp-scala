package com.tjclp.fastmcp.surface

import org.scalatest.funsuite.AnyFunSuite
import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.{given, *}
import com.tjclp.fastmcp.core.StructuredToolResult

class RootImportSurfaceJsTest extends AnyFunSuite:

  object ExampleTools:
    @Tool(name = Some("hello"))
    def hello(@Param("Person to greet") name: String): String =
      s"Hello, $name!"

    @Prompt(name = Some("hello_prompt"))
    def helloPrompt(@Param("Person to greet") name: String): String =
      s"Prompt for $name"

    @Resource(uri = "static://hello", description = Some("Greeting resource"))
    def helloResource(): String =
      "hello"

  case class HelloArgs(
      @Param(description = "Person to greet")
      name: String
  )
  case class HelloResult(message: String)
  enum Mood:
    case happy, sad
  case class MoodArgs(mood: Mood)

  given JsonEncoder[HelloResult] = DeriveJsonEncoder.gen[HelloResult]

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }

  test("root import exposes the public JS annotation and derived-schema surface") {
    val server = McpServer("RootImportJsServer")
    val _ = server.scanAnnotations[ExampleTools.type]

    val toolDef = server.toolManager.getToolDefinition("hello")
    assert(toolDef.isDefined)
    assert(toolDef.get.inputSchema.toJsonString.contains("name"))

    val schema = ToolInputSchema.derived[HelloArgs]
    assert(schema.toJsonString.contains("name"))
    assert(schema.toJsonString.contains("Person to greet"))

    val typedTool = McpTool[HelloArgs, HelloResult](
      name = "typed-hello",
      description = Some("Typed greeting")
    ) { args =>
      HelloResult(s"Hello, ${args.name}!")
    }
    assert(typedTool.definition.inputSchema.toJsonString.contains("name"))

    val toolResult = runUnsafe(server.toolManager.callTool("hello", Map("name" -> "Ada"), None))
    assert(toolResult == "Hello, Ada!")

    val promptResult =
      runUnsafe(server.promptManager.getPrompt("hello_prompt", Map("name" -> "Ada"), None))
    assert(promptResult.headOption.exists(_.content.asInstanceOf[TextContent].text.contains("Ada")))

    val resourceResult = runUnsafe(server.resourceManager.readResource("static://hello", None))
    assert(resourceResult == "hello")
  }

  test("typed contracts derive singleton enum schemas and decoders on Scala.js (#78)") {
    val server = McpServer("RootImportJsEnumServer")
    val tool = McpTool[MoodArgs, String](name = "describe-mood") { args =>
      s"mood:${args.mood}"
    }

    assert(tool.definition.inputSchema.toJsonString.contains("\"enum\":[\"happy\",\"sad\"]"))
    runUnsafe(server.tool(tool))
    val result = runUnsafe(
      server.toolManager.callTool("describe-mood", Map("mood" -> "happy"), None)
    )
    assert(result == StructuredToolResult(List(TextContent("mood:happy")), None))
  }

  test("root import exposes the settings sub-records and the resource-contents ADT (TJC-2336)") {
    val settings = McpServerSettings(
      tasks = TaskSettings(enabled = true, ownerKey = TaskOwnerKey.Transport),
      limits = LimitSettings(maxFrameChars = 8 * 1024 * 1024)
    )
    assert(settings.tasks.enabled && settings.limits.maxFrameChars == 8 * 1024 * 1024)
    val policy: TaskSupport = TaskSupport.Optional
    assert(policy == TaskSupport.Optional)
    val contents: List[ResourceContents] = List(
      TextResourceContents("file:///a.txt", "a"),
      BlobResourceContents("file:///a.bin", "AAAA")
    )
    assert(contents.map(_.uri) == List("file:///a.txt", "file:///a.bin"))
    assert(EmbeddedResource(contents.head).resource.uri == "file:///a.txt")
  }

  test("root import exposes the McpContext request shapes and completion types (TJC-2336)") {
    val ctx = McpContext.empty
    val info: Option[Implementation] = ctx.getClientInfo
    val caps: Option[ClientCapabilities] = ctx.getClientCapabilities
    assert(info.isEmpty && caps.isEmpty)

    val ask = CreateMessageRequestParams(
      messages = List(SamplingMessage(Role.User, TextContent("hi"))),
      maxTokens = 8,
      modelPreferences = Some(ModelPreferences(hints = Some(List(ModelHint(Some("claude")))))),
      toolChoice = Some(ToolChoice(Some("none")))
    )
    val form = ElicitRequestParams("Proceed?", Json.Obj("type" -> Json.Str("object")))
    val link = ElicitRequestUrlParams("Sign in", "https://example.com/login")
    // No session and no declared client capabilities: every server→client request fails closed.
    val sampled: Either[?, CreateMessageResult] = runUnsafe(ctx.createMessage(ask).either)
    val elicited: Either[?, ElicitResult] = runUnsafe(ctx.elicit(form).either)
    val opened: Either[?, ElicitResult] = runUnsafe(ctx.elicitUrl(link).either)
    val roots: Either[?, ListRootsResult] = runUnsafe(ctx.listRoots().either)
    assert(sampled.isLeft && elicited.isLeft && opened.isLeft && roots.isLeft)
    assert(Root("file:///workspace").uri == "file:///workspace")
    // Notifications without a session are no-ops; their argument types come from the root import.
    runUnsafe(ctx.sendLogMessage(LoggingLevel.Info, Json.Str("hello")))
    runUnsafe(ctx.sendProgress(ProgressToken.NumberToken(1L), 0.5, Some(1.0)))

    val server = McpServer("RootImportCompletionServer")
    runUnsafe(server.completion { (params, _) =>
      val prefix = params.argument.value
      val pool = params.ref match
        case PromptReference(name, _) => List(s"$name-a", s"$name-b")
        case ResourceTemplateReference(uri) => List(uri)
      ZIO.succeed(Completion(pool.filter(_.startsWith(prefix)), hasMore = Some(false)))
    })
    val request = CompleteRequestParams(
      ref = PromptReference("greet"),
      argument = CompletionArgument("name", "g"),
      context = Some(CompletionContext(Some(Map("locale" -> "en"))))
    )
    val reference: CompletionReference = request.ref
    assert(reference == PromptReference("greet"))
  }
