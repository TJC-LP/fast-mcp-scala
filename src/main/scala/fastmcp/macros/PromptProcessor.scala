package fastmcp.macros

import fastmcp.core.*
import fastmcp.server.FastMCPScala
import fastmcp.server.manager.*
import zio.*

import java.lang.System as JSystem
import scala.quoted.*

/**
 * Responsible for processing @Prompt annotations and generating
 * prompt registration code.
 */
private[macros] object PromptProcessor:
  /**
   * Process a @Prompt annotation and generate registration code
   */
  def processPromptAnnotation(
                               server: Expr[FastMCPScala],
                               ownerSymAny: Any,
                               methodAny: Any,
                               promptAnnotAny: Any
                             )(using quotes: Quotes): Expr[FastMCPScala] =
    import quotes.reflect.*
    val ownerSym = ownerSymAny.asInstanceOf[Symbol]
    val methodSym = methodAny.asInstanceOf[Symbol]
    val promptAnnot = promptAnnotAny.asInstanceOf[Term]

    val methodName = methodSym.name

    // Parse @Prompt annotation parameters
    val (annotName, annotDesc) = MacroUtils.parsePromptParams(promptAnnot)
    val finalName = annotName.getOrElse(methodName)

    // Fetch Scaladoc if description is missing
    val scaladocDesc: Option[String] = methodSym.docstring
    val finalDesc: Option[String] = annotDesc.orElse(scaladocDesc)

    // Analyze parameters for @PromptParam
    val promptArgs: List[Expr[PromptArgument]] = methodSym.paramSymss.headOption.getOrElse(Nil).map { param =>
      val paramName = param.name
      val paramAnnotTerm = param.annotations.find(_.tpe <:< TypeRepr.of[PromptParam]).map(_.asExpr.asTerm)
      val (paramDesc, paramRequired) = MacroUtils.parsePromptParamArgs(paramAnnotTerm)
      '{ PromptArgument(
        name = ${ Expr(paramName) },
        description = ${ Expr(paramDesc) },
        required = ${ Expr(paramRequired) }
      ) }
    }
    val promptArgsExpr = Expr.ofList(promptArgs)

    // Get method reference
    val methodRefExpr = MacroUtils.getMethodRefExpr(ownerSym, methodSym)

    '{
      JSystem.err.println(s"[McpAnnotationProcessor] Registering @Prompt: ${${ Expr(finalName) }}")
      val regEffect = $server.prompt(
        name = ${ Expr(finalName) },
        description = ${ Expr(finalDesc) },
        arguments = Some($promptArgsExpr),
        handler = (args: Map[String, Any]) => ZIO.attempt {
          MapToFunctionMacro.callByMap($methodRefExpr).asInstanceOf[Map[String, Any] => List[Message]](args)
        }
      )
      zio.Unsafe.unsafe { implicit unsafe =>
        zio.Runtime.default.unsafe.run(regEffect).getOrThrowFiberFailure()
      }
      $server
    }
end PromptProcessor