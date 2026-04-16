package com.tjclp.fastmcp.macros

import scala.annotation.tailrec
import scala.deriving.Mirror
import scala.quoted.*
import scala.reflect.ClassTag

/** Macro that converts a function `f: (T1, T2, ..., TN) => R` into a handler `Map[String, Any] =>
  * R` by using [[JacksonConverter]] to convert each map entry to the correct type.
  */
object MapToFunctionMacro:

  private val baseContext = JacksonConversionContext.default

  /** Entry point: lifts `f` into a Map-based handler. */
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
          extractParamsAndReturnType(dummyAppliedTerm)

        case AppliedType(base, args) if base.typeSymbol.fullName.startsWith("scala.Function") =>
          val paramTypes = args.init
          val returnType = args.last
          val params = paramTypes.zipWithIndex.map { case (paramTpe, i) =>
            ParamInfo(s"arg$i", paramTpe)
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
      case _ => None

    // Summon JacksonConverter[T] for a parameter type.
    def summonJacksonConverter(tpe: TypeRepr)(using Quotes): Expr[JacksonConverter[?]] =
      tpe.asType match
        case '[Option[a]] =>
          summonJacksonConverter(TypeRepr.of[a]) match
            case '{ $inner: JacksonConverter[a] } =>
              val ct = Expr
                .summon[ClassTag[a]]
                .getOrElse(
                  report.errorAndAbort(
                    s"No ClassTag for Option element type: ${TypeRepr.of[a].show}"
                  )
                )
              '{ DeriveJacksonConverter.containers.option[a](using $inner, $ct) }
        case '[List[a]] =>
          summonJacksonConverter(TypeRepr.of[a]) match
            case '{ $inner: JacksonConverter[a] } =>
              val ct = Expr
                .summon[ClassTag[a]]
                .getOrElse(
                  report.errorAndAbort(
                    s"No ClassTag for List element type: ${TypeRepr.of[a].show}"
                  )
                )
              '{ DeriveJacksonConverter.containers.list[a](using $inner, $ct) }
        case '[Seq[a]] =>
          summonJacksonConverter(TypeRepr.of[a]) match
            case '{ $inner: JacksonConverter[a] } =>
              val ct = Expr
                .summon[ClassTag[a]]
                .getOrElse(
                  report.errorAndAbort(
                    s"No ClassTag for Seq element type: ${TypeRepr.of[a].show}"
                  )
                )
              '{ DeriveJacksonConverter.containers.seq[a](using $inner, $ct) }
        case '[Map[k, v]] =>
          (summonJacksonConverter(TypeRepr.of[k]), summonJacksonConverter(TypeRepr.of[v])) match
            case ('{ $kConv: JacksonConverter[k] }, '{ $vConv: JacksonConverter[v] }) =>
              val kCt = Expr
                .summon[ClassTag[k]]
                .getOrElse(
                  report.errorAndAbort(s"No ClassTag for Map key type: ${TypeRepr.of[k].show}")
                )
              val vCt = Expr
                .summon[ClassTag[v]]
                .getOrElse(
                  report.errorAndAbort(
                    s"No ClassTag for Map value type: ${TypeRepr.of[v].show}"
                  )
                )
              '{ DeriveJacksonConverter.containers.map[k, v](using $kConv, $vConv, $kCt, $vCt) }
            case _ =>
              report.errorAndAbort(s"No JacksonConverter in scope for type: ${tpe.show}")
        case '[t] =>
          Expr.summon[JacksonConverter[t]] match
            case Some(conv) => conv
            case None =>
              (Expr.summon[Mirror.ProductOf[t]], Expr.summon[ClassTag[t]]) match
                case (Some(productMirror), Some(ct)) =>
                  '{ DeriveJacksonConverter.derived[t](using $productMirror, $ct) }
                case _ =>
                  (Expr.summon[Mirror.SumOf[t]], Expr.summon[ClassTag[t]]) match
                    case (Some(sumMirror), Some(ct)) =>
                      '{ DeriveJacksonConverter.derived[t](using $sumMirror, $ct) }
                    case _ =>
                      report.errorAndAbort(s"No JacksonConverter in scope for type: ${tpe.show}")
        case _ =>
          report.errorAndAbort(s"Unsupported parameter type: ${tpe.show}")

    // Build code that converts each map entry using its JacksonConverter.
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
            $convExpr.convert(key, raw, MapToFunctionMacro.baseContext)
          }.asExprOf[Any]
        else
          '{
            val key = $nameExpr
            val raw = $mapExpr.getOrElse(
              key,
              throw new NoSuchElementException("Key not found in map: " + key)
            )
            $convExpr.convert(key, raw, MapToFunctionMacro.baseContext)
          }.asExprOf[Any]
      })

    val fnTerm = f.asTerm
    val (params, retTpe) = extractParamsAndReturnType(fnTerm)
    val namedParams = tryGetRealParamNames(fnTerm) match
      case Some(names) if names.length == params.length =>
        params.zip(names).map((param, realName) => param.copy(name = realName))
      case _ => params

    retTpe.asType match
      case '[r] =>
        '{ (map: Map[String, Any]) =>
          val fnValue = $f
          val argsList: List[Any] = ${ buildArgConversionExpr(namedParams, 'map) }
          val result = MacroUtils.invokeFunctionWithArgs(fnValue, argsList)
          result.asInstanceOf[r]
        }.asExprOf[Map[String, Any] => r]
      case _ =>
        report.errorAndAbort(s"Unsupported return type: ${retTpe.show}")
