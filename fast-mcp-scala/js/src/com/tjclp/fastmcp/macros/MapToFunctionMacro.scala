package com.tjclp.fastmcp
package macros

import scala.annotation.tailrec
import scala.deriving.Mirror
import scala.quoted.*

import zio.json.*

import com.tjclp.fastmcp.codec.JsMcpDecodeContext
import com.tjclp.fastmcp.core.McpDecodeContext
import com.tjclp.fastmcp.core.McpDecoder

/** JS-target `Map[String, Any] => R` handler generator.
  *
  * The JVM version relies on Jackson-backed `JacksonConverter`; the JS backend instead prefers a
  * user-provided `McpDecoder[T]` and falls back to `zio-json` `JsonDecoder[T]` (deriving one for
  * product/sum types when necessary).
  */
object MapToFunctionMacro:

  private val baseContext = JsMcpDecodeContext.default

  transparent inline def callByMap[F](inline f: F): Any =
    ${ callByMapImpl('f, '{ MapToFunctionMacro.baseContext }) }

  transparent inline def callByMap[F](inline f: F, context: McpDecodeContext): Any =
    ${ callByMapImpl('f, 'context) }

  private def callByMapImpl[F: Type](
      f: Expr[F],
      contextExpr: Expr[McpDecodeContext]
  )(using q: Quotes): Expr[Any] =
    import q.reflect.*

    case class ParamInfo(name: String, tpe: TypeRepr)

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

    @tailrec
    def tryGetRealParamNames(term: Term): Option[List[String]] = term match
      case Inlined(_, _, inner) => tryGetRealParamNames(inner)
      case Block(_, inner) => tryGetRealParamNames(inner)
      case ident @ Ident(_) if ident.symbol.isDefDef && !ident.symbol.flags.is(Flags.Synthetic) =>
        ident.symbol.paramSymss.headOption.map(_.map(_.name))
      case select @ Select(_, _)
          if select.symbol.isDefDef && !select.symbol.flags.is(Flags.Synthetic) =>
        select.symbol.paramSymss.headOption.map(_.map(_.name))
      case Closure(meth @ Ident(_), _) if meth.symbol.isDefDef =>
        meth.symbol.paramSymss.headOption.map(_.map(_.name))
      case _ => None

    def jsonDecoderToMcpDecoder[T: Type](jsonDecoderExpr: Expr[JsonDecoder[T]])(using
        Quotes
    ): Expr[McpDecoder[T]] =
      '{
        McpDecoder.instance[T] { (name, rawValue, context) =>
          val json = context.writeValueAsString(rawValue)
          $jsonDecoderExpr.decodeJson(json) match
            case Right(value) => value
            case Left(err) =>
              throw new RuntimeException(
                s"Failed to decode parameter '$name' from JSON: $err. Value: $json"
              )
        }
      }

    def summonOrDeriveJsonDecoder(tpe: TypeRepr)(using Quotes): Expr[JsonDecoder[?]] =
      tpe.dealias.simplified.asType match
        case '[Option[a]] =>
          summonOrDeriveJsonDecoder(TypeRepr.of[a]) match
            case '{ $inner: JsonDecoder[a] } =>
              '{
                given JsonDecoder[a] = $inner
                summon[JsonDecoder[Option[a]]]
              }
        case '[List[a]] =>
          summonOrDeriveJsonDecoder(TypeRepr.of[a]) match
            case '{ $inner: JsonDecoder[a] } =>
              '{
                given JsonDecoder[a] = $inner
                summon[JsonDecoder[List[a]]]
              }
        case '[Seq[a]] =>
          summonOrDeriveJsonDecoder(TypeRepr.of[a]) match
            case '{ $inner: JsonDecoder[a] } =>
              '{
                given JsonDecoder[a] = $inner
                summon[JsonDecoder[Seq[a]]]
              }
        case '[Array[a]] =>
          summonOrDeriveJsonDecoder(TypeRepr.of[a]) match
            case '{ $inner: JsonDecoder[a] } =>
              '{
                given JsonDecoder[a] = $inner
                summon[JsonDecoder[Array[a]]]
              }
        case '[Map[String, v]] =>
          summonOrDeriveJsonDecoder(TypeRepr.of[v]) match
            case '{ $inner: JsonDecoder[v] } =>
              '{
                given JsonDecoder[v] = $inner
                summon[JsonDecoder[Map[String, v]]]
              }
        case '[Map[k, v]] =>
          (Expr.summon[JsonFieldDecoder[k]], summonOrDeriveJsonDecoder(TypeRepr.of[v])) match
            case (
                  Some('{ $keyDecoder: JsonFieldDecoder[k] }),
                  '{ $valueDecoder: JsonDecoder[v] }
                ) =>
              '{
                given JsonFieldDecoder[k] = $keyDecoder
                given JsonDecoder[v] = $valueDecoder
                summon[JsonDecoder[Map[k, v]]]
              }
            case _ =>
              report.errorAndAbort(
                s"No JsonFieldDecoder / JsonDecoder combination found for type: ${tpe.show}"
              )
        case '[t] =>
          Expr
            .summon[JsonDecoder[t]]
            .orElse(
              Expr.summon[Mirror.Of[t]].map { mirror =>
                '{ DeriveJsonDecoder.gen[t](using $mirror) }
              }
            )
            .getOrElse(
              report.errorAndAbort(
                s"No McpDecoder or derivable JsonDecoder found for type: ${tpe.show}"
              )
            )

    def summonDecoder(tpe: TypeRepr)(using Quotes): Expr[McpDecoder[?]] =
      tpe.dealias.simplified.asType match
        case '[t] =>
          Expr
            .summon[McpDecoder[t]]
            .getOrElse(
              jsonDecoderToMcpDecoder[t](
                summonOrDeriveJsonDecoder(tpe).asExprOf[JsonDecoder[t]]
              )
            )

    def buildArgConversionExpr(params: List[ParamInfo], mapExpr: Expr[Map[String, Any]])(using
        Quotes
    ): Expr[List[Any]] =
      Expr.ofList(params.map { p =>
        val nameExpr = Expr(p.name)
        val decoderExpr = summonDecoder(p.tpe)
        val isOptionType = p.tpe.dealias.simplified match
          case AppliedType(base, _) if base.typeSymbol.fullName == "scala.Option" => true
          case _ => false

        if isOptionType then
          '{
            val key = $nameExpr
            val rawOpt: Option[Any] = $mapExpr.get(key)
            val raw: Any = rawOpt.getOrElse(None)
            $decoderExpr.decode(key, raw, $contextExpr)
          }.asExprOf[Any]
        else
          '{
            val key = $nameExpr
            val raw = $mapExpr.getOrElse(
              key,
              throw new NoSuchElementException("Key not found in map: " + key)
            )
            $decoderExpr.decode(key, raw, $contextExpr)
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
