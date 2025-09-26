package com.tjclp.fastmcp
package macros

import scala.quoted.*

import zio.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.server.*

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

    // 3️⃣  Collect @Param/@PromptParam metadata -------------------------------------------------------
    val argExprs: List[Expr[PromptArgument]] =
      methodSym.paramSymss.headOption.getOrElse(Nil).map { pSym =>
        val (descOpt, required) = MacroUtils.parsePromptParamArgs(
          MacroUtils.extractParamAnnotation(pSym, Some("Prompt"))
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
            val result = MapToFunctionMacro
              .callByMap($methodRefExpr)
              .asInstanceOf[Map[String, Any] => Any](args)

            result match {
              case msgs: List[?] if msgs.nonEmpty && msgs.head.isInstanceOf[Message] =>
                msgs.asInstanceOf[List[Message]]
              case s: String =>
                List(Message(role = Role.User, content = TextContent(s)))
              case other =>
                List(Message(role = Role.User, content = TextContent(other.toString)))
            }
          }
      )
    }

    // 6️⃣  Execute & return server ------------------------------------------------------------
    runAndReturnServer(server)(registration)
