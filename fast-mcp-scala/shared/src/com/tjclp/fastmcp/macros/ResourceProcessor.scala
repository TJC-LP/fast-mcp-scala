package com.tjclp.fastmcp
package macros

import scala.quoted.*

import zio.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.server.McpServerCore
import com.tjclp.fastmcp.server.manager.ResourceHandler
import com.tjclp.fastmcp.server.manager.ResourceTemplateHandler

/** Cross-platform `@Resource` annotation processor. Handles both static and templated (URI with
  * `{placeholders}`) resources. Emits registration against [[McpServerCore]].
  */
private[macros] object ResourceProcessor extends AnnotationProcessorBase:

  def processResourceAnnotation[R: Type](using Quotes)(
      server: Expr[McpServerCore[R]],
      ownerSym: quotes.reflect.Symbol,
      methodSym: quotes.reflect.Symbol
  ): Expr[McpServerCore[R]] =
    import quotes.reflect.*

    val methodName = methodSym.name

    val resourceAnnot = findAnnotation[Resource](methodSym).getOrElse {
      report.errorAndAbort(s"No @Resource annotation found on method '$methodName'")
    }

    val (uri, nameOpt, descOpt, mimeTypeOpt) = MacroUtils.parseResourceParams(resourceAnnot)
    val finalName = nameOpt.orElse(Some(methodName))
    val finalDesc = descOpt.orElse(methodSym.docstring)

    val placeholderRegex = raw"\{([^{}]+)}".r
    val placeholders = placeholderRegex.findAllMatchIn(uri).map(_.group(1)).toList
    val isTemplate = placeholders.nonEmpty

    val paramSyms = methodSym.paramSymss.headOption.getOrElse(Nil)
    val methodParamNames = paramSyms.map(_.name)

    if isTemplate then
      val missing = placeholders.filterNot(methodParamNames.contains)
      val extra = methodParamNames.filterNot(placeholders.contains)
      if missing.nonEmpty || extra.nonEmpty then
        report.errorAndAbort(
          s"Resource template URI parameters {${placeholders.mkString(",")}} " +
            s"do not match method parameters (${methodParamNames.mkString(",")}) for method '$methodName'"
        )
    else if methodParamNames.nonEmpty then
      report.errorAndAbort(s"Static resource method '$methodName' must not have parameters.")

    val argsExpr: Expr[Option[List[ResourceArgument]]] =
      if !isTemplate then '{ None }
      else
        val list = paramSyms.map { pSym =>
          val (descOpt, required) =
            MacroUtils.extractParamAnnotation(pSym) match
              case Some(annotTerm) =>
                var d: Option[String] = None
                var req: Boolean = true
                annotTerm match
                  case Apply(_, args) =>
                    args.foreach {
                      case Literal(StringConstant(s)) => d = Some(s)
                      case NamedArg("description", Literal(StringConstant(s))) => d = Some(s)
                      case NamedArg("required", Literal(BooleanConstant(b))) => req = b
                      case _ => ()
                    }
                  case _ => ()
                (d.orElse(pSym.docstring), req)
              case None => (pSym.docstring, true)

          '{ ResourceArgument(${ Expr(pSym.name) }, ${ Expr(descOpt) }, ${ Expr(required) }) }
        }
        '{ Some(${ Expr.ofList(list) }) }

    val methodRefExpr = methodRef(ownerSym, methodSym)

    val coerceBody: Expr[Any => String | Array[Byte]] = '{ (anyResult: Any) =>
      anyResult match
        case s: String => s
        case b: Array[Byte] => b
        case other => other.toString
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
        s"@Resource '$methodName' requires ZIO environment '${rMethodRepr.show}' but the server's " +
          s"environment type is '${rServerRepr.show}', which does not provide it. " +
          s"Construct the server as McpServer.typed[${rServerRepr.show} & ${rMethodRepr.show}] " +
          s"(or wider), or remove the environment requirement from the method body."
      )

    val registration: Expr[ZIO[Any, Throwable, McpServerCore[R]]] = rMethodType match
      case '[rMethod] =>
        val templateHandler: Expr[ResourceTemplateHandler[rMethod]] = effectShape match
          case MacroUtils.EffectShape.Pure =>
            '{ (params: Map[String, String]) =>
              ZIO.attempt {
                val anyResult = MapToFunctionMacro
                  .callByMap($methodRefExpr)
                  .asInstanceOf[Map[String, Any] => Any](params.asInstanceOf[Map[String, Any]])
                $coerceBody(anyResult)
              }
            }
          case MacroUtils.EffectShape.Zio =>
            '{ (params: Map[String, String]) =>
              ZIO
                .suspend {
                  MapToFunctionMacro
                    .callByMap($methodRefExpr)
                    .asInstanceOf[Map[String, Any] => Any](params.asInstanceOf[Map[String, Any]])
                    .asInstanceOf[ZIO[rMethod, Any, Any]]
                    .mapError {
                      case t: Throwable => t
                      case other => new RuntimeException(s"Resource error: $other")
                    }
                }
                .map($coerceBody)
            }
          case MacroUtils.EffectShape.TryEffect =>
            '{ (params: Map[String, String]) =>
              ZIO
                .suspend {
                  val result = MapToFunctionMacro
                    .callByMap($methodRefExpr)
                    .asInstanceOf[Map[String, Any] => Any](params.asInstanceOf[Map[String, Any]])
                    .asInstanceOf[scala.util.Try[Any]]
                  ZIO.fromTry(result)
                }
                .map($coerceBody)
            }
          case MacroUtils.EffectShape.EitherThrowable =>
            '{ (params: Map[String, String]) =>
              ZIO
                .suspend {
                  val result = MapToFunctionMacro
                    .callByMap($methodRefExpr)
                    .asInstanceOf[Map[String, Any] => Any](params.asInstanceOf[Map[String, Any]])
                    .asInstanceOf[Either[Throwable, Any]]
                  ZIO.fromEither(result)
                }
                .map($coerceBody)
            }

        val staticHandler: Expr[ResourceHandler[rMethod]] = effectShape match
          case MacroUtils.EffectShape.Pure =>
            '{ () =>
              ZIO.attempt {
                val anyResult = MapToFunctionMacro
                  .callByMap($methodRefExpr)
                  .asInstanceOf[Map[String, Any] => Any](Map.empty)
                $coerceBody(anyResult)
              }
            }
          case MacroUtils.EffectShape.Zio =>
            '{ () =>
              ZIO
                .suspend {
                  MapToFunctionMacro
                    .callByMap($methodRefExpr)
                    .asInstanceOf[Map[String, Any] => Any](Map.empty)
                    .asInstanceOf[ZIO[rMethod, Any, Any]]
                    .mapError {
                      case t: Throwable => t
                      case other => new RuntimeException(s"Resource error: $other")
                    }
                }
                .map($coerceBody)
            }
          case MacroUtils.EffectShape.TryEffect =>
            '{ () =>
              ZIO
                .suspend {
                  val result = MapToFunctionMacro
                    .callByMap($methodRefExpr)
                    .asInstanceOf[Map[String, Any] => Any](Map.empty)
                    .asInstanceOf[scala.util.Try[Any]]
                  ZIO.fromTry(result)
                }
                .map($coerceBody)
            }
          case MacroUtils.EffectShape.EitherThrowable =>
            '{ () =>
              ZIO
                .suspend {
                  val result = MapToFunctionMacro
                    .callByMap($methodRefExpr)
                    .asInstanceOf[Map[String, Any] => Any](Map.empty)
                    .asInstanceOf[Either[Throwable, Any]]
                  ZIO.fromEither(result)
                }
                .map($coerceBody)
            }

        // See ToolProcessor for an explanation of the cast.
        if isTemplate then
          '{
            $server.resourceTemplate(
              definition = ResourceDefinition(
                uri = ${ Expr(uri) },
                name = ${ Expr(finalName) },
                description = ${ Expr(finalDesc) },
                mimeType = ${ Expr(mimeTypeOpt) },
                isTemplate = true,
                arguments = $argsExpr
              ),
              handler = $templateHandler
                .asInstanceOf[com.tjclp.fastmcp.server.manager.ResourceTemplateHandler[R]]
            )
          }
        else
          '{
            $server.resource(
              definition = ResourceDefinition(
                uri = ${ Expr(uri) },
                name = ${ Expr(finalName) },
                description = ${ Expr(finalDesc) },
                mimeType = ${ Expr(mimeTypeOpt) },
                isTemplate = false,
                arguments = None
              ),
              handler =
                $staticHandler.asInstanceOf[com.tjclp.fastmcp.server.manager.ResourceHandler[R]]
            )
          }

    runAndReturnServer[R](server)(registration)
