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
  * `McpTool.apply` picks it up implicitly.
  */
trait ToolSchemaProvider[A]:
  def inputSchema: ToolInputSchema

object ToolSchemaProvider:
  def apply[A](using provider: ToolSchemaProvider[A]): ToolSchemaProvider[A] = provider

  def instance[A](schema: ToolInputSchema): ToolSchemaProvider[A] =
    new ToolSchemaProvider[A]:
      val inputSchema: ToolInputSchema = schema

/** Witness that a handler result type coerces into `String | Array[Byte]` — the MCP resource body
  * shape. Users rarely see this: built-in givens cover `String` and `Array[Byte]`. Useful for the
  * resource factories to accept a pure `String`-returning lambda without forcing a union-type
  * annotation.
  */
trait AsResourceBody[-A]:
  def coerce(a: A): String | Array[Byte]

object AsResourceBody:
  given string: AsResourceBody[String] with
    def coerce(a: String): String | Array[Byte] = a
  given bytes: AsResourceBody[Array[Byte]] with
    def coerce(a: Array[Byte]): String | Array[Byte] = a
  given union: AsResourceBody[String | Array[Byte]] with
    def coerce(a: String | Array[Byte]): String | Array[Byte] = a

/** Typeclass that lifts an effect-shaped `F[A]` into `ZIO[Any, Throwable, A]`.
  *
  * Used by the typed-contract factories so a handler lambda can return a `ZIO`,
  * `Either[Throwable, _]`, or `Try` without the caller wrapping it. Pure-value handlers bypass
  * this typeclass via a dedicated overload — they don't need an effect witness. Users wanting
  * another effect system (e.g. `cats.effect.IO`) supply their own given.
  */
trait ToHandlerEffect[F[_]]:
  def lift[A](fa: => F[A]): ZIO[Any, Throwable, A]

object ToHandlerEffect:

  given zio[E <: Throwable]: ToHandlerEffect[[A] =>> ZIO[Any, E, A]] with
    def lift[A](fa: => ZIO[Any, E, A]): ZIO[Any, Throwable, A] = fa

  given either: ToHandlerEffect[[A] =>> Either[Throwable, A]] with
    def lift[A](fa: => Either[Throwable, A]): ZIO[Any, Throwable, A] = ZIO.fromEither(fa)

  given tryE: ToHandlerEffect[scala.util.Try] with
    def lift[A](fa: => scala.util.Try[A]): ZIO[Any, Throwable, A] = ZIO.fromTry(fa)

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
  * a typed handler. Compiles on JVM and Scala.js so definitions can be shared across a
  * cross-platform module.
  *
  * Construct via the companion's `apply` / `contextual` factories — both accept any effect shape
  * with a given [[ToHandlerEffect]] (plain value, ZIO, Either[Throwable, _], Try, or a user-supplied
  * one). The input schema is derived automatically from a [[ToolSchemaProvider]] unless the caller
  * passes `inputSchema = Some(...)` to override.
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

  /** Builder produced by [[apply]] — holds the `ToolDefinition` and captures `In`/`Out` so the
    * handler call site can infer the effect type `F` from the lambda's return.
    */
  final class Builder[In, Out] private[McpTool] (definition: ToolDefinition):

    /** Attach a pure handler `In => Out`. */
    def apply(handler: In => Out): McpTool[In, Out] =
      new McpTool(definition, (in, _) => ZIO.attempt(handler(in)))

    /** Attach an effectful handler `In => F[Out]` for any `F` with a given [[ToHandlerEffect]]. */
    def apply[F[_]](handler: In => F[Out])(using effect: ToHandlerEffect[F]): McpTool[In, Out] =
      new McpTool(definition, (in, _) => effect.lift(handler(in)))

    /** Attach a pure contextual handler that sees the optional [[McpContext]]. */
    def contextual(handler: (In, Option[McpContext]) => Out): McpTool[In, Out] =
      new McpTool(definition, (in, ctx) => ZIO.attempt(handler(in, ctx)))

    /** Attach an effectful contextual handler. */
    def contextual[F[_]](
        handler: (In, Option[McpContext]) => F[Out]
    )(using effect: ToHandlerEffect[F]): McpTool[In, Out] =
      new McpTool(definition, (in, ctx) => effect.lift(handler(in, ctx)))

  /** Primary factory. Returns a [[Builder]]; apply it with your handler lambda:
    *
    * {{{
    *   McpTool[AddArgs, Int](name = "add") { args =>
    *     args.a + args.b       // plain value
    *   }
    *
    *   McpTool[AddArgs, Int](name = "add") { args =>
    *     ZIO.succeed(args.a + args.b)   // ZIO
    *   }
    * }}}
    *
    * The input schema is derived from a summoned [[ToolSchemaProvider]]. Use the [[withSchema]]
    * sibling to supply a hand-written `ToolInputSchema` instead.
    */
  def apply[In, Out](
      name: String,
      description: Option[String] = None,
      annotations: Option[ToolAnnotations] = None
  )(using schemaProvider: ToolSchemaProvider[In]): Builder[In, Out] =
    new Builder(
      ToolDefinition(
        name = name,
        description = description,
        inputSchema = schemaProvider.inputSchema,
        annotations = annotations
      )
    )

  /** Factory that skips the `ToolSchemaProvider` summoning and uses a hand-written JSON schema. */
  def withSchema[In, Out](
      name: String,
      description: Option[String] = None,
      annotations: Option[ToolAnnotations] = None,
      inputSchema: ToolInputSchema
  ): Builder[In, Out] =
    new Builder(
      ToolDefinition(
        name = name,
        description = description,
        inputSchema = inputSchema,
        annotations = annotations
      )
    )

  /** Internal constructor used by the annotation macros — skips schema provider summoning since the
    * macro builds the schema directly from the method signature.
    */
  private[fastmcp] def unsafeFromDefinition[In, Out](
      definition: ToolDefinition
  )(handler: (In, Option[McpContext]) => ZIO[Any, Throwable, Out]): McpTool[In, Out] =
    new McpTool(definition, handler)

/** Shared typed contract for an MCP prompt.
  *
  * Pairs a `PromptDefinition` with a handler that turns the typed argument into the list of
  * `Message`s the prompt should emit. Prompts do not carry a `ToolInputSchema` — MCP prompts use a
  * simple `arguments` list (name + description + required) that you supply explicitly.
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

  /** Builder produced by [[apply]] — carries the `PromptDefinition` so the handler call site can
    * infer the effect type `F` from the lambda's return.
    */
  final class Builder[In] private[McpPrompt] (definition: PromptDefinition):

    /** Attach a pure handler `In => List[Message]`. */
    def apply(handler: In => List[Message]): McpPrompt[In] =
      new McpPrompt(definition, in => ZIO.attempt(handler(in)))

    /** Attach an effectful handler `In => F[List[Message]]`. */
    def apply[F[_]](
        handler: In => F[List[Message]]
    )(using effect: ToHandlerEffect[F]): McpPrompt[In] =
      new McpPrompt(definition, in => effect.lift(handler(in)))

  /** Primary factory. Apply the returned [[Builder]] with your handler lambda. */
  def apply[In](
      name: String,
      description: Option[String] = None,
      arguments: List[PromptArgument] = Nil
  ): Builder[In] =
    new Builder(PromptDefinition(name, description, normalizeArguments(arguments)))

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

  /** Builder produced by [[apply]] — carries the `ResourceDefinition`; apply it with your body. */
  final class Builder private[McpStaticResource] (definition: ResourceDefinition):

    /** Attach a pure handler returning text or binary. The [[AsResourceBody]] typeclass witnesses
      * the body shape so the same `apply(...)` call works for both `String` and `Array[Byte]`.
      */
    def apply[A](handler: => A)(using body: AsResourceBody[A]): McpStaticResource =
      new McpStaticResource(definition, () => ZIO.attempt(body.coerce(handler)))

    /** Attach an effectful handler returning any `F[A]` (ZIO, Either, Try, ...) whose `A` coerces
      * into a resource body.
      */
    def effect[F[_], A](
        handler: => F[A]
    )(using effect: ToHandlerEffect[F], body: AsResourceBody[A]): McpStaticResource =
      new McpStaticResource(definition, () => effect.lift(handler).map(body.coerce))

  /** Primary factory. Apply the returned [[Builder]] with your handler block. */
  def apply(
      uri: String,
      name: Option[String] = None,
      description: Option[String] = None,
      mimeType: Option[String] = Some("text/plain")
  ): Builder =
    new Builder(
      ResourceDefinition(
        uri = uri,
        name = name,
        description = description,
        mimeType = mimeType,
        isTemplate = false,
        arguments = None
      )
    )

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

  /** Builder produced by [[apply]] — carries the `ResourceDefinition`; apply it with your body. */
  final class Builder[In] private[McpTemplateResource] (definition: ResourceDefinition):

    /** Attach a pure handler `In => A` (text or binary). */
    def apply[A](handler: In => A)(using body: AsResourceBody[A]): McpTemplateResource[In] =
      new McpTemplateResource(definition, in => ZIO.attempt(body.coerce(handler(in))))

    /** Attach an effectful handler `In => F[A]` (ZIO, Either, Try, ...). */
    def effect[F[_], A](
        handler: In => F[A]
    )(using effect: ToHandlerEffect[F], body: AsResourceBody[A]): McpTemplateResource[In] =
      new McpTemplateResource(definition, in => effect.lift(handler(in)).map(body.coerce))

  /** Primary factory. Apply the returned [[Builder]] with your handler lambda. */
  def apply[In](
      uriPattern: String,
      name: Option[String] = None,
      description: Option[String] = None,
      mimeType: Option[String] = Some("text/plain"),
      arguments: List[ResourceArgument] = Nil
  ): Builder[In] =
    new Builder(
      ResourceDefinition(
        uri = uriPattern,
        name = name,
        description = description,
        mimeType = mimeType,
        isTemplate = true,
        arguments = normalizeArguments(arguments)
      )
    )
