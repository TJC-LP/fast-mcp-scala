package com.tjclp.fastmcp
package macros

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.manager.*

import scala.quoted.*
import zio.*

/** Refactored Resource processor that utilises [[AnnotationProcessorBase]]. The remaining logic is
  * limited to URI‑template handling and @Resource‑specific parameter processing.
  */
private[macros] object ResourceProcessor extends AnnotationProcessorBase:

  def processResourceAnnotation(using Quotes)(
      server: Expr[FastMcpServer],
      ownerSym: quotes.reflect.Symbol,
      methodSym: quotes.reflect.Symbol
  ): Expr[FastMcpServer] =
    import quotes.reflect.*

    val methodName = methodSym.name

    // 1️⃣  Locate annotation ------------------------------------------------------------------
    val resourceAnnot = findAnnotation[Resource](methodSym).getOrElse {
      report.errorAndAbort(s"No @Resource annotation found on method '$methodName'")
    }

    // 2️⃣  Parse URI / name / description / mimeType -----------------------------------------
    val (uri, nameOpt, descOpt, mimeTypeOpt) = MacroUtils.parseResourceParams(resourceAnnot)
    val finalName = nameOpt.orElse(Some(methodName))
    val finalDesc = descOpt.orElse(methodSym.docstring)

    // 3️⃣  Determine if URI is a template -----------------------------------------------------
    val placeholderRegex = raw"\{([^{}]+)}".r
    val placeholders = placeholderRegex.findAllMatchIn(uri).map(_.group(1)).toList
    val isTemplate = placeholders.nonEmpty

    // Validate parameters vs placeholders ----------------------------------------------------
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

    // 4️⃣  Build ResourceArgument list --------------------------------------------------------
    val argsExpr: Expr[Option[List[ResourceArgument]]] =
      if !isTemplate then '{ None }
      else
        val list = paramSyms.map { pSym =>
          // Extract @ResourceParam description / required
          val (descOpt, required) =
            MacroUtils.extractAnnotation[ResourceParam](pSym) match
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

    // 5️⃣  Stable method reference ------------------------------------------------------------
    val methodRefExpr = methodRef(ownerSym, methodSym)

    // 6️⃣  Compose registration effect --------------------------------------------------------
    val registration: Expr[ZIO[Any, Throwable, FastMcpServer]] =
      if isTemplate then
        '{
          $server.resourceTemplate(
            uriPattern = ${ Expr(uri) },
            handler = (params: Map[String, String]) =>
              ZIO.attempt {
                val anyResult = MapToFunctionMacro
                  .callByMap($methodRefExpr)
                  .asInstanceOf[Map[String, Any] => Any](
                    params.asInstanceOf[Map[String, Any]]
                  )
                anyResult match
                  case s: String => s
                  case b: Array[Byte] => b
                  case other => other.toString
              },
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
            handler = () =>
              ZIO.attempt {
                val anyResult = MapToFunctionMacro
                  .callByMap($methodRefExpr)
                  .asInstanceOf[Map[String, Any] => Any](Map.empty)
                anyResult match
                  case s: String => s
                  case b: Array[Byte] => b
                  case other => other.toString
              },
            name = ${ Expr(finalName) },
            description = ${ Expr(finalDesc) },
            mimeType = ${ Expr(mimeTypeOpt) }
          )
        }

    // 7️⃣  Execute registration and return server --------------------------------------------
    runAndReturnServer(server)(registration)
