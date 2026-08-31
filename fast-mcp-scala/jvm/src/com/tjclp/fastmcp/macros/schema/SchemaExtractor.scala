package com.tjclp.fastmcp.macros.schema

import scala.deriving.Mirror
import scala.quoted.*

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
        val _ = Expr.summon[Mirror.SumOf[T]].getOrElse {
          report.errorAndAbort(
            s"Cannot derive enum schema for ${Type.show[T]}: Missing Mirror.SumOf[T]. Ensure it's a standard Scala 3 enum."
          )
        }
        '{ Schema.derivedEnumeration[T].defaultStringBased }
      else
        // For non-enums, use the regular implicit summon and naming logic
        // NOTE: Requires `import sttp.tapir.generic.auto.*` at the *call site*
        def summonOrAbort: Expr[Schema[T]] = Expr.summon[Schema[T]].getOrElse {
          report.errorAndAbort(
            s"No Tapir Schema found for parameter '$paramName' of type: ${Type.show[T]}. Did you import sttp.tapir.generic.auto.* at the call site?"
          )
        }
        // Scala 3 enums nested anywhere in T's field tree would render as coproducts of empty
        // objects under generic.auto. Re-derive T's schema inside a block whose innermost scope
        // carries string-based enum schemas: the block-local givens win over the imported
        // generic.auto candidates when the derivation's per-field summons run (GH #78). The
        // macro-time summon is kept purely for its friendly missing-import diagnostic.
        //
        // User schemas always win: a local is planted only when the macro-time summon for the
        // enum finds nothing, or finds tapir's AUTO-derivation bridge (LowPrioritySchema
        // .derivedSchema over generic.auto's Derived) — a plain `.isEmpty` filter would disable
        // the fix entirely under generic.auto, whose bridge satisfies every Schema summon.
        def isAutoDerivedSchema(term: Term): Boolean =
          def fnSymbol(t: Term): Symbol = t match
            case Apply(fn, _) => fnSymbol(fn)
            case TypeApply(fn, _) => fnSymbol(fn)
            case Inlined(_, _, body) => fnSymbol(body)
            case Block(_, expr) => fnSymbol(expr)
            case other => other.symbol
          val fullName = fnSymbol(term).fullName
          fullName.startsWith("sttp.tapir.generic.auto") ||
          fullName.endsWith("LowPrioritySchema.derivedSchema")
        val nestedEnums = com.tjclp.fastmcp.macros.EnumTypeCollector
          .collectSingletonEnums(tpeRepr)
          .filter { e =>
            e.asType match
              case '[et] =>
                Expr.summon[Schema[et]] match
                  case None => true
                  case Some(existing) => isAutoDerivedSchema(existing.asTerm)
          }
        val rawSchemaExpr: Expr[Schema[T]] =
          if nestedEnums.isEmpty then summonOrAbort
          else
            val _ = summonOrAbort
            def withLocalGivens(remaining: List[TypeRepr]): Expr[Schema[T]] =
              remaining match
                case Nil => '{ scala.compiletime.summonInline[Schema[T]] }
                case e :: rest =>
                  e.asType match
                    case '[et] =>
                      '{
                        given Schema[et] = Schema.derivedEnumeration[et].defaultStringBased
                        ${ withLocalGivens(rest) }
                      }
            withLocalGivens(nestedEnums)
        // Apply naming only to non-enum product types
        maybeAssignNameToSchema[T](rawSchemaExpr)

    (isEnum, schemaExpr)
