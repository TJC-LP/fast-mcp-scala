package downstream.probe

import com.tjclp.fastmcp.{*, given}
import zio.*

// A 1.0.x user's workaround for the missing McpDecoder[Unit], at package level.
given userUnit: McpDecoder[Unit] = McpDecoder.instance[Unit]((_, _, _) => Right(()))

object PackageLevel:
  val tool = McpTool[Unit, String](name = "ping")(_ => ZIO.succeed("pong"))

object ObjectLevel:
  given localUnit: McpDecoder[Unit] = userUnit
  val tool = McpTool[Unit, String](name = "ping2")(_ => ZIO.succeed("pong"))
