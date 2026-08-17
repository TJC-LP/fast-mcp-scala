package com.tjclp.fastmcp.examples.conformance

import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.core.{Fnv1a, LoggingLevel, ProgressToken}
import com.tjclp.fastmcp.core.wire.{
  Completion,
  CreateMessageRequestParams,
  ElicitRequestParams,
  SamplingMessage,
  TextResourceContents
}
import com.tjclp.fastmcp.jsonrpc.McpError

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

  /** One-question sampling probe: ask the client LLM and return the answer text. */
  private def askLLM(ctx: McpContext, prompt: String, maxTokens: Int): Task[String] =
    ctx
      .createMessage(
        CreateMessageRequestParams(
          messages = List(SamplingMessage(Role.User, TextContent(prompt))),
          maxTokens = maxTokens
        )
      )
      .map(r => textOf(r.content))

  // ---- 2026-07-28 MRTR fixture helpers (SEP-2322 / SEP-2575) ----

  private val StateSecret = "conformance-fixture-secret"

  /** Integrity-protected request state (`payload.keyedHash`) — test-fixture strength only, enough
    * for the tampered-state scenario to detect the appended suffix.
    */
  private def signState(payload: String): String =
    s"$payload.${Fnv1a.hex64(StateSecret + payload)}"

  private def verifyState(state: String): Option[String] =
    state.lastIndexOf('.') match
      case -1 => None
      case i =>
        val payload = state.substring(0, i)
        Option.when(signState(payload) == state)(payload)

  /** The raw `params.inputResponses` map — fixtures with hand-chosen keys decode it themselves. */
  private def rawInputResponses(ctx: McpContext): UIO[Map[String, Json]] =
    ctx.session match
      case Some(s) => s.currentRequestContext.map(_.map(_.inputResponses).getOrElse(Map.empty))
      case None => ZIO.succeed(Map.empty)

  private def stringSchema(field: String): Json =
    json(s"""{"type":"object","properties":{"$field":{"type":"string"}},"required":["$field"]}""")

  private val OkSchema: Json =
    json("""{"type":"object","properties":{"ok":{"type":"boolean"}},"required":["ok"]}""")

  private def elicitationRequest(message: String, requestedSchema: Json): Json =
    Json.Obj(
      "method" -> Json.Str("elicitation/create"),
      "params" -> Json.Obj(
        "message" -> Json.Str(message),
        "requestedSchema" -> requestedSchema
      )
    )

  private def samplingRequest(text: String, maxTokens: Int): Json =
    json(
      s"""{"method":"sampling/createMessage","params":{"messages":[{"role":"user","content":{"type":"text","text":"$text"}}],"maxTokens":$maxTokens}}"""
    )

  private val RootsRequest: Json = json("""{"method":"roots/list","params":{}}""")

  /** Decode an accepted ElicitResult's string field from a raw inputResponses entry. */
  private def acceptedString(
      responses: Map[String, Json],
      key: String,
      field: String
  ): Option[String] =
    responses.get(key).flatMap {
      case Json.Obj(fields) =>
        val m = fields.toMap
        for
          action <- m.get("action").collect { case Json.Str(a) => a }
          if action == "accept"
          content <- m.get("content").collect { case Json.Obj(c) => c.toMap }
          value <- content.get(field).collect { case Json.Str(s) => s }
        yield value
      case _ => None
    }

  /** Read `content.<field>` from a raw ElicitResult Json (sendRequest's untyped answer). */
  private def contentField(answer: Json, field: String): String =
    answer match
      case Json.Obj(fields) =>
        fields.toMap
          .get("content")
          .collect { case Json.Obj(c) => c.toMap }
          .flatMap(_.get(field))
          .collect { case Json.Str(s) => s }
          .getOrElse("?")
      case _ => "?"

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
        withCtx(ctxOpt)(ctx => askLLM(ctx, args.prompt, 100).map(t => s"LLM response: $t"))
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
      },
    // ---- 2026-07-28 fixtures (SEP-2575 stateless + SEP-2322 MRTR) ----
    McpTool
      .withSchema[NoArgs, String](
        name = "test_missing_capability",
        description = Some("Requires the sampling capability; undeclared clients get -32021"),
        inputSchema = EmptySchema
      )
      .contextual { (_, ctxOpt) =>
        withCtx(ctxOpt)(ctx => askLLM(ctx, "capability probe", 50).map(t => s"LLM response: $t"))
      },
    McpTool
      .withSchema[NoArgs, String](
        name = "test_streaming_elicitation",
        description = Some("Elicits via MRTR — never a server-initiated request on the stream"),
        inputSchema = EmptySchema
      )
      .contextual { (_, ctxOpt) =>
        withCtx(ctxOpt) { ctx =>
          ctx
            .elicit(ElicitRequestParams(message = "Confirm?", requestedSchema = OkSchema))
            .map(r => s"confirmed: action=${r.action}")
        }
      },
    McpTool
      .withSchema[NoArgs, String](
        name = "test_logging_tool",
        description = Some("Emits a log message only when the request authorizes a log level"),
        inputSchema = EmptySchema
      )
      .contextual { (_, ctxOpt) =>
        withCtx(ctxOpt) { ctx =>
          ctx
            .sendLogMessage(LoggingLevel.Info, Json.Str("test log emission"))
            .as("logging attempted")
        }
      },
    McpTool
      .withSchema[NoArgs, String](
        name = "test_input_required_result_elicitation",
        description = Some("MRTR: asks the user's name via elicitation, greets on the retry"),
        inputSchema = EmptySchema
      )
      .contextual { (_, ctxOpt) =>
        withCtx(ctxOpt) { ctx =>
          // Hand-rolled sentinel: the scenario asserts the literal inputRequests key "user_name".
          rawInputResponses(ctx).flatMap { responses =>
            acceptedString(responses, "user_name", "name") match
              case Some(name) => ZIO.succeed(s"Hello, $name!")
              case None =>
                ZIO.fail(
                  McpError.inputRequired(
                    "user_name",
                    elicitationRequest("What is your name?", stringSchema("name"))
                  )
                )
          }
        }
      },
    McpTool
      .withSchema[NoArgs, String](
        name = "test_input_required_result_sampling",
        description = Some("MRTR: asks the client LLM a question, completes on the retry"),
        inputSchema = EmptySchema
      )
      .contextual { (_, ctxOpt) =>
        withCtx(ctxOpt)(ctx =>
          askLLM(ctx, "What is the capital of France?", 100).map(t => s"LLM says: $t")
        )
      },
    McpTool
      .withSchema[NoArgs, String](
        name = "test_input_required_result_list_roots",
        description = Some("MRTR: asks for the client's roots, completes on the retry"),
        inputSchema = EmptySchema
      )
      .contextual { (_, ctxOpt) =>
        withCtx(ctxOpt) { ctx =>
          ctx.listRoots().map(r => s"Client roots: ${r.roots.map(_.uri).mkString(", ")}")
        }
      },
    McpTool
      .withSchema[NoArgs, String](
        name = "test_input_required_result_request_state",
        description = Some("MRTR: carries integrity-protected requestState across the round trip"),
        inputSchema = EmptySchema
      )
      .contextual { (_, ctxOpt) =>
        withCtx(ctxOpt) { ctx =>
          ctx
            .sendRequest(
              "elicitation/create",
              Some(
                Json.Obj(
                  "message" -> Json.Str("Please confirm"),
                  "requestedSchema" -> OkSchema
                )
              ),
              requestState = Some(signState("confirm"))
            )
            .as("state-ok: confirmed")
        }
      },
    McpTool
      .withSchema[NoArgs, String](
        name = "test_input_required_result_multiple_inputs",
        description = Some("MRTR: batches elicitation, sampling, and roots in one round trip"),
        inputSchema = EmptySchema
      )
      .contextual { (_, ctxOpt) =>
        withCtx(ctxOpt) { ctx =>
          // Hand-rolled sentinel: one InputRequiredResult carrying all three request types.
          rawInputResponses(ctx).flatMap { responses =>
            val keys = List("user_name", "greeting", "client_roots")
            if keys.forall(responses.contains) then ZIO.succeed("All inputs received: state-ok")
            else
              ZIO.fail(
                McpError.inputRequired(
                  List(
                    "user_name" ->
                      elicitationRequest("What is your name?", stringSchema("name")),
                    "greeting" -> samplingRequest("Generate a greeting", 50),
                    "client_roots" -> RootsRequest
                  ),
                  Some(signState("multi"))
                )
              )
          }
        }
      },
    McpTool
      .withSchema[NoArgs, String](
        name = "test_input_required_result_multi_round",
        description = Some("MRTR: two sequential questions across three round trips"),
        inputSchema = EmptySchema
      )
      .contextual { (_, ctxOpt) =>
        withCtx(ctxOpt) { ctx =>
          def askStep1 = ctx.sendRequest(
            "elicitation/create",
            Some(
              Json.Obj(
                "message" -> Json.Str("Step 1: What is your name?"),
                "requestedSchema" -> stringSchema("name")
              )
            ),
            requestState = Some(signState("round1"))
          )
          // Step-2 params are identical on every replay so the content-derived key is stable;
          // only requestState (not hashed) carries the accumulated answer.
          def askStep2(name: String) = ctx.sendRequest(
            "elicitation/create",
            Some(
              Json.Obj(
                "message" -> Json.Str("Step 2: What is your favorite color?"),
                "requestedSchema" -> stringSchema("color")
              )
            ),
            requestState = Some(signState(s"round2:$name"))
          )
          ctx.getRequestState.flatMap(verifyState) match
            case Some(payload) if payload.startsWith("round2:") =>
              // Round 3: step 1's answer is no longer re-sent — skip straight to step 2.
              val name = payload.stripPrefix("round2:")
              askStep2(name).map(c => s"Done: $name likes ${contentField(c, "color")}")
            case _ =>
              for
                nameAnswer <- askStep1
                name = contentField(nameAnswer, "name")
                colorAnswer <- askStep2(name)
              yield s"Done: $name likes ${contentField(colorAnswer, "color")}"
        }
      },
    McpTool
      .withSchema[NoArgs, String](
        name = "test_input_required_result_tampered_state",
        description = Some("MRTR: rejects requestState that fails integrity verification"),
        inputSchema = EmptySchema
      )
      .contextual { (_, ctxOpt) =>
        withCtx(ctxOpt) { ctx =>
          ctx.getRequestState match
            case Some(state) if verifyState(state).isEmpty =>
              ZIO.fail(McpError.invalidParams("requestState failed integrity verification"))
            case _ =>
              ctx
                .sendRequest(
                  "elicitation/create",
                  Some(
                    Json.Obj(
                      "message" -> Json.Str("Please confirm"),
                      "requestedSchema" -> OkSchema
                    )
                  ),
                  requestState = Some(signState("tamper-probe"))
                )
                .as("confirmed")
        }
      },
    McpTool
      .withSchema[NoArgs, String](
        name = "test_input_required_result_capabilities",
        description = Some("MRTR: only requests input kinds the client declared capabilities for"),
        inputSchema = EmptySchema
      )
      .contextual { (_, ctxOpt) =>
        withCtx(ctxOpt) { ctx =>
          if ctx.getClientCapabilities.exists(_.elicitation.isDefined) then
            ctx
              .elicit(ElicitRequestParams("What is your name?", stringSchema("name")))
              .map(r => s"elicited: action=${r.action}")
          else askLLM(ctx, "What is the capital of France?", 100).map(t => s"LLM says: $t")
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
      name = "test_input_required_result_prompt",
      description = Some("MRTR prompt: elicits context before rendering (SEP-2322 non-tool path)")
    ).contextual { (_, ctxOpt) =>
      ctxOpt match
        case None => ZIO.fail(new RuntimeException("MRTR prompt requires a request context"))
        case Some(ctx) =>
          ctx
            .sendRequest(
              "elicitation/create",
              Some(
                Json.Obj(
                  "message" -> Json.Str("What context should the prompt use?"),
                  "requestedSchema" -> stringSchema("context")
                )
              )
            )
            .map { answer =>
              List(
                Message(
                  Role.User,
                  TextContent(s"Prompt using context: ${contentField(answer, "context")}")
                )
              )
            }
    },
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
