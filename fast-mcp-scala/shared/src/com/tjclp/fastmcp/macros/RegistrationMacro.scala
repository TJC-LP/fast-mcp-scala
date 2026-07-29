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
