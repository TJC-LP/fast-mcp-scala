package fastmcp.macros

import fastmcp.core.Tool // Import the Tool annotation
import fastmcp.server.FastMCPScala
import fastmcp.server.manager.*
import zio.*

import java.lang.System as JSystem
import scala.quoted.*
import io.circe.Json

/**
 * Responsible for processing @Tool annotations and generating
 * tool registration code.
 */
private[macros] object ToolProcessor:
  /**
   * Process a @Tool annotation and generate registration code
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

    // --- Extract @Param descriptions for each parameter ---
    val paramSymbols = methodSym.paramSymss.headOption.getOrElse(Nil)
    val paramDescriptions: Map[String, String] = paramSymbols.flatMap { pSym =>
      // Use generic annotation extraction for @Param
      MacroUtils.extractAnnotation[fastmcp.core.Param](pSym)
        .flatMap { annotTerm =>
          annotTerm match
            case Apply(_, argTerms) =>
              // Try named "description" first, then positional
              argTerms.collectFirst {
                case NamedArg("description", Literal(StringConstant(desc))) => desc
              }.orElse {
                argTerms.collectFirst {
                  case Literal(StringConstant(desc)) => desc
                }
              }
            case _ => None
        }
        .map(desc => pSym.name -> desc)
    }.toMap

    '{
      JSystem.err.println(s"[McpAnnotationProcessor] Registering @Tool: ${${ Expr(finalName) }}")
      val rawSchema: io.circe.Json = JsonSchemaMacro.schemaForFunctionArgs($methodRefExpr)
      val schemaWithDescriptions: io.circe.Json =
        if (${Expr(paramDescriptions.nonEmpty)}) MacroUtils.injectParamDescriptions(rawSchema, ${Expr(paramDescriptions)})
        else rawSchema
      val regEffect = $server.tool(
        name = ${ Expr(finalName) },
        description = ${ Expr(finalDesc) },
        handler = (args: Map[String, Any]) => ZIO.attempt {
          MapToFunctionMacro.callByMap($methodRefExpr).asInstanceOf[Map[String, Any] => Any](args)
        },
        inputSchema = Right(schemaWithDescriptions.spaces2),
        options = ToolRegistrationOptions(allowOverrides = true)
      )
      zio.Unsafe.unsafe { implicit unsafe =>
        zio.Runtime.default.unsafe.run(regEffect).getOrThrowFiberFailure()
      }
      $server
    }
end ToolProcessor