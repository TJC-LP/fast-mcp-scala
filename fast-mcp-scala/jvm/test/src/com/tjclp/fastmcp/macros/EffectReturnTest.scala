package com.tjclp.fastmcp
package macros

import scala.util.{Failure, Success, Try}

import org.scalatest.funsuite.AnyFunSuite
import zio.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.JacksonConverter.given
import com.tjclp.fastmcp.macros.RegistrationMacro.scanAnnotations
import com.tjclp.fastmcp.server.*

/** Verifies that `@Tool` / `@Resource` / `@Prompt` macros honor effect-shaped return types (ZIO,
  * Try, Either[Throwable, _]) instead of stringifying the unexecuted effect — issue #50.
  */
class EffectReturnTest extends AnyFunSuite:

  private def runTool(server: FastMcpServer, name: String, args: Map[String, Any]): Any =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(server.toolManager.callTool(name, args, None))
        .getOrThrowFiberFailure()
    }

  private def runToolExit(
      server: FastMcpServer,
      name: String,
      args: Map[String, Any]
  ): Exit[Throwable, Any] =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(server.toolManager.callTool(name, args, None))
    }

  test("@Tool returning UIO[Int] executes the effect (issue #50 repro)") {
    val server = new FastMcpServer("EffectReturnUio", "0.1.0")
    server.scanAnnotations[EffectReturnTest.type]

    val result = runTool(server, "addZio", Map("a" -> 3, "b" -> 4))
    assert(result == 7)
    assert(!result.toString.contains("ZIO"), s"Result should not be a ZIO toString: $result")
  }

  test("@Tool returning Task[String] executes and returns the value") {
    val server = new FastMcpServer("EffectReturnTask", "0.1.0")
    server.scanAnnotations[EffectReturnTest.type]

    val result = runTool(server, "shoutZio", Map("text" -> "hello"))
    assert(result == "HELLO")
  }

  test("@Tool returning failed Task surfaces the Throwable") {
    val server = new FastMcpServer("EffectReturnTaskFail", "0.1.0")
    server.scanAnnotations[EffectReturnTest.type]

    val exit = runToolExit(server, "boomZio", Map.empty)
    assert(exit.isFailure, s"Expected failure exit, got: $exit")
    val cause = exit.causeOption.get
    val defects = cause.failures ++ cause.defects
    assert(
      defects.exists(_.getMessage.contains("kaboom")),
      s"Expected Throwable with 'kaboom', got: $defects"
    )
  }

  test("@Tool returning IO[String, Int] wraps non-Throwable failures in RuntimeException") {
    val server = new FastMcpServer("EffectReturnIo", "0.1.0")
    server.scanAnnotations[EffectReturnTest.type]

    val exit = runToolExit(server, "divideIo", Map("a" -> 10, "b" -> 0))
    assert(exit.isFailure)
    val cause = exit.causeOption.get
    val failures = cause.failures
    assert(failures.exists(t => t.isInstanceOf[RuntimeException] && t.getMessage.contains("divide by zero")))
  }

  test("@Tool returning Try[Int] success returns the value") {
    val server = new FastMcpServer("EffectReturnTry", "0.1.0")
    server.scanAnnotations[EffectReturnTest.type]

    val result = runTool(server, "addTry", Map("a" -> 2, "b" -> 5))
    assert(result == 7)
  }

  test("@Tool returning Try[Int] failure surfaces the Throwable") {
    val server = new FastMcpServer("EffectReturnTryFail", "0.1.0")
    server.scanAnnotations[EffectReturnTest.type]

    val exit = runToolExit(server, "addTry", Map("a" -> -1, "b" -> 0))
    assert(exit.isFailure)
    val cause = exit.causeOption.get
    assert(cause.failures.exists(_.getMessage.contains("negative")))
  }

  test("@Tool returning Either[Throwable, Int] success returns the value") {
    val server = new FastMcpServer("EffectReturnEither", "0.1.0")
    server.scanAnnotations[EffectReturnTest.type]

    val result = runTool(server, "addEither", Map("a" -> 8, "b" -> 9))
    assert(result == 17)
  }

  test("@Tool returning Either[Throwable, Int] Left surfaces the Throwable") {
    val server = new FastMcpServer("EffectReturnEitherFail", "0.1.0")
    server.scanAnnotations[EffectReturnTest.type]

    val exit = runToolExit(server, "addEither", Map("a" -> -1, "b" -> -1))
    assert(exit.isFailure)
    val cause = exit.causeOption.get
    assert(cause.failures.exists(_.getMessage.contains("both negative")))
  }

  test("@Resource returning UIO[String] executes the effect") {
    val server = new FastMcpServer("EffectReturnResource", "0.1.0")
    server.scanAnnotations[EffectReturnTest.type]

    val result = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(server.resourceManager.readResource("greeting://zio", None))
        .getOrThrowFiberFailure()
    }
    assert(result == "hello-from-zio", s"Expected unwrapped string, got: $result")
  }

  test("@Prompt returning UIO[List[Message]] executes the effect") {
    val server = new FastMcpServer("EffectReturnPrompt", "0.1.0")
    server.scanAnnotations[EffectReturnTest.type]

    val result = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(server.promptManager.getPrompt("zio-prompt", Map("topic" -> "scala"), None))
        .getOrThrowFiberFailure()
    }
    val texts = result.map(_.content).collect { case t: TextContent => t.text }
    assert(texts.exists(_.contains("scala")), s"Expected message containing 'scala', got: $texts")
  }

object EffectReturnTest:

  @Tool(name = Some("addZio"), description = Some("Add via UIO"))
  def addZio(@Param("a") a: Int, @Param("b") b: Int): UIO[Int] = ZIO.succeed(a + b)

  @Tool(name = Some("shoutZio"), description = Some("Uppercase via Task"))
  def shoutZio(@Param("text") text: String): Task[String] = ZIO.attempt(text.toUpperCase)

  @Tool(name = Some("boomZio"), description = Some("Always fails"))
  def boomZio(): Task[Int] = ZIO.fail(new RuntimeException("kaboom"))

  @Tool(name = Some("divideIo"), description = Some("Divide with non-Throwable error"))
  def divideIo(@Param("a") a: Int, @Param("b") b: Int): IO[String, Int] =
    if b == 0 then ZIO.fail("divide by zero") else ZIO.succeed(a / b)

  @Tool(name = Some("addTry"), description = Some("Add via Try"))
  def addTry(@Param("a") a: Int, @Param("b") b: Int): Try[Int] =
    if a < 0 then Failure(new IllegalArgumentException("negative input"))
    else Success(a + b)

  @Tool(name = Some("addEither"), description = Some("Add via Either[Throwable, Int]"))
  def addEither(@Param("a") a: Int, @Param("b") b: Int): Either[Throwable, Int] =
    if a < 0 && b < 0 then Left(new IllegalArgumentException("both negative"))
    else Right(a + b)

  @Resource(uri = "greeting://zio", description = Some("Greeting via UIO"))
  def greetingZio(): UIO[String] = ZIO.succeed("hello-from-zio")

  @Prompt(name = Some("zio-prompt"), description = Some("Prompt via UIO"))
  def zioPrompt(@Param("topic") topic: String): UIO[List[Message]] =
    ZIO.succeed(List(Message(role = Role.User, content = TextContent(s"talk about $topic"))))
