//> using scala 3.8.3
//> using dep com.tjclp::fast-mcp-scala:0.3.0
//> using options "-Xcheck-macros" "-experimental"

import com.tjclp.fastmcp.*
import zio.*

object Example:

  @Tool(name = Some("add"), description = Some("Add two numbers"), readOnlyHint = Some(true))
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
      server <- ZIO.succeed(McpServer("ExampleServer", "0.3.0"))
      _      <- ZIO.attempt(server.scanAnnotations[Example.type])
      _      <- server.runStdio()
    yield ()

ExampleServer.main(args)
