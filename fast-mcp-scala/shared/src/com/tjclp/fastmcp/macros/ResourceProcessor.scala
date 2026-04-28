package com.tjclp.fastmcp
package macros

import scala.quoted.*

import zio.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.server.McpServerCore

/** Cross-platform `@Resource` annotation processor. Handles both static and templated (URI with
  * `{placeholders}`) resources. Emits registration against [[McpServerCore]].
  */
private[macros] object ResourceProcessor extends AnnotationProcessorBase:

  def processResourceAnnotation(using Quotes)(
      server: Expr[McpServerCore],
      ownerSym: quotes.reflect.Symbol,
      methodSym: quotes.reflect.Symbol
  ): Expr[McpServerCore] =
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

    val templateHandler: Expr[Map[String, String] => ZIO[Any, Throwable, String | Array[Byte]]] =
      effectShape match
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
                  .asInstanceOf[ZIO[Any, Any, Any]]
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

    val staticHandler: Expr[() => ZIO[Any, Throwable, String | Array[Byte]]] =
      effectShape match
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
                  .asInstanceOf[ZIO[Any, Any, Any]]
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

    val registration: Expr[ZIO[Any, Throwable, McpServerCore]] =
      if isTemplate then
        '{
          $server.resourceTemplate(
            uriPattern = ${ Expr(uri) },
            handler = $templateHandler,
            name = ${ Expr(finalName) },
            description = ${ Expr(finalDesc) },
            mimeType = ${ Expr(mimeTypeOpt) },
            arguments = $argsExpr
          )
        }
      else
        '{
          $server.resource(
            uri = ${ Expr(uri) },
            handler = $staticHandler,
            name = ${ Expr(finalName) },
            description = ${ Expr(finalDesc) },
            mimeType = ${ Expr(mimeTypeOpt) }
          )
        }

    runAndReturnServer(server)(registration)
