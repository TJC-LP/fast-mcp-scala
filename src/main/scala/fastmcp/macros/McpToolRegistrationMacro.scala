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

/**
 * Contains helper methods for registering annotated tools
 */
object McpToolRegistrationMacro:
  /**
   * Extension method for FastMCPScala that scans an object for @Tool annotations
   * and registers them with the server
   */
  extension (server: FastMCPScala)
    /**
     * Scan an object for methods with @Tool annotations and register them
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

    // Find all methods with @Tool annotation
    val annotatedMethods = sym.declaredMethods.filter { method =>
      method.annotations.exists(_.tpe <:< TypeRepr.of[Tool])
    }

    if annotatedMethods.isEmpty then
      report.warning(s"No @Tool annotations found in ${Type.show[T]}")
      return server

    // Helper to parse annotation arguments reflectively
    def parseToolParams(term: Term): (Option[String], Option[String], List[String]) =
      var toolName: Option[String] = None
      var toolDesc: Option[String] = None
      var toolTags: List[String] = Nil

      def parseOptionString(argTerm: Term): Option[String] = argTerm match {
        case Apply(Select(Ident("Some"), "apply"), List(Literal(StringConstant(s)))) =>
          Some(s)
        case Select(Ident("None"), "MODULE$") =>
          None
        case _ => None
      }

      def parseListString(argTerm: Term): List[String] = argTerm match {
        case Apply(TypeApply(Select(Ident("List"), "apply"), _), elems) =>
          elems.collect {
            case Literal(StringConstant(item)) => item
          }
        case _ => Nil
      }

      // e.g. new Tool(name = Some("xyz"), description = Some("desc"), tags = List("..."))
      term match {
        case Apply(Select(New(_), _), argTerms) =>
          argTerms.foreach {
            case NamedArg("name", valueTerm) =>
              toolName = parseOptionString(valueTerm)
            case NamedArg("description", valueTerm) =>
              toolDesc = parseOptionString(valueTerm)
            case NamedArg("tags", valueTerm) =>
              toolTags = parseListString(valueTerm)
            case _ => ()
          }
        case _ => ()
      }
      (toolName, toolDesc, toolTags)

    // For each annotated method, generate code to register it with the server
    val registrations: List[Expr[FastMCPScala]] = annotatedMethods.map { method =>
      val methodName = method.name

      // Find the @Tool annotation
      val toolAnnot = method.annotations.find(_.tpe <:< TypeRepr.of[Tool]).get

      // Reflectively parse name, description, tags from the annotation
      val (toolName, toolDesc, toolTags) = parseToolParams(toolAnnot.asExpr.asTerm)
      val finalName = toolName.getOrElse(methodName)

      // Find the method symbol by name in the companion module
      val companionSym = sym.companionModule
      val methodSymOpt = companionSym.declaredMethod(methodName).headOption.getOrElse {
        report.errorAndAbort(
          s"Could not find method symbol for '$methodName' in ${companionSym.fullName}"
        )
      }

      // Create a Term representing the method reference, then specify the owner
      val expandedTerm: Term =
        Select(Ref(companionSym), methodSymOpt).etaExpand(Symbol.spliceOwner)

      // Convert to an expression
      val methodRefExpr = expandedTerm.asExprOf[Any]

      '{
        // Log registration
        JSystem.err.println(s"[McpToolRegistration] Registering tool: ${${Expr(finalName)}}")

        // Generate the JSON schema from method signature
        val schema = JsonSchemaMacro.schemaForFunctionArgs($methodRefExpr)

        // Create the tool handler
        val handler: ToolHandler = (args: Map[String, Any]) => ZIO.attempt {
          val mappedFn = MapToFunctionMacro.callByMap($methodRefExpr)
          mappedFn.asInstanceOf[Map[String, Any] => Any](args)
        }

        // Register the tool by calling .tool(...) which returns a ZIO
        // We'll run the ZIO for side effects and then return server
        val regEffect = $server.tool(
          name = ${Expr(finalName)},
          description = ${Expr(toolDesc)},
          handler = handler,
          inputSchema = Right(schema.spaces2),
          options = ToolRegistrationOptions(allowOverrides = true)
        )

        // Synchronously run that effect so we can return the server
        zio.Unsafe.unsafe { implicit unsafe =>
          zio.Runtime.default.unsafe.run(regEffect).getOrThrowFiberFailure()
        }

        // Return the server
        $server
      }
    }

    // If no registrations, just return the server
    if registrations.isEmpty then server
    else
      // Combine all registrations into one final block
      val registrationTerms = registrations.map(_.asTerm)
      val block = Block(registrationTerms.init.toList, registrationTerms.last)
      block.asExprOf[FastMCPScala]