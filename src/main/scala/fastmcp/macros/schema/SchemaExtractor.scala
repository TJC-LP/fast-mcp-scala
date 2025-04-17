package fastmcp.macros.schema

import scala.quoted.*
import scala.deriving.Mirror
import sttp.tapir.Schema
import sttp.tapir.Schema.SName

/** Helper for extracting schema information from types at compile time. This is part of the
  * JsonSchemaMacro refactoring to reduce compilation time.
  */
object SchemaExtractor:

  /** Optionally gives a name to a Tapir Schema[T]. If T is a product type, Tapir can then track it
    * in `$defs`, referencing it by that name instead of the raw FQN. For instance:
    * .name(SName("Test")) => references #/$defs/Test Only applied to non-enum product types now.
    */
  def maybeAssignNameToSchema[T: Type](originalSchema: Expr[Schema[T]])(using
      Quotes
  ): Expr[Schema[T]] =
    import quotes.reflect.*
    val tpeRepr = TypeRepr.of[T]
    val tpeSym = tpeRepr.typeSymbol

    val isProduct = tpeSym.isClassDef && tpeSym.caseFields.nonEmpty

    if (isProduct) then
      val shortName = tpeSym.name // e.g. "Test"
      '{ $originalSchema.name(SName(${ Expr(shortName) })) }
    else originalSchema

  /** Checks if a type is an enum and creates appropriate schema. Returns tuple of (isEnum,
    * schemaExpr)
    */
  def createSchemaFor[T: Type](paramName: String)(using Quotes): (Boolean, Expr[Schema[T]]) =
    import quotes.reflect.*

    // --- Check if 'T' is an Enum ---
    val tpeRepr = TypeRepr.of[T]
    val tSymbol = tpeRepr.typeSymbol
    val isEnum = tSymbol.flags.is(Flags.Enum)

    // --- Get Schema Expression ---
    val schemaExpr: Expr[Schema[T]] =
      if (isEnum) then
        // Explicitly use string-based derivation for enums
        Expr.summon[Mirror.SumOf[T]].getOrElse {
          report.errorAndAbort(
            s"Cannot derive enum schema for ${Type.show[T]}: Missing Mirror.SumOf[T]. Ensure it's a standard Scala 3 enum."
          )
        }
        report.info(
          s"Detected enum type ${Type.show[T]}, using derivedEnumeration."
        ) // Optional debug info
        '{ Schema.derivedEnumeration[T].defaultStringBased }
      else
        // For non-enums, use the regular implicit summon and naming logic
        // NOTE: Requires `import sttp.tapir.generic.auto.*` at the *call site*
        val rawSchemaExpr = Expr.summon[Schema[T]].getOrElse {
          report.errorAndAbort(
            s"No Tapir Schema found for parameter '$paramName' of type: ${Type.show[T]}. Did you import sttp.tapir.generic.auto.* at the call site?"
          )
        }
        // Apply naming only to non-enum product types
        maybeAssignNameToSchema[T](rawSchemaExpr)

    (isEnum, schemaExpr)
