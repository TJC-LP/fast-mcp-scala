package fastmcp.macros

// Explicitly import Java's System to avoid conflicts with zio.System

import fastmcp.server.FastMCPScala
import zio.*

/**
 * Simple annotation processor for FastMCP-Scala
 *
 * This is a placeholder implementation that logs the annotation processing but
 * doesn't actually process the annotations. It will be replaced with a more robust
 * implementation in the future.
 */
object MacroAnnotationProcessor:
  /**
   * Process all @Tool annotations in a given class
   *
   * @param server The server to register tools with
   * @return A ZIO effect that registers all tools
   */
  def processToolAnnotations[T](server: FastMCPScala): ZIO[Any, Throwable, Unit] =
    ZIO.logInfo(s"[MacroProcessor] Processing tool annotations") *>
      ZIO.succeed(())

  /**
   * Process all @Resource annotations in a given class
   *
   * @param server The server to register resources with
   * @return A ZIO effect that registers all resources
   */
  def processResourceAnnotations[T](server: FastMCPScala): ZIO[Any, Throwable, Unit] =
    ZIO.logInfo(s"[MacroProcessor] Processing resource annotations") *>
      ZIO.succeed(())

  /**
   * Process all @Prompt annotations in a given class
   *
   * @param server The server to register prompts with
   * @return A ZIO effect that registers all prompts
   */
  def processPromptAnnotations[T](server: FastMCPScala): ZIO[Any, Throwable, Unit] =
    ZIO.logInfo(s"[MacroProcessor] Processing prompt annotations") *>
      ZIO.succeed(())

/**
 * Convenience methods for using the annotation processor
 */
extension (server: FastMCPScala)
  /**
   * Process all annotated methods in a given class and register them with this server
   *
   * @tparam T The class containing annotated methods
   * @return A ZIO effect that completes when all annotations are processed
   */
  def processAnnotations[T]: ZIO[Any, Throwable, Unit] =
    // Process tools first, then resources, then prompts
    for
      _ <- MacroAnnotationProcessor.processToolAnnotations[T](server)
      _ <- MacroAnnotationProcessor.processResourceAnnotations[T](server)
      _ <- MacroAnnotationProcessor.processPromptAnnotations[T](server)
    yield ()