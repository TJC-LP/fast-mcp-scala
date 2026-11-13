package compatclient

import java.time.Duration

import com.tjclp.fastmcp.*

/** Settings code as a program written against fast-mcp-scala 1.0.1 has it.
  *
  * This module is COMPILED AGAINST THE RELEASED 1.0.1 JAR (`fast-mcp-scala.compat.client` in
  * `package.mill`), so every call below is linked to a 1.0.1 signature: the 17-field
  * `McpServerSettings`, the 11-field `TaskSettings`, the 5-field `LimitSettings`, their default
  * getters, `copy` and extractors. `BinaryCompatibilityTest` runs it on the current classes. Do not
  * use anything newer than 1.0.1 here.
  */
object OldSettings:

  def serverDefaults(): McpServerSettings = McpServerSettings()

  def serverNamed(): McpServerSettings =
    McpServerSettings(port = 9001, instructions = Some("old"))

  def serverWithNew(): McpServerSettings = new McpServerSettings(host = "0.0.0.0")

  def serverPositional(): McpServerSettings =
    McpServerSettings(
      "old.example",
      9002,
      "/old",
      true,
      true,
      Some(Duration.ofSeconds(5)),
      None,
      true,
      true,
      true,
      Some(Set("old.example")),
      Some(Set("https://old.example")),
      4096,
      Some(7),
      TaskSettings(enabled = true),
      LimitSettings(maxDepth = 9),
      Some("positional")
    )

  def serverCopy(settings: McpServerSettings): McpServerSettings = settings.copy(port = 9003)

  /** A 17-pattern extractor: first, second and last field. */
  def serverFields(settings: McpServerSettings): (String, Int, Option[String]) =
    settings match
      case McpServerSettings(host, port, _, _, _, _, _, _, _, _, _, _, _, _, _, _, instructions) =>
        (host, port, instructions)

  def taskDefaults(): TaskSettings = TaskSettings()

  def taskNamed(): TaskSettings = TaskSettings(enabled = true, maxStoredTotal = 11)

  def taskPositional(): TaskSettings =
    TaskSettings(true, 1000L, 2000L, 50L, 2, 4, 3, 5, 10L, 20L, TaskOwnerKey.Transport)

  def taskCopy(settings: TaskSettings): TaskSettings = settings.copy(pollIntervalMs = 77L)

  def taskFields(settings: TaskSettings): (Boolean, Long, TaskOwnerKey) =
    settings match
      case TaskSettings(enabled, defaultTtlMs, _, _, _, _, _, _, _, _, ownerKey) =>
        (enabled, defaultTtlMs, ownerKey)

  def limitDefaults(): LimitSettings = LimitSettings()

  def limitNamed(): LimitSettings = LimitSettings(maxDepth = 12)

  def limitPositional(): LimitSettings = LimitSettings(1000, 10, 20, 30, 40)

  def limitCopy(settings: LimitSettings): LimitSettings = settings.copy(maxUriChars = 99)

  def limitFields(settings: LimitSettings): (Int, Int) =
    settings match
      case LimitSettings(maxFrameChars, _, _, _, maxSubscriptionsPerSession) =>
        (maxFrameChars, maxSubscriptionsPerSession)

  /** What a type class derived in 1.0.1 from the case class `Mirror` does to build a value: it
    * hands `fromProduct` the five fields 1.0.1 knew.
    */
  def limitFromMirror(): LimitSettings =
    summon[scala.deriving.Mirror.ProductOf[LimitSettings]].fromProduct((1000, 10, 20, 30, 40))
