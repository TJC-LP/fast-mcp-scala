package com.tjclp.fastmcp
package macros

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.server.*
import zio.*

import scala.quoted.*

/** Lightweight implementation thanks to [[AnnotationProcessorBase]]. */
private[macros] object PromptProcessor extends AnnotationProcessorBase:

  def processPromptAnnotation(using Quotes)(
      server: Expr[FastMcpServer],
      ownerSym: quotes.reflect.Symbol,
      methodSym: quotes.reflect.Symbol
  ): Expr[FastMcpServer] =
    import quotes.reflect.*

    val methodName = methodSym.name

    // 1️⃣  Find @Prompt annotation -------------------------------------------------------------
    val promptAnnot = findAnnotation[Prompt](methodSym).getOrElse {
      report.errorAndAbort(s"No @Prompt annotation found on method '$methodName'")
    }

    // 2️⃣  name / description with Scaladoc fallback ------------------------------------------
    val (finalName, finalDesc) = nameAndDescription(promptAnnot, methodSym)

    // 3️⃣  Collect @PromptParam metadata -------------------------------------------------------
    val argExprs: List[Expr[PromptArgument]] =
      methodSym.paramSymss.headOption.getOrElse(Nil).map { pSym =>
        val (descOpt, required) = MacroUtils.parsePromptParamArgs(
          MacroUtils.extractAnnotation[PromptParam](pSym)
        )
        '{ PromptArgument(${ Expr(pSym.name) }, ${ Expr(descOpt) }, ${ Expr(required) }) }
      }

    val maybeArgs: Expr[Option[List[PromptArgument]]] =
      if argExprs.isEmpty then '{ None }
      else '{ Some(${ Expr.ofList(argExprs) }) }

    // 4️⃣  Method reference -------------------------------------------------------------------
    val methodRefExpr = methodRef(ownerSym, methodSym)

    // 5️⃣  Compose registration effect --------------------------------------------------------
    val registration: Expr[ZIO[Any, Throwable, FastMcpServer]] = '{
      $server.prompt(
        name = ${ Expr(finalName) },
        description = ${ Expr(finalDesc) },
        arguments = $maybeArgs,
        handler = (args: Map[String, Any]) =>
          ZIO.attempt {
            MapToFunctionMacro
              .callByMap($methodRefExpr)
              .asInstanceOf[Map[String, Any] => List[Message]](args)
          }
      )
    }

    // 6️⃣  Execute & return server ------------------------------------------------------------
    runAndReturnServer(server)(registration)
