//> using scala 3.7.2
//> using dep com.tjclp::fast-mcp-scala:0.2.1-SNAPSHOT
//> using options "-Xcheck-macros" "-experimental"

import com.tjclp.fastmcp.core.{Tool, Param, Prompt, Resource}
import com.tjclp.fastmcp.server.FastMcpServer
import com.tjclp.fastmcp.macros.RegistrationMacro.*
import zio.*

// Define annotated tools, prompts, and resources
object Example:

  @Tool(name = Some("add"), description = Some("Add two numbers"))
  def add(
      @Param("First operand") a: Double,
      @Param("Second operand") b: Double
  ): Double = a + b

  @Prompt(name = Some("greet"), description = Some("Generate a greeting message"))
  def greet(@Param("Name to greet") name: String): String =
    s"Hello, $name!"

  @Resource(uri = "file://test", description = Some("Test resource"))
  def test(): String = "This is a test"

  @Resource(uri = "user://{userId}", description = Some("Test resource"))
  def getUser(@Param("The user id") userId: String): String = s"User ID: $userId"

object ExampleServer extends ZIOAppDefault:

  override def run =
    for
      server <- ZIO.succeed(FastMcpServer("ExampleServer", "0.2.0"))
      _ <- ZIO.attempt(server.scanAnnotations[Example.type])
      _ <- server.runStdio()
    yield ()

ExampleServer.main(args)
