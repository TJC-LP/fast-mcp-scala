package com.tjclp.fastmcp
package server.transport


import zio.*

import com.tjclp.fastmcp.{*, given}

/** Subprocess fixtures for [[StdioStdoutHygieneTest]]. Each is a real stdio server started with
  * `java -cp <test classpath> <main>` because the property under test — "nothing but JSON-RPC
  * frames ever reaches `System.out`" — only exists in a process whose `System.out` IS the wire.
  * (In-process, ZIO's default logger writes through `scala.Console.out`, which captured the
  * original stream long before a test could swap it.) Every tool logs through `ZIO.logInfo`, the
  * exact shape of the D2 dogfooding `LogTool`.
  */
object LoggingStdioApp extends McpServerApp[Stdio, LoggingStdioApp.type]:

  @Tool(name = Some("add"), description = Some("Add two numbers (logs via ZIO.logInfo)"))
  def add(@Param("First operand") a: Int, @Param("Second operand") b: Int): ZIO[Any, Nothing, Int] =
    ZIO.logInfo("adding") *> ZIO.succeed(a + b)

/** The lower-level path the README/CLAUDE.md quickstart uses: a plain `ZIOAppDefault` that builds
  * an `McpServer`, scans annotations and calls `runStdio()` directly — no `McpServerApp`, so no
  * `bootstrap` layer of ours is involved.
  */
object PlainRunStdioApp extends ZIOAppDefault:

  @Tool(name = Some("add"), description = Some("Add two numbers (logs via ZIO.logInfo)"))
  def add(@Param("First operand") a: Int, @Param("Second operand") b: Int): ZIO[Any, Nothing, Int] =
    ZIO.logInfo("adding") *> ZIO.succeed(a + b)

  override def run: ZIO[Any, Throwable, Unit] =
    for
      server <- ZIO.succeed(McpServer("PlainRunStdioApp"))
      _ <- ZIO.attempt(server.scanAnnotations[PlainRunStdioApp.type])
      _ <- server.runStdio()
    yield ()

/** The documented override path: a user-installed logger (here: stderr with a `CUSTOM ` prefix)
  * must be the ONLY logger — the runner must neither clobber it nor add a second stderr logger.
  */
object CustomLoggerStdioApp extends McpServerApp[Stdio, CustomLoggerStdioApp.type]:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers ++
      Runtime.addLogger(ZLogger.default.map(line => java.lang.System.err.println("CUSTOM " + line)))

  @Tool(name = Some("add"), description = Some("Add two numbers (logs via ZIO.logInfo)"))
  def add(@Param("First operand") a: Int, @Param("Second operand") b: Int): ZIO[Any, Nothing, Int] =
    ZIO.logInfo("adding") *> ZIO.succeed(a + b)

/** [[LoggingStdioApp]] plus foreign `System.out` writers: two daemon threads spamming
  * `println("noise")` flat out for the life of the process — the worst case for a stdio server
  * whose stdout is shared with user code. A frame written in ONE `PrintStream` call survives this
  * as a frame surrounded by whole `noise` lines; a frame written in several calls is split
  * (`{...}noise` + an empty line) and lost to the client — against the pre-fix `writeLine` the D2
  * stress harness lost 90-183 of 201 replies per run this way.
  */
object NoisyStdioApp:

  val Noise = "noise"

  def main(args: Array[String]): Unit =
    (1 to 2).foreach { n =>
      val t = new Thread(() => while true do java.lang.System.out.println(Noise), s"stdout-noise-$n")
      t.setDaemon(true)
      t.start()
    }
    LoggingStdioApp.main(args)
