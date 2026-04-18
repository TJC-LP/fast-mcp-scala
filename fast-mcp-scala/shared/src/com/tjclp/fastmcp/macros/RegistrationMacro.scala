package com.tjclp.fastmcp
package macros

import scala.quoted.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.server.McpServerCore

/** Cross-platform entry point for annotation scanning. Works against the shared `McpServerCore`
  * trait so JVM and Scala.js share a single implementation — platform-specific code resolves through
  * the usual given / implicit instances (`JacksonConverter` on JVM, `ZioJsonMcpDecoder` on JS).
  */
object RegistrationMacro:

  extension (server: McpServerCore)

    /** Scan an object for `@Tool`, `@Prompt`, and `@Resource` annotations and register them. Emits
      * a compile-time warning if none are found.
      */
    inline def scanAnnotations[T]: McpServerCore =
      ${ scanAnnotationsImpl[T]('server, warnOnEmpty = '{ true }) }

    /** Like [[scanAnnotations]] but does not warn when the target type has no annotations. Used by
      * the sugar trait [[com.tjclp.fastmcp.server.McpServer]] which may be extended by contract-only
      * servers that legitimately have no annotations on the object itself.
      */
    inline def scanAnnotationsQuiet[T]: McpServerCore =
      ${ scanAnnotationsImpl[T]('server, warnOnEmpty = '{ false }) }

  private def scanAnnotationsImpl[T: Type](
      server: Expr[McpServerCore],
      warnOnEmpty: Expr[Boolean]
  )(using quotes: Quotes): Expr[McpServerCore] =
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
      val registrationExprs: List[Expr[McpServerCore]] = annotatedMethods.flatMap { method =>
        method.annotations.collectFirst {
          case toolAnnot if toolAnnot.tpe <:< TypeRepr.of[Tool] =>
            ToolProcessor.processToolAnnotation(server, sym, method)
          case promptAnnot if promptAnnot.tpe <:< TypeRepr.of[Prompt] =>
            PromptProcessor.processPromptAnnotation(server, sym, method)
          case resourceAnnot if resourceAnnot.tpe <:< TypeRepr.of[Resource] =>
            ResourceProcessor.processResourceAnnotation(server, sym, method)
        }
      }

      if registrationExprs.isEmpty then server
      else
        val registrationTerms = registrationExprs.map(_.asTerm)
        Block(registrationTerms.init, registrationTerms.last).asExprOf[McpServerCore]
