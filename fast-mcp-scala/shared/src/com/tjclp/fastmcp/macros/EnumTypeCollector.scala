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

  /** All zio-json-derivable types (Scala 3 enums AND case classes) reachable through `root`'s field
    * tree, excluding `root` itself, in POST-ORDER (dependencies before dependents) — so a planted
    * `JsonDecoder` local for an inner type is in scope when an outer type's derivation expands.
    * Same walk as [[collectEnums]]; ordering and the product inclusion differ.
    */
  def collectDerivable(using q: Quotes)(root: q.reflect.TypeRepr): List[q.reflect.TypeRepr] =
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
          val isProduct = !isScalaEnum && sym.isClassDef && sym.flags.is(Flags.Case) &&
            sym.caseFields.nonEmpty
          tpe match
            case AppliedType(_, args) => args.foreach(walk(_, depth + 1))
            case _ => ()
          if isProduct then sym.caseFields.foreach(f => walk(tpe.memberType(f), depth + 1))
          // Post-order: children recorded above, self after — dependency-safe local ordering.
          if isScalaEnum || (isProduct && tpe.typeArgs.isEmpty) then found += tpe

    val rootTpe = root.dealias.simplified
    visited += rootTpe
    rootTpe match
      case AppliedType(_, args) => args.foreach(walk(_, 1))
      case _ => ()
    val rootSym = rootTpe.typeSymbol
    if rootSym.isClassDef && rootSym.caseFields.nonEmpty then
      rootSym.caseFields.foreach(f => walk(rootTpe.memberType(f), 1))
    found.toList

  private val MaxDepth = 32
