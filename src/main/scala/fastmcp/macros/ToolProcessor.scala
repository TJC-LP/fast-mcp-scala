package fastmcp.macros

import fastmcp.core.*
import fastmcp.macros.{JsonSchemaMacro, MapToFunctionMacro}
import fastmcp.server.FastMCPScala
import fastmcp.server.manager.*
import zio.*

import java.lang.System as JSystem
import scala.quoted.*

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
      methodAny: Any,
      toolAnnotAny: Any
  )(using quotes: Quotes): Expr[FastMCPScala] =
    import quotes.reflect.*
    // Cast back to reflect types
    val ownerSym  = ownerSymAny.asInstanceOf[Symbol]
    val methodSym = methodAny.asInstanceOf[Symbol]
    val toolAnnot = toolAnnotAny.asInstanceOf[Term]

    val methodName = methodSym.name

    // Parse @Tool annotation parameters
    val (annotName, annotDesc, toolTags) = MacroUtils.parseToolParams(toolAnnot)
    val finalName = annotName.getOrElse(methodName)

    // Fetch Scaladoc if description is missing
    val scaladocDesc: Option[String] = methodSym.docstring
    val finalDesc: Option[String] = annotDesc.orElse(scaladocDesc)

    // Get method reference
    val methodRefExpr = MacroUtils.getMethodRefExpr(ownerSym, methodSym)

    '{
      JSystem.err.println(s"[McpAnnotationProcessor] Registering @Tool: ${${Expr(finalName)}}")
      val schema = JsonSchemaMacro.schemaForFunctionArgs($methodRefExpr)
      val handler: ToolHandler = (args: Map[String, Any]) => ZIO.attempt {
        MapToFunctionMacro.callByMap($methodRefExpr).asInstanceOf[Map[String, Any] => Any](args)
      }
      val regEffect = $server.tool(
        name = ${Expr(finalName)},
        description = ${Expr(finalDesc)},
        handler = handler,
        inputSchema = Right(schema.spaces2),
        options = ToolRegistrationOptions(allowOverrides = true)
      )
      zio.Unsafe.unsafe { implicit unsafe =>
        zio.Runtime.default.unsafe.run(regEffect).getOrThrowFiberFailure()
      }
      $server
    }
end ToolProcessor