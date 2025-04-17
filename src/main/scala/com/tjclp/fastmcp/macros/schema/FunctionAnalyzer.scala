package com.tjclp.fastmcp.macros.schema

import scala.annotation.tailrec
import scala.quoted.*

/** Helper for analyzing functions at compile time to extract parameter information. Part of the
  * JsonSchemaMacro refactoring to reduce compilation time.
  */
object FunctionAnalyzer:

  /** Recursively extracts the parameter list (paramName + paramType). If we detect a function
    * method reference or partial application, we might pick up the real parameter names.
    */
  @tailrec
  def extractParams(using quotes: Quotes)(
      tpe: quotes.reflect.TypeRepr,
      maybeParamNames: Option[List[String]]
  ): List[(String, quotes.reflect.TypeRepr)] =
    import quotes.reflect.*

    tpe.widen match
      case MethodType(paramNames, paramTypes, _) =>
        paramNames.zip(paramTypes)

      case PolyType(_, _, resType) =>
        extractParams(resType, maybeParamNames)

      case AppliedType(fnType, argTypes)
          if fnType.typeSymbol.fullName.startsWith("scala.Function") =>
        val paramTpes = argTypes.dropRight(1)
        val names = maybeParamNames match
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

        names.zip(paramTpes)

      case other =>
        report.errorAndAbort(s"Expected a method/function type but found: ${other.show}")

  /** Tries to pull real parameter names from the AST if the user wrote e.g.
    * `schemaForFunctionArgs(createUser)` or `schemaForFunctionArgs(createUser(_,_,_))`.
    */
  @tailrec
  def maybeRealParamNames(using quotes: Quotes)(fnTerm: quotes.reflect.Term): Option[List[String]] =
    import quotes.reflect.*

    fnTerm match
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
        None
