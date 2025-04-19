package com.tjclp.fastmcp.macros.schema

import sttp.tapir.*
import sttp.tapir.Schema.SName
import sttp.tapir.SchemaType.*

import scala.quoted.*

/** Helper for generating schemas from function parameters. Part of the JsonSchemaMacro refactoring
  * to reduce compilation time.
  */
object SchemaGenerator:

  /** Generates a product field for a parameter of a function.
    */
  def generateProductField[T: Type](paramName: String)(using Quotes): Expr[SProductField[Unit]] =

    val (_, schemaExpr) = SchemaExtractor.createSchemaFor[T](paramName)

    '{
      SProductField[Unit, T](
        FieldName(${ Expr(paramName) }),
        $schemaExpr, // Use the determined schema expression
        (_: Unit) => None
      )
    }

  /** Creates a top-level Schema[Unit] for function arguments.
    */
  def createArgsSchema(fields: Expr[List[SProductField[Unit]]])(using Quotes): Expr[Schema[Unit]] =
    '{
      Schema(
        SProduct[Unit](fields = $fields)
      ).name(SName("FunctionArgs")) // Name the top-level schema
    }
