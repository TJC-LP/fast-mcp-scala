package com.tjclp.fastmcp
package macros

import scala.annotation.tailrec
import scala.deriving.Mirror
import scala.quoted.*

import zio.json.*

import com.tjclp.fastmcp.codec.DefaultDecodeContext
import com.tjclp.fastmcp.core.McpDecodeContext
import com.tjclp.fastmcp.core.McpDecoder

/** The single, platform-neutral `Map[String, Any] => R` handler generator.
  *
  * Promoted to `shared/` from the (portable) JS version — it prefers a user-provided
  * `McpDecoder[T]` and falls back to deriving a zio-json `JsonDecoder[T]`. This replaces the old
  * JVM `MapToFunctionMacro` that summoned the now-deleted Jackson `JacksonConverter`, so the
  * annotation/macro decode path is identical on JVM and Scala.js. Pairs with the shared
  * [[DefaultDecodeContext]].
  */
object MapToFunctionMacro:

  private val baseContext = DefaultDecodeContext.default

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

    /** The user-written method a (possibly eta-expanded) reference denotes. An eta-expansion is
      * `Block(List(DefDef($anonfun, _, _, Some(Apply(Select(qual, m), args)))),
      * Closure(Ident($anonfun)))` — the shape [[MacroUtils.getMethodRefExpr]] emits and the
      * compiler produces for a bare `callByMap(m)` — so the callee is read off the synthetic
      * method's body; a direct `Ident` / `Select` of a method is taken as is. `None` for lambdas
      * and other function values.
      */
    def underlyingMethod(term: Term): Option[Symbol] =
      @tailrec
      def calleeOf(t: Term): Option[Symbol] = t match
        case Apply(fn, _) => calleeOf(fn)
        case TypeApply(fn, _) => calleeOf(fn)
        case Inlined(_, _, inner) => calleeOf(inner)
        case Typed(inner, _) => calleeOf(inner)
        case Block(_, inner) => calleeOf(inner)
        case s @ Select(_, _) if s.symbol.isDefDef => Some(s.symbol)
        case i @ Ident(_) if i.symbol.isDefDef => Some(i.symbol)
        case _ => None

      term match
        case Inlined(_, _, inner) => underlyingMethod(inner)
        case Typed(inner, _) => underlyingMethod(inner)
        case Block(stats, Closure(meth @ Ident(_), _)) if meth.symbol.isDefDef =>
          stats.collectFirst { case dd: DefDef if dd.symbol == meth.symbol => dd } match
            case Some(dd) => dd.rhs.flatMap(calleeOf)
            case None => None
        case Block(_, inner) => underlyingMethod(inner)
        case ident @ Ident(_) if ident.symbol.isDefDef && !ident.symbol.flags.is(Flags.Synthetic) =>
          Some(ident.symbol)
        case select @ Select(_, _)
            if select.symbol.isDefDef && !select.symbol.flags.is(Flags.Synthetic) =>
          Some(select.symbol)
        case _ => None

    /** Scala default arguments of the denoted method's first parameter list, by parameter name: a
      * reference to the compiler-generated `<method>$default$N` getter (`N` is the 1-based
      * parameter position; the getter is declared next to the method, in the same class). Only
      * parameters flagged `HasDefault` on the method's OWN symbol qualify (never a same-named
      * sibling's getter), and only when the getter is nullary — a default in a FIRST parameter list
      * cannot depend on other parameters, so a getter with parameters means the method is
      * polymorphic and is left to the runtime "missing argument" failure.
      */
    def defaultGetters(methodSym: Symbol): Map[String, Expr[Any]] =
      val owner = methodSym.owner
      // Only object members: that is the sole shape `scanAnnotations` registers, and it gives the
      // getter a stable qualifier (`Ref(module)`, `Outer.this.module` for a class-nested object).
      if !(owner.isClassDef && owner.flags.is(Flags.Module)) then Map.empty
      else
        methodSym.paramSymss.headOption
          .getOrElse(Nil)
          .zipWithIndex
          .flatMap {
            case (pSym, idx) if pSym.flags.is(Flags.HasDefault) =>
              owner.declaredMethod(s"${methodSym.name}$$default$$${idx + 1}") match
                case getter :: Nil if getter.paramSymss.isEmpty =>
                  Some(pSym.name -> Select(Ref(owner.companionModule), getter).asExprOf[Any])
                case _ => None
            case _ => None
          }
          .toMap

    def jsonDecoderToMcpDecoder[T: Type](jsonDecoderExpr: Expr[JsonDecoder[T]])(using
        Quotes
    ): Expr[McpDecoder[T]] =
      // AST path (no re-serialisation of client input): see DefaultDecodeContext.decodeRaw.
      '{
        McpDecoder.instance[T] { (name, rawValue, context) =>
          DefaultDecodeContext.decodeRaw[T](name, rawValue, context, $jsonDecoderExpr)
        }
      }

    /** The zio-json decoder for `tpe`, derived structurally: container arms recurse into the
      * element type and plant its decoder as a local `given` before summoning zio-json's container
      * instance (the same wire shape the schema advertises); anything else is a summoned
      * `JsonDecoder`, or a Mirror-derived one for products and singleton enums. `paramName` is the
      * annotated parameter this decoder is for — every abort names it and the remedy.
      */
    def summonOrDeriveJsonDecoder(tpe: TypeRepr, paramName: String)(using
        Quotes
    ): Expr[JsonDecoder[?]] =
      def derive(inner: TypeRepr): Expr[JsonDecoder[?]] =
        summonOrDeriveJsonDecoder(inner, paramName)
      tpe.dealias.simplified.asType match
        case '[Option[a]] =>
          derive(TypeRepr.of[a]) match
            case '{ $inner: JsonDecoder[a] } =>
              '{
                given JsonDecoder[a] = $inner
                summon[JsonDecoder[Option[a]]]
              }
        case '[List[a]] =>
          derive(TypeRepr.of[a]) match
            case '{ $inner: JsonDecoder[a] } =>
              '{
                given JsonDecoder[a] = $inner
                summon[JsonDecoder[List[a]]]
              }
        // Vector before Seq: quoted type patterns match by conformance, and Vector[a] <: Seq[a]
        // would otherwise yield a JsonDecoder[Seq[a]] that is not a JsonDecoder[Vector[a]].
        case '[Vector[a]] =>
          derive(TypeRepr.of[a]) match
            case '{ $inner: JsonDecoder[a] } =>
              '{
                given JsonDecoder[a] = $inner
                summon[JsonDecoder[Vector[a]]]
              }
        case '[Seq[a]] =>
          derive(TypeRepr.of[a]) match
            case '{ $inner: JsonDecoder[a] } =>
              '{
                given JsonDecoder[a] = $inner
                summon[JsonDecoder[Seq[a]]]
              }
        case '[Set[a]] =>
          derive(TypeRepr.of[a]) match
            case '{ $inner: JsonDecoder[a] } =>
              '{
                given JsonDecoder[a] = $inner
                summon[JsonDecoder[Set[a]]]
              }
        case '[Array[a]] =>
          derive(TypeRepr.of[a]) match
            case '{ $inner: JsonDecoder[a] } =>
              '{
                given JsonDecoder[a] = $inner
                summon[JsonDecoder[Array[a]]]
              }
        case '[Map[String, v]] =>
          derive(TypeRepr.of[v]) match
            case '{ $inner: JsonDecoder[v] } =>
              '{
                given JsonDecoder[v] = $inner
                summon[JsonDecoder[Map[String, v]]]
              }
        case '[Map[k, v]] =>
          (Expr.summon[JsonFieldDecoder[k]], derive(TypeRepr.of[v])) match
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
                s"Cannot decode parameter '$paramName': no JsonFieldDecoder for the keys and " +
                  s"JsonDecoder for the values of ${tpe.show}. JSON object keys are strings — use " +
                  s"Map[String, V], or provide a given JsonDecoder[${tpe.show}] or " +
                  s"McpInputCodec[${tpe.show}]."
              )
        case '[t] =>
          Expr
            .summon[JsonDecoder[t]]
            .orElse(
              // Enum-aware derivation: plants string-based JsonDecoder locals for enum FIELD
              // types lacking a user instance, so case-class params with enum fields decode
              // zero-boilerplate like top-level enum params already do (GH #78).
              Expr.summon[Mirror.Of[t]].map { mirror =>
                ZioJsonEnumDerivation.deriveDecoderImpl[t](mirror)
              }
            )
            .getOrElse(
              report.errorAndAbort(
                s"Cannot decode parameter '$paramName': no McpDecoder, JsonDecoder or " +
                  s"Mirror-derivable JsonDecoder found for ${tpe.show}. Provide a given " +
                  s"JsonDecoder[${tpe.show}] or McpInputCodec[${tpe.show}] (decoder + schema in one " +
                  "value). Primitives, java.time values, Scala 3 enums, case classes, Option, List, " +
                  "Vector, Set, Seq, Array and Map[String, V] derive automatically."
              )
            )

    /** The `McpDecoder` for one parameter: a user instance wins; otherwise the zio-json decoder is
      * summoned or derived and wrapped. The derived decoder is CHECKED against `JsonDecoder[t]`
      * (invariant) rather than cast — a container arm matched by conformance (a `Seq` or `Map`
      * SUBTYPE such as `IndexedSeq[T]`) yields a decoder for the supertype, and casting it used to
      * crash the macro with an `ExprCastException` and a compiler stack trace.
      */
    def summonDecoder(tpe: TypeRepr, paramName: String)(using Quotes): Expr[McpDecoder[?]] =
      tpe.dealias.simplified.asType match
        case '[t] =>
          Expr.summon[McpDecoder[t]].getOrElse {
            // `isExprOf`, not a quoted `'{ $d: JsonDecoder[t] }` pattern: a lowercase type name in a
            // quoted pattern binds a FRESH type variable, so that pattern matches any decoder.
            val derived = summonOrDeriveJsonDecoder(tpe, paramName)
            if derived.isExprOf[JsonDecoder[t]] then
              jsonDecoderToMcpDecoder[t](derived.asExprOf[JsonDecoder[t]])
            else
              report.errorAndAbort(
                s"Cannot decode parameter '$paramName' of type ${tpe.show}: derivation produced a " +
                  s"${derived.asTerm.tpe.widen.show}, which is not a JsonDecoder[${tpe.show}] " +
                  "(JsonDecoder is invariant, so a decoder for a supertype does not fit). Provide a " +
                  s"given JsonDecoder[${tpe.show}] or McpInputCodec[${tpe.show}], or declare the " +
                  "parameter as List, Vector, Set, Seq, Array or Map[String, V]."
              )
          }

    /** One decoded argument per parameter. An argument present in the map is decoded; an absent one
      * takes the parameter's Scala default when it has one (exactly what a direct Scala call would
      * do), an absent `Option` without a default is `None`, and anything else is a missing required
      * argument — reported by name (a `NoSuchElementException`, which the dispatch boundary maps to
      * `-32602` / an `isError` result).
      */
    def buildArgConversionExpr(
        params: List[ParamInfo],
        defaults: Map[String, Expr[Any]],
        mapExpr: Expr[Map[String, Any]]
    )(using Quotes): Expr[List[Any]] =
      Expr.ofList(params.map { p =>
        val nameExpr = Expr(p.name)
        val decoderExpr = summonDecoder(p.tpe, p.name)
        val isOptionType = p.tpe.dealias.simplified match
          case AppliedType(base, _) if base.typeSymbol.fullName == "scala.Option" => true
          case _ => false

        defaults.get(p.name) match
          case Some(defaultExpr) =>
            '{
              val key = $nameExpr
              $mapExpr.get(key) match
                case Some(raw) => $decoderExpr.decode(key, raw, $contextExpr)
                case None => $defaultExpr
            }.asExprOf[Any]
          case None if isOptionType =>
            '{
              val key = $nameExpr
              val rawOpt: Option[Any] = $mapExpr.get(key)
              val raw: Any = rawOpt.getOrElse(None)
              $decoderExpr.decode(key, raw, $contextExpr)
            }.asExprOf[Any]
          case None =>
            '{
              val key = $nameExpr
              val raw = $mapExpr.getOrElse(
                key,
                throw new NoSuchElementException("Missing required argument '" + key + "'")
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
    val defaults: Map[String, Expr[Any]] =
      underlyingMethod(fnTerm).map(defaultGetters).getOrElse(Map.empty)

    retTpe.asType match
      case '[r] =>
        '{ (map: Map[String, Any]) =>
          val fnValue = $f
          val argsList: List[Any] = ${ buildArgConversionExpr(namedParams, defaults, 'map) }
          val result = MacroUtils.invokeFunctionWithArgs(fnValue, argsList)
          result.asInstanceOf[r]
        }.asExprOf[Map[String, Any] => r]
      case _ =>
        report.errorAndAbort(s"Unsupported return type: ${retTpe.show}")
