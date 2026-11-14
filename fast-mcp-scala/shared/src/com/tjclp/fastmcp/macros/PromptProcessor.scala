package com.tjclp.fastmcp
package macros

import scala.quoted.*

import zio.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.server.McpContext
import com.tjclp.fastmcp.server.McpServerCore
import com.tjclp.fastmcp.server.manager.ContextualPromptHandler

/** Cross-platform `@Prompt` annotation processor. Emits registration against [[McpServerCore]]. */
private[macros] object PromptProcessor extends AnnotationProcessorBase:

  /** The name this method registers under — identical to what [[processPromptAnnotation]] uses. */
  def registeredName(using Quotes)(methodSym: quotes.reflect.Symbol): String =
    import quotes.reflect.*
    val annot = findAnnotation[Prompt](methodSym).getOrElse(
      report.errorAndAbort(s"No @Prompt annotation found on method '${methodSym.name}'")
    )
    nameAndDescription(annot, methodSym)._1

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

    requireRegistrableShape("@Prompt", methodSym)

    val (finalName, finalDesc) = nameAndDescription(promptAnnot, methodSym)

    // The parameters whose Scala default the generated handler applies when the client omits them.
    val appliedDefaults: Set[String] = MacroUtils.appliedDefaultGetters(methodSym).keySet

    // The parameter that receives the request's McpContext, recognised by type (any name). It is
    // not a prompt argument: it is left out of `arguments` and filled in by the handler below.
    val ctxParamName: Option[String] = contextParameter("@Prompt", methodSym)

    val argExprs: List[Expr[PromptArgument]] =
      methodSym.paramSymss.headOption
        .getOrElse(Nil)
        .filterNot { pSym =>
          ctxParamName.contains(pSym.name)
        }
        .map { pSym =>
          // An `Option` argument and an argument with an applied Scala default are optional unless
          // `@Param(required = true)` says otherwise: the generated handler supplies `None` or the
          // default when the client omits them.
          val isOptionType = pSym.termRef.widenTermRefByName <:< TypeRepr.of[Option[?]]
          val (descOpt, required) = MacroUtils.parsePromptParamArgs(
            MacroUtils.extractParamAnnotation(pSym),
            isOptionType || appliedDefaults.contains(pSym.name)
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

    val withContext: Expr[(Map[String, Any], Option[McpContext]) => Map[String, Any]] =
      ctxParamName match
        case Some(name) =>
          '{ (args: Map[String, Any], ctxOpt: Option[McpContext]) =>
            args + (${ Expr(name) } -> ctxOpt.getOrElse(McpContext.empty))
          }
        case None => '{ (args: Map[String, Any], _: Option[McpContext]) => args }

    val registration: Expr[ZIO[Any, Throwable, McpServerCore[R]]] = rMethodType match
      case '[rMethod] =>
        val handler: Expr[ContextualPromptHandler[rMethod]] = effectShape match
          case MacroUtils.EffectShape.Pure =>
            '{ (wireArgs: Map[String, Any], ctxOpt: Option[McpContext]) =>
              ZIO.attempt {
                val result = MapToFunctionMacro
                  .callByMap($methodRefExpr)
                  .asInstanceOf[Map[String, Any] => Any]($withContext(wireArgs, ctxOpt))
                $coerceMessages(result)
              }
            }
          case MacroUtils.EffectShape.Zio =>
            '{ (wireArgs: Map[String, Any], ctxOpt: Option[McpContext]) =>
              ZIO
                .suspend {
                  MapToFunctionMacro
                    .callByMap($methodRefExpr)
                    .asInstanceOf[Map[String, Any] => Any]($withContext(wireArgs, ctxOpt))
                    .asInstanceOf[ZIO[rMethod, Any, Any]]
                    .mapError {
                      case t: Throwable => t
                      case other => new RuntimeException(s"Prompt error: $other")
                    }
                }
                .map($coerceMessages)
            }
          case MacroUtils.EffectShape.TryEffect =>
            '{ (wireArgs: Map[String, Any], ctxOpt: Option[McpContext]) =>
              ZIO
                .suspend {
                  val result = MapToFunctionMacro
                    .callByMap($methodRefExpr)
                    .asInstanceOf[Map[String, Any] => Any]($withContext(wireArgs, ctxOpt))
                    .asInstanceOf[scala.util.Try[Any]]
                  ZIO.fromTry(result)
                }
                .map($coerceMessages)
            }
          case MacroUtils.EffectShape.EitherThrowable =>
            '{ (wireArgs: Map[String, Any], ctxOpt: Option[McpContext]) =>
              ZIO
                .suspend {
                  val result = MapToFunctionMacro
                    .callByMap($methodRefExpr)
                    .asInstanceOf[Map[String, Any] => Any]($withContext(wireArgs, ctxOpt))
                    .asInstanceOf[Either[Throwable, Any]]
                  ZIO.fromEither(result)
                }
                .map($coerceMessages)
            }

        // See ToolProcessor for an explanation of the cast. A method without a context parameter
        // registers through `prompt`, as it always has; `promptContextual` is used only when the
        // context is asked for.
        val definition: Expr[PromptDefinition] =
          '{ PromptDefinition(${ Expr(finalName) }, ${ Expr(finalDesc) }, $maybeArgs) }
        if ctxParamName.isDefined then
          '{
            $server.promptContextual(
              definition = $definition,
              handler = $handler
                .asInstanceOf[com.tjclp.fastmcp.server.manager.ContextualPromptHandler[R]]
            )
          }
        else
          '{
            val contextual =
              $handler.asInstanceOf[com.tjclp.fastmcp.server.manager.ContextualPromptHandler[R]]
            $server.prompt(
              definition = $definition,
              handler = (args: Map[String, Any]) => contextual(args, None)
            )
          }

    runAndReturnServer[R](server)(registration)
