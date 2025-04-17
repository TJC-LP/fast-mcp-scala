package fastmcp.macros

// Import the Tool annotation
import fastmcp.server.FastMCPScala
import fastmcp.server.manager.*
import zio.*

import java.lang.System as JSystem
import scala.quoted.*

/** Responsible for processing @Tool annotations and generating tool registration code.
  */
private[macros] object ToolProcessor:

  /** Process a @Tool annotation and generate registration code
    */
  def processToolAnnotation(
      server: Expr[FastMCPScala],
      ownerSymAny: Any,
      methodAny: Any
      // toolAnnotAny removed
  )(using quotes: Quotes): Expr[FastMCPScala] =
    import quotes.reflect.*
    // Cast back to reflect types
    val ownerSym = ownerSymAny.asInstanceOf[Symbol]
    val methodSym = methodAny.asInstanceOf[Symbol]

    // Use generic annotation extraction for @Tool
    val toolAnnotTermOpt = MacroUtils.extractAnnotation[fastmcp.core.Tool](methodSym)
    val toolAnnotTerm = toolAnnotTermOpt.getOrElse {
      report.errorAndAbort(s"No @Tool annotation found on method ${methodSym.name}")
    }

    val methodName = methodSym.name

    // Parse @Tool annotation parameters from the extracted term
    val (annotName, annotDesc, toolTags) = MacroUtils.parseToolParams(toolAnnotTerm)
    val finalName = annotName.getOrElse(methodName)

    // Fetch Scaladoc if description is missing
    val scaladocDesc: Option[String] = methodSym.docstring
    val finalDesc: Option[String] = annotDesc.orElse(scaladocDesc)

    // Get method reference
    val methodRefExpr = MacroUtils.getMethodRefExpr(ownerSym, methodSym)

    // --- Check for context parameter: McpContext named 'ctx' ---
    val paramSymbols = methodSym.paramSymss.headOption.getOrElse(Nil)
    val ctxParamOpt = paramSymbols.find { p =>
      p.name == "ctx" && p.info <:< TypeRepr.of[fastmcp.server.McpContext]
    }
    val userParams = paramSymbols.filterNot(ctxParamOpt.contains)

    // --- Extract @Param descriptions for each parameter (excluding ctx) ---
    val paramDescriptions: Map[String, String] = userParams.flatMap { pSym =>
      // Use generic annotation extraction for @Param
      MacroUtils
        .extractAnnotation[fastmcp.core.Param](pSym)
        .flatMap { annotTerm =>
          annotTerm match
            case Apply(_, argTerms) =>
              // Try named "description" first, then positional
              argTerms
                .collectFirst { case NamedArg("description", Literal(StringConstant(desc))) =>
                  desc
                }
                .orElse {
                  argTerms.collectFirst { case Literal(StringConstant(desc)) =>
                    desc
                  }
                }
            case _ => None
        }
        .map(desc => pSym.name -> desc)
    }.toMap

    '{
      JSystem.err.println(s"[McpAnnotationProcessor] Registering @Tool: ${${ Expr(finalName) }}")
      val rawSchema: io.circe.Json = JsonSchemaMacro.schemaForFunctionArgs(
        $methodRefExpr,
        ${ Expr(ctxParamOpt.map(_.name).toList) }
      )
      val schemaWithDescriptions: io.circe.Json =
        if (${ Expr(paramDescriptions.nonEmpty) })
          MacroUtils.injectParamDescriptions(rawSchema, ${ Expr(paramDescriptions) })
        else rawSchema
      // Create a contextual handler based on whether the function has a ctx parameter
      val contextualHandler: ContextualToolHandler =
        (args: Map[String, Any], ctxOpt: Option[fastmcp.server.McpContext]) =>
          ZIO.attempt {
            val merged = ${ Expr(ctxParamOpt.isDefined) } match {
              case true => args + ("ctx" -> ctxOpt.getOrElse(fastmcp.server.McpContext()))
              case false => args
            }
            MapToFunctionMacro
              .callByMap($methodRefExpr)
              .asInstanceOf[Map[String, Any] => Any](merged)
          }

      val regEffect = $server.contextualTool(
        name = ${ Expr(finalName) },
        description = ${ Expr(finalDesc) },
        handler = contextualHandler,
        inputSchema = Right(schemaWithDescriptions.spaces2),
        options = ToolRegistrationOptions(allowOverrides = true)
      )
      zio.Unsafe.unsafe { implicit unsafe =>
        zio.Runtime.default.unsafe.run(regEffect).getOrThrowFiberFailure()
      }
      $server
    }
end ToolProcessor
