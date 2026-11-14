package com.tjclp.fastmcp
package macros

import scala.quoted.*

import com.tjclp.fastmcp.core.Content
import com.tjclp.fastmcp.core.McpEncoder

/** Decides, when an annotated `@Tool` method is expanded, how its result reaches the wire (GH #119,
  * row C2.9).
  *
  * A result is encoded through an [[McpEncoder]] for the method's result type, as a typed contract
  * (`McpTool`) encodes its `Out`, when that encoder is one the user defined:
  *   - a `given McpEncoder[T]`, in scope where the annotations are scanned or in the companion of
  *     `T`, or
  *   - a `given zio.json.JsonEncoder[T]` (also `derives JsonEncoder` / `derives JsonCodec`) that
  *     the library lifts, on its own or inside a zio-json container instance (`List[T]`,
  *     `Option[T]`, `Map[String, T]`).
  *
  * Every other result keeps the rendering 1.0 has: a `String`, a `Content`, a `List[Content]` and
  * an `Array[Byte]` are recognised when the tool is called, and any other value is sent as its
  * `toString` text. That includes a type for which only the library's own derivation would supply
  * an encoder (a case class without a `JsonEncoder`, `Option[String]`, `List[Int]`, `Unit`):
  * encoding those would change what an existing server sends.
  */
object ResultEncoderResolution:
  // Internal API: not exported from `com.tjclp.fastmcp`, not documented for direct use. The object
  // is public only because of `describe`, which the test suites call from outside the package.

  /** How an annotated `@Tool` method that returns `T` would have its result sent, decided where
    * this is called: `"encode"` (through a user-defined encoder), `"runtime"` (the 1.0 rendering)
    * or `"no-encoder"` (the 1.0 rendering, with the expansion-time warning). A test hook: the
    * warning itself cannot be observed from a compiled test.
    */
  inline def describe[T]: String = ${ describeImpl[T] }

  private def describeImpl[T: Type](using Quotes): Expr[String] =
    import quotes.reflect.*
    Expr(resolve(TypeRepr.of[T]) match
      case Resolution.Encode(_) => "encode"
      case Resolution.Runtime => "runtime"
      case Resolution.NoEncoder => "no-encoder"
    )

  /** What [[resolve]] found for one result type. */
  private[macros] enum Resolution[+T]:

    /** A user-defined encoder: the generated handler encodes the result through it. */
    case Encode(encoder: T)

    /** The run-time rendering applies; nothing to say about it. */
    case Runtime

    /** The run-time rendering applies and no `McpEncoder` exists for the type at all, so every
      * value that is not recognised when the tool is called is sent as its `toString` text.
      */
    case NoEncoder

  /** The type a method's result has once its effect is run: `A` of `ZIO[R, E, A]`, `Try[A]` and
    * `Either[Throwable, A]`, the declared type otherwise.
    */
  private[macros] def resultType(using Quotes)(
      methodSym: quotes.reflect.Symbol,
      shape: MacroUtils.EffectShape
  ): quotes.reflect.TypeRepr =
    import quotes.reflect.*

    val declared = (methodSym.termRef.widenTermRefByName match
      case mt: MethodType => mt.resType
      case other => other
    ).dealias

    def argument(of: TypeRepr, index: Int): TypeRepr =
      declared.baseType(of.typeSymbol) match
        case AppliedType(_, args) if args.length > index => args(index)
        case _ => TypeRepr.of[Any]

    shape match
      case MacroUtils.EffectShape.Pure => declared
      case MacroUtils.EffectShape.Zio => argument(TypeRepr.of[zio.ZIO], 2)
      case MacroUtils.EffectShape.TryEffect => argument(TypeRepr.of[scala.util.Try], 0)
      case MacroUtils.EffectShape.EitherThrowable => argument(TypeRepr.of[Either], 1)

  private[macros] def resolve(using Quotes)(
      rawTpe: quotes.reflect.TypeRepr
  ): Resolution[quotes.reflect.Term] =
    import quotes.reflect.*

    val tpe = rawTpe.dealias.simplified

    // Types whose value is only known when the tool is called, and types the run-time rendering
    // recognises itself.
    val dynamic =
      tpe =:= TypeRepr.of[Any] || tpe =:= TypeRepr.of[AnyRef] || tpe =:= TypeRepr.of[Matchable] ||
        tpe <:< TypeRepr.of[Null] // Nothing and Null: an encoder search for them is ambiguous
    val renderedAtRuntime =
      tpe <:< TypeRepr.of[String | Content | List[Content] | Array[Byte]]

    if dynamic || renderedAtRuntime then Resolution.Runtime
    else
      tpe.asType match
        case '[t] =>
          Implicits.search(TypeRepr.of[McpEncoder[t]]) match
            case success: ImplicitSearchSuccess if mentionsUserGiven(success.tree) =>
              Resolution.Encode(success.tree)
            case outcome =>
              companionEncoder(tpe) match
                case Some(encoder) => Resolution.Encode(encoder)
                case None =>
                  outcome match
                    // A library instance, or instances the compiler cannot choose between: the
                    // rendering stays as it was.
                    case _: ImplicitSearchSuccess | _: AmbiguousImplicits | _: DivergingImplicit =>
                      Resolution.Runtime
                    // Nothing matches. (Not only `NoMatchingImplicits`: a candidate whose own
                    // macro gives up, as zio-json's string-enumeration instance does for a class
                    // that is not one, is reported as a plain failure.)
                    case _ => Resolution.NoEncoder

  /** A `given McpEncoder` declared in the companion of the result type's class.
    *
    * The ordinary search does not always reach it: at a call site with `import
    * com.tjclp.fastmcp.{*, given}` the library's derivation for case classes is found in the
    * lexical scope, which is searched before any companion, so for a case class the companion's
    * `McpEncoder` is never consulted. An instance the user wrote for the type must win over a
    * derived one, so it is looked up here.
    */
  private def companionEncoder(using Quotes)(
      tpe: quotes.reflect.TypeRepr
  ): Option[quotes.reflect.Term] =
    import quotes.reflect.*

    val wanted = tpe.asType match
      case '[t] => TypeRepr.of[McpEncoder[t]]
    val companion = tpe.typeSymbol.companionModule
    // Only a companion that can be named from anywhere: one nested in a class has no stable path.
    def isStatic(sym: Symbol): Boolean =
      !sym.exists || sym.flags.is(Flags.Package) ||
        (sym.flags.is(Flags.Module) && isStatic(sym.maybeOwner))
    if !companion.exists || isLibraryOwned(companion) || !isStatic(companion) then None
    else
      val members = companion.declaredFields ++ companion.declaredMethods
      members.collectFirst {
        case member
            if isInstance(member) && member.paramSymss.isEmpty &&
              Ref(companion).select(member).tpe.widen <:< wanted =>
          Ref(companion).select(member)
      }

  private def isInstance(using Quotes)(sym: quotes.reflect.Symbol): Boolean =
    import quotes.reflect.*
    sym.exists && (sym.flags.is(Flags.Given) || sym.flags.is(Flags.Implicit))

  /** Whether the resolved instance is, or takes as an argument, a given that is defined outside
    * this library, zio-json and the standard library. The expansion of one of the library's own
    * inline givens is not looked into: the derived encoder of a case class is the library's
    * instance, whatever the encoders of its fields are.
    */
  private def mentionsUserGiven(using Quotes)(tree: quotes.reflect.Term): Boolean =
    import quotes.reflect.*

    @scala.annotation.tailrec
    def head(term: Tree): Symbol = term match
      case Apply(fn, _) => head(fn)
      case TypeApply(fn, _) => head(fn)
      case other => other.symbol

    val accumulator = new TreeAccumulator[Boolean]:
      def foldTree(found: Boolean, tree: Tree)(owner: Symbol): Boolean =
        if found then true
        else
          tree match
            case Inlined(Some(call), _, _) if head(call).exists =>
              val instance = head(call)
              isInstance(instance) && !isLibraryOwned(instance)
            case ref: Ref if isInstance(ref.symbol) && !isLibraryOwned(ref.symbol) => true
            case other => foldOverTree(found, other)(owner)

    accumulator.foldTree(false, tree)(Symbol.spliceOwner)

  private val libraryPackages: List[String] =
    List("com.tjclp.fastmcp", "zio.json", "scala", "java")

  /** Whether `sym` is declared in a package of this library, zio-json or the standard library. */
  private def isLibraryOwned(using Quotes)(sym: quotes.reflect.Symbol): Boolean =
    import quotes.reflect.*

    @scala.annotation.tailrec
    def enclosingPackage(current: Symbol): Option[String] =
      if !current.exists || current == defn.RootClass then None
      else if current.flags.is(Flags.Package) then Some(current.fullName)
      else enclosingPackage(current.maybeOwner)

    enclosingPackage(sym) match
      case Some(pkg) => libraryPackages.exists(lib => pkg == lib || pkg.startsWith(lib + "."))
      case None => false
