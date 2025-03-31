package fastmcp.macros

// Explicitly import Java's System to avoid conflicts with zio.System
import java.lang.{System => JSystem}
import scala.quoted.*
import fastmcp.core.*
import fastmcp.server.*
import io.modelcontextprotocol.spec.McpSchema
import zio.*

/**
 * Macro-based annotation processor for FastMCP-Scala
 * 
 * This implementation uses Scala 3's new macro system to process annotations
 * at compile time, generating code to register tools, resources, and prompts.
 * 
 * The macro system allows us to:
 * 1. Inspect annotated methods at compile time
 * 2. Extract type information for method parameters
 * 3. Generate JSON schemas automatically based on parameter types
 * 4. Create appropriate handlers that correctly map incoming arguments to method parameters
 * 5. Generate efficient code that doesn't rely on runtime reflection
 * 
 * Future enhancements:
 * - Process @Resource and @Prompt annotations
 * - Generate JSON schema for parameters based on their types
 * - Handle different return types and map them to appropriate MCP types
 * - Support context injection for methods that request it
 * - Support for all parameter types, including complex objects, lists, maps, etc.
 */
object MacroAnnotationProcessor:
  /**
   * Process all @Tool annotations in a given class at compile time
   * 
   * @param server The server to register tools with
   * @return A ZIO effect that registers all tools
   */
  inline def processToolAnnotations[T](server: FastMCPScala): ZIO[Any, Throwable, Unit] =
    ${ processToolAnnotationsImpl('server) }
  
  /**
   * Implementation of the processToolAnnotations macro
   * 
   * This method inspects the type T at compile time, finds all methods
   * annotated with @Tool, and generates code to register them as tools.
   */
  private def processToolAnnotationsImpl[T](server: Expr[FastMCPScala])(using Quotes, Type[T]): Expr[ZIO[Any, Throwable, Unit]] =
    import quotes.reflect.*
    
    // Log message for debugging
    JSystem.err.println(s"Processing tool annotations for ${TypeRepr.of[T].show}")
    
    /*
     * TODO: Full macro implementation would:
     * 1. Find all methods in T annotated with @Tool
     *    - Get method symbol from TypeRepr.of[T].typeSymbol
     *    - Filter methods with Tool annotation
     * 
     * 2. For each method:
     *    - Extract name and description from the annotation
     *    - Extract parameter information (names, types, default values)
     *    - Generate JSON schema based on parameter types
     * 
     * 3. Generate code to register each tool with the server
     *    - Create a tool handler that maps args to method parameters
     *    - Use appropriate server.tool(...) call
     *    - Combine all registration effects
     * 
     * 4. Return combined effect
     */
    
    // For now, we'll return a simple ZIO effect that logs to stderr
    '{
      ZIO.attemptBlocking {
        JSystem.err.println("[MacroProcessor] Tool annotations processed - full implementation coming soon")
      }.as(())
    }
  
  /**
   * Process all @Resource annotations in a given class at compile time
   * 
   * @param server The server to register resources with
   * @return A ZIO effect that registers all resources
   */
  inline def processResourceAnnotations[T](server: FastMCPScala): ZIO[Any, Throwable, Unit] =
    ${ processResourceAnnotationsImpl('server) }
  
  /**
   * Implementation of the processResourceAnnotations macro
   */
  private def processResourceAnnotationsImpl[T](server: Expr[FastMCPScala])(using Quotes, Type[T]): Expr[ZIO[Any, Throwable, Unit]] =
    // For now, we'll return a simple ZIO effect that logs to stderr
    '{
      ZIO.attemptBlocking {
        JSystem.err.println("[MacroProcessor] Resource annotations processed - full implementation coming soon")
      }.as(())
    }
  
  /**
   * Process all @Prompt annotations in a given class at compile time
   * 
   * @param server The server to register prompts with
   * @return A ZIO effect that registers all prompts
   */
  inline def processPromptAnnotations[T](server: FastMCPScala): ZIO[Any, Throwable, Unit] =
    ${ processPromptAnnotationsImpl('server) }
  
  /**
   * Implementation of the processPromptAnnotations macro
   */
  private def processPromptAnnotationsImpl[T](server: Expr[FastMCPScala])(using Quotes, Type[T]): Expr[ZIO[Any, Throwable, Unit]] =
    // For now, we'll return a simple ZIO effect that logs to stderr
    '{
      ZIO.attemptBlocking {
        JSystem.err.println("[MacroProcessor] Prompt annotations processed - full implementation coming soon")
      }.as(())
    }

/**
 * Convenience methods for using the macro annotation processor
 */
extension (server: FastMCPScala)
  /**
   * Process all annotated methods in a given class and register them with this server
   * 
   * @tparam T The class containing annotated methods
   * @return A ZIO effect that completes when all annotations are processed
   */
  inline def processAnnotations[T]: ZIO[Any, Throwable, Unit] =
    // Process tools first, then resources, then prompts
    for
      _ <- MacroAnnotationProcessor.processToolAnnotations[T](server)
      _ <- MacroAnnotationProcessor.processResourceAnnotations[T](server)
      _ <- MacroAnnotationProcessor.processPromptAnnotations[T](server)
    yield ()