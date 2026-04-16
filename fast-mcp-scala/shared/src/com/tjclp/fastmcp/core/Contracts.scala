package com.tjclp.fastmcp.core

import scala.reflect.ClassTag

import zio.*
import zio.json.*

import com.tjclp.fastmcp.server.McpContext

/** Platform-neutral low-level decode context used by [[McpDecoder]] implementations.
  *
  * The JVM supplies a Jackson 3 backed implementation (`JacksonConversionContext`); Scala.js can
  * plug in a different one without changing the public decoder contract.
  *
  * Most users never touch this — it's the bridge that lets `McpDecoder[T]` implementations convert
  * raw JSON-RPC argument values (typically `Map[String, Any]` on the JVM) into typed Scala values.
  */
trait McpDecodeContext:
  def convertValue[T: ClassTag](name: String, rawValue: Any): T
  def parseJsonArray(name: String, rawJson: String): List[Any]
  def parseJsonObject(name: String, rawJson: String): Map[String, Any]
  def writeValueAsString(value: Any): String

/** Public typed decoder used by the shared contract layer to translate incoming MCP arguments into
  * a case-class shape.
  *
  * Most users get one of these for free: the JVM's `JacksonConverter` derivation produces an
  * `McpDecoder[T]` for any case class whose fields Jackson 3 can handle natively. Implement
  * manually only when the default derivation can't express your wire format.
  *
  * @tparam T
  *   the target Scala type produced from the decoded argument
  */
trait McpDecoder[T]:
  def decode(name: String, rawValue: Any, context: McpDecodeContext): T

  def contramap[U](f: U => Any): McpDecoder[T] =
    val self = this
    new McpDecoder[T]:
      def decode(name: String, rawValue: Any, context: McpDecodeContext): T =
        val transformed =
          try f(rawValue.asInstanceOf[U])
          catch case _: ClassCastException => rawValue
        self.decode(name, transformed, context)

object McpDecoder:
  def apply[T](using decoder: McpDecoder[T]): McpDecoder[T] = decoder

  def instance[T](f: (String, Any, McpDecodeContext) => T): McpDecoder[T] =
    new McpDecoder[T]:
      def decode(name: String, rawValue: Any, context: McpDecodeContext): T =
        f(name, rawValue, context)

/** Public typed result encoder used by mounted tool contracts.
  *
  * Any type with a given `JsonEncoder[A]` (from `zio-json`) gets an `McpEncoder[A]` for free via
  * the low-priority fallback, serializing to a single `TextContent`. Supply your own `McpEncoder`
  * when you want structured `Content` output — e.g., returning `ImageContent`, `EmbeddedResource`,
  * or a custom multi-content composition.
  *
  * @tparam A
  *   contravariant in the source type; supply a narrower encoder to get broader coverage
  */
trait McpEncoder[-A]:
  def encode(value: A): List[Content]

  def contramap[B](f: B => A): McpEncoder[B] =
    val self = this
    new McpEncoder[B]:
      def encode(value: B): List[Content] =
        self.encode(f(value))

trait McpEncoderLowPriority:

  given [A](using encoder: JsonEncoder[A]): McpEncoder[A] with

    def encode(value: A): List[Content] =
      List(TextContent(value.toJson))

object McpEncoder extends McpEncoderLowPriority:
  def apply[A](using encoder: McpEncoder[A]): McpEncoder[A] = encoder

  def instance[A](f: A => List[Content]): McpEncoder[A] =
    new McpEncoder[A]:
      def encode(value: A): List[Content] =
        f(value)

  given McpEncoder[String] with

    def encode(value: String): List[Content] =
      List(TextContent(value))

  given McpEncoder[Int] with

    def encode(value: Int): List[Content] =
      List(TextContent(value.toString))

  given McpEncoder[Long] with

    def encode(value: Long): List[Content] =
      List(TextContent(value.toString))

  given McpEncoder[Double] with

    def encode(value: Double): List[Content] =
      List(TextContent(value.toString))

  given McpEncoder[Float] with

    def encode(value: Float): List[Content] =
      List(TextContent(value.toString))

  given McpEncoder[Boolean] with

    def encode(value: Boolean): List[Content] =
      List(TextContent(value.toString))

  given McpEncoder[Unit] with

    def encode(value: Unit): List[Content] =
      Nil

  given McpEncoder[Content] with

    def encode(value: Content): List[Content] =
      List(value)

  given McpEncoder[List[Content]] with

    def encode(value: List[Content]): List[Content] =
      value

  given McpEncoder[Seq[Content]] with

    def encode(value: Seq[Content]): List[Content] =
      value.toList

trait McpCodec[A] extends McpDecoder[A] with McpEncoder[A]

object McpCodec:
  def apply[A](using codec: McpCodec[A]): McpCodec[A] = codec

/** Platform hook for deriving a tool input schema from a typed request.
  *
  * The JVM supplies a given instance via macro (`JsonSchemaMacro.schemaForCaseClass`) for any case
  * class that Tapir's `Schema` derivation can handle, honoring `@Param` metadata on fields.
  * `McpTool.derived` picks it up implicitly.
  */
trait ToolSchemaProvider[A]:
  def inputSchema: ToolInputSchema

object ToolSchemaProvider:
  def apply[A](using provider: ToolSchemaProvider[A]): ToolSchemaProvider[A] = provider

  def instance[A](schema: ToolInputSchema): ToolSchemaProvider[A] =
    new ToolSchemaProvider[A]:
      val inputSchema: ToolInputSchema = schema

/** Public resource template argument metadata. */
case class ResourceArgument(
    name: String,
    description: Option[String],
    required: Boolean = true
)

/** Public resource definition metadata. */
case class ResourceDefinition(
    uri: String,
    name: Option[String],
    description: Option[String],
    mimeType: Option[String] = Some("text/plain"),
    isTemplate: Boolean = false,
    arguments: Option[List[ResourceArgument]] = None
)

/** Shared typed contract for an MCP tool.
  *
  * A first-class, macro-free value pairing an MCP `ToolDefinition` (name, schema, annotations) with
  * a typed `ZIO` handler. Prefer the `McpTool.derived` factory when the input is a case class — it
  * pulls the `ToolInputSchema` from the implicit `ToolSchemaProvider` so you never hand-write JSON
  * schemas.
  *
  * Compiles under Scala.js, so definitions can be shared with a cross-platform module. The server
  * runtime itself is JVM-only.
  *
  * @tparam In
  *   the typed request argument (decoded from the JSON-RPC `arguments` object)
  * @tparam Out
  *   the typed handler result (encoded to `Content` via `McpEncoder`)
  */
final case class McpTool[In, Out] private (
    definition: ToolDefinition,
    handler: (In, Option[McpContext]) => ZIO[Any, Throwable, Out]
)

object McpTool:

  def withDefinition[In, Out](
      definition: ToolDefinition
  )(handler: In => ZIO[Any, Throwable, Out]): McpTool[In, Out] =
    contextualWithDefinition(definition)((input, _) => handler(input))

  def contextualWithDefinition[In, Out](
      definition: ToolDefinition
  )(handler: (In, Option[McpContext]) => ZIO[Any, Throwable, Out]): McpTool[In, Out] =
    new McpTool(definition, handler)

  def apply[In, Out](
      name: String,
      description: Option[String] = None,
      inputSchema: ToolInputSchema = ToolInputSchema.default,
      annotations: Option[ToolAnnotations] = None
  )(handler: In => ZIO[Any, Throwable, Out]): McpTool[In, Out] =
    withDefinition(
      ToolDefinition(
        name = name,
        description = description,
        inputSchema = inputSchema,
        annotations = annotations
      )
    )(handler)

  def contextual[In, Out](
      name: String,
      description: Option[String] = None,
      inputSchema: ToolInputSchema = ToolInputSchema.default,
      annotations: Option[ToolAnnotations] = None
  )(handler: (In, Option[McpContext]) => ZIO[Any, Throwable, Out]): McpTool[In, Out] =
    contextualWithDefinition(
      ToolDefinition(
        name = name,
        description = description,
        inputSchema = inputSchema,
        annotations = annotations
      )
    )(handler)

  /** Build an `McpTool` with the input schema derived automatically from a `ToolSchemaProvider[In]`
    * (the JVM summons one via macro for any Tapir-derivable case class, honoring `@Param` metadata
    * on fields).
    *
    * @param name
    *   tool name exposed to the MCP client
    * @param description
    *   optional human-readable description
    * @param annotations
    *   optional MCP Tool Annotations (hints like `readOnlyHint`, `destructiveHint`)
    * @param handler
    *   effectful function producing the typed `Out` from an `In`
    * @tparam In
    *   typed request shape — must have an implicit `ToolSchemaProvider[In]`
    * @tparam Out
    *   typed result shape — must have an implicit `McpEncoder[Out]` at mount time
    */
  def derived[In, Out](
      name: String,
      description: Option[String] = None,
      annotations: Option[ToolAnnotations] = None
  )(handler: In => ZIO[Any, Throwable, Out])(using
      schemaProvider: ToolSchemaProvider[In]
  ): McpTool[In, Out] =
    apply(name, description, schemaProvider.inputSchema, annotations)(handler)

  def derivedContextual[In, Out](
      name: String,
      description: Option[String] = None,
      annotations: Option[ToolAnnotations] = None
  )(handler: (In, Option[McpContext]) => ZIO[Any, Throwable, Out])(using
      schemaProvider: ToolSchemaProvider[In]
  ): McpTool[In, Out] =
    contextual(name, description, schemaProvider.inputSchema, annotations)(handler)

/** Shared typed contract for an MCP prompt.
  *
  * Pairs a `PromptDefinition` with a handler that turns the typed argument into the list of
  * `Message`s the prompt should emit. Unlike `McpTool`, prompts do not carry a `ToolInputSchema` —
  * MCP prompts use a simple `arguments` list (name + description + required) that you supply
  * explicitly.
  *
  * @tparam In
  *   typed argument shape — must have an implicit `McpDecoder[In]` at mount time
  */
final case class McpPrompt[In] private (
    definition: PromptDefinition,
    handler: In => ZIO[Any, Throwable, List[Message]]
)

object McpPrompt:

  private def normalizeArguments(arguments: List[PromptArgument]): Option[List[PromptArgument]] =
    Option.when(arguments.nonEmpty)(arguments)

  def withDefinition[In](
      definition: PromptDefinition
  )(handler: In => ZIO[Any, Throwable, List[Message]]): McpPrompt[In] =
    new McpPrompt(definition, handler)

  def apply[In](
      name: String,
      description: Option[String] = None,
      arguments: List[PromptArgument] = Nil
  )(handler: In => ZIO[Any, Throwable, List[Message]]): McpPrompt[In] =
    withDefinition(
      PromptDefinition(
        name = name,
        description = description,
        arguments = normalizeArguments(arguments)
      )
    )(handler)

/** Shared typed contract for a static (non-templated) MCP resource.
  *
  * Use this when the URI has no `{placeholders}`. The handler produces either text (`String`) or
  * binary (`Array[Byte]`) content on each read.
  */
final case class McpStaticResource private (
    definition: ResourceDefinition,
    handler: () => ZIO[Any, Throwable, String | Array[Byte]]
)

object McpStaticResource:

  def withDefinition(
      definition: ResourceDefinition
  )(handler: => ZIO[Any, Throwable, String | Array[Byte]]): McpStaticResource =
    new McpStaticResource(definition.copy(isTemplate = false, arguments = None), () => handler)

  def apply(
      uri: String,
      name: Option[String] = None,
      description: Option[String] = None,
      mimeType: Option[String] = Some("text/plain")
  )(handler: => ZIO[Any, Throwable, String | Array[Byte]]): McpStaticResource =
    withDefinition(
      ResourceDefinition(
        uri = uri,
        name = name,
        description = description,
        mimeType = mimeType,
        isTemplate = false,
        arguments = None
      )
    )(handler)

/** Shared typed contract for a templated MCP resource (URI with `{placeholders}`).
  *
  * Placeholders in the URI pattern are matched against fields on the `In` argument shape and
  * decoded via `McpDecoder[In]` before the handler runs.
  *
  * @tparam In
  *   typed argument shape carrying the URI placeholder values
  */
final case class McpTemplateResource[In] private (
    definition: ResourceDefinition,
    handler: In => ZIO[Any, Throwable, String | Array[Byte]]
)

object McpTemplateResource:

  private def normalizeArguments(
      arguments: List[ResourceArgument]
  ): Option[List[ResourceArgument]] =
    Option.when(arguments.nonEmpty)(arguments)

  def withDefinition[In](
      definition: ResourceDefinition
  )(handler: In => ZIO[Any, Throwable, String | Array[Byte]]): McpTemplateResource[In] =
    new McpTemplateResource(definition.copy(isTemplate = true), handler)

  def apply[In](
      uriPattern: String,
      name: Option[String] = None,
      description: Option[String] = None,
      mimeType: Option[String] = Some("text/plain"),
      arguments: List[ResourceArgument] = Nil
  )(handler: In => ZIO[Any, Throwable, String | Array[Byte]]): McpTemplateResource[In] =
    withDefinition(
      ResourceDefinition(
        uri = uriPattern,
        name = name,
        description = description,
        mimeType = mimeType,
        isTemplate = true,
        arguments = normalizeArguments(arguments)
      )
    )(handler)
