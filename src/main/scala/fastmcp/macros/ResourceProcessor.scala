package fastmcp.macros

import fastmcp.core.ResourceParam
import fastmcp.server.FastMCPScala
import fastmcp.server.manager.*
import zio.*

import java.lang.System as JSystem
import scala.quoted.*

/**
 * Responsible for processing @Resource annotations and generating
 * resource registration code for FastMCPScala.
 */
private[macros] object ResourceProcessor:

  /**
   * Processes a method annotated with @Resource.
   * Determines if it's a static or templated resource based on the URI pattern.
   * Generates code to call the appropriate `server.resource` or `server.resourceTemplate`
   * method in FastMCPScala, passing the correct handler type and collected metadata.
   */
  def processResourceAnnotation(
                                 server: Expr[FastMCPScala],
                                 ownerSymAny: Any, // Symbol of the class/object containing the method
                                 methodAny: Any, // Symbol of the annotated method
                                 resourceAnnotAny: Any // Term representing the @Resource annotation instance
                               )(using quotes: Quotes): Expr[FastMCPScala] =
    import quotes.reflect.*

    // --- 1. Symbol and Annotation Parsing ---
    val ownerSym = ownerSymAny.asInstanceOf[Symbol]
    val methodSym = methodAny.asInstanceOf[Symbol]
    val resourceAnnot = resourceAnnotAny.asInstanceOf[Term]
    val methodName = methodSym.name

    val (uri_, annotName, annotDesc, mimeType_) = MacroUtils.parseResourceParams(resourceAnnot)
    val finalName = annotName.orElse(Some(methodName))
    val finalDesc = annotDesc.orElse(methodSym.docstring)

    // --- 2. Determine Resource Type (Static vs. Template) ---
    val uriTemplatePattern = """\{([^{}]+)}""".r
    val uriParams = uriTemplatePattern.findAllMatchIn(uri_).map(_.group(1)).toList
    val isTemplate = uriParams.nonEmpty

    // --- 3. Parameter Validation and Argument Gathering ---
    val methodParamSyms = methodSym.paramSymss.headOption.getOrElse(Nil)
    val methodParamNames = methodParamSyms.map(_.name).toSet

    val resourceArgumentsExpr: Expr[Option[List[ResourceArgument]]] =
      if (isTemplate) {
        val placeholderNames = uriParams.toSet
        if (placeholderNames != methodParamNames) {
          report.errorAndAbort(
            s"Resource template URI parameters {${uriParams.mkString(", ")}} in '$uri_' " +
              s"do not match function parameters (${methodParamNames.mkString(", ")}) " +
              s"for method '$methodName'. Ensure parameter names match URI placeholders exactly."
          )
        }
        val argsList: List[Expr[ResourceArgument]] = methodParamSyms.map { psym =>
          val paramName = psym.name
          val paramDoc = psym.docstring
          val resourceParamAnnotOpt = psym.annotations.find(_.tpe <:< TypeRepr.of[ResourceParam])
          var paramAnnotDesc: Option[String] = None
          var paramAnnotRequired: Boolean = true
          resourceParamAnnotOpt.map(_.asExpr.asTerm).foreach {
            case Apply(_, argVals) =>
              argVals.foreach {
                case Literal(StringConstant(s)) => paramAnnotDesc = Some(s)
                case NamedArg("description", Literal(StringConstant(s))) => paramAnnotDesc = Some(s)
                case NamedArg("required", Literal(BooleanConstant(b))) => paramAnnotRequired = b
                case lit@Literal(_) if argVals.size > 1 && {
                  argVals.head match {
                    case _: Literal => true
                    case _ => false
                  }
                } =>
                  lit match {
                    case Literal(BooleanConstant(b)) => paramAnnotRequired = b
                    case _ => ()
                  }
                case _ => ()
              }
            case _ => ()
          }
          val finalParamDesc = paramAnnotDesc.orElse(paramDoc)
          '{ ResourceArgument(${ Expr(paramName) }, ${ Expr(finalParamDesc) }, ${ Expr(paramAnnotRequired) }) }
        }
        '{ Some(${ Expr.ofList(argsList) }) }
      } else {
        if (methodParamSyms.nonEmpty) {
          report.errorAndAbort(
            s"Static resource method '$methodName' (URI: '$uri_') must not have parameters. Found: ${methodParamNames.mkString(", ")}"
          )
        }
        '{ None }
      }

    // --- 4. Generate Handler and Registration Code ---
    val methodRefExpr = MacroUtils.getMethodRefExpr(ownerSym, methodSym)

    val registrationCode: Expr[FastMCPScala] =
      if (isTemplate) {
        // --- Generate Template Handler and Call server.resourceTemplate ---
        '{
          JSystem.err.println(s"[ResourceProcessor] Registering TEMPLATE resource: ${${ Expr(uri_) }} -> ${${ Expr(methodName) }}")
          // Call server.resourceTemplate
          val registrationEffect = $server.resourceTemplate(
            uriPattern = ${ Expr(uri_) },
            handler = (params: Map[String, String]) =>
              ZIO.attempt {
                val fn = MapToFunctionMacro.callByMap($methodRefExpr)
                val anyParams: Map[String, Any] = params.asInstanceOf[Map[String, Any]]
                val result: Any = fn.asInstanceOf[Map[String, Any] => Any](anyParams)
                result match {
                  case s: String => s
                  case b: Array[Byte] => b
                  case other => other.toString
                }
              },
            name = ${ Expr(finalName) },
            description = ${ Expr(finalDesc) },
            mimeType = ${ Expr(mimeType_) },
            arguments = $resourceArgumentsExpr // Pass the generated arguments Expr
          )
          Unsafe.unsafe { implicit unsafe => Runtime.default.unsafe.run(registrationEffect).getOrThrowFiberFailure() }
          $server
        }
      } else {
        // --- Generate Static Handler and Call server.resource ---
        '{
          JSystem.err.println(s"[ResourceProcessor] Registering STATIC resource: ${${ Expr(uri_) }} -> ${${ Expr(methodName) }}")
          // Call server.resource
          val registrationEffect = $server.resource(
            uri = ${ Expr(uri_) },
            handler = () =>
              ZIO.attempt {
                val fn = MapToFunctionMacro.callByMap($methodRefExpr)
                val result: Any = fn.asInstanceOf[Map[String, Any] => Any](Map.empty)
                result match {
                  case s: String => s
                  case b: Array[Byte] => b
                  case other => other.toString
                }
              },
            name = ${ Expr(finalName) },
            description = ${ Expr(finalDesc) },
            mimeType = ${ Expr(mimeType_) }
            // No 'arguments' parameter for the static method
          )
          Unsafe.unsafe { implicit unsafe => Runtime.default.unsafe.run(registrationEffect).getOrThrowFiberFailure() }
          $server
        }
      }

    // --- 5. Return Generated Code ---
    registrationCode

end ResourceProcessor
