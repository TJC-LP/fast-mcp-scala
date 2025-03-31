package fastmcp

// Explicitly use java.lang.System to avoid conflicts with zio.System
import java.lang.{System => JSystem}
import zio.*
import fastmcp.examples.{SimpleServer, AnnotatedServer}

/**
 * Main entry point for FastMCP-Scala
 * 
 * This object serves as the application's main entry point using ZIOAppDefault.
 * Since MCP uses stdin/stdout for communication, user messages and
 * logs are redirected to stderr or a log file.
 */
object FastMCPMain extends ZIOAppDefault:
  /**
   * ZIO Application entry point
   * This works automatically with SBT and ZIO's runtime
   */
  override def run =
    for
      // Setup phase
      _ <- setupLogging
      args <- getArgs
      
      // Parse command line arguments
      serverType = if args.isEmpty then "simple" else args(0)
      
      _ <- ZIO.logInfo(s"FastMCP-Scala starting with server type: $serverType")
      
      // Run the appropriate server
      result <- serverType.toLowerCase match
        case "simple" => 
          ZIO.logInfo("Running SimpleServer...") *>
          SimpleServer.run
          
        case "annotated" =>
          ZIO.logInfo("Running AnnotatedServer...") *>
          AnnotatedServer.run
          
        case _ =>
          ZIO.logError(s"Unknown server type: $serverType (valid options: simple, annotated)") *>
          ZIO.fail(new IllegalArgumentException(s"Unknown server type: $serverType"))
    yield result

  /**
   * Set up logging to redirect stdout to stderr for MCP communication
   */
  private def setupLogging =
    ZIO.attemptBlocking {
      // Always redirect stdout to stderr for MCP compatibility
      JSystem.setOut(JSystem.err)
      
      // Log to stderr
      JSystem.err.println("[FastMCP] Redirected stdout to stderr for MCP compatibility")
    } *> 
    // Log confirmation
    ZIO.logInfo("STDOUT and ZIO logging redirected to STDERR for MCP communication")
end FastMCPMain