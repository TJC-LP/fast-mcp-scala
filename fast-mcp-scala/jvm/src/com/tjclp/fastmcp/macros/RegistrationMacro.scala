package com.tjclp.fastmcp
package macros

import scala.quoted.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.server.*

/** Main object containing extension method for registering annotated tools, prompts, and resources.
  * Delegates actual processing to specialized objects.
  */
object RegistrationMacro:

  /** Extension method for FastMCPScala that scans an object for MCP annotations (@Tool, @Prompt,
    * \@Resource) and registers them with the server.
    */
  extension (server: FastMcpServer)

    /** Scan an object for methods with @Tool, @Prompt, or @Resource annotations and register them.
      *
      * @tparam T
      *   Type of the object containing annotated methods
      * @return
      *   The same FastMCPScala instance, for chaining
      */
    inline def scanAnnotations[T]: FastMcpServer =
      ${ scanAnnotationsImpl[T]('server) }

  /** Macro implementation for scanAnnotations
    */
  private def scanAnnotationsImpl[T: Type](
      server: Expr[FastMcpServer]
  )(using quotes: Quotes): Expr[FastMcpServer] =
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
    val registrationExprs: List[Expr[FastMcpServer]] = annotatedMethods.flatMap { method =>
      method.annotations.collectFirst {
        case toolAnnot if toolAnnot.tpe <:< TypeRepr.of[Tool] =>
          ToolProcessor.processToolAnnotation(server, sym, method)
        case promptAnnot if promptAnnot.tpe <:< TypeRepr.of[Prompt] =>
          PromptProcessor.processPromptAnnotation(server, sym, method)
        case resourceAnnot if resourceAnnot.tpe <:< TypeRepr.of[Resource] =>
          ResourceProcessor.processResourceAnnotation(server, sym, method)
      }
    }

    // If no registrations, just return the server
    if registrationExprs.isEmpty then server
    else
      // Combine all registrations into one final block
      val registrationTerms = registrationExprs.map(_.asTerm)
      val block = Block(registrationTerms.init, registrationTerms.last)
      block.asExprOf[FastMcpServer]

end RegistrationMacro // End main object
