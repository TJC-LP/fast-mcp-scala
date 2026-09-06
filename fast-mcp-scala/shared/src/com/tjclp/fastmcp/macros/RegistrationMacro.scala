package com.tjclp.fastmcp
package macros

import scala.quoted.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.server.McpServerCore

/** Cross-platform entry point for annotation scanning. Works against the shared `McpServerCore[R]`
  * trait so JVM and Scala.js share a single implementation — platform-specific code resolves
  * through the usual given / implicit instances (the shared zio-json `McpDecoders` derivation on
  * both platforms).
  *
  * The server's `R` flows into each per-method registration so Scala's normal type-checker enforces
  * that every annotated method's ZIO requirement is a subtype of (or equal to) `R`. If you write
  * `@Tool def foo(): ZIO[Client, ...]` and scan it onto an `McpServer[Any]`, you get a normal Scala
  * compile error pointing at the mismatched handler type.
  */
object RegistrationMacro:

  extension [R](server: McpServerCore[R])

    /** Scan an object for `@Tool`, `@Prompt`, and `@Resource` annotations and register them. Emits
      * a compile-time warning if none are found.
      */
    inline def scanAnnotations[T]: McpServerCore[R] =
      ${ scanAnnotationsImpl[T, R]('server, warnOnEmpty = '{ true }) }

    /** Like [[scanAnnotations]] but does not warn when the target type has no annotations. Used by
      * the sugar trait [[com.tjclp.fastmcp.server.McpServer]] which may be extended by
      * contract-only servers that legitimately have no annotations on the object itself.
      */
    inline def scanAnnotationsQuiet[T]: McpServerCore[R] =
      ${ scanAnnotationsImpl[T, R]('server, warnOnEmpty = '{ false }) }

  private def scanAnnotationsImpl[T: Type, R: Type](
      server: Expr[McpServerCore[R]],
      warnOnEmpty: Expr[Boolean]
  )(using quotes: Quotes): Expr[McpServerCore[R]] =
    import quotes.reflect.*

    val tpe = TypeRepr.of[T]
    val sym = tpe.typeSymbol

    val annotatedMethods = sym.declaredMethods.filter { method =>
      method.annotations.exists(annot =>
        annot.tpe <:< TypeRepr.of[Tool] ||
          annot.tpe <:< TypeRepr.of[Prompt] ||
          annot.tpe <:< TypeRepr.of[Resource]
      )
    }

    if annotatedMethods.isEmpty then
      val shouldWarn = warnOnEmpty.valueOrAbort
      if shouldWarn then
        report.warning(s"No @Tool, @Prompt, or @Resource annotations found in ${Type.show[T]}")
      server
    else
      if !sym.flags.is(Flags.Module) then
        report.errorAndAbort(
          s"scanAnnotations[${Type.show[T]}] ${MacroUtils.NotAnObjectHint}; ${sym.fullName} is a " +
            "class. Annotated methods can only be registered from an object."
        )

      // (kind label, registered key, method). Mirrors the collectFirst order used below so a method
      // carrying several annotations is keyed by the one that actually gets registered.
      val keyed: List[(String, String, Symbol)] = annotatedMethods.flatMap { method =>
        method.annotations.collectFirst {
          case a if a.tpe <:< TypeRepr.of[Tool] =>
            ("@Tool name", ToolProcessor.registeredName(method), method)
          case a if a.tpe <:< TypeRepr.of[Prompt] =>
            ("@Prompt name", PromptProcessor.registeredName(method), method)
          case a if a.tpe <:< TypeRepr.of[Resource] =>
            val (kind, key) = ResourceProcessor.registeredUriKey(method)
            (kind, key, method)
        }
      }

      // The position is only consulted for symbols compiled in this run: a scanned object unpickled
      // from a dependency has no recorded span, and asking for it would emit a spurious compiler
      // warning ("Missing symbol position"); such methods are anchored at the call site instead.
      def position(m: Symbol): Option[Position] =
        if m.isDefinedInCurrentRun then m.pos else None

      def where(m: Symbol): String =
        position(m).map(p => s" @ ${p.sourceFile.name}:${p.startLine + 1}").getOrElse("")

      // `name(p: T, ...) @ File.scala:line`. Value parameters are rendered from the method type so
      // type parameters and erased class names never leak into the message.
      def describe(m: Symbol): String =
        def valueParams(t: TypeRepr): List[String] = t match
          case mt: MethodType =>
            mt.paramNames.zip(mt.paramTypes).map { case (n, pt) =>
              s"$n: ${pt.show(using Printer.TypeReprShortCode)}"
            } ++ valueParams(mt.resType)
          case pt: PolyType => valueParams(pt.resType)
          case _ => Nil
        m.name + valueParams(tpe.memberType(m)).mkString("(", ", ", ")") + where(m)

      val collisions: List[((String, String), List[Symbol])] = keyed
        .groupBy { case (kind, key, _) => (kind, key) }
        .collect { case (k, ms) if ms.sizeIs > 1 => (k, ms.map(_._3)) }
        .toList
        .sortBy { case ((kind, key), _) => (kind, key) }

      val objectName = sym.companionModule.fullName
      collisions.foreach { case ((kind, key), methods) =>
        val remedy =
          if kind == ResourceProcessor.TemplateKind then
            "a distinct uri (placeholder names alone do not distinguish templates)"
          else if kind == ResourceProcessor.StaticKind then "a distinct uri"
          else "an explicit name = Some(\"...\")"
        val msg =
          s"$kind '$key' is registered by ${methods.size} annotated methods in $objectName: " +
            s"${methods.map(describe).mkString(", ")}. Each annotated method must register a " +
            s"unique $kind — give one of them $remedy or remove its annotation."
        // One error per colliding declaration so IDEs navigate to each; the summary below lands
        // at the scanAnnotations call site (the scanned object usually lives in another file).
        methods.foreach(m => report.error(msg, position(m).getOrElse(Position.ofMacroExpansion)))
      }
      if collisions.nonEmpty then
        report.errorAndAbort(
          s"scanAnnotations[${Type.show[T]}]: ${collisions.size} duplicate registration(s) in " +
            s"$objectName — " + collisions
              .map { case ((kind, key), ms) =>
                s"$kind '$key' <- ${ms.map(describe).mkString(" | ")}"
              }
              .mkString("; ")
        )

      val registrationExprs: List[Expr[McpServerCore[R]]] = annotatedMethods.flatMap { method =>
        method.annotations.collectFirst {
          case toolAnnot if toolAnnot.tpe <:< TypeRepr.of[Tool] =>
            ToolProcessor.processToolAnnotation[R](server, sym, method)
          case promptAnnot if promptAnnot.tpe <:< TypeRepr.of[Prompt] =>
            PromptProcessor.processPromptAnnotation[R](server, sym, method)
          case resourceAnnot if resourceAnnot.tpe <:< TypeRepr.of[Resource] =>
            ResourceProcessor.processResourceAnnotation[R](server, sym, method)
        }
      }

      if registrationExprs.isEmpty then server
      else
        val registrationTerms = registrationExprs.map(_.asTerm)
        Block(registrationTerms.init, registrationTerms.last).asExprOf[McpServerCore[R]]
