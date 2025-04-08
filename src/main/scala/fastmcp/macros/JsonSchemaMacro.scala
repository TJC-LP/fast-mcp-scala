package fastmcp.macros

import scala.quoted.*
import sttp.apispec.Schema as ASchema
import sttp.apispec.circe.*
import sttp.tapir.*
import sttp.tapir.docs.apispec.schema.*
import sttp.tapir.generic.auto.*
import io.circe.Json
import io.circe.syntax.*
import sttp.tapir.Schema.SName
import sttp.tapir.SchemaType.*
import sttp.tapir.SchemaType.SProductField

import scala.annotation.tailrec

object JsonSchemaMacro:

  /**
   * Produces a JSON schema describing the parameters of the given function.
   * Nested case classes will appear in `$defs`.
   *
   * Example usage:
   * {{{
   *   def createUser(name: String, age: Int, email: Option[String], tags: List[String], test: Test): Unit = ()
   *   val schemaJson = JsonSchemaMacro.schemaForFunctionArgs(createUser)
   *   println(schemaJson.spaces2)
   * }}}
   */
  inline def schemaForFunctionArgs[F](inline fn: F): Json =
    ${ schemaForFunctionArgsImpl('fn) }

  private def schemaForFunctionArgsImpl[F: Type](fn: Expr[F])(using Quotes): Expr[Json] = {
    import quotes.reflect.*
    
    // =========== HELPER METHOD ===========

    /**
     * Optionally give a name to a Tapir Schema[T]. If T is a product/sum type, Tapir
     * can then track it in `$defs`, referencing it by that name instead of the raw FQN.
     * For instance: .name(SName("Test")) => references #/$defs/Test
     */
    def maybeAssignNameToSchema[T: Type](originalSchema: Expr[Schema[T]]): Expr[Schema[T]] = {
      val tpeRepr = TypeRepr.of[T]
      val tpeSym = tpeRepr.typeSymbol

      // If T is a case class or a Scala 3 "product",
      // we can name it using e.g. the short name of the type (the symbol name).
      // Otherwise we just return the schema unmodified.
      val isProduct = tpeSym.isClassDef && tpeSym.caseFields.nonEmpty

      if (isProduct) {
        val shortName = tpeSym.name // e.g. "Test"
        '{ $originalSchema.name(SName(${ Expr(shortName) })) }
      } else {
        // not a product type, or no fields => no special naming
        originalSchema
      }
    }

    /**
     * Recursively extracts the parameter list (paramName + paramType).
     * If we detect a function method reference or partial application, we might pick up the real param names.
     */
    @tailrec
    def extractParams(tpe: TypeRepr, maybeParamNames: Option[List[String]]): List[(String, TypeRepr)] = {
      tpe.widen match {
        case MethodType(paramNames, paramTypes, _) =>
          // e.g. def createUser(name: String, age: Int): ...
          paramNames.zip(paramTypes)

        case PolyType(_, _, resType) =>
          // e.g. def createUser[A](...) => unwrap
          extractParams(resType, maybeParamNames)

        case AppliedType(fnType, argTypes) if fnType.typeSymbol.fullName.matches("scala.Function\\d+") =>
          // it's a FunctionN => paramTpes are all but the last (return type)
          val paramTpes = argTypes.dropRight(1)
          val names = maybeParamNames match {
            case Some(realNames) if realNames.length == paramTpes.length =>
              realNames
            case Some(realNames) =>
              report.warning(
                s"Parameter name count mismatch for function type ${tpe.show}. " +
                  s"Expected ${paramTpes.length}, found ${realNames.length}. Falling back to argN."
              )
              (0 until paramTpes.size).map(i => s"arg$i").toList
            case None =>
              (0 until paramTpes.size).map(i => s"arg$i").toList
          }
          names.zip(paramTpes)

        case other =>
          report.errorAndAbort(s"Expected a method/function type but found: ${other.show}")
      }
    }

    /**
     * Tries to pull real param names from the AST if the user wrote e.g.
     * `schemaForFunctionArgs(createUser)` or `schemaForFunctionArgs(createUser(_,_,_))`.
     */
    @tailrec
    def maybeRealParamNames(fnTerm: Term): Option[List[String]] = fnTerm match {
      case Inlined(_, _, inner) =>
        maybeRealParamNames(inner)

      case ident @ Ident(_) =>
        if (ident.symbol.isDefDef)
          ident.symbol.paramSymss.headOption.map(_.map(_.name))
        else None

      case sel @ Select(_, _) =>
        if (sel.symbol.isDefDef)
          sel.symbol.paramSymss.headOption.map(_.map(_.name))
        else None

      case Closure(methRef, _) =>
        if (methRef.symbol.isDefDef)
          methRef.symbol.paramSymss.headOption.map(_.map(_.name))
        else None

      case Block(_, expr) =>
        maybeRealParamNames(expr)

      case _ =>
        // can't find param names -> fallback
        None
    }

    // =========== MAIN MACRO LOGIC ===========

    val fnTerm = fn.asTerm
    val fnType = fnTerm.tpe

    // gather param names & types
    val paramNamesOpt = maybeRealParamNames(fnTerm)
    val params: List[(String, TypeRepr)] = extractParams(fnType, paramNamesOpt)

    // For each param, we summon the Tapir Schema[t], optionally name it if t is a product type.
    val productFieldsExpr: List[Expr[SProductField[Unit]]] = params.map { case (paramName, paramType) =>
      paramType.asType match {
        case '[t] =>
          // Summon the schema at call site
          val rawSchemaExpr = Expr.summon[Schema[t]].getOrElse {
            report.errorAndAbort(
              s"No Tapir Schema found for parameter '$paramName' of type: ${Type.show[t]}"
            )
          }

          // Possibly name the schema (for case classes). E.g. => .name(SName("Test"))
          val namedSchemaExpr = maybeAssignNameToSchema[t](rawSchemaExpr)

          // Return an SProductField referencing that named schema
          '{
            SProductField[Unit, t](
              FieldName(${ Expr(paramName) }),
              $namedSchemaExpr,
              (_: Unit) => Some(???.asInstanceOf[t])
            )
          }
      }
    }

    // Combine fields => Single SProduct => top-level schema
    val fieldsListExpr: Expr[List[SProductField[Unit]]] = Expr.ofList(productFieldsExpr)
    val productSchemaExpr: Expr[Schema[Unit]] = '{
      // Optionally name the top-level schema so that it also can appear in $defs if nested in a bigger schema
      Schema(
        SProduct[Unit](fields = $fieldsListExpr)
      ).name(SName("FunctionArgs"))
    }

    // Convert to Apispec + Circe JSON
    '{
      // Convert Tapir schema -> apispec schema
      val apispecSchema = TapirSchemaToJsonSchema($productSchemaExpr, markOptionsAsNullable = true)
      apispecSchema.asJson
    }
  }

