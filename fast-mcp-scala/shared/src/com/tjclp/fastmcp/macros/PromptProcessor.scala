package com.tjclp.fastmcp
package macros

import scala.quoted.*

import zio.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.server.McpServerCore
import com.tjclp.fastmcp.server.manager.PromptHandler

/** Cross-platform `@Prompt` annotation processor. Emits registration against [[McpServerCore]]. */
private[macros] object PromptProcessor extends AnnotationProcessorBase:

  def processPromptAnnotation[R: Type](using Quotes)(
      server: Expr[McpServerCore[R]],
      ownerSym: quotes.reflect.Symbol,
      methodSym: quotes.reflect.Symbol
  ): Expr[McpServerCore[R]] =
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

    val coerceMessages: Expr[Any => List[Message]] = '{ (anyResult: Any) =>
      anyResult match
        case msgs: List[?] if msgs.nonEmpty && msgs.head.isInstanceOf[Message] =>
          msgs.asInstanceOf[List[Message]]
        case s: String =>
          List(Message(role = Role.User, content = TextContent(s)))
        case other =>
          List(Message(role = Role.User, content = TextContent(other.toString)))
    }

    val effectShape = MacroUtils.detectEffectShape(methodSym)

    val rMethodType: Type[?] = MacroUtils
      .extractZioRequirement(methodSym)
      .map(_.asType)
      .getOrElse(TypeRepr.of[Any].asType)

    val rServerRepr = TypeRepr.of[R]
    val rMethodRepr = rMethodType match { case '[t] => TypeRepr.of[t] }
    if !(rServerRepr <:< rMethodRepr) then
      report.errorAndAbort(
        s"@Prompt '$methodName' requires ZIO environment '${rMethodRepr.show}' but the server's " +
          s"environment type is '${rServerRepr.show}', which does not provide it. " +
          s"Construct the server as McpServer.typed[${rServerRepr.show} & ${rMethodRepr.show}] " +
          s"(or wider), or remove the environment requirement from the method body."
      )

    val registration: Expr[ZIO[Any, Throwable, McpServerCore[R]]] = rMethodType match
      case '[rMethod] =>
        val handler: Expr[PromptHandler[rMethod]] = effectShape match
          case MacroUtils.EffectShape.Pure =>
            '{ (args: Map[String, Any]) =>
              ZIO.attempt {
                val result = MapToFunctionMacro
                  .callByMap($methodRefExpr)
                  .asInstanceOf[Map[String, Any] => Any](args)
                $coerceMessages(result)
              }
            }
          case MacroUtils.EffectShape.Zio =>
            '{ (args: Map[String, Any]) =>
              ZIO
                .suspend {
                  MapToFunctionMacro
                    .callByMap($methodRefExpr)
                    .asInstanceOf[Map[String, Any] => Any](args)
                    .asInstanceOf[ZIO[rMethod, Any, Any]]
                    .mapError {
                      case t: Throwable => t
                      case other => new RuntimeException(s"Prompt error: $other")
                    }
                }
                .map($coerceMessages)
            }
          case MacroUtils.EffectShape.TryEffect =>
            '{ (args: Map[String, Any]) =>
              ZIO
                .suspend {
                  val result = MapToFunctionMacro
                    .callByMap($methodRefExpr)
                    .asInstanceOf[Map[String, Any] => Any](args)
                    .asInstanceOf[scala.util.Try[Any]]
                  ZIO.fromTry(result)
                }
                .map($coerceMessages)
            }
          case MacroUtils.EffectShape.EitherThrowable =>
            '{ (args: Map[String, Any]) =>
              ZIO
                .suspend {
                  val result = MapToFunctionMacro
                    .callByMap($methodRefExpr)
                    .asInstanceOf[Map[String, Any] => Any](args)
                    .asInstanceOf[Either[Throwable, Any]]
                  ZIO.fromEither(result)
                }
                .map($coerceMessages)
            }

        // See ToolProcessor for an explanation of the cast.
        '{
          $server.prompt(
            definition = PromptDefinition(${ Expr(finalName) }, ${ Expr(finalDesc) }, $maybeArgs),
            handler = $handler.asInstanceOf[com.tjclp.fastmcp.server.manager.PromptHandler[R]]
          )
        }

    runAndReturnServer[R](server)(registration)
