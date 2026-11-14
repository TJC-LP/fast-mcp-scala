package com.tjclp.fastmcp
package server.transport

import scala.concurrent.{ExecutionContext, Future}

import org.scalatest.funsuite.AsyncFunSuite
import zio.*

/** The stdio logging policy — stdout is the wire, so ZIO's default logger is replaced by one on
  * stderr — checked on the logger set itself, on every platform: this file is compiled into the
  * JVM, Scala.js and Scala Native test modules (`shared/test/src`).
  *
  * The JVM also proves the outcome on a real process (`StdioStdoutHygieneTest`). No such process
  * test exists for Bun or Scala Native, and Bun is where the default logger differs most
  * (`console.log` for every level below Error), so the rule is pinned here where all three run it.
  */
class StdioLoggingSpec extends AsyncFunSuite:

  // ScalaTest's default serial context only runs what is queued on it; on Scala.js a future that
  // ZIO completes from its own scheduler never gets there ("Queue is empty while future is not
  // completed"). The global context works on all three platforms.
  override implicit val executionContext: ExecutionContext = ExecutionContext.global

  private def run[A](effect: Task[A]): Future[A] =
    Unsafe.unsafe(implicit unsafe => Runtime.default.unsafe.runToFuture(effect))

  private def usesDefaultLogger(loggers: Set[ZLogger[String, Any]]): Boolean =
    loggers.exists(Runtime.defaultLoggers.contains)

  test("the runtime starts with ZIO's default logger (what the stdio runner has to replace)") {
    run(ZIO.loggers).map(loggers => assert(usesDefaultLogger(loggers)))
  }

  test("redirectDefaultLoggers: no default logger remains and the stderr logger is installed") {
    run(StdioLogging.redirectDefaultLoggers(ZIO.loggers)).map { loggers =>
      assert(!usesDefaultLogger(loggers))
      assert(loggers.contains(StdioLogging.stderrLogger))
    }
  }

  test("redirectDefaultLoggers: the swap lasts as long as the wrapped effect, no longer") {
    run(StdioLogging.redirectDefaultLoggers(ZIO.unit) *> ZIO.loggers).map { loggers =>
      assert(usesDefaultLogger(loggers))
      assert(!loggers.contains(StdioLogging.stderrLogger))
    }
  }

  test("redirectDefaultLoggers: loggers the application installed are left exactly as they are") {
    val mine: ZLogger[String, Any] = ZLogger.default.map(_ => ())
    val installed = Runtime.removeDefaultLoggers ++ Runtime.addLogger(mine)
    run(
      (ZIO.loggers <*> StdioLogging.redirectDefaultLoggers(ZIO.loggers)).provideLayer(installed)
    ).map { (before, inside) =>
      assert(before.contains(mine))
      assert(inside == before)
      assert(!inside.contains(StdioLogging.stderrLogger))
    }
  }

  test("bootstrap (the McpServerApp[Stdio] layer): stderr logger in, default loggers out") {
    run(ZIO.loggers.provideLayer(StdioLogging.bootstrap)).map { loggers =>
      assert(!usesDefaultLogger(loggers))
      assert(loggers.contains(StdioLogging.stderrLogger))
    }
  }
