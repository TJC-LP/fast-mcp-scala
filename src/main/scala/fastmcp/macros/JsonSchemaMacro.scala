package fastmcp.macros

import scala.quoted.*
import sttp.apispec.circe.*
import sttp.tapir.*
import sttp.tapir.docs.apispec.schema.*

import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import sttp.tapir.Schema.SName
import sttp.tapir.SchemaType.*

import scala.annotation.tailrec
import scala.deriving.Mirror // Required for derivedEnumeration check

object JsonSchemaMacro:

  /**
   * Produces a JSON schema describing the parameters of the given function.
   * Definitions (`$defs`) are resolved and inlined into the `properties`.
   * Also injects @Param annotation parameter descriptions as JSON "description" fields in properties.
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
    ${ schemaForFunctionArgsImpl('fn) }

  private def schemaForFunctionArgsImpl[F: Type](fn: Expr[F])(using Quotes): Expr[Json] = {
    import quotes.reflect.*

    // =========== HELPER METHODS ===========

    /**
     * Optionally give a name to a Tapir Schema[T]. If T is a product type, Tapir
     * can then track it in `$defs`, referencing it by that name instead of the raw FQN.
     * For instance: .name(SName("Test")) => references #/$defs/Test
     * Only applied to non-enum product types now.
     */
    def maybeAssignNameToSchema[T: Type](originalSchema: Expr[Schema[T]]): Expr[Schema[T]] = {
      val tpeRepr = TypeRepr.of[T]
      val tpeSym = tpeRepr.typeSymbol

      val isProduct = tpeSym.isClassDef && tpeSym.caseFields.nonEmpty

      if (isProduct) {
        val shortName = tpeSym.name // e.g. "Test"
        '{ $originalSchema.name(SName(${ Expr(shortName) })) }
      } else {
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
          paramNames.zip(paramTypes)

        case PolyType(_, _, resType) =>
          extractParams(resType, maybeParamNames)

        case AppliedType(fnType, argTypes) if fnType.typeSymbol.fullName.startsWith("scala.Function") =>
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

      case ident@Ident(_) =>
        if (ident.symbol.isDefDef)
          ident.symbol.paramSymss.headOption.map(_.map(_.name))
        else None

      case sel@Select(_, _) =>
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
        None
    }

    // =========== MAIN MACRO LOGIC ===========

    val fnTerm = fn.asTerm
    val fnType = fnTerm.tpe

    val paramNamesOpt = maybeRealParamNames(fnTerm)
    val params: List[(String, TypeRepr)] = extractParams(fnType, paramNamesOpt)

    val productFieldsExpr: List[Expr[SProductField[Unit]]] = params.map { case (paramName, paramType) =>
      paramType.asType match {
        case '[t] =>
          // --- Check if 't' is an Enum ---
          val tpeRepr = TypeRepr.of[t]
          val tSymbol = tpeRepr.typeSymbol
          val isEnum = tSymbol.flags.is(Flags.Enum)

          // --- Get Schema Expression ---
          val schemaExpr: Expr[Schema[t]] =
            if (isEnum) {
              // Explicitly use string-based derivation for enums
              Expr.summon[Mirror.SumOf[t]].getOrElse {
                report.errorAndAbort(s"Cannot derive enum schema for ${Type.show[t]}: Missing Mirror.SumOf[t]. Ensure it's a standard Scala 3 enum.")
              }
              report.info(s"Detected enum type ${Type.show[t]}, using derivedEnumeration.") // Optional debug info
              '{ Schema.derivedEnumeration[t].defaultStringBased }
            } else {
              // For non-enums, use the regular implicit summon and naming logic
              // NOTE: Requires `import sttp.tapir.generic.auto.*` at the *call site* (e.g., ManualServer.scala)
              val rawSchemaExpr = Expr.summon[Schema[t]].getOrElse {
                report.errorAndAbort(
                  s"No Tapir Schema found for parameter '$paramName' of type: ${Type.show[t]}. Did you import sttp.tapir.generic.auto.* at the call site?"
                )
              }
              // Apply naming only to non-enum product types
              maybeAssignNameToSchema[t](rawSchemaExpr)
            }

          // --- Create SProductField ---
          '{
            SProductField[Unit, t](
              FieldName(${ Expr(paramName) }),
              $schemaExpr, // Use the determined schema expression
              (_: Unit) => None
            )
          }
      }
    }

    val fieldsListExpr: Expr[List[SProductField[Unit]]] = Expr.ofList(productFieldsExpr)
    val productSchemaExpr: Expr[Schema[Unit]] = '{
      Schema(
        SProduct[Unit](fields = $fieldsListExpr)
      ).name(SName("FunctionArgs")) // Still useful if this schema is nested elsewhere
    }

    // Convert to Apispec + Circe JSON, then resolve references
    '{
      // Convert Tapir schema -> apispec schema
      val apispecSchema = TapirSchemaToJsonSchema($productSchemaExpr, markOptionsAsNullable = true)
      // Convert apispec schema -> initial circe JSON (potentially with $defs/$ref)
      val initialJson = apispecSchema.asJson
      // Post-process the JSON to resolve and inline references
      MacroUtils.resolveJsonRefs(initialJson)
    }
  }

end JsonSchemaMacro