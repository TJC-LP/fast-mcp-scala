package com.tjclp.fastmcp.macros

import scala.quoted.*

/** Compile-time walker that finds Scala 3 enum types reachable through a type's field tree.
  *
  * Used by the schema and decoder derivation macros to plant block-local givens (string-based
  * `Schema` / `JsonDecoder`) for enum and nested case-class fields that would otherwise fail
  * zio-json's per-field summon (GH #78). Shared so both the JVM and Scala.js compilations of the
  * macros can use it.
  */
private[fastmcp] object EnumTypeCollector:

  /** All Scala 3 enum types reachable through `root`'s field tree, excluding `root` itself.
    *
    * Recurses into every `AppliedType` argument (covering `Option`/`List`/`Seq`/`Map`/`Either`/
    * tuples/custom generics uniformly) and into the case fields of case classes. Dedupe and the
    * cycle guard key on the full `TypeRepr` via `=:=` — not the type symbol — so a generic case
    * class applied at two different enum arguments contributes both.
    */
  def collectEnums(using q: Quotes)(root: q.reflect.TypeRepr): List[q.reflect.TypeRepr] =
    import q.reflect.*

    val visited = scala.collection.mutable.ListBuffer.empty[TypeRepr]
    val found = scala.collection.mutable.ListBuffer.empty[TypeRepr]

    def walk(raw: TypeRepr, depth: Int): Unit =
      if depth <= MaxDepth then
        val tpe = raw.dealias.simplified
        if !visited.exists(_ =:= tpe) then
          visited += tpe
          val sym = tpe.typeSymbol
          val isScalaEnum =
            sym.flags.is(Flags.Enum) && !sym.flags.is(Flags.Case) && !sym.flags.is(
              Flags.JavaDefined
            )
          if isScalaEnum then found += tpe
          tpe match
            case AppliedType(_, args) => args.foreach(walk(_, depth + 1))
            case _ => ()
          if !isScalaEnum && sym.isClassDef && sym.caseFields.nonEmpty then
            sym.caseFields.foreach(f => walk(tpe.memberType(f), depth + 1))

    val rootTpe = root.dealias.simplified
    visited += rootTpe
    rootTpe match
      case AppliedType(_, args) => args.foreach(walk(_, 1))
      case _ => ()
    val rootSym = rootTpe.typeSymbol
    if rootSym.isClassDef && rootSym.caseFields.nonEmpty then
      rootSym.caseFields.foreach(f => walk(rootTpe.memberType(f), 1))
    found.toList

  /** All zio-json-derivable types reachable through `root`'s field tree, excluding `root` itself,
    * in POST-ORDER (dependencies before dependents) — so a planted `JsonDecoder` local for an inner
    * type is in scope when an outer type's derivation expands. Same walk as [[collectEnums]];
    * ordering and what is included differ:
    *   - Scala 3 enums and monomorphic case classes;
    *   - monomorphic sealed hierarchies ([[isSealedHierarchy]]), after the cases they are made of,
    *     so that a field of a sealed-trait type gets a decoder (GH #119);
    *   - Java enums ([[isJavaEnum]]), for which zio-json has no derivation at all;
    *   - tuples ([[isTuple]]), which the caller derives as products where the wire shape must be
    *     the `{"_1": ..., "_2": ...}` object the schema advertises.
    *
    * A `root` that is itself a sealed hierarchy contributes what its cases reach.
    */
  def collectDerivable(using q: Quotes)(root: q.reflect.TypeRepr): List[q.reflect.TypeRepr] =
    import q.reflect.*

    val visited = scala.collection.mutable.ListBuffer.empty[TypeRepr]
    val found = scala.collection.mutable.ListBuffer.empty[TypeRepr]

    def walkCases(tpe: TypeRepr, depth: Int): Unit =
      sealedCases(tpe).foreach(walk(_, depth + 1))

    def walk(raw: TypeRepr, depth: Int): Unit =
      if depth <= MaxDepth then
        val tpe = raw.dealias.simplified
        if !visited.exists(_ =:= tpe) then
          visited += tpe
          val sym = tpe.typeSymbol
          val javaEnum = isJavaEnum(tpe)
          val tuple = isTuple(tpe)
          val isScalaEnum =
            sym.flags.is(Flags.Enum) && !sym.flags.is(Flags.Case) && !sym.flags.is(
              Flags.JavaDefined
            )
          val isProduct = !isScalaEnum && !javaEnum && sym.isClassDef &&
            sym.flags.is(Flags.Case) && sym.caseFields.nonEmpty
          val isSum = isSealedHierarchy(tpe)
          tpe match
            case AppliedType(_, args) => args.foreach(walk(_, depth + 1))
            case _ => ()
          if isProduct then sym.caseFields.foreach(f => walk(tpe.memberType(f), depth + 1))
          if isSum then walkCases(tpe, depth)
          // Post-order: children recorded above, self after — dependency-safe local ordering.
          if isScalaEnum || javaEnum || tuple || isSum || (isProduct && tpe.typeArgs.isEmpty) then
            found += tpe

    val rootTpe = root.dealias.simplified
    visited += rootTpe
    rootTpe match
      case AppliedType(_, args) => args.foreach(walk(_, 1))
      case _ => ()
    val rootSym = rootTpe.typeSymbol
    if rootSym.isClassDef && rootSym.caseFields.nonEmpty then
      rootSym.caseFields.foreach(f => walk(rootTpe.memberType(f), 1))
    if isSealedHierarchy(rootTpe) then walkCases(rootTpe, 0)
    found.toList

  /** A monomorphic sealed trait or sealed abstract class with at least one case, declared outside
    * the standard library: the sum types zio-json derives a decoder for and the schema macro
    * describes as a `oneOf`. Scala 3 enums and Java enums are not included (they have their own
    * paths), nor are `Option`, `Either` and the collections (zio-json's own instances).
    */
  def isSealedHierarchy(using q: Quotes)(raw: q.reflect.TypeRepr): Boolean =
    import q.reflect.*
    val tpe = raw.dealias.simplified
    val sym = tpe.typeSymbol
    sym.isClassDef && sym.flags.is(Flags.Sealed) && !sym.flags.is(Flags.Enum) &&
    !sym.flags.is(Flags.Case) && !sym.flags.is(Flags.Module) && !sym.flags.is(Flags.JavaDefined) &&
    tpe.typeArgs.isEmpty && sym.children.nonEmpty && !isStandardLibrary(sym)

  /** The class types of the cases of the sealed hierarchy `tpe` that can be walked further: case
    * objects have no fields, and a case with type parameters has no type to walk at this point.
    */
  private def sealedCases(using q: Quotes)(tpe: q.reflect.TypeRepr): List[q.reflect.TypeRepr] =
    import q.reflect.*
    tpe.typeSymbol.children.collect {
      case child
          if child.isClassDef && !child.flags.is(Flags.Module) &&
            child.primaryConstructor.paramSymss.flatten.forall(!_.isTypeParam) =>
        child.typeRef
    }

  private def isStandardLibrary(using q: Quotes)(sym: q.reflect.Symbol): Boolean =
    val name = sym.fullName
    name.startsWith("scala.") || name.startsWith("java.")

  /** A Java `enum` class (`java.util.concurrent.TimeUnit`, a user's own). The compiler gives it a
    * `Mirror.SumOf`, but zio-json's derivation does not terminate on it, so every derivation entry
    * point must take the [[ZioJsonEnumDerivation.javaEnumDecoder]] path instead.
    */
  def isJavaEnum(using q: Quotes)(tpe: q.reflect.TypeRepr): Boolean =
    import q.reflect.*
    val sym = tpe.dealias.simplified.typeSymbol
    sym.isClassDef && sym.flags.is(Flags.Enum) && sym.flags.is(Flags.JavaDefined)

  /** The constants of the Java enum class `enumClass`, in DECLARATION order.
    *
    * Read from the declared static fields of the class, not from `Symbol.children`: the compiler
    * registers a Java enum constant as a child when it first completes it, so `children` lists the
    * constants in the order the current compilation run happened to reference them
    * (`TimeUnit.MILLISECONDS` first, if another source file mentions it first). A schema whose
    * `enum` order changed from build to build would be a moving wire shape. `children` remains the
    * fallback should a compiler version stop listing the fields.
    */
  def javaEnumConstants(using q: Quotes)(enumClass: q.reflect.Symbol): List[q.reflect.Symbol] =
    import q.reflect.*
    val declared = enumClass.companionModule.declaredFields.filter { field =>
      field.flags.is(Flags.Enum) && field.flags.is(Flags.JavaStatic)
    }
    if declared.nonEmpty then declared else enumClass.children

  /** `TupleN[...]` for N >= 1. */
  def isTuple(using q: Quotes)(tpe: q.reflect.TypeRepr): Boolean =
    import q.reflect.*
    defn.isTupleClass(tpe.dealias.simplified.typeSymbol)

  private val MaxDepth = 32
