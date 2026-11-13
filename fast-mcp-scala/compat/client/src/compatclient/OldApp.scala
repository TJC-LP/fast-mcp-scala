package compatclient

import zio.*
import zio.json.*

import com.tjclp.fastmcp.{*, given}

/** A server written against fast-mcp-scala 1.0.1: annotated methods next to typed contracts.
  *
  * Compiled against the released 1.0.1 jar (see [[OldSettings]]), so the annotation scan, the
  * schema derivation and the codec derivation below were all expanded by the 1.0.1 macros, and the
  * generated code calls the runtime helpers 1.0.1 had. `BinaryCompatibilityTest` builds this server
  * on the current classes and calls every registration through the router.
  */
object OldApp extends McpServerApp[Stdio, OldApp.type]:

  override def name: String = "old-app"

  override def settings: McpServerSettings =
    McpServerSettings(exposeTemplatesEndpoint = true, instructions = Some("compiled against 1.0.1"))

  enum Shape:
    case circle, square

  case class Label(
      @Param("The text of the label") text: String,
      @Param("Whether to upper-case it") upper: Boolean = false
  )

  @Tool(name = Some("add"), description = Some("Add two numbers"), readOnlyHint = Some(true))
  def add(@Param("First number") a: Int, @Param("Second number") b: Int): Int = a + b

  @Tool(name = Some("label"), description = Some("Render a label"))
  def label(@Param("The label") label: Label): String =
    if label.upper then label.text.toUpperCase else label.text

  @Tool(name = Some("area"), description = Some("Area of a unit shape"))
  def area(shape: Shape, @Param("Scale", required = false) scale: Option[Double]): String =
    val unit = shape match
      case Shape.circle => math.Pi
      case Shape.square => 1.0
    f"${unit * scale.getOrElse(1.0)}%.2f"

  @Tool(name = Some("effect"), description = Some("A ZIO-returning tool"))
  def effect(@Param("Word") word: String): ZIO[Any, Throwable, String] =
    ZIO.succeed(word.reverse)

  @Tool(name = Some("who"), description = Some("A tool that reads its context"))
  def who(ctx: McpContext): String =
    s"client=${ctx.getClientInfo.map(_.name).getOrElse("none")}"

  /** `y` has a Scala default and no `required = false`: 1.0.1 lists it in `required` and its
    * handler applies the default when the argument is missing.
    */
  @Tool(name = Some("defaulted"), description = Some("A parameter with a Scala default"))
  def defaulted(@Param("Left") x: Int, @Param("Right") y: Int = 10): Int = x + y

  @Resource(uri = "old://welcome", name = Some("Welcome"), description = Some("A static resource"))
  def welcome(): String = "welcome from 1.0.1"

  @Resource(uri = "old://users/{userId}", name = Some("User"), mimeType = Some("text/plain"))
  def user(@Param("The user id") userId: String): String = s"user $userId"

  @Prompt(name = Some("greet"), description = Some("A greeting prompt"))
  def greet(@Param("Who to greet") name: String): List[Message] =
    List(Message(role = Role.User, content = TextContent(s"Greet $name.")))

  case class SumArgs(@Param("Left operand") left: Int, @Param("Right operand") right: Int)
  case class SumResult(sum: Int)
  given JsonEncoder[SumResult] = DeriveJsonEncoder.gen[SumResult]

  case class NameArgs(@Param("A name") name: String)
  case class ScaleArgs(@Param("Value") value: Int, @Param("Factor") factor: Int = 3)

  override val tools: List[McpTool[?, ?]] = List(
    McpTool[SumArgs, SumResult](name = "typed-sum", description = Some("Typed sum")) { args =>
      SumResult(args.left + args.right)
    }.withOutputSchema,
    McpTool[NameArgs, String](name = "typed-zio") { args => ZIO.succeed(s"hi ${args.name}") },
    McpTool[ScaleArgs, Int](name = "typed-defaulted") { args => args.value * args.factor }
  )

  override val prompts: List[McpPrompt[?]] = List(
    McpPrompt[NameArgs](
      name = "typed-greet",
      arguments = List(PromptArgument("name", Some("A name"), required = true))
    ) { args => List(Message(Role.User, TextContent(s"Hello ${args.name}!"))) }
  )

  override val staticResources: List[McpStaticResource] = List(
    McpStaticResource(uri = "old://typed", description = Some("A typed static resource"))("typed")
  )

  override val templateResources: List[McpTemplateResource[?]] = List(
    McpTemplateResource[NameArgs](
      uriPattern = "old://typed/{name}",
      arguments = List(ResourceArgument("name", Some("A name"), required = true))
    ) { args => s"typed ${args.name}" }
  )
