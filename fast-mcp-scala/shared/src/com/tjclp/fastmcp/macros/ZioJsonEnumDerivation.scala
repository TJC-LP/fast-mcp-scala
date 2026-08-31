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

  private[macros] def deriveDecoderImpl[T: Type](
      mirror: Expr[Mirror.Of[T]]
  )(using Quotes): Expr[JsonDecoder[T]] =
    import quotes.reflect.*
    val missing = EnumTypeCollector
      .collectDerivable(TypeRepr.of[T])
      .filter { e =>
        e.asType match
          case '[et] => Expr.summon[JsonDecoder[et]].isEmpty
      }
    def withLocals(remaining: List[TypeRepr]): Expr[JsonDecoder[T]] =
      remaining match
        case Nil => '{ DeriveJsonDecoder.gen[T](using $mirror) }
        case e :: rest =>
          e.asType match
            case '[et] =>
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
    val missing = EnumTypeCollector
      .collectDerivable(TypeRepr.of[T])
      .filter { e =>
        e.asType match
          case '[et] => Expr.summon[JsonEncoder[et]].isEmpty
      }
    def withLocals(remaining: List[TypeRepr]): Expr[JsonEncoder[T]] =
      remaining match
        case Nil => '{ DeriveJsonEncoder.gen[T](using $config, $mirror) }
        case e :: rest =>
          e.asType match
            case '[et] =>
              Expr.summon[Mirror.Of[et]] match
                case Some(em) =>
                  '{
                    given JsonEncoder[et] = DeriveJsonEncoder.gen[et](using $config, $em)
                    ${ withLocals(rest) }
                  }
                case None => withLocals(rest)
    withLocals(missing)
