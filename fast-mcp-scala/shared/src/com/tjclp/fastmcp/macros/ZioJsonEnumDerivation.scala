package com.tjclp.fastmcp.macros

import scala.deriving.Mirror
import scala.quoted.*

import zio.json.*

/** zio-json derivation with codec locals pre-planted for the field tree's derivable types.
  *
  * `DeriveJsonDecoder.gen[T]` summons a `JsonDecoder` per field and does not recurse, so a bare
  * enum field — or a nested case class — fails to compile at the user's call site. These entry
  * points expand `gen[T]` inside a block that carries `given JsonDecoder[X] = gen[X]` locals, in
  * dependency (post-)order, for every reachable enum or monomorphic case-class type that has NO
  * user-supplied instance at the call site (GH #78).
  *
  * The summon-first filter is the load-bearing design point: `Expr.summon` performs the full
  * call-site implicit search (contextual scope, then companions) without adding candidates, so a
  * user's hand-written enum codec — custom naming and all — is always preferred; a local is planted
  * only where the search comes up empty. zio-json's own derivation on an all-parameterless-case
  * enum already produces bare strings (`enumValuesAsStrings` defaults to true), so no shim is
  * needed; parameterized-case enums derive zio-json's wrapper-object encoding.
  */
object ZioJsonEnumDerivation:
  // Public (not private[fastmcp]) deliberately: the deriveDecoder/deriveEncoder entry points are
  // called from inline givens in other packages, and a package-private member forces the compiler
  // to synthesize an inline accessor whose parameter type is the *package class*
  // (com/tjclp/fastmcp/macros) — a class that never exists in bytecode, blowing up any runtime
  // reflection over inheritors (e.g. test discovery) with NoClassDefFoundError. Internal API:
  // not exported from com.tjclp.fastmcp, not documented for direct use.

  inline def deriveDecoder[T](using m: Mirror.Of[T]): JsonDecoder[T] =
    ${ deriveDecoderImpl[T]('m) }

  inline def deriveEncoder[T](using m: Mirror.Of[T]): JsonEncoder[T] =
    ${ deriveEncoderImpl[T]('m) }

  /** zio-json's own tuple instance (`JsonDecoder.tuple2` ...), which reads a JSON ARRAY. The schema
    * macro describes a tuple as the product it is (`{"_1": ..., "_2": ...}`), so on the input side
    * this instance is replaced by a product decoder. A tuple decoder a user defines is not one of
    * these and is left alone.
    */
  private[macros] def isZioJsonTupleInstance(using Quotes)(term: quotes.reflect.Term): Boolean =
    import quotes.reflect.*
    @scala.annotation.tailrec
    def head(t: Term): Term = t match
      case Inlined(_, _, inner) => head(inner)
      case Typed(inner, _) => head(inner)
      case Block(Nil, inner) => head(inner)
      case Apply(fn, _) => head(fn)
      case TypeApply(fn, _) => head(fn)
      case other => other
    val sym = head(term).symbol
    sym.exists && sym.name.startsWith("tuple") &&
    sym.maybeOwner.exists && sym.maybeOwner.fullName.startsWith("zio.json.")

  /** Whether the input-side derivation must supply its own `JsonDecoder` for `tpe`: nothing is
    * summonable, or what is summonable is zio-json's array-shaped tuple decoder.
    */
  private[macros] def needsDerivedDecoder(using Quotes)(tpe: quotes.reflect.TypeRepr): Boolean =
    import quotes.reflect.*
    tpe.asType match
      case '[et] =>
        Expr.summon[JsonDecoder[et]] match
          case None => true
          case Some(found) =>
            EnumTypeCollector.isTuple(tpe) && isZioJsonTupleInstance(found.asTerm)

  /** The constants of the Java enum `T`, by name, in declaration order. */
  private def javaEnumConstants[T: Type](using Quotes): List[(String, Expr[T])] =
    import quotes.reflect.*
    TypeRepr.of[T].dealias.simplified.typeSymbol.children.map { constant =>
      constant.name -> Ref(constant).asExprOf[T]
    }

  /** A decoder for the Java enum `T` that reads exactly the constant names the schema macro
    * enumerates (`"SECONDS"`), or `None` when `T` is not a Java enum.
    */
  private[macros] def javaEnumDecoder[T: Type](using Quotes): Option[Expr[JsonDecoder[T]]] =
    import quotes.reflect.*
    if !EnumTypeCollector.isJavaEnum(TypeRepr.of[T]) then None
    else
      val constants = javaEnumConstants[T]
      val entries = constants.map { case (name, value) => '{ (${ Expr(name) }, $value) } }
      val expected = Expr(constants.map(_._1).mkString(", "))
      Some('{
        val byName: Map[String, T] = Map(${ Varargs(entries) }*)
        JsonDecoder.string.mapOrFail { name =>
          byName.get(name).toRight("expected one of " + $expected)
        }
      })

  /** The encoder twin of [[javaEnumDecoder]]: the constant's name as a JSON string. */
  private[macros] def javaEnumEncoder[T: Type](using Quotes): Option[Expr[JsonEncoder[T]]] =
    import quotes.reflect.*
    if !EnumTypeCollector.isJavaEnum(TypeRepr.of[T]) then None
    else
      val constants = javaEnumConstants[T]
      val entries = constants.map { case (name, value) => '{ ($value, ${ Expr(name) }) } }
      Some('{
        val names: Map[T, String] = Map(${ Varargs(entries) }*)
        JsonEncoder.string.contramap[T](value => names.getOrElse(value, value.toString))
      })

  private[macros] def deriveDecoderImpl[T: Type](
      mirror: Expr[Mirror.Of[T]]
  )(using Quotes): Expr[JsonDecoder[T]] =
    import quotes.reflect.*
    val missing = EnumTypeCollector.collectDerivable(TypeRepr.of[T]).filter(needsDerivedDecoder)
    def withLocals(remaining: List[TypeRepr]): Expr[JsonDecoder[T]] =
      remaining match
        case Nil =>
          // A Java enum has a Mirror too, but `gen` does not terminate on it.
          javaEnumDecoder[T].getOrElse('{ DeriveJsonDecoder.gen[T](using $mirror) })
        case e :: rest =>
          e.asType match
            case '[et] =>
              javaEnumDecoder[et] match
                case Some(decoder) =>
                  '{
                    given JsonDecoder[et] = $decoder
                    ${ withLocals(rest) }
                  }
                case None =>
                  Expr.summon[Mirror.Of[et]] match
                    case Some(em) =>
                      '{
                        given JsonDecoder[et] = DeriveJsonDecoder.gen[et](using $em)
                        ${ withLocals(rest) }
                      }
                    case None => withLocals(rest) // let gen[T] surface its own error
    withLocals(missing)

  private[macros] def deriveEncoderImpl[T: Type](
      mirror: Expr[Mirror.Of[T]]
  )(using Quotes): Expr[JsonEncoder[T]] =
    import quotes.reflect.*
    // DeriveJsonEncoder.gen (unlike the decoder's) takes (config, mirror); summon the
    // configuration at the call site so user-supplied JsonCodecConfiguration is respected.
    val config = Expr.summon[JsonCodecConfiguration].getOrElse {
      report.errorAndAbort("No given JsonCodecConfiguration found (zio-json provides a default)")
    }
    // Tuples are not planted here: zio-json's own tuple encoder is always summonable, and a
    // result keeps the JSON array it has always been written as.
    val missing = EnumTypeCollector
      .collectDerivable(TypeRepr.of[T])
      .filter { e =>
        e.asType match
          case '[et] => Expr.summon[JsonEncoder[et]].isEmpty
      }
    def withLocals(remaining: List[TypeRepr]): Expr[JsonEncoder[T]] =
      remaining match
        case Nil =>
          javaEnumEncoder[T].getOrElse('{ DeriveJsonEncoder.gen[T](using $config, $mirror) })
        case e :: rest =>
          e.asType match
            case '[et] =>
              javaEnumEncoder[et] match
                case Some(encoder) =>
                  '{
                    given JsonEncoder[et] = $encoder
                    ${ withLocals(rest) }
                  }
                case None =>
                  Expr.summon[Mirror.Of[et]] match
                    case Some(em) =>
                      '{
                        given JsonEncoder[et] = DeriveJsonEncoder.gen[et](using $config, $em)
                        ${ withLocals(rest) }
                      }
                    case None => withLocals(rest)
    withLocals(missing)
