package fastmcp.macros

import fastmcp.core.*
import fastmcp.server.FastMCPScala

import scala.quoted.*

/** Main object containing extension method for registering annotated tools, prompts, and resources.
  * Delegates actual processing to specialized objects.
  */
object MCPRegistrationMacro:

  /** Extension method for FastMCPScala that scans an object for MCP annotations (@Tool, @Prompt,
    * \@Resource) and registers them with the server.
    */
  extension (server: FastMCPScala)

    /** Scan an object for methods with @Tool, @Prompt, or @Resource annotations and register them.
      *
      * @tparam T
      *   Type of the object containing annotated methods
      * @return
      *   The same FastMCPScala instance, for chaining
      */
    inline def scanAnnotations[T]: FastMCPScala =
      ${ scanAnnotationsImpl[T]('server) }

  /** Macro implementation for scanAnnotations
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
          toolAnnot.asExpr.asTerm
          ToolProcessor.processToolAnnotation(server, sym, method)
        case promptAnnot if promptAnnot.tpe <:< TypeRepr.of[Prompt] =>
          promptAnnot.asExpr.asTerm
          PromptProcessor.processPromptAnnotation(server, sym, method)
        case resourceAnnot if resourceAnnot.tpe <:< TypeRepr.of[Resource] =>
          val annotTerm = resourceAnnot.asExpr.asTerm
          ResourceProcessor.processResourceAnnotation(server, sym, method, annotTerm)
      }
    }

    // If no registrations, just return the server
    if registrationExprs.isEmpty then server
    else
      // Combine all registrations into one final block
      val registrationTerms = registrationExprs.map(_.asTerm)
      val block = Block(registrationTerms.init, registrationTerms.last)
      block.asExprOf[FastMCPScala]

end MCPRegistrationMacro // End main object
