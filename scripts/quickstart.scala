//> using scala 3.6.4
//> using repository m2Local
//> using dep com.tjclp::fast-mcp-scala:0.1.2-SNAPSHOT
//> using options "-Xcheck-macros" "-experimental"

import com.tjclp.fastmcp.core.{Tool, ToolParam, Prompt, PromptParam, Resource, ResourceParam}
import com.tjclp.fastmcp.server.FastMcpServer
import com.tjclp.fastmcp.macros.RegistrationMacro.*
import zio.*

// Define annotated tools, prompts, and resources
object Example:

  @Tool(name = Some("add"), description = Some("Add two numbers"))
  def add(
      @ToolParam("First operand") a: Double,
      @ToolParam("Second operand") b: Double
  ): Double = a + b

  @Prompt(name = Some("greet"), description = Some("Generate a greeting message"))
  def greet(@PromptParam("Name to greet") name: String): String =
    s"Hello, $name!"

  @Resource(uri = "file://test", description = Some("Test resource"))
  def test(): String = "This is a test"

  @Resource(uri = "user://{userId}", description = Some("Test resource"))
  def getUser(@ResourceParam("The user id") userId: String): String = s"User ID: $userId"

object ExampleServer extends ZIOAppDefault:

  override def run =
    for
      server <- ZIO.succeed(FastMcpServer("ExampleServer", "0.1.1"))
      _ <- ZIO.attempt(server.scanAnnotations[Example.type])
      _ <- server.runStdio()
    yield ()
