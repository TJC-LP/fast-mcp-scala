package fastmcp.macros

import fastmcp.core.*
import fastmcp.macros.{JsonSchemaMacro, MapToFunctionMacro}
import fastmcp.server.FastMCPScala
import fastmcp.server.manager.*
import io.circe.Json
import zio.*

import java.lang.System as JSystem
import scala.compiletime.summonFrom
import scala.quoted.*
import scala.util.matching.Regex

/**
 * Contains helper methods for registering annotated tools, prompts, and resources.
 * NOTE: This file should ideally be renamed to McpAnnotationProcessor.scala
 */
object McpToolRegistrationMacro: // Rename this object ideally
  /**
   * Extension method for FastMCPScala that scans an object for MCP annotations
   * (@Tool, @Prompt, @Resource) and registers them with the server.
   */
  extension (server: FastMCPScala)
    /**
     * Scan an object for methods with @Tool, @Prompt, or @Resource annotations and register them.
     *
     * @tparam T Type of the object containing annotated methods
     * @return The same FastMCPScala instance, for chaining
     */
    inline def scanAnnotations[T]: FastMCPScala =
      ${ scanAnnotationsImpl[T]('server) }

  /**
   * Macro implementation for scanAnnotations
   */
  private def scanAnnotationsImpl[T: Type](
      server: Expr[FastMCPScala]
  )(using quotes: Quotes): Expr[FastMCPScala] =
    import quotes.reflect.*

    val tpe = TypeRepr.of[T]
    val sym = tpe.typeSymbol

    // Find all methods with relevant annotations
    val annotatedMethods = sym.declaredMethods.filter { method =>
      method.annotations.exists(annot =>
        annot.tpe <:< TypeRepr.of[Tool] ||
        annot.tpe <:< TypeRepr.of[Prompt] ||
        annot.tpe <:< TypeRepr.of[Resource]
      )
    }

    if annotatedMethods.isEmpty then
      report.warning(s"No @Tool, @Prompt, or @Resource annotations found in ${Type.show[T]}")
      return server

    // Process each annotated method
    val registrationExprs: List[Expr[FastMCPScala]] = annotatedMethods.flatMap { method =>
      method.annotations.collectFirst {
        case toolAnnot if toolAnnot.tpe <:< TypeRepr.of[Tool] =>
          val annotTerm = toolAnnot.asExpr.asTerm
          processToolAnnotation(server, sym, method, annotTerm)
        case promptAnnot if promptAnnot.tpe <:< TypeRepr.of[Prompt] =>
          val annotTerm = promptAnnot.asExpr.asTerm
          processPromptAnnotation(server, sym, method, annotTerm)
        case resourceAnnot if resourceAnnot.tpe <:< TypeRepr.of[Resource] =>
          val annotTerm = resourceAnnot.asExpr.asTerm
          processResourceAnnotation(server, sym, method, annotTerm)
      }
    }

    // If no registrations, just return the server
    if registrationExprs.isEmpty then server
    else
      // Combine all registrations into one final block
      val registrationTerms = registrationExprs.map(_.asTerm)
      val block = Block(registrationTerms.init.toList, registrationTerms.last)
      block.asExprOf[FastMCPScala]

  // --- Tool Processing ---
  private def processToolAnnotation(
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
    val (annotName, annotDesc, toolTags) = parseToolParams(toolAnnot)
    val finalName = annotName.getOrElse(methodName)

    // Fetch Scaladoc if description is missing
    val scaladocDesc: Option[String] = methodSym.docstring
    val finalDesc: Option[String] = annotDesc.orElse(scaladocDesc)

    // Get method reference
    val methodRefExpr = getMethodRefExpr(ownerSym, methodSym)

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

  // --- Prompt Processing ---
  private def processPromptAnnotation(
      server: Expr[FastMCPScala],
      ownerSymAny: Any,
      methodAny: Any,
      promptAnnotAny: Any
  )(using quotes: Quotes): Expr[FastMCPScala] =
    import quotes.reflect.*
    val ownerSym  = ownerSymAny.asInstanceOf[Symbol]
    val methodSym = methodAny.asInstanceOf[Symbol]
    val promptAnnot = promptAnnotAny.asInstanceOf[Term]

    val methodName = methodSym.name

    // Parse @Prompt annotation parameters
    val (annotName, annotDesc) = parsePromptParams(promptAnnot)
    val finalName = annotName.getOrElse(methodName)

    // Fetch Scaladoc if description is missing
    val scaladocDesc: Option[String] = methodSym.docstring
    val finalDesc: Option[String] = annotDesc.orElse(scaladocDesc)

    // Analyze parameters for @PromptParam
    val promptArgs: List[Expr[PromptArgument]] = methodSym.paramSymss.headOption.getOrElse(Nil).map { param =>
      val paramName = param.name
      val paramAnnotTerm = param.annotations.find(_.tpe <:< TypeRepr.of[PromptParam]).map(_.asExpr.asTerm)
      val (paramDesc, paramRequired) = parsePromptParamArgs(paramAnnotTerm)
      '{ PromptArgument(
          name = ${Expr(paramName)},
          description = ${Expr(paramDesc)},
          required = ${Expr(paramRequired)}
        )}
    }
    val promptArgsExpr = Expr.ofList(promptArgs)

    // Get method reference
    val methodRefExpr = getMethodRefExpr(ownerSym, methodSym)

    '{
      JSystem.err.println(s"[McpAnnotationProcessor] Registering @Prompt: ${${Expr(finalName)}}")
      val handler: PromptHandler = (args: Map[String, Any]) => ZIO.attempt {
        MapToFunctionMacro.callByMap($methodRefExpr).asInstanceOf[Map[String, Any] => List[Message]](args)
      }
      val regEffect = $server.prompt(
        name = ${Expr(finalName)},
        description = ${Expr(finalDesc)},
        arguments = Some($promptArgsExpr),
        handler = handler
      )
      zio.Unsafe.unsafe { implicit unsafe =>
        zio.Runtime.default.unsafe.run(regEffect).getOrThrowFiberFailure()
      }
      $server
    }

  // --- Resource Processing ---
  private def processResourceAnnotation(
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
    val (uri, annotName, annotDesc, mimeType) = parseResourceParams(resourceAnnot)
    val finalName = annotName.orElse(Some(methodName)) // Default name to method name if not in annotation

    // Fetch Scaladoc if description is missing
    val scaladocDesc: Option[String] = methodSym.docstring
    val finalDesc: Option[String] = annotDesc.orElse(scaladocDesc)

    // Get method reference
    val methodRefExpr = getMethodRefExpr(ownerSym, methodSym)

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

  // --- Helper Methods ---

  // Gets a reference to the method within its owner object
  private def getMethodRefExpr(ownerSymAny: Any, methodSymAny: Any)(using quotes: Quotes): Expr[Any] =
    import quotes.reflect.* // Import reflection types here
    val ownerSym = ownerSymAny.asInstanceOf[Symbol] // Cast Any to Symbol
    val methodSym = methodSymAny.asInstanceOf[Symbol] // Cast Any to Symbol

    val companionSym = ownerSym.companionModule
    val methodSymOpt = companionSym.declaredMethod(methodSym.name).headOption.getOrElse {
      report.errorAndAbort(
        s"Could not find method symbol for '${methodSym.name}' in ${companionSym.fullName}"
      )
    }
    Select(Ref(companionSym), methodSymOpt).etaExpand(Symbol.spliceOwner).asExprOf[Any]

  // Helper to parse @Tool annotation arguments
  private def parseToolParams(termAny: Any)(using quotes: Quotes): (Option[String], Option[String], List[String]) =
    import quotes.reflect.* // Import reflection types here
    val term = termAny.asInstanceOf[Term] // Cast Any to Term

    var toolName: Option[String] = None
    var toolDesc: Option[String] = None
    var toolTags: List[String] = Nil

    def parseOptionString(argTerm: Term): Option[String] = argTerm match {
      case Apply(TypeApply(Select(Ident("Some"), "apply"), _), List(Literal(StringConstant(s)))) => Some(s)
      case Apply(Select(Ident("Some"), "apply"), List(Literal(StringConstant(s)))) => Some(s)
      case Select(Ident("None"), _) | Ident("None") => None
      case _ => None
    }
    def parseListString(argTerm: Term): List[String] = argTerm match {
      case Apply(TypeApply(Select(Ident("List"), "apply"), _), elems) =>
        elems.collect { case Literal(StringConstant(item)) => item }
      case Select(Ident("Nil"), _) | Apply(TypeApply(Select(Ident("List"), "apply"), _), Nil) => Nil
      case _ => Nil
    }
    term match {
      case Apply(Select(New(_), _), argTerms) =>
        argTerms.foreach {
          case NamedArg("name", valueTerm) => toolName = parseOptionString(valueTerm)
          case NamedArg("description", valueTerm) => toolDesc = parseOptionString(valueTerm)
          case NamedArg("tags", valueTerm) => toolTags = parseListString(valueTerm)
          case _ => ()
        }
      case _ => ()
    }
    (toolName, toolDesc, toolTags)


  // Helper to parse @Prompt annotation arguments
  private def parsePromptParams(termAny: Any)(using quotes: Quotes): (Option[String], Option[String]) =
    import quotes.reflect.* // Import reflection types here
    val term = termAny.asInstanceOf[Term] // Cast Any to Term

    var promptName: Option[String] = None
    var promptDesc: Option[String] = None

    def parseOptionString(argTerm: Term): Option[String] = argTerm match {
      case Apply(TypeApply(Select(Ident("Some"), "apply"), _), List(Literal(StringConstant(s)))) => Some(s)
      case Apply(Select(Ident("Some"), "apply"), List(Literal(StringConstant(s)))) => Some(s)
      case Select(Ident("None"), _) | Ident("None") => None
      case _ => None
    }

    term match {
      case Apply(Select(New(_), _), argTerms) =>
        argTerms.foreach {
          case NamedArg("name", valueTerm) => promptName = parseOptionString(valueTerm)
          case NamedArg("description", valueTerm) => promptDesc = parseOptionString(valueTerm)
          case _ => ()
        }
      case _ => ()
    }
    (promptName, promptDesc)

  // Helper to parse @PromptParam annotation arguments
  private def parsePromptParamArgs(paramAnnotOptAny: Option[Any])(using quotes: Quotes): (Option[String], Boolean) =
    import quotes.reflect.* // Import reflection types here
    val paramAnnotOpt = paramAnnotOptAny.map(_.asInstanceOf[Term]) // Cast Option[Any] to Option[Term]

    paramAnnotOpt match {
      case Some(annotTerm) =>
        var paramDesc: Option[String] = None
        var paramRequired: Boolean = true // Default required for @PromptParam
        annotTerm match {
          case Apply(_, args) => // args is defined here
            args.foreach {
              case Literal(StringConstant(s)) => paramDesc = Some(s)
              case NamedArg("description", Literal(StringConstant(s))) => paramDesc = Some(s)
              case NamedArg("required", Literal(BooleanConstant(b))) => paramRequired = b
              case Literal(BooleanConstant(b)) if args.size > 1 => paramRequired = b
              case _ => ()
            }
          case _ => ()
        }
        (paramDesc, paramRequired)
      case None => (None, true) // Default if no @PromptParam
    }

  // Helper to parse @Resource annotation arguments
  private def parseResourceParams(termAny: Any)(using quotes: Quotes): (String, Option[String], Option[String], Option[String]) =
    import quotes.reflect.* // Import reflection types here
    val term = termAny.asInstanceOf[Term] // Cast Any to Term

    var uri: String = ""
    var resourceName: Option[String] = None
    var resourceDesc: Option[String] = None
    var mimeType: Option[String] = None

    def parseOptionString(argTerm: Term): Option[String] = argTerm match {
      case Apply(TypeApply(Select(Ident("Some"), "apply"), _), List(Literal(StringConstant(s)))) => Some(s)
      case Apply(Select(Ident("Some"), "apply"), List(Literal(StringConstant(s)))) => Some(s)
      case Select(Ident("None"), _) | Ident("None") => None
      case _ => None
    }

    term match {
      case Apply(Select(New(_), _), argTerms) =>
        argTerms.foreach {
          case Literal(StringConstant(s)) if uri.isEmpty => uri = s
          case NamedArg("uri", Literal(StringConstant(s))) => uri = s
          case NamedArg("name", valueTerm) => resourceName = parseOptionString(valueTerm)
          case NamedArg("description", valueTerm) => resourceDesc = parseOptionString(valueTerm)
          case NamedArg("mimeType", valueTerm) => mimeType = parseOptionString(valueTerm)
          case _ => ()
        }
      case _ => ()
    }
    if uri.isEmpty then report.errorAndAbort("@Resource annotation must have a 'uri' parameter.")
    (uri, resourceName, resourceDesc, mimeType)

end McpToolRegistrationMacro // End object