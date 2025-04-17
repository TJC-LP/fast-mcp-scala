package com.tjclp.fastmcp.macros

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.scala.ClassTagExtensions
import com.fasterxml.jackson.module.scala.DefaultScalaModule

import scala.annotation.tailrec
import scala.quoted.*

/** Macro that converts a function f: (T1, T2, ..., TN) => R into a handler: Map[String, Any] => R
  * by using JacksonConverter to convert each Map entry to the correct type.
  */
object MapToFunctionMacro:

  // Shared Jackson mapper
  private val mapperBuilder = JsonMapper
    .builder()
    .addModule(DefaultScalaModule)
    .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)

  private val mapper: JsonMapper & ClassTagExtensions =
    mapperBuilder.build() :: ClassTagExtensions

  /** Entry point: lifts f into a Map-based handler. */
  transparent inline def callByMap[F](inline f: F): Any =
    ${ callByMapImpl('f) }

  private def callByMapImpl[F: Type](f: Expr[F])(using q: Quotes): Expr[Any] =
    import q.reflect.*

    // Holds a parameter's name and type
    case class ParamInfo(name: String, tpe: TypeRepr)

    // Extract parameter types and return type from a function term
    @tailrec
    def extractParamsAndReturnType(term: Term): (List[ParamInfo], TypeRepr) =
      val tpe = term.tpe.widen

      tpe match
        case mt: MethodType =>
          val params = mt.paramNames.zip(mt.paramTypes).map { case (name, tpe) =>
            ParamInfo(name, tpe)
          }
          (params, mt.resType)

        case pt: PolyType =>
          report.warning(
            s"Encountered PolyType ${pt.show}. Applying 'Any' which might lead to runtime issues if type parameters are needed for conversion."
          )
          val dummyAppliedTerm = term.appliedToTypes(pt.paramNames.map(_ => TypeRepr.of[Any]))
          extractParamsAndReturnType(dummyAppliedTerm) // Recurse with applied types

        case atpe @ AppliedType(base, args)
            if base.typeSymbol.fullName.startsWith("scala.Function") =>
          val paramTypes = args.init // Last type is return type
          val returnType = args.last
          // Generate fallback names initially
          val params = paramTypes.zipWithIndex.map { case (tpe, i) =>
            ParamInfo(s"arg$i", tpe)
          }.toList
          (params, returnType)

        case _ =>
          report.errorAndAbort(
            s"Couldn't extract parameters from function: ${term.show}, type: ${tpe.show}"
          )

    // Try to recover real parameter names (for method references)
    @tailrec
    def tryGetRealParamNames(term: Term): Option[List[String]] = term match
      case Inlined(_, _, inner) => tryGetRealParamNames(inner)
      case Block(_, expr) => tryGetRealParamNames(expr)
      case ident @ Ident(_) if ident.symbol.isDefDef && !ident.symbol.flags.is(Flags.Synthetic) =>
        ident.symbol.paramSymss.headOption.map(_.map(_.name))
      case select @ Select(_, _)
          if select.symbol.isDefDef && !select.symbol.flags.is(Flags.Synthetic) =>
        select.symbol.paramSymss.headOption.map(_.map(_.name))
      case closure @ Closure(meth @ Ident(_), _) if meth.symbol.isDefDef =>
        meth.symbol.paramSymss.headOption.map(_.map(_.name))
      // This case intentionally removed as it's unreachable in our code structure
      case _ => None

    // Summon JacksonConverter[T] for a parameter type
    def summonJacksonConverter(tpe: TypeRepr)(using Quotes): Expr[JacksonConverter[?]] =
      import quotes.reflect.report
      tpe.asType match
        case '[t] =>
          Expr.summon[JacksonConverter[t]] match
            case Some(conv) => conv
            case None => report.errorAndAbort(s"No JacksonConverter in scope for type: ${tpe.show}")
        case _ => report.errorAndAbort(s"Unsupported parameter type: ${tpe.show}")

    // Build code that converts each map entry using its JacksonConverter
    def buildArgConversionExpr(params: List[ParamInfo], mapExpr: Expr[Map[String, Any]])(using
        Quotes
    ): Expr[List[Any]] =
      Expr.ofList(params.map { p =>
        val nameExpr = Expr(p.name)
        val convExpr = summonJacksonConverter(p.tpe)
        val isOptionType = p.tpe match
          case AppliedType(base, _) if base.typeSymbol.fullName == "scala.Option" => true
          case _ => false
        if isOptionType then
          '{
            val key = $nameExpr
            val rawOpt: Option[Any] = $mapExpr.get(key)
            val raw: Any = rawOpt.getOrElse(None)
            $convExpr.convert(key, raw, MapToFunctionMacro.mapper)
          }.asExprOf[Any]
        else
          '{
            val key = $nameExpr
            val raw = $mapExpr.getOrElse(
              key,
              throw new NoSuchElementException("Key not found in map: " + key)
            )
            $convExpr.convert(key, raw, MapToFunctionMacro.mapper)
          }.asExprOf[Any]
      })

    // Main reflective logic
    val fnTerm = f.asTerm
    val (params, retTpe) = extractParamsAndReturnType(fnTerm)
    val namedParams = tryGetRealParamNames(fnTerm) match
      case Some(names) if names.length == params.length =>
        params.zip(names).map((pi, n) => pi.copy(name = n))
      case _ => params

    retTpe.asType match
      case '[r] =>
        '{ (map: Map[String, Any]) =>
          val fnValue = $f
          val argsList: List[Any] = ${ buildArgConversionExpr(namedParams, 'map) }
          val result = MacroUtils.invokeFunctionWithArgs(fnValue, argsList)
          result.asInstanceOf[r]
        }.asExprOf[Map[String, Any] => r]
      case _ => report.errorAndAbort(s"Unsupported return type: ${retTpe.show}")
