package com.tjclp.fastmcp.core.wire

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.{
  AudioContent,
  Content,
  EmbeddedResource,
  ImageContent,
  LoggingLevel,
  Protocol,
  ResourceLink,
  Role,
  TaskSupport,
  TextContent,
  ToolExecution,
  ToolInputSchema,
  toAst
}

/** M3 regression net: round-trips the native-core wire types through their zio-json codecs and
  * asserts the spec-critical encodings (discriminator tags, absent-vs-null `_meta`, embedded
  * JSON Schema objects, text-vs-blob resource discrimination).
  *
  * The codec *patterns* were first validated standalone against zio-json 0.7.44; this is the
  * in-tree version that runs as part of the suite at M8 re-green.
  */
class WireCodecRoundTripTest extends AnyFlatSpec with Matchers {

  /** assert `decode(encode(x)) == x` for a given codec. */
  private def roundTrips[A: JsonEncoder: JsonDecoder](value: A): org.scalatest.Assertion =
    value.toJson.fromJson[A] shouldBe Right(value)

  // ---------- Content ADT ----------

  "Content discriminator" should "emit spec `type` tags via @jsonHint" in {
    (TextContent("hi"): Content).toJson should include("\"type\":\"text\"")
    (ImageContent("AAAA", "image/png"): Content).toJson should include("\"type\":\"image\"")
    (AudioContent("AAAA", "audio/wav"): Content).toJson should include("\"type\":\"audio\"")
    (ResourceLink("file:///x", "x"): Content).toJson should include("\"type\":\"resource_link\"")
    val embedded: Content = EmbeddedResource(TextResourceContents("file:///x", "body"))
    embedded.toJson should include("\"type\":\"resource\"")
  }

  it should "omit None _meta and annotations (absent, not null)" in {
    val json = (TextContent("hi"): Content).toJson
    json should not include "_meta"
    json should not include "annotations"
    json should not include "null"
  }

  it should "round-trip every variant through Content" in {
    roundTrips[Content](TextContent("hi"))
    roundTrips[Content](ImageContent("AAAA", "image/png"))
    roundTrips[Content](AudioContent("AAAA", "audio/wav"))
    roundTrips[Content](ResourceLink("file:///x", "x", description = Some("d")))
    roundTrips[Content](EmbeddedResource(TextResourceContents("file:///x", "body")))
    roundTrips[Content](
      TextContent("hi", annotations = Some(Annotations(priority = Some(0.5))))
    )
  }

  // ---------- ResourceContents (text vs blob, no `type` tag) ----------

  "ResourceContents" should "discriminate by text/blob presence with no type tag" in {
    val text: ResourceContents = TextResourceContents("file:///a", "body")
    text.toJson should not include "\"type\""
    roundTrips[ResourceContents](text)
    roundTrips[ResourceContents](BlobResourceContents("file:///b", "AAAA", Some("application/octet-stream")))
  }

  it should "reject an object with neither text nor blob" in {
    """{"uri":"x"}""".fromJson[ResourceContents].isLeft shouldBe true
  }

  // ---------- Tool wire shape ----------

  "Tool.inputSchema" should "serialize as an embedded JSON object, not a string" in {
    val tool = Tool(
      name = "add",
      inputSchema = ToolInputSchema.unsafeFromJsonString(
        """{"type":"object","properties":{"a":{"type":"integer"}}}"""
      )
    )
    val json = tool.toJson
    json should include("\"inputSchema\":{\"type\":\"object\"")
    json should not include "\"inputSchema\":\""
    // semantic round-trip: parsed schema AST is preserved
    val back = json.fromJson[Tool].toOption.get
    back.inputSchema.toAst shouldBe tool.inputSchema.toAst
  }

  it should "carry execution.taskSupport when set" in {
    val tool = Tool(
      name = "slow",
      inputSchema = ToolInputSchema.default,
      execution = Some(ToolExecution(Some(TaskSupport.Optional)))
    )
    tool.toJson should include("\"taskSupport\":\"optional\"")
    roundTrips[Tool](tool)
  }

  // ---------- Envelopes ----------

  "InitializeResult" should "round-trip with derived capabilities" in {
    val result = InitializeResult(
      protocolVersion = Protocol.LatestProtocolVersion,
      capabilities = ServerCapabilities(tools = Some(ToolsCapability(listChanged = Some(true)))),
      serverInfo = Implementation("srv", "0.5.0")
    )
    roundTrips(result)
  }

  "ServerCapabilities" should "omit logging unless explicitly present (issue #56)" in {
    val caps = ServerCapabilities(tools = Some(ToolsCapability()))
    val json = caps.toJson
    json should not include "logging"
    // and when present, it serializes
    ServerCapabilities(logging = Some(Json.Obj())).toJson should include("logging")
  }

  "CallToolResult" should "round-trip mixed content with isError" in {
    val result = CallToolResult(
      content = List(TextContent("done"), ImageContent("AAAA", "image/png")),
      isError = Some(false)
    )
    roundTrips(result)
  }

  "ReadResourceResult" should "round-trip mixed text/blob contents" in {
    val result = ReadResourceResult(
      contents = List(
        TextResourceContents("file:///a", "body"),
        BlobResourceContents("file:///b", "AAAA")
      )
    )
    roundTrips(result)
  }

  "GetPromptResult" should "round-trip with prompt messages" in {
    val result = GetPromptResult(
      messages = List(PromptMessage(Role.User, TextContent("hello"))),
      description = Some("greeting")
    )
    roundTrips(result)
  }

  "CompleteRequestParams" should "round-trip both reference kinds" in {
    roundTrips(
      CompleteRequestParams(
        ref = PromptReference("code_review"),
        argument = CompletionArgument("language", "sca")
      )
    )
    roundTrips(
      CompleteRequestParams(
        ref = ResourceTemplateReference("file:///{path}"),
        argument = CompletionArgument("path", "/sr")
      )
    )
  }

  "CompletionReference" should "use ref/prompt and ref/resource tags" in {
    (PromptReference("p"): CompletionReference).toJson should include("\"type\":\"ref/prompt\"")
    (ResourceTemplateReference("u"): CompletionReference).toJson should include(
      "\"type\":\"ref/resource\""
    )
  }

  // ---------- Logging ----------

  "LoggingLevel" should "round-trip lowercase and order by severity" in {
    LoggingLevel.Warning.toJson shouldBe "\"warning\""
    "\"emergency\"".fromJson[LoggingLevel] shouldBe Right(LoggingLevel.Emergency)
    import LoggingLevel.severity
    LoggingLevel.Debug.severity should be < LoggingLevel.Error.severity
  }

  // ---------- Tasks (audit) ----------

  "TaskSupport" should "round-trip the three spec values" in {
    TaskSupport.values.foreach(ts => roundTrips(ts))
    TaskSupport.Required.toJson shouldBe "\"required\""
  }
}
