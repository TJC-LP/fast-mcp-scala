package com.tjclp.fastmcp.macros

import com.tjclp.fastmcp.macros.schema.FunctionAnalyzer
import com.tjclp.fastmcp.macros.schema.SchemaGenerator
import io.circe.Json
import io.circe.syntax.*
import sttp.apispec.circe.*
import sttp.tapir.*
import sttp.tapir.SchemaType.SProductField
import sttp.tapir.docs.apispec.schema.TapirSchemaToJsonSchema

import scala.quoted.*
import scala.util.Properties

/** Macro that generates JSON Schema for function parameters. The implementation is split across
  * multiple helpers to reduce compile time.
  */
object JsonSchemaMacro:

  /** Produces a JSON schema describing the parameters of the given function. Definitions (`$defs`)
    * are resolved and inlined into the `properties`. Also injects @Param annotation parameter
    * descriptions as JSON "description" fields in properties.
    *
    * Example usage:
    * {{{
    *   case class Address(street: String, city: String)
    *   case class User(name: String, address: Address)
    *   def processUser(user: User, count: Int): Unit = ()
    *   val schemaJson = JsonSchemaMacro.schemaForFunctionArgs(processUser)
    *   // schemaJson will have Address definition inlined within User property,
    *   // and any @Param descriptions injected.
    *   println(schemaJson.spaces2)
    * }}}
    */
  inline def schemaForFunctionArgs[F](inline fn: F): Json =
    ${ schemaForFunctionArgsImpl('fn, '{ Nil }) }

  /** Produces a JSON schema describing the parameters of the given function, excluding specified
    * parameters. This overload is used primarily for excluding context parameters from the schema.
    */
  inline def schemaForFunctionArgs[F](inline fn: F, inline exclude: List[String]): Json =
    ${ schemaForFunctionArgsImpl('fn, 'exclude) }

  private def schemaForFunctionArgsImpl[F: Type](fn: Expr[F], exclude: Expr[List[String]])(using
      Quotes
  ): Expr[Json] =
    import quotes.reflect.*

    // Check if we're running in a CI/test environment; use noTrace to avoid excessive macro trace
    Properties.propOrFalse("scala.util.noTrace")

    // Analyze function to extract parameters
    val fnTerm = fn.asTerm
    val fnType = fnTerm.tpe

    val paramNamesOpt = FunctionAnalyzer.maybeRealParamNames(fnTerm)
    val excludeList = exclude.valueOrAbort
    val params: List[(String, TypeRepr)] = FunctionAnalyzer
      .extractParams(fnType, paramNamesOpt)
      .filterNot(p => excludeList.contains(p._1))

    // Generate schema for each parameter
    val productFieldsExpr = params.map { case (paramName, paramType) =>
      paramType.asType match
        case '[t] => SchemaGenerator.generateProductField[t](paramName)
    }

    // Create fields list and product schema
    val fieldsListExpr: Expr[List[SProductField[Unit]]] = Expr.ofList(productFieldsExpr)
    val productSchemaExpr: Expr[Schema[Unit]] = SchemaGenerator.createArgsSchema(fieldsListExpr)

    // Convert to Apispec + Circe JSON, then resolve references
    '{
      // Convert Tapir schema -> apispec schema
      val apispecSchema = TapirSchemaToJsonSchema($productSchemaExpr, markOptionsAsNullable = true)
      // Convert apispec schema -> initial circe JSON (potentially with $defs/$ref)
      val initialJson = apispecSchema.asJson
      // Post-process the JSON to resolve and inline references
      MacroUtils.resolveJsonRefs(initialJson)
    }
end JsonSchemaMacro
