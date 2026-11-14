package downstream.unitcompat

import zio.*

import com.tjclp.fastmcp.{*, given}

/** Compile-only: 1.0.x had no `McpDecoder[Unit]`, so users defined their own. The library's
  * instance lives in the `McpDecoder` companion (implicit scope), so a user's instance in lexical
  * scope, at package level or in an object, still wins instead of becoming ambiguous with it.
  */
given userUnit: McpDecoder[Unit] = McpDecoder.instance[Unit]((_, _, _) => ())

object PackageLevelUnitDecoder:
  val tool = McpTool[Unit, String](name = "ping")(_ => ZIO.succeed("pong"))

object ObjectLevelUnitDecoder:
  given localUnit: McpDecoder[Unit] = userUnit
  val tool = McpTool[Unit, String](name = "ping2")(_ => ZIO.succeed("pong"))
