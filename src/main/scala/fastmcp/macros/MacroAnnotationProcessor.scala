package fastmcp.macros

import fastmcp.server.FastMCPScala
import zio.*
import java.lang.{System => JSystem}
import scala.reflect.ClassTag

/**
 * Annotation processor for FastMCP-Scala
 *
 * This implementation is a bridge between ZIO-based code and the macro-driven 
 * compile-time annotation processing. It provides both inline methods for direct 
 * use and ZIO-wrapped versions for integration with FastMCPScala.
 * 
 * Note: This is a simplified implementation for now. Later we'll add more robust
 * features for annotation processing.
 */
object MacroAnnotationProcessor:
  /**
   * Process all @Tool annotations in a given class
   *
   * @param server The server to register tools with
   * @return A ZIO effect that registers all tools
   */
  def processToolAnnotations[T: ClassTag](server: FastMCPScala): ZIO[Any, Throwable, Unit] =
    ZIO.logInfo(s"[MacroProcessor] Processing tool annotations...") *>
      ZIO.attempt {
        // Call the macro-based annotation processor (simplified implementation)
        ToolMacros.processAnnotations[T](server)
      }

  /**
   * Process all @Resource annotations in a given class
   *
   * @param server The server to register resources with
   * @return A ZIO effect that registers all resources
   */
  def processResourceAnnotations[T](server: FastMCPScala): ZIO[Any, Throwable, Unit] =
    ZIO.logInfo(s"[MacroProcessor] Processing resource annotations...") *>
      ZIO.succeed(()) // Not implemented yet

  /**
   * Process all @Prompt annotations in a given class
   *
   * @param server The server to register prompts with
   * @return A ZIO effect that registers all prompts
   */
  def processPromptAnnotations[T](server: FastMCPScala): ZIO[Any, Throwable, Unit] =
    ZIO.logInfo(s"[MacroProcessor] Processing prompt annotations...") *>
      ZIO.succeed(()) // Not implemented yet

/**
 * Convenience extension methods for using the annotation processor
 */
extension (server: FastMCPScala)
  /**
   * Process all annotated methods in a given class and register them with this server
   * This uses ZIO effects for integration with FastMCPScala
   *
   * @tparam T The class containing annotated methods
   * @return A ZIO effect that completes when all annotations are processed
   */
  def processAnnotations[T: ClassTag]: ZIO[Any, Throwable, Unit] =
    // Process tools first, then resources, then prompts
    for
      _ <- MacroAnnotationProcessor.processToolAnnotations[T](server)
      _ <- MacroAnnotationProcessor.processResourceAnnotations[T](server)
      _ <- MacroAnnotationProcessor.processPromptAnnotations[T](server)
    yield ()
    
  /**
   * Process all annotated methods in a given class and register them with this server
   * This is an inline method that uses macros directly (simplified implementation)
   *
   * @tparam T The class containing annotated methods
   */
  inline def scanAnnotations[T]: Unit =
    JSystem.err.println(s"[FastMCPScala] Scanning for @Tool annotations in ${server.name}...")
    ToolMacros.processAnnotations[T](server)