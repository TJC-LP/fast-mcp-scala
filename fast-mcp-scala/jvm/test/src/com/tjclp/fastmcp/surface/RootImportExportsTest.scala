package com.tjclp.fastmcp.surface

import scala.compiletime.testing.typeCheckErrors

import org.scalatest.funsuite.AnyFunSuite

import com.tjclp.fastmcp.{given, *}

// Top-level so the typeCheckErrors snippets can name it (a block-local case class would be typed
// inside the quoted snippet, where schema derivation has no stable owner to summon from).
case class ExpensiveArgs(@Param("input") x: String)

/** The root import (`import com.tjclp.fastmcp.{*, given}`) must be enough for every documented
  * fence (TJC-2336): the settings sub-records (`TaskSettings`, `LimitSettings`), the per-tool task
  * policy (`TaskSupport`, `TaskOwnerKey`) and the `resources/read` payload ADT
  * (`ResourceContents`, `TextResourceContents`, `BlobResourceContents`) — and for every type a
  * handler must NAME to call a public `McpContext` / `McpServer` method: the server→client request
  * params and results (`CreateMessageRequestParams` / `CreateMessageResult` and the sampling
  * message shapes, `ElicitRequestParams` / `ElicitRequestUrlParams` / `ElicitResult`,
  * `ListRootsResult` / `Root`), the client identity snapshots (`Implementation`,
  * `ClientCapabilities`), the notification arguments (`LoggingLevel`, `ProgressToken`) and the
  * `completion/complete` provider shapes (`CompleteRequestParams` and its reference sum type,
  * `Completion`).
  *
  * Every snippet is type-checked in THIS file's scope — root import only, nothing from `server.*`,
  * `core.*` or `core.wire.*` — so a missing export is a failing test here instead of a compile error
  * the first reader of `docs/tasks.md` hits. The last test is the ambiguity probe: the root import
  * side by side with the `server.*` / `core.*` / `core.wire.*` wildcards must still resolve every
  * exported name (an export alias and its target are one reference, not two).
  */
class RootImportExportsTest extends AnyFunSuite:

  private def messages(errors: List[scala.compiletime.testing.Error]): List[String] =
    errors.map(_.message)

  private def assertCompiles(
      errors: List[scala.compiletime.testing.Error]
  ): org.scalatest.Assertion =
    assert(errors == Nil, s"unexpected errors: ${messages(errors).mkString("\n---\n")}")

  test("docs/tasks.md: enabling tasks needs only the root import (TaskSettings)") {
    assertCompiles(typeCheckErrors("""
      val server = McpServer(
        name = "my-server",
        settings = McpServerSettings(tasks = TaskSettings(enabled = true))
      )
    """))
  }

  test("docs/tasks.md: the typed-contract opt-in needs only the root import (TaskSupport)") {
    assertCompiles(typeCheckErrors("""
      val tool = McpTool[ExpensiveArgs, String](name = "expensive-op")(args => args.x)
        .withTaskSupport(TaskSupport.Optional)
    """))
  }

  test("docs/tasks.md: the owner-key policy needs only the root import (TaskOwnerKey)") {
    assertCompiles(typeCheckErrors("""
      val byTransport = TaskSettings(enabled = true, ownerKey = TaskOwnerKey.Transport)
      val byPrincipal =
        TaskSettings(enabled = true, ownerKey = TaskOwnerKey.Custom(_.transportClientKey))
    """))
  }

  test("docs/transports.md: raising the frame limit needs only the root import (LimitSettings)") {
    assertCompiles(typeCheckErrors("""
      val settings = McpServerSettings(limits = LimitSettings(maxFrameChars = 8 * 1024 * 1024))
      val ceiling: Int = LimitSettings.MaxSupportedDepth
    """))
  }

  test("an EmbeddedResource payload needs only the root import (ResourceContents ADT)") {
    assertCompiles(typeCheckErrors("""
      val text: ResourceContents =
        TextResourceContents("file:///notes.txt", "hello", Some("text/plain"))
      val blob: ResourceContents =
        BlobResourceContents("file:///logo.png", "iVBORw0KGgo=", Some("image/png"))
      val contents: List[Content] = List(EmbeddedResource(text), EmbeddedResource(blob))
    """))
  }

  // --- Types a handler must NAME to call a public McpContext / McpServer method. A root-import
  // user cannot call `ctx.createMessage(...)` without constructing the params, so "the method is
  // reachable" is not enough: the argument and result shapes must be reachable too.

  test("sampling: a tool calling ctx.createMessage(CreateMessageRequestParams(...)) compiles") {
    assertCompiles(typeCheckErrors("""
      import zio.*
      val ask = McpTool[ExpensiveArgs, String](name = "ask-model").contextual { (args, ctx) =>
        val params = CreateMessageRequestParams(
          messages = List(SamplingMessage(Role.User, TextContent(args.x))),
          maxTokens = 256,
          modelPreferences = Some(ModelPreferences(hints = Some(List(ModelHint(Some("claude")))))),
          toolChoice = Some(ToolChoice(Some("none")))
        )
        ctx.get.createMessage(params).map((result: CreateMessageResult) => result.model)
      }
    """))
  }

  test("elicitation: a tool calling ctx.elicit(ElicitRequestParams(...)) / elicitUrl compiles") {
    assertCompiles(typeCheckErrors("""
      import zio.*
      import zio.json.ast.Json
      val form = McpTool[ExpensiveArgs, String](name = "confirm").contextual { (args, ctx) =>
        val params = ElicitRequestParams(
          message = s"Proceed with ${args.x}?",
          requestedSchema = Json.Obj(
            "type" -> Json.Str("object"),
            "properties" -> Json.Obj("ok" -> Json.Obj("type" -> Json.Str("boolean")))
          )
        )
        ctx.get.elicit(params).map((result: ElicitResult) => result.action)
      }
      val browser = McpTool[ExpensiveArgs, String](name = "sign-in").contextual { (args, ctx) =>
        ctx.get
          .elicitUrl(ElicitRequestUrlParams("Sign in to continue", s"https://example.com/${args.x}"))
          .map(_.action)
      }
    """))
  }

  test("client identity and roots: getClientInfo / getClientCapabilities / listRoots compile") {
    assertCompiles(typeCheckErrors("""
      import zio.*
      val whoAmI = McpTool[ExpensiveArgs, String](name = "who-am-i").contextual { (_, ctx) =>
        val info: Option[Implementation] = ctx.flatMap(_.getClientInfo)
        val caps: Option[ClientCapabilities] = ctx.flatMap(_.getClientCapabilities)
        val canSample: Boolean = caps.exists(_.sampling.isDefined)
        ctx.get.listRoots().map { (result: ListRootsResult) =>
          val first: Option[Root] = result.roots.headOption
          s"${info.map(_.name)} $canSample ${first.map(_.uri)}"
        }
      }
    """))
  }

  test("logging and progress: sendLogMessage(LoggingLevel, ...) / sendProgress(ProgressToken, ...) compile") {
    assertCompiles(typeCheckErrors("""
      import zio.*
      import zio.json.ast.Json
      val noisy = McpTool[ExpensiveArgs, String](name = "noisy").contextual { (args, ctx) =>
        val c = ctx.get
        val token: ProgressToken = c.progressToken.getOrElse(ProgressToken.StringToken(args.x))
        c.sendLogMessage(LoggingLevel.Info, Json.Str("starting"), Some("noisy")) *>
          c.sendProgress(token, 0.5, Some(1.0), Some("halfway")) *>
          c.sendProgress(ProgressToken.NumberToken(7L), 1.0) *>
          ZIO.succeed(args.x)
      }
    """))
  }

  test("completion: registering a completion/complete provider compiles") {
    assertCompiles(typeCheckErrors("""
      import zio.*
      val server = McpServer("completions")
      val registered = server.completion { (params, _) =>
        val candidates = params.ref match
          case PromptReference(name, _) => List(s"$name-a", s"$name-b")
          case ResourceTemplateReference(uri) => List(uri)
        val prefix = params.argument.value
        val known = params.context.flatMap(_.arguments).getOrElse(Map.empty).size
        ZIO.succeed(
          Completion(candidates.filter(_.startsWith(prefix)), total = Some(known), hasMore = Some(false))
        )
      }
      val request = CompleteRequestParams(
        ref = PromptReference("greet"),
        argument = CompletionArgument("name", "A"),
        context = Some(CompletionContext(Some(Map("locale" -> "en"))))
      )
      val reference: CompletionReference = request.ref
    """))
  }

  test("ambiguity probe: root import beside server.* / core.* / core.wire.* wildcards resolves") {
    assertCompiles(typeCheckErrors("""
      import com.tjclp.fastmcp.*
      import com.tjclp.fastmcp.server.*
      import com.tjclp.fastmcp.core.*
      import com.tjclp.fastmcp.core.wire.*

      val settings: McpServerSettings =
        McpServerSettings(tasks = TaskSettings(enabled = true), limits = LimitSettings())
      val policy: TaskSupport = TaskSupport.Optional
      val key: TaskOwnerKey = TaskOwnerKey.Transport
      val text: ResourceContents = TextResourceContents("file:///a.txt", "a")
      val blob: ResourceContents = BlobResourceContents("file:///a.bin", "AAAA")

      val level: LoggingLevel = LoggingLevel.Warning
      val token: ProgressToken = ProgressToken.NumberToken(1L)
      val me: Implementation = Implementation("probe", "1.0.0")
      val caps: ClientCapabilities = ClientCapabilities()
      val roots: ListRootsResult = ListRootsResult(List(Root("file:///workspace")))
      val ask: CreateMessageRequestParams = CreateMessageRequestParams(
        messages = List(SamplingMessage(Role.User, TextContent("hi"))),
        maxTokens = 1,
        modelPreferences = Some(ModelPreferences(hints = Some(List(ModelHint(Some("m")))))),
        toolChoice = Some(ToolChoice())
      )
      val answered: CreateMessageResult = CreateMessageResult(Role.Assistant, TextContent("ok"), "m")
      val form: ElicitRequestParams = ElicitRequestParams("q", zio.json.ast.Json.Obj())
      val link: ElicitRequestUrlParams = ElicitRequestUrlParams("q", "https://example.com")
      val outcome: ElicitResult = ElicitResult("accept")
      val complete: CompleteRequestParams = CompleteRequestParams(
        ResourceTemplateReference("file:///{path}"),
        CompletionArgument("path", ""),
        Some(CompletionContext())
      )
      val reference: CompletionReference = PromptReference("p")
      val completion: Completion = Completion(List("a"))
    """))
  }
