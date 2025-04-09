package fastmcp.macros

import fastmcp.core.*
import fastmcp.server.FastMCPScala
import fastmcp.server.manager.*
import zio.*
import zio.json.*
import io.circe.Json
import io.modelcontextprotocol.spec.McpSchema
import java.lang.System as JSystem
import java.lang.reflect.Method
import scala.annotation.{experimental, tailrec}
import scala.quoted.*
import scala.reflect.ClassTag
import scala.util.Try

/**
 * Macro implementation and runtime methods for MCP tool registration
 */
object McpToolRegistrationMacro {
  /**
   * Extension methods for FastMCPScala
   */
  extension (server: FastMCPScala) {
    /**
     * Register a tool directly from a function with @Tool annotation using a macro
     *
     * This is a streamlined approach that:
     * 1. Extracts metadata from @Tool annotation if present at compile time
     * 2. Generates JSON schema from function signature at compile time
     * 3. Creates a type-safe handler that converts Map[String, Any] to function arguments
     * 4. Wraps non-ZIO results in ZIO.succeed
     * 5. Registers the tool with the server
     *
     * @param function The function to register as a tool
     * @param optToolName Optional name override for the tool
     * @param optToolDesc Optional description override for the tool
     * @param options Optional registration options
     * @return ZIO effect that completes with the FastMCPScala instance
     */
    @experimental
    transparent inline def annotatedTool[F](
        inline function: F,
        optToolName: Option[String] = None,
        optToolDesc: Option[String] = None,
        options: ToolRegistrationOptions = ToolRegistrationOptions()
    ): ZIO[Any, Throwable, FastMCPScala] =
      ${ annotatedToolImpl('function, 'optToolName, 'optToolDesc, 'options, 'server) }
  }

  /**
   * Macro implementation for annotatedTool
   */
  @experimental
  private def annotatedToolImpl[F: Type](
      functionExpr: Expr[F],
      optToolNameExpr: Expr[Option[String]],
      optToolDescExpr: Expr[Option[String]],
      optionsExpr: Expr[ToolRegistrationOptions],
      serverExpr: Expr[FastMCPScala]
  )(using quotes: Quotes): Expr[ZIO[Any, Throwable, FastMCPScala]] = {
    import quotes.reflect.*

    /**
     * Find method information through Scala 3 AST analysis
     */
    def findMethodInfo(term: Term): (String, Option[String], Option[String]) = {
      // Default values
      var methodName = "anonTool"
      var toolName: Option[String] = None
      var toolDesc: Option[String] = None
      
      def extractOptString(term: Term): Option[String] = term match {
        case Apply(Select(Ident("Some"), "apply"), List(Literal(StringConstant(s)))) =>
          Some(s)
        case Select(Ident("None"), "MODULE$") =>
          None
        case _ => 
          report.warning(s"Unknown term pattern in option extraction: ${term.show}")
          None
      }

      // Helper to extract annotation parameters
      def extractToolAnnotation(annot: Term): Unit = annot match {
        case Apply(_, args) =>
          args.foreach {
            case NamedArg("name", value) =>
              extractOptString(value).foreach(n => toolName = Some(n))
            
            case NamedArg("description", value) =>
              extractOptString(value).foreach(d => toolDesc = Some(d))
            
            case _ => // Ignore other fields
          }
        case _ => // Not an Apply node
      }

      // Process the term to find method symbol and annotations
      term match {
        // Handle different method reference patterns
        case Inlined(_, _, t) => 
          return findMethodInfo(t)
          
        case Block(_, expr) =>
          return findMethodInfo(expr)
          
        case Select(_, _) if term.symbol.isDefDef =>
          methodName = term.symbol.name
          // Look for Tool annotation
          term.symbol.annotations.find(_.tpe <:< TypeRepr.of[Tool]).foreach(extractToolAnnotation)
          
        case Ident(_) if term.symbol.isDefDef =>
          methodName = term.symbol.name
          // Look for Tool annotation
          term.symbol.annotations.find(_.tpe <:< TypeRepr.of[Tool]).foreach(extractToolAnnotation)
          
        case Closure(methRef, _) if methRef.symbol.isDefDef =>
          methodName = methRef.symbol.name
          // Look for Tool annotation
          methRef.symbol.annotations.find(_.tpe <:< TypeRepr.of[Tool]).foreach(extractToolAnnotation)
          
        case _ =>
          // For other cases, try to extract a reasonable name from the function expression
          methodName = term.show.replaceAll("[^a-zA-Z0-9]", "")
                        .takeWhile(_ != '(').trim match {
                          case "" => "anonTool"
                          case s => s
                        }
      }
      
      (methodName, toolName, toolDesc)
    }
    
    // Extract method information
    val (methodName, annotToolName, annotToolDesc) = findMethodInfo(functionExpr.asTerm)
    
    // Extract and prioritize tool name - with proper String type
    val nameExpr: Expr[String] = optToolNameExpr match {
      case '{ Some($name: String) } => name
      case '{ None } => 
        annotToolName match {
          case Some(name) => Expr(name)
          case None => Expr(methodName)
        }
      case _ => Expr(methodName)
    }
    
    // Extract and prioritize tool description - with proper Option[String] type
    val descExpr: Expr[Option[String]] = optToolDescExpr match {
      case '{ Some($desc: String) } => '{Some($desc)}
      case '{ None } => 
        annotToolDesc match {
          case Some(desc) => Expr(Some(desc))
          case None => '{None}
        }
      case _ => '{None}
    }

    // Generate schema from function signature
    val schemaJsonExpr = '{ 
      JsonSchemaMacro.schemaForFunctionArgs($functionExpr) 
    }
    val schemaStringExpr = '{
      $schemaJsonExpr.spaces2
    }

    // Build the handler: Map[String, Any] => ZIO[Any, Throwable, Any]
    val handlerExpr = '{
      (args: Map[String, Any]) => 
        ZIO.attempt {
          val mappedFn = MapToFunctionMacro.callByMap($functionExpr)
          mappedFn.asInstanceOf[Map[String, Any] => Any](args)
        }
    }

    // Return code that registers the tool on the server
    '{
      // Log registration
      ZIO.succeed(JSystem.err.println(s"[McpToolRegistrationMacro] Registering tool: " + $nameExpr)).flatMap { _ =>
        $serverExpr.tool(
          name = $nameExpr,
          description = $descExpr,
          handler = $handlerExpr,
          inputSchema = Right($schemaStringExpr),
          options = $optionsExpr
        )
      }
    }
  }
}