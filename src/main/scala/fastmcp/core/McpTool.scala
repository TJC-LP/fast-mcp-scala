package fastmcp.core

import fastmcp.server.McpContext
import zio.*

/**
 * Base trait for all MCP tools.
 * 
 * This trait defines the core functionality for MCP tools, including
 * the handle method for executing the tool operation and the convertInput
 * method for converting from Map to Input type.
 * 
 * @tparam Input The input type for the tool
 * @tparam Output The output type for the tool
 */
trait McpTool[Input, Output]:
  /**
   * Process the tool's input and produce output.
   * 
   * @param input The typed input parameters
   * @param context Optional MCP context providing additional metadata
   * @return ZIO effect that completes with output or fails with error
   */
  def handle(input: Input, context: Option[McpContext]): ZIO[Any, Throwable, Output]
  
  /**
   * Convert a Map[String, Any] to the tool's Input type.
   * Default implementation attempts a direct cast, but tool implementations
   * should override this for robust conversion.
   * 
   * @param args The input arguments as a Map
   * @return ZIO effect with the converted Input or failure
   */
  def convertInput(args: Map[String, Any]): ZIO[Any, Throwable, Input] =
    ZIO.attempt {
      try {
        args.asInstanceOf[Input]
      } catch {
        case e: ClassCastException =>
          throw new IllegalArgumentException(s"Cannot convert Map to Input type: ${e.getMessage}. Consider overriding convertInput method.")
      }
    }