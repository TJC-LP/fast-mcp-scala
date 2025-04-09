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

      // Log the structure of the term being parsed for debugging
      // JSystem.err.println(s"[parseToolParams] Parsing Term: ${term.show(using Printer.TreeStructure)}")

      def parseOptionString(argTerm: Term): Option[String] = argTerm match {
        // Matches Some("string_literal")
        case Apply(TypeApply(Select(Ident("Some"), "apply"), _), List(Literal(StringConstant(s)))) =>
          Some(s)
        // Matches Some.apply("string_literal") - alternative structure
        case Apply(Select(Ident("Some"), "apply"), List(Literal(StringConstant(s)))) =>
           Some(s)
        // Matches None or None$
        case Select(Ident("None"), _) | Ident("None") => // Handle None and None$ module
          None
        case other =>
          // Log unexpected structure for Option[String]
          // JSystem.err.println(s"[parseToolParams] Unexpected structure for Option[String]: ${other.show(using Printer.TreeStructure)}")
          None
      }

      def parseListString(argTerm: Term): List[String] = argTerm match {
        // Matches List("item1", "item2")
        case Apply(TypeApply(Select(Ident("List"), "apply"), _), elems) =>
          elems.collect {
            case Literal(StringConstant(item)) => item
          }
        // Matches Nil or List()
        case Select(Ident("Nil"), _) | Apply(TypeApply(Select(Ident("List"), "apply"), _), Nil) =>
          Nil
        case other =>
          // Log unexpected structure for List[String]
          // JSystem.err.println(s"[parseToolParams] Unexpected structure for List[String]: ${other.show(using Printer.TreeStructure)}")
          Nil
      }

      // e.g. new Tool(name = Some("xyz"), description = Some("desc"), tags = List("..."))
      term match {
        // Matches constructor call: new Tool(...named args...)
        case Apply(Select(New(_), _), argTerms) =>
          argTerms.foreach {
            case NamedArg("name", valueTerm) =>
              toolName = parseOptionString(valueTerm)
            case NamedArg("description", valueTerm) =>
              toolDesc = parseOptionString(valueTerm) // Correctly parse description
            case NamedArg("tags", valueTerm) =>
              toolTags = parseListString(valueTerm)
            // Add cases for other Tool parameters if needed (examples, version, etc.)
            case NamedArg(argName, _) =>
              // Log unhandled named arguments if necessary
              // JSystem.err.println(s"[parseToolParams] Unhandled named argument: $argName")
              ()
            case other =>
              // Log unexpected argument structure
              // JSystem.err.println(s"[parseToolParams] Unexpected argument structure in Apply: ${other.show(using Printer.TreeStructure)}")
              ()
          }
        // Handle cases where the annotation might be represented differently if needed
        case other =>
          // Log unexpected overall term structure
          // JSystem.err.println(s"[parseToolParams] Unexpected term structure for Tool annotation: ${other.show(using Printer.TreeStructure)}")
          ()
      }
      (toolName, toolDesc, toolTags)

    // For each annotated method, generate code to register it with the server
    val registrations: List[Expr[FastMCPScala]] = annotatedMethods.map { method =>
      val methodName = method.name

      // Find the @Tool annotation
      val toolAnnot = method.annotations.find(_.tpe <:< TypeRepr.of[Tool]).get

      // Reflectively parse name, description, tags from the annotation
      val (annotName, annotDesc, toolTags) = parseToolParams(toolAnnot.asExpr.asTerm)
      val finalName = annotName.getOrElse(methodName)

      // --- Fetch Scaladoc if description is missing ---
      val scaladocDesc: Option[String] = method.docstring
      val finalDesc: Option[String] = annotDesc.orElse(scaladocDesc)
      // Log which description source was used
      // if (annotDesc.isDefined) JSystem.err.println(s"[McpToolRegistration] Using annotation description for '$finalName'")
      // else if (scaladocDesc.isDefined) JSystem.err.println(s"[McpToolRegistration] Using Scaladoc description for '$finalName'")
      // else JSystem.err.println(s"[McpToolRegistration] No description found for '$finalName'")
      // -------------------------------------------------

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
        JSystem.err.println(s"[McpToolRegistration] Registering tool: ${${Expr(finalName)}} with description: ${${Expr(finalDesc)}.getOrElse("None")}")

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
          description = ${Expr(finalDesc)}, // Use the final description (annotation or Scaladoc)
          handler = handler,
          inputSchema = Right(schema.spaces2),
          options = ToolRegistrationOptions(allowOverrides = true) // Example option
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