package com.tjclp.fastmcp
package macros

import scala.quoted.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.server.JsMcpServer

/** JS-target annotation entry point. Mirrors the JVM `scanAnnotations` path but emits registrations
  * against [[JsMcpServer]].
  */
object RegistrationMacro:

  extension (server: JsMcpServer)

    inline def scanAnnotations[T]: JsMcpServer =
      ${ scanAnnotationsImpl[T]('server) }

  private def scanAnnotationsImpl[T: Type](
      server: Expr[JsMcpServer]
  )(using quotes: Quotes): Expr[JsMcpServer] =
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
      report.warning(s"No @Tool, @Prompt, or @Resource annotations found in ${Type.show[T]}")
      server
    else
      val registrationExprs: List[Expr[JsMcpServer]] = annotatedMethods.flatMap { method =>
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
        Block(registrationTerms.init, registrationTerms.last).asExprOf[JsMcpServer]
