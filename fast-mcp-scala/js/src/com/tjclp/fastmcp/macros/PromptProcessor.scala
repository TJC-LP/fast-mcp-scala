package com.tjclp.fastmcp
package macros

import scala.quoted.*

import zio.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.server.JsMcpServer
import com.tjclp.fastmcp.server.McpServerPlatform

/** JS-target prompt annotation processor. */
private[macros] object PromptProcessor extends AnnotationProcessorBase:

  def processPromptAnnotation(using Quotes)(
      server: Expr[JsMcpServer],
      ownerSym: quotes.reflect.Symbol,
      methodSym: quotes.reflect.Symbol
  ): Expr[JsMcpServer] =
    import quotes.reflect.*

    val methodName = methodSym.name

    val promptAnnot = findAnnotation[Prompt](methodSym).getOrElse {
      report.errorAndAbort(s"No @Prompt annotation found on method '$methodName'")
    }

    val (finalName, finalDesc) = nameAndDescription(promptAnnot, methodSym)

    val argExprs: List[Expr[PromptArgument]] =
      methodSym.paramSymss.headOption.getOrElse(Nil).map { pSym =>
        val (descOpt, required) = MacroUtils.parsePromptParamArgs(
          MacroUtils.extractParamAnnotation(pSym)
        )
        '{ PromptArgument(${ Expr(pSym.name) }, ${ Expr(descOpt) }, ${ Expr(required) }) }
      }

    val maybeArgs: Expr[Option[List[PromptArgument]]] =
      if argExprs.isEmpty then '{ None }
      else '{ Some(${ Expr.ofList(argExprs) }) }

    val methodRefExpr = methodRef(ownerSym, methodSym)

    val registration: Expr[ZIO[Any, Throwable, McpServerPlatform]] = '{
      $server.prompt(
        name = ${ Expr(finalName) },
        description = ${ Expr(finalDesc) },
        arguments = $maybeArgs,
        handler = (args: Map[String, Any]) =>
          ZIO.attempt {
            val result = MapToFunctionMacro
              .callByMap($methodRefExpr)
              .asInstanceOf[Map[String, Any] => Any](args)

            result match
              case msgs: List[?] if msgs.nonEmpty && msgs.head.isInstanceOf[Message] =>
                msgs.asInstanceOf[List[Message]]
              case s: String =>
                List(Message(role = Role.User, content = TextContent(s)))
              case other =>
                List(Message(role = Role.User, content = TextContent(other.toString)))
          }
      )
    }

    runAndReturnServer(server)(registration)
