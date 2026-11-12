package com.tjclp.fastmcp.macros

import scala.quoted.*

import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.McpSchema
import com.tjclp.fastmcp.macros.schema.FunctionAnalyzer

/** Native JSON Schema derivation for annotation parameters and typed contracts.
  *
  * The macro emits the small JSON Schema subset MCP tools need directly from Scala types. It
  * supports primitives, options, collections, maps, Scala 3 enums, products, and tagged sums. A
  * user-provided [[com.tjclp.fastmcp.core.McpInputCodec]] is the escape hatch for domain types
  * whose wire representation cannot be inferred from their Scala shape.
  */
object JsonSchemaMacro:

  /** Produces a JSON schema describing the parameters of the given function. */
  inline def schemaForFunctionArgs[F](inline fn: F): Json =
    ${ schemaForFunctionArgsImpl('fn, '{ Nil }) }

  /** Produces a JSON schema describing the parameters of the given function, excluding specified
    * parameters. This is primarily used to omit an injected `McpContext`.
    */
  inline def schemaForFunctionArgs[F](inline fn: F, inline exclude: List[String]): Json =
    ${ schemaForFunctionArgsImpl('fn, 'exclude) }

  /** Produces a JSON schema describing a single request or result type `T`. */
  inline def schemaForType[T]: Json =
    ${ schemaForTypeImpl[T] }

  /** [[schemaForType]] for a typed tool's `In`, guarded: MCP `arguments` is always a JSON object,
    * so a type whose schema root is not an object (`String`, `Int`, a collection, `Option`,
    * `Either`, an enum, a sealed trait) would advertise a tool no client can ever call — a
    * compile-time error.
    */
  inline def schemaForToolInput[T]: Json =
    ${ objectRootSchemaImpl[T](input = true) }

  /** [[schemaForType]] for a typed tool's `Out` under `.withOutputSchema`, guarded the same way:
    * `structuredContent` is always a JSON object, so a non-object root could never conform.
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

    val fields = params.map { case (name, tpe) => name -> schemaFor(tpe, Nil) }
    val required = params.collect { case (name, tpe) if !isOption(tpe) => name }
    val rawSchema = objectSchema(fields, required)

    val metadataEntries = params.map { case (name, tpe) =>
      tpe.asType match
        case '[t] =>
          val metadata = MacroUtils.schemaMetadataForType[t]
          '{ ${ Expr(name) } -> $metadata }
    }
    val metadata = '{ SchemaMetadataNode(properties = Map(${ Varargs(metadataEntries) }*)) }

    '{ MacroUtils.injectSchemaMetadata($rawSchema, $metadata) }

  private def schemaForTypeImpl[T: Type](using Quotes): Expr[Json] =
    val rawSchema = schemaFor(quotes.reflect.TypeRepr.of[T], Nil)
    val metadata = MacroUtils.schemaMetadataForType[T]
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
    schemaForTypeImpl[T]

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
            Some("a oneOf over the sealed hierarchy")
          else Some("a scalar")

  private def schemaFor(using Quotes)(
      rawTpe: quotes.reflect.TypeRepr,
      seenProducts: List[quotes.reflect.TypeRepr]
  ): Expr[Json] =
    import quotes.reflect.*

    val tpe = rawTpe.dealias.simplified

    customSchema(tpe).getOrElse {
      tpe.asType match
        // Option first, and nullable: the decoder accepts an explicit JSON null, so the
        // advertised schema must too (parity with the old markOptionsAsNullable behavior).
        case '[Option[a]] => nullableSchema(schemaFor(TypeRepr.of[a], seenProducts))
        // Map MUST precede Iterable: quoted type patterns match by conformance and
        // Map[String, V] <: Iterable[(String, V)] — the Iterable case would otherwise
        // advertise an array-of-tuples schema the zio-json object decoder rejects.
        case '[Map[String, value]] => mapSchema(schemaFor(TypeRepr.of[value], seenProducts))
        case '[Map[k, v]] =>
          report.errorAndAbort(
            s"Cannot derive an MCP JSON Schema for non-string-keyed map ${tpe.show}. " +
              s"JSON objects have string keys; provide a given McpSchema[${tpe.show}] or use " +
              "McpTool.withSchema."
          )
        // Either mirrors zio-json's wire shape: {"Left": ...} / {"Right": ...} wrapper objects
        // (a bare payload oneOf would advertise inputs the decoder rejects).
        case '[Either[left, right]] =>
          oneOfSchema(
            List(
              objectSchema(
                List("Left" -> schemaFor(TypeRepr.of[left], seenProducts)),
                List("Left")
              ),
              objectSchema(
                List("Right" -> schemaFor(TypeRepr.of[right], seenProducts)),
                List("Right")
              )
            )
          )
        case '[List[a]] => arraySchema(schemaFor(TypeRepr.of[a], seenProducts))
        case '[Seq[a]] => arraySchema(schemaFor(TypeRepr.of[a], seenProducts))
        case '[Vector[a]] => arraySchema(schemaFor(TypeRepr.of[a], seenProducts))
        case '[Set[a]] => uniqueArraySchema(schemaFor(TypeRepr.of[a], seenProducts))
        case '[Array[a]] => arraySchema(schemaFor(TypeRepr.of[a], seenProducts))
        case '[Iterable[a]] => arraySchema(schemaFor(TypeRepr.of[a], seenProducts))
        case _ => schemaForNonContainer(tpe, seenProducts)
    }

  private def schemaForNonContainer(using Quotes)(
      tpe: quotes.reflect.TypeRepr,
      seenProducts: List[quotes.reflect.TypeRepr]
  ): Expr[Json] =
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
    else if Set(
        "java.time.DayOfWeek",
        "java.time.Month",
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
    else if symbol.flags.is(Flags.Case) then productSchema(tpe, seenProducts)
    else if symbol.flags.is(Flags.Sealed) && symbol.children.nonEmpty then
      sumSchema(tpe, seenProducts)
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
        s"Cannot derive an MCP JSON Schema for ${tpe.show}. " +
          s"Define a given McpInputCodec[${tpe.show}] (decoder + schema), a given " +
          s"McpSchema[${tpe.show}] (schema only), or construct the tool with McpTool.withSchema."
      )

  private def productSchema(using Quotes)(
      tpe: quotes.reflect.TypeRepr,
      seenProducts: List[quotes.reflect.TypeRepr]
  ): Expr[Json] =
    import quotes.reflect.*

    val symbol = tpe.typeSymbol
    // Guard keys on the APPLIED type (=:=), not the bare symbol: Wrapper[Wrapper[Int]] revisits
    // the Wrapper symbol at a different argument and is finite, not recursive.
    if seenProducts.exists(_ =:= tpe) then
      report.errorAndAbort(
        s"Recursive type ${tpe.show} cannot be inlined into an MCP tool schema. " +
          s"Provide a given McpSchema[${tpe.show}] or use McpTool.withSchema."
      )

    val fields = symbol.caseFields.map { field =>
      field.name -> schemaFor(tpe.memberType(field), tpe :: seenProducts)
    }
    val required = symbol.caseFields.collect {
      case field if !isOption(tpe.memberType(field)) => field.name
    }
    objectSchema(fields, required)

  private def enumSchema(using Quotes)(tpe: quotes.reflect.TypeRepr): Expr[Json] =
    import quotes.reflect.*

    val cases = tpe.typeSymbol.children
    val casesWithParameters = cases.filter(_.caseFields.nonEmpty)
    if cases.isEmpty || casesWithParameters.nonEmpty then
      report.errorAndAbort(
        s"Only singleton-case Scala 3 enums derive as string schemas automatically: ${tpe.show}. " +
          s"Provide a given McpInputCodec[${tpe.show}] for an enum with parameterized cases."
      )

    val valueExprs = cases.map(enumCaseName).map(value => '{ Json.Str(${ Expr(value) }) })
    '{
      Json.Obj(
        "type" -> Json.Str("string"),
        "enum" -> Json.Arr(${ Varargs(valueExprs) }*)
      )
    }

  private def sumSchema(using Quotes)(
      tpe: quotes.reflect.TypeRepr,
      seenProducts: List[quotes.reflect.TypeRepr]
  ): Expr[Json] =
    import quotes.reflect.*

    val variants = tpe.typeSymbol.children.map { child =>
      val caseName = enumCaseName(child)
      val payload =
        if child.flags.is(Flags.Module) then objectSchema(Nil, Nil)
        else schemaFor(child.typeRef, tpe :: seenProducts)
      objectSchema(List(caseName -> payload), List(caseName))
    }
    oneOfSchema(variants)

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
