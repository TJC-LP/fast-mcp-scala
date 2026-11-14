package com.tjclp.fastmcp.macros

import scala.quoted.*

import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.McpSchema
import com.tjclp.fastmcp.macros.schema.FunctionAnalyzer

/** Native JSON Schema derivation for annotation parameters and typed contracts.
  *
  * The macro emits the small JSON Schema subset MCP tools need directly from Scala types. It
  * supports primitives, options, collections, maps, Scala 3 and Java enums, products (tuples
  * included), and tagged sums. A user-provided [[com.tjclp.fastmcp.core.McpInputCodec]] is the
  * escape hatch for domain types whose wire representation cannot be inferred from their Scala
  * shape.
  *
  * ==`required` and Scala defaults==
  * A schema is derived for one of two directions, and the two treat a case-class field with a Scala
  * default differently (TJC-5117):
  *   - Input (a value the client sends: tool arguments): the field is NOT in `required`. The
  *     derived zio-json decoder fills an absent field with the default, so the schema must not
  *     refuse the call. `@Param(required = true)` puts it back.
  *   - Output (a value the server writes: `outputSchema`): the field IS in `required`, because the
  *     encoder always writes it.
  *
  * An `Option` field is optional in both directions. The rule applies at every depth: a case class
  * nested in a field, a collection, a map value, an `Either` or a sealed hierarchy follows the
  * direction of the root.
  *
  * The input rule assumes the decoder is the derived one, or any `DeriveJsonDecoder.gen` / `derives
  * JsonDecoder` instance, which applies defaults the same way. A hand-written `JsonDecoder` that
  * does not apply the default needs `@Param(required = true)` on the field, or a `McpSchema` /
  * `McpInputCodec` for the type.
  */
object JsonSchemaMacro:

  /** Which registration path a schema is derived for; it decides the remedies a diagnostic names.
    * An annotated parameter needs a decoder as well as a schema, and it has no hand-written
    * `inputSchema`, so `McpSchema` (schema only) and `McpTool.withSchema` are not remedies there:
    * `McpInputCodec` (decoder + schema in one given) is.
    */
  private enum Path:
    case AnnotatedMethod, TypedContract

  /** Produces a JSON schema describing the parameters of the given function.
    *
    * Case classes among the parameter types use the input rule (see the object documentation). A
    * top-level PARAMETER with a Scala default is still listed in `required` here: this macro sees
    * the function's type, which does not carry defaults, and whether a default is applied depends
    * on the handler built next to it. The `@Tool` processor knows both and removes such a parameter
    * from `required` itself.
    */
  inline def schemaForFunctionArgs[F](inline fn: F): Json =
    ${ schemaForFunctionArgsImpl('fn, '{ Nil }) }

  /** Produces a JSON schema describing the parameters of the given function, excluding specified
    * parameters. This is primarily used to omit an injected `McpContext`.
    */
  inline def schemaForFunctionArgs[F](inline fn: F, inline exclude: List[String]): Json =
    ${ schemaForFunctionArgsImpl('fn, 'exclude) }

  /** Produces a JSON schema describing a single type `T`, with the INPUT rule: a case-class field
    * with a Scala default is not listed in `required` (see the object documentation).
    *
    * Input is the choice because that is how a free-standing schema is used in this library: as a
    * fragment of a hand-assembled `inputSchema`, validated against what a client sends and then
    * decoded. For a schema that describes what the server writes, use [[schemaForToolOutput]],
    * which keeps defaulted fields in `required`.
    */
  inline def schemaForType[T]: Json =
    ${ schemaForTypeImpl[T](input = true) }

  /** [[schemaForType]] for a typed tool's `In`, guarded: MCP `arguments` is always a JSON object,
    * so a type whose schema root is not an object (`String`, `Int`, a collection, `Option`,
    * `Either`, an enum, a sealed trait) would advertise a tool no client can ever call — a
    * compile-time error.
    */
  inline def schemaForToolInput[T]: Json =
    ${ objectRootSchemaImpl[T](input = true) }

  /** The schema of a typed tool's `Out` under `.withOutputSchema`, with the OUTPUT rule: a field
    * with a Scala default stays in `required`, because the encoder always writes it. Guarded like
    * [[schemaForToolInput]]: `structuredContent` is always a JSON object, so a non-object root
    * could never conform.
    */
  inline def schemaForToolOutput[T]: Json =
    ${ objectRootSchemaImpl[T](input = false) }

  private def schemaForFunctionArgsImpl[F: Type](fn: Expr[F], exclude: Expr[List[String]])(using
      Quotes
  ): Expr[Json] =
    import quotes.reflect.*

    val paramNamesOpt = FunctionAnalyzer.maybeRealParamNames(fn.asTerm)
    val excluded = exclude.valueOrAbort
    val params = FunctionAnalyzer
      .extractParams(fn.asTerm.tpe, paramNamesOpt)
      .filterNot(param => excluded.contains(param._1))

    given Path = Path.AnnotatedMethod

    // Function arguments are always input. Top-level parameters are required unless `Option`:
    // their Scala defaults are not visible here (see the Scaladoc of `schemaForFunctionArgs`).
    val fields = params.map { case (name, tpe) => name -> schemaFor(tpe, Nil, input = true) }
    val required = params.collect { case (name, tpe) if !isOption(tpe) => name }
    val rawSchema = objectSchema(fields, required)

    val metadataEntries = params.map { case (name, tpe) =>
      tpe.asType match
        case '[t] =>
          val metadata = MacroUtils.schemaMetadataForType[t](input = true)
          '{ ${ Expr(name) } -> $metadata }
    }
    val metadata = '{ SchemaMetadataNode(properties = Map(${ Varargs(metadataEntries) }*)) }

    '{ MacroUtils.injectSchemaMetadata($rawSchema, $metadata) }

  private def schemaForTypeImpl[T: Type](input: Boolean)(using Quotes): Expr[Json] =
    given Path = Path.TypedContract
    val rawSchema = schemaFor(quotes.reflect.TypeRepr.of[T], Nil, input)
    val metadata = MacroUtils.schemaMetadataForType[T](input)
    '{ MacroUtils.injectSchemaMetadata($rawSchema, $metadata) }

  private def objectRootSchemaImpl[T: Type](input: Boolean)(using Quotes): Expr[Json] =
    import quotes.reflect.*
    val tpe = TypeRepr.of[T].dealias.simplified
    nonObjectRoot(tpe).foreach { root =>
      val shown = tpe.show(using Printer.TypeReprShortCode)
      if input then
        report.errorAndAbort(
          "McpTool In must be a case class (use `case class NoArgs()` for no arguments): " +
            s"$shown derives an inputSchema whose root is $root, and MCP tool `arguments` is always " +
            "a JSON object, so such a tool could never be called. Case classes, Map[String, V] and " +
            "types with a given McpSchema / McpInputCodec are accepted."
        )
      else
        report.errorAndAbort(
          s".withOutputSchema requires Out to be a case class (or Unit): $shown derives an " +
            s"outputSchema whose root is $root, but MCP structuredContent is always a JSON object, " +
            "so no result could conform. Wrap the result in a case class (e.g. `case class " +
            s"Result(value: $shown)`) or drop .withOutputSchema."
        )
    }
    schemaForTypeImpl[T](input)

  /** `None` when [[schemaFor]] renders `tpe` as a JSON object; otherwise a description of the root
    * it renders. Mirrors the dispatch order of [[schemaFor]] / [[schemaForNonContainer]]. A
    * user-supplied `McpSchema` is trusted as written.
    */
  private def nonObjectRoot(using Quotes)(tpe: quotes.reflect.TypeRepr): Option[String] =
    import quotes.reflect.*
    val customSchema = tpe.asType match
      case '[t] => Expr.summon[McpSchema[t]].isDefined
    if customSchema then None
    else
      tpe.asType match
        case '[Option[?]] => Some("a nullable anyOf (Option)")
        case '[Map[?, ?]] => None // string-keyed: an object; other keys abort in schemaFor
        case '[Either[?, ?]] => Some("a Left/Right oneOf (Either)")
        case '[Array[?]] => Some("an array")
        case '[Iterable[?]] => Some("an array")
        case _ =>
          val symbol = tpe.typeSymbol
          if tpe =:= TypeRepr.of[Unit] || tpe =:= TypeRepr.of[Any] then None
          else if symbol.fullName == "zio.json.ast.Json" then None
          else if symbol.flags.is(Flags.Enum) then Some("a string enum")
          else if symbol.flags.is(Flags.Case) then None
          else if symbol.flags.is(Flags.Sealed) && symbol.children.nonEmpty then
            Some("a oneOf (or a string enum) over the sealed hierarchy")
          else Some("a scalar")

  /** The schema of `rawTpe`. `input` is the direction (see the object documentation); it only
    * changes `required` in [[productSchema]] and is passed down unchanged.
    */
  private def schemaFor(using Quotes)(
      rawTpe: quotes.reflect.TypeRepr,
      seenProducts: List[quotes.reflect.TypeRepr],
      input: Boolean
  )(using path: Path): Expr[Json] =
    import quotes.reflect.*

    val tpe = rawTpe.dealias.simplified

    customSchema(tpe).getOrElse {
      tpe.asType match
        // Option first, and nullable: the decoder accepts an explicit JSON null, so the
        // advertised schema must too (parity with the old markOptionsAsNullable behavior).
        case '[Option[a]] => nullableSchema(schemaFor(TypeRepr.of[a], seenProducts, input))
        // Map MUST precede Iterable: quoted type patterns match by conformance and
        // Map[String, V] <: Iterable[(String, V)] — the Iterable case would otherwise
        // advertise an array-of-tuples schema the zio-json object decoder rejects.
        case '[Map[String, value]] => mapSchema(schemaFor(TypeRepr.of[value], seenProducts, input))
        case '[Map[k, v]] =>
          report.errorAndAbort(
            s"Cannot derive an MCP JSON Schema for non-string-keyed map ${tpe.show}. " +
              "JSON objects have string keys; " + (path match
                case Path.AnnotatedMethod =>
                  s"use Map[String, V], or provide a given McpInputCodec[${tpe.show}] " +
                    "(decoder + schema)."
                case Path.TypedContract =>
                  s"provide a given McpSchema[${tpe.show}] or use McpTool.withSchema."
              )
          )
        // Either mirrors zio-json's wire shape: {"Left": ...} / {"Right": ...} wrapper objects
        // (a bare payload oneOf would advertise inputs the decoder rejects).
        case '[Either[left, right]] =>
          oneOfSchema(
            List(
              objectSchema(
                List("Left" -> schemaFor(TypeRepr.of[left], seenProducts, input)),
                List("Left")
              ),
              objectSchema(
                List("Right" -> schemaFor(TypeRepr.of[right], seenProducts, input)),
                List("Right")
              )
            )
          )
        case '[List[a]] => arraySchema(schemaFor(TypeRepr.of[a], seenProducts, input))
        case '[Seq[a]] => arraySchema(schemaFor(TypeRepr.of[a], seenProducts, input))
        case '[Vector[a]] => arraySchema(schemaFor(TypeRepr.of[a], seenProducts, input))
        case '[Set[a]] => uniqueArraySchema(schemaFor(TypeRepr.of[a], seenProducts, input))
        case '[Array[a]] => arraySchema(schemaFor(TypeRepr.of[a], seenProducts, input))
        case '[Iterable[a]] => arraySchema(schemaFor(TypeRepr.of[a], seenProducts, input))
        case _ => schemaForNonContainer(tpe, seenProducts, input)
    }

  private def schemaForNonContainer(using Quotes)(
      tpe: quotes.reflect.TypeRepr,
      seenProducts: List[quotes.reflect.TypeRepr],
      input: Boolean
  )(using path: Path): Expr[Json] =
    import quotes.reflect.*

    val symbol = tpe.typeSymbol
    val fullName = symbol.fullName

    if tpe =:= TypeRepr.of[String] || tpe =:= TypeRepr.of[Char] then scalarSchema("string")
    else if tpe =:= TypeRepr.of[Boolean] then scalarSchema("boolean")
    else if List(
        TypeRepr.of[Byte],
        TypeRepr.of[Short],
        TypeRepr.of[Int],
        TypeRepr.of[Long],
        TypeRepr.of[BigInt]
      ).exists(candidate => tpe =:= candidate)
    then scalarSchema("integer")
    else if List(TypeRepr.of[Float], TypeRepr.of[Double], TypeRepr.of[BigDecimal])
        .exists(candidate => tpe =:= candidate)
    then scalarSchema("number")
    else if tpe =:= TypeRepr.of[Unit] then objectSchema(Nil, Nil)
    else if fullName == "java.util.UUID" then formattedStringSchema("uuid")
    else if fullName == "java.net.URI" || fullName == "java.net.URL" then
      formattedStringSchema("uri")
    else if fullName == "java.time.LocalDate" then formattedStringSchema("date")
    else if Set(
        "java.time.Instant",
        "java.time.LocalDateTime",
        "java.time.OffsetDateTime",
        "java.time.ZonedDateTime"
      ).contains(fullName)
    then formattedStringSchema("date-time")
    else if fullName == "java.time.LocalTime" || fullName == "java.time.OffsetTime" then
      formattedStringSchema("time")
    else if fullName == "java.time.Duration" || fullName == "java.time.Period" then
      formattedStringSchema("duration")
    // By name, not through the symbol: on Scala.js and Scala Native `java.time` may come from a
    // library that does not declare these as Java enums. The names are what zio-json's decoders
    // for the two types accept (they also accept other letter cases; the schema lists the
    // canonical spelling, which is what input validation then requires).
    else if fullName == "java.time.DayOfWeek" then stringEnumSchema(DayOfWeekNames)
    else if fullName == "java.time.Month" then stringEnumSchema(MonthNames)
    else if Set(
        "java.time.MonthDay",
        "java.time.Year",
        "java.time.YearMonth",
        "java.time.ZoneId",
        "java.time.ZoneOffset",
        "java.util.Currency",
        "scala.Symbol"
      ).contains(fullName)
    then scalarSchema("string")
    else if fullName == "zio.json.ast.Json" || tpe =:= TypeRepr.of[Any] then '{ Json.Obj() }
    else if symbol.flags.is(Flags.Enum) then enumSchema(tpe)
    else if symbol.flags.is(Flags.Case) then productSchema(tpe, seenProducts, input)
    else if symbol.flags.is(Flags.Sealed) && symbol.children.nonEmpty then
      sumSchema(tpe, seenProducts, input)
    else if symbol.isTypeParam then
      report.errorAndAbort(
        s"Cannot derive an MCP JSON Schema for unresolved type parameter ${tpe.show} — a " +
          "generic sealed trait's child schemas cannot be resolved through the parent's type " +
          "arguments. Provide a given McpInputCodec (decoder + schema) or McpSchema for the " +
          "sealed trait itself; note zio-json decoding of nested sealed traits also needs a " +
          "user-supplied JsonDecoder, so McpInputCodec covers both in one given."
      )
    else
      report.errorAndAbort(
        s"Cannot derive an MCP JSON Schema for ${tpe.show}. " + (path match
          case Path.AnnotatedMethod =>
            s"Define a given McpInputCodec[${tpe.show}] (decoder + schema in one value): an " +
              "annotated parameter needs both, so a McpSchema alone is not enough."
          case Path.TypedContract =>
            s"Define a given McpInputCodec[${tpe.show}] (decoder + schema), a given " +
              s"McpSchema[${tpe.show}] (schema only), or construct the tool with " +
              "McpTool.withSchema."
        )
      )

  /** The object schema of a case class. A field is in `required` unless it is an `Option` or, for
    * an input schema only, has a Scala default the derived decoder applies when the key is absent
    * ([[MacroUtils.decodedDefaultFields]]). An output schema keeps a defaulted field in `required`:
    * the encoder always writes it. `@Param(required = ...)` is applied on top by
    * [[MacroUtils.injectSchemaMetadata]].
    */
  private def productSchema(using Quotes)(
      tpe: quotes.reflect.TypeRepr,
      seenProducts: List[quotes.reflect.TypeRepr],
      input: Boolean
  )(using path: Path): Expr[Json] =
    import quotes.reflect.*

    val symbol = tpe.typeSymbol
    // Guard keys on the APPLIED type (=:=), not the bare symbol: Wrapper[Wrapper[Int]] revisits
    // the Wrapper symbol at a different argument and is finite, not recursive.
    if seenProducts.exists(_ =:= tpe) then
      report.errorAndAbort(
        s"Recursive type ${tpe.show} cannot be inlined into an MCP tool schema. " + (path match
          case Path.AnnotatedMethod =>
            s"Provide a given McpInputCodec[${tpe.show}] (decoder + schema in one value)."
          case Path.TypedContract =>
            s"Provide a given McpSchema[${tpe.show}] or use McpTool.withSchema."
        )
      )

    val (fields, required) = productMembers(tpe, seenProducts, input)
    objectSchema(fields, required)

  /** The properties of the case class `tpe` and the names among them that are required. */
  private def productMembers(using Quotes)(
      tpe: quotes.reflect.TypeRepr,
      seenProducts: List[quotes.reflect.TypeRepr],
      input: Boolean
  )(using path: Path): (List[(String, Expr[Json])], List[String]) =
    val symbol = tpe.typeSymbol
    val fields = symbol.caseFields.map { field =>
      field.name -> schemaFor(tpe.memberType(field), tpe :: seenProducts, input)
    }
    val defaulted = if input then MacroUtils.decodedDefaultFields(symbol) else Set.empty[String]
    val required = symbol.caseFields.collect {
      case field if !isOption(tpe.memberType(field)) && !defaulted.contains(field.name) =>
        field.name
    }
    (fields, required)

  private def enumSchema(using Quotes)(tpe: quotes.reflect.TypeRepr): Expr[Json] =
    import quotes.reflect.*

    // A Java enum's constants come from its declared fields: `children` is not in a stable
    // order for a Java class (see EnumTypeCollector.javaEnumConstants).
    val cases =
      if EnumTypeCollector.isJavaEnum(tpe) then EnumTypeCollector.javaEnumConstants(tpe.typeSymbol)
      else tpe.typeSymbol.children
    val casesWithParameters = cases.filter(_.caseFields.nonEmpty)
    if cases.isEmpty || casesWithParameters.nonEmpty then
      report.errorAndAbort(
        s"Only singleton-case Scala 3 enums derive as string schemas automatically: ${tpe.show}. " +
          s"Provide a given McpInputCodec[${tpe.show}] for an enum with parameterized cases."
      )

    stringEnumSchema(cases.map(enumCaseName))

  private val DayOfWeekNames: List[String] =
    List("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")

  private val MonthNames: List[String] = List(
    "JANUARY",
    "FEBRUARY",
    "MARCH",
    "APRIL",
    "MAY",
    "JUNE",
    "JULY",
    "AUGUST",
    "SEPTEMBER",
    "OCTOBER",
    "NOVEMBER",
    "DECEMBER"
  )

  private def stringEnumSchema(values: List[String])(using Quotes): Expr[Json] =
    val valueExprs = values.map(value => '{ Json.Str(${ Expr(value) }) })
    '{
      Json.Obj(
        "type" -> Json.Str("string"),
        "enum" -> Json.Arr(${ Varargs(valueExprs) }*)
      )
    }

  /** The schema of a sealed hierarchy, in the shape zio-json's derived codec reads and writes.
    *
    * It is built from the inputs zio-json's derivation takes (`JsonDecoderDerivation.split` /
    * `JsonEncoderDerivation.split`), read by [[sumCodecSettings]]:
    *   - the cases are the LEAVES of the hierarchy: zio-json looks through a sealed trait nested in
    *     another one, so `sealed trait Sub extends Shape` contributes its own cases and no `"Sub"`
    *     level;
    *   - a case is called by its `@jsonHint`, or else by its class name passed through the
    *     hierarchy's `@jsonHintNames` format (or the configuration's `sumTypeMapping`);
    *   - with a discriminator field (`@jsonDiscriminator("type")`, or the configuration's
    *     `sumTypeHandling`): a `oneOf` of the cases' own objects, each with the case name as the
    *     constant value of that field, `{"type": "Circle", "radius": 1}`, and `{"type": "Origin"}`
    *     for a case object;
    *   - without one, a hierarchy of case objects only is a string enumeration of the case names
    *     (`"Fast"`), unless the configuration sets `enumValuesAsStrings = false`;
    *   - any other hierarchy is a `oneOf` of single-key wrapper objects, `{"Circle": {...}}`, with
    *     `{}` as the payload of a case object.
    */
  private def sumSchema(using Quotes)(
      tpe: quotes.reflect.TypeRepr,
      seenProducts: List[quotes.reflect.TypeRepr],
      input: Boolean
  )(using path: Path): Expr[Json] =
    import quotes.reflect.*

    def leaves(symbol: Symbol): List[Symbol] =
      symbol.children.flatMap { child =>
        val nestedHierarchy = child.isClassDef && child.flags.is(Flags.Sealed) &&
          !child.flags.is(Flags.Case) && !child.flags.is(Flags.Module) &&
          !child.flags.is(Flags.Enum) && child.children.nonEmpty
        if nestedHierarchy then leaves(child) else List(child)
      }.distinct

    val settings = sumCodecSettings(tpe, input)

    // A case object is listed by its term symbol; the annotation is on its module class.
    def caseName(child: Symbol): String =
      val annotated =
        if child.flags.is(Flags.Module) && !child.isClassDef then child.moduleClass else child
      zioJsonStringAnnotation(annotated, "jsonHint")
        .orElse(zioJsonStringAnnotation(child, "jsonHint"))
        .getOrElse(settings.caseNames(enumCaseName(child)))

    val cases = leaves(tpe.typeSymbol)
    settings.discriminator match
      case Some(discriminator) =>
        val variants = cases.map { child =>
          val tag = discriminator -> '{ Json.Obj("const" -> Json.Str(${ Expr(caseName(child)) })) }
          if child.flags.is(Flags.Module) then taggedObjectSchema(List(tag), List(discriminator))
          else if child.flags.is(Flags.Case) then
            val childTpe = child.typeRef
            if (tpe :: seenProducts).exists(_ =:= childTpe) then
              // Reports the recursion with the usual message.
              schemaFor(childTpe, tpe :: seenProducts, input)
            else
              val (fields, required) = productMembers(childTpe, tpe :: seenProducts, input)
              taggedObjectSchema(tag :: fields, discriminator :: required)
          else schemaFor(child.typeRef, tpe :: seenProducts, input)
        }
        oneOfSchema(variants)
      case None if settings.enumValuesAsStrings && cases.forall(_.flags.is(Flags.Module)) =>
        stringEnumSchema(cases.map(caseName))
      case None =>
        val variants = cases.map { child =>
          val name = caseName(child)
          val payload =
            if child.flags.is(Flags.Module) then objectSchema(Nil, Nil)
            else schemaFor(child.typeRef, tpe :: seenProducts, input)
          objectSchema(List(name -> payload), List(name))
        }
        oneOfSchema(variants)

  /** What decides the wire shape of a derived sum codec, as far as the schema needs it. */
  private final case class SumCodecSettings(
      discriminator: Option[String],
      caseNames: String => String,
      enumValuesAsStrings: Boolean
  )

  /** The settings the codec of the sealed hierarchy `tpe` is derived with.
    *
    * zio-json takes them from the annotations of the hierarchy and, where an annotation is absent,
    * from a `JsonCodecConfiguration`. Which configuration applies depends on who derives the codec:
    *   - Input, decoder derived by this library ([[ZioJsonEnumDerivation.deriveDecoderImpl]]): it
    *     calls `DeriveJsonDecoder.gen[T](using mirror)`, the overload that is bound to
    *     `JsonCodecConfiguration.default`. A configuration given at the call site does not reach
    *     it, so the defaults are the truth.
    *   - Output, encoder derived by this library ([[ZioJsonEnumDerivation.deriveEncoderImpl]]): it
    *     passes the `JsonCodecConfiguration` given where the tool is declared. That is a run-time
    *     value: unless it is zio-json's own default, its settings cannot be read here, and the
    *     derivation is refused instead of describing a shape the encoder may not write.
    *   - A codec the user defined: its shape is the user's. The annotations and zio-json's defaults
    *     are the best description available (what `derives JsonDecoder` produces without a
    *     configuration); anything else needs an `McpSchema`, as for every other custom codec.
    *
    * `@jsonHintNames` takes a `JsonMemberFormat`. zio-json's own formats are applied here, at
    * expansion time; a `CustomCase` function cannot be, and is refused.
    */
  private def sumCodecSettings(using Quotes)(
      tpe: quotes.reflect.TypeRepr,
      input: Boolean
  ): SumCodecSettings =
    import quotes.reflect.*

    val symbol = tpe.typeSymbol
    val shown = tpe.show

    if !input then
      val userEncoder = tpe.asType match
        case '[t] => Expr.summon[JsonEncoder[t]].isDefined
      // Anything but zio-json's own `JsonCodecConfiguration.default`.
      val customConfiguration = Expr.summon[JsonCodecConfiguration].exists { found =>
        val instance = MacroUtils.stripTerm(found.asTerm).symbol
        !(instance.name == "default" &&
          instance.maybeOwner.fullName.stripSuffix("$") == "zio.json.JsonCodecConfiguration")
      }
      if !userEncoder && customConfiguration then
        report.errorAndAbort(
          s"Cannot derive an MCP JSON Schema for the sealed hierarchy $shown: a given " +
            "zio.json.JsonCodecConfiguration is in scope, and the encoder derived for the " +
            "hierarchy follows it (sumTypeHandling, sumTypeMapping, enumValuesAsStrings), but its " +
            "settings are not known when the schema is derived. Define a given " +
            s"zio.json.JsonEncoder[$shown] together with a given McpSchema[$shown] (or a given " +
            s"McpInputCodec[$shown], decoder + schema, when the type is also an argument), or " +
            "state the shape with @jsonDiscriminator / @jsonHintNames on the hierarchy and keep " +
            "the configuration out of scope where the tool is declared."
        )

    val caseNames: String => String =
      symbol.annotations.find(_.tpe.typeSymbol.fullName == "zio.json.jsonHintNames") match
        case None => identity
        case Some(Apply(_, List(argument))) =>
          val format = argument match
            case NamedArg(_, value) => value
            case value => value
          // A member of an object is spelled with the module class's `$` (`ziojson_03$.SnakeCase`).
          MacroUtils.stripTerm(format).tpe.termSymbol.fullName.replace("$.", ".") match
            case "zio.json.SnakeCase" => zio.json.SnakeCase
            case "zio.json.CamelCase" => zio.json.CamelCase
            case "zio.json.PascalCase" => zio.json.PascalCase
            case "zio.json.KebabCase" => zio.json.KebabCase
            case "zio.json.IdentityFormat" => zio.json.IdentityFormat
            case "zio.json.ziojson_03.SnakeCase" => zio.json.ziojson_03.SnakeCase
            case "zio.json.ziojson_03.KebabCase" => zio.json.ziojson_03.KebabCase
            case _ =>
              report.errorAndAbort(
                s"Cannot derive an MCP JSON Schema for the sealed hierarchy $shown: the format of " +
                  s"@jsonHintNames (${format.show}) is not one of zio-json's own (SnakeCase, " +
                  "CamelCase, PascalCase, KebabCase, IdentityFormat), so the case names the codec " +
                  "uses are not known when the schema is derived. Name the cases with @jsonHint, " +
                  s"or provide a given McpInputCodec[$shown] (decoder + schema)."
              )
        case Some(other) =>
          report.errorAndAbort(
            s"Cannot derive an MCP JSON Schema for the sealed hierarchy $shown: unexpected " +
              s"@jsonHintNames annotation ${other.show}. Provide a given McpInputCodec[$shown]."
          )

    SumCodecSettings(
      discriminator = zioJsonStringAnnotation(symbol, "jsonDiscriminator"),
      caseNames = caseNames,
      enumValuesAsStrings = true
    )

  /** The string argument of the zio-json annotation `name` (`jsonDiscriminator`, `jsonHint`) on
    * `symbol`, when it carries one: a literal or a `final val` constant, positional or named. An
    * argument that is not a compile-time constant is an error: the schema could not state the name
    * the codec uses.
    */
  private def zioJsonStringAnnotation(using Quotes)(
      symbol: quotes.reflect.Symbol,
      name: String
  ): Option[String] =
    import quotes.reflect.*
    symbol.annotations.find(_.tpe.typeSymbol.fullName == s"zio.json.$name").map { annotation =>
      val argument = annotation match
        case Apply(_, List(NamedArg(_, value))) => Some(value)
        case Apply(_, List(value)) => Some(value)
        case _ => None
      argument
        .flatMap(value => MacroUtils.constantOf(value) { case StringConstant(text) => text })
        .getOrElse(
          report.errorAndAbort(
            s"Cannot derive an MCP JSON Schema for ${symbol.fullName}: the argument of @$name is " +
              s"not a compile-time constant (${annotation.show}). Use a string literal or a " +
              "`final val` constant, or provide a given McpInputCodec (decoder + schema) for the " +
              "type."
          )
        )
    }

  /** An object schema with the given properties, all of `required` required, and no statement about
    * further properties (a discriminated case).
    */
  private def taggedObjectSchema(fields: List[(String, Expr[Json])], required: List[String])(using
      Quotes
  ): Expr[Json] =
    val fieldExprs = fields.map { case (name, schema) => '{ ${ Expr(name) } -> $schema } }
    val requiredExprs = required.map(name => '{ Json.Str(${ Expr(name) }) })
    '{
      Json.Obj(
        "type" -> Json.Str("object"),
        "properties" -> Json.Obj(${ Varargs(fieldExprs) }*),
        "required" -> Json.Arr(${ Varargs(requiredExprs) }*)
      )
    }

  private def customSchema(using Quotes)(tpe: quotes.reflect.TypeRepr): Option[Expr[Json]] =
    tpe.asType match
      case '[t] =>
        Expr.summon[McpSchema[t]].map { schema =>
          val typeName = Expr(tpe.show)
          '{
            $schema.jsonSchema
              .fromJson[Json]
              .fold(
                error =>
                  throw new IllegalArgumentException(
                    "Invalid McpSchema JSON for " + $typeName + ": " + error
                  ),
                identity
              )
          }
        }

  private def isOption(using Quotes)(rawTpe: quotes.reflect.TypeRepr): Boolean =
    rawTpe.dealias.simplified.asType match
      case '[Option[?]] => true
      case _ => false

  private def enumCaseName(using Quotes)(symbol: quotes.reflect.Symbol): String =
    symbol.name.stripSuffix("$")

  private def scalarSchema(kind: String)(using Quotes): Expr[Json] =
    '{ Json.Obj("type" -> Json.Str(${ Expr(kind) })) }

  private def formattedStringSchema(format: String)(using Quotes): Expr[Json] =
    '{
      Json.Obj(
        "type" -> Json.Str("string"),
        "format" -> Json.Str(${ Expr(format) })
      )
    }

  private def nullableSchema(inner: Expr[Json])(using Quotes): Expr[Json] =
    '{ Json.Obj("anyOf" -> Json.Arr($inner, Json.Obj("type" -> Json.Str("null")))) }

  private def arraySchema(items: Expr[Json])(using Quotes): Expr[Json] =
    '{ Json.Obj("type" -> Json.Str("array"), "items" -> $items) }

  private def uniqueArraySchema(items: Expr[Json])(using Quotes): Expr[Json] =
    '{
      Json.Obj(
        "type" -> Json.Str("array"),
        "items" -> $items,
        "uniqueItems" -> Json.Bool(true)
      )
    }

  private def mapSchema(values: Expr[Json])(using Quotes): Expr[Json] =
    '{ Json.Obj("type" -> Json.Str("object"), "additionalProperties" -> $values) }

  private def oneOfSchema(variants: List[Expr[Json]])(using Quotes): Expr[Json] =
    '{ Json.Obj("oneOf" -> Json.Arr(${ Varargs(variants) }*)) }

  private def objectSchema(fields: List[(String, Expr[Json])], required: List[String])(using
      Quotes
  ): Expr[Json] =
    val fieldExprs = fields.map { case (name, schema) => '{ ${ Expr(name) } -> $schema } }
    val requiredExprs = required.map(name => '{ Json.Str(${ Expr(name) }) })

    // additionalProperties:false is emitted ONLY for truly empty schemas (Unit, case-object
    // payloads) — the long-standing Unit shape. Any schema with fields omits it, required or
    // not: the zio-json decoders accept unknown fields by default, and the advertised contract
    // must not be stricter than what decode enforces.
    if fields.isEmpty then
      '{
        Json.Obj(
          "type" -> Json.Str("object"),
          "properties" -> Json.Obj(),
          "additionalProperties" -> Json.Bool(false)
        )
      }
    else if required.isEmpty then
      '{
        Json.Obj(
          "type" -> Json.Str("object"),
          "properties" -> Json.Obj(${ Varargs(fieldExprs) }*)
        )
      }
    else
      '{
        Json.Obj(
          "type" -> Json.Str("object"),
          "properties" -> Json.Obj(${ Varargs(fieldExprs) }*),
          "required" -> Json.Arr(${ Varargs(requiredExprs) }*)
        )
      }
end JsonSchemaMacro
