package com.tjclp.fastmcp.examples.conformance

import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.core.{LoggingLevel, ProgressToken}
import com.tjclp.fastmcp.core.wire.{
  Completion,
  CreateMessageRequestParams,
  ElicitRequestParams,
  SamplingMessage,
  TextResourceContents
}

/** Cross-platform MCP "everything" server mirroring the conformance harness's reference
  * `everything-server.ts` legacy ACTIVE surface (spec 2025-11-25). One source of truth registered
  * identically on JVM and JS; the thin platform mains (`ConformanceServerJvm` /
  * `ConformanceServerJs`) just build an [[McpServer]] and `runHttp()`.
  *
  * Drives the official suite via `bunx @modelcontextprotocol/conformance server --url … --suite
  * active` (see `scripts/conformance.sh`). Both JVM and JS stream every server→client message
  * (sampling / elicitation / progress / logging) on each request's own POST SSE response, so the
  * full active suite passes on both transports — no baseline.
  */
object ConformanceServer:

  val Name = "mcp-conformance-test-server"
  val Version = "1.0.0"
  val DefaultPort = 8077

  /** Streamable (stateful) HTTP, localhost-bound, with logging + resource-subscribe + DNS-rebinding
    * protection enabled — exactly what the active suite exercises.
    */
  def settings(port: Int): McpServerSettings =
    McpServerSettings(
      host = "127.0.0.1",
      port = port,
      httpEndpoint = "/mcp",
      stateless = false,
      loggingEnabled = true,
      resourcesSubscribe = true,
      allowedHosts = Some(Set("127.0.0.1", "localhost", "[::1]", "0.0.0.0"))
    )

  // 1x1 red PNG (matches the reference server's image fixture); audio bytes are not validated.
  private val RedPng =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg=="
  private val WavB64 = "UklGRiYAAABXQVZFZm10IBAAAAABAAEAQB8AAAB9AAACABAAZGF0YQIAAAA="

  private val PngBytes: Array[Byte] =
    Array(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).map(_.toByte)

  // ---- arg shapes (manual JSON schemas below; decoders derived) ----

  case class NoArgs()
  case class PromptArg(prompt: String)
  case class MessageArg(message: String)
  case class TwoArgs(arg1: String, arg2: String)
  case class EmbedArg(resourceUri: String)
  case class TemplateId(id: String)

  given JsonDecoder[NoArgs] = DeriveJsonDecoder.gen[NoArgs]
  given JsonDecoder[PromptArg] = DeriveJsonDecoder.gen[PromptArg]
  given JsonDecoder[MessageArg] = DeriveJsonDecoder.gen[MessageArg]
  given JsonDecoder[TwoArgs] = DeriveJsonDecoder.gen[TwoArgs]
  given JsonDecoder[EmbedArg] = DeriveJsonDecoder.gen[EmbedArg]
  given JsonDecoder[TemplateId] = DeriveJsonDecoder.gen[TemplateId]

  private def schema(s: String): ToolInputSchema = ToolInputSchema.unsafeFromJsonString(s)
  private val EmptySchema = schema("""{"type":"object","properties":{}}""")
  private def json(s: String): Json = s.fromJson[Json].getOrElse(Json.Obj())

  private def textOf(c: Content): String = c match
    case t: TextContent => t.text
    case _ => ""

  private def tokenText(t: ProgressToken): String = t match
    case ProgressToken.StringToken(s) => s
    case ProgressToken.NumberToken(n) => n.toString

  private def withCtx(ctxOpt: Option[McpContext])(f: McpContext => Task[String]): Task[String] =
    ctxOpt match
      case Some(ctx) => f(ctx)
      case None => ZIO.fail(new RuntimeException("server-initiated request requires a session"))

  // ---- tools ----

  private val tools: List[McpTool[?, ?]] = List(
    McpTool.withSchema[NoArgs, String](
      name = "test_simple_text",
      description = Some("Tests simple text content response"),
      inputSchema = EmptySchema
    )(_ => "This is a simple text response for testing."),
    McpTool.withSchema[NoArgs, Content](
      name = "test_image_content",
      description = Some("Tests image content response"),
      inputSchema = EmptySchema
    )(_ => ImageContent(RedPng, "image/png")),
    McpTool.withSchema[NoArgs, Content](
      name = "test_audio_content",
      description = Some("Tests audio content response"),
      inputSchema = EmptySchema
    )(_ => AudioContent(WavB64, "audio/wav")),
    McpTool.withSchema[NoArgs, Content](
      name = "test_embedded_resource",
      description = Some("Tests embedded resource content response"),
      inputSchema = EmptySchema
    )(_ =>
      EmbeddedResource(
        TextResourceContents(
          uri = "test://embedded-resource",
          text = "This is an embedded resource content.",
          mimeType = Some("text/plain")
        )
      )
    ),
    McpTool.withSchema[NoArgs, List[Content]](
      name = "test_multiple_content_types",
      description = Some("Tests response with multiple content types (text, image, resource)"),
      inputSchema = EmptySchema
    )(_ =>
      List(
        TextContent("Multiple content types test:"),
        ImageContent(RedPng, "image/png"),
        EmbeddedResource(
          TextResourceContents(
            uri = "test://mixed-content-resource",
            text = """{"test":"data","value":123}""",
            mimeType = Some("application/json")
          )
        )
      )
    ),
    McpTool
      .withSchema[NoArgs, String](
        name = "test_tool_with_logging",
        description = Some("Tests tool that emits log messages during execution"),
        inputSchema = EmptySchema
      )
      .contextual { (_, ctxOpt) =>
        withCtx(ctxOpt) { ctx =>
          ctx.sendLogMessage(LoggingLevel.Info, Json.Str("Tool execution started")) *>
            ZIO.sleep(50.millis) *>
            ctx.sendLogMessage(LoggingLevel.Info, Json.Str("Tool processing data")) *>
            ZIO.sleep(50.millis) *>
            ctx
              .sendLogMessage(LoggingLevel.Info, Json.Str("Tool execution completed"))
              .as("Tool with logging executed successfully")
        }
      },
    McpTool.withSchema[NoArgs, String](
      name = "test_error_handling",
      description = Some("Tests error response handling"),
      inputSchema = EmptySchema
    )((_: NoArgs) =>
      ZIO.fail(new RuntimeException("This tool intentionally returns an error for testing")): Task[
        String
      ]
    ),
    McpTool
      .withSchema[NoArgs, String](
        name = "test_tool_with_progress",
        description = Some("Tests tool that reports progress notifications"),
        inputSchema = EmptySchema
      )
      .contextual { (_, ctxOpt) =>
        withCtx(ctxOpt) { ctx =>
          ctx.progressToken match
            case Some(tok) =>
              ctx.sendProgress(tok, 0.0, Some(100.0), Some("Completed step 0 of 100")) *>
                ZIO.sleep(50.millis) *>
                ctx.sendProgress(tok, 50.0, Some(100.0), Some("Completed step 50 of 100")) *>
                ZIO.sleep(50.millis) *>
                ctx
                  .sendProgress(tok, 100.0, Some(100.0), Some("Completed step 100 of 100"))
                  .as(tokenText(tok))
            case None => ZIO.succeed("no-progress-token")
        }
      },
    McpTool
      .withSchema[PromptArg, String](
        name = "test_sampling",
        description = Some("Tests server-initiated sampling (LLM completion request)"),
        inputSchema = schema(
          """{"type":"object","properties":{"prompt":{"type":"string"}},"required":["prompt"]}"""
        )
      )
      .contextual { (args, ctxOpt) =>
        withCtx(ctxOpt) { ctx =>
          ctx
            .createMessage(
              CreateMessageRequestParams(
                messages = List(SamplingMessage(Role.User, TextContent(args.prompt))),
                maxTokens = 100
              )
            )
            .map(r => s"LLM response: ${textOf(r.content)}")
        }
      },
    McpTool
      .withSchema[MessageArg, String](
        name = "test_elicitation",
        description = Some("Tests server-initiated elicitation (user input request)"),
        inputSchema = schema(
          """{"type":"object","properties":{"message":{"type":"string"}},"required":["message"]}"""
        )
      )
      .contextual { (args, ctxOpt) =>
        withCtx(ctxOpt) { ctx =>
          ctx
            .elicit(
              ElicitRequestParams(
                message = args.message,
                requestedSchema = json(
                  """{"type":"object","properties":{"response":{"type":"string","description":"User's response"}},"required":["response"]}"""
                )
              )
            )
            .map(r =>
              s"User response: action=${r.action}, content=${r.content.getOrElse(Map.empty)}"
            )
        }
      },
    McpTool
      .withSchema[NoArgs, String](
        name = "test_elicitation_sep1034_defaults",
        description = Some("Tests elicitation with default values per SEP-1034"),
        inputSchema = EmptySchema
      )
      .contextual { (_, ctxOpt) =>
        withCtx(ctxOpt) { ctx =>
          ctx
            .elicit(
              ElicitRequestParams(
                message = "Please review the defaults",
                requestedSchema = Sep1034Schema
              )
            )
            .map(r => s"Elicitation completed: action=${r.action}")
        }
      },
    McpTool
      .withSchema[NoArgs, String](
        name = "test_elicitation_sep1330_enums",
        description = Some("Tests elicitation with enum schema improvements per SEP-1330"),
        inputSchema = EmptySchema
      )
      .contextual { (_, ctxOpt) =>
        withCtx(ctxOpt) { ctx =>
          ctx
            .elicit(
              ElicitRequestParams(
                message = "Please select options",
                requestedSchema = Sep1330Schema
              )
            )
            .map(r => s"Elicitation completed: action=${r.action}")
        }
      }
  )

  private val Sep1034Schema: Json = json(
    """{"type":"object","properties":{
      |"name":{"type":"string","description":"User name","default":"John Doe"},
      |"age":{"type":"integer","description":"User age","default":30},
      |"score":{"type":"number","description":"User score","default":95.5},
      |"status":{"type":"string","description":"User status","enum":["active","inactive","pending"],"default":"active"},
      |"verified":{"type":"boolean","description":"Verification status","default":true}},
      |"required":[]}""".stripMargin
  )

  private val Sep1330Schema: Json = json(
    """{"type":"object","properties":{
      |"untitledSingle":{"type":"string","description":"Select one","enum":["option1","option2","option3"]},
      |"titledSingle":{"type":"string","description":"Select one with titles","oneOf":[{"const":"value1","title":"First Option"},{"const":"value2","title":"Second Option"},{"const":"value3","title":"Third Option"}]},
      |"legacyEnum":{"type":"string","description":"Select one (legacy)","enum":["opt1","opt2","opt3"],"enumNames":["Option One","Option Two","Option Three"]},
      |"untitledMulti":{"type":"array","description":"Select multiple","minItems":1,"maxItems":3,"items":{"type":"string","enum":["option1","option2","option3"]}},
      |"titledMulti":{"type":"array","description":"Select multiple with titles","minItems":1,"maxItems":3,"items":{"anyOf":[{"const":"value1","title":"First Choice"},{"const":"value2","title":"Second Choice"},{"const":"value3","title":"Third Choice"}]}}},
      |"required":[]}""".stripMargin
  )

  // ---- prompts ----

  private val prompts: List[McpPrompt[?]] = List(
    McpPrompt[NoArgs](
      name = "test_simple_prompt",
      description = Some("A simple prompt without arguments")
    )(_ => List(Message(Role.User, TextContent("This is a simple prompt for testing.")))),
    McpPrompt[TwoArgs](
      name = "test_prompt_with_arguments",
      description = Some("A prompt with required arguments"),
      arguments = List(
        PromptArgument("arg1", Some("First test argument"), required = true),
        PromptArgument("arg2", Some("Second test argument"), required = true)
      )
    )(a =>
      List(
        Message(
          Role.User,
          TextContent(s"Prompt with arguments: arg1='${a.arg1}', arg2='${a.arg2}'")
        )
      )
    ),
    McpPrompt[EmbedArg](
      name = "test_prompt_with_embedded_resource",
      description = Some("A prompt that includes an embedded resource"),
      arguments =
        List(PromptArgument("resourceUri", Some("URI of the resource to embed"), required = true))
    )(a =>
      List(
        Message(
          Role.User,
          EmbeddedResource(
            TextResourceContents(
              uri = a.resourceUri,
              text = "Embedded resource content for testing.",
              mimeType = Some("text/plain")
            )
          )
        ),
        Message(Role.User, TextContent("Please process the embedded resource above."))
      )
    ),
    McpPrompt[NoArgs](
      name = "test_prompt_with_image",
      description = Some("A prompt that includes image content")
    )(_ =>
      List(
        Message(Role.User, ImageContent(RedPng, "image/png")),
        Message(Role.User, TextContent("Please analyze the image above."))
      )
    )
  )

  // ---- resources ----

  private val staticResources: List[McpStaticResource] = List(
    McpStaticResource(
      "test://static-text",
      name = Some("Static Text Resource"),
      description = Some("A static text resource for testing"),
      mimeType = Some("text/plain")
    )("This is the content of the static text resource."),
    McpStaticResource(
      "test://static-binary",
      name = Some("Static Binary Resource"),
      description = Some("A static binary resource (image) for testing"),
      mimeType = Some("image/png")
    )(PngBytes),
    McpStaticResource(
      "test://watched-resource",
      name = Some("Watched Resource"),
      description = Some("A resource for subscription testing"),
      mimeType = Some("text/plain")
    )("Watched resource content")
  )

  private val templateResources: List[McpTemplateResource[?]] = List(
    McpTemplateResource[TemplateId](
      uriPattern = "test://template/{id}/data",
      name = Some("Resource Template"),
      description = Some("A resource template with parameter substitution"),
      mimeType = Some("application/json"),
      arguments = List(ResourceArgument("id", Some("The template id"), required = true))
    )(a => s"""{"id":"${a.id}","templateTest":true,"data":"Data for ID: ${a.id}"}""")
  )

  /** Register the full active surface (tools, prompts, resources, completion) on a server. */
  def register(server: McpServer[Any]): ZIO[Any, Throwable, Unit] =
    for
      _ <- ZIO.foreachDiscard(tools)(server.tool(_))
      _ <- ZIO.foreachDiscard(prompts)(server.prompt(_))
      _ <- ZIO.foreachDiscard(staticResources)(server.resource(_))
      _ <- ZIO.foreachDiscard(templateResources)(server.resource(_))
      _ <- server.completion[Any]((_, _) => ZIO.succeed(Completion(values = Nil)))
    yield ()
