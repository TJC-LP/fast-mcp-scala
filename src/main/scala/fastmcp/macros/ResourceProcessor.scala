package fastmcp.macros

import fastmcp.core.*
import fastmcp.macros.MapToFunctionMacro
import fastmcp.server.FastMCPScala
import fastmcp.server.manager.*
import zio.*

import java.lang.System as JSystem
import scala.quoted.*
import scala.util.matching.Regex

/**
 * Responsible for processing @Resource annotations and generating
 * resource registration code.
 */
private[macros] object ResourceProcessor:
  /**
   * Process a @Resource annotation and generate registration code
   */
  def processResourceAnnotation(
      server: Expr[FastMCPScala],
      ownerSymAny: Any,
      methodAny: Any,
      resourceAnnotAny: Any
  )(using quotes: Quotes): Expr[FastMCPScala] =
    import quotes.reflect.*
    val ownerSym  = ownerSymAny.asInstanceOf[Symbol]
    val methodSym = methodAny.asInstanceOf[Symbol]
    val resourceAnnot = resourceAnnotAny.asInstanceOf[Term]

    val methodName = methodSym.name

    // Parse @Resource annotation parameters
    val (uri, annotName, annotDesc, mimeType) = MacroUtils.parseResourceParams(resourceAnnot)
    val finalName = annotName.orElse(Some(methodName)) // Default name to method name if not in annotation

    // Fetch Scaladoc if description is missing
    val scaladocDesc: Option[String] = methodSym.docstring
    val finalDesc: Option[String] = annotDesc.orElse(scaladocDesc)

    // Get method reference
    val methodRefExpr = MacroUtils.getMethodRefExpr(ownerSym, methodSym)

    // Check if URI is a template
    val uriTemplatePattern = """\{([^{}]+)\}""".r
    val uriParams = uriTemplatePattern.findAllMatchIn(uri).map(_.group(1)).toList

    if uriParams.nonEmpty then
      // --- Template Resource ---
      val funcParams = methodSym.paramSymss.headOption.getOrElse(Nil).map(_.name)
      if uriParams.toSet != funcParams.toSet then
        report.errorAndAbort(
          s"Resource template URI parameters {${uriParams.mkString(", ")}} " +
          s"in '$uri' do not match function parameters (${funcParams.mkString(", ")}) " +
          s"for method '${methodSym.name}'."
        )

      '{
        JSystem.err.println(s"[McpAnnotationProcessor] Registering @Resource template: ${${Expr(uri)}}")
        // Generate handler that calls the function and converts the result
        val handler: ResourceTemplateHandler = (params: Map[String, String]) =>
          ZIO.attempt {
            // FIXED: Correctly invoke MapToFunctionMacro.callByMap with params
            val fn = MapToFunctionMacro.callByMap($methodRefExpr)
            val result = fn.asInstanceOf[Map[String, Any] => Any](params)
            
            // Convert result to String or Array[Byte] with simpler approach
            result match {
              case s: String => s
              case b: Array[Byte] => b
              case map: Map[?, ?] =>
                // Use simple string representation for maps
                map.toString
              case other =>
                // Use simple string representation for other types
                other.toString
            }
          }

        val regEffect = $server.resourceTemplate(
          uriPattern = ${Expr(uri)},
          name = ${Expr(finalName)},
          description = ${Expr(finalDesc)},
          mimeType = ${Expr(mimeType.orElse(Some("application/json")))}, // Default JSON for Maps
          handler = handler
        )
        zio.Unsafe.unsafe { implicit unsafe =>
          zio.Runtime.default.unsafe.run(regEffect).getOrThrowFiberFailure()
        }
        $server
      }
    else
      // --- Static Resource ---
      if methodSym.paramSymss.headOption.exists(_.nonEmpty) then
         report.errorAndAbort(s"Static resource method '${methodSym.name}' must not have parameters.")

      '{
        JSystem.err.println(s"[McpAnnotationProcessor] Registering static @Resource: ${${Expr(uri)}}")
        // Generate handler that calls the function and converts the result
        val handler: ResourceHandler = () =>
          ZIO.attempt {
            // FIXED: Correctly invoke MapToFunctionMacro.callByMap with empty map for no-arg functions
            val fn = MapToFunctionMacro.callByMap($methodRefExpr)
            val result = fn.asInstanceOf[() => Any]()
            
            // Convert result to String or Array[Byte] with simpler approach
            result match {
              case s: String => s
              case b: Array[Byte] => b
              case map: Map[?, ?] =>
                // Use simple string representation for maps
                map.toString
              case other =>
                // Use simple string representation for other types
                other.toString
            }
          }

        val regEffect = $server.resource(
          uri = ${Expr(uri)},
          name = ${Expr(finalName)},
          description = ${Expr(finalDesc)},
          mimeType = ${Expr(mimeType.orElse(Some("text/plain")))}, // Default text/plain for static resources
          handler = handler
        )
        zio.Unsafe.unsafe { implicit unsafe =>
          zio.Runtime.default.unsafe.run(regEffect).getOrThrowFiberFailure()
        }
        $server
      }
end ResourceProcessor