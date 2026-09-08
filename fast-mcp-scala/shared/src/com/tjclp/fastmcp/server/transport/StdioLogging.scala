package com.tjclp.fastmcp
package server.transport

import zio.*

/** Logging policy of the stdio transport: **stdout is the wire, so logs go to stderr.**
  *
  * ZIO's default logger prints to stdout — `println` on the JVM and Scala Native, `console.log` on
  * Scala.js/Bun for every level below Error — so a plain `ZIO.logInfo` inside a tool handler would
  * write a non-JSON line onto the channel the client is parsing: a stray line at best, and under
  * load a line spliced into a reply frame that the client can never parse (D2 dogfooding,
  * TJC-2338). This object is the one definition of the fix, shared by all three platforms:
  *
  *   - [[bootstrap]] is the `ZLayer` `McpServerApp[Stdio]` installs as its ZIO `bootstrap` (via
  *     `TransportRunner.stdio.bootstrap`): ZIO's default loggers removed, [[stderrLogger]] added.
  *   - [[redirectDefaultLoggers]] is the safety net inside `McpServer.runStdio()` for servers that
  *     do not go through `McpServerApp` (a `ZIOAppDefault` calling `runStdio()` directly): if ZIO's
  *     stock stdout logger is still installed when the stdio loop starts, it is swapped for
  *     [[stderrLogger]] for the loop's lifetime. Loggers installed by the user are never touched —
  *     a `bootstrap` that removed the defaults and added its own logger sees no change.
  *
  * To use a different logger, override `bootstrap` on the `McpServerApp` (as a `val`, which is how
  * `ZIOAppDefault` declares it):
  * {{{
  *   override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
  *     Runtime.removeDefaultLoggers ++ Runtime.addLogger(myLogger)
  * }}}
  */
object StdioLogging:

  /** ZIO's default line format at the default verbosity (Info and above), written to `System.err` —
    * `console.error` on Scala.js, the process's stderr on the JVM and Scala Native.
    */
  val stderrLogger: ZLogger[String, Any] =
    ZLogger.default
      .map(line => java.lang.System.err.println(line))
      .filterLogLevel(_ >= LogLevel.Info)

  /** Replace ZIO's default (stdout) loggers with [[stderrLogger]]; the default `bootstrap` of every
    * `McpServerApp[Stdio]`.
    */
  val bootstrap: ZLayer[Any, Nothing, Unit] =
    Runtime.removeDefaultLoggers ++ Runtime.addLogger(stderrLogger)

  /** Run `effect` with [[stderrLogger]] in place of ZIO's default logger when — and only when — a
    * default logger is still among the current loggers. Loggers the user installed are kept.
    */
  def redirectDefaultLoggers[R, E, A](effect: ZIO[R, E, A])(using Trace): ZIO[R, E, A] =
    ZIO.loggersWith { loggers =>
      if loggers.exists(Runtime.defaultLoggers.contains) then
        ZIO.scoped[R](bootstrap.build *> effect)
      else effect
    }
