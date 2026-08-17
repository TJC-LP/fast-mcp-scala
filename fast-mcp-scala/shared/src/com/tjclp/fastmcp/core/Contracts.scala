package com.tjclp.fastmcp.core

import scala.annotation.targetName
import scala.reflect.ClassTag

import zio.*
import zio.json.*

import com.tjclp.fastmcp.server.McpContext

/** Platform-neutral low-level decode context used by [[McpDecoder]] implementations.
  *
  * One shared implementation serves both platforms (`DefaultDecodeContext`, zio-json backed).
  *
  * Most users never touch this — it's the bridge that lets `McpDecoder[T]` implementations convert
  * raw JSON-RPC argument values into typed Scala values.
  */
trait McpDecodeContext:
  def convertValue[T: ClassTag](name: String, rawValue: Any): T
  def parseJsonArray(name: String, rawJson: String): List[Any]
  def parseJsonObject(name: String, rawJson: String): Map[String, Any]
  def writeValueAsString(value: Any): String

/** Public typed decoder used by the shared contract layer to translate incoming MCP arguments into
  * a case-class shape.
  *
  * Most users get one of these for free: the shared zio-json derivation (`McpDecoders`) produces an
  * `McpDecoder[T]` for any type with a `given JsonDecoder[T]`. Implement manually only when the
  * default derivation can't express your wire format.
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

  /** Structured (JSON AST) form of the result, feeding `CallToolResult.structuredContent`. `None`
    * by default; the zio-json fallback overrides it with the value's AST. Only reaches the wire
    * when the tool declares an `outputSchema` (see `WireMapping.toolResultToWire`).
    */
  def encodeStructured(value: A): Option[zio.json.ast.Json] = None

  def contramap[B](f: B => A): McpEncoder[B] =
    val self = this
    new McpEncoder[B]:
      def encode(value: B): List[Content] =
        self.encode(f(value))
      override def encodeStructured(value: B): Option[zio.json.ast.Json] =
        self.encodeStructured(f(value))

/** A tool handler result carrying both renderings: `content` always (text fallback included), and
  * `structured` when the encoder can produce a JSON AST. Produced at typed-contract mount time —
  * where `Out` is still known — and consumed by `WireMapping.toolResultToWire`.
  */
final case class StructuredToolResult(
    content: List[Content],
    structured: Option[zio.json.ast.Json]
)

trait McpEncoderLowPriority:

  given [A](using encoder: JsonEncoder[A]): McpEncoder[A] with

    def encode(value: A): List[Content] =
      List(TextContent(value.toJson))

    override def encodeStructured(value: A): Option[zio.json.ast.Json] =
      value.toJsonAST.toOption

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

/** Output-schema twin of [[ToolSchemaProvider]]: derives the `outputSchema` a tool advertises on
  * `tools/list` from its `Out` type (same Tapir-backed macro, both platforms). Opt-in via
  * `McpTool#withOutputSchema` — a tool that declares an output schema also emits conforming
  * `structuredContent` on every call (spec MUST).
  */
trait ToolOutputSchemaProvider[A]:
  def outputSchema: wire.ToolOutputSchema

object ToolOutputSchemaProvider:
  def apply[A](using provider: ToolOutputSchemaProvider[A]): ToolOutputSchemaProvider[A] = provider

  def instance[A](schema: wire.ToolOutputSchema): ToolOutputSchemaProvider[A] =
    new ToolOutputSchemaProvider[A]:
      val outputSchema: wire.ToolOutputSchema = schema

/** Witness that a handler result type coerces into `String | Array[Byte]` — the MCP resource body
  * shape. Users rarely see this: built-in givens cover `String` and `Array[Byte]`. Useful for the
  * resource factories to accept a pure `String`-returning lambda without forcing a union-type
  * annotation.
  */
trait AsResourceBody[-A]:
  def coerce(a: A): String | Array[Byte]

object AsResourceBody:

  given AsResourceBody[String] with
    def coerce(a: String): String | Array[Byte] = a

  given AsResourceBody[Array[Byte]] with
    def coerce(a: Array[Byte]): String | Array[Byte] = a

  given AsResourceBody[String | Array[Byte]] with
    def coerce(a: String | Array[Byte]): String | Array[Byte] = a

/** Typeclass that lifts an effect-shaped `F[A]` into `ZIO[R, Throwable, A]`.
  *
  * Used by the typed-contract factories so a handler lambda can return a `ZIO`, `Either[Throwable,
  * _]`, or `Try` without the caller wrapping it. Pure-value handlers bypass this typeclass via a
  * dedicated overload — they don't need an effect witness. Users wanting another effect system
  * (e.g. `cats.effect.IO`) supply their own given.
  *
  * `R` is the lifted effect's ZIO environment. The `ZIO[R, E, *]` given carries `R` through; pure /
  * `Either` / `Try` givens lift into `ZIO[Any, ...]`, which by ZIO's contravariance is a subtype of
  * `ZIO[R, ...]` for any `R`.
  */
trait ToHandlerEffect[F[_], R]:
  def lift[A](fa: => F[A]): ZIO[R, Throwable, A]

object ToHandlerEffect:

  given [R, R0 >: R, E <: Throwable]: ToHandlerEffect[[A] =>> ZIO[R0, E, A], R] with
    def lift[A](fa: => ZIO[R0, E, A]): ZIO[R, Throwable, A] = fa

  given [R]: ToHandlerEffect[[A] =>> Either[Throwable, A], R] with
    def lift[A](fa: => Either[Throwable, A]): ZIO[R, Throwable, A] = ZIO.fromEither(fa)

  given [R]: ToHandlerEffect[scala.util.Try, R] with
    def lift[A](fa: => scala.util.Try[A]): ZIO[R, Throwable, A] = ZIO.fromTry(fa)

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
  * with a given [[ToHandlerEffect]] (plain value, ZIO, Either[Throwable, _], Try, or a
  * user-supplied one). The input schema is derived automatically from a [[ToolSchemaProvider]]
  * unless the caller passes `inputSchema = Some(...)` to override.
  *
  * @tparam In
  *   the typed request argument (decoded from the JSON-RPC `arguments` object)
  * @tparam Out
  *   the typed handler result (encoded to `Content` via `McpEncoder`)
  */
final case class McpTool[In, Out] private (
    definition: ToolDefinition,
    handler: (In, Option[McpContext]) => ZIO[Any, Throwable, Out],
    private[fastmcp] val decoder: McpDecoder[In],
    private[fastmcp] val encoder: McpEncoder[Out]
):

  /** Opt this tool into experimental MCP Tasks. Without this call the tool defaults to
    * [[TaskSupport.Forbidden]] — clients invoking it with `params.task` get a `-32601` error. Has
    * no effect unless [[com.tjclp.fastmcp.server.TaskSettings.enabled]] is true server-side.
    */
  def withTaskSupport(value: TaskSupport): McpTool[In, Out] =
    copy(definition = definition.copy(taskSupport = Some(value)))

  /** Advertise a derived `outputSchema` on `tools/list` and emit conforming `structuredContent` on
    * every call (the spec requires the two together). Needs an `Out` the schema macro can derive —
    * same Tapir path as input schemas — and an encoder with a structured form (any zio-json
    * `JsonEncoder` qualifies).
    */
  def withOutputSchema(using provider: ToolOutputSchemaProvider[Out]): McpTool[In, Out] =
    copy(definition = definition.copy(outputSchema = Some(provider.outputSchema)))

object McpTool:

  /** Environment-aware typed tool contract. Use `McpTool[In, Out, R](...)` to construct one; the
    * nested type keeps the original `McpTool[In, Out]` arity source-compatible for no-environment
    * handlers and type annotations.
    */
  final case class WithEnv[In, Out, R] private[McpTool] (
      definition: ToolDefinition,
      handler: (In, Option[McpContext]) => ZIO[R, Throwable, Out],
      private[fastmcp] val decoder: McpDecoder[In],
      private[fastmcp] val encoder: McpEncoder[Out]
  ):

    /** Opt this tool into experimental MCP Tasks. */
    def withTaskSupport(value: TaskSupport): WithEnv[In, Out, R] =
      copy(definition = definition.copy(taskSupport = Some(value)))

    /** Advertise a derived `outputSchema` and emit conforming `structuredContent` (see
      * [[McpTool.withOutputSchema]]).
      */
    def withOutputSchema(using provider: ToolOutputSchemaProvider[Out]): WithEnv[In, Out, R] =
      copy(definition = definition.copy(outputSchema = Some(provider.outputSchema)))

  /** Builder produced by [[apply]] — holds the `ToolDefinition` and captures `In`/`Out` so the
    * handler call site can infer the effect type `F` from the lambda's return.
    */
  final class Builder[In, Out] private[McpTool] (
      definition: ToolDefinition
  )(using decoder: McpDecoder[In], encoder: McpEncoder[Out]):

    /** Attach a pure handler `In => Out`. */
    def apply(handler: In => Out): McpTool[In, Out] =
      new McpTool(definition, (in, _) => ZIO.attempt(handler(in)), decoder, encoder)

    /** Attach an effectful handler `In => F[Out]` for any `F` with a given [[ToHandlerEffect]]. */
    def apply[F[_]](handler: In => F[Out])(using
        effect: ToHandlerEffect[F, Any]
    ): McpTool[In, Out] =
      new McpTool(definition, (in, _) => effect.lift(handler(in)), decoder, encoder)

    /** Attach a pure contextual handler that sees the optional [[McpContext]]. */
    def contextual(handler: (In, Option[McpContext]) => Out): McpTool[In, Out] =
      new McpTool(definition, (in, ctx) => ZIO.attempt(handler(in, ctx)), decoder, encoder)

    /** Attach an effectful contextual handler. */
    def contextual[F[_]](
        handler: (In, Option[McpContext]) => F[Out]
    )(using effect: ToHandlerEffect[F, Any]): McpTool[In, Out] =
      new McpTool(definition, (in, ctx) => effect.lift(handler(in, ctx)), decoder, encoder)

  /** Environment-aware builder produced by the three-type-argument factory. */
  final class EnvBuilder[In, Out, R] private[McpTool] (
      definition: ToolDefinition
  )(using decoder: McpDecoder[In], encoder: McpEncoder[Out]):

    /** Attach a pure handler `In => Out`. */
    def apply(handler: In => Out): WithEnv[In, Out, R] =
      new WithEnv(definition, (in, _) => ZIO.attempt(handler(in)), decoder, encoder)

    /** Attach an effectful handler `In => F[Out]` for any `F` with a given [[ToHandlerEffect]]. */
    def apply[F[_]](handler: In => F[Out])(using
        effect: ToHandlerEffect[F, R]
    ): WithEnv[In, Out, R] =
      new WithEnv(definition, (in, _) => effect.lift(handler(in)), decoder, encoder)

    /** Attach a pure contextual handler that sees the optional [[McpContext]]. */
    def contextual(handler: (In, Option[McpContext]) => Out): WithEnv[In, Out, R] =
      new WithEnv(definition, (in, ctx) => ZIO.attempt(handler(in, ctx)), decoder, encoder)

    /** Attach an effectful contextual handler. */
    def contextual[F[_]](
        handler: (In, Option[McpContext]) => F[Out]
    )(using effect: ToHandlerEffect[F, R]): WithEnv[In, Out, R] =
      new WithEnv(definition, (in, ctx) => effect.lift(handler(in, ctx)), decoder, encoder)

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
    *
    *   // Layer-dependent handler — supply R explicitly:
    *   McpTool[AddArgs, Int, Client](name = "add") { args =>
    *     ZIO.serviceWithZIO[Client](_.do(args))
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
  )(using
      schemaProvider: ToolSchemaProvider[In],
      decoder: McpDecoder[In],
      encoder: McpEncoder[Out]
  ): Builder[In, Out] =
    new Builder(
      ToolDefinition(
        name = name,
        description = description,
        inputSchema = schemaProvider.inputSchema,
        annotations = annotations
      )
    )

  /** Environment-aware factory. Use this form when the handler depends on services supplied via
    * `server.runHttp().provide(...)`.
    */
  @targetName("applyWithEnvName")
  def apply[In, Out, R](name: String)(using
      schemaProvider: ToolSchemaProvider[In],
      decoder: McpDecoder[In],
      encoder: McpEncoder[Out]
  ): EnvBuilder[In, Out, R] =
    apply[In, Out, R](name, None, None)

  /** Environment-aware factory with a description. */
  @targetName("applyWithEnvDescription")
  def apply[In, Out, R](name: String, description: Option[String])(using
      schemaProvider: ToolSchemaProvider[In],
      decoder: McpDecoder[In],
      encoder: McpEncoder[Out]
  ): EnvBuilder[In, Out, R] =
    apply[In, Out, R](name, description, None)

  /** Environment-aware factory with complete metadata. */
  @targetName("applyWithEnvFull")
  def apply[In, Out, R](
      name: String,
      description: Option[String],
      annotations: Option[ToolAnnotations]
  )(using
      schemaProvider: ToolSchemaProvider[In],
      decoder: McpDecoder[In],
      encoder: McpEncoder[Out]
  ): EnvBuilder[In, Out, R] =
    new EnvBuilder(
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
      inputSchema: ToolInputSchema,
      description: Option[String] = None,
      annotations: Option[ToolAnnotations] = None
  )(using decoder: McpDecoder[In], encoder: McpEncoder[Out]): Builder[In, Out] =
    new Builder(
      ToolDefinition(
        name = name,
        description = description,
        inputSchema = inputSchema,
        annotations = annotations
      )
    )

  /** Environment-aware factory that skips schema derivation and uses a hand-written JSON schema. */
  @targetName("withSchemaWithEnvName")
  def withSchema[In, Out, R](
      name: String,
      inputSchema: ToolInputSchema
  )(using decoder: McpDecoder[In], encoder: McpEncoder[Out]): EnvBuilder[In, Out, R] =
    withSchema[In, Out, R](name, inputSchema, None, None)

  /** Environment-aware schema factory with a description. */
  @targetName("withSchemaWithEnvDescription")
  def withSchema[In, Out, R](
      name: String,
      inputSchema: ToolInputSchema,
      description: Option[String]
  )(using decoder: McpDecoder[In], encoder: McpEncoder[Out]): EnvBuilder[In, Out, R] =
    withSchema[In, Out, R](name, inputSchema, description, None)

  /** Environment-aware schema factory with complete metadata. */
  @targetName("withSchemaWithEnvFull")
  def withSchema[In, Out, R](
      name: String,
      inputSchema: ToolInputSchema,
      description: Option[String],
      annotations: Option[ToolAnnotations]
  )(using decoder: McpDecoder[In], encoder: McpEncoder[Out]): EnvBuilder[In, Out, R] =
    new EnvBuilder(
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
  )(handler: (In, Option[McpContext]) => ZIO[Any, Throwable, Out])(using
      decoder: McpDecoder[In],
      encoder: McpEncoder[Out]
  ): McpTool[In, Out] =
    new McpTool(definition, handler, decoder, encoder)

  /** Internal environment-aware constructor used by macros. */
  @targetName("unsafeFromDefinitionWithEnv")
  private[fastmcp] def unsafeFromDefinition[In, Out, R](
      definition: ToolDefinition
  )(handler: (In, Option[McpContext]) => ZIO[R, Throwable, Out])(using
      decoder: McpDecoder[In],
      encoder: McpEncoder[Out]
  ): WithEnv[In, Out, R] =
    new WithEnv(definition, handler, decoder, encoder)

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
    handler: (In, Option[McpContext]) => ZIO[Any, Throwable, List[Message]],
    private[fastmcp] val decoder: McpDecoder[In]
)

object McpPrompt:

  /** Environment-aware typed prompt contract. */
  final case class WithEnv[In, R] private[McpPrompt] (
      definition: PromptDefinition,
      handler: (In, Option[McpContext]) => ZIO[R, Throwable, List[Message]],
      private[fastmcp] val decoder: McpDecoder[In]
  )

  private def normalizeArguments(arguments: List[PromptArgument]): Option[List[PromptArgument]] =
    Option.when(arguments.nonEmpty)(arguments)

  /** Builder produced by [[apply]] — carries the `PromptDefinition` so the handler call site can
    * infer the effect type `F` from the lambda's return.
    */
  final class Builder[In] private[McpPrompt] (
      definition: PromptDefinition
  )(using decoder: McpDecoder[In]):

    /** Attach a pure handler `In => List[Message]`. */
    def apply(handler: In => List[Message]): McpPrompt[In] =
      new McpPrompt(definition, (in, _) => ZIO.attempt(handler(in)), decoder)

    /** Attach an effectful handler `In => F[List[Message]]`. */
    def apply[F[_]](
        handler: In => F[List[Message]]
    )(using effect: ToHandlerEffect[F, Any]): McpPrompt[In] =
      new McpPrompt(definition, (in, _) => effect.lift(handler(in)), decoder)

    /** Attach a pure contextual handler that sees the optional [[McpContext]]. */
    def contextual(handler: (In, Option[McpContext]) => List[Message]): McpPrompt[In] =
      new McpPrompt(definition, (in, ctx) => ZIO.attempt(handler(in, ctx)), decoder)

    /** Attach an effectful contextual handler. */
    def contextual[F[_]](
        handler: (In, Option[McpContext]) => F[List[Message]]
    )(using effect: ToHandlerEffect[F, Any]): McpPrompt[In] =
      new McpPrompt(definition, (in, ctx) => effect.lift(handler(in, ctx)), decoder)

  /** Environment-aware builder produced by the two-type-argument factory. */
  final class EnvBuilder[In, R] private[McpPrompt] (
      definition: PromptDefinition
  )(using decoder: McpDecoder[In]):

    /** Attach a pure handler `In => List[Message]`. */
    def apply(handler: In => List[Message]): WithEnv[In, R] =
      new WithEnv(definition, (in, _) => ZIO.attempt(handler(in)), decoder)

    /** Attach an effectful handler `In => F[List[Message]]`. */
    def apply[F[_]](
        handler: In => F[List[Message]]
    )(using effect: ToHandlerEffect[F, R]): WithEnv[In, R] =
      new WithEnv(definition, (in, _) => effect.lift(handler(in)), decoder)

    /** Attach a pure contextual handler that sees the optional [[McpContext]]. */
    def contextual(handler: (In, Option[McpContext]) => List[Message]): WithEnv[In, R] =
      new WithEnv(definition, (in, ctx) => ZIO.attempt(handler(in, ctx)), decoder)

    /** Attach an effectful contextual handler. */
    def contextual[F[_]](
        handler: (In, Option[McpContext]) => F[List[Message]]
    )(using effect: ToHandlerEffect[F, R]): WithEnv[In, R] =
      new WithEnv(definition, (in, ctx) => effect.lift(handler(in, ctx)), decoder)

  /** Primary factory. Apply the returned [[Builder]] with your handler lambda. */
  def apply[In](
      name: String,
      description: Option[String] = None,
      arguments: List[PromptArgument] = Nil
  )(using decoder: McpDecoder[In]): Builder[In] =
    new Builder(PromptDefinition(name, description, normalizeArguments(arguments)))

  /** Environment-aware factory. */
  @targetName("applyWithEnvName")
  def apply[In, R](name: String)(using decoder: McpDecoder[In]): EnvBuilder[In, R] =
    apply[In, R](name, None, Nil)

  /** Environment-aware factory with a description. */
  @targetName("applyWithEnvDescription")
  def apply[In, R](
      name: String,
      description: Option[String]
  )(using decoder: McpDecoder[In]): EnvBuilder[In, R] =
    apply[In, R](name, description, Nil)

  /** Environment-aware factory with arguments. */
  @targetName("applyWithEnvArguments")
  def apply[In, R](
      name: String,
      arguments: List[PromptArgument]
  )(using decoder: McpDecoder[In]): EnvBuilder[In, R] =
    apply[In, R](name, None, arguments)

  /** Environment-aware factory with complete metadata. */
  @targetName("applyWithEnvFull")
  def apply[In, R](
      name: String,
      description: Option[String],
      arguments: List[PromptArgument]
  )(using decoder: McpDecoder[In]): EnvBuilder[In, R] =
    new EnvBuilder(PromptDefinition(name, description, normalizeArguments(arguments)))

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

  /** Environment-aware typed static resource contract. */
  final case class WithEnv[R] private[McpStaticResource] (
      definition: ResourceDefinition,
      handler: () => ZIO[R, Throwable, String | Array[Byte]]
  )

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
    )(using effect: ToHandlerEffect[F, Any], body: AsResourceBody[A]): McpStaticResource =
      new McpStaticResource(definition, () => effect.lift(handler).map(body.coerce))

  /** Environment-aware builder. */
  final class EnvBuilder[R] private[McpStaticResource] (definition: ResourceDefinition):

    /** Attach a pure handler returning text or binary. */
    def apply[A](handler: => A)(using body: AsResourceBody[A]): WithEnv[R] =
      new WithEnv(definition, () => ZIO.attempt(body.coerce(handler)))

    /** Attach an effectful handler returning any `F[A]` whose `A` coerces into a resource body. */
    def effect[F[_], A](
        handler: => F[A]
    )(using effect: ToHandlerEffect[F, R], body: AsResourceBody[A]): WithEnv[R] =
      new WithEnv(definition, () => effect.lift(handler).map(body.coerce))

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

  /** Environment-aware factory. */
  def withEnv[R](
      uri: String,
      name: Option[String] = None,
      description: Option[String] = None,
      mimeType: Option[String] = Some("text/plain")
  ): EnvBuilder[R] =
    new EnvBuilder(
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
    handler: In => ZIO[Any, Throwable, String | Array[Byte]],
    private[fastmcp] val decoder: McpDecoder[In]
)

object McpTemplateResource:

  /** Environment-aware typed template resource contract. */
  final case class WithEnv[In, R] private[McpTemplateResource] (
      definition: ResourceDefinition,
      handler: In => ZIO[R, Throwable, String | Array[Byte]],
      private[fastmcp] val decoder: McpDecoder[In]
  )

  private def normalizeArguments(
      arguments: List[ResourceArgument]
  ): Option[List[ResourceArgument]] =
    Option.when(arguments.nonEmpty)(arguments)

  /** Builder produced by [[apply]] — carries the `ResourceDefinition`; apply it with your body. */
  final class Builder[In] private[McpTemplateResource] (
      definition: ResourceDefinition
  )(using decoder: McpDecoder[In]):

    /** Attach a pure handler `In => A` (text or binary). */
    def apply[A](handler: In => A)(using body: AsResourceBody[A]): McpTemplateResource[In] =
      new McpTemplateResource(definition, in => ZIO.attempt(body.coerce(handler(in))), decoder)

    /** Attach an effectful handler `In => F[A]` (ZIO, Either, Try, ...). */
    def effect[F[_], A](
        handler: In => F[A]
    )(using
        effect: ToHandlerEffect[F, Any],
        body: AsResourceBody[A]
    ): McpTemplateResource[In] =
      new McpTemplateResource(
        definition,
        in => effect.lift(handler(in)).map(body.coerce),
        decoder
      )

  /** Environment-aware builder produced by the two-type-argument factory. */
  final class EnvBuilder[In, R] private[McpTemplateResource] (
      definition: ResourceDefinition
  )(using decoder: McpDecoder[In]):

    /** Attach a pure handler `In => A` (text or binary). */
    def apply[A](handler: In => A)(using body: AsResourceBody[A]): WithEnv[In, R] =
      new WithEnv(definition, in => ZIO.attempt(body.coerce(handler(in))), decoder)

    /** Attach an effectful handler `In => F[A]` (ZIO, Either, Try, ...). */
    def effect[F[_], A](
        handler: In => F[A]
    )(using effect: ToHandlerEffect[F, R], body: AsResourceBody[A]): WithEnv[In, R] =
      new WithEnv(
        definition,
        in => effect.lift(handler(in)).map(body.coerce),
        decoder
      )

  /** Primary factory. Apply the returned [[Builder]] with your handler lambda. */
  def apply[In](
      uriPattern: String,
      name: Option[String] = None,
      description: Option[String] = None,
      mimeType: Option[String] = Some("text/plain"),
      arguments: List[ResourceArgument] = Nil
  )(using decoder: McpDecoder[In]): Builder[In] =
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

  /** Environment-aware factory. */
  @targetName("applyWithEnvUri")
  def apply[In, R](uriPattern: String)(using decoder: McpDecoder[In]): EnvBuilder[In, R] =
    apply[In, R](uriPattern, None, None, Some("text/plain"), Nil)

  /** Environment-aware factory with arguments. */
  @targetName("applyWithEnvArguments")
  def apply[In, R](
      uriPattern: String,
      arguments: List[ResourceArgument]
  )(using decoder: McpDecoder[In]): EnvBuilder[In, R] =
    apply[In, R](uriPattern, None, None, Some("text/plain"), arguments)

  /** Environment-aware factory with description and arguments. */
  @targetName("applyWithEnvDescriptionArguments")
  def apply[In, R](
      uriPattern: String,
      description: Option[String],
      arguments: List[ResourceArgument]
  )(using decoder: McpDecoder[In]): EnvBuilder[In, R] =
    apply[In, R](uriPattern, None, description, Some("text/plain"), arguments)

  /** Environment-aware factory with complete metadata. */
  @targetName("applyWithEnvFull")
  def apply[In, R](
      uriPattern: String,
      name: Option[String],
      description: Option[String],
      mimeType: Option[String],
      arguments: List[ResourceArgument]
  )(using decoder: McpDecoder[In]): EnvBuilder[In, R] =
    new EnvBuilder(
      ResourceDefinition(
        uri = uriPattern,
        name = name,
        description = description,
        mimeType = mimeType,
        isTemplate = true,
        arguments = normalizeArguments(arguments)
      )
    )
